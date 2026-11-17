package com.aicode.core.theme

import androidx.compose.ui.graphics.Color

/** Preview rasters and terminal output keep the same dark contrast in every appearance theme. */
object ToolPreviewPalette {
    val thumbnailBackground = Color(0xFF1A1A1E)
    val terminalBackground = Color(0xFF0B0B0D)
    val terminalCommand = Color(0xFFF2F4F8)
    val terminalOutput = Color(0xFF7BE38B)
    val terminalError = Color(0xFFFF8A8A)
    val diffRemove = Color(0xFFFF6B6B)
    val diffAdd = Color(0xFF4ADE80)
    val commandTail = Color(0xFF9BE28B).copy(alpha = 0.82f)
    val success = Color(0xFF34C759)
    val placeholder = Color(0xFF6B7280)
    val thumbnailStatus = Color.White.copy(alpha = 0.8f)
    val thumbnailCommand = Color.White.copy(alpha = 0.62f)
    val thumbnailText = Color.White.copy(alpha = 0.6f)
    val thumbnailFileName = Color.White.copy(alpha = 0.7f)
    val hudBackground = Color.Black.copy(alpha = 0.6f)
    val hudText = Color.White.copy(alpha = 0.8f)
    val shadowAmbient = Color.Black.copy(alpha = 0.06f)
    val shadowSpot = Color.Black.copy(alpha = 0.12f)
}
