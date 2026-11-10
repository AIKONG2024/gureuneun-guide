package app.gureuneun.guide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.Ko
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.StoryLength
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.StoryPlayer
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.net.CompanionClient
import app.gureuneun.guide.sdk.MediaKeys
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.delay

/**
 * 여행 중 차 화면.
 * 달리는 동안(주행 규제 중이거나 P 가 아닐 때): 현대 블루 이야기 카드 하나가 주인공. 지금 이야기하는 곳 이름,
 * 그곳이 보이는 쪽(차 그림 둘레에 밝힘), 큰 단추 네 개만. 글과 영상, QR 은 차가 섰을 때만.
 * 차가 서면: 명소 장면을 크게, 옆에 QR 과 오늘 코스.
 * 앱이 내비게이션 옆 절반 창(Pleos 1:1 분할, 세로)에 들어가면 카드 안의 그림과 글을 위아래로 쌓고,
 * 답하는 단추는 주 단추를 위에 크게 둠. 달리는 동안은 스크롤 없이 한 화면에 들어감.
 */
@Composable
fun TripScreen(
    ui: TripEngine.Ui,
    vehicle: VehicleBridge.State,
    ux: UxBridge.Ux,
    @Suppress("UNUSED_PARAMETER") navi: NaviBridge.State,
    group: CompanionClient.State,
    engine: TripEngine,
    partial: String = "",
    level: Double = 0.0,
) {
    ui.scenario ?: return
    val parked = vehicle.parked && !ux.restricted
    var showDemo by remember { mutableStateOf(false) }
    // 뒤로 키는 시연 도구 창부터 닫음 (앱이 뒤로 숨지 않게)
    BackHandler(enabled = showDemo && ui.demoMode) { showDemo = false }

    BrandBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 1100.dp
            val inner = maxHeight - 64.dp
            Box(Modifier.fillMaxSize().padding(32.dp)) {
                when {
                    wide -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            TopBarArea(ui, group, engine, parked, partial, level, compact = false) { showDemo = true }
                            if (parked) ParkedHero(ui, engine, Modifier.weight(1f)) else NowCard(ui, engine, Modifier.weight(1f), compact = false)
                        }
                        if (parked) {
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                ParkedSide(ui, group, engine)
                            }
                        } else {
                            // 달리는 동안 오른쪽 칸은 밀지 않음: 지도가 남는 높이를 채움
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                DrivingSide(ui)
                            }
                        }
                    }
                    !parked -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TopBarArea(ui, group, engine, parked, partial, level, compact = true) { showDemo = true }
                        NowCard(ui, engine, Modifier.weight(1f), compact = true)
                        NextStrip(ui)
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        TopBarArea(ui, group, engine, parked, partial, level, compact = true) { showDemo = true }
                        // 사진 칸 바로 아래 QR 카드가 첫 화면에 들어오게 높이를 맞춤 (나머지 카드는 밀어 봄)
                        ParkedHero(ui, engine, Modifier.height((inner - 520.dp).coerceIn(600.dp, 760.dp)), compact = true)
                        ParkedSide(ui, group, engine)
                    }
                }
            }

            if (ui.phase == TripEngine.Phase.ROLLCALL) RollCall(ui, vehicle, group, parked, engine)
            // 차가 서 있을 때만 화면 가운데 물음 창. 달리는 동안의 물음은 이야기 카드 안에서 답함 (화면을 덮지 않게)
            if (parked && ui.phase == TripEngine.Phase.DRIVING) {
                val offer = ui.offer
                val pick = ui.pick
                val windowAsk = ui.windowAsk
                when {
                    ui.confirmEnd -> EndDialog(ui, engine)
                    windowAsk != null -> WindowDialog(windowAsk.name.pick(ui.lang), windowAsk.ambience, ui, engine)
                    offer != null -> OfferDialog(offer, ui, engine)
                    pick != null -> PickDialog(pick, ui, engine)
                }
            }
            if (showDemo && ui.demoMode) DemoSheet(ui, vehicle, engine) { showDemo = false }
            // 알림은 조작 단추 줄 바로 위에 (절반 창에서 달릴 때 아래 '다음 명소' 줄이 있으면 그만큼 더 위)
            ToastHost(ui.toast, ui.toastSeq, bottom = if (!wide && !parked && nextStripShown(ui)) 330.dp else 214.dp)
        }
    }
}

// ---------------------------------------------------------------- 맨 위 막대

@Composable
private fun TopBarArea(
    ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine, parked: Boolean,
    partial: String, level: Double, compact: Boolean, onDemo: () -> Unit,
) {
    // 듣는 동안과 알아들은 직후에는 이 자리에 듣기 표시를 겹쳐 띄움 (아래 카드는 밀리지 않음)
    Box {
        TopBar(ui, group, engine, parked, compact, onDemo)
        Hearing(ui, partial, level, Modifier.matchParentSize())
    }
}

@Composable
private fun TopBar(ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine, parked: Boolean, compact: Boolean, onDemo: () -> Unit) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    // 절반 창에서는 앱 표시를 빼고 단추 안쪽 여백을 줄여 여행 이름이 잘리지 않게
    val pad = if (compact) 20.dp else 26.dp
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!compact) {
            AppMark(64.dp)
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(sc.who.pick(lang), style = T.label, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sc.title.pick(lang), style = T.titleM, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 그룹 번호와 휴대폰 수는 QR 카드에서 보여 줌. 맨 위에는 운전자가 알아야 할 것만
        if (group.cars.size > 1) Tag(t(lang, "차 ${group.cars.size}대", "${group.cars.size} cars"), Ic.Car)
        if (ui.demoMode) Btn(t(lang, "시연", "Demo"), icon = Ic.Sliders, tone = Tone.Quiet, height = 76.dp, style = T.label, iconSize = 28.dp, hPad = pad, onClick = onDemo)
        // 여행을 끝내는 단추는 차가 섰을 때만. 달리는 동안은 "여행 끝내 줘"라고 말하면 됨
        if (parked && ui.phase == TripEngine.Phase.DRIVING) Btn(t(lang, "여행 끝", "End trip"), icon = Ic.Flag, height = 76.dp, style = T.label, iconSize = 28.dp, hPad = pad) { engine.requestEndTrip() }
    }
}

