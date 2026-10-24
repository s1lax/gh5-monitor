package com.lumixmonitor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.MonitorViewModel

@Composable
internal fun PreviewDiagnosisPanel(vm:MonitorViewModel){
    val diagnosis=vm.previewDiagnosis
    val quiet=Color(0xFF9CA9B5)
    Text("Проверка причины, без изменения экспозиции GH5.",color=Color.White,fontSize=14.sp)
    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clickable{vm.neutralPreview=!vm.neutralPreview},verticalAlignment=Alignment.CenterVertically){
        Text("Исходный USB без обработки",color=Color.White,fontSize=14.sp,modifier=Modifier.weight(1f))
        Switch(vm.neutralPreview,{vm.neutralPreview=it})
    }
    Text("Временно отключает LUT, коррекцию просмотра, false color, пикинг и зебру на экране телефона. Сохранённые настройки не меняются. Не отключает обработку, уже выполненную GH5.",color=quiet,fontSize=12.sp)
    Text("1. Одна неподвижная сцена и одинаковые фиксированные ISO, выдержка, диафрагма и ББ в обоих режимах. Выключите лупу и стоп-кадр.\n2. Выберите фото на GH5, подождите 2 секунды и сохраните этап «Фото».\n3. Выберите видео на GH5, подождите 2 секунды и сохраните этап «Видео». Запись включать не нужно.",color=Color.White,fontSize=13.sp)
    listOf("photo" to "Фото","video" to "Видео").forEach{(slot,label)->
        OutlinedButton(onClick={vm.capturePreviewDiagnosis(slot)},enabled=!diagnosis.busy && !vm.isDemo,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
            Text("Сохранить этап «$label»",fontSize=14.sp)
        }
        val capture=diagnosis.captures[slot]
        if(capture==null)Text("$label: ещё не сохранён",color=quiet,fontSize=12.sp)
        else {
            capture.thumbnail?.let{Image(it.asImageBitmap(),"Исходный USB: $label",Modifier.fillMaxWidth().height(110.dp),contentScale=ContentScale.Fit)}
            Text(capture.summary,color=quiet,fontSize=12.sp)
        }
    }
    if(diagnosis.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(diagnosis.message.isNotBlank())Text(diagnosis.message,color=Color(0xFFB4E4CC),fontSize=12.sp)
    OutlinedButton(onClick={diagnosis.export()},enabled=!diagnosis.busy && diagnosis.captures.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
        Text("Экспортировать сравнение ZIP",fontSize=14.sp)
    }
    Text("В архиве: JPEG без перекодирования, декодированный PNG и фактически прочитанные параметры. Числа яркости относятся к JPEG, не к RAW и не к замеру экспозиции. Диапазон видео, яркость LCD и View Assist на GH5 автоматически не определяются. По одному тёмному или светлому кадру диапазон не угадывается.",color=quiet,fontSize=12.sp)
    Text("Если исходный вывод остаётся светлее только в видео, позже сравним настройки GH5: Luminance Level, Photo Style, HLG/V-Log View Assist и настройки её монитора. Сейчас они автоматически не меняются.",color=quiet,fontSize=12.sp)
}
