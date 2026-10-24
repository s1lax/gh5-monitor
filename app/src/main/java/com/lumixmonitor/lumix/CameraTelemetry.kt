package com.lumixmonitor.lumix

data class CameraTelemetry(
    val observedAtMs: Long = 0,
    val status: Int? = null,
    val elapsedSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingImages: Long? = null,
    val battery: Int? = null,
    val actualAperture: Int? = null,
    val actualShutter: Int? = null,
    val actualIso: Int? = null,
    val meterThirds: Int? = null,
    val aeLocked: Boolean? = null,
    val afStatus: Int? = null,
    val afFrame: ByteArray? = null,
    val focalLength: Int? = null,
) {
    val recording: Boolean? get() = status?.let { it in listOf(1, 2, 9) }
    val afText: String get() = when (afStatus) {
        0, 1 -> "Фокус подтверждён"; 65534 -> "Фокусировка…"; 65535 -> "Фокус не подтверждён"
        2 -> "MF / AF не выполняется"; 3 -> "AF отменён"; 4 -> "Недостаточно света"; 5 -> "Слишком ярко"; else -> "AF —"
    }
    val statusText: String get() = when(status) {
        0 -> "Ожидание"; 1, 2, 9 -> "REC"; 3 -> "Просмотр"; 4 -> "Обработка"; 5 -> "Шумоподавление"; 6 -> "Меню камеры"; else -> "Статус —"
    }
}

object CameraTelemetryParser {
    fun parse(rec: ByteArray, exposure: ByteArray, battery: ByteArray, af: ByteArray = byteArrayOf(), lens: ByteArray = byteArrayOf()): CameraTelemetry {
        val r = PanasonicData.blocks(rec); val e = PanasonicData.blocks(exposure)
        val b = PanasonicData.blocks(battery); val a = PanasonicData.blocks(af); val l = PanasonicData.blocks(lens)
        fun u16(m: Map<Int, ByteArray>, key: Int) = m[key]?.let { PanasonicData.u16(it, 0) }
        fun u32(m: Map<Int, ByteArray>, key: Int) = m[key]?.let { PanasonicData.u32(it, 0) }
        val aperture = u16(e, 0x12000021)?.takeIf { it in 1..1000 }
        val shutter = u32(e, 0x12000022)?.toInt()?.takeUnless { it in listOf(-1,-2,-3,0,0x0ffffffd) }
        val iso = u32(e, 0x12000023)?.toInt()?.takeIf{it>0}?.and(0x0fffffff)?.takeIf{it in 1..1_000_000}
        val meter = u16(e, 0x12000024)?.takeIf { it !in listOf(0xfffd, 0xfffe, 0xffff) }
        return CameraTelemetry(
            status = u16(r, 0x12000013)?.takeIf { it in 0..11 },
            elapsedSeconds = u32(r, 0x12000014)?.takeIf { it < 0xfffffffdL },
            remainingSeconds = u32(r, 0x12000012)?.takeIf { it < 0xfffffffdL },
            remainingImages = u32(r, 0x12000011)?.takeIf { it < 0xfffffffdL },
            battery = u16(b, 0x16000011)?.takeIf { it in 0..100 },
            actualAperture = aperture, actualShutter = shutter, actualIso = iso,
            meterThirds = meter?.let(PanasonicData::signedMagnitude),
            aeLocked = u16(e, 0x12000027)?.takeIf { it in 0..1 }?.let { it == 1 },
            afStatus = u16(a, 0x01000011), afFrame = a[0x01000012],
            focalLength = u16(l, 0x120100a1)?.takeIf { it in 1..10000 },
        )
    }
}