/** 듣는 중이면 소리 크기 막대와 들리는 글자, 다 들으면 알아들은 문장을 맨 위 막대 자리에 잠깐 띄움 */
@Composable
private fun Hearing(ui: TripEngine.Ui, partial: String, level: Double, modifier: Modifier) {
    var showHeard by remember { mutableStateOf(false) }
    LaunchedEffect(ui.heardSeq) {
        if (ui.heardSeq > 0) {
            showHeard = true
            delay(4000)
            showHeard = false
        }
    }
    // 차가 물은 것에 답을 듣는 중이면 물음 아래 안내 한 곳에서만 보여 줌 (한눈에 볼 것을 하나로)
    val visible = (ui.listening && !ui.asking) || (showHeard && ui.heardText != null)
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        val shape = RoundedCornerShape(28.dp)
        Row(
            Modifier.fillMaxSize().clip(shape).background(Color(0xFF0A1C38)).border(2.dp, C.accent.copy(alpha = 0.7f), shape).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (ui.listening) LevelBars(level, Modifier.size(64.dp, 44.dp)) else Icon(Ic.Mic, C.accent, 36.dp)
            Text(if (ui.listening) t(ui.lang, "듣고 있어요", "Listening") else t(ui.lang, "이렇게 들었어요", "I heard"), style = T.label, color = C.accent, maxLines = 1)
            val hint = t(ui.lang, "창밖에 보이는 걸 물어보세요", "Ask about anything outside")
            val shown = if (ui.listening) partial.ifBlank { hint } else "“${ui.heardText ?: ""}”"
            Text(shown, style = T.bodyL, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** 소리 크기 막대 다섯 개. 음성 인식 엔진이 알려 주는 소리 크기에 맞춰 움직임 (듣는 동안에만) */
@Composable
private fun LevelBars(level: Double, modifier: Modifier) {
    val norm = ((level + 2.0) / 12.0).coerceIn(0.0, 1.0).toFloat()
    val amp by animateFloatAsState(maxOf(norm, 0.12f), tween(120), label = "level")
    val wave = rememberInfiniteTransition(label = "bars")
    val phase by wave.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val n = 5
        val bw = size.width / (n * 2f - 1f)
        for (i in 0 until n) {
            val wobble = 0.4f + 0.6f * ((kotlin.math.sin(phase + i * 1.3f) + 1f) / 2f)
            val bh = size.height * (0.14f + 0.86f * amp * wobble)
            drawRoundRect(C.accent, topLeft = Offset(i * bw * 2f, (size.height - bh) / 2f), size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2f))
        }
    }
}

// ---------------------------------------------------------------- 달리는 동안: 이야기 카드

@Composable
private fun NowCard(ui: TripEngine.Ui, engine: TripEngine, modifier: Modifier, compact: Boolean) {
    val lang = ui.lang
    // 달리는 동안의 물음(고른 장소, 쉬어 가기, 충전, 여행 끝)은 이 카드 안에서 답함
    val docked = ui.confirmEnd || ui.offer != null || ui.pick != null
    // 들려주는 것이 없는 동안에는 다음 명소가 주인공 (끝난 이야기 제목을 붙들고 있지 않게)
    val next = ui.nextPlace
    val quiet = isQuiet(ui)
    val offer = ui.offer
    val pick = ui.pick
    val title = when {
        // 물음이 떠 있으면 무엇을 묻는지가 제목 (물음 순서는 아래 DockCenter 와 같음)
        ui.confirmEnd -> t(lang, "오늘 여행을 끝낼까요?", "End today's trip?")
        offer != null -> offer.title
        pick != null -> pick.name
        quiet && next != null -> next.name.pick(lang)
        quiet -> t(lang, "명소를 모두 지났어요", "Every stop is behind us")
        else -> ui.nowTitle ?: t(lang, "출발 준비 중", "Getting ready")
    }
    BrandCard(modifier.fillMaxWidth(), padding = if (compact) 32.dp else 40.dp) {
        when {
            ui.confirmEnd -> Eyebrow(t(lang, "여행 끝", "End trip"), Ic.Flag, C.onBrand2)
            offer != null -> Eyebrow(t(lang, "차가 먼저 권해요", "The car suggests"), offerIcon(offer), C.onBrand2)
            pick != null -> Eyebrow(pickLabel(pick, lang), pickIcon(pick), C.onBrand2)
            quiet -> Text(if (next != null) t(lang, "다음 명소", "Next stop") else t(lang, "오늘 코스", "Today's course"), style = T.label, color = C.onBrand2, maxLines = 1)
            else -> KindRow(ui, title)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = if (compact) T.headM else T.headL, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                docked -> DockCenter(ui, compact)
                quiet -> QuietCenter(ui, compact)
                else -> DrivingCenter(ui, compact)
            }
        }
        if (docked) {
            // 답하는 단추는 평소 조작 단추가 있던 자리에 (손이 가는 곳을 바꾸지 않게)
            DockActions(ui, engine, compact)
        } else {
            val hold = ui.holds.firstOrNull { it != TripEngine.HOLD_LISTEN }
            if (hold != null) {
                HoldBanner(holdMessage(hold, lang))
                Spacer(Modifier.height(18.dp))
            }
            // 한 문장짜리 안내에는 진행 막대를 그리지 않음
            if (ui.sentenceCount > 1) {
                Segments(ui.sentenceCount, ui.sentenceIndex, Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
            }
            ControlBar(ui, engine)
        }
    }
}

/** 들려주는 것도, 답을 기다리는 물음도 없이 달리는 중인지 */
private fun isQuiet(ui: TripEngine.Ui) =
    !(ui.confirmEnd || ui.offer != null || ui.pick != null) && ui.sentenceCount == 0 && ui.phase == TripEngine.Phase.DRIVING

/** 절반 창에서 달릴 때 카드 아래 '다음 명소' 줄이 보이는지 (조용할 때는 카드가 이미 다음 명소를 크게 보여 줌) */
private fun nextStripShown(ui: TripEngine.Ui) = ui.nextPlace != null && !isQuiet(ui)

/** 카드 맨 위: 지금 무엇을 하는 중인지, 이야기 길이. 아래 큰 제목과 같은 말이면 그곳 이름을 대신 씀 */
@Composable
private fun KindRow(ui: TripEngine.Ui, title: String?) {
    val lang = ui.lang
    // 다 들려준 뒤에는 "지금 들려주는 이야기"가 아니라 "방금 들은 이야기"
    val idle = ui.sentenceCount == 0
    val kind = if (idle) idleLabel(ui.nowKind, lang) else kindLabel(ui.nowKind, lang)
    val label = if (kind == title) ui.nowPlace?.name?.pick(lang)?.takeIf { it != title } else kind
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (label != null) Text(label, style = T.label, color = C.onBrand2, maxLines = 1)
        // 이야기 길이는 다음 길 안내까지 조용히 들을 수 있는 시간에 맞춰 고름
        val len = ui.nowLength
        if (len != null && ui.nowKind == StoryPlayer.Kind.STORY && !idle) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(C.onBrandRaised).padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(lengthLabel(len, lang), style = T.label, color = Color.White, maxLines = 1)
            }
        }
    }
}

/**
 * 이야기 카드 가운데: 그림과 글. 넓은 창은 그림 왼쪽, 글 오른쪽.
 * 절반 창(세로)은 키가 커서 그림을 위에 크게 두고 글을 그 아래 가운데에 (한눈에 들어오게)
 */
@Composable
private fun CenterLayout(compact: Boolean, visual: @Composable () -> Unit, text: @Composable (TextAlign) -> Unit) {
    if (compact) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            visual()
            Spacer(Modifier.height(30.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) { text(TextAlign.Center) }
        }
    } else {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            visual()
            Column { text(TextAlign.Start) }
        }
    }
}

/** 둥근 판 위 아이콘 (카드 가운데 그림) */
@Composable
private fun Medallion(icon: Ic, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(C.onBrandRaised), contentAlignment = Alignment.Center) {
        Icon(icon, Color.White, size * 0.46f)
    }
}

