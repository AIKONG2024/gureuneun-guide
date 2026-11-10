package app.gureuneun.guide.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.GuideApp
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.GLog
import app.gureuneun.guide.sdk.ListenBridge
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.VehicleBridge

/**
 * SDK 연결 상태를 한눈에 보고 하나씩 눌러 확인하는 점검 화면 (PT 시연용).
 * 왼쪽은 SDK 별 상태 카드, 오른쪽은 시연 설정과 시험 단추, 로그.
 * 차를 움직이는 단추(창문, 글로브 박스, 공조, 되돌리기)는 차가 P 로 서 있고 주행 규제가 없을 때만 동작함.
 * 나머지 단추도 주행 규제 중에는 동작하지 않음 (점검 화면은 서 있을 때 쓰는 화면).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagScreen(app: GuideApp, onBack: () -> Unit) {
    val v by app.vehicle.state.collectAsState()
    val n by app.navi.state.collectAsState()
    val u by app.ux.state.collectAsState()
    val eng by app.speech.engine.collectAsState()
    val fix by app.location.lastFix.collectAsState()
    val sup by app.location.supported.collectAsState()
    val reg by app.location.registered.collectAsState()
    val locErr by app.location.lastError.collectAsState()
    val g by app.engine.companion.state.collectAsState()
    val ui by app.engine.ui.collectAsState()
    val logs by GLog.lines.collectAsState()
    val stt by app.listen.status.collectAsState()
    val sttPartial by app.listen.partial.collectAsState()
    val sttErr by app.listen.lastError.collectAsState()
    val mic by app.listen.problem.collectAsState()
    val inCall by app.audio.inCall.collectAsState()
    val ringing by app.audio.ringing.collectAsState()
    val navVoice by app.audio.navSpeaking.collectAsState()
    var naviErr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        app.navi.errors.collect { f -> naviErr = f.code + (f.what?.let { " (${it})" } ?: "") }
    }
    var demoCmds by remember { mutableStateOf(app.demoCommands) }
    /** 조건이 안 맞아 하지 않은 단추의 까닭 (단추 묶음 아래에 잠깐 보여 줌) */
    var refused by remember { mutableStateOf<String?>(null) }

    // 차를 움직이는 단추: P 로 확인되고 주행 규제가 없을 때만
    val canMove = v.parked && !u.restricted
    val moveWhy = "차가 P 로 서 있고 주행 규제가 없을 때만 동작 (지금 기어 ${v.gear}, 규제 ${u.restricted})"
    val canUse = !u.restricted
    val useWhy = "주행 규제 중에는 동작하지 않음"
    val act: @Composable (String, Boolean, String, Color?, () -> Unit) -> Unit = { label, allowed, why, swatch, action ->
        Act(label, allowed, swatch) {
            if (allowed) {
                refused = null
                action()
            } else {
                GLog.w("Diag", "${label} 안 함: ${why}")
                refused = "${label} 안 함. ${why}"
            }
        }
    }

    val sttOk = when (stt) {
        ListenBridge.Status.READY, ListenBridge.Status.LISTENING -> true
        ListenBridge.Status.ERROR -> false
        ListenBridge.Status.OFF -> null
    }
    val sdks: List<Pair<String, Boolean?>> = listOf(
        "Vehicle" to v.connected,
        "NaviHelper" to n.connected,
        "TextToSpeech" to (eng != SpeechBridge.Engine.NONE),
        "SpeechToText" to sttOk,
        "FusedLocation" to sup,
        "CarUxRestrictions" to u.known,
    )

    val status: @Composable () -> Unit = {
        SdkCard(
            "Vehicle", v.connected,
            listOf(
                "차 상태" to listOf(
                    "기어" to v.gear, "정차" to yn(v.parked), "방향지시등" to v.turnSignal, "와이퍼" to yn(v.wiperOn), "전조등" to yn(v.lowBeamOn),
                    "착석" to seatsOn(v.occupied), "벨트" to seatsOn(v.belts), "창문" to windowsOf(v.windows),
                    "조명 색" to (v.ambientColor?.let { "#%06X".format(it and 0xFFFFFF) } ?: "-"), "밝기" to (v.ambientBrightness?.toString() ?: "-"),
                ),
                "주행과 에너지" to listOf(
                    "이번 주행" to (v.drivingMin?.let { "${it}분" } ?: "-"), "동력원" to v.powertrain,
                    "배터리" to (v.socPct?.let { "${it}%" } ?: "-"), "연료" to (v.fuelPct?.let { "${it}%" } ?: "-"),
                    "갈 수 있는 거리" to (v.rangeKm?.let { "%.1fkm".format(it) } ?: "-"),
                ),
                "공조, 글로브 박스, 선루프" to listOf(
                    "내기 순환" to yn(v.recirc), "바람" to "${v.fanSpeed ?: "-"}/${v.fanMax ?: "-"}",
                    "온도" to (v.tempC?.let { "${it}℃ (${v.tempMin ?: "-"}~${v.tempMax ?: "-"})" } ?: "-"),
                    "글로브 박스 잠금 지원" to yn(v.gloveSupported), "잠김" to yn(v.gloveLocked),
                    "선루프" to yn(v.sunroof), "위치" to (v.sunroofPos?.toString() ?: "-"),
                ),
                "되돌리기와 오류" to listOf(
                    "앱이 바꾼 차 상태" to (if (app.vehicle.hasChanges) "있음" else "없음"), "마지막 오류" to (v.lastError ?: "-"),
                ),
            ),
        )
        SdkCard(
            "NaviHelper", n.connected,
            listOf(
                "길 안내" to listOf(
                    "상태" to n.status,
                    "다음 안내" to (n.tbtDistanceM?.let { "${formatDist(it)} ${n.tbtType ?: ""} ${n.tbtText ?: ""}".trim() } ?: "-"),
                    "남은 거리" to (n.remainDistanceM?.let { formatDist(it.toDouble()) } ?: "-"),
                    "목적지" to (n.destinationName ?: "-"), "경유지" to (n.waypointCount?.let { "${it}곳" } ?: "-"),
                    "마지막 오류" to (naviErr ?: n.lastError ?: "-"),
                ),
                "길 상황" to listOf(
                    "제한 속도" to (n.speedLimit?.let { "${it}km/h" } ?: "-"),
                    "막히는 구간" to (n.jamDistM?.let { "${formatDist(it)} ${n.jamTimeS ?: 0}초" } ?: "-"),
                    "단속 카메라" to (n.cameraAheadM?.let { "${it}m" } ?: "-"),
                    "주의 지점" to (n.cautionType?.let { "${it} ${n.cautionAheadM ?: "-"}m" } ?: "-"),
                ),
            ),
        )
        SdkCard(
            "TextToSpeech, 오디오", eng != SpeechBridge.Engine.NONE,
            listOf(null to listOf("엔진" to eng.name, "통화 중" to yn(inCall), "전화 울림" to yn(ringing), "다른 앱의 길 안내 음성" to yn(navVoice))),
        )
        SdkCard(
            "SpeechToText", sttOk,
            listOf(null to listOf("상태" to stt.name, "듣는 중 글자" to sttPartial.ifBlank { "-" }, "마지막 오류" to (sttErr ?: "-"), "마이크 문제" to (mic ?: "-"))),
        )
        SdkCard(
            "FusedLocation", sup,
            listOf(
                null to listOf(
                    "지원" to yn(sup), "위치" to (fix?.pos?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "-"),
                    "근접 알림" to "${reg}곳", "마지막 오류" to (locErr ?: "-"),
                ),
            ),
        )
        SdkCard(
            "CarUxRestrictions", u.known,
            listOf(
                null to listOf(
                    "확인" to yn(u.known), "DO 필요" to yn(u.requiresDO), "영상 제한" to yn(u.noVideo),
                    "글 제한" to (if (u.limitText) "예 (최대 ${u.maxStringLength ?: "-"}자)" else "아니요"), "규제로 봄" to yn(u.restricted),
                ),
            ),
        )
        SdkCard(
            "그룹 서버", if (g.online) true else if (g.reachable) null else false,
            listOf(null to listOf("주소" to g.baseUrl, "연결" to yn(g.online), "그룹" to (g.code ?: "-"), "휴대폰" to "${g.phones}대")),
        )
    }

    val controls: @Composable () -> Unit = {
        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Eyebrow("시연 설정", Ic.Sliders)
            Spacer(Modifier.height(8.dp))
            // 시연 영상용 adb 명령(--es demo ...)을 받을지. 출시 빌드 기본은 꺼짐
            SwitchRow(
                Ic.Phone, "시연 명령 받기", if (demoCmds) "켜짐. adb 로 보낸 시연 명령을 받아요" else "꺼짐. 다른 앱이 보낸 명령으로 차가 움직이지 않게 막아요", demoCmds,
            ) {
                demoCmds = !demoCmds
                app.demoCommands = demoCmds
                GLog.i("Diag", if (demoCmds) "시연 명령 받기 켬" else "시연 명령 받기 끔")
            }
            Spacer(Modifier.height(18.dp))
            Text("위치", style = T.label, color = C.text3)
            Spacer(Modifier.height(10.dp))
            if (ui.phase == TripEngine.Phase.HOME) {
                Segmented(listOf("시연 경로", "실제 위치"), if (ui.demoMode) 0 else 1, Modifier.fillMaxWidth()) {
                    app.engine.setDemoMode(it == 0)
                    GLog.i("Diag", if (it == 0) "위치: 시연 경로" else "위치: 실제 위치")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ui.demoMode) "에뮬레이터용. 실제 도로 경로를 따라 위치를 흘려 넣고 근접 판정을 앱 안에서 해요" else "실차용. FusedLocation 근접 알림을 그대로 써요",
                    style = T.caption, color = C.text3,
                )
            } else {
                Text("여행 중에는 바꿀 수 없어요. 지금은 ${if (ui.demoMode) "시연 경로" else "실제 위치"}", style = T.bodyS, color = C.text2)
            }
        }

        ActGroup("음성과 소리", canUse, "주행 규제가 없을 때") {
            act("한국어로 읽기", canUse, useWhy, null) { app.speech.speak("잠시 후 오른쪽에 첨성대가 보여요.", "ko") { } }
            act("영어로 읽기", canUse, useWhy, null) { app.speech.speak("On your right is the 63 Building.", "en") { } }
            act("듣기 시험", canUse, useWhy, null) { app.listen.listen("ko", { GLog.i("STT", "듣기 시험 결과: ${it}") }, { GLog.w("STT", "듣기 시험 실패: ${it}") }) }
            act("효과음", canUse, useWhy, null) { app.engine.sound.chime() }
            act("파도 소리", canUse, useWhy, null) { app.engine.sound.waves(8) }
        }
        ActGroup("실내 조명", canUse, "주행 규제가 없을 때") {
            act("바다색", canUse, useWhy, Color(0xFF00B8D9)) { app.vehicle.setAmbient(0xFF00B8D9.toInt(), 85) }
            act("노을색", canUse, useWhy, Color(0xFFFF8A3D)) { app.vehicle.setAmbient(0xFFFF8A3D.toInt(), 85) }
            act("별빛", canUse, useWhy, Color(0xFF6C5CE7)) { app.vehicle.setAmbient(0xFF6C5CE7.toInt(), 60) }
            act("흰색", canUse, useWhy, Color.White) { app.vehicle.setAmbient(0xFFFFFFFF.toInt(), 70) }
        }
        ActGroup("창문, 공조, 글로브 박스", canMove, if (canMove) "P 단, 주행 규제 없음" else "P 단에서만 (지금 기어 ${v.gear})") {
            act("앞 창문 조금 열기", canMove, moveWhy, null) { app.vehicle.setWindows(2, VehicleBridge.FRONT_WINDOWS) }
            act("앞 창문 닫기", canMove, moveWhy, null) { app.vehicle.setWindows(0, VehicleBridge.FRONT_WINDOWS) }
            act("바깥 공기", canMove, moveWhy, null) { app.vehicle.setRecirculation(false) }
            act("내기 순환", canMove, moveWhy, null) { app.vehicle.setRecirculation(true) }
            act("온도 -1", canMove, moveWhy, null) { app.vehicle.nudgeTemperature(-1f) }
            act("온도 +1", canMove, moveWhy, null) { app.vehicle.nudgeTemperature(1f) }
            act("글로브 박스 풀기", canMove, moveWhy, null) { app.vehicle.setGloveBoxLock(false) { ok -> GLog.i("Diag", "글로브 박스 풀기 ${if (ok) "됨" else "안 됨"}") } }
            act("글로브 박스 잠금", canMove, moveWhy, null) { app.vehicle.setGloveBoxLock(true) { ok -> GLog.i("Diag", "글로브 박스 잠금 ${if (ok) "됨" else "안 됨"}") } }
            act("바꾼 것 되돌리기", canMove, moveWhy, null) { app.vehicle.restoreAll { ok -> GLog.i("Diag", if (ok) "되돌리기 끝" else "되돌리기: 아직 남은 것 있음") } }
        }
        ActGroup("내비게이션", canUse, "주행 규제가 없을 때") {
            act("첨성대로 길 안내", canUse, useWhy, null) { app.navi.routeTo("첨성대", LatLng(35.83470, 129.21898)) }
            act("길 안내 취소", canUse, useWhy, null) { app.navi.cancelRoute() }
            act("충전소 화면", canUse, useWhy, null) { app.navi.openChargers() }
            act("주유소 찾기", canUse, useWhy, null) { app.navi.openGasStations() }
            act("휴게소 검색", canUse, useWhy, null) { app.navi.searchNearby("휴게소") }
            act("위치 차단 안내", canUse, useWhy, null) { app.location.showBlockedPopup() }
        }
        ActGroup("그룹 서버", canUse, g.baseUrl) {
            act("서버 주소 10.0.2.2", canUse, useWhy, null) { app.engine.companion.setBaseUrl("http://10.0.2.2:8787") }
        }
        refused?.let {
            Row(
                Modifier.fillMaxWidth().background(C.warnSoft, androidx.compose.foundation.shape.RoundedCornerShape(S.chipRadius)).padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Ic.Info, Color(0xFFFFDCD5), 28.dp)
                Text(it, style = T.bodyS, color = Color(0xFFFFDCD5))
            }
        }

        Card(Modifier.fillMaxWidth(), padding = 28.dp) {
            Eyebrow("로그 (최근 60줄, 새것부터)", Ic.Clock)
            Spacer(Modifier.height(12.dp))
            val mono = T.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 20.sp, lineHeight = 28.sp)
            logs.takeLast(60).reversed().forEach { Text(it, style = mono, color = C.text2) }
        }
    }

    BrandBackground {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp)) {
            val wide = maxWidth > 1100.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Btn("돌아가기", icon = Ic.ChevronLeft, tone = Tone.Quiet, height = 76.dp, style = T.label, iconSize = 28.dp, onClick = onBack)
                    Column(Modifier.weight(1f)) {
                        Text("점검", style = T.titleL, color = C.text)
                        Text("Pleos SDK 연결 상태를 보고 하나씩 눌러 확인해요", style = T.bodyS, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val ok = sdks.count { it.second == true }
                    Tag("SDK ${ok}/${sdks.size} 연결", if (ok == sdks.size) Ic.Check else Ic.Info, bg = if (ok == sdks.size) C.accentSoft else C.raised, fg = if (ok == sdks.size) C.accent else C.text2)
                }
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) { status() }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) { controls() }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        status()
                        controls()
                    }
                }
            }
        }
    }
}

