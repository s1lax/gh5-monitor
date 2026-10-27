package com.lumixmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private val WbAccent=Color(0xFFB4E4CC)
private val WbQuiet=Color(0xFF9CA9B5)

/** Camera controls only: no phone tint or invented Kelvin for automatic/preset WB. */
@Composable
internal fun UsbWhiteBalanceEditor(vm:MonitorViewModel,modes:List<Pair<Int,String>>,mode:Int?){
    val r=vm.remoteControl
    var page by remember{mutableStateOf("Режим")}
    val ready=r.properties[LumixProp.WHITE_BALANCE_PARAM]?.current==mode && mode!=null
    LaunchedEffect(mode){r.requestRefresh()}
    DisposableEffect(r){onDispose{r.cancelWhiteBalanceMeasurement()}}
    fun leave(){r.cancelWhiteBalanceMeasurement()}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        listOf("Режим","Кельвины","Подстройка","Замер").forEach{label->
            WbTab(label,page==label){if(page!=label){leave();page=label}}
        }
    }
    when(page){
        "Режим"->if(modes.isNotEmpty())key(modes.map{it.first}){
            DiscreteSettingWheel("ББ",modes,mode,true){vm.setSettingValue(SettingKind.WHITE_BALANCE,it)}
        }else WbHint("Камера ещё не сообщила режимы ББ")
        "Кельвины"->{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                (0..3).filter{i->modes.any{it.first==0x8010+i}}.forEach{i->
                    WbTab("K${i+1}",mode==0x8010+i){vm.setSettingValue(SettingKind.WHITE_BALANCE,0x8010+i)}
                }
            }
            val setting=RemoteSetting.all.first{it.code==LumixProp.WHITE_BALANCE_K_SET}
            val values=if(ready && UsbWhiteBalance.isKelvin(mode))r.choices(setting) else emptyList()
            if(values.isNotEmpty())key(mode,values.map{it.first}){
                DiscreteSettingWheel("Температура ББ",values,r.properties[setting.code]?.current){r.set(setting,it)}
            }else WbHint(if(UsbWhiteBalance.isKelvin(mode))"Читаю диапазон температуры GH5…" else "Выберите K1–K4. У AWB и пресетов температура в K не задаётся; используйте подстройку A/B и G/M.")
        }
        "Подстройка"->{
            val a=r.properties[0x02000053];val g=r.properties[0x02000054]
            WhiteBalanceGridEditor(mode,a?.current?.let(PanasonicData::signedMagnitude) ?: 0,g?.current?.let(PanasonicData::signedMagnitude) ?: 0,
                ready && !a?.choices.isNullOrEmpty() && !g?.choices.isNullOrEmpty()){ab,gm->r.setWhiteBalanceShift(ab,gm)}
            if(!ready)WbHint("Ожидание настроек выбранного режима ББ")
        }
        "Замер"->{
            if(r.whiteBalanceMeasuring){
                vm.whiteBalanceReference?.let{WbHint(it.text)}
                WbHint("Заполните центральную область кадра нейтральным белым / серым эталоном. Замер перезапишет выбранную ячейку WB.")
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    WbButton("Измерить и записать",!r.whiteBalanceBusy,Modifier.weight(1f)){r.sampleWhiteBalance()}
                    WbButton("Закрыть замер",!r.whiteBalanceBusy,Modifier.weight(1f)){r.cancelWhiteBalanceMeasurement()}
                }
            }else{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    (0..3).filter{i->modes.any{it.first==0x800b+i}}.forEach{i->
                        WbTab("WB${i+1}",mode==0x800b+i){vm.setSettingValue(SettingKind.WHITE_BALANCE,0x800b+i)}
                    }
                }
                WbButton("Войти в замер ББ",ready && UsbWhiteBalance.isCustom(mode) && !r.whiteBalanceBusy){r.beginWhiteBalanceMeasurement()}
                WbHint("Выберите WB1–WB4. Вход открывает замер; запись выполняется отдельной кнопкой. Во время видеозаписи замер недоступен.")
            }
        }
    }
    // Keep the touch grid fixed while asynchronous camera feedback arrives.
    Text(r.feedback.ifBlank{" "},color=WbQuiet,fontSize=11.sp,maxLines=2,modifier=Modifier.fillMaxWidth().height(34.dp))
}
@Composable private fun WbHint(text:String){Text(text,color=WbQuiet,fontSize=11.sp,modifier=Modifier.padding(vertical=5.dp))}
@Composable private fun WbTab(text:String,active:Boolean,click:()->Unit){
    Box(Modifier.heightIn(min=40.dp).background(if(active)WbAccent else Color(0xFF252E36),RoundedCornerShape(5.dp))
        .semantics{contentDescription="ББ: $text";selected=active}.clickable(role=Role.Tab,onClick=click).padding(horizontal=12.dp,vertical=8.dp),contentAlignment=Alignment.Center){
        Text(text,color=if(active)Color.Black else WbQuiet,fontSize=12.sp)
    }
}
@Composable private fun WbButton(text:String,enabled:Boolean,modifier:Modifier=Modifier.fillMaxWidth(),click:()->Unit){
    OutlinedButton(onClick=click,enabled=enabled,modifier=modifier.heightIn(min=44.dp),contentPadding=PaddingValues(8.dp)){Text(text,fontSize=12.sp)}
}
