package com.lumixmonitor.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Контейнеры PTP (ISO 15740 / PIMA 15740).
 *
 * Формат заголовка — 12 байт, little-endian:
 *   uint32 length          полная длина контейнера, включая заголовок
 *   uint16 type            1 = Command, 2 = Data, 3 = Response, 4 = Event
 *   uint16 code            opcode / код ответа / код события
 *   uint32 transactionId   сквозной счётчик
 *
 * У Command/Response/Event после заголовка идут до 5 параметров uint32.
 * У Data после заголовка идут непосредственно данные.
 */
object PtpContainerType {
    const val COMMAND = 0x0001
    const val DATA = 0x0002
    const val RESPONSE = 0x0003
    const val EVENT = 0x0004
}

/** Стандартные opcodes PTP (ISO 15740). */
object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
}

/** Стандартные коды ответов PTP (ISO 15740). */
object PtpResponseCode {
    const val UNDEFINED = 0x2000
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val INVALID_TRANSACTION_ID = 0x2004
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val PARAMETER_NOT_SUPPORTED = 0x2006
    const val INCOMPLETE_TRANSFER = 0x2007
    const val INVALID_STORAGE_ID = 0x2008
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val INVALID_OBJECT_FORMAT_CODE = 0x200B
    const val STORE_FULL = 0x200C
    const val OBJECT_WRITE_PROTECTED = 0x200D
    const val STORE_READ_ONLY = 0x200E
    const val ACCESS_DENIED = 0x200F
    const val STORE_NOT_AVAILABLE = 0x2013
    const val INVALID_CODE_FORMAT = 0x2016
    const val UNKNOWN_VENDOR_CODE = 0x2017
    const val CAPTURE_ALREADY_TERMINATED = 0x2018
    const val DEVICE_BUSY = 0x2019
    const val INVALID_PARENT_OBJECT = 0x201A
    const val INVALID_DEVICE_PROP_FORMAT = 0x201B
    const val INVALID_DEVICE_PROP_VALUE = 0x201C
    const val INVALID_PARAMETER = 0x201D
    const val SESSION_ALREADY_OPEN = 0x201E
    const val TRANSACTION_CANCELLED = 0x201F

    fun name(code: Int): String = when (code) {
        UNDEFINED -> "Undefined"
        OK -> "OK"
        GENERAL_ERROR -> "GeneralError"
        SESSION_NOT_OPEN -> "SessionNotOpen"
        INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        INVALID_STORAGE_ID -> "InvalidStorageID"
        INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        STORE_FULL -> "StoreFull"
        OBJECT_WRITE_PROTECTED -> "ObjectWriteProtected"
        STORE_READ_ONLY -> "StoreReadOnly"
        ACCESS_DENIED -> "AccessDenied"
        STORE_NOT_AVAILABLE -> "StoreNotAvailable"
        UNKNOWN_VENDOR_CODE -> "UnknownVendorCode"
        CAPTURE_ALREADY_TERMINATED -> "CaptureAlreadyTerminated"
        DEVICE_BUSY -> "DeviceBusy"
        INVALID_PARENT_OBJECT -> "InvalidParentObject"
        INVALID_DEVICE_PROP_FORMAT -> "InvalidDevicePropFormat"
        INVALID_DEVICE_PROP_VALUE -> "InvalidDevicePropValue"
        INVALID_PARAMETER -> "InvalidParameter"
        SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        TRANSACTION_CANCELLED -> "TransactionCancelled"
        else -> "0x%04X".format(code)
    }
}

/** Заголовок контейнера. */
data class PtpHeader(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
)

/** Разобранный контейнер. */
class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray,
) {
    val isResponse: Boolean get() = type == PtpContainerType.RESPONSE
    val isEvent: Boolean get() = type == PtpContainerType.EVENT
    val isData: Boolean get() = type == PtpContainerType.DATA

    /** Параметры Command/Response/Event: payload как массив uint32. */
    fun parameters(): IntArray {
        val count = payload.size / 4
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count) { bb.int }
    }

    /** Первый параметр или 0, если параметров нет. */
    fun firstParameter(): Int {
        if (payload.size < 4) return 0
        return ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    override fun toString(): String {
        val typeName = when (type) {
            PtpContainerType.COMMAND -> "CMD"
            PtpContainerType.DATA -> "DATA"
            PtpContainerType.RESPONSE -> "RSP"
            PtpContainerType.EVENT -> "EVT"
            else -> "?"
        }
        return "$typeName code=0x%04X tid=$transactionId len=${payload.size}".format(code)
    }

    companion object {
        const val HEADER_SIZE = 12

        /** Разумный верхний предел на контейнер, чтобы не выделять память по мусорной длине. */
        const val MAX_CONTAINER_BYTES = 16 * 1024 * 1024

        fun parseHeader(buf: ByteArray, offset: Int = 0): PtpHeader {
            val bb = ByteBuffer.wrap(buf, offset, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            return PtpHeader(
                length = bb.int,
                type = bb.short.toInt() and 0xFFFF,
                code = bb.short.toInt() and 0xFFFF,
                transactionId = bb.int,
            )
        }

        fun buildCommand(opcode: Int, transactionId: Int, parameters: IntArray): ByteArray {
            val size = HEADER_SIZE + parameters.size * 4
            val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(size)
            bb.putShort(PtpContainerType.COMMAND.toShort())
            bb.putShort(opcode.toShort())
            bb.putInt(transactionId)
            for (p in parameters) bb.putInt(p)
            return bb.array()
        }

        fun buildData(opcode: Int, transactionId: Int, data: ByteArray): ByteArray {
            val size = HEADER_SIZE + data.size
            val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(size)
            bb.putShort(PtpContainerType.DATA.toShort())
            bb.putShort(opcode.toShort())
            bb.putInt(transactionId)
            bb.put(data)
            return bb.array()
        }
    }
}

/** Ошибка обмена по PTP. */
class PtpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Камера не ответила в отведённое время.
 *
 * Отделено от общей ошибки обмена: молчание камеры означает проблему канала
 * (режим камеры, кабель, состояние порта), а не ошибку протокола, и лечится
 * иначе — повтором с большим таймаутом, а не разбором кодов ответа.
 */
class PtpTimeoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
