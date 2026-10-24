package com.lumixmonitor.lumix

/** Length-delimited Panasonic records. All lengths are checked before arithmetic/allocation. */
object PanasonicData {
    fun u16(b: ByteArray, p: Int): Int? = if (p >= 0 && p <= b.size - 2)
        (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8) else null
    fun u32(b: ByteArray, p: Int): Long? = if (p >= 0 && p <= b.size - 4)
        (0..3).fold(0L) { a, i -> a or ((b[p + i].toLong() and 255L) shl (8 * i)) } else null

    fun blocks(b: ByteArray): Map<Int, ByteArray> {
        val out = linkedMapOf<Int, ByteArray>()
        var at = 0
        while (at <= b.size - 8 && out.size < 256) {
            val id = u32(b, at)!!.toInt()
            val size = u32(b, at + 4)!!
            if (size > b.size - at - 8L) break
            val end = at + 8 + size.toInt()
            out[id] = b.copyOfRange(at + 8, end)
            at = end
        }
        return out
    }

    fun signedMagnitude(value: Int): Int = if (value and 0x8000 != 0) -(value and 0x7fff) else value
}

data class CameraProperty(val code: Int, val valueSize: Int, val current: Int, val choices: List<Int>, val verifiedGh5Range:Boolean=false)

object Gh5Compatibility {
    /** GH5 2.6 omits WB shift enums; ±9 and readback were verified on the physical camera. */
    fun settings(model:String?,properties:Map<Int,CameraProperty>):Map<Int,CameraProperty> {
        if(model !in listOf("DC-GH5","DMC-GH5","GH5"))return properties
        return properties.mapValues{(code,p)->
            if(code in listOf(0x02000053,0x02000054) && p.valueSize==2 && p.choices.isEmpty())
                p.copy(choices=(-9..9).map{if(it<0)0x8000 or -it else it},verifiedGh5Range=true) else p
        }
    }
}

/** Panasonic ListProperty's nested nodes, forms and their UINT8/16/32 types. */
object CameraPropertyParser {
    fun parse(b: ByteArray): Map<Int, CameraProperty> {
        val out = linkedMapOf<Int, CameraProperty>()
        fun walk(at: Int, limit: Int, depth: Int): Int? {
            if (depth > 8 || at < 0 || at > limit - 28 || limit > b.size) return null
            val code = PanasonicData.u32(b, at)!!.toInt()
            val header = PanasonicData.u32(b, at + 4)!!
            if (header !in 20L..256L || header > limit - at - 8L) return null
            val count = PanasonicData.u32(b, at + 20)!!
            val length = PanasonicData.u32(b, at + 24)!!
            val start = at + 8 + header.toInt()
            if (length > limit - start.toLong() || count > 512) return null
            val end = start + length.toInt()
            if (start <= end - 8 && (PanasonicData.u32(b, start)!!.toInt() ushr 24) in listOf(2, 6)) {
                var pos = start
                repeat(count.toInt()) { pos = walk(pos, end, depth + 1) ?: return null }
                if (pos != end) return null
            } else if (length >= 32) {
                val formLength = PanasonicData.u32(b, start + 4)!!
                if (formLength > end - start - 8L || formLength < 24) return null
                val type = PanasonicData.u32(b, start + 24)!!.toInt()
                val size = when (type) { 2 -> 1; 4 -> 2; 6 -> 4; else -> return end }
                val form = PanasonicData.u32(b, start + 28)!!.toInt()
                val valueAt = start + 32
                fun value(p: Int): Int? = if (p < start || p > end - size) null else when (size) {
                    1 -> b[p].toInt() and 255
                    2 -> PanasonicData.u16(b, p)
                    else -> PanasonicData.u32(b, p)?.toInt()
                }
                val current = value(valueAt) ?: return null
                val values = mutableListOf<Int>()
                if (form == 2) {
                    val n = PanasonicData.u32(b, valueAt + size) ?: return null
                    val first = valueAt + size + 4
                    if (n > 512 || n * size > end - first.toLong()) return null
                    repeat(n.toInt()) { values += value(first + it * size) ?: return null }
                } else if (form == 1) {
                    val min = value(valueAt + size) ?: return null
                    val max = value(valueAt + 2 * size) ?: return null
                    val step = value(valueAt + 3 * size) ?: return null
                    if (step > 0 && max >= min && (max.toLong() - min) / step <= 511) {
                        var n = min.toLong()
                        while (n <= max.toLong()) { values += n.toInt(); n += step }
                    }
                }
                out[code] = CameraProperty(code, size, current, values.distinct())
            }
            return end
        }
        if (walk(0, b.size, 0) != b.size) return emptyMap()
        return out
    }
}
