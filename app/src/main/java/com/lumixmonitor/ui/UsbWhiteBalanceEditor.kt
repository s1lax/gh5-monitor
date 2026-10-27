package com.lumixmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind
import com.lumixmonitor.lumix.*

internal val WbAccent=Color(0xFFFFD54F)
private val WbQuiet=Color(0xFFADB5BC)

/** Choose a WB family first; fine adjustment always belongs to the current camera mode. */
@Composable
internal fun UsbWhiteBalanceEditor(vm:MonitorViewModel,modes:List<Pair<Int,String>>,mode:Int?){
    val r=vm.remoteControl
    var page by remember{mutableStateOf(UsbWhiteBalance.family(mode))}
    var fine by remember{mutableStateOf(false)}
    val ready=r.properties[LumixProp.WHITE_BALANCE_PARAM]?.current==mode && mode!=null
    LaunchedEffect(mode){r.requestRefresh();page=UsbWhiteBalance.family(mode)}
    val name=wbModeName(mode,vm.settings.whiteBalance)
    if(fine){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            WbAction("‹ Назад",Modifier.width(92.dp)){fine=false}
            WbModeIcon(mode,Modifier.padding(horizontal=8.dp).size(42.dp,28.dp))
            Text("Подстройка: $name",color=WbAccent,fontSize=14.sp)
        }
        val a=r.properties[0x02000053];val g=r.properties[0x02000054]
        WhiteBalanceGridEditor(mode,a?.current?.let(PanasonicData::signedMagnitude) ?: 0,g?.current?.let(PanasonicData::signedMagnitude) ?: 0,
            ready && !a?.choices.isNullOrEmpty() && !g?.choices.isNullOrEmpty()){ab,gm->r.setWhiteBalanceShift(ab,gm)}
        if(!ready)WbHint("Ожидание настроек выбранного режима ББ")
    }else{
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("Пресеты","Кельвины","Замер").forEach{label->
                WbChoice(label,page==label,Modifier.weight(1f),description="ББ: $label"){page=label}
            }
        }
        Spacer(Modifier.height(8.dp))
        val presetOrder=listOf(2,0x8014,0x8015,4,0x8008,0x800f,6,5,7)
        val entries=modes.filter{UsbWhiteBalance.family(it.first)==page}.sortedBy{if(page=="Пресеты")presetOrder.indexOf(it.first).takeIf{n->n>=0} ?: 99 else it.first}
        if(page=="Пресеты"){
            entries.chunked(3).forEach{row->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    row.forEach{entry->WbModeTile(entry.first,wbModeName(entry.first,entry.second),mode==entry.first,Modifier.weight(1f)){vm.setSettingValue(SettingKind.WHITE_BALANCE,entry.first)}}
                    repeat(3-row.size){Spacer(Modifier.weight(1f))}
                }
                Spacer(Modifier.height(5.dp))
            }
        }else{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                entries.forEach{entry->WbModeTile(entry.first,wbModeName(entry.first,entry.second),mode==entry.first,Modifier.weight(1f)){vm.setSettingValue(SettingKind.WHITE_BALANCE,entry.first)}}
            }
            if(page=="Кельвины"){
                val setting=RemoteSetting.all.first{it.code==LumixProp.WHITE_BALANCE_K_SET}
                val values=if(ready && UsbWhiteBalance.isKelvin(mode))r.choices(setting) else emptyList()
                if(values.isNotEmpty())key(mode,values.map{it.first}){
                    DiscreteSettingWheel("Температура ББ",values,r.properties[setting.code]?.current){r.set(setting,it)}
                }else WbHint(if(UsbWhiteBalance.isKelvin(mode))"Читаю температуру GH5…" else "Выберите ячейку K1–K4 для настройки температуры.")
            }else{
                WbAction("White Set · Измерить…",enabled=ready && UsbWhiteBalance.isCustom(mode) && !r.whiteBalanceBusy){vm.beginWhiteBalanceMeasurement()}
                WbHint("Выберите WB1–WB4. Замер по белому / серому листу перезапишет эту ячейку.")
            }
        }
        if(entries.isEmpty())WbHint("Камера ещё не сообщила доступные режимы")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().background(Color(0xFF20272D),RoundedCornerShape(5.dp)).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
            WbModeIcon(mode,Modifier.size(40.dp,24.dp))
            Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                Text(name,color=Color.White,fontSize=12.sp)
                if(ready){
                    val ab=r.properties[0x02000053]?.current?.let(PanasonicData::signedMagnitude)
                    val gm=r.properties[0x02000054]?.current?.let(PanasonicData::signedMagnitude)
                    if(ab!=null && gm!=null && (ab!=0 || gm!=0))Text(WhiteBalanceGrid.text(ab,gm),color=WbAccent,fontSize=10.sp)
                }
            }
            WbAction("Подстройка ›",Modifier.width(140.dp),ready,description="Подстройка ББ: $name"){fine=true}
        }
    }
    // Reserve feedback space: incoming confirmations must not move the touch grid.
    Text(r.feedback.ifBlank{" "},color=WbQuiet,fontSize=11.sp,maxLines=2,modifier=Modifier.fillMaxWidth().height(34.dp))
}

