package com.lumixmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.RemoteCameraController
import com.lumixmonitor.monitor.ImageGeometry
import kotlinx.coroutines.*

/** Repeats only while pressed; cancellation, rotation and leaving the screen stop the producer. */
@Composable
internal fun Modifier.repeatPress(enabled:Boolean=true,onRelease:()->Unit={},action:()->Unit):Modifier {
    val latest by rememberUpdatedState(action)
    val release by rememberUpdatedState(onRelease)
    return semantics {role=Role.Button;if(!enabled)disabled();onClick {if(enabled)latest();enabled}}
        .pointerInput(enabled){
            if(enabled)detectTapGestures(onPress={
                latest()
                coroutineScope {
                    val repeating=launch {delay(380);while(isActive){latest();delay(200)}}
                    try {tryAwaitRelease()} finally {repeating.cancel();release()}
                }
            })
        }
}

/** GH5 does not return the newer magnification-state blocks: marker means requested position. */
@Composable
internal fun AssistNavigator(r:RemoteCameraController,quarterTurns:Int,modifier:Modifier=Modifier,showScale:Boolean=false,scaleEnabled:Boolean=true){
    AssistNavigator(r.assistTarget,quarterTurns,modifier,showScale,scaleEnabled,"Область MF","Задано телефоном",r::moveAssist,r::stepAssist)
}

@Composable
internal fun AssistNavigator(position:Pair<Int,Int>?,quarterTurns:Int,modifier:Modifier=Modifier,showScale:Boolean=true,scaleEnabled:Boolean=true,
    title:String="Область MF",source:String="Подтверждено GH5",move:(Int,Int)->Unit,step:(Boolean)->Unit){
    val accent=Color(0xFFB4E4CC)
    val point=position?.let{ImageGeometry.screenPoint(it.first/1000f,it.second/1000f,quarterTurns)}
    val target by rememberUpdatedState<(Offset,Float,Float)->Unit>({p,w,h->
        val sensor=ImageGeometry.sensorPoint((p.x/w).coerceIn(0f,1f),(p.y/h).coerceIn(0f,1f),quarterTurns)
        move((sensor.first*1000).toInt(),(sensor.second*1000).toInt())
    })
    Column(modifier.background(Color(0xE614191E),RoundedCornerShape(7.dp)).padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Text(title,color=accent,fontSize=10.sp)
        Canvas(Modifier.fillMaxWidth().height(65.dp).semantics{contentDescription="Навигатор лупы GH5"}
            .pointerInput(Unit){detectTapGestures{target(it,size.width.toFloat(),size.height.toFloat())}}
            .pointerInput(Unit){detectDragGestures{change,_->change.consume();target(change.position,size.width.toFloat(),size.height.toFloat())}}){
            drawRect(accent.copy(alpha=.7f),style=Stroke(1.dp.toPx()))
            drawLine(accent.copy(alpha=.2f),Offset(size.width/2,0f),Offset(size.width/2,size.height))
            drawLine(accent.copy(alpha=.2f),Offset(0f,size.height/2),Offset(size.width,size.height/2))
            point?.let{p->
                val c=Offset(p.first*size.width,p.second*size.height)
                drawCircle(accent,5.dp.toPx(),c,style=Stroke(1.5.dp.toPx()))
                drawLine(accent,c-Offset(9.dp.toPx(),0f),c+Offset(9.dp.toPx(),0f))
                drawLine(accent,c-Offset(0f,9.dp.toPx()),c+Offset(0f,9.dp.toPx()))
            }
        }
        Text(if(point==null)"GH5: позиция неизвестна" else source,color=Color(0xFF9CA9B5),fontSize=8.sp)
        if(showScale)Row(Modifier.fillMaxWidth()){
            listOf(false to "−1×",true to "+1×").forEach{(increase,label)->
                Box(Modifier.weight(1f).height(48.dp).semantics{contentDescription=if(increase)"Лупа + +1×" else "Лупа − −1×"}.repeatPress(scaleEnabled){step(increase)},contentAlignment=Alignment.Center){Text(label,color=if(scaleEnabled)accent else Color.Gray,fontSize=18.sp)}
            }
        }
        @Composable fun arrow(label:String,glyph:String,dx:Float,dy:Float){
            Box(Modifier.size(48.dp).semantics{contentDescription=label}.repeatPress{
                val p=position ?: (500 to 500);val screen=ImageGeometry.screenPoint(p.first/1000f,p.second/1000f,quarterTurns)
                val sensor=ImageGeometry.sensorPoint((screen.first+dx).coerceIn(0f,1f),(screen.second+dy).coerceIn(0f,1f),quarterTurns)
                move((sensor.first*1000).toInt(),(sensor.second*1000).toInt())
            },contentAlignment=Alignment.Center){Text(glyph,color=accent,fontSize=22.sp)}
        }
        arrow("Область MF вверх","▲",0f,-.04f)
        Row(verticalAlignment=Alignment.CenterVertically){
            arrow("Область MF влево","◀",-.04f,0f)
            Box(Modifier.size(48.dp).semantics{contentDescription="Область MF в центр"}.repeatPress{move(500,500)},contentAlignment=Alignment.Center){Text("◎",color=accent,fontSize=22.sp)}
            arrow("Область MF вправо","▶",.04f,0f)
        }
        arrow("Область MF вниз","▼",0f,.04f)
    }
}
