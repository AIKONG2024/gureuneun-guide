package app.gureuneun.guide.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.Place
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.cos
import kotlin.math.max

@Composable
fun Panel(modifier: Modifier = Modifier, accent: Color? = null, padding: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(G.surface)
            .then(if (accent != null) Modifier.border(2.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(28.dp)) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Pill(text: String, color: Color = G.surface2, textColor: Color = G.text, size: TextUnit = 22.sp, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(text, color = textColor, fontSize = size, fontWeight = FontWeight.Medium)
    }
}

/** 누르는 동안 95%로 살짝 줄어드는 효과 (Pleos 버튼 인터랙션 가이드의 shrink effect) */
@Composable
fun pressScale(src: MutableInteractionSource): Float {
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    return scale
}

/** 누를 수 있는 작은 단추. 운전 중에도 한 번에 누를 수 있게 높이 76dp 이상 (Android for Cars 최소 터치 영역) */
@Composable
fun ChipButton(
    text: String,
    color: Color = G.surface2,
    textColor: Color = G.text,
    size: TextUnit = 24.sp,
    minHeight: Dp = 76.dp,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    Box(
        Modifier
            .heightIn(min = minHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = size, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = G.sky,
    textColor: Color = Color(0xFF06121A),
    height: Dp = 96.dp,
    size: TextUnit = 30.sp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    Box(
        modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) color else G.surface2)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) textColor else G.muted, fontSize = size, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, height: Dp = 96.dp, size: TextUnit = 28.sp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    Box(
        modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(G.surface2)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = G.text, fontSize = size, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, color: Color = G.muted) {
    Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
fun Dot(color: Color, size: Dp = 14.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun QrImage(content: String, size: Dp) {
    val bmp = remember(content) { qrBitmap(content, 512) }
    Box(Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp)) {
        Image(bmp.asImageBitmap(), contentDescription = "QR")
    }
}

fun qrBitmap(content: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    for (x in 0 until px) for (y in 0 until px) bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

/** 오늘 경로와 명소, 지금 차 위치를 그린 작은 지도 */
@Composable
fun RouteMap(
    route: List<LatLng>,
    places: List<Place>,
    entered: Collection<String>,
    car: LatLng?,
    heading: Double,
    accent: Color,
    modifier: Modifier = Modifier,
    extra: List<Pair<LatLng, Color>> = emptyList(),
) {
    Canvas(modifier) {
        if (route.size < 2) return@Canvas
        val all = route + places.map { it.pos }
        val minLat = all.minOf { it.lat }
        val maxLat = all.maxOf { it.lat }
        val minLon = all.minOf { it.lon }
        val maxLon = all.maxOf { it.lon }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = max((maxLon - minLon) * k, 1e-6)
        val h = max(maxLat - minLat, 1e-6)
        val pad = 34f
        val scale = minOf((size.width - pad * 2) / w.toFloat(), (size.height - pad * 2) / h.toFloat())
        val ox = (size.width - w.toFloat() * scale) / 2
        val oy = (size.height - h.toFloat() * scale) / 2
        fun pt(p: LatLng) = Offset(ox + ((p.lon - minLon) * k).toFloat() * scale, oy + (maxLat - p.lat).toFloat() * scale)

        val path = Path()
        route.forEachIndexed { i, p -> val o = pt(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, Color(0xFF3A4757), style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, accent.copy(alpha = 0.45f), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        places.forEach { p ->
            val o = pt(p.pos)
            val done = p.id in entered
            val r = if (p.kind.name == "CITY") 0f else 13f
            if (r > 0f) {
                drawCircle(if (done) accent else G.bg, r, o)
                drawCircle(accent, r, o, style = Stroke(width = 4f))
            }
        }
        extra.forEach { (p, c) -> drawCircle(c, 10f, pt(p)) }
        car?.let { c ->
            val o = pt(c)
            rotate(heading.toFloat(), o) {
                val tri = Path().apply {
                    moveTo(o.x, o.y - 22f)
                    lineTo(o.x + 15f, o.y + 16f)
                    lineTo(o.x, o.y + 8f)
                    lineTo(o.x - 15f, o.y + 16f)
                    close()
                }
                drawPath(tri, Color.White)
            }
        }
    }
}

@Composable
fun LabeledRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

val CardPadding = PaddingValues(24.dp)
