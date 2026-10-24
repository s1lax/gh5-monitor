package com.lumixmonitor.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock

/** Ответ PTP: код и параметры. */
class PtpResponse(
    val resCode: Int,
    val parameters: IntArray = IntArray(0),
) {
    val isOk: Boolean get() = resCode == PtpResponseCode.OK
}

/** Ответ PTP вместе с данными (фаза Data). */
class PtpDataResponse(
    val resCode: Int,
    val parameters: IntArray,
    val data: ByteArray,
)

/**
 * Транспорт PTP поверх USB bulk-эндпоинтов.
 *
 * Реализует последовательность инициализации, без которой камера не отвечает:
 * сброс автомата PTP запросом Device Reset Request, снятие halt с эндпоинтов,
 * чтение событий с interrupt-эндпоинта, проверку transaction ID с
 * ресинхронизацией и выравнивание запросов чтения до размера пакета.
 *
 * Все транзакции сериализованы: PTP не допускает перемешивания обмена.
 */
class PtpUsbTransport(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val endpointEvent: UsbEndpoint?,
) {
    private val ioLock = ReentrantLock(true)
    private var transactionCounter = 0

    @Volatile
    private var closed = false

    @Volatile private var transactionFailed = false
    /** An interrupted data phase cannot be followed by another PTP command. */
    val isSynchronized: Boolean get() = !transactionFailed

    private fun transactionFailed(t: Throwable) {
        if (!closed && !transactionFailed) {
            transactionFailed = true
            PtpLog.add("PTP: незавершённая транзакция; новые команды заблокированы (${t.message})")
        }
    }

    /** Буфер для чтения событий камеры. */
    private val eventBuffer = ByteArray(maxOf(endpointEvent?.maxPacketSize ?: 0, 1024))
    private val bulkReader = PtpBulkReader(
        packetSize = endpointIn.maxPacketSize,
        transfer = { buffer, requested, timeout ->
            ensureOpen()
            connection.bulkTransfer(endpointIn, buffer, 0, requested, timeout)
        },
        onWait = { PtpLog.add(it) },
    )

    /** Поток, читающий события камеры с interrupt-эндпоинта. */
    private var eventThread: Thread? = null

    /**
     * Подробная запись обмена. Кадры liveview идут десятками в секунду, и
     * писать про каждый — значит забить журнал и нагрузить ввод-вывод.
     */
    private var verboseTransactions = true

    private var liveviewFrames = 0

    /** Слушатель асинхронных событий камеры. */
    var eventListener: ((PtpContainer) -> Unit)? = null

    val isOpen: Boolean get() = !closed

    // ------------------------------------------------------------- инициализация

    /**
     * Инициализация USB-уровня. Вызывать после claimInterface и до первой команды.
     */
    fun setup() {
        logDeviceDescriptor()

        if (MINIMAL_SETUP) {
            // v3: без Device Reset Request (0x66) и без clearHalt — они мешали
            // свежему подключению. Поток событий ВКЛЮЧЁН: камеру нужно читать,
            // иначе её interrupt-буфер заполняется и liveview замирает.
            PtpLog.add("минимальный путь: без 0x66, без clearHalt, поток событий включён")
            if (endpointEvent == null) {
                PtpLog.add("interrupt-эндпоинт отсутствует: события камеры недоступны")
            } else {
                startEventReader()
            }
            settle(SETTLE_AFTER_OPEN_MS, "после открытия камеры")
            return
        }

        // "Device Reset Request" (bRequest 0x66) — сброс PTP-автомата камеры.
        //
        // Рабочий референс делает его после usb.reset() (полной пересборки
        // USB-устройства), которой в Android нет. Ранее он был убран по
        // ошибочному подозрению, что «вешает» камеру; на деле отказы вызывали
        // неверный номер транзакции и команда до открытия сессии. Без 0x66
        // камера, прошедшая через несколько неудачных сессий, остаётся с
        // «грязным» состоянием PTP-автомата: принимает команду OpenSession, но
        // не отвечает на неё (при этом шлёт на interrupt heartbeat 0xC101).
        val resetResult = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE,
            DEVICE_RESET_REQUEST,
            0,
            usbInterface.id,
            null,
            0,
            CONTROL_TIMEOUT_MS,
        )
        PtpLog.add("Device Reset Request (0x66) -> $resetResult")

        // Снятие halt с эндпоинтов: лечит застрявший эндпоинт после прошлой
        // неудачной сессии.
        clearHalt(endpointOut.address, "OUT")
        clearHalt(endpointIn.address, "IN")

        // Поток событий запускается до паузы и до первой команды: камера шлёт
        // события сразу после захвата интерфейса, и их надо забирать непрерывно,
        // иначе буфер interrupt-эндпоинта забивается и камера перестаёт отвечать.
        if (endpointEvent == null) {
            PtpLog.add("interrupt-эндпоинт отсутствует: события камеры недоступны")
        } else if (EVENT_READER_ENABLED) {
            startEventReader()
        } else {
            PtpLog.add("чтение событий отключено (эксперимент: проверяем конкуренцию с bulk-IN)")
        }

        // Пауза после сброса: камере нужно время, чтобы снова начать слушать
        // команды. Заменяет собой usb.reset() из референса, которого в Android
        // нет.
        settle(SETTLE_AFTER_OPEN_MS, "после сброса камеры")
    }

    /**
     * Снимает halt с эндпоинта.
     *
     * В Android нет публичного clearHalt, поэтому используется стандартный
     * control-запрос CLEAR_FEATURE(ENDPOINT_HALT): requestType 0x02 —
     * host-to-device, standard, получатель endpoint; request 0x01; value 0;
     * index — адрес эндпоинта.
     */
    private fun clearHalt(endpointAddress: Int, label: String) {
        val result = connection.controlTransfer(
            REQUEST_TYPE_STANDARD_ENDPOINT,
            CLEAR_FEATURE,
            FEATURE_ENDPOINT_HALT,
            endpointAddress,
            null,
            0,
            CONTROL_TIMEOUT_MS,
        )
        PtpLog.add("clearHalt($label 0x%02X) -> %d".format(endpointAddress, result))
    }

    /**
     * Пауза, которую нужно выдержать, прежде чем камера снова примет команду.
     * Прерывание — штатный выход при закрытии, а не ошибка.
     */
    private fun settle(millis: Long, what: String) {
        PtpLog.add("пауза $millis мс $what")
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun logDeviceDescriptor() {        PtpLog.add(
            "USB устройство: VID=0x%04X PID=0x%04X классов=%d"
                .format(device.vendorId, device.productId, device.interfaceCount),
        )
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            PtpLog.add(
                "  интерфейс $i: class=${iface.interfaceClass} " +
                    "subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} " +
                    "эндпоинтов=${iface.endpointCount}",
            )
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                PtpLog.add(
                    "    эндпоинт 0x%02X тип=%d направление=%d maxPacket=%d"
                        .format(ep.address, ep.type, ep.direction, ep.maxPacketSize),
                )
            }
        }
        PtpLog.add(
            "выбраны: IN=0x%02X OUT=0x%02X EVENT=%s".format(
                endpointIn.address,
                endpointOut.address,
                endpointEvent?.let { "0x%02X".format(it.address) } ?: "нет",
            ),
        )
    }

    /**
     * Запускает фоновый поток, читающий события камеры с interrupt-эндпоинта.
     *
     * Это обязательная часть обмена, а не удобство: Lumix S5 требует, чтобы
     * interrupt-канал постоянно опрашивался. Пока он читается, камера отвечает
     * на bulk-команды; стоит перестать — буфер её событий забивается, и камера
     * замолкает (bulk-IN не отвечает, bulk-OUT начинает NAK-ить).
     *
     * Ранее чтение в отдельном потоке ошибочно считалось источником «голодания»
     * обмена; на деле тот отказ был из-за неверного номера транзакции и команды,
     * посланной до открытия сессии. Возвращено по итогам сверки с рабочим
     * референсом, который запускает listenInterruptIn() сразу после
     * claimInterface и до первой команды.
     */
    private fun startEventReader() {
        val endpoint = endpointEvent ?: return

        eventThread = Thread({
            // Всё тело потока под try/catch: необработанное исключение в фоновом
            // потоке убивает процесс целиком, а этого допускать нельзя.
            try {
                PtpLog.add("поток событий запущен")
                while (!closed) {
                    val read = try {
                        connection.bulkTransfer(endpoint, eventBuffer, eventBuffer.size, EVENT_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        if (!closed) PtpLog.add("событие: ошибка ${t.message}")
                        break
                    }
                    if (read < PtpContainer.HEADER_SIZE) {
                        if (read < 0) {
                            // Пусто — короткая пауза, чтобы не крутиться вхолостую.
                            // Прерывание приходит при закрытии — это штатный выход.
                            try {
                                Thread.sleep(EVENT_POLL_PAUSE_MS)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                        continue
                    }
                    try {
                        val header = PtpContainer.parseHeader(eventBuffer)
                        val payloadLength = (header.length - PtpContainer.HEADER_SIZE)
                            .coerceIn(0, eventBuffer.size - PtpContainer.HEADER_SIZE)
                        val payload = eventBuffer.copyOfRange(
                            PtpContainer.HEADER_SIZE,
                            PtpContainer.HEADER_SIZE + payloadLength,
                        )
                        val container = PtpContainer(
                            header.type, header.code, header.transactionId, payload,
                        )
                        if (container.code != 0xC107 && container.code != 0xC102) {
                            PtpLog.add("событие 0x%04X tid=%d".format(container.code, container.transactionId))
                        }
                        eventListener?.invoke(container)
                    } catch (t: Throwable) {
                        PtpLog.add("событие: не разобрано (${t.message})")
                    }
                }
            } catch (t: Throwable) {
                PtpLog.add("поток событий завершился: $t")
            }
            PtpLog.add("поток событий остановлен")
        }, "lumix-ptp-events").apply { isDaemon = true }
        eventThread?.start()
    }

    /**
     * Закрывает соединение.
     *
     * Сначала запрещаем новые транзакции. Затем ждём завершения текущего
     * bulkTransfer под ioLock и потока событий, и только потом освобождаем USB.
     * Флаг closed также запрещает повторы записи в сломанный канал.
     */
    fun close() {
        if (closed) return
        closed = true

        ioLock.lock()
        try {
            val thread = eventThread
            try {
                thread?.interrupt()
            } catch (_: Throwable) {
                // поток уже завершён
            }
            if (thread != null && thread !== Thread.currentThread()) {
                try {
                    thread.join(EVENT_THREAD_JOIN_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            try {
                connection.releaseInterface(usbInterface)
            } finally {
                connection.close()
            }
        } finally {
            ioLock.unlock()
        }
    }

    // ----------------------------------------------------------------- операции

    /** Команда без данных: Command -> Response. */
    fun sendCommand(
        opcode: Int,
        parameters: IntArray = IntArray(0),
        expected: IntArray = intArrayOf(PtpResponseCode.OK),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readAttempts: Int = BULK_IN_READ_ATTEMPTS,
    ): PtpResponse {
        ioLock.lock()
        var phaseIncomplete = false
        try {
            verboseTransactions = opcode != LIVEVIEW_IMAGE_OPCODE && opcode != 0x9108
            var attempt = 0
            while (true) {
                attempt++
                val tid = nextTransactionId()
                phaseIncomplete = true
                writeCommand(opcode, tid, parameters, timeoutMs)
                val container = waitBulkIn(tid, COMMAND_MAX_BYTES, timeoutMs, readAttempts)

                if (container.type != PtpContainerType.RESPONSE) {
                    throw PtpException(
                        "ожидался ответ, получен тип ${container.type}",
                    )
                }
                phaseIncomplete = false
                if (retryIfBusy(container.code, expected, attempt, opcode)) continue

                return PtpResponse(container.code, container.parameters())
            }
        } catch (t: Throwable) {
            if (phaseIncomplete) transactionFailed(t)
            throw t
        } finally {
            ioLock.unlock()
        }
    }

    /** Команда с исходящими данными: Command -> Data -> Response. */
    fun sendData(
        opcode: Int,
        parameters: IntArray,
        payload: ByteArray,
        expected: IntArray = intArrayOf(PtpResponseCode.OK),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readAttempts: Int = BULK_IN_READ_ATTEMPTS,
    ): PtpResponse {
        ioLock.lock()
        var phaseIncomplete = false
        try {
            verboseTransactions = opcode != LIVEVIEW_IMAGE_OPCODE && opcode != 0x9108
            var attempt = 0
            while (true) {
                attempt++
                val tid = nextTransactionId()
                phaseIncomplete = true
                writeCommand(opcode, tid, parameters, timeoutMs)
                writeData(opcode, tid, payload, timeoutMs)
                val container = waitBulkIn(tid, COMMAND_MAX_BYTES, timeoutMs, readAttempts)

                if (container.type != PtpContainerType.RESPONSE) {
                    throw PtpException("ожидался ответ, получен тип ${container.type}")
                }
                phaseIncomplete = false
                if (retryIfBusy(container.code, expected, attempt, opcode)) continue

                return PtpResponse(container.code, container.parameters())
            }
        } catch (t: Throwable) {
            if (phaseIncomplete) transactionFailed(t)
            throw t
        } finally {
            ioLock.unlock()
        }
    }

    /** Команда, возвращающая данные: Command -> Data -> Response. */
    fun receiveData(
        opcode: Int,
        parameters: IntArray = IntArray(0),
        expected: IntArray = intArrayOf(PtpResponseCode.OK),
        maxBytes: Int = DEFAULT_MAX_DATA_BYTES,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readAttempts: Int = BULK_IN_READ_ATTEMPTS,
    ): PtpDataResponse {
        val queuedNs = System.nanoTime()
        ioLock.lock()
        val acquiredNs = System.nanoTime()
        var writeNs = 0L
        var dataNs = 0L
        var responseNs = 0L
        var dataBytes = 0
        var responseCode = 0
        var phaseIncomplete = false
        try {
            verboseTransactions = opcode != LIVEVIEW_IMAGE_OPCODE && opcode != 0x9108
            var attempt = 0
            while (true) {
                attempt++
                val tid = nextTransactionId()
                val writeStart = System.nanoTime()
                phaseIncomplete = true
                writeCommand(opcode, tid, parameters, timeoutMs)
                val dataStart = System.nanoTime()
                writeNs += dataStart - writeStart
                val first = waitBulkIn(tid, maxBytes, timeoutMs, readAttempts)
                dataNs += System.nanoTime() - dataStart
                dataBytes = first.payload.size
                responseCode = first.code

                // Камера может ответить сразу, без фазы данных.
                if (first.type == PtpContainerType.RESPONSE) {
                    phaseIncomplete = false
                    if (retryIfBusy(first.code, expected, attempt, opcode)) continue
                    return PtpDataResponse(first.code, first.parameters(), ByteArray(0))
                }

                if (first.type != PtpContainerType.DATA) {
                    throw PtpException("ожидались данные, получен тип ${first.type}")
                }

                val responseStart = System.nanoTime()
                val second = waitBulkIn(tid, COMMAND_MAX_BYTES, timeoutMs, readAttempts)
                responseNs += System.nanoTime() - responseStart
                responseCode = second.code
                if (second.type != PtpContainerType.RESPONSE) {
                    throw PtpException("ожидался ответ после данных, получен ${second.type}")
                }
                phaseIncomplete = false
                if (retryIfBusy(second.code, expected, attempt, opcode)) continue

                if (opcode == LIVEVIEW_IMAGE_OPCODE && second.code == PtpResponseCode.OK) {
                    liveviewFrames++
                    if (liveviewFrames == 1) {
                        PtpLog.add("первый кадр liveview: ${first.payload.size} байт")
                    } else if (liveviewFrames % 100 == 0) {
                        PtpLog.add("liveview: $liveviewFrames кадров")
                    }
                }
                return PtpDataResponse(second.code, second.parameters(), first.payload)
            }
        } catch (t: Throwable) {
            if (phaseIncomplete) transactionFailed(t)
            throw t
        } finally {
            ioLock.unlock()
            val elapsedNs = System.nanoTime() - queuedNs
            if (elapsedNs >= 100_000_000L) {
                PtpLog.add(java.util.Locale.US.let { locale -> String.format(locale,
                    "PTP SLOW op=0x%04X param=0x%08X total_ms=%.1f lock_ms=%.1f write_ms=%.1f data_ms=%.1f response_ms=%.1f bytes=%d result=0x%04X",
                    opcode, parameters.firstOrNull() ?: 0, elapsedNs / 1e6,
                    (acquiredNs - queuedNs) / 1e6, writeNs / 1e6, dataNs / 1e6,
                    responseNs / 1e6, dataBytes, responseCode) })
            }
        }
    }

    /**
     * Камера занята — это штатный ответ, а не ошибка: она в этот момент
     * может выполнять автофокус или запись. Ждём и повторяем.
     * @return true, если нужно повторить транзакцию.
     */
    private fun retryIfBusy(
        resCode: Int,
        expected: IntArray,
        attempt: Int,
        opcode: Int,
    ): Boolean {
        if (resCode != PtpResponseCode.DEVICE_BUSY) return false
        if (expected.contains(PtpResponseCode.DEVICE_BUSY)) return false
        if (attempt >= TRY_COUNT) {
            throw PtpException(
                "камера занята: ${TRY_COUNT} попыток для opcode 0x%04X".format(opcode),
            )
        }
        Thread.sleep(TRY_INTERVAL_MS)
        return true
    }

    // -------------------------------------------------------------- обмен

    private fun nextTransactionId(): Int {
        transactionCounter += 1
        // 0xFFFFFFFE — максимальный допустимый номер транзакции PTP
        // (0xFFFFFFFF зарезервирован). Сравнение идёт в Long: 0xFFFFFFFE как Int
        // отрицательный, поэтому прежняя проверка
        // «transactionCounter > 0xFFFFFFFE.toInt()» была истинна при каждом
        // вызове и прибивала номер транзакции к 1. Из-за этого все команды
        // уходили с tid=1, а камера, получив команду с уже использованным
        // номером, перестаёт принимать данные.
        if (transactionCounter.toLong() > 0xFFFFFFFEL) {
            transactionCounter = 1
        }
        return transactionCounter
    }

    private fun writeFully(data: ByteArray, timeoutMs: Int) {
        try {
            writeFullyOnce(data, timeoutMs)
            return
        } catch (e: PtpException) {
            if (closed) throw e
            // Эндпоинт мог остаться в состоянии halt от предыдущей неудачной
            // операции: тогда камера не подтверждает приём и bulkTransfer
            // уходит в таймаут. CLEAR_FEATURE снимает halt, после чего запись
            // имеет смысл повторить один раз.
            PtpLog.add("запись: первая попытка не удалась (${e.message}); снимаю halt и повторяю")
            clearHalt(endpointOut.address, "OUT")
        }
        writeFullyOnce(data, timeoutMs)
    }

    private fun writeFullyOnce(data: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < data.size) {
            ensureOpen()
            val written = connection.bulkTransfer(
                endpointOut, data, offset, data.size - offset, timeoutMs,
            )
            if (written <= 0) {
                PtpLog.add("запись: прервана на $offset из ${data.size} (код $written)")
                throw PtpException("запись прервана на $offset из ${data.size} (код $written)")
            }
            offset += written
        }
        if (verboseTransactions) PtpLog.add("запись: отправлено ${data.size} байт")
    }

    private fun writeCommand(opcode: Int, tid: Int, parameters: IntArray, timeoutMs: Int) {
        ensureOpen()
        if (verboseTransactions) {
            PtpLog.add(
                "> команда 0x%04X tid=%d параметров=%d".format(opcode, tid, parameters.size),
            )
        }
        writeFully(PtpContainer.buildCommand(opcode, tid, parameters), timeoutMs)
    }

    private fun writeData(opcode: Int, tid: Int, payload: ByteArray, timeoutMs: Int) {
        PtpLog.add("> данные 0x%04X tid=%d байт=%d".format(opcode, tid, payload.size))
        writeFully(PtpContainer.buildData(opcode, tid, payload), timeoutMs)
    }

    /**
     * Читает контейнер, ожидая конкретный transaction ID.
     *
     * Чужой ID означает рассинхронизацию потока (например, остатки прерванной
     * транзакции). Такие контейнеры отбрасываются, при накоплении — снимается
     * halt, чтобы восстановить канал, а не падать сразу.
     */
    private fun waitBulkIn(
        expectedTid: Int,
        maxBytes: Int,
        timeoutMs: Int,
        attempts: Int,
    ): PtpContainer {
        var mismatches = 0
        while (true) {
            ensureOpen()
            val container = readContainer(maxBytes, timeoutMs, attempts)

            if (container.transactionId == expectedTid) {
                if (verboseTransactions) {
                    PtpLog.add(
                        "< тип=%d код=0x%04X tid=%d байт=%d".format(
                            container.type, container.code, container.transactionId,
                            container.payload.size,
                        ),
                    )
                }
                return container
            }

            mismatches++
            PtpLog.add(
                "! чужой tid=%d (ожидался %d), тип=%d код=0x%04X — пропуск %d".format(
                    container.transactionId, expectedTid, container.type,
                    container.code, mismatches,
                ),
            )

            // Событие, пришедшее в общий канал, всё равно доставляем слушателю.
            if (container.type == PtpContainerType.EVENT) {
                try {
                    eventListener?.invoke(container)
                } catch (_: Throwable) {
                    // обработчик события не должен ломать обмен
                }
            }

            if (mismatches >= RESYNC_MAX_SKIPS) {
                throw PtpException(
                    "не удалось синхронизировать канал: $mismatches чужих контейнеров",
                )
            }
            if (mismatches % RESYNC_CLEAR_HALT_EVERY == 0) {
                clearHalt(endpointIn.address, "IN")
            }
        }
    }

    /**
     * Читает один контейнер целиком.
     *
     * Запросы выровнены до размера пакета: невыровненный последний пакет
     * может привести к ошибке передачи.
     */
    private fun readContainer(maxBytes: Int, timeoutMs: Int, attempts: Int): PtpContainer =
        bulkReader.readContainer(maxBytes, timeoutMs, attempts)

    private fun ensureOpen() {
        if (closed) throw PtpException("USB-соединение закрыто")
        if (transactionFailed) throw PtpException("PTP-транзакция прервана; требуется переподключить USB")
    }

    /** Округление вверх до размера пакета. */
    private fun align(byteLength: Int): Int {
        if (byteLength <= 0) return BULK_IN_ALIGNMENT
        return ((byteLength + BULK_IN_ALIGNMENT - 1) / BULK_IN_ALIGNMENT) * BULK_IN_ALIGNMENT
    }

    companion object {
        private const val TAG = "PtpUsbTransport"

        const val DEFAULT_TIMEOUT_MS = 5_000
        const val CONTROL_TIMEOUT_MS = 2_000

        /**
         * Минимальный путь инициализации (эксперимент v2): пропустить 0x66,
         * clearHalt и поток событий — как это делает Windows. Проверяем, не эти
         * ли шаги мешают камере ответить на OpenSession.
         */
        private const val MINIMAL_SETUP = true

        /**
         * Включать ли фоновый поток чтения событий.
         *
         * true — как в рабочем референсе (interrupt-канал читается непрерывно).
         * Отключение оставлено для экспериментов на MediaTek-хостах.
         */
        private const val EVENT_READER_ENABLED = true

        /**
         * Android 13 bulkTransfer pins its Java byte array across the blocking ioctl.
         * A long interrupt read can therefore hold up GC and the next frame command.
         * Poll briefly, then leave an unpinned gap (EVENT_POLL_PAUSE_MS).
         * Keep draining events: disabling the endpoint can stall the camera itself.
         */
        private const val EVENT_TIMEOUT_MS = 10

        /** Пауза между попытками чтения, когда событий нет. */
        private const val EVENT_POLL_PAUSE_MS = 20L

        /** Ожидание выхода потока событий при закрытии: больше EVENT_TIMEOUT_MS. */
        private const val EVENT_THREAD_JOIN_MS = 1_500L

        /** Верхняя граница данных одного ответа. */
        const val DEFAULT_MAX_DATA_BYTES = 10_000_000
        private const val COMMAND_MAX_BYTES = 12 + 4 * 5

        private const val TRY_COUNT = 30
        private const val TRY_INTERVAL_MS = 100L
        private const val RESYNC_MAX_SKIPS = 8
        private const val RESYNC_CLEAR_HALT_EVERY = 4

        private const val BULK_IN_FIRST_REQUEST = 16 * 1024

        /**
         * Сколько раз повторять первое чтение ответа. Камера после сброса
         * PTP-автомата отвечает не всегда с первой попытки; если она молчит
         * совсем, каждая попытка оставляет строку в журнале.
         */
        private const val BULK_IN_READ_ATTEMPTS = 2

        /**
         * Пауза перед первой командой после открытия интерфейса. Заменяет собой
         * usb.reset() из рабочего референса, которого в Android нет.
         */
        private const val SETTLE_AFTER_OPEN_MS = 1_000L

        /** Кратно размерам пакета bulk для FS/HS/SS (64/512/1024). */
        private const val BULK_IN_ALIGNMENT = 1024

        /** Opcode кадра liveview: их десятки в секунду, логировать каждый нельзя. */
        private const val LIVEVIEW_IMAGE_OPCODE = 0x9706

        // Управляющие запросы USB.
        private const val REQUEST_TYPE_CLASS_INTERFACE = 0x21
        private const val REQUEST_TYPE_STANDARD_ENDPOINT = 0x02
        private const val DEVICE_RESET_REQUEST = 0x66
        private const val CLEAR_FEATURE = 0x01
        private const val FEATURE_ENDPOINT_HALT = 0x0000

        // Интерфейс PTP: класс Still Image, подкласс PTP, протокол PTP.
        const val USB_CLASS_STILL_IMAGE = 6
        const val USB_SUBCLASS_PTP = 1
        const val USB_PROTOCOL_PTP = 1

        fun hex(value: Int, digits: Int = 4): String = "0x%0${digits}X".format(value)
    }
}
