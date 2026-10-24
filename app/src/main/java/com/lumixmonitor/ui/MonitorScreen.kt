package com.lumixmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixmonitor.ConnectionState
import com.lumixmonitor.MonitorViewModel
import com.lumixmonitor.SettingKind
import com.lumixmonitor.lumix.LiveviewSetting
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

@Composable
fun MonitorScreen(viewModel: MonitorViewModel) {
    if(viewModel.isDemo || viewModel.connectionState is ConnectionState.Connected){
        FieldMonitor(viewModel)
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (viewModel.connectionState) {
            is ConnectionState.Connected -> MonitorContent(viewModel)
            else -> ConnectScreen(viewModel)
        }
    }
}

// ------------------------------------------------------------------ подключение

@Composable
private fun ConnectScreen(viewModel: MonitorViewModel) {
    var devices by remember { mutableStateOf(viewModel.findDevices()) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Список устройств обновляем, пока не подключились.
    LaunchedEffect(Unit) {
        while (true) {
            devices = viewModel.findDevices()
            delay(1_000)
        }
    }

    val state = viewModel.connectionState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "GH5 Monitor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Подключите камеру кабелем USB и включите на ней режим PC(Tether).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        when (state) {
            is ConnectionState.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(viewModel.statusText)
                }
            }
            is ConnectionState.Failed -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = viewModel.tetherHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                if (devices.isEmpty()) {
                    Text(
                        text = "Камера не найдена.\n\n" + viewModel.tetherHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Найдено устройств: ${devices.size}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.deviceName, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "VID 0x%04X  PID 0x%04X".format(
                                        device.vendorId, device.productId,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { viewModel.connect(device) }) {
                                Text("Подключить")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = { viewModel.startDemo() }) { Text("Демо без камеры") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.reconnect() }) {
                Text("Переподключиться")
            }
            if (state is ConnectionState.Connecting) {
                TextButton(onClick = { viewModel.disconnect() }) {
                    Text("Остановить")
                }
            }
            OutlinedButton(
                onClick = {
                    viewModel.refreshDiagnostics()
                    showDiagnostics = true
                },
            ) {
                Text("Журнал")
            }
        }

        if (showDiagnostics) {
            DiagnosticsDialog(viewModel, onDismiss = { showDiagnostics = false })
        }
    }
}

// --------------------------------------------------------------------- монитор

