package com.lumixmonitor.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumixmonitor.MonitorTools
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind
import com.lumixmonitor.monitor.MonitorGlView
import com.lumixmonitor.monitor.Scopes
import com.lumixmonitor.monitor.ImageGeometry
import com.lumixmonitor.monitor.ScopeRaster
import com.lumixmonitor.monitor.DisplayCalibration
import com.lumixmonitor.lumix.WhiteBalanceRegion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

private val Panel=Color(0xFF14191E)
private val Accent=Color(0xFFB4E4CC)
private val Quiet=Color(0xFF9CA9B5)

/** Permanent controls live outside the fitted camera image. */
@Composable
fun FieldMonitor(vm:MonitorViewModel){
    ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(fontSize=12.sp,lineHeight=16.sp)){
        FieldMonitorBody(vm)
    }
}

@Composable
private fun FieldMonitorBody(vm:MonitorViewModel){
    var menu by remember{mutableStateOf<String?>(null)}
    var selectedSetting by remember{mutableStateOf<SettingKind?>(null)}
    var quality by remember{mutableStateOf(false)}
    var clean by remember{mutableStateOf(false)}
    var cleanHint by remember{mutableStateOf(false)}
    val measuring=vm.remoteControl.whiteBalanceMeasuring && !vm.isWifi
    val wbEditorOpen=menu=="value" && selectedSetting==SettingKind.WHITE_BALANCE && !vm.isWifi && !clean
    DisposableEffect(wbEditorOpen){onDispose{if(wbEditorOpen)vm.remoteControl.cancelWhiteBalanceMeasurement()}}
    BackHandler(enabled=measuring || clean || menu!=null || vm.selectingAssist || (vm.isWifi && vm.wifi.gallery)){
        if(measuring)vm.remoteControl.cancelWhiteBalanceMeasurement()
        else if(vm.isWifi && vm.wifi.gallery)vm.wifi.cardFiles.back() else {clean=false;menu=null;vm.cancelAssistSelection()}
    }
    LaunchedEffect(clean){cleanHint=clean;if(clean){delay(3000);cleanHint=false}}
    LaunchedEffect(vm.wifi.gallery){if(vm.wifi.gallery){menu=null;clean=false}}
    val tools=vm.tools
    val frame=vm.displayFrame
    val context=LocalContext.current
    val window=(context as? Activity)?.window
    DisposableEffect(window){
        val previous=window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        onDispose{window?.attributes=window?.attributes?.apply{screenBrightness=previous}}
    }
    LaunchedEffect(vm.screenBrightness){window?.attributes=window?.attributes?.apply{screenBrightness=vm.screenBrightness}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.loadLut(uri)}
    val scopeVisible=tools.showHistogram || tools.showWaveform || tools.showVectorscope
    fun openSetting(kind:SettingKind){selectedSetting=kind;menu="value"}

    @Composable fun QuickTools(horizontal:Boolean,cellWidth:Dp,cellHeight:Dp=58.dp){
        Rail("Пикинг","PK",tools.focusPeaking,horizontal=horizontal,cellWidth=cellWidth,cellHeight=cellHeight){vm.updateTools{it.copy(focusPeaking=!it.focusPeaking)}}
        Rail("Зебра","Z",tools.zebra,horizontal=horizontal,cellWidth=cellWidth,cellHeight=cellHeight){vm.updateTools{it.copy(zebra=!it.zebra)}}
        Rail("Трети","▦",tools.showThirds,horizontal=horizontal,cellWidth=cellWidth,cellHeight=cellHeight){vm.updateTools{it.copy(showThirds=!it.showThirds)}}
    }
    @Composable fun Setting(kind:SettingKind,label:String,value:String,horizontal:Boolean=false,width:Dp=80.dp,height:Dp=60.dp){
        Rail(label,value,horizontal=horizontal,cellWidth=width,cellHeight=height){openSetting(kind)}
    }
    val preview: @Composable ()->Unit = {
        Column(Modifier.fillMaxSize()){
        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
            if(frame==null)Text("Ожидание кадра…",color=Quiet) else CameraViewport(vm,frame,allowTouchAf=!clean && !measuring,onTap={if(clean)clean=false},onLongPress={if(!measuring){clean=!clean;menu=null}})
            if((vm.focusAssistActive || vm.selectingAssist) && !clean && !vm.isDemo && !measuring){
                val nav=Modifier.align(Alignment.BottomEnd).padding(6.dp).width(164.dp)
                if(vm.isWifi)WifiAssistNavigator(vm,nav) else AssistNavigator(vm.remoteControl,vm.previewQuarterTurns,nav,showScale=true,scaleEnabled=vm.cameraFocusAssist)
            }
            if(vm.isWifi)WifiFrameNotice(vm,Modifier.align(Alignment.TopCenter))
            if(vm.heldFrame!=null && !measuring)Text("СТОП-КАДР",color=Color(0xFFFFCC80),modifier=Modifier.align(Alignment.TopStart).background(Panel).padding(6.dp))
        }
        if(vm.isWifi && !clean)WifiStatusStrip(vm){menu="camera"}
        }
    }
    val assistLabel=if(vm.assistPending)"…" else if(vm.selectingAssist)"◎" else "MF"
    val t=vm.cameraTelemetry
    val actualRec=t.recording==true && android.os.SystemClock.elapsedRealtime()-t.observedAtMs<4500
    val connectionLabel=if(actualRec)"● REC" else if(vm.proxyRecording)"● MP4" else if(vm.isDemo)"DEMO" else if(vm.isWifi)"Wi-Fi" else "USB"
    val fps=Regex("([0-9.]+) FPS").find(vm.performanceText)?.groupValues?.get(1)?.let{"$it fps"} ?: "—"
    val cameraStatus=(if(vm.isWifi)"GH5 ${vm.wifi.cameraStatus.battery?.label ?: "—"}" else (t.battery ?: vm.cameraBattery)?.let{"GH5 $it%"} ?: "GH5 —")+if(vm.neutralPreview)" · USB без LUT" else if(vm.displayCalibration.isAdjusted)" · V" else ""
    var captureNotice by remember{mutableStateOf(false)}
    LaunchedEffect(vm.captureFeedback){captureNotice=vm.captureFeedback.isNotEmpty();if(captureNotice){delay(4000);captureNotice=false}}

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).pointerInput(clean){if(clean)detectTapGestures(onTap={clean=false})}){
        val portrait=maxHeight>maxWidth
        val controlsWidth=maxWidth
        val railCellHeight=((maxHeight-88.dp)/6).coerceAtMost(64.dp)
        if(measuring)preview()
        else if(clean)preview()
        else if(portrait){
            Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
                    Rail("Меню","≡",active=menu!=null,horizontal=true,cellWidth=64.dp){menu=if(menu==null)"tools" else null}
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                        Text("${vm.shootingModeText} · $connectionLabel · $fps",color=Accent,fontSize=12.sp)
                        Text(cameraStatus,color=Quiet,fontSize=10.sp)
                    }
                    Rail("${vm.previewQuarterTurns*90}°","↻",horizontal=true,cellWidth=48.dp){vm.rotatePreview()}
                    Rail("Чистый","↔",horizontal=true,cellWidth=56.dp){clean=true;menu=null}
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){QuickTools(true,controlsWidth/3,54.dp)}
                Box(Modifier.weight(1f).fillMaxWidth()){preview()}
                if(scopeVisible)ScopePanel(vm.scopes,vm.scopeRaster,tools,Modifier.fillMaxWidth().height(156.dp),horizontal=true)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Setting(SettingKind.ISO,"ISO",vm.observedSettings.iso,true,controlsWidth/5)
                    Setting(SettingKind.SHUTTER,"Затвор",vm.observedSettings.shutter,true,controlsWidth/5)
                    Setting(SettingKind.APERTURE,"Диафр.",vm.observedSettings.aperture,true,controlsWidth/5)
                    Setting(SettingKind.WHITE_BALANCE,"ББ",compactWb(vm.whiteBalanceText),true,controlsWidth/5)
                    Setting(SettingKind.EXPOSURE,"EV",vm.settings.exposureComp,true,controlsWidth/5)
                }
                Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Rail("Лупа GH5",assistLabel,vm.focusAssistActive,horizontal=true,cellWidth=88.dp,enabled=!vm.assistPending){vm.toggleFocusAssist()}}
                    ShutterButton(vm,Modifier.width(88.dp))
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Rail("Фокус","AF",horizontal=true,cellWidth=88.dp){vm.autoFocus()}}
                }
            }
        }else{
            Row(Modifier.fillMaxSize()){
                val cellHeight=railCellHeight
                Column(Modifier.width(72.dp).fillMaxHeight().padding(horizontal=5.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Rail("Меню","≡",active=menu!=null){menu=if(menu==null)"tools" else null}
                    Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.SpaceEvenly){
                        QuickTools(false,62.dp,cellHeight)
                        Rail("Фокус","AF",cellHeight=cellHeight){vm.autoFocus()}
                    }
                    Rail("${vm.previewQuarterTurns*90}°","↻",cellHeight=44.dp){vm.rotatePreview()}
                    Rail("Чистый","↔",cellHeight=44.dp){clean=true;menu=null}
                    Text(vm.shootingModeText,color=Accent,fontSize=12.sp,maxLines=1,modifier=Modifier.semantics{contentDescription="Режим камеры: ${vm.shootingModeText}"})
                    Text(connectionLabel,color=if(vm.isDemo)Color(0xFFFFCC80) else Accent,fontSize=10.sp)
                    Text(fps,color=Quiet,fontSize=8.sp)
                    Text(cameraStatus,color=Quiet,fontSize=8.sp,maxLines=1)
                }
                Box(Modifier.weight(1f).fillMaxHeight()){preview()}
                if(scopeVisible)ScopePanel(vm.scopes,vm.scopeRaster,tools,Modifier.width(156.dp).fillMaxHeight())
                Column(Modifier.width(84.dp).fillMaxHeight().padding(horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.SpaceEvenly){
                        Setting(SettingKind.ISO,"ISO",vm.observedSettings.iso,height=cellHeight)
                        Setting(SettingKind.SHUTTER,"Затвор",vm.observedSettings.shutter,height=cellHeight)
                        Setting(SettingKind.APERTURE,"Диафр.",vm.observedSettings.aperture,height=cellHeight)
                    }
                    ShutterButton(vm,Modifier.fillMaxWidth())
                    Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.SpaceEvenly){
                        Setting(SettingKind.WHITE_BALANCE,"ББ",compactWb(vm.whiteBalanceText),height=cellHeight)
                        Setting(SettingKind.EXPOSURE,"EV",vm.settings.exposureComp,height=cellHeight)
                        Rail("Лупа GH5",assistLabel,vm.focusAssistActive,enabled=!vm.assistPending,cellHeight=cellHeight){vm.toggleFocusAssist()}
                    }
                }
            }
        }
        if(captureNotice && !clean)Text(vm.captureFeedback,color=Color.White,fontSize=12.sp,modifier=Modifier.align(Alignment.TopCenter).padding(top=if(portrait)106.dp else 8.dp).background(Panel.copy(alpha=.92f),RoundedCornerShape(5.dp)).padding(9.dp))
        if(vm.isWifi && vm.wifi.gallery)WifiGalleryPanel(vm)
        if(menu=="value" && selectedSetting!=null && !clean && !measuring){
            Box(Modifier.fillMaxSize().clickable(interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()},indication=null){menu=null})
            SettingWheelEditor(vm,selectedSetting!!,Modifier.align(Alignment.BottomCenter).widthIn(max=520.dp).fillMaxWidth().heightIn(max=maxHeight),
                chooseKind={selectedSetting=it},close={menu=null})
        }
        if(measuring)WhiteSetScreen(vm,Modifier.fillMaxSize())
        if(menu!=null && menu!="value" && !clean){
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.3f)).clickable{menu=null})
            val panelModifier=if(portrait)Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.8f) else Modifier.align(Alignment.CenterStart).width(360.dp).fillMaxHeight()
            Column(panelModifier.background(Panel).padding(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(when(menu){"camera"->"Камера";"display"->"Экран / LUT";"preview-diagnosis"->"Фото / видео: яркость";"connection"->"Связь / журнал";"value"->selectedSetting?.label ?: "Параметр";else->"Инструменты"},color=Color.White,fontSize=16.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={menu=null}){Text("×",fontSize=22.sp)}
            }
            if(menu!="value")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                listOf("tools" to "Инстр.","camera" to "Камера","display" to "Экран","connection" to "Связь").forEach{(key,label)->Box(Modifier.weight(1f).height(48.dp).clickable{menu=key},contentAlignment=Alignment.Center){Text(label,fontSize=13.sp,color=if(menu==key)Accent else Quiet)}}
            }
            if(menu=="tools")PageTabs(vm,Modifier.fillMaxWidth().padding(bottom=8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
                when(menu){
                    "tools"->{
                        Text("Инструменты всех разделов работают вместе.",color=Quiet,fontSize=12.sp)
                        Action("Пикинг + зебра + направляющие"){vm.updateTools{it.copy(focusPeaking=true,zebra=true,showThirds=true,showFramelines=true,falseColor=false)}}
                        if(vm.selectedPage=="frame"){
                        Toggle("Трети",tools.showThirds){vm.updateTools{it.copy(showThirds=!it.showThirds)}}
                        Toggle("Уровень GH5",tools.showLevel){vm.updateTools{it.copy(showLevel=!it.showLevel)}}
                        Toggle("Центр",tools.showCenterMarker){vm.updateTools{it.copy(showCenterMarker=!it.showCenterMarker)}}
                        Toggle("Рамка кадра",tools.showFramelines){vm.updateTools{it.copy(showFramelines=!it.showFramelines)}}
                        ChoiceRow(listOf("2.39" to 2.39f,"1.85" to 1.85f,"16:9" to 16f/9f,"9:16" to 9f/16f),tools.guideAspect){v->vm.updateTools{it.copy(guideAspect=v,showFramelines=true)}}
                        ToolSlider("Безопасная зона",tools.safeArea,"${(tools.safeArea*100).toInt()}%",.7f..1f){v->vm.updateTools{it.copy(safeArea=v)}}
                        Action(if(vm.heldFrame==null)"Стоп-кадр для сравнения" else "Вернуться к живому изображению"){vm.toggleHold()}
                        Action("Повернуть кадр на 90° · сейчас ${vm.previewQuarterTurns*90}°"){vm.rotatePreview()}
                        Text("Desqueeze",color=Quiet,fontSize=12.sp)
                        ChoiceRow(listOf("1×" to 1f,"1.33×" to 1.33f,"1.5×" to 1.5f,"2×" to 2f),tools.desqueeze){v->vm.updateTools{it.copy(desqueeze=v)}}
                        }
                        if(vm.selectedPage=="focus"){
                        Toggle("Пикинг",tools.focusPeaking){vm.updateTools{it.copy(focusPeaking=!it.focusPeaking)}}
                        ToolSlider("Порог границ",tools.peakingThreshold,String.format("%.2f",tools.peakingThreshold),.02f.. .3f){v->vm.updateTools{it.copy(peakingThreshold=v)}}
                        Action(if(vm.nativePixels)"Пиксели 1:1 ✓" else "Пиксели 1:1"){vm.toggleNativePixels()}
                        Toggle("AF касанием кадра",vm.touchFocusEnabled){vm.toggleTouchFocus()}
                        Action("Автофокус GH5"){vm.autoFocus()}
                        Action("Лупа GH5 / выбрать область"){menu=null;vm.toggleFocusAssist()}
                        Action("Полное управление фокусом GH5"){menu="camera"}
                        Text("Шаги MF, режим и зона AF, блокировка AF/AE — в разделе «Камера → Фокус». Масштаб ±1× доступен на навигаторе, когда включена лупа.",color=Quiet,fontSize=12.sp)
                        }
                        if(vm.selectedPage=="expose"){
                        Toggle("Зебра",tools.zebra){vm.updateTools{it.copy(zebra=!it.zebra)}}
                        ToolSlider("Порог зебры JPEG",tools.zebraThreshold,"${(tools.zebraThreshold*100).toInt()}%",.5f..1f){v->vm.updateTools{it.copy(zebraThreshold=v)}}
                        Toggle("False color",tools.falseColor){vm.updateTools{it.copy(falseColor=!it.falseColor)}}
                        Text("JPEG: <3% фиолетовый · <10% синий · 40–50% зелёный · 50–60% розовый · 90–95% жёлтый · ≥95% красный",color=Quiet,fontSize=11.sp)
                        Toggle("Гистограмма",tools.showHistogram){vm.updateTools{it.copy(showHistogram=!it.showHistogram)}}
                        Toggle("RGB-гистограмма",tools.rgbHistogram){vm.updateTools{it.copy(rgbHistogram=!it.rgbHistogram)}}
                        Toggle("Waveform",tools.showWaveform){vm.updateTools{it.copy(showWaveform=!it.showWaveform)}}
                        Toggle("Vectorscope",tools.showVectorscope){vm.updateTools{it.copy(showVectorscope=!it.showVectorscope)}}
                        Toggle("LUT",tools.lutEnabled){if(vm.viewingLut==null)menu="display" else vm.updateTools{it.copy(lutEnabled=!it.lutEnabled)}}
                        Action("Настроить LUT и экран"){menu="display"}
                        }
                        Action("Сбросить увеличение"){if(vm.nativePixels)vm.toggleNativePixels();vm.setTransform(1f)}
                        Text(if(vm.isWifi)"Двойной тап — лупа GH5. В лупе: pinch — масштаб, перетаскивание — область. ↻ поворачивает только изображение. Долгое нажатие скрывает и возвращает панели. Инструменты измеряют JPEG до LUT." else "Pinch — увеличение, перетаскивание — область, двойной тап — 2× / 1×. Долгое нажатие скрывает и возвращает панели. Инструменты измеряют JPEG до LUT.",color=Quiet,fontSize=11.sp)
                    }
                    "camera"->{if(vm.isWifi)WifiCameraPanel(vm,::openSetting) else RemoteCameraPanel(vm,::openSetting)}
                    "preview-diagnosis"->{PreviewDiagnosisPanel(vm)}
                    "display"->{
                        if(!vm.isWifi)Action("Сравнить яркость фото / видео"){menu="preview-diagnosis"}
                        Text("Яркость дисплея телефона",color=Quiet,fontSize=12.sp)
                        Slider(value=if(vm.screenBrightness<0f).5f else vm.screenBrightness,onValueChange={vm.setBrightness(it)},valueRange=.05f..1f)
                        Action("Системная яркость"){vm.setBrightness(-1f)}
                        val calibration=vm.displayCalibration
                        Text("Калибровка просмотра",fontSize=13.sp,color=Color.White)
                        Toggle("Коррекция просмотра",calibration.enabled){vm.updateDisplayCalibration(calibration.copy(enabled=!calibration.enabled))}
                        ToolSlider("Просмотр EV",calibration.ev,String.format("%+.2f EV",calibration.ev),-3f..3f){vm.updateDisplayCalibration(calibration.copy(enabled=true,ev=it))}
                        ToolSlider("Гамма",calibration.gamma,String.format("%.2f",calibration.gamma),.5f..2f){vm.updateDisplayCalibration(calibration.copy(enabled=true,gamma=it))}
                        ToolSlider("Контраст",calibration.contrast,String.format("%.2f",calibration.contrast),.5f..1.5f){vm.updateDisplayCalibration(calibration.copy(enabled=true,contrast=it))}
                        Action("Сбросить калибровку"){vm.updateDisplayCalibration(DisplayCalibration())}
                        Text("V — коррекция просмотра. Не меняет экспозицию GH5, JPEG, MP4 или измерения. Зебра и графики анализируют исходное USB-превью. Для сравнения: фиксированные M, ISO и ББ; Constant Preview ON, Live View Boost OFF. Коррекция не исправляет автоусиление камеры.",fontSize=11.sp,color=Quiet)
                        Text("LUT: ${vm.viewingLut?.title ?: "не загружен"}",fontSize=12.sp,color=Color.White)
                        Action("Открыть 3D .cube"){picker.launch(arrayOf("*/*"))}
                        if(vm.viewingLut!=null)Action("Удалить загруженную LUT"){vm.clearLut()}
                        Toggle("Применять LUT",tools.lutEnabled){vm.updateTools{it.copy(lutEnabled=!it.lutEnabled)}}
                        ToolSlider("Сила LUT",tools.lutAmount,"${(tools.lutAmount*100).toInt()}%",0f..1f){v->vm.updateTools{it.copy(lutAmount=v)}}
                        Text("LUT — только для просмотра. JPEG и измерения остаются до LUT. LUT 2–64, SDR; выход за 0–1 ограничивается. Преобразование V-Log включайте только для соответствующего превью.",fontSize=11.sp,color=Quiet)
                        Action(if(vm.heldFrame==null)"Стоп-кадр для сравнения" else "Вернуться к живому изображению"){vm.toggleHold()}
                        Action("Сохранить исходное JPEG-превью"){vm.savePreview()}
                        Action(if(vm.proxyRecording)"Остановить MP4-превью" else "Записать MP4-превью на телефон"){if(vm.proxyRecording)vm.stopProxy() else vm.startProxy()}
                        if(vm.proxyStatus.isNotEmpty())Text(vm.proxyStatus,color=Accent,fontSize=11.sp)
                        Text("MP4-превью: исходные USB-кадры, без LUT, интерфейса и звука. Запись на SD GH5 управляется отдельно.",fontSize=11.sp,color=Quiet)
                    }
                    "connection"->{
                        Action(if(vm.isWifi)"Перейти на USB" else "Подключиться по Wi-Fi"){vm.showConnectionSetup(!vm.isWifi);menu=null}

                        Text(vm.statusText,color=Accent,fontSize=12.sp)
                        Text(vm.performanceText,color=Quiet,fontSize=11.sp)
                        Text("Кадров: ${vm.frameCount}\nJPEG: ${vm.actualFrameSize ?: "—"}\nСчётчик FPS — превью, не частота записи GH5.",fontSize=11.sp,color=Quiet)
                        if(vm.isWifi)Action("Wi-Fi: качество и управление"){menu="camera"} else Action("Качество USB-превью"){quality=true}
                        Action("Обновить журнал"){vm.refreshDiagnostics()}
                        Action("Проверка режимов живью"){if(!vm.isDemo)vm.probeLiveview()}
                        Action("Список значений GH5"){if(!vm.isDemo)vm.logAllowedValues()}
                        Action("Диагностика AF"){if(!vm.isDemo)vm.dumpAfStructure()}
                        Action("Переподключить GH5"){vm.reconnect();menu=null}
                        Action("Демо без камеры"){vm.startDemo();menu=null}
                        Action("Отключить / выйти из демо"){vm.disconnect()}
                        vm.diagnostics.takeLast(45).forEach{Text(it,color=Quiet,fontSize=9.sp,fontFamily=FontFamily.Monospace)}
                    }

                }
            }
            Text(vm.statusText,color=Quiet,fontSize=10.sp,maxLines=3,modifier=Modifier.padding(top=8.dp))
        }
    }
    if(clean && cleanHint)Text("Коснитесь экрана или нажмите Назад, чтобы вернуть панели",color=Color.White,fontSize=12.sp,modifier=Modifier.align(Alignment.BottomCenter).padding(14.dp).background(Panel.copy(alpha=.85f),RoundedCornerShape(5.dp)).clickable{clean=false}.padding(10.dp))
    }
    if(quality)QualityDialog(vm){quality=false}
    vm.probeReport?.let{report->AlertDialog(onDismissRequest={vm.clearProbeReport()},title={Text("Проверка живью")},text={Text(report,modifier=Modifier.verticalScroll(rememberScrollState()),fontSize=11.sp)},confirmButton={TextButton(onClick={vm.clearProbeReport()}){Text("Закрыть")}})}
}

