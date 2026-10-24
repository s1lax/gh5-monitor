package com.lumixmonitor.ptp

/** A USB read can contain part of a PTP container, or data followed by a response. */
internal class PtpBulkReader(
    private val packetSize: Int,
    private val transfer: (ByteArray, Int, Int) -> Int,
    private val onWait: (String) -> Unit = {},
) {
    private val buffer = ByteArray(1024 * 1024)
    private var position = 0
    private var limit = 0
    private var zeroPackets = 0
    private var bufferedResponses = 0

    fun readContainer(maxBytes: Int, timeoutMs: Int, attempts: Int): PtpContainer {
        val headerBytes = ByteArray(PtpContainer.HEADER_SIZE)
        readFully(headerBytes, timeoutMs, attempts, header = true)
        val header = PtpContainer.parseHeader(headerBytes)
        val payloadLength = header.length.toLong() - PtpContainer.HEADER_SIZE
        if (payloadLength < 0 || payloadLength > maxBytes ||
            header.length > PtpContainer.MAX_CONTAINER_BYTES) {
            throw PtpException("некорректная длина PTP-контейнера: ${header.length}")
        }
        val payload = ByteArray(payloadLength.toInt())
        readFully(payload, timeoutMs, attempts, header = false)
        if (position < limit) {
            bufferedResponses++
            if (bufferedResponses <= 3 || bufferedResponses % 100 == 0) {
                onWait("PTP: сохранён хвост USB-пакета (${limit - position} байт), случаев $bufferedResponses")
            }
        }
        return PtpContainer(header.type, header.code, header.transactionId, payload)
    }

    private fun readFully(target: ByteArray, timeoutMs: Int, attempts: Int, header: Boolean) {
        var offset = 0
        while (offset < target.size) {
            if (position == limit) {
                val desired = if (header) 16 * 1024 else minOf(target.size - offset, buffer.size)
                val aligned = ((desired + packetSize - 1) / packetSize) * packetSize
                fill(minOf(aligned, buffer.size), timeoutMs, attempts)
            }
            val count = minOf(limit - position, target.size - offset)
            System.arraycopy(buffer, position, target, offset, count)
            position += count
            offset += count
        }
    }

    private fun fill(request: Int, timeoutMs: Int, attempts: Int) {
        var failures = 0
        var emptyReads = 0
        while (true) {
            val start = System.nanoTime()
            val count = transfer(buffer, request, timeoutMs)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (count > request) throw PtpException("USB вернул больше запрошенного")
            if (count > 0) {
                if (elapsedMs >= 100) onWait("USB SLOW IN: $count/$request байт за ${elapsedMs}мс")
                position = 0
                limit = count
                return
            }
            // A leftover zero-length USB packet is an end marker, not a timed-out transaction.
            if (count == 0 && ++emptyReads <= 8) {
                zeroPackets++
                if (zeroPackets <= 3 || zeroPackets % 100 == 0) {
                    onWait("USB ZLP: $zeroPackets, ожидание ${elapsedMs}мс")
                }
                continue
            }
            failures++
            onWait("USB IN: $count байт за ${elapsedMs}мс, попытка $failures/$attempts")
            if (failures >= attempts || emptyReads > 8) {
                throw PtpTimeoutException("камера не ответила на USB IN (${elapsedMs}мс, попыток $failures)")
            }
        }
    }
}
