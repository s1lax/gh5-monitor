package com.lumixmonitor.ptp

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал диагностики: в памяти и в файле.
 *
 * Нужен потому, что телефон занят камерой в единственном USB-порту, поэтому
 * подключить его к компьютеру для `adb logcat` одновременно нельзя. Журнал
 * показывается на экране, а также пишется в файл — тогда он переживает падение
 * приложения и его можно забрать через adb.
 */
object PtpLog {

    private const val MAX_ENTRIES = 600
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val entries = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    /** Подключает файл-приёмник. Вызывается один раз при старте приложения. */
    fun init(target: File) {
        try {
            target.parentFile?.mkdirs()
            if (target.exists() && target.length() > MAX_FILE_BYTES) {
                target.delete()
            }
            target.appendText("--- запуск ${Date()} ---\n")
        } catch (_: Throwable) {
            // отсутствие файла не должно мешать работе
        }
        file = target
    }

    @Synchronized
    fun add(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
        writeToFile(line)
    }

    private fun writeToFile(line: String) {
        val target = file ?: return
        try {
            if (target.length() > MAX_FILE_BYTES) {
                target.writeText("--- журнал усечён ---\n")
            }
            target.appendText(line + "\n")
        } catch (_: Throwable) {
            // запись журнала не должна ломать обмен с камерой
        }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Удобная запись в шестнадцатеричном виде. */
    fun hex(value: Int, digits: Int = 4): String = "0x%0${digits}X".format(value)
}