/** SDK 하나의 상태. 머리에 연결 점, 아래에 "이름 값" 묶음을 흐르듯 늘어놓음 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SdkCard(name: String, ok: Boolean?, sections: List<Pair<String?, List<Pair<String, String>>>>) {
    Card(Modifier.fillMaxWidth(), padding = 26.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusDot(ok, 16.dp)
            Text(name, style = T.bodyM.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = C.text, modifier = Modifier.weight(1f))
            Text(when (ok) { true -> "연결됨"; false -> "안 됨"; null -> "확인 중" }, style = T.caption, color = if (ok == false) Color(0xFFFFB4A6) else C.text3)
        }
        sections.forEach { (title, items) ->
            Spacer(Modifier.height(14.dp))
            if (title != null) {
                Text(title, style = T.caption, color = C.accent)
                Spacer(Modifier.height(4.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { (k, value) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = C.text3)) { append(k) }
                            append(" ")
                            append(value)
                        },
                        style = T.caption, color = C.text,
                    )
                }
            }
        }
    }
}

/** 시험 단추 묶음. 머리에 이 묶음이 지금 동작하는지 점으로 보여 줌 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActGroup(title: String, allowed: Boolean, rule: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), padding = 26.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = T.bodyM.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = C.text, modifier = Modifier.weight(1f))
            StatusDot(allowed)
            Text(rule, style = T.caption, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** 조건이 맞을 때만 동작하는 단추. 안 맞으면 흐리게 보이고, 누르면 까닭을 보여 주고 로그에 남김 */