@Composable
private fun DrivingCenter(ui: TripEngine.Ui, compact: Boolean) {
    val lang = ui.lang
    val side = ui.nowSide
    val showSide = side != null && ui.nowKind in setOf(StoryPlayer.Kind.ANNOUNCE, StoryPlayer.Kind.STORY, StoryPlayer.Kind.KID, StoryPlayer.Kind.ANSWER)
    if (showSide) {
        CenterLayout(compact, { SideIndicator(side, 250.dp) }) { align ->
            Text(sideLabel(side, ui.nowKind, lang), style = T.headM, color = Color.White, maxLines = 2, textAlign = align)
            Spacer(Modifier.height(6.dp))
            Text(t(lang, "창밖을 보면 돼요", "Take a look outside"), style = T.bodyL, color = C.onBrand2, textAlign = align)
        }
    } else {
        CenterLayout(compact, { Medallion(Ic.Speaker, if (compact) 170.dp else 200.dp) }) { align ->
            Text(t(lang, "운전 중에는 소리로 안내해요", "While driving, the guide speaks"), style = T.titleL, color = Color.White, maxLines = 2, textAlign = align)
            Spacer(Modifier.height(6.dp))
            Text(t(lang, "글과 영상은 동승자 휴대폰에서 같이 봐요", "Text and video are on passengers' phones"), style = T.bodyM, color = C.onBrand2, maxLines = 2, textAlign = align)
        }
    }
}

