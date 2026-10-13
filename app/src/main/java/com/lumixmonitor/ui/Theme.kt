package com.lumixmonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Тёмная тема: приложение используется как монитор, светлый интерфейс
 * мешал бы оценивать экспозицию кадра.
 */
private val MonitorColors = darkColorScheme(
    primary = Color(0xFF7FD1FF),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFFB8C8D0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF121416),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF1E2225),
    onSurfaceVariant = Color(0xFFBFC8CE),
    error = Color(0xFFFFB4AB),
)

@Composable
fun LumixMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonitorColors,
        content = content,
    )
}
