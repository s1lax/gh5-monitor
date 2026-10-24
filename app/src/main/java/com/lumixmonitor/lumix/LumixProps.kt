package com.lumixmonitor.lumix

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Вендорские opcodes Panasonic (не стандартные PTP).
 *
 * Источник: рабочий драйвер Panasonic S5 из проекта Tethr (MIT),
 * core/src/TethrPTPUSB/TethrPanasonic.ts. Подробности — в lumix-protocol-reference.md.
 */
object LumixOp {
    const val OPEN_SESSION = 0x9102
    const val CLOSE_SESSION = 0x9103
    const val GET_DEVICE_PROP_DESC = 0x9108
    const val GET_DEVICE_PROP_VALUE = 0x9402
    const val SET_DEVICE_PROP_VALUE = 0x9403
    const val INITIATE_CAPTURE = 0x9404
    const val CTRL_LIVEVIEW = 0x9405
    const val LIVEVIEW = 0x9412
    const val GET_LIVEVIEW_SETTINGS = 0x9414
    const val SET_LIVEVIEW_SETTINGS = 0x9415
    const val MANUAL_FOCUS_DRIVE = 0x9416
    const val LIVEVIEW_IMAGE = 0x9706
}

/** Параметры команд Panasonic. Значения проверены на рабочем референсе. */
object LumixParam {
    const val SESSION_ID = 0x00010001
    const val INITIATE_CAPTURE = 0x03000011
    const val AUTO_FOCUS = 0x03000024
    const val LIVEVIEW_START = 0x0d000010
    const val LIVEVIEW_STOP = 0x0d000011
    const val MANUAL_FOCUS_PROP = 0x03010011
    const val LIVEVIEW_TRANS_IMG = 0x0d800011
    const val LIVEVIEW_RECOM_IMG = 0x0d800012
}

/** События камеры. */
object LumixEvent {
    const val DEVICE_PROP_CHANGED = 0xC102
    const val OBJECT_ADDED = 0xC108
}

/**
 * Коды свойств устройства. Panasonic использует 32-битные коды,
 * а не стандартные PTP-свойства.
 */
object LumixProp {
    const val PHOTO_STYLE = 0x02000010
    const val PHOTO_STYLE_PARAM = 0x02000011

    const val ISO = 0x02000020
    const val ISO_PARAM = 0x02000021
    const val ISO_UPPER_LIMIT = 0x02000022

    const val SHUTTER_SPEED = 0x02000030
    const val SHUTTER_SPEED_PARAM = 0x02000031
    const val SHUTTER_SPEED_RANGE_LIMIT = 0x02000032

    const val APERTURE = 0x02000040
    const val APERTURE_PARAM = 0x02000041
    const val APERTURE_RANGE_LIMIT = 0x02000042

    const val WHITE_BALANCE = 0x02000050
    const val WHITE_BALANCE_PARAM = 0x02000051
    const val WHITE_BALANCE_K_SET = 0x02000052

    const val EXPOSURE = 0x02000060
    const val EXPOSURE_PARAM = 0x02000061

    const val AF_AREA = 0x02000070
    const val AF_AREA_MODE_PARAM = 0x02000071
    const val AF_AREA_AREA_PARAM = 0x02000072
    const val AF_AREA_QUICK_PARAM = 0x02000073
    const val CAMERA_MODE = 0x02000080
    const val CAMERA_MODE_DRIVE_MODE = 0x02000081
    const val CAMERA_MODE_MODE_POS = 0x02000082

    const val IMAGE_MODE = 0x020000A0
    const val IMAGE_MODE_PARAM = 0x020000A1
    const val IMAGE_MODE_QUALITY = 0x020000A2
    const val IMAGE_MODE_ASPECT = 0x020000A3
}

/**
 * Кодировки значений. Схемы неочевидны, поэтому вынесены отдельно
 * и покрыты тестами в LumixCodecTest.
 */
object LumixCodec {

    // ------------------------------------------------------------ выдержка