/** 들려주는 것이 없을 때: 다음 명소까지 남은 거리. 가까워지면 차가 먼저 말한다는 것만 알려 줌 */
@Composable
private fun QuietCenter(ui: TripEngine.Ui, compact: Boolean) {
    val lang = ui.lang
    val next = ui.nextPlace
    val dist = ui.nextDistanceM
    CenterLayout(compact, { Medallion(if (next != null) Ic.Navigation else Ic.Flag, if (compact) 170.dp else 200.dp) }) { align ->
        if (next != null && dist != null) {
            Row {
                Text(formatDist(dist), style = T.headM, color = Color.White, maxLines = 1, modifier = Modifier.alignByBaseline())
                Text(t(lang, " 남았어요", " to go"), style = T.titleM, color = C.onBrand2, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            if (next != null) t(lang, "가까워지면 차가 먼저 알려 드려요", "The car will speak up as we get close")
            else t(lang, "여행을 마치려면 “여행 끝내 줘”라고 말해 주세요", "To finish, just say “end the trip”"),
            style = T.bodyL, color = if (next != null && dist != null) C.onBrand2 else Color.White, maxLines = 2, textAlign = align,
        )
    }
}

@Composable
private fun HoldBanner(msg: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Ic.Pause, C.onBrand2, 30.dp)
        Text(msg, style = T.bodyM, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 이야기 조작 단추 네 개. 아이콘 위, 라벨 아래 (운전대의 이전, 다음 버튼과 같은 일) */
@Composable
private fun ControlBar(ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    val paused = TripEngine.HOLD_USER in ui.holds
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        CtrlBtn(Ic.Replay, t(lang, "다시 듣기", "Replay"), Modifier.weight(1f)) { engine.onMediaCommand(MediaKeys.Command.PREVIOUS) }
        CtrlBtn(if (paused) Ic.Play else Ic.Pause, if (paused) t(lang, "계속", "Resume") else t(lang, "멈춤", "Pause"), Modifier.weight(1f)) { engine.togglePause() }
        CtrlBtn(Ic.Next, t(lang, "다음 이야기", "Next"), Modifier.weight(1f)) { engine.onMediaCommand(MediaKeys.Command.NEXT) }
        CtrlBtn(Ic.Mic, if (ui.listening && !ui.asking) t(lang, "듣는 중", "Listening") else t(lang, "물어보기", "Ask"), Modifier.weight(1.25f), primary = true) { engine.ask() }
    }
}

@Composable
private fun CtrlBtn(icon: Ic, label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    val fg = if (primary) C.onAccent else Color.White
    Column(
        modifier.height(120.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(26.dp))
            .background(if (primary) C.accent else C.onBrandRaised)
            .drawWithContent { drawContent(); if (pressed) drawRect(Color.White.copy(alpha = 0.12f)) }
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, fg, 40.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, style = T.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 달리는 동안의 물음 (화면을 덮지 않고 이야기 카드 안에서 답함). 가운데에는 판단에 필요한 것만:
 * 고른 장소는 종류와 지금 위치에서의 거리, 권하는 것은 까닭, 여행 끝은 끝나면 무엇이 되는지
 */
@Composable
private fun DockCenter(ui: TripEngine.Ui, compact: Boolean) {
    val lang = ui.lang
    val offer = ui.offer
    val pick = ui.pick
    val icon: Ic
    val main: String
    val sub: String?
    when {
        ui.confirmEnd -> {
            icon = Ic.Flag
            main = t(lang, "끝내면 하루 기록을 보여 드려요", "We'll show today's record")
            sub = t(lang, "앱이 바꾼 조명과 공조는 원래대로 돌려 둬요", "Lights and climate go back to how they were")
        }
        offer != null -> {
            icon = offerIcon(offer)
            main = offer.body
            sub = null
        }
        pick != null -> {
            icon = pickIcon(pick)
            val word = when (pick.kind.lowercase()) {
                "cafe" -> t(lang, "카페", "Café")
                "parking" -> t(lang, "주차장", "Parking")
                "landmark" -> t(lang, "볼거리", "Sight")
                else -> t(lang, "고른 곳", "Picked place")
            }
            val d = ui.carPos?.let { Geo.distanceM(it, pick.pos) }
            main = if (d != null) t(lang, "${word}, 여기서 ${formatDist(d)}", "${word}, ${formatDist(d)} away") else word
            sub = t(lang, "가는 길에 들르거나 목적지로 바꿀 수 있어요", "Stop on the way or make it the destination")
        }
        else -> return
    }
    CenterLayout(compact, { Medallion(icon, if (compact) 140.dp else 200.dp) }) { align ->
        Text(main, style = T.titleL, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = align)
        if (sub != null) {
            Spacer(Modifier.height(8.dp))
            Text(sub, style = T.bodyL, color = C.onBrand2, maxLines = 2, textAlign = align)
        }
    }
}

/** 물음에 답하는 큰 단추와, 말로 답해도 된다는 안내 */
@Composable
private fun DockActions(ui: TripEngine.Ui, engine: TripEngine, compact: Boolean) {
    val lang = ui.lang
    val offer = ui.offer
    val pick = ui.pick
    when {
        ui.confirmEnd -> AnswerButtons(
            Answer(t(lang, "끝내고 기록 보기", "End and see the record"), Ic.Flag) { engine.endTrip() },
            listOf(Answer(t(lang, "계속 가기", "Keep going")) { engine.cancelEndTrip() }),
            onBrand = true,
        )
        offer != null -> AnswerButtons(
            Answer(offer.yes, offerIcon(offer)) { engine.answerOffer(true) },
            listOf(Answer(offer.no) { engine.answerOffer(false) }),
            onBrand = true,
        )
        pick != null -> PickButtons(pick, lang, engine, onBrand = true)
    }
    Spacer(Modifier.height(18.dp))
    VoiceHint(ui, onBrand = true)
}

/** 답 단추 하나 (글, 아이콘, 넓은 줄에서 차지하는 몫) */
private class Answer(val text: String, val icon: Ic? = null, val weight: Float = 1f, val onClick: () -> Unit)

/**
 * 물음에 답하는 단추 묶음. 줄이 넉넉하면 한 줄에 나란히,
 * 좁으면(절반 창) 주 단추를 위에 한 줄로 크게 두고 나머지는 그 아래에 나란히 둠 (글자가 잘리지 않게)
 */
@Composable
private fun AnswerButtons(primary: Answer, others: List<Answer>, onBrand: Boolean, style: androidx.compose.ui.text.TextStyle = T.button) {
    val other = if (onBrand) Tone.OnBrand else Tone.Secondary
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Btn(primary.text, Modifier.weight(primary.weight), icon = primary.icon, tone = Tone.Primary, height = 104.dp, style = style, onClick = primary.onClick)
                others.forEach { a -> Btn(a.text, Modifier.weight(a.weight), icon = a.icon, tone = other, height = 104.dp, style = style, onClick = a.onClick) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Btn(primary.text, Modifier.fillMaxWidth(), icon = primary.icon, tone = Tone.Primary, height = 104.dp, style = style, onClick = primary.onClick)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    others.forEach { a -> Btn(a.text, Modifier.weight(1f), icon = a.icon, tone = other, height = 92.dp, style = style, onClick = a.onClick) }
                }
            }
        }
    }
}

private fun pickIcon(p: TripEngine.Pick): Ic = when (p.kind.lowercase()) {
    "parking" -> Ic.Parking
    "cafe" -> Ic.Cup
    else -> Ic.Pin
}

/** 고른 장소 단추. 카페, 볼거리는 '가는 길에 들르기'가, 주차장은 '그리로 안내'가 먼저 ("응"이라고 하면 이것) */
@Composable
private fun PickButtons(pick: TripEngine.Pick, lang: String, engine: TripEngine, onBrand: Boolean, style: androidx.compose.ui.text.TextStyle = T.button) {
    val parking = pick.kind.equals("parking", ignoreCase = true)
    val later = Answer(t(lang, "나중에", "Later"), weight = 0.8f) { engine.answerPick("later") }
    if (parking) {
        AnswerButtons(
            Answer(t(lang, "그리로 안내", "Take us there"), Ic.Navigation, 1.3f) { engine.answerPick("destination") },
            listOf(Answer(t(lang, "경유지로", "Add a stop")) { engine.answerPick("waypoint") }, later),
            onBrand, style,
        )
    } else {
        AnswerButtons(
            Answer(t(lang, "가는 길에 들르기", "Stop on the way"), Ic.Pin, 1.3f) { engine.answerPick("waypoint") },
            listOf(Answer(t(lang, "목적지로 바꾸기", "Go there instead")) { engine.answerPick("destination") }, later),
            onBrand, style,
        )
    }
}

/** 물음 아래: 말로도 답할 수 있다는 안내. 차가 듣는 중이면 그렇다고 바꿔 보여 줌 */
@Composable
private fun VoiceHint(ui: TripEngine.Ui, onBrand: Boolean) {
    val lang = ui.lang
    val listening = ui.asking && ui.listening
    val words = answerWords(ui)
    val quiet = if (onBrand) C.onBrand2 else C.text3
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Ic.Mic, if (listening) C.accent else quiet, 30.dp)
        Text(
            if (listening) t(lang, "듣고 있어요. ${words} 하고 말해 주세요", "Listening. Say ${words}") else t(lang, "말로 답해도 돼요: ${words}", "You can just say ${words}"),
            style = if (listening) T.bodyM.copy(fontWeight = FontWeight.SemiBold) else T.bodyM, color = if (listening) C.accent else quiet,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 달리는 동안: 오른쪽 칸

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DrivingSide(ui: TripEngine.Ui) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    val next = ui.nextPlace
    Card(Modifier.fillMaxWidth().weight(1f), padding = 28.dp) {
        if (isQuiet(ui)) {
            // 다음 명소는 왼쪽 큰 카드에 있으니 여기서는 오늘 코스가 어디까지 왔는지
            val stops = sc.places.filter { it.triggers && it.kind != PlaceKind.CITY }
            Eyebrow(t(lang, "오늘 코스", "Today's course"), Ic.Route)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t(lang, "지난 명소", "Stops passed"), style = T.titleL, color = C.text, maxLines = 1, modifier = Modifier.weight(1f))
                Text("${stops.count { it.id in ui.entered }}/${stops.size}", style = T.titleL, color = C.accent)
            }
        } else if (next != null) {
            Eyebrow(t(lang, "다음 명소", "Next stop"), Ic.Pin)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(next.name.pick(lang), style = T.titleL, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ui.nextDistanceM?.let { Text(formatDist(it), style = T.titleL, color = C.accent) }
            }
        } else {
            Eyebrow(t(lang, "오늘 코스", "Today's course"), Ic.Route)
            Spacer(Modifier.height(10.dp))
            Text(t(lang, "오늘 코스의 명소를 다 지났어요", "We've passed every stop today"), style = T.titleM, color = C.text)
        }
        Spacer(Modifier.height(18.dp))
        RouteMap(
            route = sc.route, places = sc.places.filter { it.triggers }, entered = ui.entered, car = ui.carPos, heading = ui.heading,
            modifier = Modifier.fillMaxWidth().weight(1f), nextId = next?.id,
        )
    }
    val adapt = adaptTags(ui)
    if (adapt.isNotEmpty()) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Eyebrow(t(lang, "지금 차에 맞춘 것", "Adapting to the car"), Ic.Car)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                adapt.forEach { (ic, text) -> Tag(text, ic, bg = C.accentSoft, fg = C.text) }
            }
        }
    }
    if (ui.starsNeeded > 0) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Gift, C.sand, 36.dp)
                Spacer(Modifier.width(16.dp))
                Text(t(lang, "보물 상자", "Treasure box"), style = T.bodyL.copy(fontWeight = FontWeight.SemiBold), color = C.text, modifier = Modifier.weight(1f))
                Stars(ui.stars, ui.starsNeeded)
            }
        }
    }
    if (ui.laterPicks.isNotEmpty()) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Ic.Bookmark, C.accent, 34.dp)
                Column {
                    Text(t(lang, "담아 둔 장소 ${ui.laterPicks.size}곳", "${ui.laterPicks.size} saved places"), style = T.bodyM.copy(fontWeight = FontWeight.SemiBold), color = C.text)
                    Text(t(lang, "차를 세우면 보여 드려요", "Shown when we stop"), style = T.caption, color = C.text3)
                }
            }
        }
    }
}

