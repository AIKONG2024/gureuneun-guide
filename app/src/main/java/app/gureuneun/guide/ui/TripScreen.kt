package app.gureuneun.guide.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.engine.Card
import app.gureuneun.guide.engine.Phase
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.Ui

/**
 * 차량 화면 하나. 왼쪽은 지도, 오른쪽은 지금 단계에 맞는 카드 하나만 보여 줌.
 * 주행 중에는 큰 글씨와 버튼만, 사진과 영상과 QR 은 정차 중에만 보여 줌 (기획서 '주행 안전').
 */
@Composable
fun TripScreen(engine: TripEngine) {
    val ui by engine.ui.collectAsState()
    // 강조색은 늘 액티브 블루. 여행 모드는 칩과 진입 연출, 창 배경의 은은한 금빛으로만 나타냄
    CompositionLocalProvider(LocalAccent provides C.blue) {
        BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
            // 플레오스 커넥트 앱 영역(1682×1210)에서는 지도와 카드를 반씩, 좁은 창에서는 카드만
            val wide = maxWidth >= 1400.dp
            val rec = ui.record
            if (ui.phase == Phase.RECORD && rec != null) {
                RecordLayout(ui, engine, rec, wide)
            } else {
                Row(Modifier.fillMaxSize().padding(17.dp), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    if (wide) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            MapPanel(ui, engine, Modifier.fillMaxSize())
                            ToastView(ui, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp))
                        }
                    }
                    SidePanel(ui, engine, Modifier.weight(1f).fillMaxHeight())
                }
                if (!wide) ToastView(ui, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            }
            EdgeGlow(ui)
            if (ui.showQr) QrSheet(ui, engine)
            if (ui.showDemo) DemoSheet(ui, engine)
        }
    }
}

// ---------------------------------------------------------------- 오른쪽 카드 영역

private data class PanelState(val kind: String, val ui: Ui)

private fun panelKind(ui: Ui): String = when {
    ui.phase == Phase.PREP -> "plan"
    ui.phase == Phase.ARRIVED || ui.card is Card.Arrive -> "arrive"
    else -> when (val c = ui.card) {
        is Card.Approach -> "approach"
        is Card.Entry -> "entry"
        is Card.SightCard -> "sight:" + c.sight.id + ":" + (c.asked ?: "")
        is Card.Offer -> "offer:" + c.place.id
        is Card.Listen -> "listen"
        else -> "idle"
    }
}

@Composable
fun SidePanel(ui: Ui, engine: TripEngine, modifier: Modifier) {
    val c = ui.card
    val entry = c is Card.Entry
    // 보라·파랑 빛은 차가 말하거나 들을 때만 (대기, 멈춤에서는 끔)
    val aiOn = when (c) {
        is Card.SightCard -> !c.paused && !c.done
        is Card.Offer -> c.accepted == null
        is Card.Listen -> true
        else -> false
    }
    val warm by animateFloatAsState(if (entry) 1f else 0f, tween(1400), label = "warm")
    val ai by animateFloatAsState(if (aiOn) 1f else 0f, tween(900), label = "ai")
    val modeTint by animateFloatAsState(if (ui.mode) 1f else 0.6f, tween(1800), label = "tint")
    val t = rememberInfiniteTransition(label = "glowpulse")
    val pulse by t.animateFloat(0.82f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    Column(
        modifier.clip(RoundedCornerShape(38.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(38.dp)).drawBehind {
            val w = size.width
            val h = size.height
            // 시안 .app: 오른쪽 위의 은은한 금빛
            drawRect(
                Brush.radialGradient(
                    listOf(C.gold.copy(alpha = 0.14f * modeTint), Color.Transparent),
                    center = Offset(w * 0.92f, 0f), radius = w * 0.75f,
                ),
            )
            // 시안 .app.gm: 요금소 통과 순간 창 전체가 따뜻한 빛으로
            if (warm > 0.01f) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1D10), Color(0xFF1B150F), C.panel)), alpha = warm * 0.6f)
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x6BE0B27A), Color.Transparent),
                        center = Offset(w * 0.7f, h * 0.18f), radius = w * 0.85f,
                    ),
                    alpha = warm * 0.6f,
                )
                bottomGlow(listOf(Color(0xD9F0BE6E), Color(0x66E09650), Color.Transparent), warm * pulse)
            }
            // 시안 .glow: 차가 말하고 있을 때 아래쪽 보라·파랑 빛
            if (ai > 0.01f) bottomGlow(listOf(Color(0xD98A6EFF), Color(0x734678FF), Color.Transparent), ai * (if (ui.speaking) pulse else 0.8f))
        },
    ) {
        PanelHeader(ui, engine)
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 34.dp)) {
            AnimatedContent(
                targetState = PanelState(panelKind(ui), ui),
                contentKey = { it.kind },
                transitionSpec = {
                    (fadeIn(tween(420, delayMillis = 100)) + slideInVertically(tween(420, delayMillis = 100)) { it / 18 }) togetherWith fadeOut(tween(160))
                },
                label = "panel",
            ) { s -> PanelBody(s, engine) }
        }
    }
}