    /** Кодирует строку выдержки ("1/500", "2.5", "bulb", "auto") в 32-битное значение. */
    fun encodeShutterSpeed(text: String): Int? {
        return when (text) {
            "bulb" -> 0xFFFFFFFF.toInt()
            "auto" -> 0x0FFFFFFE
            else -> {
                if (text.startsWith("1/")) {
                    val denominator = text.removePrefix("1/").toDoubleOrNull() ?: return null
                    if (denominator <= 0.0) return null
                    (denominator * 1000.0).roundToInt()
                } else {
                    val seconds = text.toDoubleOrNull() ?: return null
                    if (seconds < 0.0) return null
                    (seconds * 1000.0).roundToInt() or 0x80000000.toInt()
                }
            }
        }
    }

    /**
     * Декодирует выдержку.
     * Старший бит 0 — дробь вида 1/N (значение = N * 1000).
     * Старший бит 1 — секунды (значение = секунды * 1000).
     */
    fun decodeShutterSpeed(value: Int): String? {
        return when (value) {
            0xFFFFFFFF.toInt() -> "bulb"
            0x0FFFFFFF -> "auto"
            0x0FFFFFFE -> null
            else -> {
                val negativeFlag = (value and 0x80000000.toInt()) != 0
                val magnitude = value and 0x7FFFFFFF
                val text = millisToText(magnitude)
                if (negativeFlag) text else "1/$text"
            }
        }
    }

    private fun millisToText(millis: Int): String {
        return if (millis % 1000 == 0) {
            (millis / 1000).toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", millis / 1000.0)
        }
    }

    // ------------------------------------------------------- экспокоррекция

    /**
     * Кодирует экспокоррекцию ("0", "+1 1/3", "-2/3") в 16 бит.
     * Младшие 4 бита — шаги по 1/3 EV, бит 0x8000 — знак минус.
     */
    fun encodeExposureComp(text: String): Int? {
        if (text == "0") return 0x0000

        var negative = false
        var whole = 0
        var thirds = 0

        val full = Regex("^([+-]?)(\\d+)( 1/3| 2/3)?$").find(text)
        val fractionOnly = Regex("^([+-]?)(1/3|2/3)$").find(text)

        when {
            full != null -> {
                negative = full.groupValues[1] == "-"
                whole = full.groupValues[2].toInt()
                thirds = when (full.groupValues[3]) {
                    " 1/3" -> 1
                    " 2/3" -> 2
                    else -> 0
                }
            }
            fractionOnly != null -> {
                negative = fractionOnly.groupValues[1] == "-"
                thirds = if (fractionOnly.groupValues[2] == "1/3") 1 else 2
            }
            else -> return null
        }

        val steps = whole * 3 + thirds
        if (steps > 0xF) return null
        return if (negative) 0x8000 or steps else steps
    }

    /** Декодирует экспокоррекцию в читаемую строку. */
    fun decodeExposureComp(value: Int): String {
        if (value == 0) return "0"
        val steps = value and 0xF
        val whole = steps / 3
        val thirds = steps % 3
        val negative = (value and 0x8000) != 0
        val sign = if (negative) "-" else "+"
        val thirdText = when (thirds) {
            1 -> "1/3"
            2 -> "2/3"
            else -> ""
        }
        return when {
            whole == 0 -> sign + thirdText
            thirds == 0 -> sign + whole
            else -> "$sign$whole $thirdText"
        }
    }

    // ------------------------------------------------------------ диафрагма

    /** Кодирует f-число: 0 = авто, иначе значение * 10. */
    fun encodeAperture(fNumber: Double): Int = (fNumber * 10.0).roundToInt()

    /** Декодирует f-число. */
    fun decodeAperture(value: Int): Double = value / 10.0
    fun apertureText(value:Int):String = when(value){0,0xfffe->"—";0xffff->"Auto";else->formatAperture(decodeAperture(value))}

    /** Форматирует диафрагму для интерфейса: 2.8 -> "f/2.8". */
    fun formatAperture(fNumber: Double): String {
        val text = if (abs(fNumber - fNumber.roundToInt()) < 0.001) {
            fNumber.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", fNumber)
        }
        return "f/$text"
    }

    // ------------------------------------------------------------------ ISO

    const val ISO_AUTO = 0xFFFFFFFF.toInt()
    const val ISO_AUTO_I = 0xFFFFFFFE.toInt()

