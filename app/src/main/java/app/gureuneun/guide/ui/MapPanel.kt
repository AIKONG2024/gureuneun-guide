package app.gureuneun.guide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.core.TripPlace
import app.gureuneun.guide.engine.Card
import app.gureuneun.guide.engine.Phase
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.Ui
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val MapGlass = Color(0xF022282E)
private val PoiFill = Color(0xFFE8ECEF)
private val PoiIcon = Color(0xFF5B4636)
private val PoiRing = Color(0xFF121518)
private val Label = Color(0xFFE3E8EC)
private val CarBlue = Color(0xFF3D8BFF)
private val Route = Color(0xFF2EC95C)
private val OpenRing = Color(0xFFD9A866)
private val OpenPlus = Color(0xFFE0B27A)

/** 지도 그림의 채도를 낮춰 앱 화면(다크, 블루) 위에서 튀지 않게 함 */
private val MapTone = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) })

/**
 * 오프라인 지도. 주행 중에는 차를 따라가고(북쪽이 위, 지도 글자가 뒤집히지 않게), 출발 전에는 경주 시내 일정 전체를 보여 줌.
 * 지나는 명소를 설명하는 동안에는 차에서 그 명소 쪽으로 빛줄기를 그려 '보이는 방향'을 알려 줌.
 */