@Composable
private fun PanelBody(s: PanelState, engine: TripEngine) {
    val ui = s.ui
    Box(Modifier.fillMaxSize()) {
        when {
            s.kind == "plan" -> PlanView(ui, engine)
            s.kind == "arrive" -> ArriveView(ui, engine)
            s.kind == "approach" -> ApproachView(ui, engine)
            s.kind == "entry" -> {
                val c = ui.card as? Card.Entry
                if (c != null) EntryView(ui, engine, c) else IdleView(ui, engine)
            }
            s.kind.startsWith("sight") -> (ui.card as? Card.SightCard)?.let { SightView(ui, engine, it) }
            s.kind.startsWith("offer") -> (ui.card as? Card.Offer)?.let { OfferView(ui, engine, it) }
            s.kind == "listen" -> ListenView(ui.card as? Card.Listen)
            else -> IdleView(ui, engine)
        }
    }
}

@Composable
fun PanelHeader(ui: Ui, engine: TripEngine) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).padding(start = 30.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(60.dp)
        Spacer(Modifier.width(18.dp))
        Text("구르는 가이드", style = ts(33, Bold))
        Spacer(Modifier.weight(1f))
        StatusChip(ui, engine)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(72.dp).clip(CircleShape).clickable { engine.toggleDemo(true) },
            contentAlignment = Alignment.Center,
        ) { Ico("more", C.sub, 34.dp) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
private fun StatusChip(ui: Ui, engine: TripEngine) {
    if (ui.mode) {
        Chip("${engine.region.name} 여행 모드", icon = "landmark", tone = "gold")
    } else {
        val on = ui.online
        val label = when {
            !on -> "휴대폰 연결 대기"
            ui.phones > 0 -> "휴대폰 ${ui.phones}대 연결"
            else -> "여행 링크 열림"
        }
        Chip(label, icon = "phone", tone = if (on) "blue" else "gray") { engine.toggleQr(true) }
    }
}

/** 창 아래쪽에 번지는 빛 (시안의 .glow / .goldglow) */
private fun DrawScope.bottomGlow(colors: List<Color>, a: Float) {
    val w = size.width
    val h = size.height
    withTransform({ scale(1f, 0.2f, Offset(w / 2f, h + 30f)) }) {
        drawCircle(
            Brush.radialGradient(colors, center = Offset(w / 2f, h + 30f), radius = w * 0.62f),
            radius = w * 0.62f, center = Offset(w / 2f, h + 30f), alpha = a.coerceIn(0f, 1f),
        )
    }
}

// ---------------------------------------------------------------- 실내 조명 표현 (에뮬레이터에는 실내 조명이 없어 화면 가장자리 빛으로 보여 줌)

@Composable
private fun EdgeGlow(ui: Ui) {
    val approach = ui.card is Card.Approach
    val dim by animateFloatAsState(if (approach) 1f else 0f, tween(2600), label = "dim")
    if (dim < 0.01f) return
    // 여행지가 가까워지면 화면도 실내 조명처럼 살짝 어두워짐
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f * dim)),
                center = Offset(w / 2f, h / 2f),
                radius = maxOf(w, h) * 0.6f,
            ),
        )
    }
}

