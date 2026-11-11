package app.gureuneun.guide.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.Place
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.sdk.VehicleBridge
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------- 바탕과 카드

/** 앱 바탕. 위쪽에서 현대 블루 빛이 은은하게 번짐 */
@Composable
fun BrandBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxSize().background(C.bg).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(C.brand.copy(alpha = 0.62f), C.brand.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.16f, -size.height * 0.12f),
                    radius = size.width * 0.78f,
                ),
            )
        },
        content = content,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.surface, padding: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(S.cardRadius)).background(color).padding(padding), content = content)
}

/** 현대 블루 면 카드. 지금 들려주는 이야기처럼 화면의 주인공에만 씀 */
@Composable
fun BrandCard(modifier: Modifier = Modifier, padding: Dp = 36.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(S.cardRadius))
            .background(Brush.linearGradient(listOf(Color(0xFF003A78), C.brand, C.brandDeep), start = Offset.Zero, end = Offset.Infinite))
            .padding(padding),
        content = content,
    )
}

// ---------------------------------------------------------------- 단추

enum class Tone { Primary, Secondary, OnBrand, Quiet, Danger }

/**
 * 단추. 누르면 95%로 줄고 살짝 밝아짐 (Pleos 버튼 인터랙션). 높이는 76dp 이상.
 * Primary(액티브 블루)는 한 화면에 하나만 씀.
 */
@Composable
fun Btn(
    text: String,
    modifier: Modifier = Modifier,
    icon: Ic? = null,
    tone: Tone = Tone.Secondary,
    height: Dp = 96.dp,
    style: TextStyle = T.button,
    enabled: Boolean = true,
    iconSize: Dp = 34.dp,
    /** 글자 앞에 작은 색 점 (조명 색 고르기 등) */
    dot: Color? = null,
    /** 좌우 안쪽 여백. 좁은 줄에 단추 여럿을 둘 때 줄임 */
    hPad: Dp = 26.dp,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    val shape = RoundedCornerShape(S.buttonRadius)
    val (bg, fg) = when (tone) {
        Tone.Primary -> C.accent to C.onAccent
        Tone.Secondary -> C.raised to C.text
        Tone.OnBrand -> C.onBrandRaised to Color.White
        Tone.Quiet -> Color.Transparent to C.text
        Tone.Danger -> C.warnSoft to Color(0xFFFFDCD5)
    }
    Row(
        modifier
            .heightIn(min = height)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            .then(if (tone == Tone.Quiet) Modifier.border(2.dp, C.line, shape) else Modifier)
            .drawWithContent { drawContent(); if (pressed) drawRect(Color.White.copy(alpha = 0.12f)) }
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, fg, iconSize)
        if (dot != null) Box(Modifier.size(24.dp).clip(CircleShape).background(dot).border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape))
        Text(text, style = style, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** 아이콘만 있는 둥근 단추 (닫기 등). label 은 화면 읽기와 자동 시험이 찾는 이름 */
@Composable
fun IconBtn(ic: Ic, modifier: Modifier = Modifier, tint: Color = C.text, bg: Color = C.raised, size: Dp = S.touch, label: String? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "press")
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(bg)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(ic, tint, size * 0.44f) }
}

// ---------------------------------------------------------------- 칩, 고르기

/** 누르지 않는 표시 칩 */
@Composable
fun Tag(text: String, icon: Ic? = null, bg: Color = C.raised, fg: Color = C.text2, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(S.chipRadius)).background(bg).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, fg, 26.dp)
        Text(text, style = T.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 둘 이상 가운데 하나를 고르는 단추 묶음 (안내 언어 등) */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, height: Dp = S.touch, onSelect: (Int) -> Unit) {
    Row(modifier.height(height).clip(RoundedCornerShape(S.buttonRadius)).background(C.raised).padding(6.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) C.accent else Color.Transparent, tween(160), label = "seg")
            Box(
                Modifier.fillMaxSize().weight(1f).clip(RoundedCornerShape(S.buttonRadius - 4.dp)).background(bg)
                    .clickable { onSelect(i) }.padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = T.label, color = if (on) C.onAccent else C.text2, maxLines = 1) }
        }
    }
}

