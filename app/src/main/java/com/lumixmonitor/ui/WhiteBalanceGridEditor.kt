package com.lumixmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.lumix.WhiteBalanceGrid
import kotlin.math.abs
import kotlin.math.max

@Composable internal fun WhiteBalanceGridEditor(mode:Int?,ab:Int,gm:Int,enabled:Boolean,choose:(Int,Int)->Unit){
    var draft by remember(mode){mutableStateOf(ab to gm)}
    var dragging by remember{mutableStateOf(false)}
    LaunchedEffect(mode,ab,gm){if(!dragging)draft=ab to gm}
    val latestChoose by rememberUpdatedState(choose)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Box(Modifier.size(196.dp,180.dp)){
            Text("G",color=Color.White,modifier=Modifier.align(Alignment.TopCenter))
            Text("M",color=Color.White,modifier=Modifier.align(Alignment.BottomCenter))
            Text("A",color=Color.White,modifier=Modifier.align(Alignment.CenterStart))
            Text("B",color=Color.White,modifier=Modifier.align(Alignment.CenterEnd))
            Canvas(Modifier.align(Alignment.Center).size(148.dp).semantics{contentDescription="Сетка ББ: ${WhiteBalanceGrid.text(draft.first,draft.second)}"}
                .pointerInput(mode,enabled){detectTapGestures{if(enabled){draft=WhiteBalanceGrid.point(it.x/size.width,it.y/size.height);latestChoose(draft.first,draft.second)}}}
                .pointerInput(mode,enabled){detectDragGestures(
                    onDragStart={dragging=true},onDragEnd={dragging=false},onDragCancel={dragging=false},
                    onDrag={change,_->if(enabled){change.consume();draft=WhiteBalanceGrid.point(change.position.x/size.width,change.position.y/size.height);latestChoose(draft.first,draft.second)}})}){
                // Illustrative color field, independent of the image and camera profile.
                for(y in 0..18)for(x in 0..18){
                    val xx=(x-9)/9f;val yy=(y-9)/9f;val edge=max(abs(xx),abs(yy))
                    val r=(1f-max(0f,xx)*.75f-max(0f,-yy)*.35f*edge).coerceIn(0f,1f)
                    val g=(1f-max(0f,yy)*.8f).coerceIn(0f,1f)
                    val b=(1f-max(0f,-xx)*.9f-max(0f,-yy)*.25f*edge).coerceIn(0f,1f)
                    drawRect(Color(r,g,b,if(enabled)1f else .4f),Offset(x*size.width/19,y*size.height/19),Size(size.width/19+1,size.height/19+1))
                }
                for(i in 0..19){val f=i/19f
                    drawLine(Color.Black.copy(alpha=.2f),Offset(f*size.width,0f),Offset(f*size.width,size.height),.6.dp.toPx())
                    drawLine(Color.Black.copy(alpha=.2f),Offset(0f,f*size.height),Offset(size.width,f*size.height),.6.dp.toPx())}
                drawLine(Color.Black.copy(alpha=.7f),Offset(size.width/2,0f),Offset(size.width/2,size.height),1.dp.toPx())
                drawLine(Color.Black.copy(alpha=.7f),Offset(0f,size.height/2),Offset(size.width,size.height/2),1.dp.toPx())
                val p=Offset(WhiteBalanceGrid.x(draft.first)*size.width,WhiteBalanceGrid.y(draft.second)*size.height)
                drawCircle(Color.Black,7.dp.toPx(),p);drawCircle(Color.White,5.dp.toPx(),p,style=Stroke(2.dp.toPx()))
            }
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(WhiteBalanceGrid.text(draft.first,draft.second),color=Color(0xFFB4E4CC),fontSize=14.sp)
            Text("Касание или перетаскивание точки",color=Color(0xFF9CA9B5),fontSize=11.sp)
            OutlinedButton(onClick={draft=0 to 0;latestChoose(0,0)},enabled=enabled,modifier=Modifier.heightIn(min=44.dp)){Text("Центр",fontSize=12.sp)}
        }
    }
}
