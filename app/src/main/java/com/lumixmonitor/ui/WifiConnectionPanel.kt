package com.lumixmonitor.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind

@Composable
internal fun WifiConnectPanel(vm:MonitorViewModel){
    val wifi=vm.wifi;val context=LocalContext.current
    var address by remember(wifi.address){mutableStateOf(wifi.address)}
    Text("На GH5: Wi-Fi → New Connection → Remote Shooting & View. Подключите телефон к сети камеры и выберите «Оставаться подключённым», даже если нет интернета. USB камеры отключите.",fontSize=14.sp)
    Text("Для раздачи с телефона: включите точку доступа 5 ГГц и подключите к ней GH5 через Via Network. Здесь нужен IP камеры из списка клиентов точки доступа; попробуйте кнопку поиска ниже.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(address,{address=it},label={Text("IP камеры (обычно 192.168.54.1)")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
        Button(onClick={vm.connectWifi(address)},enabled=!wifi.starting,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("Подключить Wi-Fi")}
        OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("Настройки Wi-Fi")}
    }
    OutlinedButton(onClick={wifi.discover()},enabled=!wifi.discovering && !wifi.starting,modifier=Modifier.heightIn(min=48.dp)){Text(if(wifi.discovering)"Поиск…" else "Найти камеру в общей сети")}
    OutlinedButton(onClick={wifi.diagnose()},enabled=!wifi.starting,modifier=Modifier.heightIn(min=48.dp)){Text("Проверить ответ GH5")}
    wifi.discovered.forEach{camera->TextButton(onClick={address=camera.host},modifier=Modifier.heightIn(min=48.dp)){Text(camera.name)}}
    if(wifi.starting)LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(wifi.message.ifBlank{vm.statusText},fontSize=12.sp)
    Spacer(Modifier.height(8.dp))
    Text("Прямое соединение камера → телефон не требует роутера. Оба устройства также могут работать в одной локальной сети. Закройте Image App и другие пульты: камера обычно принимает один клиент. Доступное качество Wi-Fi может быть ниже USB.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun WifiCameraPanel(vm:MonitorViewModel,openSetting:(SettingKind)->Unit){
    val wifi=vm.wifi;val t=wifi.telemetry;val quiet=Color(0xFF9CA9B5)
    Text("${wifi.model} · Wi-Fi",color=Color(0xFFB4E4CC),fontSize=16.sp)
    Text("${t.statusText} · батарея ${t.battery?.let{"$it%"} ?: "—"}\n${vm.actualFrameSize ?: "Ожидание кадра"}",color=Color.White,fontSize=13.sp)
    Text(vm.performanceText,color=quiet,fontSize=11.sp)
    if(android.os.Build.VERSION.SDK_INT in 29..33){
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Switch(wifi.lowLatency,{wifi.chooseLowLatency(it)})
            Text("Минимальная задержка Wi-Fi",color=Color.White,fontSize=13.sp)
        }
        Text("Выключено: приоритет пропускной способности. Включено: Android снижает задержку, иногда ценой скорости потока. Сравните оба режима.",color=quiet,fontSize=12.sp)
    }
    Row{
        RadioButton(!wifi.shutterMovie,{wifi.shutterMovie=false});TextButton({wifi.shutterMovie=false}){Text("Спуск: фото")}
        RadioButton(wifi.shutterMovie,{wifi.shutterMovie=true});TextButton({wifi.shutterMovie=true}){Text("Спуск: видео")}
    }
    Text("Выберите действие центральной кнопки. При подтверждённом REC она всегда останавливает запись.",color=quiet,fontSize=12.sp)
    OutlinedButton({wifi.command("oneshot_af")},enabled=!wifi.commandBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Автофокус GH5")}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton({wifi.command("capture")},enabled=!wifi.commandBusy,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("Снять фото")}
        OutlinedButton({wifi.command(if(t.recording==true)"video_recstop" else "video_recstart")},enabled=!wifi.commandBusy,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(if(t.recording==true)"Стоп видео" else "REC видео")}
    }
    listOf(SettingKind.ISO,SettingKind.SHUTTER,SettingKind.APERTURE,SettingKind.EXPOSURE,SettingKind.WHITE_BALANCE,SettingKind.PHOTO_STYLE,SettingKind.AF_MODE,SettingKind.AF_ZONE).forEach{kind->
        val choices=vm.allowedSettings(kind)
        OutlinedButton({openSetting(kind)},enabled=choices.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
            Text("${kind.label}: ${vm.currentSettingText(kind)}",fontSize=14.sp)
        }
    }
    Text("Качество Wi-Fi",color=Color.White,fontSize=14.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        wifi.choices("liveviewsize").forEach{option->OutlinedButton({wifi.select("liveviewsize",option.id)},enabled=t.recording!=true,modifier=Modifier.heightIn(min=48.dp)){Text(option.label)}}
    }
    if(wifi.choices("liveviewsize").isEmpty())Text("Камера не сообщила выбор качества; используется её текущий режим.",color=quiet,fontSize=12.sp)
    Text("Камерная лупа, её навигатор, точка AF и оригиналы сейчас доступны по USB. Пикинг, зебра, LUT, графики, направляющие, 1:1 и сохранение превью работают и с Wi-Fi.",color=quiet,fontSize=12.sp)
    Text(wifi.message,color=Color(0xFFB4E4CC),fontSize=12.sp)
}