/** 요금소를 지나는 순간 화면을 한 번 가로지르는 빛 */
@Composable
private fun EntrySweep(ui: Ui) {
    val c = ui.card as? Card.Entry
    val key = if (c != null && c.step == 1) c.at else null
    val p = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        p.snapTo(0f)
        p.animateTo(1f, tween(2200, easing = LinearEasing))
    }
    val v = p.value
    if (key == null || v <= 0f || v >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val x = -w * 0.35f + v * w * 1.7f
        val band = w * 0.2f
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color(0x14FFF6E6), Color(0x38FFFFFF), Color(0x14FFF6E6), Color.Transparent),
                start = Offset(x - band, 0f),
                end = Offset(x + band, h * 0.3f),
            ),
        )
    }
}

// ---------------------------------------------------------------- 방향지시등, 알림

@Composable
private fun SignalBadge(ui: Ui, modifier: Modifier) {
    AnimatedVisibility(ui.turnSignal != "OFF", modifier, enter = fadeIn(tween(150)), exit = fadeOut(tween(400))) {
        val t = rememberInfiniteTransition(label = "blink")
        val a by t.animateFloat(1f, 0.2f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "a")
        val right = ui.turnSignal.contains("RIGHT")
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xF2151B21)).padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico("blink", C.green.copy(alpha = a), 38.dp, Modifier.then(if (right) Modifier else Modifier.graphicsMirror()))
            Spacer(Modifier.width(14.dp))
            Text(if (right) "오른쪽 방향지시등" else "왼쪽 방향지시등", style = ts(26, Bold, lh = 1.1f))
            Spacer(Modifier.width(16.dp))
            Text("설명 잠시 멈춤", style = ts(24, Semi, C.sub, 1.1f))
        }
    }
}

@Composable
fun ToastView(ui: Ui, modifier: Modifier) {
    val last = remember { mutableStateOf("") }
    ui.toast?.let { if (it != last.value) last.value = it }
    AnimatedVisibility(
        ui.toast != null, modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(320)),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xF5222A32)).border(1.dp, C.line, RoundedCornerShape(999.dp))
                .padding(horizontal = 30.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(LocalAccent.current))
            Spacer(Modifier.width(14.dp))
            Text(last.value, style = ts(26, Semi, lh = 1.15f))
        }
    }
}

fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

@Composable
fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }

fun Modifier.graphicsMirror(): Modifier = this.graphicsLayer { scaleX = -1f }

// ---------------------------------------------------------------- QR (정차 중에만)

