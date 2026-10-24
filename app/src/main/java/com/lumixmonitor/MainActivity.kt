package com.lumixmonitor

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.lumixmonitor.ui.LumixMonitorTheme
import com.lumixmonitor.ui.MonitorScreen
import java.io.File

/**
 * Единственная Activity приложения.
 *
 * Экран не гасится (монитор смотрят постоянно) и не пересоздаётся при повороте,
 * чтобы не рвать USB-соединение.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        setContent {
            LumixMonitorTheme {
                MonitorScreen(viewModel)
            }
        }

        handleUsbAttachIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    override fun onPause(){viewModel.onBackground();super.onPause()}

    /** Если приложение открыли по факту подключения камеры — сразу подключаемся. */
    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent == null) return
        if(BuildConfig.DEBUG && intent.getBooleanExtra("phone-camera-probe",false)){
            runCatching {
                Class.forName("com.lumixmonitor.PhoneCameraProbe").getMethod("capture",android.app.Activity::class.java).invoke(null,this)
            }.onFailure{com.lumixmonitor.ptp.PtpLog.add("Phone camera probe: ${it.message}")}
            return
        }
        if (BuildConfig.DEBUG && intent.hasExtra("usb-quality-probe")) {
            viewModel.probeUsbQuality(intent.getStringExtra("usb-quality-probe") ?: "")
            return
        }
        if(intent.getBooleanExtra("capture-probe",false)){viewModel.requestFrameProbe();return}
        val reference=intent.getStringExtra("reference")
        if(reference in listOf("display-reference.jpg","footage-reference.png")){
            val file=File(filesDir,reference!!)
            if(file.exists() && file.length()<8_000_000)viewModel.startDemo(file.readBytes())
            return
        }
        if(intent.getBooleanExtra("demo",false)){viewModel.startDemo();return}
        val device = IntentCompat.getParcelableExtra(
            intent,
            UsbManager.EXTRA_DEVICE,
            UsbDevice::class.java,
        ) ?: return
        viewModel.connect(device)
    }

    /** Скрывает системные панели — liveview занимает весь экран (по свайпу показываются временно). */
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
