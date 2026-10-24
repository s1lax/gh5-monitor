package com.lumixmonitor.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PtpBulkReaderTest {
    private fun packet(type: Int, payload: ByteArray = byteArrayOf()) =
        ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + payload.size).putShort(type.toShort()).putShort(0x2001.toShort())
            .putInt(17).put(payload).array()

    private fun reader(vararg chunks: ByteArray): PtpBulkReader {
        val queue = ArrayDeque(chunks.toList())
        return PtpBulkReader(512, { buffer, request, _ ->
            if (queue.isEmpty()) -1 else {
                val next = queue.removeFirst()
                val count = minOf(next.size, request)
                next.copyInto(buffer, endIndex = count)
                if (count < next.size) queue.addFirst(next.copyOfRange(count, next.size))
                count
            }
        })
    }

    @Test fun dataAndResponseInTheSameReadAreBothPreserved() {
        val payload = ByteArray(512) { it.toByte() }
        val input = reader(packet(2, payload) + packet(3))
        assertArrayEquals(payload, input.readContainer(1024, 10, 1).payload)
        assertTrue(input.readContainer(32, 10, 1).isResponse)
    }

    @Test fun aHeaderSplitAcrossReadsIsReassembled() {
        val bytes = packet(2, byteArrayOf(3, 4, 5))
        val input = reader(bytes.copyOfRange(0, 5), bytes.copyOfRange(5, bytes.size))
        assertArrayEquals(byteArrayOf(3, 4, 5), input.readContainer(1024, 10, 1).payload)
    }

    @Test fun aLargeJpegAndItsTrailingResponseSurviveSeveralUsbReads() {
        val jpeg = ByteArray(450_000) { (it * 17).toByte() }
        val input = reader(packet(2, jpeg) + packet(3))
        assertArrayEquals(jpeg, input.readContainer(1_000_000, 10, 1).payload)
        assertTrue(input.readContainer(32, 10, 1).isResponse)
    }

    @Test fun zeroLengthEndMarkersDoNotConsumeTimeoutRetries() {
        val input = reader(byteArrayOf(), byteArrayOf(), packet(3))
        assertTrue(input.readContainer(32, 10, 1).isResponse)
    }

    @Test(expected = PtpTimeoutException::class)
    fun anEndlessSequenceOfEmptyPacketsIsBounded() {
        reader(*Array(20) { byteArrayOf() }).readContainer(32, 10, 1)
    }

    @Test(expected = PtpException::class)
    fun oversizedHeadersAreRejectedBeforePayloadAllocation() {
        val header = packet(2)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(10_000_000)
        reader(header).readContainer(1024, 10, 1)
    }
}