@Composable
private fun QrSheet(ui: Ui, engine: TripEngine) {
    val qr = remember(ui.qrUrl) { Qr.make(ui.qrUrl, 520) }
    Box(
        Modifier.fillMaxSize().background(Color(0xD905080B)).noRippleClick { engine.toggleQr(false) },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.width(1440.dp).clip(RoundedCornerShape(36.dp)).background(C.panel).swallowTaps().padding(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(460.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(28.dp)) {
                Image(qr, null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text("휴대폰으로 '우리 여행' 열기", style = ts(44, Heavy, lh = 1.2f))
                Spacer(Modifier.height(10.dp))
                Text("앱을 설치하지 않아도 웹으로 바로 열려요", style = ts(28, color = C.sub))
                Spacer(Modifier.height(38.dp))
                QrStep(1, "휴대폰 카메라로 QR을 찍어요")
                QrStep(2, "가고 싶은 곳의 링크를 붙여 넣어 담아요")
                QrStep(3, "담은 곳이 이 차 일정에 바로 들어와요")
                Spacer(Modifier.height(26.dp))
                Text(ui.qrUrl, style = ts(22, Semi, C.faint))
                Spacer(Modifier.height(34.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton("문자로 링크 받기", Modifier.weight(1f), primary = false, icon = "send") { engine.sendLink() }
                    BigButton("닫기", Modifier.width(220.dp)) { engine.toggleQr(false) }
                }
            }
        }
    }
}

@Composable
private fun QrStep(n: Int, text: String) {
    Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.card2), contentAlignment = Alignment.Center) {
            Text("$n", style = ts(24, Heavy, C.blue, 1f))
        }
        Spacer(Modifier.width(18.dp))
        Text(text, style = ts(30, Semi, lh = 1.2f))
    }
}

// ---------------------------------------------------------------- 시연 메뉴

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoSheet(ui: Ui, engine: TripEngine) {
    var auto by remember { mutableStateOf(engine.isDemoAuto) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).noRippleClick { engine.toggleDemo(false) }) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(720.dp).background(C.panel).swallowTaps()
                .padding(horizontal = 44.dp, vertical = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시연 메뉴", style = ts(40, Heavy))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(64.dp).clip(CircleShape).clickable { engine.toggleDemo(false) }, contentAlignment = Alignment.Center) {
                    Ico("x", C.sub, 30.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("심사용 시연 메뉴예요. 실제 차량에서는 위치와 차량 데이터로 같은 흐름이 진행돼요", style = ts(22, color = C.sub, lh = 1.45f))
            DemoSection("주행")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DemoChip("안내 시작", ui.phase == Phase.PREP) { engine.toggleDemo(false); engine.startTrip() }
                DemoChip("도착으로 건너뛰기", ui.phase == Phase.DRIVE) { engine.toggleDemo(false); engine.demoArrive() }
                for (x in listOf(1.0, 3.0, 5.0, 8.0)) DemoChip("${x.toInt()}배속") { engine.demoSpeed(x) }
            }
            DemoSection("휴대폰")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DemoChip("엄마가 황리단길 카페 담기") { engine.toggleDemo(false); engine.injectDemoPlace() }
            }
            DemoSection("말하기")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (t in listOf("응", "다음에", "저 숲은 뭐야?", "저기 뭐야?")) DemoChip("\"$t\"", ui.phase == Phase.DRIVE) { engine.toggleDemo(false); engine.demoSay(t) }
                DemoChip("마이크로 묻기", ui.phase == Phase.DRIVE) { engine.toggleDemo(false); engine.listenAsk() }
            }
            DemoSection("차량")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DemoChip("오른쪽 방향지시등") { engine.toggleDemo(false); engine.demoSignal() }
                DemoChip("여행 모드 끝내기", ui.mode) { engine.toggleDemo(false); engine.exitMode() }
                DemoChip(if (auto) "자동 진행 켜짐" else "자동 진행 꺼짐") { auto = !auto; engine.isDemoAuto = auto }
            }
            Spacer(Modifier.weight(1f))
            BigButton("처음부터 다시", Modifier.fillMaxWidth(), primary = false, icon = "replay") { engine.toggleDemo(false); engine.demoReset() }
        }
    }
}

@Composable
private fun DemoSection(t: String) {
    Text(t, style = ts(24, Semi, C.faint), modifier = Modifier.padding(top = 34.dp, bottom = 14.dp))
}

@Composable
private fun DemoChip(t: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.height(70.dp).clip(shape).background(if (enabled) C.card2 else C.card).border(1.dp, C.line, shape)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(t, style = ts(26, Semi, if (enabled) C.text else C.faint, 1.1f))
    }
}

