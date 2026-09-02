package com.moci.words.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// 网页版使用的 SVG 路径转成 Compose 矢量图标，保持视觉一致

private fun icon(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (path in paths) {
        builder.addPath(
            PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }
    return builder.build()
}

object MociIcons {
    val Home = icon("home", "M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1v-9.5Z")
    val Book = icon("book", "M6 4h9a3 3 0 0 1 3 3v13H8a2 2 0 0 1-2-2V4Zm2 3h7v2H8V7Zm0 4h7v2H8v-2Z")
    val Users = icon(
        "users",
        "M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm-6 8c0-3 4-5 6-5s6 2 6 5v1H6v-1Z",
        "M16 12a3 3 0 1 0-1-5.8 5.5 5.5 0 0 1 0 5.8ZM16 15c1.7.6 3 1.8 3 3.5V21h3v-1c0-2.2-2.6-3.6-6-4Z",
    )
    val Chart = icon("chart", "M5 19V9h3v10H5Zm6 0V5h3v14h-3Zm6 0v-6h3v6h-3Z")
    val Trophy = icon(
        "trophy",
        "M7 4h2v1a3 3 0 0 0 6 0V4h2v3a5 5 0 0 1-4 4.9V14H17v2H7v-2h4v-2.1A5 5 0 0 1 7 7V4Zm2 0v3a3 3 0 0 0 6 0V4H9Z",
        "M8 18h8v2H8v-2Z",
    )
    val Person = icon("person", "M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm0 2c-4 0-8 2-8 5v1h16v-1c0-3-4-5-8-5Z")
    val Study = icon("study", "M4 7a4 4 0 0 1 4-4h5l7 7v9a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V7Zm8 3v2h5v2h-5v2l-3.5-3L12 10Z")
    val Speaker = icon(
        "speaker",
        "M3 10v4h3.2L11 18.5V5.5L6.2 10H3Z",
        "M14 9.15a3.15 3.15 0 0 1 0 5.7 1 1 0 0 1-.9-1.78 1.15 1.15 0 0 0 0-2.14A1 1 0 0 1 14 9.15Z",
        "M16.55 7.15a5.5 5.5 0 0 1 0 9.7 1 1 0 0 1-.83-1.83 3.5 3.5 0 0 0 0-6.04 1 1 0 0 1 .83-1.83Z",
    )
    val Mic = icon(
        "mic",
        "M12 15a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-2.08A7 7 0 0 0 19 12h-2Z",
    )
    val Search = icon(
        "search",
        "M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5Zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14Z",
    )
    val Back = icon("back", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2Z")
    val Add = icon("add", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2Z")
    val ChevronLeft = icon("chevron_left", "M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12Z")
    val ChevronRight = icon("chevron_right", "M8.59 16.59 10 18l6-6-6-6-1.41 1.41L13.17 12Z")
    val Close = icon("close", "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12Z")
    val Edit = icon("edit", "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25ZM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83Z")
    val Settings = icon(
        "settings",
        "M19.14 12.94c.04-.31.06-.63.06-.94s-.02-.63-.06-.94l2.03-1.58a.5.5 0 0 0 .12-.61l-1.92-3.32a.5.5 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54A.5.5 0 0 0 13.93 2h-3.86a.5.5 0 0 0-.48.41l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96a.5.5 0 0 0-.59.22L2.71 8.87a.5.5 0 0 0 .12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94L2.83 14.52a.5.5 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.86c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.5.5 0 0 0-.12-.61l-2.03-1.58ZM12 15.6A3.6 3.6 0 1 1 15.6 12 3.6 3.6 0 0 1 12 15.6Z",
    )
    val Clock = icon(
        "clock",
        "M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2Zm1 5h-2v6l4.25 2.52.75-1.23-3-1.77V7Z",
    )
}
