package app.gureuneun.guide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 달리는 동안(주행 규제 중이거나 기어가 P가 아닐 때)은 운전자가 1~2초 안에 볼 수 있는 것만 둠:
 * 지금 이야기하는 곳, 어느 쪽 창밖인지, 쉬는 이유, 큰 단추 네 개. 글과 영상, QR은 차가 섰을 때만.
 * 앱이 내비게이션 옆 절반 창(세로)에 들어가도 스크롤 없이 한 화면에 들어가게 짬.
 */
@Composable
fun TripScreen(
    ui: TripEngine.Ui,
    vehicle: VehicleBridge.State,
    ux: UxBridge.Ux,
    navi: NaviBridge.State,
    group: CompanionClient.State,
    engine: TripEngine,
    partial: String = "",
    level: Double = 0.0,
) {
    ui.scenario ?: return
    val accent by animateColorAsState(Color(ui.accent), tween(1200), label = "accent")
    // 차가 서 있고 주행 규제가 없을 때만 영상과 QR, 긴 글을 보여 줌
    val parkedView = vehicle.parked && !ux.restricted

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), G.bg, G.bg))
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp)) {
            val wide = maxWidth > 1100.dp
            when {
                wide -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TopBarArea(ui, group, engine, parkedView, partial, level, accent)
                        NowCard(ui, parkedView, accent, engine, Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (parkedView) ParkedSide(ui, vehicle, group, accent, engine) else DrivingSide(ui, vehicle, accent, engine)
                    }
                }
                // 좁은 창에서 달릴 때: 스크롤 없이 한 화면
                !parkedView -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TopBarArea(ui, group, engine, parkedView, partial, level, accent)
                    NowCard(ui, parkedView, accent, engine, Modifier.weight(1f))
                    NextStrip(ui)
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TopBarArea(ui, group, engine, parkedView, partial, level, accent)
                    NowCard(ui, parkedView, accent, engine, Modifier.height(720.dp))
                    ParkedSide(ui, vehicle, group, accent, engine)
                }
            }
        }

        if (ui.phase == TripEngine.Phase.ROLLCALL) RollCall(ui, vehicle, group, parkedView, accent, engine)
        // 차가 서 있을 때만 화면 가운데 물음 창. 달리는 동안의 물음은 이야기 카드 안에 붙여 화면을 덮지 않음
        if (parkedView && ui.phase == TripEngine.Phase.DRIVING) {
            val offer = ui.offer
            val pick = ui.pick
            val windowAsk = ui.windowAsk
            when {
                ui.confirmEnd -> EndDialog(ui, engine)
                windowAsk != null -> WindowDialog(windowAsk.name.pick(ui.lang), ui, engine)
                offer != null -> OfferDialog(offer, ui, engine)
                pick != null -> PickDialog(pick, ui, engine)
            }
        }
        Toast(ui)
    }
}

/**
 * 맨 위 막대(여행 이름, 그룹 번호). 듣는 동안과 알아들은 직후에는 이 자리에 듣기 표시를 겹쳐 띄움.
 * 막대 높이에 맞춰 겹치므로 아래 카드가 밀리지 않고, 오른쪽 다음 명소 카드도 가리지 않음
 */
@Composable
private fun TopBarArea(
    ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine, parkedView: Boolean,
    partial: String, level: Double, accent: Color,
) {
    Box {
        TopBar(ui, group, engine, parkedView)
        Hearing(ui, partial, level, accent, Modifier.matchParentSize())
    }
}

@Composable
private fun TopBar(ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine, parkedView: Boolean) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(sc.who.pick(lang), color = G.muted, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sc.title.pick(lang), color = G.text, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ui.groupCode?.let { Pill(t(lang, "그룹 $it", "Group $it")) }
        Pill(t(lang, "휴대폰 ${group.phones}", "Phones ${group.phones}"))
        if (group.cars.size > 1) Pill(t(lang, "차 ${group.cars.size}대", "${group.cars.size} cars"))
        // 여행을 끝내는 단추는 차가 섰을 때만. 달리는 동안은 "여행 끝내 줘"라고 말하면 됨
        if (parkedView) ChipButton(t(lang, "여행 끝", "End trip"), color = Color(0xFF3A2026), textColor = Color(0xFFFFB4B4)) { engine.requestEndTrip() }
    }
}

