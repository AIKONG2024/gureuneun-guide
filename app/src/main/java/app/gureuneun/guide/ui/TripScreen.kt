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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.core.Ko
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.StoryLength
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.StoryPlayer
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.net.CompanionClient
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.delay

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
    val lang = ui.lang
    val sc = ui.scenario ?: return
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
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TopBar(ui, group, engine)
                        NowCard(ui, parkedView, accent, engine, Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        SideColumn(ui, vehicle, ux, group, parkedView, accent, engine)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TopBar(ui, group, engine)
                    NowCard(ui, parkedView, accent, engine, Modifier.height(760.dp))
                    SideColumn(ui, vehicle, ux, group, parkedView, accent, engine)
                }
            }
        }

        if (ui.phase == TripEngine.Phase.ROLLCALL) RollCall(ui, vehicle, engine)
        ui.pick?.let { PickDialog(it, lang, engine) }
        ui.windowAsk?.let { WindowDialog(it.name.pick(lang), lang, engine) }
        ui.offer?.let { OfferDialog(it, engine) }
        Hearing(ui, partial, level, accent)
        Toast(ui)
    }
}

@Composable
private fun TopBar(ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(sc.who.pick(lang), color = G.muted, fontSize = 20.sp)
            Text(sc.title.pick(lang), color = G.text, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ui.groupCode?.let { Pill(t(lang, "그룹 $it", "Group $it"), color = G.surface2) }
        Pill(t(lang, "휴대폰 ${group.phones}", "Phones ${group.phones}"), color = G.surface2)
        if (group.cars.size > 1) Pill(t(lang, "차 ${group.cars.size}대", "${group.cars.size} cars"), color = G.surface2)
        Pill(t(lang, "여행 끝", "End trip"), color = Color(0xFF3A2026), textColor = Color(0xFFFFB4B4), onClick = { engine.endTrip() })
    }
}

