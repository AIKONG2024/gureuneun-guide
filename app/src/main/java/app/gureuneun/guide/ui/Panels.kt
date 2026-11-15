package app.gureuneun.guide.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.TripPlace
import app.gureuneun.guide.engine.Card
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.Ui
import app.gureuneun.guide.engine.Upcoming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// 시안 보조 색 (frame.css 와 s17 시안)
private val Cyan2 = Color(0xFF7FD6EA)
private val Soft = Color(0xFFDDE3E8)
private val GoldLabel = Color(0xFFD9B98E)
private val WarmLabel = Color(0xFFD9C2A0)
private val WarmValue = Color(0xFFFFF3E2)
private val WarmTitle = Color(0xFFF6E7CF)
private val Hint = Color(0xFFB9C1C9)
private val Speak = Color(0xFFC9D1D8)
private val Glass = Color(0x0FFFFFFF)
private val Glass2 = Color(0x14FFFFFF)

// ================================================================ 출발 전: 우리 여행 (정차 중이라 사진을 보여 줌)

@Composable
fun PlanView(ui: Ui, engine: TripEngine) {
    val trip = ui.trip
    val timed = trip.places.filter { it.time != null }.sortedBy { it.time }
    val open = trip.places.filter { it.time == null && it.status == "open" }
    val first = trip.places.firstOrNull { it.id == TripEngine.FIRST }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(28.dp))
        Text("우리 여행 · ${trip.title}", style = ts(44, Bold, lh = 1.2f))
        Spacer(Modifier.height(6.dp))
        Text("담아 둔 곳을 경로와 시간에 맞춰 정리했어요", style = ts(28, Semi, C.sub))
        Spacer(Modifier.height(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            var order = 0
            var firstShown = false
            for (p in timed) {
                val food = p.kind == "food"
                val n = if (food) null else ++order
                val style = when {
                    food -> "ai"
                    !firstShown -> "first".also { firstShown = true }
                    else -> "plain"
                }
                PlanRow(ui, p, n, style)
            }
        }
        Row(Modifier.padding(start = 6.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico("bookmark", GoldLabel, 25.dp)
            Spacer(Modifier.width(10.dp))
            Text("담아 둔 곳 · 가까이 가면 먼저 알려 드려요", style = ts(25, Bold, GoldLabel))
        }
        if (open.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(23.dp)).border(2.dp, Glass2, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("휴대폰에서 링크를 붙여 넣으면 여기에 담겨요", style = ts(26, Medium, C.faint))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { open.forEach { PlanRow(ui, it, null, "open") } }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(17.dp)) {
            BigButton("휴대폰 연결", Modifier.weight(1f), primary = false, icon = "qr") { engine.toggleQr(true) }
            BigButton("${Geo.ro(first?.name ?: "목적지")} 안내 시작", Modifier.weight(1f), icon = "nav") { engine.startTrip() }
        }
    }
}