@Composable
private fun Act(label: String, allowed: Boolean, swatch: Color?, onClick: () -> Unit) {
    Box(Modifier.graphicsLayer { alpha = if (allowed) 1f else 0.45f }) {
        Btn(label, height = S.touch, style = T.label, dot = swatch, onClick = onClick)
    }
}

private fun yn(b: Boolean?) = when (b) { true -> "예"; false -> "아니요"; null -> "-" }

private fun seatName(key: String) = when (key) {
    "ROW_1_LEFT" -> "운전석"
    "ROW_1_RIGHT" -> "조수석"
    "ROW_2_LEFT" -> "뒤 왼쪽"
    "ROW_2_RIGHT" -> "뒤 오른쪽"
    "ROW_2_CENTER" -> "뒤 가운데"
    else -> key
}

/** 켜진 좌석만 이름으로 */
private fun seatsOn(m: Map<String, Boolean>) =
    m.filterValues { it }.keys.sortedBy { SEAT_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.joinToString(", ") { seatName(it) }.ifBlank { if (m.isEmpty()) "-" else "없음" }

private val SEAT_ORDER = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")

private fun windowsOf(m: Map<String, Int>) =
    if (m.isEmpty()) "-" else m.entries.sortedBy { SEAT_ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }.joinToString(", ") { "${seatName(it.key)} ${it.value}" }