@Composable
private fun MonitorContent(viewModel: MonitorViewModel) {
    var controlsVisible by remember { mutableStateOf(true) }
    var showQuality by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Полноэкранное видео. Тап по свободному месту переключает оверлеи.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { controlsVisible = !controlsVisible },
        ) {
            VideoLayer(viewModel)
        }

        if (controlsVisible) {
            TopOverlay(
                viewModel,
                onQuality = { showQuality = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            BottomOverlay(
                viewModel,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showQuality) {
        QualityDialog(viewModel, onDismiss = { showQuality = false })
    }

    viewModel.probeReport?.let { report ->
        ProbeDialog(report, onDismiss = { viewModel.clearProbeReport() })
    }
}

/** Отчёт проверки живью: что камера отдаёт на самом деле. */
@Composable
private fun ProbeDialog(report: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверка живью") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

/**
 * Верхний оверлей: модель камеры, статус и выбор качества liveview.
 * Полупрозрачный, как в полевых мониторах.
 */
@Composable
private fun TopOverlay(
    viewModel: MonitorViewModel,
    onQuality: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = viewModel.cameraModel ?: "Камера",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = viewModel.statusText,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                maxLines = 1,
            )
            if (viewModel.performanceText.isNotEmpty()) {
                Text(
                    text = viewModel.performanceText,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        // Кнопка качества видна всегда, даже если камера ещё не назвала режим.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onQuality)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = viewModel.activeLiveviewMode?.label ?: "Качество",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = viewModel.actualFrameSize?.let { "кадр $it" } ?: "нет кадров",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Нижний оверлей: настройки камеры, действия и инструменты монитора.
 */
@Composable
private fun BottomOverlay(viewModel: MonitorViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .navigationBarsPadding(),
    ) {
        ControlRow {
            SettingChip(
                "ISO",
                viewModel.settings.iso,
                onPrev = { viewModel.cycleSetting(SettingKind.ISO, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.ISO) },
            )
            SettingChip(
                "Выдержка",
                viewModel.settings.shutter,
                onPrev = { viewModel.cycleSetting(SettingKind.SHUTTER, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.SHUTTER) },
            )
            SettingChip(
                "Диафрагма",
                viewModel.settings.aperture,
                onPrev = { viewModel.cycleSetting(SettingKind.APERTURE, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.APERTURE) },
            )
            SettingChip(
                "EV",
                viewModel.settings.exposureComp,
                onPrev = { viewModel.cycleSetting(SettingKind.EXPOSURE, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.EXPOSURE) },
            )
            SettingChip(
                "ББ",
                viewModel.settings.whiteBalance,
                onPrev = { viewModel.cycleSetting(SettingKind.WHITE_BALANCE, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.WHITE_BALANCE) },
            )
            SettingChip(
                "Стиль",
                viewModel.settings.photoStyle,
                onPrev = { viewModel.cycleSetting(SettingKind.PHOTO_STYLE, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.PHOTO_STYLE) },
            )
            SettingChip(
                "Кадр",
                viewModel.settings.aspect,
                onPrev = { viewModel.cycleSetting(SettingKind.ASPECT, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.ASPECT) },
            )
            SettingChip(
                "Формат",
                viewModel.settings.quality,
                onPrev = { viewModel.cycleSetting(SettingKind.QUALITY, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.QUALITY) },
            )
            SettingChip(
                "AF-режим",
                viewModel.settings.afMode,
                onPrev = { viewModel.cycleSetting(SettingKind.AF_MODE, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.AF_MODE) },
            )
            SettingChip(
                "AF-зона",
                viewModel.settings.afZone,
                onPrev = { viewModel.cycleSetting(SettingKind.AF_ZONE, forward = false) },
                onNext = { viewModel.cycleSetting(SettingKind.AF_ZONE) },
            )
        }

        Text(
            text = "◀ ▶ — значение настройки; ▶ всегда добавляет света",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
        )

        ControlRow {
            ActionButton("Спуск") { viewModel.capture() }
            ActionButton("AF") { viewModel.autoFocus() }
            ActionButton("Фокус ближе") { viewModel.focusNear(2) }
            ActionButton("Фокус дальше") { viewModel.focusFar(2) }
        }

        ControlRow {
            ToolToggle(
                "Трети",
                viewModel.tools.showThirds,
            ) { viewModel.updateTools { it.copy(showThirds = !it.showThirds) } }
            ToolToggle(
                "Уровень",
                viewModel.tools.showLevel,
            ) { viewModel.updateTools { it.copy(showLevel = !it.showLevel) } }
            ToolToggle(
                "Кадровые линии",
                viewModel.tools.showFramelines,
            ) { viewModel.updateTools { it.copy(showFramelines = !it.showFramelines) } }
            ToolToggle(
                "Центр",
                viewModel.tools.showCenterMarker,
            ) { viewModel.updateTools { it.copy(showCenterMarker = !it.showCenterMarker) } }
        }

        ControlRow {
            ActionButton("Переподключить") { viewModel.reconnect() }
            ActionButton("Отключить") { viewModel.disconnect() }
            ActionButton("Проверка живью") { viewModel.probeLiveview() }
            ActionButton("Список значений") { viewModel.logAllowedValues() }
            ActionButton("Структура AF") { viewModel.dumpAfStructure() }
        }
    }
}

/**
 * Выбор качества liveview. Режимы сообщает сама камера.
 */
@Composable
internal fun QualityDialog(viewModel: MonitorViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Качество liveview") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Камера сообщает эти режимы:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                viewModel.liveviewModes.forEach { mode ->
                    ModeRow(mode, mode == viewModel.activeLiveviewMode) {
                        viewModel.selectLiveviewMode(mode)
                        onDismiss()
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Попробовать больше (камера может не поддержать):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                viewModel.experimentalModes.forEach { mode ->
                    ModeRow(mode, false) {
                        viewModel.selectLiveviewMode(mode)
                        onDismiss()
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Размер кадра в строке статуса — фактическое разрешение JPEG. " +
                        "Режимы и максимальное качество зависят от модели камеры.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun ModeRow(mode: LiveviewSetting, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mode.label,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
        )
        if (active) {
            Text(
                "✓",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun VideoLayer(viewModel: MonitorViewModel) {
    val bitmap = viewModel.frame

    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Ожидание кадра…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // Keep the complete camera frame. The phone is wider than 16:9; Crop used
    // to cut off the top/bottom and made composition guides inaccurate.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            val tools = viewModel.tools

            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f

                if (tools.showThirds) {
                    for (third in 1..2) {
                        val x = size.width * third / 3f
                        val y = size.height * third / 3f
                        val verticalStart = Offset(x, 0f)
                        val verticalEnd = Offset(x, size.height)
                        val horizontalStart = Offset(0f, y)
                        val horizontalEnd = Offset(size.width, y)
                        // Dark outline keeps the grid visible on both bright and dark scenes.
                        drawLine(Color.Black.copy(alpha = 0.55f), verticalStart, verticalEnd, 3.dp.toPx())
                        drawLine(Color.Black.copy(alpha = 0.55f), horizontalStart, horizontalEnd, 3.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.65f), verticalStart, verticalEnd, 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.65f), horizontalStart, horizontalEnd, 1.dp.toPx())
                    }
                }

                if (tools.showFramelines) {
                    val guide = Color.White.copy(alpha = 0.35f)
                    // Кадр 2.39:1 внутри 16:9.
                    val targetHeight = size.width / 2.39f
                    if (targetHeight < size.height) {
                        val top = (size.height - targetHeight) / 2f
                        drawRect(
                            color = guide,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, targetHeight),
                            style = Stroke(width = 2f),
                        )
                    }
                    // Безопасные зоны 90% и 80%.
                    listOf(0.9f, 0.8f).forEach { factor ->
                        val w = size.width * factor
                        val h = size.height * factor
                        drawRect(
                            color = guide.copy(alpha = 0.22f),
                            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                            size = Size(w, h),
                            style = Stroke(width = 1.5f),
                        )
                    }
                }

                if (tools.showCenterMarker) {
                    val marker = Color.White.copy(alpha = 0.75f)
                    val arm = min(size.width, size.height) * 0.03f
                    drawLine(marker, Offset(centerX - arm, centerY), Offset(centerX + arm, centerY), 2f)
                    drawLine(marker, Offset(centerX, centerY - arm), Offset(centerX, centerY + arm), 2f)
                }

                val roll = viewModel.levelRoll
                if (tools.showLevel && roll != null) {
                    drawLevel(roll, viewModel.levelPitch, centerX, centerY, size.minDimension)
                }
            }
        }
    }
}

/**
 * Электронный уровень.
 *
 * Значения крена и тангажа приходят от самой камеры в блоке 0x17000005
 * кадра liveview, в десятых долях градуса — считать их на GPU не нужно.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLevel(
    roll: Float,
    pitch: Float?,
    centerX: Float,
    centerY: Float,
    minDimension: Float,
) {
    val radius = minDimension * 0.16f
    val isLevel = abs(roll) < 1.0f
    val lineColor = if (isLevel) Color(0xFF66DD66) else Color(0xFFFFC24B)

    val reference = Color.White.copy(alpha = 0.55f)
    drawLine(reference, Offset(centerX - radius, centerY), Offset(centerX - radius * 0.5f, centerY), 3f)
    drawLine(reference, Offset(centerX + radius * 0.5f, centerY), Offset(centerX + radius, centerY), 3f)

    rotate(degrees = roll, pivot = Offset(centerX, centerY)) {
        drawLine(lineColor, Offset(centerX - radius, centerY), Offset(centerX + radius, centerY), 4f)
        drawLine(
            lineColor,
            Offset(centerX - radius, centerY - 8f),
            Offset(centerX - radius, centerY + 8f),
            4f,
        )
        drawLine(
            lineColor,
            Offset(centerX + radius, centerY - 8f),
            Offset(centerX + radius, centerY + 8f),
            4f,
        )
    }

    if (pitch != null) {
        // Простая шкала тангажа слева от центра.
        val barHeight = minDimension * 0.2f
        val x = centerX - radius * 1.35f
        drawLine(
            Color.White.copy(alpha = 0.35f),
            Offset(x, centerY - barHeight / 2f),
            Offset(x, centerY + barHeight / 2f),
            2f,
        )
        val clamped = pitch.coerceIn(-45f, 45f) / 45f
        val y = centerY - clamped * barHeight / 2f
        drawCircle(lineColor, radius = 5f, center = Offset(x, y))
    }
}

// -------------------------------------------------------------------- элементы

/**
 * Чип настройки со стрелками в обе стороны.
 *
 * Стрелки, а не тап по всему чипу: с одним обработчиком значение перебирается
 * только вперёд, и вернуться к предыдущему невозможно.
 */
@Composable
internal fun SettingChip(
    label: String,
    value: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepArrow("◀", onPrev)
        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        StepArrow("▶", onNext)
    }
}

/** Стрелка перебора значения настройки. */
@Composable
private fun StepArrow(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun ActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
internal fun ToolToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val border = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val background = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else Color.Transparent

    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ControlRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * Журнал диагностики.
 *
 * Показывается прямо в приложении: телефон занят камерой в единственном
 * USB-порту, поэтому одновременно подключить его к компьютеру для adb нельзя.
 * Текст можно выделить и скопировать.
 */
@Composable
private fun DiagnosticsDialog(viewModel: MonitorViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }
    val lines = viewModel.diagnostics

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Журнал диагностики (${lines.size})") },
        text = {
            if (lines.isEmpty()) {
                Text("Журнал пуст")
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.refreshDiagnostics() }) { Text("Обновить") }
        },
    )
}