@Composable
private fun Rail(label:String,value:String,active:Boolean?=null,horizontal:Boolean=false,enabled:Boolean=true,cellWidth:Dp=76.dp,repeatable:Boolean=false,cellHeight:Dp=58.dp,onClick:()->Unit){
    val selected=active==true
    val action=if(repeatable)Modifier.repeatPress(enabled,action=onClick) else if(active==null)Modifier.clickable(enabled=enabled,role=Role.Button,onClick=onClick) else Modifier.toggleable(value=active,enabled=enabled,role=Role.Checkbox,onValueChange={onClick()})
    Column(Modifier.then(if(horizontal)Modifier.width(cellWidth) else Modifier.fillMaxWidth()).height(cellHeight).padding(vertical=3.dp).background(if(selected)Accent.copy(alpha=.17f) else Color.Transparent,RoundedCornerShape(7.dp)).semantics(mergeDescendants=true){contentDescription="$label $value"}.then(action),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Text(value,color=if(selected)Accent else if(enabled)Color.White else Quiet,fontSize=if(value.length>8)13.sp else 19.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium,maxLines=1)
        Text(label,color=if(selected)Accent else Quiet,fontSize=11.sp,lineHeight=14.sp,maxLines=1)
    }
}

@Composable
private fun ShutterButton(vm:MonitorViewModel,modifier:Modifier){
    val enabled=!vm.capturePending && !(vm.isWifi && vm.wifi.commandBusy)
    val t=vm.cameraTelemetry
    val recording=t.recording==true && android.os.SystemClock.elapsedRealtime()-t.observedAtMs<4500
    Box(modifier.height(88.dp).semantics(mergeDescendants=true){contentDescription=if(recording)"Остановить запись GH5" else "Спуск ${if(enabled)"●" else "…"}"}
        .clickable(enabled=enabled,role=Role.Button){vm.shutter()},contentAlignment=Alignment.Center){
        Canvas(Modifier.size(62.dp)){
            val c=if(!enabled)Quiet else if(recording)Color(0xFFFF6666) else Accent
            drawCircle(c,radius=size.minDimension/2-2.dp.toPx(),style=Stroke(2.dp.toPx()))
            if(recording){val edge=size.minDimension*.4f;drawRoundRect(c,Offset((size.width-edge)/2,(size.height-edge)/2),Size(edge,edge),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))}
            else drawCircle(c,radius=size.minDimension/2-7.dp.toPx())
        }
        if(!enabled || recording)Text(if(!enabled)"Съёмка…" else "Стоп",color=if(enabled)Color.White else Quiet,fontSize=11.sp,modifier=Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PageTabs(vm:MonitorViewModel,modifier:Modifier){
    Row(modifier.height(48.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
        listOf("frame" to "Кадр","focus" to "Фокус","expose" to "Экспозиция").forEach{(key,label)->
            Box(Modifier.weight(1f).fillMaxHeight().background(if(vm.selectedPage==key)Accent else Color.Transparent,RoundedCornerShape(5.dp)).semantics(mergeDescendants=true){contentDescription=when(key){"frame"->"Страница кадрирования";"focus"->"Страница фокуса";else->"Страница экспозиции"}}.selectable(selected=vm.selectedPage==key,role=Role.Tab,onClick={vm.selectPage(key)}),contentAlignment=Alignment.Center){Text(label,color=if(vm.selectedPage==key)Color.Black else Quiet,fontSize=12.sp)}
        }
    }
}

@Composable
private fun Action(label:String,action:()->Unit){OutlinedButton(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp)){Text(label,fontSize=14.sp)}}
@Composable
private fun Toggle(label:String,value:Boolean,action:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClick=action),verticalAlignment=Alignment.CenterVertically){Text(label,color=Color.White,fontSize=14.sp,modifier=Modifier.weight(1f));Switch(checked=value,onCheckedChange={action()})}}
@Composable
private fun ToolSlider(label:String,value:Float,formatted:String,range:ClosedFloatingPointRange<Float>,change:(Float)->Unit){Column{Text("$label · $formatted",color=Quiet,fontSize=11.sp);Slider(value=value.coerceIn(range.start,range.endInclusive),onValueChange=change,valueRange=range,modifier=Modifier.semantics{contentDescription=label})}}
@Composable
private fun ChoiceRow(items:List<Pair<String,Float>>,selected:Float,choose:(Float)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){items.forEach{(text,value)->Box(Modifier.weight(1f).height(36.dp).background(if(abs(value-selected)<.01f)Accent else Color(0xFF293139),RoundedCornerShape(4.dp)).clickable{choose(value)},contentAlignment=Alignment.Center){Text(text,color=if(abs(value-selected)<.01f)Color.Black else Color.White,fontSize=11.sp)}}}}

