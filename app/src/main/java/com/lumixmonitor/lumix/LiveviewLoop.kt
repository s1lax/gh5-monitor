package com.lumixmonitor.lumix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log

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
) {
    @Volatile
    private var running = false

    private var grabThread: Thread? = null
    private var decodeThread: Thread? = null
    private val slot = LatestSlot<LiveviewFrame>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (!running) return
        running = false
        slot.close()
        grabThread?.interrupt()
        decodeThread?.interrupt()
        if (join) {
            try {
                grabThread?.join(STOP_JOIN_MS)
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
    }

    private fun grabLoop() {
        var emptyStreak = 0
        try {
            while (running) {
                val frame = try {
                    camera.getLiveviewImage()
                } catch (t: Throwable) {
                    if (running) {
                        Log.w(TAG, "ошибка получения кадра", t)
                        postError(t)
                    }
                    break
                }

                if (frame != null) {
                    emptyStreak = 0
                    slot.offer(frame)
                    // Паузы нет: тянем так быстро, как позволяет USB.
                } else {
                    emptyStreak += 1
                    // Долгая тишина — камера перестала отдавать кадры.
                    // Просим передачу заново, иначе картинка замирает навсегда.
                    if (emptyStreak == STALL_FRAMES) {
                        Log.w(TAG, "кадры не приходят — перезапрашиваю liveview")
                        try {
                            camera.startLiveview()
                        } catch (t: Throwable) {
                            Log.w(TAG, "не удалось перезапустить liveview", t)
                        }
                    }
                    // Камера занята — отступаем, чтобы не забивать шину.
                    Thread.sleep(BACKOFF_MS)
                }
            }
        } catch (_: InterruptedException) {
            // штатная остановка
        } finally {
            slot.close()
            mainHandler.post { onStopped() }
        }
    }

    private fun decodeLoop() {
        try {
            while (true) {
                val frame = slot.take() ?: break
                val bitmap = try {
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                } catch (t: Throwable) {
                    Log.w(TAG, "кадр не декодировался", t)
                    null
                }

                if (bitmap != null) {
                    mainHandler.post { onFrame(bitmap, frame) }
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

        /** Пауза при пустом кадре: камера занята. */
        private const val BACKOFF_MS = 200L

        /** Сколько ждать выхода потоков при остановке с join. */
        private const val STOP_JOIN_MS = 2_500L

        /** Сколько пустых кадров подряд считаем зависанием передачи (~3 с). */
        private const val STALL_FRAMES = 15
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