@Composable
private fun NowCard(ui: TripEngine.Ui, parkedView: Boolean, accent: Color, engine: TripEngine, modifier: Modifier) {
    val lang = ui.lang
    val place = ui.nowPlace
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
            Text(label, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            ui.nowLength?.let {
                Pill(
                    when (it) {
                        StoryLength.SHORT -> t(lang, "짧게", "Short")
                        StoryLength.MEDIUM -> t(lang, "보통", "Medium")
                        StoryLength.LONG -> t(lang, "길게", "Long")
                    },
                    size = 18.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (ui.speaking) Pill(t(lang, "말하는 중", "Speaking"), color = accent.copy(alpha = 0.25f), size = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            ui.nowTitle ?: t(lang, "출발 준비 중", "Getting ready"),
            color = G.text, fontSize = 60.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))

        // 질문을 듣는 동안은 아래 듣기 표시로 알리므로 여기서는 뺌
        val hold = ui.holds.firstOrNull { it != TripEngine.HOLD_LISTEN }
        if (hold != null) {
            val msg = when (hold) {
                TripEngine.HOLD_TURN -> t(lang, "방향지시등이 켜져 잠시 멈췄어요. 불이 꺼지면 이어서 들려 드려요.", "Turn signal on. I'll continue when it's off.")
                TripEngine.HOLD_GUIDE -> t(lang, "곧 길 안내가 나와서 잠시 쉬어요.", "Navigation guidance coming up. Pausing for a moment.")
                TripEngine.HOLD_CAUTION -> t(lang, "단속 카메라나 어린이 보호구역 앞이라 잠시 쉬어요.", "Pausing near a speed camera or school zone.")
                else -> t(lang, "잠시 멈춤", "Paused")
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.warn.copy(alpha = 0.16f)).padding(18.dp)) {
                Text(msg, color = G.warn, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (parkedView && place?.model != null) {
                Column {
                    PlaceClip(
                        model = place.model,
                        name = place.name.pick(lang),
                        night = ui.night,
                        accent = accent,
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(ui.nowSentence ?: "", color = G.text, fontSize = 26.sp, lineHeight = 36.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            } else if (parkedView) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(ui.nowText ?: t(lang, "명소에 가까워지면 차가 먼저 알려 드려요.", "When we get close to a landmark, the car will tell you first."), color = G.text, fontSize = 28.sp, lineHeight = 42.sp)
                }
            } else {
                // 달리는 중: 글 대신 소리로. 지금 문장 한 줄만 크게
                Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        ui.nowSentence ?: t(lang, "운전 중에는 소리로 안내해요.", "While driving, the guide speaks."),
                        color = G.text, fontSize = 34.sp, lineHeight = 48.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(t(lang, "달리는 동안에는 영상과 긴 글을 띄우지 않아요", "No video or long text while moving"), color = G.muted, fontSize = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (ui.sentenceCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(ui.sentenceCount) { i ->
                    Box(Modifier.height(8.dp).weight(1f).clip(RoundedCornerShape(4.dp)).background(if (i <= ui.sentenceIndex) accent else G.surface2))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GhostButton(t(lang, "다시 듣기", "Replay"), Modifier.weight(1f)) { engine.onMediaCommand(app.gureuneun.guide.sdk.MediaKeys.Command.PREVIOUS) }
            GhostButton(if (TripEngine.HOLD_USER in ui.holds) t(lang, "계속", "Resume") else t(lang, "멈춤", "Pause"), Modifier.weight(1f)) { engine.togglePause() }
            GhostButton(t(lang, "다음 이야기", "Next"), Modifier.weight(1f)) { engine.onMediaCommand(app.gureuneun.guide.sdk.MediaKeys.Command.NEXT) }
            AskButton(ui.listening, accent, lang, Modifier.weight(1.25f)) { engine.ask() }
        }
        Text(t(lang, "운전대의 이전, 다음 버튼으로도 넘길 수 있어요. 창밖이 궁금하면 [물어보기]를 누르고 \"오른쪽에 보이는 거 뭐야?\" 하고 물어보세요", "Steering wheel prev/next buttons work too. Curious about something outside? Tap Ask and say \"What's that on the right?\""), color = G.muted, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SideColumn(
    ui: TripEngine.Ui,
    vehicle: VehicleBridge.State,
    ux: UxBridge.Ux,
    group: CompanionClient.State,
    parkedView: Boolean,
    accent: Color,
    engine: TripEngine,
) {
    val lang = ui.lang
    val sc = ui.scenario ?: return

    // 차 상태
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val seated = vehicle.seatedKeys.size
        when {
            vehicle.allBuckled -> Pill(t(lang, "벨트 ${vehicle.buckledCount}/$seated", "Belts ${vehicle.buckledCount}/$seated"), color = G.good.copy(alpha = 0.2f))
            ui.beltsConfirmed -> Pill(t(lang, "출발 전 벨트 확인함", "Belts checked"), color = G.good.copy(alpha = 0.2f))
            else -> Pill(t(lang, "벨트 ${vehicle.buckledCount}/$seated", "Belts ${vehicle.buckledCount}/$seated"))
        }
        Pill(t(lang, "기어 ${vehicle.gear}", "Gear ${vehicle.gear}"))
        if (vehicle.turnSignal != "OFF") Pill(t(lang, if (vehicle.turnSignal == "LEFT") "왼쪽 깜빡이" else "오른쪽 깜빡이", "Signal ${vehicle.turnSignal.lowercase()}"), color = G.warn.copy(alpha = 0.25f))
        if (ui.rain) Pill(t(lang, "비 오는 날 이야기", "Rain stories"), color = Color(0x335B8CFF))
        if (ui.night) Pill(t(lang, "밤 이야기", "Night stories"), color = Color(0x33A78BFA))
        if (ui.windowOpened) Pill(t(lang, "창문 열림, 닫기", "Window open, close"), color = Color(0x3300E5FF), onClick = { engine.closeWindows() })
        Pill(
            if (ui.kidMode) t(lang, "뒷자리 아이 모드 켬", "Kid mode on") else t(lang, "아이 모드 끔", "Kid mode off"),
            color = if (ui.kidMode) Color(0x33FFB86B) else G.surface2,
            onClick = { engine.toggleKidMode() },
        )
        if (ui.freshAir) Pill(t(lang, "바깥 공기 들이는 중", "Fresh air in"), color = Color(0x3300E5FF))
        if (ui.jam) Pill(t(lang, "막히는 길, 긴 이야기", "Traffic: longer stories"), color = G.warn.copy(alpha = 0.22f))
        if (ui.sunroofOpen) Pill(t(lang, "선루프 열림", "Sunroof open"), color = Color(0x33A78BFA))
        (ui.drivingMin ?: vehicle.drivingMin)?.takeIf { it > 0 }?.let { Pill(t(lang, "주행 ${Ko.minutes(it, "ko")}", "Driving ${Ko.minutes(it, "en")}"), color = if (it >= TripEngine.REST_AFTER_MIN) G.warn.copy(alpha = 0.22f) else G.surface2) }
        if (vehicle.socPct != null || vehicle.rangeKm != null) {
            val parts = listOfNotNull(vehicle.socPct?.let { "$it%" }, vehicle.rangeKm?.let { "${it.toInt()}km" }).joinToString(", ")
            Pill(t(lang, "배터리 $parts", "Battery $parts"), color = if ((vehicle.socPct ?: 100) <= TripEngine.LOW_SOC) G.bad.copy(alpha = 0.22f) else G.surface2)
        }
        ui.guideDistanceM?.let {
            Pill(t(lang, "다음 길 안내 ${it.toInt()}m", "Next turn ${it.toInt()}m") + if (ui.guideFromNavi) "" else t(lang, " (시연)", " (demo)"))
        }
        if (ux.restricted) Pill(t(lang, "주행 중 화면 제한", "Driving: screen limited"), color = G.bad.copy(alpha = 0.2f))
    }

    // 물어본 것과 답
    if (ui.asked > 0 && ui.heardText != null) {
        Panel(padding = 20.dp) {
            SectionTitle(t(lang, "방금 물어본 것", "You just asked"))
            Text("“${ui.heardText}”", color = G.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ui.answerTitle?.let { Text(t(lang, "답: $it", "Answer: $it"), color = accent, fontSize = 21.sp, modifier = Modifier.padding(top = 6.dp)) }
            // 긴 답 글은 차가 서 있을 때만
            if (parkedView) ui.answerText?.let { Text(it, color = G.muted, fontSize = 19.sp, lineHeight = 27.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }

    // 퀴즈 별과 보물 상자
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
                color = if (ui.treasure == TripEngine.Treasure.READY) Color(0xFFFFC94D) else G.muted, fontSize = 20.sp, lineHeight = 28.sp,
            )
            if (ui.quizSent > 0) Text(t(lang, "보낸 퀴즈 ${ui.quizSent}개", "${ui.quizSent} quizzes sent") + (ui.lastStarBy?.let { t(lang, ", 마지막 별 $it", ", last star: $it") } ?: ""), color = G.muted, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    // 지도
    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(t(lang, "오늘 코스", "Today's course"))
            Spacer(Modifier.weight(1f))
            ui.nextPlace?.let { p ->
                Text(
                    t(lang, "다음 ${p.name.pick(lang)}", "Next ${p.name.pick(lang)}") + (ui.nextDistanceM?.let { "  " + formatDist(it) } ?: ""),
                    color = G.text, fontSize = 20.sp,
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
                    color = G.muted, fontSize = 18.sp,
                )
            }
        }
    }

    // QR: 차가 서 있을 때만
    Panel(padding = 20.dp) {
        val joinUrl = group.joinUrl
        if (parkedView && joinUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                QrImage(joinUrl, 200.dp)
                Column(Modifier.weight(1f)) {
                    Text(t(lang, "함께 탄 사람은 QR을 찍어 주세요", "Passengers: scan to join"), color = G.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(t(lang, "앱을 깔지 않아도 이 여행의 그룹 페이지가 열려요. 차가 이야기를 시작하면 휴대폰에 영상과 3D 모형이 같이 떠요.", "No app needed. When the car tells a story, your phone shows the video and 3D model."), color = G.muted, fontSize = 19.sp, lineHeight = 27.sp)
                    ui.groupCode?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(t(lang, "그룹 번호 $it", "Group code $it"), color = accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        } else if (joinUrl == null) {
            Text(t(lang, "그룹 서버에 연결되면 QR이 떠요. 차 안 안내는 그대로 됩니다.", "The QR appears when the group server is reachable. The in-car guide still works."), color = G.muted, fontSize = 20.sp)
        } else {
            Text(t(lang, "달리는 동안에는 QR을 숨겨요. 차가 서면 다시 보여요.", "QR is hidden while driving. It comes back when parked."), color = G.muted, fontSize = 20.sp)
            ui.groupCode?.let { Text(t(lang, "그룹 번호 $it", "Group code $it"), color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        }
    }

    // 다른 차에 장소 공유 (예: 먼저 도착한 차가 주차장 공유)
    val shareables = sc.pickables.filter { it.kind == PlaceKind.PARKING || it.kind == PlaceKind.CAFE }
    if (shareables.isNotEmpty() && (group.cars.size > 1 || sc.cars > 1)) {
        Panel(padding = 20.dp) {
            SectionTitle(t(lang, "다른 차에 보내기", "Send to the other cars"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shareables.forEach { p -> Pill(p.name.pick(lang), color = G.surface2, onClick = { engine.sharePlace(p) }) }
            }
        }
    }

    // 시연 패널
    if (ui.demoMode) {
        Panel(padding = 20.dp) {
            SectionTitle(t(lang, "시연 주행 (에뮬레이터용)", "Demo drive (emulator)"))
            if (ui.routeLengthM > 0) {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(G.surface2)) {
                    Box(Modifier.fillMaxWidth((ui.progressM / ui.routeLengthM).toFloat().coerceIn(0f, 1f)).height(10.dp).background(accent))
                }
                Spacer(Modifier.height(12.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (ui.demoRunning) t(lang, "주행 멈춤", "Pause drive") else t(lang, "주행 시작", "Drive"), color = accent.copy(alpha = 0.3f), onClick = { if (ui.demoRunning) engine.demoPause() else engine.demoPlay() })
                Pill(t(lang, "빨리 감기 x${ui.demoFast}", "Fast x${ui.demoFast}"), onClick = { engine.demoFaster() })
                Pill(t(lang, "다음 명소 앞으로", "Skip to next place"), onClick = { engine.demoJumpToNext() })
                Pill(if (ui.followGear) t(lang, "기어 따라 주행: 켬", "Follow gear: on") else t(lang, "기어 따라 주행: 끔", "Follow gear: off"), onClick = { engine.toggleFollowGear() })
                Pill(if (ui.demoJam) t(lang, "막히는 길 흉내: 켬", "Traffic jam: on") else t(lang, "막히는 길 흉내: 끔", "Traffic jam: off"), onClick = { engine.setDemoJam(!ui.demoJam) })
            }
            // 마이크 입력이 없는 에뮬레이터에서 말로 묻기를 보여 주는 단추 (앱에 넣어 둔 녹음을 음성 인식 SDK 에 그대로 넣음)
            val qs = engine.demoQuestions[lang] ?: engine.demoQuestions.getValue("ko")
            val nextQ = qs[ui.demoQuestionIdx % qs.size]
            Spacer(Modifier.height(10.dp))
            Pill(t(lang, "녹음한 질문으로 묻기: “${nextQ.text}”", "Ask with a recording: “${nextQ.text}”"), color = accent.copy(alpha = 0.22f), onClick = { engine.askDemo() })
        }
    }
}

private fun formatDist(m: Double): String = if (m >= 1000) "%.1fkm".format(m / 1000) else "${m.toInt()}m"

/** 퀴즈 별. 모은 만큼 채운 별 */
@Composable
private fun Stars(have: Int, need: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(maxOf(need, have)) { i ->
            Text(if (i < have) "★" else "☆", color = if (i < have) Color(0xFFFFC94D) else G.line, fontSize = 34.sp)
        }
    }
}

/** [물어보기] 버튼. 듣는 동안에는 색이 차고 마이크가 숨 쉬듯 커졌다 작아짐 */
@Composable
private fun AskButton(listening: Boolean, accent: Color, lang: String, modifier: Modifier, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val scale by pulse.animateFloat(1f, if (listening) 1.18f else 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "micScale")
    val ink = if (listening) Color(0xFF06121A) else G.text
    Box(
        modifier.height(88.dp).clip(RoundedCornerShape(28.dp)).background(if (listening) accent else accent.copy(alpha = 0.26f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MicGlyph(if (listening) ink else accent, Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale })
            Text(if (listening) t(lang, "듣는 중", "Listening") else t(lang, "물어보기", "Ask"), color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
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

/** 듣는 중이면 소리 크기 막대와 알아듣는 중인 글자를, 다 들으면 알아들은 문장을 화면 아래에 잠깐 띄움 */
@Composable
private fun Hearing(ui: TripEngine.Ui, partial: String, level: Double, accent: Color) {
    var showHeard by remember { mutableStateOf(false) }
    LaunchedEffect(ui.heardSeq) {
        if (ui.heardSeq > 0) {
            showHeard = true
            delay(5000)
            showHeard = false
        }
    }
    val visible = ui.listening || (showHeard && ui.heardText != null)
    Box(Modifier.fillMaxSize().padding(bottom = 150.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.widthIn(max = 1200.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xF2101820))
                    .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(36.dp)).padding(horizontal = 34.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LevelBars(if (ui.listening) level else 0.0, accent, ui.listening)
                Column {
                    Text(if (ui.listening) t(ui.lang, "듣고 있어요", "Listening") else t(ui.lang, "이렇게 들었어요", "I heard"), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val shown = if (ui.listening) partial.ifBlank { t(ui.lang, "창밖에 보이는 걸 물어보세요", "Ask about anything outside") } else "“${ui.heardText ?: ""}”"
                    Text(shown, color = G.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 소리 크기 막대 다섯 개. 음성 인식 엔진이 알려 주는 소리 크기(rms)에 맞춰 움직임 */
@Composable
private fun LevelBars(level: Double, accent: Color, live: Boolean) {
    val norm = ((level + 2.0) / 12.0).coerceIn(0.0, 1.0).toFloat()
    val amp by animateFloatAsState(if (live) maxOf(norm, 0.12f) else 0.05f, tween(120), label = "level")
    val wave = rememberInfiniteTransition(label = "bars")
    val phase by wave.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.size(88.dp, 60.dp)) {
        val n = 5
        val bw = size.width / (n * 2f - 1f)
        for (i in 0 until n) {
            val wobble = 0.4f + 0.6f * ((kotlin.math.sin(phase + i * 1.3f) + 1f) / 2f)
            val bh = size.height * (0.14f + 0.86f * amp * wobble)
            drawRoundRect(accent, topLeft = Offset(i * bw * 2f, (size.height - bh) / 2f), size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2f))
        }
    }
}

/** 차가 먼저 권하는 것: 쉬어 가기, 충전, 보물 상자, 하늘 열기. [예]를 눌러야만 움직임 */
@Composable
private fun OfferDialog(o: TripEngine.Offer, engine: TripEngine) {
    val color = when (o.kind) {
        TripEngine.OfferKind.TREASURE -> Color(0xFFFFC94D)
        TripEngine.OfferKind.SUNROOF -> Color(0xFFA78BFA)
        TripEngine.OfferKind.CHARGE -> G.good
        TripEngine.OfferKind.REST -> G.sky
    }
    Box(Modifier.fillMaxSize().background(Color(0xAA05080C)), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 980.dp), accent = color, padding = 36.dp) {
            Text(o.title, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(o.body, color = G.text, fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton(o.yes, Modifier.weight(1f), color = color) { engine.answerOffer(true) }
                BigButton(o.no, Modifier.weight(1f), color = G.surface2, textColor = G.text) { engine.answerOffer(false) }
            }
        }
    }
}

@Composable
private fun RollCall(ui: TripEngine.Ui, vehicle: VehicleBridge.State, engine: TripEngine) {
    val lang = ui.lang
    Box(Modifier.fillMaxSize().background(Color(0xCC05080C)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 980.dp), padding = 40.dp) {
            Text(t(lang, "출발 전 좌석 점호", "Seat roll call"), color = G.sky, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(t(lang, "모두 안전벨트를 매면 출발 안내를 시작해요", "Buckle up and the trip begins"), color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
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
                            Text(names[key] ?: key, color = G.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    !seated -> t(lang, "빈자리", "Empty")
                                    buckled -> t(lang, "벨트 맸어요", "Buckled")
                                    else -> t(lang, "벨트를 매 주세요", "Please buckle up")
                                },
                                color = if (buckled) G.good else if (seated) G.warn else G.muted, fontSize = 22.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton(t(lang, "모두 맸어요, 출발", "All buckled, let's go"), Modifier.weight(1f), height = 104.dp) { engine.completeRollcall() }
            }
            Text(
                t(lang, "차량 안전벨트 신호가 오면 자동으로 넘어가요", "Moves on automatically when the car reports all belts buckled"),
                color = G.muted, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PickDialog(p: TripEngine.Pick, lang: String, engine: TripEngine) {
    Box(Modifier.fillMaxSize().background(Color(0xAA05080C)), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 900.dp), accent = G.sky, padding = 36.dp) {
            Text(
                if (p.fromCar) t(lang, "${p.by}가 보낸 장소", "Sent from ${p.by}") else t(lang, "${p.by}님이 고른 곳", "${p.by} picked a place"),
                color = G.sky, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
            Text(p.name, color = G.text, fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 58.sp)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton(t(lang, "목적지로 안내", "Navigate there"), Modifier.weight(1f)) { engine.answerPick("destination") }
                BigButton(t(lang, "경유지로 추가", "Add as stop"), Modifier.weight(1f), color = G.surface2, textColor = G.text) { engine.answerPick("waypoint") }
            }
            Spacer(Modifier.height(12.dp))
            GhostButton(t(lang, "나중에", "Later"), Modifier.fillMaxWidth(), height = 76.dp) { engine.answerPick("later") }
        }
    }
}

@Composable
private fun WindowDialog(name: String, lang: String, engine: TripEngine) {
    Box(Modifier.fillMaxSize().background(Color(0xAA05080C)), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 900.dp), accent = Color(0xFF00E5FF), padding = 36.dp) {
            Text(name, color = Color(0xFF00E5FF), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(t(lang, "창문을 한 뼘 내려 파도 소리를 들여 볼까요?", "Lower the windows a little to hear the waves?"), color = G.text, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
            Text(t(lang, "차가 서 있을 때만 움직여요", "Windows move only while parked"), color = G.muted, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton(t(lang, "창문 내리기", "Lower windows"), Modifier.weight(1f), color = Color(0xFF00E5FF)) { engine.answerWindow(true) }
                BigButton(t(lang, "괜찮아요", "No thanks"), Modifier.weight(1f), color = G.surface2, textColor = G.text) { engine.answerWindow(false) }
            }
        }
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
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF0222B36)).padding(horizontal = 30.dp, vertical = 20.dp)) {
                Text(ui.toast ?: "", color = G.text, fontSize = 24.sp)
            }
        }
    }
}