internal fun wbModeName(mode:Int?,fallback:String="—"):String=when(mode){
    2->"AWB";0x8014->"AWBc";0x8015->"AWBw";4->"День";5->"Люмин.";6->"Лампа";7->"Вспышка";0x8008->"Облачно";0x800f->"Тень"
    in 0x8010..0x8013->"K${mode!!-0x800f}";in 0x800b..0x800e->"WB${mode!!-0x800a}";else->fallback
}
@Composable private fun WbHint(text:String){Text(text,color=WbQuiet,fontSize=11.sp,modifier=Modifier.padding(vertical=6.dp))}
@Composable private fun WbChoice(text:String,active:Boolean,modifier:Modifier=Modifier,description:String,click:()->Unit){
    Box(modifier.heightIn(min=42.dp).background(if(active)WbAccent else Color(0xFF252E36),RoundedCornerShape(5.dp))
        .semantics{contentDescription=description;selected=active}.clickable(role=Role.Tab,onClick=click).padding(horizontal=8.dp,vertical=8.dp),contentAlignment=Alignment.Center){
        Text(text,color=if(active)Color.Black else WbQuiet,fontSize=12.sp)
    }
}
@Composable private fun WbModeTile(mode:Int,text:String,active:Boolean,modifier:Modifier,click:()->Unit){
    Row(modifier.heightIn(min=48.dp).border(1.dp,if(active)WbAccent else Color(0xFF47515A),RoundedCornerShape(4.dp))
        .background(if(active)Color(0xFF3C3622) else Color(0xFF20272D),RoundedCornerShape(4.dp))
        .semantics{contentDescription="ББ: $text";selected=active}.clickable(role=Role.Button,onClick=click).padding(7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
        WbModeIcon(mode,Modifier.size(if(mode in listOf(2,0x8014,0x8015))44.dp else 24.dp,24.dp),if(active)WbAccent else Color.White)
        if(!UsbWhiteBalance.isKelvin(mode) && !UsbWhiteBalance.isCustom(mode) && mode !in listOf(2,0x8014,0x8015))Text(text,color=if(active)WbAccent else Color.White,fontSize=11.sp,modifier=Modifier.padding(start=5.dp))
    }
}
@Composable internal fun WbAction(text:String,modifier:Modifier=Modifier.fillMaxWidth(),enabled:Boolean=true,description:String=text,click:()->Unit){
    OutlinedButton(onClick=click,enabled=enabled,modifier=modifier.heightIn(min=44.dp).semantics{contentDescription=description},contentPadding=PaddingValues(6.dp)){Text(text,color=if(enabled)WbAccent else WbQuiet,fontSize=12.sp)}
}
