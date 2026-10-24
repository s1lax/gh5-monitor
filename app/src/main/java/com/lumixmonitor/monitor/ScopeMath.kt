package com.lumixmonitor.monitor

import kotlin.math.roundToInt

data class Scopes(val histogram: IntArray, val rgb: Array<IntArray>, val waveform: IntArray,
    val vectors: IntArray, val width: Int = 160, val height: Int = 64)

/** JPEG code-value analysis, before viewing LUTs. Never analyses the UI. */
object ScopeMath {
    fun luma(color: Int): Int = (((color ushr 16 and 255)*0.2126f +
        (color ushr 8 and 255)*0.7152f + (color and 255)*0.0722f).roundToInt()).coerceIn(0,255)

    fun analyse(pixels: IntArray, width: Int, height: Int): Scopes {
        require(width > 0 && height > 0 && pixels.size == width*height)
        val histogram = IntArray(256)
        val rgb = Array(3) { IntArray(256) }
        val wave = IntArray(width*64)
        val vectors = IntArray(64*64)
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y*width+x]
            val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
            val v = luma(color)
            histogram[v]++
            rgb[0][r]++; rgb[1][g]++; rgb[2][b]++
            wave[(63-v*63/255)*width+x]++
            val cb = ((128f-0.114572f*r-0.385428f*g+0.5f*b)*63f/255f).roundToInt().coerceIn(0,63)
            val cr = ((128f+0.5f*r-0.454153f*g-0.045847f*b)*63f/255f).roundToInt().coerceIn(0,63)
            vectors[(63-cr)*64+cb]++
        }
        return Scopes(histogram,rgb,wave,vectors,width,64)
    }
}