@Composable
private fun PlanRow(ui: Ui, p: TripPlace, order: Int?, style: String) {
    val m = ui.trip.member(p.by)
    val who = m?.name ?: p.by
    val fresh = ui.newPlaceId == p.id
    val pulse = if (fresh) {
        val t = rememberInfiniteTransition(label = "fresh")
        t.animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p").value
    } else 0f
    val shape = RoundedCornerShape(23.dp)
    val (bg, ring) = when (style) {
        "ai" -> C.blue.copy(alpha = 0.07f) to C.blue.copy(alpha = 0.35f)
        "first" -> C.card to Color.Transparent
        "open" -> Color.Transparent to Color(0x8CE0B27A)
        else -> Color.Transparent to Color.Transparent
    }
    val freshRing = if (style == "open") Color(0xFFE0B27A) else C.blue
    Row(
        Modifier.fillMaxWidth().height(110.dp).clip(shape).background(bg)
            .border(2.dp, if (fresh) freshRing.copy(alpha = pulse) else ring, shape).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(p.time ?: "미정", style = ts(30, Bold, if (style == "open") GoldLabel else Soft), modifier = Modifier.width(100.dp))
        if (style == "ai") {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFF10303A)), contentAlignment = Alignment.Center) {
                Ico("food", Cyan2, 38.dp)
            }
        } else {
            Thumb(p, 76.dp)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(if (style == "ai") "점심 · ${p.name}" else p.name, style = ts(33, Bold, lh = 1.15f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (style == "ai") {
                    Ico("bookmark", Cyan2, 23.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${Geo.iGa(who)} 담아 둔 식당 · 가는 길에 맞춰 제안", style = ts(23, Semi, Cyan2, 1.1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Avatar(who, m?.let { Color(it.color) } ?: C.blue, 32.dp)
                    Spacer(Modifier.width(9.dp))
                    Text(placeSub(p, who), style = ts(23, Semi, C.sub, 1.1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when {
            p.status == "visited" -> CheckDot()
            order != null -> NumberDot(order)
        }
    }
}

private fun placeSub(p: TripPlace, who: String): String {
    val tail = when {
        p.id == TripEngine.FINAL -> " · 야경 시간에 맞춤"
        p.source.isNotBlank() && p.source != "지나는 명소" -> " · ${p.source}에서"
        else -> ""
    }
    return "${Geo.iGa(who)} 담음$tail"
}

// ================================================================ 주행 중 기본 화면

@Composable
fun IdleView(ui: Ui, engine: TripEngine) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(26.dp))
        TodaySteps(ui)
        Spacer(Modifier.height(40.dp))
        if (!ui.mode) {
            val d = ui.pose?.let { (engine.gateS - it.s).toInt().coerceAtLeast(0) } ?: 0
            Text("곧 여행지예요", style = ts(28, Semi, C.faint))
            Spacer(Modifier.height(10.dp))
            Text("${engine.region.gateName}까지 ${fmtDist(d)}", style = ts(52, Bold, lh = 1.2f))
            Spacer(Modifier.height(14.dp))
            Text("요금소를 지나면 조명과 소리가 ${engine.region.name} 테마로 바뀌어요", style = ts(31, Medium, C.sub, lh = 1.42f))
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("지나는 명소", style = ts(30, Bold))
                Spacer(Modifier.width(14.dp))
                Text("가까워지면 먼저 알려 드려요", style = ts(25, Medium, C.faint))
            }
            Spacer(Modifier.height(18.dp))
            if (ui.upcoming.isEmpty()) {
                Text("이 길에서 더 알려 드릴 명소가 없어요", style = ts(30, Medium, C.sub))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.upcoming.forEachIndexed { i, u -> UpcomingRow(u, i == 0) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        AskBar { engine.listenAsk() }
    }
}

/** 오늘 일정: 들른 곳, 지금 가는 곳, 다음 곳 */
@Composable
private fun TodaySteps(ui: Ui) {
    val timed = ui.trip.places.filter { it.time != null }.sortedBy { it.time }
    val extra = ui.trip.places.filter { it.time == null && (it.status == "next" || it.status == "visited") }
    val seq = buildList {
        var added = false
        for (p in timed) {
            if (!added && p.name == ui.dest) {
                addAll(extra)
                added = true
            }
            add(p)
        }
        if (!added) addAll(extra)
    }
    if (seq.isEmpty()) return
    val cur = seq.indexOfFirst { it.status != "visited" }.let { if (it < 0) seq.size - 1 else it }
    val from = (cur - 1).coerceAtLeast(0)
    val shown = seq.subList(from, min(seq.size, from + 3))
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.width(20.dp).height(2.dp).background(Glass2))
            val done = p.status == "visited"
            val now = !done && p == seq[cur]
            when {
                now -> Chip(p.name, tone = "blue")
                done -> Chip(p.name, icon = "check", tone = "gray")
                else -> Chip(p.name, tone = "gray")
            }
        }
    }
}

@Composable
private fun UpcomingRow(u: Upcoming, first: Boolean) {
    val shape = RoundedCornerShape(23.dp)
    Row(
        Modifier.fillMaxWidth().height(107.dp).clip(shape).background(if (first) C.blue.copy(alpha = 0.10f) else C.card)
            .border(2.dp, if (first) C.blue.copy(alpha = 0.5f) else Color.Transparent, shape).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(62.dp).clip(RoundedCornerShape(17.dp)).background(if (first) C.blue else C.card2), contentAlignment = Alignment.Center) {
            Ico(sideIcon(u.side), if (first) C.onBlue else Soft, 32.dp, stroke = 2.4f)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(u.sight.name, style = ts(32, Bold, lh = 1.12f))
            Text("${sideWord(u.side)} · ${fmtDist(u.aheadM)} 앞", style = ts(26, Medium, C.sub, 1.2f))
        }
        if (u.sight.tag.isNotBlank()) Tag(u.sight.tag)
    }
}