/** 켜고 끄는 줄 (점검 화면의 시연 명령 받기). 줄 전체를 눌러도 됨 */
@Composable
fun SwitchRow(icon: Ic, title: String, sub: String?, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = S.touch).clip(RoundedCornerShape(S.buttonRadius)).clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(if (on) C.accentSoft else C.raised), contentAlignment = Alignment.Center) {
            Icon(icon, if (on) C.accent else C.text2, 30.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.bodyM.copy(fontWeight = FontWeight.SemiBold), color = C.text)
            if (sub != null) Text(sub, style = T.caption, color = C.text3, maxLines = 2)
        }
        Switch(on)
    }
}

@Composable
fun Switch(on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, tween(160), label = "switch")
    Box(Modifier.size(84.dp, 48.dp).clip(RoundedCornerShape(24.dp)).background(if (on) C.accent else C.raised2)) {
        Box(Modifier.padding(5.dp).offset(x = 36.dp * x).size(38.dp).clip(CircleShape).background(if (on) C.onAccent else C.text2))
    }
}

@Composable
fun StatusDot(ok: Boolean?, size: Dp = 14.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(when (ok) { true -> C.accent; false -> C.warn; null -> C.text3 }))
}

/** 카드 머리말: 작은 아이콘과 회색 제목 */
@Composable
fun Eyebrow(text: String, icon: Ic? = null, color: Color = C.text3, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, color, 26.dp)
        Text(text, style = T.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- QR

@Composable
fun QrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(content) { qrBitmap(content, 480) }
    Box(modifier.size(size).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(size * 0.06f)) {
        Image(bmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize())
    }
}

