package app.gureuneun.guide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.R

/**
 * 색: 기획서 차량 화면 시안(Pleos 다크 테마)과 같은 값.
 * 강조색은 늘 현대 액티브 블루. 지역 테마색(경주 신라 금빛)은 '여행 모드' 칩과 요금소 통과 연출에만 씀
 */
object C {
    val bg = Color(0xFF0C0F12)
    val panel = Color(0xFF171B20)
    val card = Color(0xFF22272D)
    val card2 = Color(0xFF2B3138)
    val line = Color(0x12FFFFFF)
    val text = Color(0xFFF2F4F6)
    val sub = Color(0xFFAAB2BA)
    val faint = Color(0xFF6F7881)
    val blue = Color(0xFF00AAD2)
    val blueText = Color(0xFFBFE6F2)
    val onBlue = Color(0xFF03222E)
    val navy = Color(0xFF002C5F)
    val navy2 = Color(0xFF0B4A8F)
    val navi = Color(0xFF2F8CFF)
    val gold = Color(0xFFD9A866)
    val goldText = Color(0xFFF2CF98)
    val goldDeep = Color(0xFFB8862F)
    val green = Color(0xFF3BD16F)
    val route = Color(0xFF27C964)
    val routeCase = Color(0xFF0B3D1D)
    val amber = Color(0xFFF2C230)
    val red = Color(0xFFE5484D)
    val wave1 = Color(0xFF9FB6FF)
    val wave2 = Color(0xFF6E7CFF)
    val track = Color(0xFF2E343B)
    val trackFill = Color(0xFFC9D1D8)
}

val LocalAccent = compositionLocalOf { C.blue }

/** Asta Sans (42dot, SIL OFL 1.1). 플레오스 커넥트 기본 글꼴과 같은 파일을 굵기별로 넣음 */
val Asta = FontFamily(
    Font(R.font.asta_regular, FontWeight.Normal),
    Font(R.font.asta_medium, FontWeight.Medium),
    Font(R.font.asta_semibold, FontWeight.SemiBold),
    Font(R.font.asta_bold, FontWeight.Bold),
    Font(R.font.asta_extrabold, FontWeight.ExtraBold),
)

private val Phrase = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)

/** 글자 스타일. 시안처럼 큰 제목일수록 자간을 좁힘 (38px 제목에 -1.2px) */
fun ts(size: Int, w: FontWeight = FontWeight.Normal, color: Color = C.text, lh: Float = 1.34f, align: TextAlign? = null): TextStyle {
    val spacing = when {
        size >= 60 -> -0.04f * size
        size >= 34 -> -0.032f * size
        else -> -0.012f * size
    }
    return TextStyle(
        fontFamily = Asta, fontSize = size.sp, fontWeight = w, color = color, lineHeight = (size * lh).sp,
        letterSpacing = spacing.sp, textAlign = align ?: TextAlign.Unspecified, lineBreak = Phrase,
    )
}

val Medium = FontWeight.Medium
val Bold = FontWeight.Bold
val Semi = FontWeight.SemiBold
val Heavy = FontWeight.ExtraBold

@Composable
fun Avatar(name: String, color: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = ts((size.value * 0.48f).toInt(), Heavy, Color.White, 1.0f))
    }
}

