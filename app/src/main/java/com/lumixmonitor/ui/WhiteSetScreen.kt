package com.lumixmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind

/** Native-style measure controls at the edges, leaving the central live reference visible. */
@Composable internal fun WhiteSetScreen(vm:MonitorViewModel,modifier:Modifier){
    val r=vm.remoteControl
    Box(modifier.semantics{contentDescription="Экран замера ББ White Set"}){
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha=.75f)).padding(horizontal=10.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            WbAction("‹ Назад",Modifier.width(92.dp),enabled=!r.whiteBalanceBusy){r.cancelWhiteBalanceMeasurement()}
            WbModeIcon(vm.currentSettingValue(SettingKind.WHITE_BALANCE),Modifier.padding(horizontal=10.dp).size(32.dp),WbAccent)
            Text("${wbModeName(vm.currentSettingValue(SettingKind.WHITE_BALANCE))} · White Set",color=Color.White,fontSize=16.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).widthIn(max=520.dp).fillMaxWidth().background(Color.Black.copy(alpha=.8f)).padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("Заполните жёлтую рамку белым / серым листом",color=Color.White,fontSize=12.sp)
            Text(vm.whiteBalanceReference?.text ?: "Ожидание свежего кадра…",color=WbAccent,fontSize=11.sp)
            WbAction(if(r.whiteBalanceBusy)"Ожидание GH5…" else "SET · Измерить и записать",enabled=!r.whiteBalanceBusy,description="Измерить и записать"){r.sampleWhiteBalance()}
        }
    }
}