/** 좁은 창에서 달릴 때 카드 아래 한 줄: 다음 명소와 남은 거리 */
@Composable
private fun NextStrip(ui: TripEngine.Ui) {
    val lang = ui.lang
    // 조용할 때는 위 카드가 이미 다음 명소를 크게 보여 줌
    if (!nextStripShown(ui)) return
    val next = ui.nextPlace ?: return
    Card(Modifier.fillMaxWidth(), padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Ic.Pin, C.accent, 32.dp)
            Text(t(lang, "다음", "Next"), style = T.label, color = C.text3)
            Text(next.name.pick(lang), style = T.titleM, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ui.nextDistanceM?.let { Text(formatDist(it), style = T.titleM, color = C.accent) }
        }
    }
}

// ---------------------------------------------------------------- 차가 섰을 때

@Composable
private fun ParkedHero(ui: TripEngine.Ui, engine: TripEngine, modifier: Modifier, compact: Boolean = false) {
    val lang = ui.lang
    val place = ui.nowPlace
    // 지금 명소 장면이 없으면(출발 안내 등) 오늘 여행지 표지 장면을 씀. 글만 있는 빈 카드가 되지 않게
    val cover = ui.scenario?.let { coverOf(it) }
    val model = place?.model ?: cover?.first
    val night = if (place?.model != null) ui.night else (ui.night || cover?.second == true)
    BrandCard(modifier.fillMaxWidth(), padding = 0.dp) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model != null) PlaceClip(model, night, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0x4D000000), 0.28f to Color.Transparent, 0.58f to Color(0x8C001A3A), 1f to Color(0xF5001A3A)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(if (compact) 32.dp else 40.dp)) {
                val title = ui.nowTitle ?: place?.name?.pick(lang) ?: t(lang, "출발 준비 중", "Getting ready")
                KindRow(ui, title)
                Spacer(Modifier.height(6.dp))
                Text(title, style = if (compact) T.headM else T.headL, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 지금 읽는 문장. 들려주는 것이 없으면 다음에 무엇이 오는지 한 줄
                val line = ui.nowSentence ?: if (ui.sentenceCount == 0) {
                    ui.nextPlace?.let { n -> t(lang, "다음은 ${n.name.pick(lang)}. 가까워지면 차가 먼저 알려 드려요", "Next up: ${n.name.pick(lang)}. The car will speak up as we get close") }
                } else null
                if (!line.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(line, style = if (compact) T.bodyM else T.bodyL, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.padding(if (compact) 24.dp else 28.dp)) {
            if (ui.sentenceCount > 1) {
                Segments(ui.sentenceCount, ui.sentenceIndex, Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
            }
            ControlBar(ui, engine)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParkedSide(ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine) {
    val lang = ui.lang
    val sc = ui.scenario ?: return

    // 함께 보기 QR: 차가 서 있을 때 가장 먼저
    Card(Modifier.fillMaxWidth(), padding = 28.dp) {
        val joinUrl = group.joinUrl
        if (joinUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(joinUrl, 220.dp)
                Column(Modifier.weight(1f)) {
                    Eyebrow(t(lang, "휴대폰으로 같이 보기", "On passengers' phones"), Ic.Phone, C.accent)
                    Spacer(Modifier.height(8.dp))
                    Text(t(lang, "함께 탄 사람은 QR을 찍어 주세요", "Passengers, scan to join"), style = T.titleM, color = C.text)
                    Spacer(Modifier.height(8.dp))
                    Text(t(lang, "앱을 깔지 않아도 영상과 3D 모형, 퀴즈를 같이 봐요", "No app needed. Videos, 3D models and quizzes show up on your phone"), style = T.bodyS, color = C.text2, maxLines = 3)
                    ui.groupCode?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(t(lang, "그룹 번호 ${it}", "Group code ${it}"), style = T.titleM, color = C.accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    PhonesLine(group, lang)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Ic.Qr, C.text3, 34.dp)
                Text(t(lang, "그룹 서버에 연결되면 QR이 떠요. 차 안 안내는 그대로 돼요.", "The QR appears once the group server is reachable. The in-car guide still works."), style = T.bodyS, color = C.text2)
            }
        }
    }

    // 달리는 동안 '나중에'로 담아 둔 장소
    if (ui.laterPicks.isNotEmpty()) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Eyebrow(t(lang, "담아 둔 장소", "Saved for later"), Ic.Bookmark, C.accent)
            ui.laterPicks.forEach { p ->
                Spacer(Modifier.height(18.dp))
                Text(p.name, style = T.bodyL.copy(fontWeight = FontWeight.SemiBold), color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pickLabel(p, lang), style = T.caption, color = C.text3)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn(t(lang, "들르기", "Stop there"), Modifier.weight(1f), icon = Ic.Pin, height = 80.dp, style = T.label, iconSize = 28.dp) { engine.routeLaterPick(p.id, "waypoint") }
                    Btn(t(lang, "목적지로", "Go there"), Modifier.weight(1f), icon = Ic.Navigation, height = 80.dp, style = T.label, iconSize = 28.dp) { engine.routeLaterPick(p.id, "destination") }
                    IconBtn(Ic.Close, size = 80.dp) { engine.routeLaterPick(p.id, "drop") }
                }
            }
        }
    }

    if (ui.starsNeeded > 0) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Gift, C.sand, 36.dp)
                Spacer(Modifier.width(16.dp))
                Text(t(lang, "보물 상자", "Treasure box"), style = T.titleM, color = C.text, modifier = Modifier.weight(1f))
                Stars(ui.stars, ui.starsNeeded, 38.dp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when (ui.treasure) {
                    TripEngine.Treasure.READY -> t(lang, "별을 다 모았어요! 차를 세우면 보물 상자를 열어 드려요.", "All stars collected! Park to open the treasure box.")
                    TripEngine.Treasure.OPEN -> t(lang, "보물 상자를 열었어요. 달리기 시작하면 다시 잠가요.", "The treasure box is open. It locks again when we drive off.")
                    else -> t(lang, "휴대폰 퀴즈를 맞히면 별이 모여요. 별 ${ui.starsNeeded}개면 조수석 앞 글로브 박스가 열려요.", "Answer phone quizzes to collect stars. ${ui.starsNeeded} stars open the glove box.")
                },
                style = T.bodyS, color = if (ui.treasure == TripEngine.Treasure.READY) C.sand else C.text2,
            )
        }
    }

    val askedText = ui.askedText
    if (ui.asked > 0 && askedText != null) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Eyebrow(t(lang, "방금 물어본 것", "You just asked"), Ic.Mic)
            Spacer(Modifier.height(10.dp))
            Text("“${askedText}”", style = T.bodyL.copy(fontWeight = FontWeight.SemiBold), color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ui.answerTitle?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = T.label, color = C.accent)
            }
            ui.answerText?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = T.bodyS, color = C.text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    Card(Modifier.fillMaxWidth(), padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(t(lang, "오늘 코스", "Today's course"), Ic.Route)
            Spacer(Modifier.weight(1f))
            ui.nextPlace?.let { p ->
                Text(t(lang, "다음 ${p.name.pick(lang)}", "Next ${p.name.pick(lang)}") + (ui.nextDistanceM?.let { "  " + formatDist(it) } ?: ""), style = T.label, color = C.text2)
            }
        }
        Spacer(Modifier.height(14.dp))
        RouteMap(
            route = sc.route, places = sc.places.filter { it.triggers }, entered = ui.entered, car = ui.carPos, heading = ui.heading,
            modifier = Modifier.fillMaxWidth().height(280.dp), nextId = ui.nextPlace?.id,
        )
        Spacer(Modifier.height(8.dp))
        sc.places.filter { it.triggers && it.kind != PlaceKind.CITY }.forEach { p ->
            val done = p.id in ui.entered
            val now = ui.nowPlace?.id == p.id
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(when { now -> C.accent; done -> C.accent.copy(alpha = 0.45f); else -> C.raised2 }))
                Text(p.name.pick(lang), style = T.bodyM, color = if (done || now) C.text else C.text3, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        now -> t(lang, "지금", "Now")
                        ui.heard[p.id] != null -> t(lang, "들음", "Heard")
                        done -> t(lang, "지남", "Passed")
                        else -> ""
                    },
                    style = T.label, color = if (now) C.accent else C.text3,
                )
            }
        }
    }

    // 다른 차에 장소 보내기 (먼저 도착한 차가 주차장을 알려 주는 식)
    val shareables = sc.pickables.filter { it.kind == PlaceKind.PARKING || it.kind == PlaceKind.CAFE }
    if (shareables.isNotEmpty() && (group.cars.size > 1 || sc.cars > 1)) {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Eyebrow(t(lang, "다른 차에 보내기", "Send to the other cars"), Ic.Send)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shareables.forEach { p ->
                    Btn(p.name.pick(lang), icon = if (p.kind == PlaceKind.PARKING) Ic.Parking else Ic.Cup, height = 80.dp, style = T.label, iconSize = 28.dp) { engine.sharePlace(p) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(t(lang, "달리는 중에는 \"다른 차에 주차장 보내 줘\"라고 말하면 돼요", "While driving, just say \"send the parking to the other car\""), style = T.caption, color = C.text3)
        }
    }

    Card(Modifier.fillMaxWidth(), padding = 24.dp) {
        SwitchRow(
            Ic.Kid, t(lang, "뒷자리 아이 모드", "Kid mode"),
            t(lang, "명소 이야기 끝에 아이 눈높이 이야기와 휴대폰 퀴즈", "A short kids' story and a phone quiz after each place"),
            ui.kidMode,
        ) { engine.toggleKidMode() }
        if (ui.windowOpened) {
            Spacer(Modifier.height(14.dp))
            Btn(t(lang, "앞 창문 닫기", "Close front windows"), Modifier.fillMaxWidth(), icon = Ic.Window, height = 88.dp) { engine.closeWindows() }
        }
    }
}