fun qrBitmap(content: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(px * px) { i -> if (matrix[i % px, i / px]) 0xFF0E1015.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}

// ---------------------------------------------------------------- 지도, 별, 진행

/**
 * 오늘 경로와 명소, 지금 차 위치를 그린 작은 지도.
 * 지나온 길은 액티브 블루, 남은 길은 회색. 지난 명소는 채운 점, 다음 명소는 흰 테두리
 */
@Composable
fun RouteMap(
    route: List<LatLng>,
    places: List<Place>,
    entered: Collection<String>,
    car: LatLng?,
    heading: Double,
    modifier: Modifier = Modifier,
    nextId: String? = null,
    showCar: Boolean = true,
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
        val pad = 36f
        val scale = minOf((size.width - pad * 2) / w.toFloat(), (size.height - pad * 2) / h.toFloat())
        val ox = (size.width - w.toFloat() * scale) / 2
        val oy = (size.height - h.toFloat() * scale) / 2
        fun pt(p: LatLng) = Offset(ox + ((p.lon - minLon) * k).toFloat() * scale, oy + (maxLat - p.lat).toFloat() * scale)
        fun pathOf(pts: List<LatLng>) = Path().apply { pts.forEachIndexed { i, p -> val o = pt(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }

        drawPath(pathOf(route), C.raised2, style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 지나온 길: 차와 가장 가까운 경로 점까지
        if (car != null) {
            var best = 0
            var bestD = Double.MAX_VALUE
            route.forEachIndexed { i, p ->
                val d = (p.lat - car.lat) * (p.lat - car.lat) + (p.lon - car.lon) * (p.lon - car.lon) * k * k
                if (d < bestD) { bestD = d; best = i }
            }
            if (best > 0) drawPath(pathOf(route.subList(0, best + 1) + car), C.accent, style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        places.filter { it.kind != PlaceKind.CITY }.forEach { p ->
            val o = pt(p.pos)
            val done = p.id in entered
            when {
                done -> { drawCircle(C.accent, 14f, o); drawCircle(C.surface, 5f, o) }
                p.id == nextId -> { drawCircle(C.surface, 15f, o); drawCircle(Color.White, 15f, o, style = Stroke(width = 5f)) }
                else -> { drawCircle(C.surface, 13f, o); drawCircle(C.text3, 13f, o, style = Stroke(width = 4f)) }
            }
        }
        if (showCar && car != null) {
            val o = pt(car)
            drawCircle(C.accent.copy(alpha = 0.22f), 30f, o)
            rotate(heading.toFloat(), o) {
                val tri = Path().apply {
                    moveTo(o.x, o.y - 20f); lineTo(o.x + 14f, o.y + 15f); lineTo(o.x, o.y + 8f); lineTo(o.x - 14f, o.y + 15f); close()
                }
                drawPath(tri, Color.White)
            }
        }
    }
}

/** 퀴즈 별: 모은 별은 샌드, 남은 별은 테두리 */
@Composable
fun Stars(have: Int, need: Int, size: Dp = 34.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(maxOf(need, have)) { i -> Icon(if (i < have) Ic.Star else Ic.StarOutline, if (i < have) C.sand else C.text3, size) }
    }
}

/** 이야기 문장 진행 막대 */
@Composable
fun Segments(count: Int, index: Int, modifier: Modifier = Modifier, on: Color = C.accent, off: Color = Color(0x26FFFFFF)) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i -> Box(Modifier.height(8.dp).weight(1f).clip(RoundedCornerShape(4.dp)).background(if (i <= index) on else off)) }
    }
}

// ---------------------------------------------------------------- 어느 쪽 창밖인지

/**
 * 차를 위에서 본 그림 둘레에 그곳이 보이는 쪽을 밝힘. 운전자가 1초 안에 "오른쪽"을 알아보게 글보다 먼저 그림으로.
 * side: "left", "right", "ahead", "behind"
 */
@Composable
fun SideIndicator(side: String, size: Dp, modifier: Modifier = Modifier, color: Color = C.accent) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2f, w / 2f)
        val r = w * 0.40f
        val track = w * 0.05f
        drawCircle(Color.White.copy(alpha = 0.12f), r, c, style = Stroke(width = track))
        val mid = when (side) { "left" -> 180f; "right" -> 0f; "behind" -> 90f; else -> -90f }
        drawArc(color, mid - 50f, 100f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = w * 0.085f, cap = StrokeCap.Round))
        // 바깥을 가리키는 화살촉
        val a = Math.toRadians(mid.toDouble())
        val tip = Offset(c.x + (r + w * 0.10f) * cos(a).toFloat(), c.y + (r + w * 0.10f) * sin(a).toFloat())
        val base = Offset(c.x + (r + w * 0.03f) * cos(a).toFloat(), c.y + (r + w * 0.03f) * sin(a).toFloat())
        val nx = -sin(a).toFloat()
        val ny = cos(a).toFloat()
        val half = w * 0.06f
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(base.x + nx * half, base.y + ny * half); lineTo(base.x - nx * half, base.y - ny * half); close() }, color)
        // 가운데 차 (앞이 위)
        val cw = w * 0.22f
        val ch = w * 0.38f
        val body = Offset(c.x - cw / 2, c.y - ch / 2)
        drawRoundRect(Color.White.copy(alpha = 0.92f), body, Size(cw, ch), CornerRadius(cw * 0.42f), style = Stroke(width = w * 0.022f))
        drawRoundRect(Color.White.copy(alpha = 0.92f), Offset(c.x - cw * 0.34f, c.y - ch * 0.26f), Size(cw * 0.68f, ch * 0.16f), CornerRadius(cw * 0.1f))
        drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(c.x - cw * 0.3f, c.y + ch * 0.2f), Size(cw * 0.6f, ch * 0.1f), CornerRadius(cw * 0.08f))
    }
}

// ---------------------------------------------------------------- 좌석 점호 그림