@Composable
fun MapPanel(ui: Ui, engine: TripEngine, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tiles = remember { Tiles.get(ctx) }
    val ver by tiles.version.collectAsState()
    val measurer = rememberTextMeasurer()
    val route = remember { engine.path.pts.map { tiles.local(it) } }
    val gateIdx = remember { engine.path.index(engine.gateS) }
    val city = remember {
        val pts = route.drop(gateIdx)
        Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }
    val sights = remember { engine.region.sights.map { it to tiles.local(it.pos) } }
    val bg = Color(tiles.bg)
    val order = remember(ui.trip.places) {
        ui.trip.places.filter { it.time != null && it.kind != "food" }.sortedBy { it.time }.mapIndexed { i, p -> p.id to i + 1 }.toMap()
    }

    Box(modifier.clip(RoundedCornerShape(38.dp)).background(bg).border(1.dp, C.line, RoundedCornerShape(38.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            if (ver < 0) return@Canvas
            val w = size.width
            val h = size.height
            val pose = ui.pose
            val follow = pose != null && (ui.phase == Phase.DRIVE || ui.phase == Phase.ARRIVED)
            val center: Offset
            val rot: Float
            val sc: Float
            val anchor: Offset
            if (follow && pose != null) {
                center = tiles.local(pose.pos)
                rot = 0f
                sc = when {
                    pose.speedKmh > 70 -> 0.5f
                    pose.speedKmh > 38 -> 0.66f
                    else -> 0.78f
                }
                anchor = Offset(w * 0.5f, h * 0.55f)
            } else {
                center = city.center
                rot = 0f
                sc = min(w / (city.width + 280f), h / (city.height + 320f))
                anchor = Offset(w / 2f, h / 2f)
            }
            val rr = Math.toRadians(rot.toDouble())
            val cs = cos(rr).toFloat()
            val sn = sin(rr).toFloat()
            fun inv(p: Offset): Offset {
                val dx = (p.x - anchor.x) / sc
                val dy = (p.y - anchor.y) / sc
                return Offset(center.x + dx * cs + dy * sn, center.y - dx * sn + dy * cs)
            }
            fun fwd(p: Offset): Offset {
                val vx = (p.x - center.x) * sc
                val vy = (p.y - center.y) * sc
                return Offset(anchor.x + vx * cs - vy * sn, anchor.y + vx * sn + vy * cs)
            }
            val cr = listOf(inv(Offset(0f, 0f)), inv(Offset(w, 0f)), inv(Offset(0f, h)), inv(Offset(w, h)))
            val t = tiles.tile
            val tx0 = floor(cr.minOf { it.x } / t).toInt() + tiles.txMin
            val tx1 = floor(cr.maxOf { it.x } / t).toInt() + tiles.txMin
            val ty0 = floor(cr.minOf { it.y } / t).toInt() + tiles.tyMin
            val ty1 = floor(cr.maxOf { it.y } / t).toInt() + tiles.tyMin

            withTransform({
                translate(anchor.x, anchor.y)
                rotate(rot, Offset.Zero)
                scale(sc, sc, Offset.Zero)
                translate(-center.x, -center.y)
            }) {
                for (tx in tx0..tx1) for (ty in ty0..ty1) {
                    val img = tiles.get(tx, ty) ?: continue
                    drawImage(img, dstOffset = IntOffset((tx - tiles.txMin) * t, (ty - tiles.tyMin) * t), dstSize = IntSize(t + 1, t + 1), colorFilter = MapTone)
                }
                // 경로: 지나온 길은 흐리게, 남은 길은 초록과 진행 방향 표시
                val idx = if (pose != null && follow) engine.path.index(pose.s) else 0
                val now = if (pose != null && follow) tiles.local(pose.pos) else route.first()
                val aheadPts = listOf(now) + route.subList(min(idx + 1, route.size), route.size)
                val ahead = Path().apply {
                    moveTo(aheadPts[0].x, aheadPts[0].y)
                    for (i in 1 until aheadPts.size) lineTo(aheadPts[i].x, aheadPts[i].y)
                }
                if (follow && idx > 0) {
                    val done = Path().apply {
                        moveTo(route[0].x, route[0].y)
                        for (i in 1..idx) lineTo(route[i].x, route[i].y)
                        lineTo(now.x, now.y)
                    }
                    drawPath(done, Color(0x806E7883), style = Stroke(width = 9f / sc, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawPath(ahead, C.routeCase, style = Stroke(width = 19f / sc, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(ahead, Route, style = Stroke(width = 13f / sc, cap = StrokeCap.Round, join = StrokeJoin.Round))
                chevrons(aheadPts, 120f / sc, 4.5f / sc)

                // 명소와 담은 곳 (글자는 화면 기준으로 세워서)
                val sightCard = ui.card as? Card.SightCard
                // 출발 전 전체 지도에서는 일정 핀만 (명소는 주행 중에 보여 줌)
                if (follow) {
                    for ((s, p) in sights) {
                        if (ui.trip.places.any { it.id == s.id }) continue
                        val hl = sightCard?.sight?.id == s.id && !sightCard.done
                        upright(p, rot, sc) { poi(measurer, s.name, hl) }
                    }
                }
                for (pl in ui.trip.places) {
                    val p = tiles.local(pl.pos)
                    upright(p, rot, sc) { pin(measurer, pl, order[pl.id], ui.newPlaceId == pl.id, showLabel = follow) }
                }
            }

            // 보이는 방향: 차에서 명소 쪽으로 빛줄기
            val sc2 = ui.card as? Card.SightCard
            if (follow && sc2 != null && !sc2.done) {
                val target = fwd(tiles.local(sc2.sight.pos))
                beam(anchor, target, ui.clock)
            }
            if (follow && pose != null) car(anchor, pose.heading.toFloat())
        }

        if (ui.phase == Phase.DRIVE && ui.nav != null) {
            val nav = ui.nav
            Row(
                Modifier.padding(20.dp).widthIn(min = 336.dp).clip(RoundedCornerShape(26.dp)).background(MapGlass)
                    .padding(horizontal = 26.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val kind = when {
                    nav.turn.contains("left") -> "turnleft"
                    nav.turn.contains("right") -> "turnright"
                    else -> "straight"
                }
                Ico(kind, Color.White, 52.dp, stroke = 2.6f)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(dist(nav.distM), style = ts(49, Bold, lh = 1.05f))
                    val pose = ui.pose
                    val road = if (pose != null && pose.s < engine.gateS) engine.region.gateName else "${nav.dest} 방면"
                    Text(road, style = ts(30, Semi, C.sub, 1.15f))
                }
            }
            val pose = ui.pose
            if (pose != null) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(20.dp).size(96.dp).clip(RoundedCornerShape(24.dp)).background(MapGlass),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("${pose.speedKmh.roundToInt()}", style = ts(38, Bold, lh = 1.0f))
                    Text("km/h", style = ts(18, Semi, C.sub, 1.0f))
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(17.dp).fillMaxWidth().height(122.dp).clip(RoundedCornerShape(29.dp))
                    .background(Color(0xFF1E2329)).padding(horizontal = 26.dp),
            ) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(25.dp).clip(CircleShape).background(C.navi.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(15.dp).clip(CircleShape).background(C.navi))
                        }
                        Spacer(Modifier.width(9.dp))
                        val label = ui.waypoints.firstOrNull()?.let { "$it 들러 ${ui.dest}" } ?: ui.dest
                        Text(label, style = ts(28, Semi, C.sub, 1.1f), maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp))
                    val line = buildAnnotatedString {
                        append(nav.eta)
                        withStyle(SpanStyle(color = C.sub, fontWeight = FontWeight.SemiBold)) { append(" · ${"%.1f".format(nav.remainKm)}km") }
                    }
                    Text(line, style = ts(44, Bold, lh = 1.1f))
                }
            }
        } else if (ui.phase == Phase.ARRIVED) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(17.dp).fillMaxWidth().height(122.dp).clip(RoundedCornerShape(29.dp))
                    .background(Color(0xFF1E2329)),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico("flag", C.blue, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text("${ui.dest}에 도착했어요", style = ts(40, Bold, lh = 1.1f))
            }
        } else if (ui.phase == Phase.PREP) {
            // 출발 전: 첫 목적지까지 (시나리오의 서울 출발 기준)
            val first = ui.trip.places.firstOrNull { it.id == TripEngine.FIRST }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(17.dp).fillMaxWidth().height(122.dp).clip(RoundedCornerShape(29.dp))
                    .background(Color(0xFF1E2329)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(25.dp).clip(CircleShape).background(C.navi.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(15.dp).clip(CircleShape).background(C.navi))
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(first?.name ?: "", style = ts(28, Semi, C.sub, 1.1f))
                }
                Spacer(Modifier.height(2.dp))
                val line = buildAnnotatedString {
                    append("11:58 도착")
                    withStyle(SpanStyle(color = C.sub, fontWeight = FontWeight.SemiBold)) { append(" · 364km") }
                }
                Text(line, style = ts(44, Bold, lh = 1.1f))
            }
        }
    }
}

