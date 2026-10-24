package com.lumixmonitor.monitor

import java.io.Reader

/** A bounded SDR .cube parser. Red varies fastest, then green, then blue. */
data class CubeLut(val title: String, val size: Int, val domainMin: FloatArray,
    val domainMax: FloatArray, val rgb: FloatArray) {
    fun atlas(): ByteArray {
        val result = ByteArray(size * size * size * 4)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val source = ((b * size + g) * size + r) * 3
            val target = (g * size * size + b * size + r) * 4
            for (c in 0..2) result[target+c] = (rgb[source+c].coerceIn(0f,1f)*255f+0.5f).toInt().toByte()
            result[target+3] = 255.toByte()
        }
        return result
    }

    companion object {
        fun parse(reader: Reader, name: String = "LUT"): CubeLut {
            var title = name
            var size = 0
            var values: FloatArray? = null
            var at = 0
            var characters = 0
            var min = floatArrayOf(0f,0f,0f)
            var max = floatArrayOf(1f,1f,1f)
            reader.buffered().forEachLine { raw ->
                characters += raw.length
                require(characters <= 16_000_000 && raw.length <= 4096) { "Слишком большой LUT" }
                val line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
                if (line.isEmpty()) return@forEachLine
                val parts = line.split(Regex("\\s+"))
                fun triple(): FloatArray {
                    require(parts.size == 4) { "Неверный DOMAIN" }
                    return FloatArray(3) { parts[it+1].toFloat().also { v -> require(v.isFinite()) } }
                }
                when (parts[0]) {
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"').take(80)
                    "LUT_1D_SIZE" -> error("Нужен 3D LUT, не 1D")
                    "LUT_3D_SIZE" -> {
                        require(size == 0 && parts.size == 2) { "Повторный размер LUT" }
                        size = parts[1].toInt()
                        require(size in 2..64) { "Поддерживается LUT от 2 до 64" }
                        values = FloatArray(size*size*size*3)
                    }
                    "DOMAIN_MIN" -> min = triple()
                    "DOMAIN_MAX" -> max = triple()
                    else -> {
                        val output = requireNotNull(values) { "Сначала должен быть LUT_3D_SIZE" }
                        require(parts.size == 3 && at+3 <= output.size) { "Неверное число значений LUT" }
                        for (part in parts) output[at++] = part.toFloat().also { require(it.isFinite()) { "Нечисловое значение LUT" } }
                    }
                }
            }
            val output = requireNotNull(values) { "Нет LUT_3D_SIZE" }
            require(at == output.size) { "LUT обрезан: $at из ${output.size}" }
            require((0..2).all { max[it] > min[it] }) { "Неверный диапазон DOMAIN" }
            return CubeLut(title,size,min,max,output)
        }
    }
}
