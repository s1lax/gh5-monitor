package com.lumixmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import com.lumixmonitor.lumix.UsbWhiteBalance
import kotlin.math.cos
import kotlin.math.sin

/** Simple vector symbols using the GH5's familiar WB vocabulary; no emoji font dependency. */
@Composable internal fun WbModeIcon(mode:Int?,modifier:Modifier,color:Color=Color.White){
    Box(modifier,contentAlignment=Alignment.Center){
        when{
            mode in listOf(2,0x8014,0x8015)->Text(wbModeName(mode),color=color,fontSize=10.sp,maxLines=1)
            UsbWhiteBalance.isKelvin(mode)->Text(wbModeName(mode),color=color,fontSize=15.sp,maxLines=1)
            else->{
                Canvas(Modifier.fillMaxSize()){
                    val s=size.minDimension;val cx=size.width/2;val cy=size.height/2;val stroke=s*.065f
                    fun p(x:Float,y:Float)=Offset(cx+(x-.5f)*s,cy+(y-.5f)*s)
                    fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(color,p(x,y),p(xx,yy),stroke)
                    fun path(points:List<Pair<Float,Float>>,closed:Boolean=false){
                        val q=Path();points.forEachIndexed{i,v->val a=p(v.first,v.second);if(i==0)q.moveTo(a.x,a.y) else q.lineTo(a.x,a.y)};if(closed)q.close();drawPath(q,color,style=Stroke(stroke))
                    }
                    when{
                        UsbWhiteBalance.isCustom(mode)->{
                            path(listOf(.08f to .12f,.3f to .12f,.08f to .39f),true)
                            path(listOf(.08f to .88f,.3f to .88f,.08f to .61f),true)
                            drawRect(color,p(.36f,.22f),androidx.compose.ui.geometry.Size(s*.52f,s*.56f),style=Stroke(stroke))
                        }
                        mode==4->{
                            drawCircle(color,s*.19f,p(.5f,.5f),style=Stroke(stroke))
                            for(i in 0..7){val a=i*Math.PI/4;drawLine(color,p(.5f+cos(a).toFloat()*.3f,.5f+sin(a).toFloat()*.3f),p(.5f+cos(a).toFloat()*.43f,.5f+sin(a).toFloat()*.43f),stroke)}
                        }
                        mode==0x8008->{
                            val q=Path();val start=p(.17f,.7f);q.moveTo(start.x,start.y)
                            fun curve(a:Pair<Float,Float>,b:Pair<Float,Float>,c:Pair<Float,Float>){val aa=p(a.first,a.second);val bb=p(b.first,b.second);val cc=p(c.first,c.second);q.cubicTo(aa.x,aa.y,bb.x,bb.y,cc.x,cc.y)}
                            curve(.02f to .36f,.39f to .28f,.44f to .43f);curve(.59f to .07f,.94f to .32f,.84f to .53f);curve(1f to .57f,.92f to .78f,.76f to .76f);q.close();drawPath(q,color,style=Stroke(stroke))
                        }
                        mode==0x800f->{path(listOf(.1f to .43f,.4f to .14f,.7f to .43f));line(.23f,.4f,.23f,.84f);line(.23f,.84f,.6f,.84f);for(i in 0..2)line(.64f+i*.1f,.51f,.48f+i*.1f,.84f)}
                        mode==6->{drawCircle(color,s*.25f,p(.5f,.36f),style=Stroke(stroke));line(.34f,.56f,.39f,.72f);line(.66f,.56f,.61f,.72f);line(.36f,.76f,.64f,.76f);line(.4f,.88f,.6f,.88f)}
                        mode==7->path(listOf(.58f to .08f,.23f to .54f,.49f to .54f,.4f to .92f,.78f to .4f,.52f to .4f),true)
                        mode==5->{path(listOf(.1f to .32f,.9f to .32f,.9f to .65f,.1f to .65f),true);line(.06f,.2f,.06f,.8f);line(.94f,.2f,.94f,.8f)}
                        else->{line(.2f,.5f,.8f,.5f);line(.5f,.2f,.5f,.8f)}
                    }
                }
                if(UsbWhiteBalance.isCustom(mode))Text("${mode!!-0x800a}",color=color,fontSize=11.sp,modifier=Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