@Composable
private fun AskBar(onClick: () -> Unit) {
    val shape = RoundedCornerShape(23.dp)
    Row(
        Modifier.fillMaxWidth().height(100.dp).clip(shape).background(C.card).clickable { onClick() }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(C.card2), contentAlignment = Alignment.Center) {
            Ico("mic", Soft, 32.dp)
        }
        Spacer(Modifier.width(20.dp))
        Text("궁금한 곳은 말로 물어보세요", style = ts(30, Semi))
        Spacer(Modifier.weight(1f))
        Text("저기 뭐야?", style = ts(26, Medium, C.faint))
        Spacer(Modifier.width(8.dp))
    }
}

// ================================================================ 여행지 접근: 조명을 낮추고 전주

@Composable
fun ApproachView(ui: Ui, engine: TripEngine) {
    val d = ui.pose?.let { (engine.gateS - it.s).toInt().coerceAtLeast(0) } ?: 0
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(64.dp))
        Text("${Geo.iGa(engine.region.name)} 가까워졌어요", style = ts(56, Bold, lh = 1.2f))
        Spacer(Modifier.height(18.dp))
        Text("${fmtDist(d)} 앞 요금소를 지나면 여행 모드로 바뀌어요", style = ts(32, Medium, C.sub, lh = 1.42f))
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            ThemeTile("light", "실내 조명", "서서히 낮춤", Modifier.weight(1f), warm = false)
            ThemeTile("music", "소리", "짧은 전주", Modifier.weight(1f), warm = false)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
    }
}

// ================================================================ 여행 모드 진입 (약 10초 연출 → 안내 시작)

@Composable
fun EntryView(ui: Ui, engine: TripEngine, c: Card.Entry) {
    val theme = engine.region.theme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val a = appear.value
    val stagger = c.step == 1
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.55f))
        Text(
            theme.welcome, style = ts(29, Bold, WarmTitle),
            modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 30f },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            engine.region.name, style = ts(93, Heavy, Color.White, 1.05f),
            modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 40f },
        )
        Spacer(Modifier.height(30.dp))
        if (c.step == 2) {
            Text(theme.line.replace(". ", ".\n"), style = ts(44, Bold, lh = 1.3f))
            Spacer(Modifier.height(34.dp))
        } else {
            Text("${theme.instrument} 연주에 맞춰 조명과 화면이 바뀌어요", style = ts(31, Medium, C.sub, lh = 1.4f))
            Spacer(Modifier.height(34.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            ThemeTile("music", "연주", theme.instrument, Modifier.weight(1f), delayMs = if (stagger) 700 else 0)
            ThemeTile("light", "실내 조명", theme.name, Modifier.weight(1f), delayMs = if (stagger) 1500 else 0)
            ThemeTile("screen", "화면", "${engine.region.name} 테마", Modifier.weight(1f), delayMs = if (stagger) 2300 else 0)
        }
        Spacer(Modifier.weight(1.2f))
    }
}

