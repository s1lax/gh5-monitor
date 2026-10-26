package com.lumixmonitor.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumixmonitor.MonitorTools
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.monitor.*
import kotlinx.coroutines.delay

private val WifiAccent=Color(0xFFB4E4CC)
private val WifiQuiet=Color(0xFF9CA9B5)

@Composable
private fun liveClock():Long {
    var now by remember{mutableStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(Unit){while(true){now=SystemClock.elapsedRealtime();delay(250)}}
    return now
}
@Composable
internal fun WifiFrameNotice(vm:MonitorViewModel,modifier:Modifier=Modifier){
    val w=vm.wifi;val now=liveClock()
    val age=if(w.lastPresentedAt>0)now-w.lastPresentedAt else Long.MAX_VALUE
    val text=when{
        w.gallery->"ПРОСМОТР КАРТЫ · живой поток на паузе"
        vm.heldFrame!=null->null
        w.lastPresentedAt==0L->"ОЖИДАНИЕ КАДРА GH5"
        age>=1500->"КАДР ЗАМЕР · ${"%.1f".format(age/1000f)} с без обновления"
        else->null
    }
    if(text!=null)Text(text,color=Color(0xFFFFCC80),fontSize=12.sp,
        modifier=modifier.padding(5.dp).background(Color(0xEE19130C)).padding(horizontal=10.dp,vertical=6.dp).semantics{contentDescription=text})
}
@Composable
internal fun WifiStatusStrip(vm:MonitorViewModel,open:()->Unit){
    val w=vm.wifi;val now=liveClock();val status=w.cameraStatus;val fresh=now-w.telemetry.observedAtMs<4500
    val warning=if(fresh)status.warning else null
    val card=status.cards.firstOrNull{it.slot==status.activeSlot}
    val temp=status.temperature?.takeIf{it !in listOf("low","normal")}
    val age=if(w.lastPresentedAt>0)(now-w.lastPresentedAt).coerceAtLeast(0) else null
    val parts=if(!fresh)listOf("GH5: состояние устарело") else listOfNotNull(
        when(status.recording){true->"● REC";false->"Ожидание";null->"REC: —"},
        status.activeSlot?.let{"SD$it"},if(card?.writing==true)"Запись на карту" else if(card?.full==true)"КАРТА ЗАПОЛНЕНА" else null,
        status.remainingVideo?.let{"Ост. ${clockText(it)}"},status.battery?.let{"АКБ ${it.label}"},temp?.let{"Темп.: $it"},warning,
        age?.takeIf{it>=500}?.let{"Кадр ${it} мс назад"})
    Text(parts.joinToString(" · "),color=if(!fresh || warning!=null || card?.full==true || temp!=null)Color(0xFFFFCC80) else WifiQuiet,
        fontSize=10.sp,maxLines=2,modifier=Modifier.fillMaxWidth().background(Color(0xFF10161B)).clickable(onClick=open).padding(horizontal=7.dp,vertical=5.dp).semantics{contentDescription="Состояние GH5 по Wi-Fi"})
}
@Composable
internal fun WifiAssistNavigator(vm:MonitorViewModel,modifier:Modifier=Modifier){
    val a=vm.wifi.assist
    val point=if(a.x!=null && a.y!=null)a.x to a.y else null
    AssistNavigator(point,vm.previewQuarterTurns,modifier,showScale=true,scaleEnabled=a.active,
        title="GH5 ${a.magnification?.div(100f) ?: "—"}× · ${if(a.display=="pinp")"окно" else "полный"}",
        source="Ответ GH5 · последняя команда",move=vm.wifi::moveAssist,step=vm.wifi::stepAssist)
}
@Composable
internal fun WifiFocusControls(vm:MonitorViewModel){
    val w=vm.wifi
    OutlinedButton({vm.toggleFocusAssist()},enabled=!w.assistBusy && !w.gallery,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
        Text(if(w.assist.active)"Выключить лупу GH5" else "Лупа GH5 по Wi-Fi")
    }
    Text("Увеличивает камера до передачи кадра. Нужны MF и приоритет «Смартфон». При приоритете «Камера» часть команд может отклоняться.",color=WifiQuiet,fontSize=12.sp)
    if(w.assist.active)WifiAssistNavigator(vm,Modifier.fillMaxWidth())
    Row(verticalAlignment=Alignment.CenterVertically){Switch(w.touchAf,{w.toggleTouchAf()});Text("AF касанием кадра",color=Color.White,fontSize=13.sp)}
    Text("Для AF выберите на GH5 AFS/AFC и подходящую зону. Касание не нажимает спуск автоматически.",color=WifiQuiet,fontSize=11.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Box(Modifier.weight(1f).height(52.dp).background(Color(0xFF293139)).semantics{contentDescription="Wi-Fi MF ближе"}.repeatPress(onRelease=w::stopFocus){w.driveFocus(false)},contentAlignment=Alignment.Center){Text("MF ближе",color=Color.White)}
        Box(Modifier.weight(1f).height(52.dp).background(Color(0xFF293139)).semantics{contentDescription="Wi-Fi MF дальше"}.repeatPress(onRelease=w::stopFocus){w.driveFocus(true)},contentAlignment=Alignment.Center){Text("MF дальше",color=Color.White)}
    }
    DisposableEffect(w){onDispose{w.stopFocus()}}
    Text("Удерживайте для коротких шагов фокуса. Отпускание отменяет очередь; уже принятый камерой шаг завершается.",color=WifiQuiet,fontSize=11.sp)
}
@Composable
internal fun WifiStateDetails(vm:MonitorViewModel){
    val w=vm.wifi;val s=w.cameraStatus;val now=liveClock();val fresh=now-w.telemetry.observedAtMs<4500
    Text(if(fresh)"Данные GH5" else "Данные GH5 устарели",color=if(fresh)WifiAccent else Color(0xFFFFCC80),fontSize=14.sp)
    Text("Аккумулятор: ${s.battery?.label ?: "—"}${s.battery?.takeUnless{it.percent}?.let{" (${it.current}/${it.maximum} делений)"}.orEmpty()}\nБатарейная ручка: ${s.grip?.label ?: "—"}\nАктивный слот: ${s.activeSlot?.let{"SD$it"} ?: "—"}\nОсталось видео: ${clockText(s.remainingVideo)}\nОсталось фото: ${s.remainingPhotos ?: "—"}",color=Color.White,fontSize=13.sp)
    s.cards.forEach{Text(it.label,color=if(it.full==true || it.writable==false)Color(0xFFFFCC80) else WifiQuiet,fontSize=13.sp)}
    Text("Температурный статус GH5: ${s.temperature ?: "—"}\nПредупреждение: ${s.warning ?: "—"}\nСостояние управления: ${s.operation ?: "—"}",color=WifiQuiet,fontSize=12.sp)
    Text("Деления батареи не являются точными процентами. Температурный статус не является температурой в градусах.",color=WifiQuiet,fontSize=11.sp)
}

@Composable
internal fun WifiGalleryPanel(vm:MonitorViewModel){
    val w=vm.wifi;val f=w.cardFiles
    Column(Modifier.fillMaxSize().background(Color(0xFF10161B)).padding(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text("Карта GH5 · Wi-Fi",color=WifiAccent,fontSize=17.sp,modifier=Modifier.weight(1f))
            TextButton({f.back()},enabled=!f.transferBusy && !f.loading){Text(if(f.selected!=null || f.currentId!="0")"Назад" else "Живой просмотр")}
        }
        Text(f.message,color=WifiQuiet,fontSize=12.sp)
        if(f.loading || f.transferBusy){LinearProgressIndicator(Modifier.fillMaxWidth());if(f.transferBusy)TextButton({f.cancelTransfer()}){Text("Отменить передачу")}}
        val selected=f.selected
        if(selected!=null){
            Text(selected.name,color=Color.White,fontSize=14.sp)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                val bitmap=f.preview
                if(bitmap==null)Text(if(f.transferBusy)"Загрузка…" else "Нет JPEG-превью",color=WifiQuiet)
                else{
                    val density=LocalDensity.current
                    val fit=with(density){ImageGeometry.fit(maxWidth.toPx().coerceAtLeast(1f),maxHeight.toPx().coerceAtLeast(1f),bitmap.width,bitmap.height,1f,false,vm.previewQuarterTurns)}
                    AndroidView(factory={MonitorGlView(it)},update={it.display(bitmap,MonitorTools(),ViewTransform(),null,DisplayCalibration(),vm.previewQuarterTurns)},modifier=with(density){Modifier.size(fit.width.toDp(),fit.height.toDp())})
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({vm.rotatePreview()},modifier=Modifier.heightIn(min=48.dp)){Text("↻ 90°")}
                Button({f.download(selected)},enabled=!f.transferBusy && selected.original!=null,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("Сохранить оригинал")}
            }
            Text("Сохраняется файл камеры без перекодирования. Живой поток возобновится после выхода из просмотра карты.",color=WifiQuiet,fontSize=11.sp)
        }else{
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({f.browse()},enabled=!f.loading){Text("Обновить")}
                OutlinedButton({f.browse(offset=(f.start-30).coerceAtLeast(0))},enabled=!f.loading && f.start>0){Text("Предыдущие")}
                OutlinedButton({f.browse(offset=f.start+f.returned)},enabled=!f.loading && f.returned>0 && f.start+f.returned<f.total){Text("Следующие")}
                OutlinedButton({f.browse(offset=(f.total-30).coerceAtLeast(0))},enabled=!f.loading && f.total>30){Text("Конец списка")}
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())){
                f.items.forEach{item->OutlinedButton({f.open(item)},enabled=!f.loading,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){
                    Column(Modifier.fillMaxWidth()){
                        Text((if(item.container)"▣ " else "")+item.name,color=Color.White)
                        if(!item.container)Text(item.original?.let{listOf(it.mime,it.resolution).filter{v->v.isNotBlank()}.joinToString(" · ")} ?: if(item.preview!=null)"Предпросмотр" else "GH5 не предоставляет файл по Wi-Fi",color=WifiQuiet,fontSize=11.sp)
                    }
                }}
            }
        }
    }
}
