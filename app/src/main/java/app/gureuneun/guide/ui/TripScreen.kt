package app.gureuneun.guide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        val hold = ui.holds.firstOrNull()
        if (hold != null) {
            val msg = when (hold) {
                TripEngine.HOLD_TURN -> t(lang, "방향지시등이 켜져 잠시 멈췄어요. 불이 꺼지면 이어서 들려 드려요.", "Turn signal on. I'll continue when it's off.")
                TripEngine.HOLD_GUIDE -> t(lang, "곧 길 안내가 나와서 잠시 쉬어요.", "Navigation guidance coming up. Pausing for a moment.")
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
                    PlaceViewer(
                        model = place.model,
                        name = place.name.pick(lang),
                        caption = ui.nowSentence,
                        lang = lang,
                        rain = ui.rain,
                        night = ui.night,
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
        }
        Text(t(lang, "운전대의 이전, 다음 버튼으로도 넘길 수 있어요", "Steering wheel prev/next buttons work too"), color = G.muted, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
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
        ui.guideDistanceM?.let {
            Pill(t(lang, "다음 길 안내 ${it.toInt()}m", "Next turn ${it.toInt()}m") + if (ui.guideFromNavi) "" else t(lang, " (시연)", " (demo)"))
        }
        if (ux.restricted) Pill(t(lang, "주행 중 화면 제한", "Driving: screen limited"), color = G.bad.copy(alpha = 0.2f))
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
            }
        }
    }
}

private fun formatDist(m: Double): String = if (m >= 1000) "%.1fkm".format(m / 1000) else "${m.toInt()}m"

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
