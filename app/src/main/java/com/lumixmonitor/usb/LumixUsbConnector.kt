package com.lumixmonitor.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lumixmonitor.ptp.PtpException
import com.lumixmonitor.ptp.PtpLog
import com.lumixmonitor.ptp.PtpUsbTransport

/**
 * Поиск камеры на USB, запрос разрешения и открытие PTP-транспорта.
 *
 * Отдельно от транспорта, потому что требует Context и работы с системным
 * диалогом разрешения, который нельзя ни стилизовать, ни обойти.
 */
class LumixUsbConnector(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var permissionReceiver: BroadcastReceiver? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    /** Все подключённые устройства, у которых есть PTP-интерфейс. */
    fun findPtpDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { it.findPtpInterface() != null }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Запрашивает разрешение на доступ к устройству.
     * Системный диалог показывается всегда, если разрешения ещё нет.
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }

        releaseReceiver()
        permissionCallback = onResult

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false,
                )
                val callback = permissionCallback
                releaseReceiver()
                permissionCallback = null
                callback?.invoke(granted)
            }
        }
        permissionReceiver = receiver

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Система дописывает extras в этот интент, поэтому нужен MUTABLE.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Открывает устройство и возвращает готовый транспорт.
     * Требует уже выданного разрешения.
     */
    fun open(device: UsbDevice): PtpUsbTransport {
        val usbInterface = device.findPtpInterface()
            ?: throw PtpException("у устройства нет PTP-интерфейса")

        PtpLog.add(
            "USB: конфигураций=${device.configurationCount}, " +
                "интерфейс=${usbInterface.id}, alt=${usbInterface.alternateSetting}",
        )

        val connection = usbManager.openDevice(device)
            ?: throw PtpException("не удалось открыть устройство (нет разрешения?)")
        PtpLog.add("USB: устройство открыто")

        // Интерфейс мог остаться занятым от предыдущей неудачной попытки.
        try {
            connection.releaseInterface(usbInterface)
        } catch (t: Throwable) {
            Log.d(TAG, "releaseInterface перед захватом: ${t.message}")
        }

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            PtpLog.add("USB: занять PTP-интерфейс НЕ удалось")
            throw PtpException("не удалось занять PTP-интерфейс")
        }
        PtpLog.add("USB: PTP-интерфейс занят")

        val endpointIn = usbInterface.findBulkEndpoint(UsbConstants.USB_DIR_IN)
        val endpointOut = usbInterface.findBulkEndpoint(UsbConstants.USB_DIR_OUT)
        val endpointEvent = usbInterface.findInterruptEndpoint(UsbConstants.USB_DIR_IN)

        if (endpointIn == null || endpointOut == null) {
            connection.releaseInterface(usbInterface)
            connection.close()
            throw PtpException("не найдены bulk-эндпоинты PTP")
        }

        Log.i(TAG, "открыто устройство ${device.deviceName} " +
            "(VID=0x%04X PID=0x%04X)".format(device.vendorId, device.productId))

        val transport = PtpUsbTransport(
            device = device,
            connection = connection,
            usbInterface = usbInterface,
            endpointIn = endpointIn,
            endpointOut = endpointOut,
            endpointEvent = endpointEvent,
        )

        // Сброс состояния камеры и снятие halt — без этого она не отвечает.
        transport.setup()
        return transport
    }

    fun release() {
        releaseReceiver()
        permissionCallback = null
    }

    private fun releaseReceiver() {
        val receiver = permissionReceiver ?: return
        try {
            context.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось снять приёмник разрешения", t)
        }
        permissionReceiver = null
    }

    companion object {
        private const val TAG = "LumixUsbConnector"
        private const val ACTION_USB_PERMISSION = "com.lumixmonitor.USB_PERMISSION"
    }
}

/** Ищет интерфейс PTP: класс Still Image, подкласс PTP, протокол PTP. */
internal fun UsbDevice.findPtpInterface(): UsbInterface? {
    for (i in 0 until interfaceCount) {
        val candidate = getInterface(i)
        if (candidate.interfaceClass == PtpUsbTransport.USB_CLASS_STILL_IMAGE &&
            candidate.interfaceSubclass == PtpUsbTransport.USB_SUBCLASS_PTP &&
            candidate.interfaceProtocol == PtpUsbTransport.USB_PROTOCOL_PTP
        ) {
            return candidate
        }
    }
    // Резерв: некоторые камеры объявляют PTP нестандартно, но bulk-эндпоинты есть.
    for (i in 0 until interfaceCount) {
        val candidate = getInterface(i)
        if (candidate.findBulkEndpoint(UsbConstants.USB_DIR_IN) != null &&
            candidate.findBulkEndpoint(UsbConstants.USB_DIR_OUT) != null
        ) {
            return candidate
        }
    }
    return null
}

internal fun UsbInterface.findBulkEndpoint(direction: Int): UsbEndpoint? {
    for (i in 0 until endpointCount) {
        val endpoint = getEndpoint(i)
        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
            endpoint.direction == direction
        ) {
            return endpoint
        }
    }
    return null
}

/** События PTP приходят на interrupt-эндпоинт, а не на bulk. */
internal fun UsbInterface.findInterruptEndpoint(direction: Int): UsbEndpoint? {
    for (i in 0 until endpointCount) {
        val endpoint = getEndpoint(i)
        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
            endpoint.direction == direction
        ) {
            return endpoint
        }
    }
    return null
}
