package com.lumixmonitor.ui

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind
import com.lumixmonitor.lumix.RemoteSetting
import kotlinx.coroutines.*

private val RemoteAccent=Color(0xFFB4E4CC)
private val RemoteQuiet=Color(0xFF9CA9B5)

internal fun clockText(seconds:Long?):String = seconds?.let { "%02d:%02d:%02d".format(it/3600,it/60%60,it%60) } ?: "—"

@Composable
internal fun RemoteCameraPanel(vm:MonitorViewModel,openSetting:(SettingKind)->Unit){
    val r=vm.remoteControl;val t=r.telemetry
    var section by remember{mutableStateOf("Фокус")}
    var selected by remember{mutableStateOf<Int?>(null)}
    val context=LocalContext.current
    Text(if(vm.isDemo)"DEMO · камера не используется" else vm.cameraModel ?: "GH5",color=RemoteAccent,fontSize=12.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
        listOf("Фокус","Состояние","ББ","Профиль","Видео","Предпросмотр","Оригиналы").forEach { name ->
            Box(Modifier.height(48.dp).background(if(name==section)RemoteAccent else Color(0xFF252E36),RoundedCornerShape(5.dp))
                    .clickable{section=name;selected=null}.padding(horizontal=12.dp),contentAlignment=Alignment.Center){Text(name,color=if(name==section)Color.Black else RemoteQuiet,fontSize=13.sp)}
        }
    }
    when(section){
        "Фокус" -> {
            RemoteButton(if(vm.cameraFocusAssist)"Выключить лупу GH5" else if(vm.selectingAssist)"Отменить выбор области" else "Включить лупу GH5",!vm.focusAssistPending){vm.toggleFocusAssist()}
            Text("Увеличение выполняет камера. Кратность и область меняются до передачи USB.",color=RemoteQuiet,fontSize=11.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                AssistScaleButton("−1×",vm.cameraFocusAssist){r.stepAssist(false)}
                AssistScaleButton("+1×",vm.cameraFocusAssist){r.stepAssist(true)}
            }
            Text("Один шаг — 1×. Удерживайте для повторения.",color=RemoteQuiet,fontSize=11.sp)
            if(vm.cameraFocusAssist || vm.selectingAssist){
                AssistNavigator(r,vm.previewQuarterTurns,Modifier.fillMaxWidth())
                Text("Выбор области запоминается телефоном и восстанавливается при следующем включении. Маркер — точка, заданная телефоном; перемещение кнопками GH5 не отслеживается.",color=RemoteQuiet,fontSize=11.sp)
            }
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(r.touchAf,{r.toggleTouchAf()});Text("AF касанием кадра",color=Color.White,fontSize=12.sp)}
            Text("Касание общего плана выбирает зону «1 точка» и запускает AF. Режим AF/MF задаётся на камере.",color=RemoteQuiet,fontSize=11.sp)
            RemoteButton("Режим фокуса: ${vm.settings.afMode}"){openSetting(SettingKind.AF_MODE)}
            RemoteButton("Зона AF: ${vm.settings.afZone}"){openSetting(SettingKind.AF_ZONE)}
            RemoteButton("Автофокус"){vm.autoFocus()}
            Text(t.afText,color=RemoteAccent,fontSize=12.sp)
            var fast by remember{mutableStateOf(false)}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(fast,{fast=it});Text("Быстрые шаги MF",color=RemoteQuiet,fontSize=12.sp)}
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                FocusHold("MF ближе",Modifier.weight(1f),{r.stopFocus()}){r.driveFocus(false,if(fast)2 else 1)}
                FocusHold("MF дальше",Modifier.weight(1f),{r.stopFocus()}){r.driveFocus(true,if(fast)2 else 1)}
            }
            Text("Удерживайте кнопку для непрерывных шагов.",color=RemoteQuiet,fontSize=11.sp)
        }
        "Состояние" -> {
            val fresh=SystemClock.elapsedRealtime()-t.observedAtMs<4500
            Text(if(fresh)t.statusText else "Ожидание данных камеры",color=if(t.recording==true && fresh)Color(0xFFFF7777) else RemoteAccent,fontSize=17.sp)
            Text("Записано: ${clockText(t.elapsedSeconds)}\nОсталось видео: ${clockText(t.remainingSeconds)}\nОсталось фото: ${t.remainingImages ?: "—"}\nБатарея: ${t.battery?.let{"$it%"} ?: "—"}",color=Color.White,fontSize=12.sp)
            Text("${vm.observedSettings.iso} ISO · ${vm.observedSettings.shutter} · ${vm.observedSettings.aperture}",color=RemoteAccent,fontSize=12.sp)
            Text("Экспонометр GH5: ${t.meterThirds?.let{String.format("%+.1f EV",it/3f)} ?: "—"}\n${t.afText}",color=RemoteQuiet,fontSize=12.sp)
            t.focalLength?.let{Text("Фокусное расстояние: ${it/10f} мм",color=RemoteQuiet,fontSize=12.sp)}
            RemoteButton(if(t.recording==true)"Остановить запись (спуск GH5)" else "Спуск затвора GH5",!vm.capturePending){vm.shutter()}
            Text("Действие спуска зависит от режима GH5: фото или начало видео.",color=RemoteQuiet,fontSize=11.sp)
            RemoteButton("Начать запись на GH5",t.recording!=true){vm.movie(true)}
            RemoteButton("Остановить запись на GH5",t.recording!=false){vm.movie(false)}
            Text(vm.recordCommand,color=RemoteQuiet,fontSize=11.sp)
            RemoteButton("AF/AE Lock GH5"){r.aeLock(true)}
            RemoteButton("Снять AF/AE Lock"){r.aeLock(false)}
            Text("Действие зависит от режима и настройки AF/AE LOCK на GH5. ${if(t.aeLocked==true)"Блокировка AE подтверждена." else "Подтверждения AE Lock нет."}",color=RemoteQuiet,fontSize=11.sp)
        }
        "Оригиналы" -> {
            if(r.filesChanged)Text("На карте появился новый файл — обновите список",color=RemoteAccent,fontSize=11.sp)
            Text("Полные JPEG / RW2 с карты камеры. Кнопка JPEG на основном экране сохраняет USB-превью.",color=RemoteQuiet,fontSize=11.sp)
            RemoteButton(if(r.filesLoading)"Чтение карты…" else "Обновить список с карты",!r.filesLoading && !r.transferBusy){r.refreshFiles()}
            if(r.transferText.isNotBlank())Text(r.transferText,color=RemoteAccent,fontSize=12.sp)
            r.downloadedUri?.let { uri -> RemoteButton("Открыть сохранённый оригинал"){
                runCatching{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,r.downloadedMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
                    .onFailure{android.widget.Toast.makeText(context,"Файл сохранён; приложение для просмотра этого формата не найдено",android.widget.Toast.LENGTH_LONG).show()}
            } }
            r.files.forEach { item ->
                Column(Modifier.fillMaxWidth().border(1.dp,RemoteQuiet.copy(alpha=.2f),RoundedCornerShape(5.dp)).padding(7.dp)){
                    Text(item.name,color=Color.White,fontSize=12.sp)
                    Text("${item.width}×${item.height} · ${String.format("%.1f",item.bytes/1048576f)} МБ",color=RemoteQuiet,fontSize=10.sp)
                    RemoteButton("Скачать ${item.name}",!r.transferBusy){r.download(item)}
                }
            }
        }
        else -> {
            if(section=="ББ"){
                RemoteButton("Выбрать режим баланса белого"){openSetting(SettingKind.WHITE_BALANCE)}
                RemoteButton("Перейти на температуру K1"){vm.setWhiteBalance(0x8010)}
                Text("Диапазоны зависят от выбранного ББ. Для температуры сначала выберите режим K.",color=RemoteQuiet,fontSize=11.sp)
            }
            if(section=="Профиль")RemoteButton("Выбрать Photo Style"){openSetting(SettingKind.PHOTO_STYLE)}
            if(section=="Видео")Text("Качество записи на карту. USB-превью настраивается отдельно.",color=RemoteQuiet,fontSize=11.sp)
            if(section=="Предпросмотр")Text("Настройка камеры. Коррекция экрана телефона находится в разделе «Экран».",color=RemoteQuiet,fontSize=11.sp)
            RemoteSetting.all.filter{it.section==section}.forEach { setting ->
                val property=r.properties[setting.code];val values=r.choices(setting)
                RemoteButton("${setting.label}: ${property?.let{setting.text(it.current)} ?: "—"}",values.isNotEmpty()) {selected=if(selected==setting.code)null else setting.code}
                if(values.isEmpty())Text("Нет доступных значений в текущем режиме GH5",color=RemoteQuiet,fontSize=10.sp)
                if(property?.verifiedGh5Range==true)Text("Диапазон −9…+9 проверен на GH5",color=RemoteQuiet,fontSize=10.sp)
                if(selected==setting.code && values.isNotEmpty())key(setting.code,values.map{it.first}){
                    DiscreteSettingWheel(setting.label,values,property?.current,section=="Видео"){r.set(setting,it)}
                }
            }
            if(section=="Профиль"){
                RemoteButton("Соотношение сторон фото"){openSetting(SettingKind.ASPECT)}
                RemoteButton("Формат / качество фото"){openSetting(SettingKind.QUALITY)}
            }
            RemoteButton("Перечитать доступные настройки"){r.requestRefresh()}
        }
    }
    if(r.feedback.isNotEmpty())Text(r.feedback,color=RemoteQuiet,fontSize=11.sp)
}

