package com.lumixmonitor.lumix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lumixmonitor.ptp.PtpLog
import java.util.Locale

/**
 * Цикл получения кадров liveview.
 *
 * Повторяет правила, выверенные на практике в референсе:
 *  - **один кадр в полёте**: новый кадр не запрашивается, пока не пришёл предыдущий,
 *    иначе на медленном USB запросы накапливаются и поток разваливается;
 *  - **декодирование вне критического пути**: захват и декодирование идут в разных
 *    потоках, поэтому JPEG-декодирование перекрывается с передачей по USB;
 *  - **новейший кадр побеждает**: если декодирование отстаёт, старый неразобранный
 *    кадр выбрасывается, а не ставится в очередь;
 *  - **пауза при пустом кадре**: DeviceBusy — норма (камера занята автофокусом),
 *    поэтому цикл отступает, а не крутится вхолостую.
 */
class LiveviewLoop(
    private val camera: LumixCamera,
    private val onFrame: (Bitmap, LiveviewFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onStopped: () -> Unit = {},
    private val onStats: (String) -> Unit = {},
) {
    @Volatile
    private var running = false
    @Volatile private var captureFailed = false

    private var grabThread: Thread? = null
    private var decodeThread: Thread? = null
    private data class Captured(val frame: LiveviewFrame, val requestedNs: Long, val receivedNs: Long)
    private data class Decoded(val bitmap: Bitmap, val capture: Captured, val decodeNs: Long)
    private val slot = LatestSlot<Captured>()
    private val presentation = LatestPresentation<Decoded>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statsStartNs = SystemClock.elapsedRealtimeNanos()
    private var presentedFrames = 0
    private var requestTotalNs = 0L
    private var decodeTotalNs = 0L
    private var ageTotalNs = 0L
    private var lastPresentedNs = 0L
    private var maxGapNs = 0L
    private var maxRequestNs = 0L
    private var maxDecodeNs = 0L
    private var maxDeliveryNs = 0L
    private val presentLatest = Runnable {
        val decoded = presentation.take()
        if (running && decoded != null) {
            val now = SystemClock.elapsedRealtimeNanos()
            val capture = decoded.capture
            onFrame(decoded.bitmap, capture.frame)
            presentedFrames++
            requestTotalNs += capture.receivedNs - capture.requestedNs
            decodeTotalNs += decoded.decodeNs
            ageTotalNs += now - capture.requestedNs
            maxRequestNs = maxOf(maxRequestNs, capture.receivedNs - capture.requestedNs)
            maxDecodeNs = maxOf(maxDecodeNs, decoded.decodeNs)
            maxDeliveryNs = maxOf(maxDeliveryNs, now - capture.receivedNs - decoded.decodeNs)
            if (lastPresentedNs != 0L) maxGapNs = maxOf(maxGapNs, now - lastPresentedNs)
            lastPresentedNs = now
            val elapsed = now - statsStartNs
            if (elapsed >= 2_000_000_000L) {
                val count = presentedFrames.toDouble()
                val fps = count * 1_000_000_000.0 / elapsed
                val requestMs = requestTotalNs / count / 1_000_000.0
                val decodeMs = decodeTotalNs / count / 1_000_000.0
                val appMs = ageTotalNs / count / 1_000_000.0
                val text = String.format(Locale.US,
                    "%.1f FPS · запрос %.0f мс · JPEG %.0f мс · в приложении %.0f мс",
                    fps, requestMs, decodeMs, appMs)
                onStats(text)
                PtpLog.add(String.format(Locale.US,
                    "PERF fps=%.1f request_ms=%.1f jpeg_ms=%.1f app_ms=%.1f gap_max_ms=%.1f request_max_ms=%.1f jpeg_max_ms=%.1f delivery_max_ms=%.1f",
                    fps, requestMs, decodeMs, appMs, maxGapNs / 1e6,
                    maxRequestNs / 1e6, maxDecodeNs / 1e6, maxDeliveryNs / 1e6))
                statsStartNs = now
                presentedFrames = 0
                requestTotalNs = 0
                decodeTotalNs = 0
                ageTotalNs = 0
                maxGapNs = 0
                maxRequestNs = 0
                maxDecodeNs = 0
                maxDeliveryNs = 0
            }
        }
    }

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true

        grabThread = Thread({ grabLoop() }, "lumix-liveview-grab").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        decodeThread = Thread({ decodeLoop() }, "lumix-liveview-decode").apply {
            start()
        }
    }

    /**
     * Останавливает цикл.
     *
     * @param join ждать выхода потоков. Нужно перед сменой режима liveview: иначе
     *   незавершённое чтение кадра перехватит ответ камеры на следующую команду.
     *   С join нельзя вызывать из главного потока — заблокирует интерфейс.
     */
    fun stop(join: Boolean = false) {
        stopInternal(join, finishTransaction = false)
    }

    /** Finish the in-flight frame before camera StopLiveview / CloseSession. */
    fun stopGracefully(): Boolean = stopInternal(join = true, finishTransaction = true)

    private fun stopInternal(join: Boolean, finishTransaction: Boolean): Boolean {
        running = false
        slot.close()
        presentation.clear()
        mainHandler.removeCallbacks(presentLatest)
        val previousGrab = grabThread
        if (!finishTransaction) previousGrab?.interrupt()
        decodeThread?.interrupt()
        if (join) {
            try {
                previousGrab?.join(STOP_JOIN_MS)
            } catch (_: InterruptedException) {
                // штатная остановка
            }
            try {
                decodeThread?.join(STOP_JOIN_MS)
            } catch (_: InterruptedException) {
                // штатная остановка
            }
        }
        grabThread = null
        decodeThread = null
        return previousGrab?.isAlive != true && !captureFailed
    }

    private fun grabLoop() {
        var emptyStreak = 0
        var lastFrameMs = SystemClock.elapsedRealtime()
        var lastRestartMs = lastFrameMs
        try {
            while (running) {
                val requestedNs = SystemClock.elapsedRealtimeNanos()
                val frame = try {
                    camera.getLiveviewImage()
                } catch (t: Throwable) {
                    captureFailed = true
                    if (running) {
                        Log.w(TAG, "ошибка получения кадра", t)
                        postError(t)
                    }
                    break
                }

                if (frame != null) {
                    emptyStreak = 0
                    lastFrameMs = SystemClock.elapsedRealtime()
                    slot.offer(Captured(frame, requestedNs, SystemClock.elapsedRealtimeNanos()))
                    // Паузы нет: тянем так быстро, как позволяет USB.
                } else {
                    emptyStreak += 1
                    // Долгая тишина — камера перестала отдавать кадры.
                    // Просим передачу заново, иначе картинка замирает навсегда.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameMs >= 3_000 && now - lastRestartMs >= 3_000) {
                        lastRestartMs = now
                        Log.w(TAG, "кадры не приходят — перезапрашиваю liveview")
                        try {
                            camera.startLiveview()
                        } catch (t: Throwable) {
                            Log.w(TAG, "не удалось перезапустить liveview", t)
                        }
                    }
                    // Камера занята — отступаем, чтобы не забивать шину.
                    Thread.sleep((8L shl (emptyStreak - 1).coerceAtMost(3)).coerceAtMost(50L))
                }
            }
        } catch (_: InterruptedException) {
            // штатная остановка
        } finally {
            running = false
            slot.close()
            mainHandler.post { onStopped() }
        }
    }

    private fun decodeLoop() {
        try {
            while (running) {
                val capture = slot.take() ?: break
                val frame = capture.frame
                val decodeStart = SystemClock.elapsedRealtimeNanos()
                val bitmap = try {
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                } catch (t: Throwable) {
                    Log.w(TAG, "кадр не декодировался", t)
                    null
                }

                if (bitmap != null && running) {
                    val decoded = Decoded(bitmap, capture, SystemClock.elapsedRealtimeNanos() - decodeStart)
                    if (presentation.offer(decoded)) mainHandler.post(presentLatest)
                }
            }
        } catch (_: InterruptedException) {
            // штатная остановка
        }
    }

    private fun postError(t: Throwable) {
        mainHandler.post { onError(t) }
    }

    companion object {
        private const val TAG = "LiveviewLoop"

        /** Сколько ждать выхода потоков при остановке с join. */
        private const val STOP_JOIN_MS = 2_500L

    }
}

/**
 * Ячейка на один элемент, где новый элемент вытесняет старый.
 * Нужна, чтобы декодировщик всегда получал самый свежий кадр и не копил очередь.
 */
private class LatestSlot<T> {
    private val lock = java.lang.Object()
    private var value: T? = null
    private var closed = false

    fun offer(item: T) {
        synchronized(lock) {
            value = item
            lock.notifyAll()
        }
    }

    /** @return элемент или null, если ячейка закрыта и пуста. */
    fun take(): T? {
        synchronized(lock) {
            while (value == null && !closed) {
                lock.wait()
            }
            val result = value
            value = null
            return result
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