/** 진입 연출의 연주·조명·화면 타일 (시안 .rows) */
@Composable
fun ThemeTile(icon: String, label: String, value: String, modifier: Modifier = Modifier, delayMs: Int = 0, warm: Boolean = true) {
    val a = remember { Animatable(if (delayMs == 0) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) {
            delay(delayMs.toLong())
            a.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }
    val shape = RoundedCornerShape(23.dp)
    Column(
        modifier.height(125.dp).graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 20f }.clip(shape).background(Glass)
            .border(2.dp, if (warm) Color(0x59E0B27A) else Glass2, shape).padding(horizontal = 23.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(icon, if (warm) WarmLabel else C.sub, 23.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = ts(23, Semi, if (warm) WarmLabel else C.sub, 1.1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = ts(32, Bold, if (warm) WarmValue else C.text, 1.15f), maxLines = 1)
    }
}

// ================================================================ 지나는 명소 (주행 중: 사진 없이 큰 글씨와 방향)

@Composable
fun SightView(ui: Ui, engine: TripEngine, c: Card.SightCard) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(40.dp))
        if (c.asked != null) {
            AskedBubble(c.asked)
            Spacer(Modifier.height(26.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DirBadge(ui, c)
            Spacer(Modifier.width(14.dp))
            if (c.sight.tag.isNotBlank()) Tag(c.sight.tag)
        }
        Spacer(Modifier.height(30.dp))
        Text(lineBreak(c.line), style = ts(55, Bold, lh = 1.22f))
        Spacer(Modifier.height(16.dp))
        Text(c.sight.short, style = ts(34, Medium, C.sub, lh = 1.42f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(34.dp))
        if (c.paused) PauseCard() else SpeakBar(ui, c)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico("phone", C.faint, 24.dp)
            Spacer(Modifier.width(10.dp))
            Text("사진과 자세한 설명은 일행 휴대폰에도 떠요", style = ts(24, Medium, C.faint), maxLines = 1)
        }
        Text("설명은 한국관광공사 관광 정보를 바탕으로 해요", style = ts(22, Medium, C.faint), modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(17.dp)) {
            BigButton("다시", Modifier.weight(1f), primary = false, icon = "replay") { engine.replaySight() }
            BigButton("그만", Modifier.weight(1f), primary = false, icon = "stop") { engine.stopSight() }
            BigButton("들르기", Modifier.weight(1.25f), icon = "pinplus") { engine.visitSight() }
        }
    }
}

@Composable
private fun DirBadge(ui: Ui, c: Card.SightCard) {
    // 어느 쪽 창밖인지는 안내 문장과 같게(경로 기준), 이미 지나쳤는지만 지금 진행 방향으로 판단
    val pose = ui.pose
    val side = c.side
    var behind = false
    var dist = c.distM
    if (pose != null) {
        val diff = ((Geo.bearing(pose.pos, c.sight.pos) - pose.heading + 540.0) % 360.0) - 180.0
        behind = abs(diff) > 120
        dist = Geo.dist(pose.pos, c.sight.pos).toInt()
    }
    val label = "${sideWord(side)}${if (behind) " 뒤" else ""} · ${fmtDist(dist)}"
    Row(
        Modifier.height(56.dp).clip(RoundedCornerShape(17.dp)).background(C.blue).padding(start = 15.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(sideIcon(side), C.onBlue, 32.dp, stroke = 3f)
        Spacer(Modifier.width(9.dp))
        Text(label, style = ts(29, Heavy, C.onBlue, 1.1f))
    }
}

@Composable
private fun AskedBubble(text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(23.dp, 23.dp, 23.dp, 6.dp)).background(C.card).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico("mic", Cyan2, 26.dp)
        Spacer(Modifier.width(12.dp))
        Text("\"$text\"", style = ts(28, Semi, lh = 1.1f))
    }
}

@Composable
private fun SpeakBar(ui: Ui, c: Card.SightCard) {
    val elapsed = (ui.clock - c.at).coerceAtLeast(0L)
    val prog = if (c.done) 1f else (elapsed.toFloat() / c.estMs).coerceIn(0f, 0.97f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
        WaveBars(!c.done)
        Spacer(Modifier.width(14.dp))
        Text(if (c.done) "설명을 마쳤어요" else "설명 중", style = ts(28, Semi, Speak))
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(C.track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(prog).clip(RoundedCornerShape(4.dp)).background(C.trackFill))
        }
        Spacer(Modifier.width(20.dp))
        Text("${mmss(min(elapsed, c.estMs))} / ${mmss(c.estMs)}", style = ts(26, Medium, C.faint))
    }
}