/** 칩: 시안의 .chip (높이 34px, 굵기 700). gold 는 '여행 모드', blue 는 연결 상태 */
@Composable
fun Chip(text: String, icon: String? = null, tone: String = "blue", modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val (fg, bg, ring) = when (tone) {
        "gold" -> Triple(C.goldText, C.gold.copy(alpha = 0.14f), C.gold.copy(alpha = 0.55f))
        "gray" -> Triple(C.sub, Color(0x0FFFFFFF), Color(0x1FFFFFFF))
        else -> Triple(C.blueText, C.blue.copy(alpha = 0.14f), C.blue.copy(alpha = 0.5f))
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.height(50.dp).clip(shape).background(bg).border(2.dp, ring, shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ico(icon, fg, 26.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = ts(26, Bold, fg, 1.1f))
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, bg: Color = C.card2, fg: Color = C.text, size: Int = 22, dot: Color? = null, icon: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(bg).let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
        }
        if (icon != null) {
            Ico(icon, fg, (size + 4).dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ts(size, Semi, fg, 1.1f))
    }
}

/** 버튼: 시안의 .btns (높이 80px, 모서리 18px, 22px 굵게). 주 버튼은 액티브 블루 */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, icon: String? = null, height: Dp = 112.dp, onClick: () -> Unit) {
    val bg = if (primary) C.blue else C.card2
    val fg = if (primary) C.onBlue else Color(0xFFE8ECEF)
    Row(
        modifier.height(height).clip(RoundedCornerShape(26.dp)).background(bg).clickable { onClick() }.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Ico(icon, fg, 34.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = ts(31, Bold, fg, 1.1f))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(PaddingValues(top = 4.dp, bottom = 10.dp)), style = ts(22, Semi, C.faint))
}

/** 앱 표시: 네이비 바탕에 굽이치는 길 두 줄(액티브 블루, 금빛). 시안의 .logo */
@Composable
fun AppMark(size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(C.navy2, C.navy)))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) {
            val w = this.size.width
            val h = this.size.height
            fun wave(y: Float, c: Color) {
                val p = Path()
                p.moveTo(0f, y)
                p.cubicTo(w * 0.17f, y - h * 0.2f, w * 0.33f, y + h * 0.2f, w * 0.5f, y)
                p.cubicTo(w * 0.67f, y - h * 0.2f, w * 0.83f, y + h * 0.2f, w, y)
                drawPath(p, c, style = Stroke(width = h * 0.13f, cap = StrokeCap.Round))
            }
            wave(h * 0.36f, C.blue)
            wave(h * 0.68f, C.goldText)
        }
    }
}

/** 선 아이콘. 시안과 같은 Lucide 아이콘(굵기 2)을 쓰고, 없는 것만 직접 그림 */
@Composable
fun Ico(kind: String, color: Color, size: Dp = 28.dp, modifier: Modifier = Modifier, stroke: Float = 2f) {
    Canvas(modifier.size(size)) { drawIco(kind, color, this.size, stroke) }
}

private val lucideCache = HashMap<String, List<Path>>()

private fun lucide(kind: String): List<Path>? {
    lucideCache[kind]?.let { return it }
    val ds = LUCIDE[kind] ?: return null
    val ps = ds.map { PathParser().parsePathString(it).toPath() }
    lucideCache[kind] = ps
    return ps
}

fun DrawScope.drawIco(kind: String, color: Color, sz: Size, strokeW: Float = 2f) {
    val lp = if (kind == "blink" || kind == "playfill") null else lucide(kind)
    if (lp != null) {
        val s = sz.minDimension / 24f
        withTransform({
            translate((sz.width - 24f * s) / 2f, (sz.height - 24f * s) / 2f)
            scale(s, s, Offset.Zero)
        }) {
            val st = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (p in lp) drawPath(p, color, style = st)
        }
        return
    }
    val w = sz.width
    val h = sz.height
    when (kind) {
        "blink" -> {
            // 방향지시등 (꽉 찬 화살표)
            val p = Path().apply {
                moveTo(w * 0.94f, h * 0.5f); lineTo(w * 0.5f, h * 0.12f); lineTo(w * 0.5f, h * 0.32f)
                lineTo(w * 0.08f, h * 0.32f); lineTo(w * 0.08f, h * 0.68f); lineTo(w * 0.5f, h * 0.68f); lineTo(w * 0.5f, h * 0.88f); close()
            }
            drawPath(p, color)
        }
        "playfill" -> {
            val p = Path().apply { moveTo(w * 0.3f, h * 0.18f); lineTo(w * 0.82f, h * 0.5f); lineTo(w * 0.3f, h * 0.82f); close() }
            drawPath(p, color)
        }
        else -> drawCircle(color, w * 0.3f, Offset(w / 2, h / 2), style = Stroke(width = w * 0.08f))
    }
}

