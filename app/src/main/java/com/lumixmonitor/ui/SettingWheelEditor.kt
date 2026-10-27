package com.lumixmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

private val WheelAccent=Color(0xFFB4E4CC)
private val WheelQuiet=Color(0xFF9CA9B5)

/** Compact, discrete adjustment strip. The camera image is never dimmed. */
@Composable
internal fun SettingWheelEditor(vm:MonitorViewModel,kind:SettingKind,modifier:Modifier,
    chooseKind:(SettingKind)->Unit,close:()->Unit){
    val values=remember(kind,vm.isDemo,vm.settings,vm.isWifi,vm.wifi.options,vm.wifi.values){vm.allowedSettings(kind)}
    val usbWb=kind==SettingKind.WHITE_BALANCE && !vm.isWifi && !vm.isDemo
    val cameraValue=if(usbWb)vm.pendingWhiteBalance ?: vm.currentSettingValue(kind) else vm.currentSettingValue(kind)
    Column(modifier.background(Color(0xFF14191E),RoundedCornerShape(topStart=12.dp,topEnd=12.dp)).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(if(usbWb)"Баланс белого" else kind.label,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
            Text(if(vm.isDemo)"DEMO: ${vm.currentSettingText(kind)}" else "GH5: ${vm.currentSettingText(kind)}",color=WheelQuiet,fontSize=11.sp)
            Text("×",color=Color.White,fontSize=24.sp,modifier=Modifier.size(40.dp).semantics{contentDescription="Закрыть шкалу"}.clickable(role=Role.Button,onClick=close).wrapContentSize())
        }
        if(!usbWb)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf(SettingKind.ISO to "ISO",SettingKind.SHUTTER to "Затвор",SettingKind.APERTURE to "f",SettingKind.EXPOSURE to "EV",SettingKind.WHITE_BALANCE to "ББ").forEach{(value,label)->
                Text(label,color=if(kind==value)Color.Black else WheelQuiet,fontSize=12.sp,
                    modifier=Modifier.background(if(kind==value)WheelAccent else Color(0xFF252E36),RoundedCornerShape(5.dp))
                        .semantics{contentDescription="Шкала: ${value.label}";selected=kind==value}
                        .clickable(role=Role.Tab){chooseKind(value)}.padding(horizontal=14.dp,vertical=7.dp))
            }
        }
        if(kind==SettingKind.WHITE_BALANCE && !vm.isWifi && !vm.isDemo)UsbWhiteBalanceEditor(vm,values,cameraValue)
        else if(values.isEmpty())Text("Камера ещё не сообщила доступные значения",color=WheelQuiet,fontSize=12.sp,modifier=Modifier.padding(vertical=24.dp))
        else key(kind,values.map{it.first}){
            DiscreteSettingWheel(kind.label,values,cameraValue,kind in listOf(SettingKind.WHITE_BALANCE,SettingKind.PHOTO_STYLE,SettingKind.QUALITY)){vm.setSettingValue(kind,it)}
        }
        if(!usbWb)Text(if(vm.isDemo || !vm.statusText.startsWith("${kind.label}:"))"Листайте шкалу · касание значения или − / + для точного шага" else vm.statusText,
            color=WheelQuiet,fontSize=10.sp,maxLines=2,modifier=Modifier.padding(top=3.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscreteSettingWheel(label:String,values:List<Pair<Int,String>>,current:Int?,wide:Boolean=false,choose:(Int)->Unit){
    val initial=values.indexOfFirst{it.first==current}.coerceAtLeast(0)
    val list=rememberLazyListState(initialFirstVisibleItemIndex=initial)
    val scope=rememberCoroutineScope()
    val haptic=LocalHapticFeedback.current
    val latestChoose by rememberUpdatedState(choose)
    var interacted by remember{mutableStateOf(false)}
    var lastRequested by remember{mutableStateOf(current)}
    LaunchedEffect(current){
        if(current!=lastRequested && !list.isScrollInProgress){
            val at=values.indexOfFirst{it.first==current}
            if(at>=0){interacted=false;lastRequested=current;list.scrollToItem(at)}
        }
    }
    val centered by remember{derivedStateOf{
        val layout=list.layoutInfo
        val center=(layout.viewportStartOffset+layout.viewportEndOffset)/2
        layout.visibleItemsInfo.minByOrNull{abs(it.offset+it.size/2-center)}?.index ?: initial
    }}
    fun apply(index:Int){
        val value=values.getOrNull(index)?.first ?: return
        if(lastRequested!=value){lastRequested=value;haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);latestChoose(value)}
    }
    fun move(index:Int){
        val target=index.coerceIn(0,values.lastIndex)
        interacted=true
        if(target==centered && !list.isScrollInProgress)apply(target)
        else scope.launch{list.animateScrollToItem(target)}
    }
    LaunchedEffect(list,values){
        snapshotFlow{centered to list.isScrollInProgress}.distinctUntilChanged().collectLatest{(index,moving)->
            if(moving)interacted=true
            // Let a fast fling settle; never send every crossed detent to USB.
            if(moving)delay(140)
            if(interacted)apply(index)
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text("−",color=if(centered>0)Color.White else WheelQuiet.copy(alpha=.4f),fontSize=25.sp,
            modifier=Modifier.size(36.dp,76.dp).semantics{contentDescription="Предыдущее: $label"}
                .clickable(enabled=centered>0,role=Role.Button){move(centered-1)}.wrapContentSize())
        BoxWithConstraints(Modifier.weight(1f).height(76.dp).semantics{contentDescription="Шкала $label";stateDescription=values.getOrNull(centered)?.second ?: "—"}){
            val itemWidth=if(wide)140.dp else 76.dp
            val padding=((maxWidth-itemWidth)/2).coerceAtLeast(0.dp)
            LazyRow(state=list,flingBehavior=rememberSnapFlingBehavior(lazyListState=list),
                contentPadding=PaddingValues(horizontal=padding),modifier=Modifier.fillMaxSize()){
                itemsIndexed(values,key={_,entry->entry.first}){index,entry->
                    val chosen=index==centered
                    Column(Modifier.width(itemWidth).fillMaxHeight().semantics{selected=chosen}
                        .clickable(role=Role.Button){move(index)}.padding(top=17.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        Text(wheelLabel(entry.second),color=if(chosen)WheelAccent else WheelQuiet,
                            fontSize=if(entry.second.length>16)11.sp else if(chosen)18.sp else 13.sp,fontWeight=if(chosen)FontWeight.SemiBold else FontWeight.Normal,maxLines=if(wide)2 else 1)
                        Canvas(Modifier.fillMaxWidth().height(20.dp).padding(top=7.dp)){
                            drawLine(if(chosen)WheelAccent else WheelQuiet,Offset(size.width/2,0f),Offset(size.width/2,size.height),1.dp.toPx())
                            for(f in listOf(.125f,.375f,.625f,.875f))drawLine(WheelQuiet.copy(alpha=.4f),Offset(size.width*f,size.height*.6f),Offset(size.width*f,size.height),.7.dp.toPx())
                        }
                    }
                }
            }
            Canvas(Modifier.align(Alignment.TopCenter).size(10.dp,7.dp).padding(top=1.dp)){
                drawPath(Path().apply{moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width/2,size.height);close()},WheelAccent)
            }
        }
        Text("+",color=if(centered<values.lastIndex)Color.White else WheelQuiet.copy(alpha=.4f),fontSize=25.sp,
            modifier=Modifier.size(36.dp,76.dp).semantics{contentDescription="Следующее: $label"}
                .clickable(enabled=centered<values.lastIndex,role=Role.Button){move(centered+1)}.wrapContentSize())
    }
}

private fun wheelLabel(value:String)=when(value.lowercase()){
    "auto"->"Auto";"auto (i-iso)"->"i-Auto";"daylight"->"День";"cloud"->"Облачно"
    "shade"->"Тень";"incandescent"->"Лампа";"flash"->"Вспышка";"fluorescent"->"Люм."
    "auto cool"->"AWBc";"auto warm"->"AWBw";else->value
}