// ---------------------------------------------------------------- 출발 전 좌석 점호

/** 연결된 휴대폰 수. 동승자가 QR을 찍으면 바로 늘어남 */
@Composable
private fun PhonesLine(group: CompanionClient.State, lang: String, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(if (group.phones > 0) true else null)
        Text(
            if (group.phones > 0) t(lang, "휴대폰 ${group.phones}대 연결됨", if (group.phones == 1) "1 phone connected" else "${group.phones} phones connected")
            else t(lang, "아직 연결된 휴대폰이 없어요", "No phones connected yet"),
            style = T.label, color = C.text2, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
private fun RollCall(ui: TripEngine.Ui, vehicle: VehicleBridge.State, group: CompanionClient.State, parked: Boolean, engine: TripEngine) {
    val lang = ui.lang
    val sc = ui.scenario
    val joinUrl = group.joinUrl
    Box(Modifier.fillMaxSize().background(C.scrim).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth > 1400.dp
            // 출발 전(차가 서 있을 때)이 동승자가 QR을 찍기 가장 좋은 때라 점호 창 옆에 QR을 같이 띄움
            val showQr = wide && parked && joinUrl != null
            val shape = RoundedCornerShape(40.dp)
            val inner: @Composable () -> Unit = {
                Column(if (wide) Modifier.width(600.dp) else Modifier.fillMaxWidth()) {
                    Eyebrow(t(lang, "출발 전 좌석 점호", "Seat roll call"), Ic.Users, C.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(t(lang, "모두 안전벨트를 매면 출발 안내를 시작해요", "Buckle up and the trip begins"), style = T.titleL, color = C.text)
                    val places = sc?.landmarks?.filter { it.triggers }?.joinToString(", ") { it.name.pick(lang) }.orEmpty()
                    if (places.isNotBlank()) {
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Ic.Pin, C.accent, 30.dp)
                            Text(t(lang, "오늘 지나갈 곳  ${places}", "Today's stops  ${places}"), style = T.bodyS, color = C.text2)
                        }
                    }
                    // 보물 상자 놀이는 부모가 미리 준비해야 하므로 출발 전에 알림
                    if (ui.starsNeeded > 0) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Ic.Gift, C.sand, 30.dp)
                            Text(t(lang, "보물 상자 놀이: 출발 전에 조수석 앞 글로브 박스에 작은 선물을 넣어 두세요", "Treasure game: put a small gift in the glove box before we go"), style = T.bodyS, color = C.sand)
                        }
                    }
                    Spacer(Modifier.height(36.dp))
                    Btn(t(lang, "모두 맸어요, 출발", "All buckled, let's go"), Modifier.fillMaxWidth(), icon = Ic.Check, tone = Tone.Primary, height = 112.dp, style = T.titleM, iconSize = 40.dp) { engine.completeRollcall() }
                    Spacer(Modifier.height(12.dp))
                    Text(t(lang, "차량 안전벨트 신호가 오면 저절로 넘어가요", "Moves on by itself when the car reports all belts buckled"), style = T.caption, color = C.text3)
                }
            }
            if (wide) {
                Row(
                    Modifier.clip(shape).background(Color(0xFF1B1F26)).padding(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeatMap(vehicle, lang)
                    inner()
                    if (showQr) {
                        Column(Modifier.width(340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            QrCode(joinUrl, 300.dp)
                            Spacer(Modifier.height(18.dp))
                            Text(t(lang, "함께 탄 사람은 QR을 찍어 주세요", "Passengers, scan to join"), style = T.bodyM.copy(fontWeight = FontWeight.SemiBold), color = C.text, textAlign = TextAlign.Center)
                            ui.groupCode?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(t(lang, "그룹 번호 ${it}", "Group code ${it}"), style = T.titleM, color = C.accent)
                            }
                            Text(t(lang, "앱 설치 없이 영상과 퀴즈를 같이 봐요", "No app needed"), style = T.caption, color = C.text3, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(14.dp))
                            PhonesLine(group, lang)
                        }
                    }
                }
            } else {
                // 절반 창: 점호 글과 단추 아래 좌석 그림, 그 옆에 QR (출발 전이 동승자가 QR을 찍기 가장 좋은 때)
                val qr = if (parked) joinUrl else null
                Column(
                    Modifier.clip(shape).background(Color(0xFF1B1F26)).verticalScroll(rememberScrollState()).padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    inner()
                    Spacer(Modifier.height(28.dp))
                    if (qr != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            SeatMap(vehicle, lang)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                QrCode(qr, 200.dp)
                                Spacer(Modifier.height(14.dp))
                                Text(t(lang, "함께 탄 사람은 QR을 찍어 주세요", "Passengers, scan to join"), style = T.bodyS.copy(fontWeight = FontWeight.SemiBold, lineBreak = T.balanced), color = C.text, textAlign = TextAlign.Center)
                                ui.groupCode?.let {
                                    Spacer(Modifier.height(12.dp))
                                    Text(t(lang, "그룹 번호", "Group code"), style = T.caption, color = C.text3)
                                    Text(it, style = T.titleL, color = C.accent)
                                }
                                Spacer(Modifier.height(12.dp))
                                PhonesLine(group, lang, compact = true)
                            }
                        }
                    } else {
                        SeatMap(vehicle, lang)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 차가 섰을 때의 물음 창

@Composable
private fun PickDialog(p: TripEngine.Pick, ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    PopupFrame(pickIcon(p), width = 1100.dp) {
        Text(pickLabel(p, lang), style = T.label, color = C.accent)
        Spacer(Modifier.height(8.dp))
        Text(p.name, style = T.titleL, color = C.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        PickButtons(p, lang, engine, onBrand = false)
        Spacer(Modifier.height(22.dp))
        VoiceHint(ui, onBrand = false)
    }
}

@Composable
private fun WindowDialog(name: String, ambience: String?, ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    val sound = if (ambience == "waves") t(lang, "파도 소리를", "the waves") else t(lang, "바깥 소리를", "the sounds outside")
    PopupFrame(Ic.Window) {
        Text(name, style = T.label, color = C.accent)
        Spacer(Modifier.height(8.dp))
        Text(t(lang, "앞 창문을 조금 내려 ${sound} 들여 볼까요?", "Lower the front windows a little to hear ${sound}?"), style = T.titleL, color = C.text, textAlign = TextAlign.Center)
        Text(t(lang, "뒷창문은 그대로 두고, 차가 서 있을 때만 움직여요", "Rear windows stay shut. Windows move only while parked"), style = T.bodyS, color = C.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(32.dp))
        AnswerButtons(
            Answer(t(lang, "창문 내리기", "Lower windows"), Ic.Window) { engine.answerWindow(true) },
            listOf(Answer(t(lang, "괜찮아요", "No thanks")) { engine.answerWindow(false) }),
            onBrand = false,
        )
        Spacer(Modifier.height(22.dp))
        VoiceHint(ui, onBrand = false)
    }
}

@Composable
private fun OfferDialog(o: TripEngine.Offer, ui: TripEngine.Ui, engine: TripEngine) {
    val tint = if (o.kind == TripEngine.OfferKind.TREASURE) C.sand else C.accent
    PopupFrame(offerIcon(o), tint) {
        Text(o.title, style = T.label, color = tint)
        Spacer(Modifier.height(8.dp))
        Text(o.body, style = T.titleL, color = C.text, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(32.dp))
        AnswerButtons(
            Answer(o.yes, offerIcon(o)) { engine.answerOffer(true) },
            listOf(Answer(o.no) { engine.answerOffer(false) }),
            onBrand = false,
        )
        Spacer(Modifier.height(22.dp))
        VoiceHint(ui, onBrand = false)
    }
}

@Composable
private fun EndDialog(ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    PopupFrame(Ic.Flag) {
        Text(t(lang, "오늘 여행을 끝낼까요?", "End today's trip?"), style = T.titleL, color = C.text, textAlign = TextAlign.Center)
        Text(
            t(lang, "끝내면 하루 기록 카드를 보여 드려요. 앱이 바꾼 조명과 공조는 원래대로 돌려 둬요.", "You'll see today's trip card. Lights and climate go back to how they were."),
            style = T.bodyS, color = C.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(32.dp))
        AnswerButtons(
            Answer(t(lang, "끝내고 기록 보기", "End and see the record"), Ic.Flag) { engine.endTrip() },
            listOf(Answer(t(lang, "계속 가기", "Keep going")) { engine.cancelEndTrip() }),
            onBrand = false,
        )
        Spacer(Modifier.height(22.dp))
        VoiceHint(ui, onBrand = false)
    }
}

// ---------------------------------------------------------------- 시연 도구 (에뮬레이터)

/** 에뮬레이터 시연용 단추를 한곳에 모음. 맨 위 [시연]을 누를 때만 뜨고, 실제 위치 모드(실차)에서는 단추가 없음 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoSheet(ui: TripEngine.Ui, vehicle: VehicleBridge.State, engine: TripEngine, onClose: () -> Unit) {
    val lang = ui.lang
    PopupFrame(Ic.Sliders, width = 1300.dp) {
        Text(t(lang, "시연 도구", "Demo tools"), style = T.titleL, color = C.text)
        Text(t(lang, "에뮬레이터 시연용이에요. 실제 차에서는 나오지 않아요.", "For the emulator demo only. Hidden in a real car."), style = T.bodyS, color = C.text2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        val seated = vehicle.seatedKeys.size
        val signals = buildList {
            add(t(lang, "기어 ${vehicle.gear}", "Gear ${vehicle.gear}"))
            add(t(lang, "벨트 ${vehicle.buckledCount}/${seated}", "Belts ${vehicle.buckledCount}/${seated}"))
            val fuelCar = vehicle.powertrain == "ICE" || vehicle.powertrain == "HEV" || (vehicle.socPct == null && vehicle.fuelPct != null)
            if (fuelCar) vehicle.fuelPct?.let { add(t(lang, "기름 ${it}%", "Fuel ${it}%")) } else vehicle.socPct?.let { add(t(lang, "배터리 ${it}%", "Battery ${it}%")) }
            (ui.drivingMin ?: vehicle.drivingMin)?.takeIf { it > 0 }?.let { add(t(lang, "주행 ${Ko.minutes(it, "ko")}", "Driving ${Ko.minutes(it, "en")}")) }
            ui.guideDistanceM?.let { add(t(lang, "다음 길 안내 ${it.toInt()}m", "Next turn ${it.toInt()}m")) }
        }
        Text(signals.joinToString("   "), style = T.caption, color = C.text3)
        if (ui.routeLengthM > 0) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.raised)) {
                Box(Modifier.fillMaxWidth((ui.progressM / ui.routeLengthM).toFloat().coerceIn(0f, 1f)).height(10.dp).background(C.accent))
            }
        }
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn(if (ui.demoRunning) t(lang, "주행 멈춤", "Pause drive") else t(lang, "주행 시작", "Drive"), icon = if (ui.demoRunning) Ic.Pause else Ic.Play, tone = Tone.Primary, height = 84.dp, style = T.label, iconSize = 28.dp) {
                if (ui.demoRunning) engine.demoPause() else engine.demoPlay()
            }
            Btn(t(lang, "빨리 감기 x${ui.demoFast}", "Fast x${ui.demoFast}"), icon = Ic.Next, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.demoFaster() }
            Btn(t(lang, "다음 명소 앞으로", "Skip to next place"), icon = Ic.Pin, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.demoJumpToNext() }
            Btn(if (ui.followGear) t(lang, "기어 따라 주행: 켬", "Follow gear: on") else t(lang, "기어 따라 주행: 끔", "Follow gear: off"), icon = Ic.Car, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.toggleFollowGear() }
            Btn(if (ui.demoJam) t(lang, "막히는 길 흉내: 켬", "Traffic jam: on") else t(lang, "막히는 길 흉내: 끔", "Traffic jam: off"), icon = Ic.Clock, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.setDemoJam(!ui.demoJam) }
        }
        // 마이크 입력이 없는 에뮬레이터에서 말로 묻기를 보여 주는 단추 (앱에 넣어 둔 녹음을 음성 인식 SDK 에 그대로 넣음)
        val qs = engine.demoQuestions[lang] ?: engine.demoQuestions.getValue("ko")
        val nextQ = qs[ui.demoQuestionIdx % qs.size]
        Spacer(Modifier.height(14.dp))
        Btn(t(lang, "녹음한 질문으로 묻기: “${nextQ.text}”", "Ask with a recording: “${nextQ.text}”"), icon = Ic.Mic, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.askDemo() }
        Spacer(Modifier.height(24.dp))
        Btn(t(lang, "닫기", "Close"), Modifier.width(320.dp), tone = Tone.Quiet, height = 84.dp, onClick = onClose)
    }
}

// ---------------------------------------------------------------- 글

private fun kindLabel(kind: StoryPlayer.Kind?, lang: String) = when (kind) {
    StoryPlayer.Kind.STORY -> t(lang, "지금 들려주는 이야기", "Now telling")
    StoryPlayer.Kind.ANNOUNCE -> t(lang, "곧 보여요", "Coming up")
    StoryPlayer.Kind.SYSTEM -> t(lang, "안내", "Guide")
    StoryPlayer.Kind.ANSWER -> t(lang, "물어본 것에 답하는 중", "Answering your question")
    StoryPlayer.Kind.KID -> t(lang, "뒷자리 친구들에게", "For the back seat")
    null -> t(lang, "구르는 가이드", "Rolling Guide")
}

private fun idleLabel(kind: StoryPlayer.Kind?, lang: String) = when (kind) {
    StoryPlayer.Kind.STORY -> t(lang, "방금 들은 이야기", "Just heard")
    StoryPlayer.Kind.ANNOUNCE -> t(lang, "방금 알려 드린 곳", "Just pointed out")
    StoryPlayer.Kind.SYSTEM -> t(lang, "안내", "Guide")
    StoryPlayer.Kind.ANSWER -> t(lang, "방금 답한 것", "Just answered")
    StoryPlayer.Kind.KID -> t(lang, "뒷자리 친구들에게", "For the back seat")
    null -> t(lang, "구르는 가이드", "Rolling Guide")
}

private fun lengthLabel(len: StoryLength, lang: String) = when (len) {
    StoryLength.SHORT -> t(lang, "짧은 이야기", "Short story")
    StoryLength.MEDIUM -> t(lang, "보통 이야기", "Medium story")
    StoryLength.LONG -> t(lang, "긴 이야기", "Long story")
}

private fun holdMessage(hold: String, lang: String) = when (hold) {
    TripEngine.HOLD_TURN -> t(lang, "방향지시등이 켜져 잠시 멈췄어요", "Paused for the turn signal")
    TripEngine.HOLD_GUIDE -> t(lang, "곧 길 안내가 나와서 잠시 쉬어요", "Pausing for navigation guidance")
    TripEngine.HOLD_CAUTION -> t(lang, "단속 카메라, 어린이 보호구역 앞이라 쉬어요", "Pausing near a camera or school zone")
    TripEngine.HOLD_CALL -> t(lang, "전화 중이라 잠시 멈췄어요", "Paused for the phone call")
    TripEngine.HOLD_NAVVOICE -> t(lang, "길 안내 음성이 나와서 잠시 쉬어요", "Pausing for the navigation voice")
    TripEngine.HOLD_USER -> t(lang, "멈춰 뒀어요. 계속을 누르면 이어 가요", "Paused. Tap Resume to continue")
    else -> t(lang, "잠시 멈춤", "Paused")
}

private fun sideLabel(side: String, kind: StoryPlayer.Kind?, lang: String): String {
    val word = when (side) {
        "left" -> t(lang, "왼쪽", "On your left")
        "right" -> t(lang, "오른쪽", "On your right")
        "behind" -> t(lang, "뒤쪽", "Behind us")
        else -> t(lang, "앞쪽", "Straight ahead")
    }
    if (lang == "en") return word
    return when (kind) {
        StoryPlayer.Kind.ANNOUNCE -> word + "에 보여요"
        StoryPlayer.Kind.ANSWER -> word + "으로 보이는 곳"
        else -> word + " 창밖"
    }
}

private fun pickLabel(p: TripEngine.Pick, lang: String) =
    if (p.fromCar) t(lang, "${p.by}에서 보낸 장소", "Sent from ${p.by}") else t(lang, "${p.by} 님이 고른 곳", "${p.by} picked a place")

/** 차가 물은 것에 말로 답할 낱말. 물음 아래 안내와 같은 말을 씀 */
private fun answerWords(ui: TripEngine.Ui): String {
    val lang = ui.lang
    return when {
        ui.pick != null -> t(lang, "‘응’, ‘목적지로’, ‘나중에’", "“yes”, “destination”, “later”")
        ui.confirmEnd -> t(lang, "‘응’ 또는 ‘아니’", "“yes” or “no”")
        else -> t(lang, "‘응’ 또는 ‘괜찮아’", "“yes” or “no thanks”")
    }
}

private fun offerIcon(o: TripEngine.Offer): Ic = when (o.kind) {
    TripEngine.OfferKind.REST -> Ic.Cup
    TripEngine.OfferKind.CHARGE -> if (o.title == "주유" || o.title == "Fuel") Ic.Fuel else Ic.Bolt
    TripEngine.OfferKind.TREASURE -> Ic.Gift
    TripEngine.OfferKind.SUNROOF -> Ic.Sun
}

/** 지금 이야기에 반영 중인 차 상태 (켜진 것만) */
private fun adaptTags(ui: TripEngine.Ui): List<Pair<Ic, String>> {
    val lang = ui.lang
    return buildList {
        if (ui.rain) add(Ic.Rain to t(lang, "비 오는 날 이야기", "Rain stories"))
        if (ui.night) add(Ic.Moon to t(lang, "밤 이야기", "Night stories"))
        if (ui.jam) add(Ic.Clock to t(lang, "막히는 길, 긴 이야기", "Traffic: longer stories"))
        if (ui.kidMode) add(Ic.Kid to t(lang, "뒷자리 아이 이야기", "Kid stories"))
        if (ui.freshAir) add(Ic.Wind to t(lang, "바깥 공기 들이는 중", "Fresh air in"))
        if (ui.windowOpened) add(Ic.Window to t(lang, "앞 창문 조금 열림", "Front windows open"))
    }
}