/** 차를 위에서 본 좌석 그림. 빈자리는 테두리만, 앉았는데 벨트를 안 매면 빨간 테두리, 맸으면 파랗게 채움 */
@Composable
fun SeatMap(vehicle: VehicleBridge.State, lang: String, modifier: Modifier = Modifier) {
    val seats = listOf(
        Triple("ROW_1_LEFT", t(lang, "운전석", "Driver"), Offset(0f, 0f)),
        Triple("ROW_1_RIGHT", t(lang, "조수석", "Passenger"), Offset(1f, 0f)),
        Triple("ROW_2_LEFT", t(lang, "뒷좌석", "Rear left"), Offset(0f, 1f)),
        Triple("ROW_2_RIGHT", t(lang, "뒷좌석", "Rear right"), Offset(1f, 1f)),
    )
    Box(modifier.size(440.dp, 560.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val line = Color.White.copy(alpha = 0.22f)
            drawRoundRect(Color.White.copy(alpha = 0.04f), Offset(w * 0.04f, h * 0.02f), Size(w * 0.92f, h * 0.96f), CornerRadius(w * 0.22f))
            drawRoundRect(line, Offset(w * 0.04f, h * 0.02f), Size(w * 0.92f, h * 0.96f), CornerRadius(w * 0.22f), style = Stroke(width = 4f))
            // 앞 유리 아래 대시보드 줄과 운전대 (좌석 칸에 겹치지 않게 위쪽에)
            drawLine(line, Offset(w * 0.16f, h * 0.11f), Offset(w * 0.84f, h * 0.11f), 4f, StrokeCap.Round)
            drawLine(line, Offset(w * 0.2f, h * 0.9f), Offset(w * 0.8f, h * 0.9f), 4f, StrokeCap.Round)
            drawCircle(line, w * 0.055f, Offset(w * 0.3f, h * 0.17f), style = Stroke(width = 4f))
        }
        seats.forEach { (key, name, pos) ->
            val seated = key in vehicle.seatedKeys
            val buckled = vehicle.buckled(key)
            val bg = if (buckled) C.accent else Color.Transparent
            val border = when { buckled -> C.accent; seated -> C.warn; else -> Color.White.copy(alpha = 0.18f) }
            val fg = when { buckled -> C.onAccent; seated -> Color(0xFFFFDCD5); else -> C.text3 }
            Column(
                Modifier.offset(x = 48.dp + 180.dp * pos.x, y = 130.dp + 200.dp * pos.y).size(164.dp, 176.dp)
                    .clip(RoundedCornerShape(30.dp)).background(bg).border(4.dp, border, RoundedCornerShape(30.dp)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (buckled) Ic.Check else if (seated) Ic.Info else Ic.Plus, fg, 40.dp)
                Spacer(Modifier.height(10.dp))
                Text(name, style = T.label, color = fg, maxLines = 1)
                Text(
                    when { buckled -> t(lang, "벨트 착용", "Buckled"); seated -> t(lang, "벨트 미착용", "Not buckled"); else -> t(lang, "빈자리", "Empty") },
                    style = T.caption, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 물음 창, 알림

/** 차가 서 있을 때 화면 가운데 뜨는 물음 창 (Pleos System Popup: 아이콘, 제목, 설명, 단추 둘) */
@Composable
fun PopupFrame(icon: Ic, tint: Color = C.accent, width: Dp = 1000.dp, content: @Composable ColumnScope.() -> Unit) {
    // 절반 창(세로)에서도 창 가장자리에 붙지 않게 바깥 여백을 둠
    Box(Modifier.fillMaxSize().background(C.scrim).clickable(enabled = false) {}.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = width).clip(RoundedCornerShape(36.dp)).background(Color(0xFF1B1F26)).padding(horizontal = 44.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(icon, tint, 48.dp) }
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

/** 짧은 알림 하나: 지금 보이는지와 그 글 */
class ToastState(val visible: Boolean, val message: String)

/**
 * 알림이 올 때마다(seq 가 바뀔 때마다) 4초 동안 보이게 함.
 * 놓일 자리가 화면마다 달라서(달리는 동안은 이야기 카드 안 한 줄, 서 있을 때는 장면 위) 상태만 따로 둠
 * 화면이 처음 그려질 때 이미 와 있던 알림(앞 화면에서 본 것)은 다시 띄우지 않음
 */
@Composable
fun rememberToast(message: String?, seq: Int): ToastState {
    var visible by remember { mutableStateOf(false) }
    val first = remember { seq }
    LaunchedEffect(seq) {
        if (message != null && seq != first) {
            visible = true
            delay(4000)
            visible = false
        }
    }
    return ToastState(visible && message != null, message ?: "")
}

/** 알림 풍선. 읽고 있는 글과 큰 단추, QR 을 가리지 않는 자리를 부르는 쪽이 정함. fromTop 이면 위에서 내려옴 */
@Composable
fun ToastBubble(toast: ToastState, modifier: Modifier = Modifier, fromTop: Boolean = false) {
    val enter = fadeIn(tween(160)) + slideInVertically { if (fromTop) -it / 3 else it / 3 }
    val exit = fadeOut(tween(200)) + slideOutVertically { if (fromTop) -it / 3 else it / 3 }
    AnimatedVisibility(toast.visible, modifier, enter = enter, exit = exit) {
        Row(
            Modifier.widthIn(max = 1100.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF52C313B)).padding(horizontal = 34.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Ic.Info, C.accent, 30.dp)
            Text(toast.message, style = T.bodyM, color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun formatDist(m: Double): String = if (m >= 1000) "%.1fkm".format(m / 1000) else "${m.toInt()}m"