    /** Декодирует ISO. */
    fun decodeIso(value: Int): String = when (value) {
        ISO_AUTO -> "auto"
        ISO_AUTO_I -> "auto (i-ISO)"
        -3 -> "—"
        else -> if(value and 0x10000000 != 0 && value>0)"L${value and 0x0fffffff}" else value.toString()
    }

    /** Кодирует ISO из строки. */
    fun encodeIso(text: String): Int? = when (text) {
        "auto" -> ISO_AUTO
        else -> text.toIntOrNull()
    }
}

/** Режим liveview, поддерживаемый камерой. */
data class LiveviewSetting(
    val width: Int,
    val height: Int,
    val frameSize: Int,
    val fps: Int,
) {
    /** Читаемая подпись для списка выбора. */
    val label: String get() = "${width}x$height @ ${fps}fps"

    /** Число пикселей — для выбора максимального режима. */
    val pixels: Int get() = width * height
}

/** Таблицы соответствия кодов и значений. */
object LumixTables {

    val exposureMode: List<Pair<Int, String>> = listOf(
        0 to "P", 1 to "A", 2 to "S", 3 to "M",
        5 to "video", 7 to "iA", 8 to "C1", 9 to "C2", 10 to "C3", 12 to "S&Q",
    )

    val whiteBalance: List<Pair<Int, String>> = listOf(
        0x0002 to "auto",
        0x0004 to "daylight",
        0x0005 to "fluorescent",
        0x0006 to "incandescent",
        0x0007 to "flash",
        0x8008 to "cloud",
        0x800F to "shade",
        0x800B to "WB 1",0x800C to "WB 2",0x800D to "WB 3",0x800E to "WB 4",
        0x8010 to "K1",
        0x8011 to "K2",
        0x8012 to "K3",
        0x8013 to "K4",
        0x8014 to "auto cool",
        0x8015 to "auto warm",
    )

    val photoStyle: List<Pair<Int, String>> = listOf(
        0 to "Standard",
        1 to "Vivid",
        2 to "Natural",
        3 to "Monochrome",
        4 to "Landscape",
        5 to "Portrait",
        6 to "Custom 1",7 to "Cinelike D",8 to "Cinelike V",9 to "V-Log L",
        10 to "Linear Gamma",11 to "Custom 2",12 to "Custom 3",13 to "Custom 4",
        14 to "Like709",
        15 to "L.Monochrome",
        16 to "HLG",
        17 to "L.Monochrome D",
        18 to "Flat",
        19 to "MY PHOTOSTYLE 1",
        20 to "MY PHOTOSTYLE 2",
        21 to "MY PHOTOSTYLE 3",
        22 to "MY PHOTOSTYLE 4",
        40 to "V-Log",
        41 to "Cinelike D2",
        42 to "Cinelike V2",
        44 to "L.Classic Neo",
        45 to "L.Monochrome S",
        49 to "Realtime LUT",
        50 to "LEICA Monochrome",
    )

    val imageQuality: List<Pair<Int, String>> = listOf(
        0 to "fine", 1 to "standard", 2 to "raw", 3 to "raw+fine", 4 to "raw+standard",
    )

    val imageAspect: List<Pair<Int, String>> = listOf(
        1 to "4:3", 2 to "3:2", 3 to "16:9", 4 to "1:1", 10 to "65:24", 11 to "2:1",
    )

    /**
     * Названия режимов и зон автофокуса: камера их не сообщает, поэтому
     * пока показываем номер варианта. Таблицы заполним по её кодам из журнала.
     */
    val afMode: List<Pair<Int, String>> = listOf(0 to "AF",1 to "AF Macro",2 to "AF Macro D",3 to "MF",4 to "AF-S",5 to "AF-C",6 to "AF-F")
    val afZone: List<Pair<Int, String>> = listOf(0 to "1 точка",1 to "Лицо / глаза",2 to "Слежение",3 to "Pinpoint",4 to "49 точек",5 to "Пользоват. зона",6 to "225 точек")

    fun nameOf(table: List<Pair<Int, String>>, code: Int): String? =
        table.firstOrNull { it.first == code }?.second

    fun codeOf(table: List<Pair<Int, String>>, name: String): Int? =
        table.firstOrNull { it.second == name }?.first
}
