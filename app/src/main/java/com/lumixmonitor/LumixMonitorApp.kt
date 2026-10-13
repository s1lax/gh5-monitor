package com.lumixmonitor

import android.app.Application
import android.os.Looper
import com.lumixmonitor.ptp.PtpLog
import java.io.File

/**
 * Точка входа приложения.
 *
 * Делает две вещи: подключает журнал диагностики к файлу (чтобы он пережил
 * падение) и ставит страховку от необработанных исключений в фоновых потоках.
 *
 * Страховка нужна потому, что Android убивает **весь процесс** при необработанном
 * исключении в любом потоке. Для приложения, которое держит USB-соединение с
 * камерой в фоновых потоках, это означает потерю монитора из-за единичного сбоя
 * обмена. Поэтому исключение в фоновом потоке записывается в журнал, а поток
 * просто завершается. Исключение в главном потоке по-прежнему уходит системному
 * обработчику — реальные ошибки интерфейса скрывать нельзя.
 */
class LumixMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        PtpLog.init(File(filesDir, "ptp.log"))

        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            PtpLog.add("НЕОБРАБОТАННОЕ ИСКЛЮЧЕНИЕ в потоке ${thread.name}: $throwable")
            throwable.stackTrace.take(12).forEach { frame -> PtpLog.add("    at $frame") }

            if (thread === Looper.getMainLooper().thread) {
                systemHandler?.uncaughtException(thread, throwable)
            }
        }

        PtpLog.add("приложение запущено")
    }
}