private fun dist(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${(m / 10) * 10}m"

private inline fun DrawScope.upright(at: Offset, rot: Float, sc: Float, crossinline block: DrawScope.() -> Unit) {
    withTransform({
        translate(at.x, at.y)
        rotate(-rot, Offset.Zero)
        scale(1f / sc, 1f / sc, Offset.Zero)
    }) { block() }
}

private val shadowStyle = Shadow(Color(0xF0101214), Offset.Zero, 8f)

private fun DrawScope.label(measurer: TextMeasurer, text: String, y: Float, size: Int, pill: Boolean = false) {
    val style = TextStyle(fontFamily = Asta, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Label, shadow = shadowStyle, letterSpacing = (-0.3).sp)
    val r = measurer.measure(text, style)
    if (pill) {
        val pw = r.size.width + 22f
        val ph = r.size.height + 6f
        drawRoundRect(Color(0xD90C0F12), Offset(-pw / 2f, y - 3f), Size(pw, ph), androidx.compose.ui.geometry.CornerRadius(ph / 2f))
    }
    drawText(r, topLeft = Offset(-r.size.width / 2f, y))
}

/** 명소 표시 (시안 .poi: 밝은 원에 갈색 아이콘). 지금 설명 중인 곳만 파란 고리 */
private fun DrawScope.poi(measurer: TextMeasurer, name: String, hl: Boolean) {
    val r = 24.5f
    if (hl) {
        drawCircle(C.blue.copy(alpha = 0.22f), r * 2.1f, Offset.Zero)
        drawCircle(C.blue, r + 6f, Offset.Zero)
    }
    drawCircle(PoiRing, r + 3f, Offset.Zero)
    drawCircle(PoiFill, r, Offset.Zero)
    withTransform({ translate(-r * 0.62f, -r * 0.62f) }) { drawIco("landmark", PoiIcon, Size(r * 1.24f, r * 1.24f), 2.2f) }
    label(measurer, name, r + 8f, if (hl) 30 else 26)
}

/** 일정 핀 (시안 .pin): 일정은 파란 번호 핀, 담아 둔 곳은 금빛 테두리의 '+' 핀, 들른 곳은 회색 체크 */
private fun DrawScope.pin(measurer: TextMeasurer, pl: TripPlace, n: Int?, fresh: Boolean, showLabel: Boolean) {
    val r = 27f
    val open = pl.status == "open"
    val visited = pl.status == "visited"
    val fill = when {
        visited -> Color(0xFF4A525B)
        open -> C.card
        else -> C.blue
    }
    if (fresh && !visited) drawCircle((if (open) OpenRing else C.blue).copy(alpha = 0.22f), r * 2.3f, Offset(0f, -r * 1.6f))
    val body = Path().apply {
        moveTo(0f, 0f)
        cubicTo(-r * 1.15f, -r * 1.15f, -r * 1.05f, -r * 2.75f, 0f, -r * 2.75f)
        cubicTo(r * 1.05f, -r * 2.75f, r * 1.15f, -r * 1.15f, 0f, 0f)
        close()
    }
    drawPath(body, C.bg, style = Stroke(width = 8f, join = StrokeJoin.Round))
    drawPath(body, fill)
    if (open) drawPath(body, OpenRing, style = Stroke(width = 4f, join = StrokeJoin.Round))
    val c = Offset(0f, -r * 1.72f)
    when {
        visited -> withTransform({ translate(c.x - 16f, c.y - 16f) }) { drawIco("check", Color.White, Size(32f, 32f), 3f) }
        open -> withTransform({ translate(c.x - 15f, c.y - 15f) }) { drawIco("plus", OpenPlus, Size(30f, 30f), 3f) }
        pl.kind == "food" -> withTransform({ translate(c.x - 15f, c.y - 15f) }) { drawIco("food", C.onBlue, Size(30f, 30f), 2.6f) }
        n != null -> {
            val st = TextStyle(fontFamily = Asta, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = C.onBlue)
            val rr = measurer.measure("$n", st)
            drawText(rr, topLeft = Offset(c.x - rr.size.width / 2f, c.y - rr.size.height / 2f))
        }
        else -> withTransform({ translate(c.x - 15f, c.y - 15f) }) { drawIco("pinplus", C.onBlue, Size(30f, 30f), 2.6f) }
    }
    if (showLabel) label(measurer, pl.name, 10f, 25, pill = true)
}

/** 내 차 (내비게이션 공통 기호: 파란 원에 흰 화살표, 진행 방향으로 돌림) */
private fun DrawScope.car(at: Offset, heading: Float) {
    drawCircle(CarBlue.copy(alpha = 0.25f), 42f, at)
    drawCircle(Color(0x66000000), 31f, at + Offset(0f, 4f))
    drawCircle(Color.White, 31f, at)
    drawCircle(CarBlue, 28f, at)
    rotate(heading, at) {
        val p = Path().apply {
            moveTo(at.x, at.y - 16f)
            lineTo(at.x + 12f, at.y + 14f)
            lineTo(at.x, at.y + 7f)
            lineTo(at.x - 12f, at.y + 14f)
            close()
        }
        drawPath(p, Color.White)
    }
}

private fun DrawScope.beam(from: Offset, to: Offset, clock: Long) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val wEnd = 46f
    val p = Path().apply {
        moveTo(from.x, from.y)
        lineTo(to.x + nx * wEnd, to.y + ny * wEnd)
        lineTo(to.x - nx * wEnd, to.y - ny * wEnd)
        close()
    }
    drawPath(p, Brush.linearGradient(listOf(C.blue.copy(alpha = 0.03f), C.blue.copy(alpha = 0.3f)), start = from, end = to))
    val pulse = ((clock % 1400L) / 1400f)
    drawCircle(C.blue.copy(alpha = 0.6f * (1f - pulse)), 32f + 40f * pulse, to, style = Stroke(width = 4f))
}

/** 남은 경로 위 진행 방향 표시(›) */
private fun DrawScope.chevrons(pts: List<Offset>, every: Float, s: Float) {
    var acc = every * 0.5f
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        val seg = hypot(b.x - a.x, b.y - a.y)
        if (seg <= 0f) continue
        var d = acc
        while (d < seg) {
            val f = d / seg
            val c = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            val ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            rotate(ang, c) {
                val p = Path().apply {
                    moveTo(c.x - s, c.y - s * 1.2f)
                    lineTo(c.x + s * 0.6f, c.y)
                    lineTo(c.x - s, c.y + s * 1.2f)
                }
                drawPath(p, Color(0xE6FFFFFF), style = Stroke(width = s * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            d += every
        }
        acc = d - seg
    }
}