/** 차로 변경 중 멈춤 (조용한 회색 카드) */
@Composable
private fun PauseCard() {
    val shape = RoundedCornerShape(23.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(C.card).border(1.dp, Glass2, shape).padding(horizontal = 26.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico("pause", C.sub, 32.dp)
        Spacer(Modifier.width(18.dp))
        Column {
            Text("차로 변경 중이라 잠시 멈췄어요", style = ts(32, Bold, lh = 1.2f))
            Text("방향지시등이 꺼지면 이어서 들려 드려요", style = ts(28, Medium, C.sub, lh = 1.3f))
        }
    }
}

/** 말하는 중 표시: 시안의 보라·파랑 막대 */
@Composable
fun WaveBars(active: Boolean, h: Dp = 41.dp) {
    val t = rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ph")
    Canvas(Modifier.width(78.dp).height(h)) {
        val n = 7
        val gap = size.width / n
        val brush = Brush.verticalGradient(listOf(C.wave1, C.wave2))
        for (i in 0 until n) {
            val amp = if (active) 0.3f + 0.7f * abs(sin(ph + i * 0.9f)) else 0.24f
            val bh = size.height * amp
            val x = gap * i + gap / 2f
            drawLine(brush, Offset(x, (size.height - bh) / 2f), Offset(x, (size.height + bh) / 2f), strokeWidth = gap * 0.5f, cap = StrokeCap.Round)
        }
    }
}

// ================================================================ 담아 둔 곳 제안

@Composable
fun OfferView(ui: Ui, engine: TripEngine, c: Card.Offer) {
    val m = ui.trip.member(c.place.by)
    val who = m?.name ?: c.place.by
    val next = ui.trip.places.filter { it.time != null && it.status != "visited" && it.id != c.place.id && it.kind != "food" }.minByOrNull { it.time ?: "" }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(who, m?.let { Color(it.color) } ?: C.blue, 44.dp)
            Spacer(Modifier.width(14.dp))
            Text("${Geo.iGa(who)} 담아 둔 곳", style = ts(30, Bold, Soft))
            Spacer(Modifier.weight(1f))
            Ico("route", Cyan2, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text("경로에서 ${c.minutes}분", style = ts(28, Bold, Cyan2, 1.1f))
        }
        Spacer(Modifier.height(56.dp))
        Text("${c.place.name}에\n들를까요?", style = ts(58, Bold, lh = 1.22f))
        Spacer(Modifier.height(18.dp))
        if (next != null) {
            Text("들렀다 가도 ${next.name} ${next.time} 일정에 맞춰 도착해요", style = ts(33, Medium, C.sub, lh = 1.45f))
            Spacer(Modifier.height(14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(kindIcon(c.place.kind), C.faint, 26.dp)
            Spacer(Modifier.width(10.dp))
            val note = c.place.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            Text("${c.place.source}에서 담은 곳$note", style = ts(26, Medium, C.faint))
        }
        Spacer(Modifier.weight(1f))
        if (c.accepted == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 22.dp)) {
                Ico("mic", C.wave1, 28.dp)
                Spacer(Modifier.width(12.dp))
                Text("\"응\" 또는 \"다음에\"라고 말해도 돼요", style = ts(28, Semi, Hint))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                BigButton("다음에", Modifier.weight(1f), primary = false) { engine.replyOffer(false) }
                BigButton("응, 들를게요", Modifier.weight(1.25f), icon = "pinplus") { engine.replyOffer(true) }
            }
        } else {
            val yes = c.accepted == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clip(RoundedCornerShape(23.dp, 23.dp, 6.dp, 23.dp)).background(C.card2).padding(horizontal = 26.dp, vertical = 16.dp)) {
                    Text("\"${c.reply ?: if (yes) "응" else "다음에"}\"", style = ts(30, Semi, lh = 1.1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            val shape = RoundedCornerShape(26.dp)
            Row(
                Modifier.fillMaxWidth().height(112.dp).clip(shape).background(if (yes) C.blue.copy(alpha = 0.14f) else C.card)
                    .border(2.dp, if (yes) C.blue.copy(alpha = 0.5f) else Glass2, shape).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(if (yes) "check" else "x", if (yes) C.blueText else C.sub, 36.dp, stroke = 2.4f)
                Spacer(Modifier.width(16.dp))
                Text(if (yes) "${c.place.name}, 경유지로 넣었어요" else "다음에 들를게요", style = ts(31, Bold, if (yes) C.blueText else C.text, 1.1f))
            }
        }
    }
}

// ================================================================ 말로 묻기

@Composable
fun ListenView(c: Card.Listen?) {
    val t = rememberInfiniteTransition(label = "listen")
    val r by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                for (k in 0..2) {
                    val f = (r + k / 3f) % 1f
                    drawCircle(C.wave2.copy(alpha = 0.45f * (1f - f)), radius = size.minDimension / 2f * (0.42f + 0.58f * f), style = Stroke(4f))
                }
            }
            Box(Modifier.size(128.dp).clip(CircleShape).background(Brush.linearGradient(listOf(C.wave1, C.wave2))), contentAlignment = Alignment.Center) {
                Ico("mic", Color.White, 58.dp)
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("듣고 있어요", style = ts(52, Bold))
        Spacer(Modifier.height(14.dp))
        val said = c?.partial?.takeIf { it.isNotBlank() }
        Text(if (said != null) "\"$said\"" else "\"저기 뭐야?\"처럼 물어보세요", style = ts(32, Medium, if (said != null) C.text else C.sub))
    }
}

// ================================================================ 도착 (정차): 여행 영상 만드는 중

@Composable
fun ArriveView(ui: Ui, engine: TripEngine) {
    val c = ui.card as? Card.Arrive
    val place = c?.place ?: ui.trip.places.firstOrNull { it.id == TripEngine.FINAL }
    val el = if (c != null) ui.clock - c.at else 99_999L
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(29.dp))) {
            // 정차한 뒤에만 사진을 띄움
            if ((ui.pose?.speedKmh ?: 0.0) < 3.0) Photo(place?.photo, Modifier.fillMaxSize(), maxW = 1000)
            else Box(Modifier.fillMaxSize().background(C.card))
            Photos.credit(LocalContext.current, place?.photo)?.let {
                Text(it, style = ts(19, Medium, Color(0xCCFFFFFF)), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("${place?.name ?: "목적지"}에 도착했어요", style = ts(52, Bold, lh = 1.2f))
        Spacer(Modifier.height(10.dp))
        Text("오늘 달린 길과 들른 곳을 1분 여행 영상으로 만들고 있어요", style = ts(31, Medium, C.sub, lh = 1.4f))
        Spacer(Modifier.height(26.dp))
        val items = listOf(
            "route" to "이동 경로 지도",
            "light" to "${engine.region.name} 도착 ${ui.enteredAt ?: ""}".trim(),
            "pin" to "들른 곳 ${ui.visited.size}곳",
            "wave" to "들려준 명소 설명 ${ui.heard.size}편",
            "photo" to "일행이 휴대폰으로 올린 사진",
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEachIndexed { i, (ic, label) -> MakingRow(ic, label, el > 650L * (i + 1)) }
        }
    }
}

@Composable
private fun MakingRow(icon: String, label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (done) C.blue else C.card2), contentAlignment = Alignment.Center) {
            Ico(if (done) "check" else icon, if (done) C.onBlue else C.faint, 24.dp, stroke = if (done) 2.6f else 2f)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = ts(29, Semi, if (done) C.text else C.faint, 1.1f))
    }
}