@Composable
private fun CameraViewport(vm:MonitorViewModel,bitmap:Bitmap,allowTouchAf:Boolean,onTap:()->Unit,onLongPress:()->Unit){
    val tools=vm.tools
    val measuring=vm.remoteControl.whiteBalanceMeasuring && !vm.isWifi
    val latestTap by rememberUpdatedState(onTap)
    val touchAfAllowed by rememberUpdatedState(allowTouchAf)
    val latestLongPress by rememberUpdatedState(onLongPress)
    var bounds by remember{mutableStateOf(IntSize(1,1))}
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
        val fit=with(density){ImageGeometry.fit(maxWidth.toPx().coerceAtLeast(1f),maxHeight.toPx().coerceAtLeast(1f),bitmap.width,bitmap.height,if(measuring)1f else tools.desqueeze,vm.nativePixels && !measuring,vm.previewQuarterTurns)}
        val width=with(density){fit.width.toDp()};val height=with(density){fit.height.toDp()}
        Box(Modifier.size(width,height).onSizeChanged{bounds=it}
            .pointerInput(measuring){detectTapGestures(onTap={p->
                if(measuring)return@detectTapGestures
                if(touchAfAllowed && vm.selectingAssist){
                    val point=ImageGeometry.sensorPoint(p.x/bounds.width.coerceAtLeast(1),p.y/bounds.height.coerceAtLeast(1),vm.previewQuarterTurns)
                    vm.startFocusAssistAt((point.first*1000).toInt(),(point.second*1000).toInt())
                }else if(touchAfAllowed && vm.touchFocusEnabled && !vm.focusAssistActive && vm.heldFrame==null && !vm.nativePixels && vm.viewTransform.zoom<=1f){
                    val point=ImageGeometry.sensorPoint(p.x/bounds.width.coerceAtLeast(1),p.y/bounds.height.coerceAtLeast(1),vm.previewQuarterTurns)
                    vm.pointFocus(point.first,point.second)
                }else latestTap()
            },onLongPress={latestLongPress()},onDoubleTap={if(!measuring){if(vm.isWifi && vm.heldFrame==null)vm.toggleFocusAssist() else {if(vm.nativePixels)vm.toggleNativePixels();vm.setTransform(if(vm.viewTransform.zoom>1f)1f else 2f)}}})}
            .pointerInput(fit.nativeZoom,measuring){detectTransformGestures{_,pan,zoom,_->
                if(measuring)return@detectTransformGestures
                if(vm.isWifi && vm.focusAssistActive){
                    val a=vm.wifi.assist
                    if(abs(zoom-1f)>.05f)vm.wifi.stepAssist(zoom>1f)
                    if(pan.getDistance()>1f){
                        val delta=ImageGeometry.sensorPoint(.5f-pan.x/bounds.width,.5f-pan.y/bounds.height,vm.previewQuarterTurns)
                        val origin=ImageGeometry.sensorPoint(.5f,.5f,vm.previewQuarterTurns)
                        vm.wifi.moveAssist(((a.x ?: 500)+(delta.first-origin.first)*1000).toInt(),((a.y ?: 500)+(delta.second-origin.second)*1000).toInt())
                    }
                    return@detectTransformGestures
                }
                val old=vm.viewTransform;val z=(old.zoom*zoom).coerceIn(1f,8f)
                vm.setTransform(z,old.panX-pan.x/bounds.width/z/fit.nativeZoom,old.panY-pan.y/bounds.height/z/fit.nativeZoom,fit.nativeZoom)
            }}){
            AndroidView(factory={MonitorGlView(it)},update={it.display(bitmap,vm.previewTools,if(measuring)com.lumixmonitor.monitor.ViewTransform() else vm.viewTransform.copy(zoom=vm.viewTransform.zoom*fit.nativeZoom),vm.viewingLut,vm.previewCalibration,vm.previewQuarterTurns)},modifier=Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()){
                if(measuring){
                    val region=WhiteBalanceRegion.center(bitmap.width,bitmap.height)
                    val a=ImageGeometry.screenPoint(region.left.toFloat()/bitmap.width,region.top.toFloat()/bitmap.height,vm.previewQuarterTurns)
                    val b=ImageGeometry.screenPoint((region.left+region.width).toFloat()/bitmap.width,(region.top+region.height).toFloat()/bitmap.height,vm.previewQuarterTurns)
                    val top=Offset(minOf(a.first,b.first)*size.width,minOf(a.second,b.second)*size.height)
                    val area=Size(abs(a.first-b.first)*size.width,abs(a.second-b.second)*size.height)
                    drawRect(Color.Black.copy(alpha=.8f),top,area,style=Stroke(4.dp.toPx()))
                    drawRect(WbAccent,top,area,style=Stroke(2.dp.toPx()))
                    return@Canvas
                }
                fun guide(a:Offset,b:Offset){drawLine(Color.Black.copy(alpha=.65f),a,b,2.dp.toPx());drawLine(Color.White.copy(alpha=.65f),a,b,.6.dp.toPx())}
                val completeFrame=vm.viewTransform.zoom<=1f && fit.nativeZoom<=1f
                if(completeFrame && !vm.focusAssistActive)vm.focusPoint?.let { target ->
                    val p=ImageGeometry.screenPoint(target.first,target.second,vm.previewQuarterTurns)
                    val c=Offset(size.width*p.first,size.height*p.second)
                    val d=13.dp.toPx()
                    drawRect(Color(0xFF77CCFF),c-Offset(d,d),Size(d*2,d*2),style=Stroke(1.5.dp.toPx()))
                }
                if(tools.showThirds && completeFrame)for(i in 1..2){guide(Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height));guide(Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3))}
                if(tools.showCenterMarker){guide(Offset(center.x-8.dp.toPx(),center.y),Offset(center.x+8.dp.toPx(),center.y));guide(Offset(center.x,center.y-8.dp.toPx()),Offset(center.x,center.y+8.dp.toPx()))}
                if(tools.showFramelines && completeFrame){
                    val target=if(vm.previewQuarterTurns%2==1)1f/tools.guideAspect else tools.guideAspect
                    val w=if(size.width/size.height>target)size.height*target else size.width
                    val h=w/target
                    val x=(size.width-w)/2;val y=(size.height-h)/2
                    val mask=Color.Black.copy(alpha=.45f)
                    if(x>0){drawRect(mask,size=Size(x,size.height));drawRect(mask,Offset(x+w,0f),Size(x,size.height))}
                    if(y>0){drawRect(mask,size=Size(size.width,y));drawRect(mask,Offset(0f,y+h),Size(size.width,y))}
                    drawRect(Color.White.copy(alpha=.65f),Offset(x,y),Size(w,h),style=Stroke(1.dp.toPx()))
                    val safe=tools.safeArea
                    if(safe<.999f)drawRect(Color.White.copy(alpha=.35f),Offset((size.width-w*safe)/2,(size.height-h*safe)/2),Size(w*safe,h*safe),style=Stroke(.5.dp.toPx()))
                }
                val roll=vm.levelRoll?.let{((it+vm.previewQuarterTurns*90f+270f)%180f)-90f}
                if(tools.showLevel && roll!=null){
                    val r=size.minDimension*.14f
                    val color=if(abs(roll)<1f)Accent else Color(0xFFFFBE74)
                    drawLine(Color.White.copy(alpha=.5f),Offset(center.x-r,center.y),Offset(center.x+r,center.y),1.dp.toPx())
                    rotate(roll,center){drawLine(color,Offset(center.x-r,center.y),Offset(center.x+r,center.y),1.3.dp.toPx())}
                    val pitch=((vm.levelPitch ?: 0f)/45f).coerceIn(-1f,1f)*r
                    drawRect(color,Offset(center.x-5.dp.toPx(),center.y+pitch-2.dp.toPx()),Size(10.dp.toPx(),4.dp.toPx()),style=Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

private fun compactWb(value:String)=when(value.lowercase()){
    "daylight","дневной"->"День";"auto"->"AWB";"cloud"->"Облако";"shade"->"Тень";"incandescent"->"Лампа";"fluorescent"->"Люм.";"auto cool"->"AWBc";"auto warm"->"AWBw";else->value.replace("manual ","M")
}

@Composable
private fun ScopePanel(scopes:Scopes?,raster:ScopeRaster?,tools:MonitorTools,modifier:Modifier,horizontal:Boolean=false){
    val wave=remember(raster){raster?.waveform?.asImageBitmap()}
    val vectors=remember(raster){raster?.vectors?.asImageBitmap()}
    Column(modifier.background(Panel).padding(7.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("JPEG · ДО LUT",color=Quiet,fontSize=10.sp)
        if(scopes==null)Text("Расчёт…",color=Quiet,fontSize=11.sp)
        val graphs: @Composable ()->Unit = {
        if(tools.showHistogram)ScopeGraph("Гистограмма · 0–255",Modifier.weight(1f)){s->
            val data=scopes ?: return@ScopeGraph
            val channels=if(tools.rgbHistogram)data.rgb.toList() else listOf(data.histogram)
            val colors=if(tools.rgbHistogram)listOf(Color(0xFFFF9393),Color(0xFF94DCA5),Color(0xFF8FB7FF)) else listOf(Accent)
            val maximum=channels.maxOf{it.maxOrNull() ?: 1}.coerceAtLeast(1)
            channels.forEachIndexed{c,values->val path=Path();values.forEachIndexed{x,n->val px=x/255f*s.width;val py=s.height-(sqrt(n.toFloat()/maximum)*s.height);if(x==0)path.moveTo(px,py) else path.lineTo(px,py)};drawPath(path,colors[c],style=Stroke(1.dp.toPx()))}
        }
        if(tools.showWaveform)ScopeGraph("Waveform · 0–100%",Modifier.weight(1f)){s->
            for(i in 1..3)drawLine(Color.White.copy(alpha=.12f),Offset(0f,s.height*i/4),Offset(s.width,s.height*i/4),.5.dp.toPx())
            wave?.let{drawImage(it,dstSize=IntSize(s.width.toInt().coerceAtLeast(1),s.height.toInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)}
        }
        if(tools.showVectorscope)ScopeGraph("Vectorscope · Cb / Cr",Modifier.weight(1f)){s->
            val side=min(s.width,s.height);val x=(s.width-side)/2;val y=(s.height-side)/2
            drawCircle(Color.White.copy(alpha=.2f),side*.5f,Offset(s.width/2,s.height/2),style=Stroke(.5.dp.toPx()))
            drawLine(Color.White.copy(alpha=.2f),Offset(x,s.height/2),Offset(x+side,s.height/2),.5.dp.toPx());drawLine(Color.White.copy(alpha=.2f),Offset(s.width/2,y),Offset(s.width/2,y+side),.5.dp.toPx())
            vectors?.let{drawImage(it,dstOffset=IntOffset(x.toInt(),y.toInt()),dstSize=IntSize(side.toInt().coerceAtLeast(1),side.toInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)}
        }
        }
        if(horizontal)Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){graphs()}
        else graphs()
    }
}

@Composable
private fun ScopeGraph(title:String,modifier:Modifier,draw:androidx.compose.ui.graphics.drawscope.DrawScope.(Size)->Unit){
    Column(modifier.fillMaxWidth()){
        Text(title,color=Quiet,fontSize=9.sp,maxLines=1)
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top=4.dp)){draw(size)}
    }
}
