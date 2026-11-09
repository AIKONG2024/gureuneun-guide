package app.gureuneun.guide.ui

import androidx.compose.ui.graphics.Color

object G {
    val bg = Color(0xFF0B0E13)
    val surface = Color(0xFF141A22)
    val surface2 = Color(0xFF1C2430)
    val line = Color(0xFF2A3442)
    val text = Color(0xFFF2F5F7)
    val muted = Color(0xFF8C99A8)
    val good = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val bad = Color(0xFFF87171)
    val sky = Color(0xFF7FE3FF)
}

fun t(lang: String, ko: String, en: String) = if (lang == "en") en else ko