@Composable
private fun NowCard(ui: TripEngine.Ui, parkedView: Boolean, accent: Color, engine: TripEngine, modifier: Modifier) {
    val lang = ui.lang
    val place = ui.nowPlace
    // 달리는 동안의 물음(고른 장소, 쉬어 가기, 충전, 여행 끝)은 이 카드 안에서 답함
    val docked = !parkedView && (ui.confirmEnd || ui.offer != null || ui.pick != null)
    Panel(modifier.fillMaxWidth(), accent = accent, padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val label = when (ui.nowKind) {
                StoryPlayer.Kind.STORY -> t(lang, "지금 들려주는 이야기", "Now telling")
                StoryPlayer.Kind.ANNOUNCE -> t(lang, "곧 보여요", "Coming up")
                StoryPlayer.Kind.SYSTEM -> t(lang, "안내", "Guide")
                StoryPlayer.Kind.ANSWER -> t(lang, "물어본 것에 답하는 중", "Answering your question")
                StoryPlayer.Kind.KID -> t(lang, "뒷자리 친구들에게", "For the back seat")
                null -> t(lang, "구르는 가이드", "Rolling Guide")
            }
            Text(label, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            // 이야기 길이는 다음 길 안내까지 남은 시간에 맞춰 고름 (경로를 아는 가이드)
            ui.nowLength?.let {
                Pill(
                    when (it) {
                        StoryLength.SHORT -> t(lang, "짧게", "Short")
                        StoryLength.MEDIUM -> t(lang, "보통", "Medium")
                        StoryLength.LONG -> t(lang, "길게", "Long")
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            if (ui.speaking) Pill(t(lang, "말하는 중", "Speaking"), color = accent.copy(alpha = 0.25f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            ui.nowTitle ?: t(lang, "출발 준비 중", "Getting ready"),
            color = G.text, fontSize = 64.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )

        // 질문을 듣는 동안은 위쪽 듣기 표시로 알리므로 여기서는 뺌
        val hold = ui.holds.firstOrNull { it != TripEngine.HOLD_LISTEN }
        if (hold != null && !docked) {
            Spacer(Modifier.height(14.dp))
            val msg = when (hold) {
                TripEngine.HOLD_TURN -> t(lang, "방향지시등이 켜져 잠시 멈췄어요", "Paused for the turn signal")
                TripEngine.HOLD_GUIDE -> t(lang, "곧 길 안내가 나와서 잠시 쉬어요", "Pausing for navigation guidance")
                TripEngine.HOLD_CAUTION -> t(lang, "단속 카메라, 어린이 보호구역 앞이라 쉬어요", "Pausing near a camera or school zone")
                TripEngine.HOLD_CALL -> t(lang, "전화 중이라 잠시 멈췄어요", "Paused for the phone call")
                TripEngine.HOLD_NAVVOICE -> t(lang, "길 안내 음성이 나와서 잠시 쉬어요", "Pausing for the navigation voice")
                TripEngine.HOLD_USER -> t(lang, "멈춰 뒀어요. 계속을 누르면 이어 가요", "Paused. Tap Resume to continue")
                else -> t(lang, "잠시 멈춤", "Paused")
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.warn.copy(alpha = 0.16f)).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Text(msg, color = G.warn, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                docked -> PromptDock(ui, engine, accent)
                parkedView && place?.model != null -> Column {
                    PlaceClip(
                        model = place.model,
                        name = place.name.pick(lang),
                        night = ui.night,
                        accent = accent,
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(ui.nowSentence ?: "", color = G.text, fontSize = 28.sp, lineHeight = 38.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                parkedView -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(ui.nowText ?: t(lang, "명소에 가까워지면 차가 먼저 알려 드려요.", "When we get close to a landmark, the car will tell you first."), color = G.text, fontSize = 30.sp, lineHeight = 44.sp)
                }
                else -> DrivingCenter(ui, accent)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (ui.sentenceCount > 0 && !docked) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(ui.sentenceCount) { i ->
                    Box(Modifier.height(8.dp).weight(1f).clip(RoundedCornerShape(4.dp)).background(if (i <= ui.sentenceIndex) accent else G.surface2))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (!docked) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GhostButton(t(lang, "다시 듣기", "Replay"), Modifier.weight(1f)) { engine.onMediaCommand(MediaKeys.Command.PREVIOUS) }
                GhostButton(if (TripEngine.HOLD_USER in ui.holds) t(lang, "계속", "Resume") else t(lang, "멈춤", "Pause"), Modifier.weight(1f)) { engine.togglePause() }
                GhostButton(t(lang, "다음 이야기", "Next"), Modifier.weight(1f)) { engine.onMediaCommand(MediaKeys.Command.NEXT) }
                AskButton(ui.listening && !ui.asking, accent, lang, Modifier.weight(1.25f)) { engine.ask() }
            }
        }
        // 사용법 안내 글은 차가 섰을 때만 (달리는 동안 읽게 하지 않음)
        if (parkedView) {
            Text(
                t(lang, "운전대의 이전, 다음 버튼으로도 넘길 수 있어요. 창밖이 궁금하면 [물어보기]를 누르고 \"오른쪽에 보이는 거 뭐야?\" 하고 물어보세요", "Steering wheel prev/next buttons work too. Curious about something outside? Tap Ask and say \"What's that on the right?\""),
                color = G.muted, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 달리는 동안 카드 가운데: 어느 쪽 창밖인지를 크게. 방향이 없으면 소리로 안내 중이라는 것만 */
@Composable
private fun DrivingCenter(ui: TripEngine.Ui, accent: Color) {
    val lang = ui.lang
    val side = ui.nowSide
    val showSide = side != null && ui.nowKind in setOf(StoryPlayer.Kind.ANNOUNCE, StoryPlayer.Kind.STORY, StoryPlayer.Kind.KID, StoryPlayer.Kind.ANSWER)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        if (showSide && side != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                DirectionArrow(side, accent, 112.dp)
                Column {
                    Text(sideLabel(side, ui.nowKind, lang), color = G.text, fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Text(t(lang, "창밖을 보면 돼요", "Look out the window"), color = G.muted, fontSize = 26.sp)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                SpeakerGlyph(accent.copy(alpha = 0.9f), Modifier.size(72.dp))
                Column {
                    Text(t(lang, "운전 중에는 소리로 안내해요", "While driving, the guide speaks"), color = G.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(t(lang, "글과 영상은 동승자 휴대폰에서 같이 봐요", "Text and video are on passengers' phones"), color = G.muted, fontSize = 26.sp)
                }
            }
        }
    }
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

/** 왼쪽, 오른쪽, 앞, 뒤를 가리키는 굵은 화살표 */
@Composable
private fun DirectionArrow(side: String, color: Color, size: Dp) {
    val deg = when (side) {
        "left" -> 180f
        "right" -> 0f
        "behind" -> 90f
        else -> -90f
    }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color.copy(alpha = 0.18f), radius = w / 2f)
        rotate(deg, Offset(w / 2f, h / 2f)) {
            val path = Path().apply {
                moveTo(w * 0.22f, h * 0.42f)
                lineTo(w * 0.55f, h * 0.42f)
                lineTo(w * 0.55f, h * 0.26f)
                lineTo(w * 0.82f, h * 0.50f)
                lineTo(w * 0.55f, h * 0.74f)
                lineTo(w * 0.55f, h * 0.58f)
                lineTo(w * 0.22f, h * 0.58f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** 스피커 모양 (소리로 안내 중) */
@Composable
private fun SpeakerGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.10f, h * 0.38f)
            lineTo(w * 0.28f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.18f)
            lineTo(w * 0.50f, h * 0.82f)
            lineTo(w * 0.28f, h * 0.62f)
            lineTo(w * 0.10f, h * 0.62f)
            close()
        }
        drawPath(body, color)
        val sw = w * 0.07f
        drawArc(color, -45f, 90f, false, topLeft = Offset(w * 0.40f, h * 0.30f), size = Size(w * 0.30f, h * 0.40f), style = Stroke(width = sw, cap = StrokeCap.Round))
        drawArc(color, -50f, 100f, false, topLeft = Offset(w * 0.36f, h * 0.14f), size = Size(w * 0.52f, h * 0.72f), style = Stroke(width = sw, cap = StrokeCap.Round))
    }
}

/** 달리는 동안의 물음. 화면을 덮지 않고 이야기 카드 안에서 큰 단추와 말로 답함 */
@Composable
private fun PromptDock(ui: TripEngine.Ui, engine: TripEngine, accent: Color) {
    val lang = ui.lang
    val offer = ui.offer
    val pick = ui.pick
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        when {
            ui.confirmEnd -> {
                Text(t(lang, "여행 끝", "End trip"), color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(t(lang, "오늘 여행을 끝낼까요?", "End today's trip?"), color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigButton(t(lang, "끝내고 기록 보기", "End and see the record"), Modifier.weight(1f), color = accent, size = 28.sp) { engine.endTrip() }
                    BigButton(t(lang, "계속 가기", "Keep going"), Modifier.weight(1f), color = G.surface2, textColor = G.text, size = 28.sp) { engine.cancelEndTrip() }
                }
            }
            offer != null -> {
                // 위의 큰 제목과 같으면 작은 제목은 되풀이하지 않음
                if (offer.title != ui.nowTitle) Text(offer.title, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(offer.body, color = G.text, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigButton(offer.yes, Modifier.weight(1f), color = accent, size = 28.sp) { engine.answerOffer(true) }
                    BigButton(offer.no, Modifier.weight(1f), color = G.surface2, textColor = G.text, size = 28.sp) { engine.answerOffer(false) }
                }
            }
            pick != null -> {
                Text(
                    if (pick.fromCar) t(lang, "${pick.by}에서 보낸 장소", "Sent from ${pick.by}") else t(lang, "${pick.by} 님이 고른 곳", "${pick.by} picked a place"),
                    color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                )
                Text(pick.name, color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(22.dp))
                PickButtons(pick, lang, engine, accent)
            }
        }
        VoiceHint(ui, accent)
    }
}

/** 고른 장소 단추. 카페, 볼거리는 '가는 길에 들르기'가, 주차장은 '그리로 안내'가 먼저 ("응"이라고 하면 이것) */
@Composable
private fun PickButtons(pick: TripEngine.Pick, lang: String, engine: TripEngine, accent: Color) {
    val parking = pick.kind.equals("parking", ignoreCase = true)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (parking) {
            BigButton(t(lang, "그리로 안내", "Take us there"), Modifier.weight(1.2f), color = accent, size = 28.sp) { engine.answerPick("destination") }
            BigButton(t(lang, "경유지로 들르기", "Add as a stop"), Modifier.weight(1f), color = G.surface2, textColor = G.text, size = 26.sp) { engine.answerPick("waypoint") }
        } else {
            BigButton(t(lang, "가는 길에 들르기", "Stop on the way"), Modifier.weight(1.2f), color = accent, size = 28.sp) { engine.answerPick("waypoint") }
            BigButton(t(lang, "목적지로 바꾸기", "Make it the destination"), Modifier.weight(1f), color = G.surface2, textColor = G.text, size = 26.sp) { engine.answerPick("destination") }
        }
        BigButton(t(lang, "나중에", "Later"), Modifier.weight(0.8f), color = G.surface2, textColor = G.text, size = 26.sp) { engine.answerPick("later") }
    }
}

/** 차가 물은 것에 말로 답할 낱말. 물음 아래 안내와 맨 위 듣기 표시가 같은 말을 쓰게 한곳에서 정함 */
private fun answerWords(ui: TripEngine.Ui): String {
    val lang = ui.lang
    return when {
        ui.pick != null -> t(lang, "‘응’, ‘목적지로’, ‘나중에’", "“yes”, “destination”, “later”")
        ui.confirmEnd -> t(lang, "‘응’ 또는 ‘아니’", "“yes” or “no”")
        else -> t(lang, "‘응’ 또는 ‘괜찮아’", "“yes” or “no thanks”")
    }
}

/** 물음 아래: 말로도 답할 수 있다는 안내. 차가 듣는 중이면 그렇다고 바꿔 보여 줌 */
@Composable
private fun VoiceHint(ui: TripEngine.Ui, accent: Color) {
    val lang = ui.lang
    val listening = ui.asking && ui.listening
    val words = answerWords(ui)
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MicGlyph(if (listening) accent else G.muted, Modifier.size(28.dp))
        Text(
            if (listening) t(lang, "듣고 있어요. $words 하고 말해 주세요", "Listening. Say $words") else t(lang, "말로 답해도 돼요: $words", "You can just say $words"),
            color = if (listening) accent else G.muted, fontSize = 24.sp, fontWeight = if (listening) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 좁은 창에서 달릴 때 카드 아래 한 줄: 다음 명소와 남은 거리 */
@Composable
private fun NextStrip(ui: TripEngine.Ui) {
    val lang = ui.lang
    val next = ui.nextPlace ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(G.surface).padding(horizontal = 26.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t(lang, "다음", "Next"), color = G.muted, fontSize = 24.sp)
        Spacer(Modifier.width(16.dp))
        Text(next.name.pick(lang), color = G.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        ui.nextDistanceM?.let { Text(formatDist(it), color = G.text, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
    }
}

/** 달리는 동안 오른쪽 칸: 다음 명소, 오늘 코스 지도, 지금 이야기에 반영 중인 차 상태 몇 가지만 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrivingSide(ui: TripEngine.Ui, vehicle: VehicleBridge.State, accent: Color, engine: TripEngine) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    Panel(padding = 22.dp) {
        SectionTitle(t(lang, "다음 명소", "Next stop"))
        val next = ui.nextPlace
        if (next != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(next.name.pick(lang), color = G.text, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ui.nextDistanceM?.let { Text(formatDist(it), color = accent, fontSize = 34.sp, fontWeight = FontWeight.Black) }
            }
        } else {
            Text(t(lang, "오늘 코스의 명소를 다 지났어요", "We've passed every stop today"), color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        RouteMap(
            route = sc.route,
            places = sc.places.filter { it.triggers },
            entered = ui.entered,
            car = ui.carPos,
            heading = ui.heading,
            accent = accent,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }

    // 이야기에 반영 중인 차 상태 (켜진 것만)
    val active = buildList {
        if (ui.rain) add(t(lang, "비 오는 날 이야기", "Rain stories") to Color(0x335B8CFF))
        if (ui.night) add(t(lang, "밤 이야기", "Night stories") to Color(0x33A78BFA))
        if (ui.jam) add(t(lang, "막히는 길, 긴 이야기", "Traffic: longer stories") to G.warn.copy(alpha = 0.22f))
        if (ui.kidMode) add(t(lang, "뒷자리 아이 이야기", "Kid stories") to Color(0x33FFB86B))
        if (ui.freshAir) add(t(lang, "바깥 공기 들이는 중", "Fresh air in") to Color(0x3300E5FF))
        if (ui.windowOpened) add(t(lang, "앞 창문 조금 열림", "Front windows open") to Color(0x3300E5FF))
    }
    if (active.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            active.forEach { (text, color) -> Pill(text, color = color, size = 24.sp) }
        }
    }

    if (ui.starsNeeded > 0) {
        Panel(padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(lang, "보물 상자", "Treasure box"), color = G.muted, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Stars(ui.stars, ui.starsNeeded)
            }
        }
    }
    if (ui.laterPicks.isNotEmpty()) {
        Text(t(lang, "담아 둔 장소 ${ui.laterPicks.size}곳: 차를 세우면 보여 드려요", "${ui.laterPicks.size} saved places: shown when we stop"), color = G.muted, fontSize = 22.sp)
    }
    if (ui.demoMode) DemoPanel(ui, vehicle, accent, engine)
}

/** 차가 섰을 때 오른쪽 칸: 물어본 것과 답, 보물 상자, 담아 둔 장소, 지도와 명소 목록, QR, 다른 차에 보내기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParkedSide(ui: TripEngine.Ui, vehicle: VehicleBridge.State, group: CompanionClient.State, accent: Color, engine: TripEngine) {
    val lang = ui.lang
    val sc = ui.scenario ?: return

    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipButton(
            if (ui.kidMode) t(lang, "뒷자리 아이 모드 켬", "Kid mode on") else t(lang, "아이 모드 끔", "Kid mode off"),
            color = if (ui.kidMode) Color(0x33FFB86B) else G.surface2,
        ) { engine.toggleKidMode() }
        if (ui.windowOpened) ChipButton(t(lang, "앞 창문 닫기", "Close front windows"), color = Color(0x3300E5FF)) { engine.closeWindows() }
        if (ui.rain) Pill(t(lang, "비 오는 날 이야기", "Rain stories"), color = Color(0x335B8CFF))
        if (ui.night) Pill(t(lang, "밤 이야기", "Night stories"), color = Color(0x33A78BFA))
        if (ui.sunroofOpen) Pill(t(lang, "선루프 열림", "Sunroof open"), color = Color(0x33A78BFA))
    }

    val askedText = ui.askedText
    if (ui.asked > 0 && askedText != null) {
        Panel(padding = 20.dp) {
            SectionTitle(t(lang, "방금 물어본 것", "You just asked"))
            Text("“$askedText”", color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ui.answerTitle?.let { Text(t(lang, "답: $it", "Answer: $it"), color = accent, fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp)) }
            ui.answerText?.let { Text(it, color = G.muted, fontSize = 22.sp, lineHeight = 31.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }

    if (ui.starsNeeded > 0) {
        Panel(padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(t(lang, "보물 상자", "Treasure box"))
                Spacer(Modifier.weight(1f))
                Stars(ui.stars, ui.starsNeeded)
            }
            Text(
                when (ui.treasure) {
                    TripEngine.Treasure.READY -> t(lang, "별을 다 모았어요! 차를 세우면 보물 상자를 열어 드려요.", "All stars collected! Park to open the treasure box.")
                    TripEngine.Treasure.OPEN -> t(lang, "보물 상자를 열었어요. 달리기 시작하면 다시 잠가요.", "Treasure box opened. It locks again when we drive off.")
                    else -> t(lang, "휴대폰 퀴즈를 맞히면 별이 모여요. 별 ${ui.starsNeeded}개면 조수석 앞 글로브 박스가 열려요.", "Answer phone quizzes to collect stars. ${ui.starsNeeded} stars open the glove box.")
                },
                color = if (ui.treasure == TripEngine.Treasure.READY) Color(0xFFFFC94D) else G.muted, fontSize = 22.sp, lineHeight = 30.sp,
            )
            if (ui.quizSent > 0) Text(t(lang, "보낸 퀴즈 ${ui.quizSent}개", "${ui.quizSent} quizzes sent") + (ui.lastStarBy?.let { t(lang, ", 마지막 별 $it", ", last star: $it") } ?: ""), color = G.muted, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    // 달리는 동안 '나중에'로 담아 둔 장소
    if (ui.laterPicks.isNotEmpty()) {
        Panel(padding = 20.dp) {
            SectionTitle(t(lang, "담아 둔 장소", "Saved for later"))
            ui.laterPicks.forEach { p ->
                Text(p.name, color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(t(lang, "${p.by} 님이 고른 곳", "Picked by ${p.by}"), color = G.muted, fontSize = 20.sp)
                Row(Modifier.padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChipButton(t(lang, "들르기", "Stop there"), color = accent.copy(alpha = 0.3f)) { engine.routeLaterPick(p.id, "waypoint") }
                    ChipButton(t(lang, "목적지로", "Go there")) { engine.routeLaterPick(p.id, "destination") }
                    ChipButton(t(lang, "지우기", "Remove")) { engine.routeLaterPick(p.id, "drop") }
                }
            }
        }
    }

    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(t(lang, "오늘 코스", "Today's course"))
            Spacer(Modifier.weight(1f))
            ui.nextPlace?.let { p ->
                Text(
                    t(lang, "다음 ${p.name.pick(lang)}", "Next ${p.name.pick(lang)}") + (ui.nextDistanceM?.let { "  " + formatDist(it) } ?: ""),
                    color = G.text, fontSize = 22.sp,
                )
            }
        }
        RouteMap(
            route = sc.route,
            places = sc.places.filter { it.triggers },
            entered = ui.entered,
            car = ui.carPos,
            heading = ui.heading,
            accent = accent,
            modifier = Modifier.fillMaxWidth().height(250.dp),
        )
        Spacer(Modifier.height(8.dp))
        sc.places.filter { it.triggers && it.kind != PlaceKind.CITY }.forEach { p ->
            val done = p.id in ui.entered
            val now = ui.nowPlace?.id == p.id
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(if (now) accent else if (done) G.good else G.line, 16.dp)
                Text(p.name.pick(lang), color = if (done || now) G.text else G.muted, fontSize = 24.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal)
                Spacer(Modifier.weight(1f))
                val heard = ui.heard[p.id]
                Text(
                    when {
                        now -> t(lang, "지금", "Now")
                        heard != null -> t(lang, "들음", "Heard")
                        done -> t(lang, "지남", "Passed")
                        else -> ""
                    },
                    color = G.muted, fontSize = 20.sp,
                )
            }
        }
    }

    // QR: 차가 서 있을 때만
    Panel(padding = 20.dp) {
        val joinUrl = group.joinUrl
        if (joinUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                QrImage(joinUrl, 200.dp)
                Column(Modifier.weight(1f)) {
                    Text(t(lang, "함께 탄 사람은 QR을 찍어 주세요", "Passengers: scan to join"), color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(t(lang, "앱을 깔지 않아도 이 여행의 그룹 페이지가 열려요. 차가 이야기를 시작하면 휴대폰에 영상과 3D 모형이 같이 떠요.", "No app needed. When the car tells a story, your phone shows the video and 3D model."), color = G.muted, fontSize = 22.sp, lineHeight = 30.sp)
                    ui.groupCode?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(t(lang, "그룹 번호 $it", "Group code $it"), color = accent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        } else {
            Text(t(lang, "그룹 서버에 연결되면 QR이 떠요. 차 안 안내는 그대로 됩니다.", "The QR appears when the group server is reachable. The in-car guide still works."), color = G.muted, fontSize = 22.sp)
        }
    }

    // 다른 차에 장소 보내기 (예: 먼저 도착한 차가 주차장 공유)
    val shareables = sc.pickables.filter { it.kind == PlaceKind.PARKING || it.kind == PlaceKind.CAFE }
    if (shareables.isNotEmpty() && (group.cars.size > 1 || sc.cars > 1)) {
        Panel(padding = 20.dp) {
            SectionTitle(t(lang, "다른 차에 보내기", "Send to the other cars"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shareables.forEach { p -> ChipButton(p.name.pick(lang)) { engine.sharePlace(p) } }
            }
            Text(t(lang, "달리는 중에는 \"다른 차에 주차장 보내 줘\"라고 말하면 돼요", "While driving, just say \"send the parking to the other car\""), color = G.muted, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }

    if (ui.demoMode) DemoPanel(ui, vehicle, accent, engine)
}

/** 에뮬레이터 시연용 패널. 차 상태는 Virtual Console 값이 앱에 들어온 것을 한 줄로 보여 줌 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(ui: TripEngine.Ui, vehicle: VehicleBridge.State, accent: Color, engine: TripEngine) {
    val lang = ui.lang
    // 시연 단추는 접어 둠 (운전자 화면을 어지럽히지 않게). 차 신호 한 줄은 늘 보여 Virtual Console 값이 들어오는 걸 확인함
    var open by remember { mutableStateOf(false) }
    Panel(padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(t(lang, "시연 주행 (에뮬레이터용)", "Demo drive (emulator)"))
            Spacer(Modifier.weight(1f))
            ChipButton(if (open) t(lang, "시연 단추 숨기기", "Hide demo controls") else t(lang, "시연 단추 보기", "Show demo controls"), size = 20.sp, minHeight = 60.dp) { open = !open }
        }
        val seated = vehicle.seatedKeys.size
        val signals = buildList {
            add(t(lang, "기어 ${vehicle.gear}", "Gear ${vehicle.gear}"))
            add(t(lang, "벨트 ${vehicle.buckledCount}/$seated", "Belts ${vehicle.buckledCount}/$seated"))
            if (vehicle.turnSignal != "OFF") add(t(lang, if (vehicle.turnSignal == "LEFT") "왼쪽 깜빡이" else "오른쪽 깜빡이", "Signal ${vehicle.turnSignal.lowercase()}"))
            val fuelCar = vehicle.powertrain == "ICE" || vehicle.powertrain == "HEV" || (vehicle.socPct == null && vehicle.fuelPct != null)
            if (fuelCar) vehicle.fuelPct?.let { add(t(lang, "기름 $it%", "Fuel $it%")) }
            else vehicle.socPct?.let { add(t(lang, "배터리 $it%", "Battery $it%")) }
            (ui.drivingMin ?: vehicle.drivingMin)?.takeIf { it > 0 }?.let { add(t(lang, "주행 ${Ko.minutes(it, "ko")}", "Driving ${Ko.minutes(it, "en")}")) }
            ui.guideDistanceM?.let { add(t(lang, "다음 길 안내 ${it.toInt()}m", "Next turn ${it.toInt()}m")) }
        }
        Text(t(lang, "차 신호: ", "Car signals: ") + signals.joinToString("  ·  "), color = G.muted, fontSize = 20.sp, lineHeight = 28.sp)
        if (ui.routeLengthM > 0) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(G.surface2)) {
                Box(Modifier.fillMaxWidth((ui.progressM / ui.routeLengthM).toFloat().coerceIn(0f, 1f)).height(10.dp).background(accent))
            }
        }
        if (open) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipButton(if (ui.demoRunning) t(lang, "주행 멈춤", "Pause drive") else t(lang, "주행 시작", "Drive"), color = accent.copy(alpha = 0.3f), size = 20.sp, minHeight = 60.dp) { if (ui.demoRunning) engine.demoPause() else engine.demoPlay() }
            ChipButton(t(lang, "빨리 감기 x${ui.demoFast}", "Fast x${ui.demoFast}"), size = 20.sp, minHeight = 60.dp) { engine.demoFaster() }
            ChipButton(t(lang, "다음 명소 앞으로", "Skip to next place"), size = 20.sp, minHeight = 60.dp) { engine.demoJumpToNext() }
            ChipButton(if (ui.followGear) t(lang, "기어 따라 주행: 켬", "Follow gear: on") else t(lang, "기어 따라 주행: 끔", "Follow gear: off"), size = 20.sp, minHeight = 60.dp) { engine.toggleFollowGear() }
            ChipButton(if (ui.demoJam) t(lang, "막히는 길 흉내: 켬", "Traffic jam: on") else t(lang, "막히는 길 흉내: 끔", "Traffic jam: off"), size = 20.sp, minHeight = 60.dp) { engine.setDemoJam(!ui.demoJam) }
        }
        // 마이크 입력이 없는 에뮬레이터에서 말로 묻기를 보여 주는 단추 (앱에 넣어 둔 녹음을 음성 인식 SDK 에 그대로 넣음)
        val qs = engine.demoQuestions[lang] ?: engine.demoQuestions.getValue("ko")
        val nextQ = qs[ui.demoQuestionIdx % qs.size]
        Spacer(Modifier.height(10.dp))
        ChipButton(t(lang, "녹음한 질문으로 묻기: “${nextQ.text}”", "Ask with a recording: “${nextQ.text}”"), color = accent.copy(alpha = 0.22f), size = 20.sp, minHeight = 60.dp) { engine.askDemo() }
        }
    }
}

private fun formatDist(m: Double): String = if (m >= 1000) "%.1fkm".format(m / 1000) else "${m.toInt()}m"

/** 퀴즈 별. 모은 만큼 채운 별 */
@Composable
private fun Stars(have: Int, need: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(maxOf(need, have)) { i ->
            Text(if (i < have) "★" else "☆", color = if (i < have) Color(0xFFFFC94D) else G.muted, fontSize = 34.sp)
        }
    }
}

/** [물어보기] 버튼. 듣는 동안에만 색이 차고 마이크가 숨 쉬듯 움직임 (가만히 있을 때는 애니메이션 없음) */
@Composable
private fun AskButton(listening: Boolean, accent: Color, lang: String, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (listening) Color(0xFF06121A) else G.text
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    Box(
        modifier.height(96.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(28.dp))
            .background(if (listening) accent else accent.copy(alpha = 0.26f))
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (listening) PulsingMic(ink) else MicGlyph(accent, Modifier.size(36.dp))
            Text(if (listening) t(lang, "듣는 중", "Listening") else t(lang, "물어보기", "Ask"), color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PulsingMic(color: Color) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val s by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "micScale")
    MicGlyph(color, Modifier.size(36.dp).graphicsLayer { scaleX = s; scaleY = s })
}

@Composable
private fun MicGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.36f
        drawRoundRect(color, topLeft = Offset((w - bodyW) / 2, 0f), size = Size(bodyW, h * 0.58f), cornerRadius = CornerRadius(bodyW / 2))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.17f, h * 0.2f), size = Size(w * 0.66f, h * 0.52f), style = Stroke(width = w * 0.08f, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, h * 0.73f), Offset(w / 2, h * 0.92f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.3f, h * 0.94f), Offset(w * 0.7f, h * 0.94f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    }
}

/**
 * 듣는 중이면 소리 크기 막대와 알아듣는 중인 글자를, 다 들으면 알아들은 문장을 잠깐 띄움.
 * 맨 위 막대 자리에 한 줄로 겹쳐 띄워서 이야기 카드, 단추, 오른쪽 카드를 가리지 않음
 */
@Composable
private fun Hearing(ui: TripEngine.Ui, partial: String, level: Double, accent: Color, modifier: Modifier = Modifier) {
    var showHeard by remember { mutableStateOf(false) }
    LaunchedEffect(ui.heardSeq) {
        if (ui.heardSeq > 0) {
            showHeard = true
            delay(4000)
            showHeard = false
        }
    }
    // 차가 물은 것에 답을 듣는 중이면 물음 바로 아래 안내가 "듣고 있어요"를 보여 주므로 여기서는 겹쳐 띄우지 않음 (한눈에 볼 것을 하나로)
    val visible = (ui.listening && !ui.asking) || (showHeard && ui.heardText != null)
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color(0xFF101820))
                .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(28.dp)).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (ui.listening) LevelBars(level, accent, Modifier.size(64.dp, 44.dp)) else MicGlyph(accent, Modifier.size(36.dp))
            val title = if (ui.listening) t(ui.lang, "듣고 있어요", "Listening") else t(ui.lang, "이렇게 들었어요", "I heard")
            Text(title, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            // 물음에 답하는 중이면 아래 안내와 같은 낱말을, 그냥 물을 때는 물어볼 거리를 보여 줌. 들리기 시작하면 들린 글자로
            val hint = if (ui.asking) answerWords(ui) else t(ui.lang, "창밖에 보이는 걸 물어보세요", "Ask about anything outside")
            val shown = if (ui.listening) partial.ifBlank { hint } else "“${ui.heardText ?: ""}”"
            Text(shown, color = G.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** 소리 크기 막대 다섯 개. 음성 인식 엔진이 알려 주는 소리 크기(rms)에 맞춰 움직임. 듣는 동안에만 그림 */
@Composable
private fun LevelBars(level: Double, accent: Color, modifier: Modifier = Modifier.size(88.dp, 60.dp)) {
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
            drawRoundRect(accent, topLeft = Offset(i * bw * 2f, (size.height - bh) / 2f), size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2f))
        }
    }
}

/** 차가 서 있을 때의 물음 창 틀 */
@Composable
private fun DialogFrame(accent: Color, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA05080C)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 980.dp), accent = accent, padding = 36.dp) { content() }
    }
}

/** 차가 먼저 권하는 것: 쉬어 가기, 충전, 보물 상자, 하늘 열기. [예]를 누르거나 "응"이라고 해야만 움직임 */
@Composable
private fun OfferDialog(o: TripEngine.Offer, ui: TripEngine.Ui, engine: TripEngine) {
    val color = when (o.kind) {
        TripEngine.OfferKind.TREASURE -> Color(0xFFFFC94D)
        TripEngine.OfferKind.SUNROOF -> Color(0xFFA78BFA)
        TripEngine.OfferKind.CHARGE -> G.good
        TripEngine.OfferKind.REST -> G.sky
    }
    DialogFrame(color) {
        Text(o.title, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(o.body, color = G.text, fontSize = 38.sp, fontWeight = FontWeight.Black, lineHeight = 50.sp)
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigButton(o.yes, Modifier.weight(1f), color = color) { engine.answerOffer(true) }
            BigButton(o.no, Modifier.weight(1f), color = G.surface2, textColor = G.text) { engine.answerOffer(false) }
        }
        VoiceHint(ui, color)
    }
}

@Composable
private fun EndDialog(ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    DialogFrame(Color(0xFFFFB4B4)) {
        Text(t(lang, "여행 끝", "End trip"), color = Color(0xFFFFB4B4), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(t(lang, "오늘 여행을 끝낼까요?", "End today's trip?"), color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
        Text(t(lang, "끝내면 하루 기록 카드를 보여 드려요. 앱이 바꾼 조명과 공조는 원래대로 돌려 둬요.", "You'll see today's trip card. Lights and climate go back to how they were."), color = G.muted, fontSize = 24.sp, lineHeight = 32.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigButton(t(lang, "끝내고 기록 보기", "End and see the record"), Modifier.weight(1f), color = Color(0xFFFFB4B4)) { engine.endTrip() }
            BigButton(t(lang, "계속 가기", "Keep going"), Modifier.weight(1f), color = G.surface2, textColor = G.text) { engine.cancelEndTrip() }
        }
        VoiceHint(ui, Color(0xFFFFB4B4))
    }
}

@Composable
private fun RollCall(ui: TripEngine.Ui, vehicle: VehicleBridge.State, group: CompanionClient.State, parkedView: Boolean, accent: Color, engine: TripEngine) {
    val lang = ui.lang
    val sc = ui.scenario
    // 출발 전(차가 서 있을 때)이 동승자가 QR을 찍기 가장 좋은 때라 점호 창 옆에 QR을 같이 띄움. 달리는 중이면 숨김
    val joinUrl = group.joinUrl
    val showQr = parkedView && joinUrl != null
    Box(Modifier.fillMaxSize().background(Color(0xCC05080C)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = if (showQr) 1560.dp else 980.dp), padding = 40.dp) {
          Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
           Column(Modifier.weight(1f)) {
            Text(t(lang, "출발 전 좌석 점호", "Seat roll call"), color = G.sky, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(t(lang, "모두 안전벨트를 매면 출발 안내를 시작해요", "Buckle up and the trip begins"), color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
            // 오늘 지나갈 명소
            val places = sc?.landmarks?.filter { it.triggers }?.joinToString(", ") { it.name.pick(lang) }.orEmpty()
            if (places.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(t(lang, "오늘 지나갈 곳  $places", "Today's stops  $places"), color = G.muted, fontSize = 24.sp, lineHeight = 32.sp)
            }
            Spacer(Modifier.height(28.dp))
            val names = mapOf(
                "ROW_1_LEFT" to t(lang, "운전석", "Driver"),
                "ROW_1_RIGHT" to t(lang, "조수석", "Front passenger"),
                "ROW_2_LEFT" to t(lang, "뒷좌석 왼쪽", "Rear left"),
                "ROW_2_RIGHT" to t(lang, "뒷좌석 오른쪽", "Rear right"),
            )
            listOf(listOf("ROW_1_LEFT", "ROW_1_RIGHT"), listOf("ROW_2_LEFT", "ROW_2_RIGHT")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                    row.forEach { key ->
                        val seated = key in vehicle.seatedKeys
                        val buckled = vehicle.buckled(key)
                        val bg = when {
                            !seated -> G.surface2
                            buckled -> G.good.copy(alpha = 0.22f)
                            else -> G.warn.copy(alpha = 0.18f)
                        }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(bg).padding(22.dp)) {
                            Text(names[key] ?: key, color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    !seated -> t(lang, "빈자리", "Empty")
                                    buckled -> t(lang, "벨트 맸어요", "Buckled")
                                    else -> t(lang, "벨트를 매 주세요", "Please buckle up")
                                },
                                color = if (buckled) G.good else if (seated) G.warn else G.muted, fontSize = 24.sp,
                            )
                        }
                    }
                }
            }
            // 보물 상자 놀이는 부모가 미리 준비해야 하므로 출발 전에 한 줄로 알림
            if (ui.starsNeeded > 0) {
                Text(
                    t(lang, "보물 상자 놀이: 출발 전에 조수석 앞 글로브 박스에 작은 선물을 넣어 두세요", "Treasure game: put a small gift in the glove box before we go"),
                    color = Color(0xFFFFC94D), fontSize = 24.sp, lineHeight = 32.sp, modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton(t(lang, "모두 맸어요, 출발", "All buckled, let's go"), Modifier.weight(1f), height = 104.dp) { engine.completeRollcall() }
            }
            Text(
                t(lang, "차량 안전벨트 신호가 오면 자동으로 넘어가요", "Moves on automatically when the car reports all belts buckled"),
                color = G.muted, fontSize = 22.sp, modifier = Modifier.padding(top = 12.dp),
            )
           }
           if (showQr && joinUrl != null) {
               Column(Modifier.width(400.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                   Text(t(lang, "함께 탄 사람은 QR을 찍어 주세요", "Passengers: scan to join"), color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp, textAlign = TextAlign.Center)
                   Spacer(Modifier.height(18.dp))
                   QrImage(joinUrl, 320.dp)
                   Spacer(Modifier.height(16.dp))
                   ui.groupCode?.let { Text(t(lang, "그룹 번호 $it", "Group code $it"), color = accent, fontSize = 32.sp, fontWeight = FontWeight.Black) }
                   Text(
                       t(lang, "앱을 깔지 않아도 돼요\n휴대폰에서 영상과 퀴즈를 같이 봐요", "No app needed\nVideos and quizzes show up on your phone"),
                       color = G.muted, fontSize = 21.sp, lineHeight = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                   )
               }
           }
          }
        }
    }
}

@Composable
private fun PickDialog(p: TripEngine.Pick, ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    DialogFrame(G.sky) {
        Text(
            if (p.fromCar) t(lang, "${p.by}에서 보낸 장소", "Sent from ${p.by}") else t(lang, "${p.by} 님이 고른 곳", "${p.by} picked a place"),
            color = G.sky, fontSize = 26.sp, fontWeight = FontWeight.Bold,
        )
        Text(p.name, color = G.text, fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 58.sp)
        Spacer(Modifier.height(24.dp))
        PickButtons(p, lang, engine, G.sky)
        VoiceHint(ui, G.sky)
    }
}

@Composable
private fun WindowDialog(name: String, ui: TripEngine.Ui, engine: TripEngine) {
    val lang = ui.lang
    val color = Color(0xFF00E5FF)
    DialogFrame(color) {
        Text(name, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(t(lang, "앞 창문을 조금 내려 파도 소리를 들여 볼까요?", "Lower the front windows a little to hear the waves?"), color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
        Text(t(lang, "뒷창문은 그대로 두고, 차가 서 있을 때만 움직여요", "Rear windows stay shut. Windows move only while parked"), color = G.muted, fontSize = 24.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigButton(t(lang, "창문 내리기", "Lower windows"), Modifier.weight(1f), color = color) { engine.answerWindow(true) }
            BigButton(t(lang, "괜찮아요", "No thanks"), Modifier.weight(1f), color = G.surface2, textColor = G.text) { engine.answerWindow(false) }
        }
        VoiceHint(ui, color)
    }
}

@Composable
private fun Toast(ui: TripEngine.Ui) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ui.toastSeq) {
        if (ui.toast != null && ui.toastSeq > 0) {
            visible = true
            delay(4000)
            visible = false
        }
    }
    // 이야기 카드 아래쪽의 큰 단추(다시 듣기, 멈춤, 다음 이야기, 물어보기) 위에 띄워서 달리는 중에도 단추를 가리지 않음
    Box(Modifier.fillMaxSize().padding(bottom = 196.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.widthIn(max = 1100.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF0222B36)).padding(horizontal = 32.dp, vertical = 22.dp)) {
                Text(ui.toast ?: "", color = G.text, fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