// ================================================================ 공용 조각

@Composable
fun Thumb(p: TripPlace, size: Dp) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, p.photo) {
        value = withContext(Dispatchers.IO) { p.photo.takeIf { it.isNotBlank() && !it.startsWith("/") }?.let { Photos.load(ctx, it, 240) } }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(17.dp)).background(C.card2), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Ico(kindIcon(p.kind), C.sub, size * 0.46f)
    }
}

@Composable
fun Tag(text: String) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(C.card2).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(text, style = ts(22, Semi, C.sub, 1.1f))
    }
}

@Composable
private fun NumberDot(n: Int) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(C.blue), contentAlignment = Alignment.Center) {
        Text("$n", style = ts(23, Heavy, C.onBlue, 1f))
    }
}

@Composable
private fun CheckDot() {
    Box(Modifier.size(44.dp).clip(CircleShape).background(C.card2), contentAlignment = Alignment.Center) {
        Ico("check", C.green, 26.dp, stroke = 2.6f)
    }
}

fun fmtDist(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${(m / 10) * 10}m"

fun sideWord(side: Int) = when (side) {
    1 -> "오른쪽"
    -1 -> "왼쪽"
    else -> "앞쪽"
}

fun sideIcon(side: Int) = when (side) {
    1 -> "right"
    -1 -> "left"
    else -> "ahead"
}

fun kindIcon(kind: String) = when (kind) {
    "food" -> "food"
    "cafe" -> "cup"
    else -> "landmark"
}

fun mmss(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** 큰 제목을 두 줄로: 가운데에 가까운 띄어쓰기에서 나눔 */
fun lineBreak(s: String): String {
    if (s.length <= 11 || s.contains("\n")) return s
    val mid = s.length / 2
    val idx = s.indices.filter { s[it] == ' ' }.minByOrNull { abs(it - mid) } ?: return s
    return s.substring(0, idx) + "\n" + s.substring(idx + 1)
}