@Composable
private fun RemoteButton(text:String,enabled:Boolean=true,action:()->Unit){
    OutlinedButton(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)){Text(text,fontSize=14.sp)}
}
@Composable
private fun AssistScaleButton(label:String,enabled:Boolean,action:()->Unit){
    Box(Modifier.width(92.dp).height(48.dp).background(Color(0xFF293139),RoundedCornerShape(5.dp))
        .semantics{contentDescription="Масштаб лупы $label"}.repeatPress(enabled,action=action),contentAlignment=Alignment.Center){
        Text(label,color=if(enabled)RemoteAccent else RemoteQuiet,fontSize=18.sp)
    }
}
@Composable
private fun FocusHold(label:String,modifier:Modifier,stop:()->Unit,step:()->Unit){
    val currentStep by rememberUpdatedState(step)
    val currentStop by rememberUpdatedState(stop)
    Box(modifier.height(48.dp).background(Color(0xFF293139),RoundedCornerShape(5.dp)).semantics{contentDescription=label}
        .pointerInput(Unit){detectTapGestures(onPress={
            coroutineScope{val repeat=launch{while(isActive){currentStep();delay(180)}};try{tryAwaitRelease()}finally{repeat.cancel();currentStop()}}
        })},contentAlignment=Alignment.Center){Text(label,color=Color.White,fontSize=12.sp)}
}
