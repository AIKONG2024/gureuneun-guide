package app.gureuneun.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.GuideApp
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.sdk.GLog
import app.gureuneun.guide.sdk.VehicleBridge

/**
 * SDK 연결 상태를 한눈에 보고 하나씩 눌러 확인하는 점검 화면 (PT 시연용).
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
        app.navi.errors.collect { f -> naviErr = f.code + (f.what?.let { " ($it)" } ?: "") }
    }
    var demoCmds by remember { mutableStateOf(app.demoCommands) }

    // 차를 움직이는 단추: P 로 확인되고 주행 규제가 없을 때만
    val canMove = v.parked && !u.restricted
    val moveWhy = "차가 P 로 서 있고 주행 규제가 없을 때만 동작 (지금 기어 ${v.gear}, 규제 ${u.restricted})"
    val canUse = !u.restricted
    val useWhy = "주행 규제 중에는 동작하지 않음"

    Column(
        Modifier.fillMaxSize().background(G.bg).padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            Text("SDK 점검", color = G.text, fontSize = 40.sp)
            Spacer(Modifier.weight(1f))
            Pill("돌아가기", onClick = onBack)
        }
        Line("Vehicle", "연결 ${v.connected}, 기어 ${v.gear} (정차 ${v.parked}), 방향지시등 ${v.turnSignal}, 와이퍼 ${v.wiperOn}, 전조등 ${v.lowBeamOn}, 착석 ${v.occupied}, 벨트 ${v.belts}, 창문 ${v.windows}, 조명 색 ${v.ambientColor?.let { "%06X".format(it and 0xFFFFFF) }} 밝기 ${v.ambientBrightness}")
        Line("Vehicle 주행, 에너지", "이번 주행 ${v.drivingMin ?: "-"}분, 동력원 ${v.powertrain}, 배터리 ${v.socPct ?: "-"}%, 연료 ${v.fuelPct ?: "-"}%, 갈 수 있는 거리 ${v.rangeKm?.let { "%.1f".format(it) } ?: "-"}km")
        Line("Vehicle 공조, 글로브 박스, 선루프", "내기 순환 ${v.recirc}, 바람 ${v.fanSpeed}/${v.fanMax}, 온도 ${v.tempC}℃ (${v.tempMin}~${v.tempMax}), 글로브 박스 잠금 지원 ${v.gloveSupported} 잠김 ${v.gloveLocked}, 선루프 ${v.sunroof} 위치 ${v.sunroofPos}")
        Line("Vehicle 되돌리기, 오류", "앱이 바꾼 차 상태 기록 ${if (app.vehicle.hasChanges) "있음" else "없음"}, 마지막 오류 ${v.lastError ?: "-"}")
        Line("NaviHelper", "연결 ${n.connected}, 상태 ${n.status}, 다음 안내 ${n.tbtDistanceM}m ${n.tbtType ?: ""} ${n.tbtText ?: ""}, 남은 거리 ${n.remainDistanceM}, 목적지 ${n.destinationName}, 경유지 ${n.waypointCount ?: "-"}곳, 마지막 오류 ${naviErr ?: n.lastError ?: "-"}")
        Line("NaviHelper 길 상황", "제한 속도 ${n.speedLimit}, 막히는 구간 ${n.jamDistM}m ${n.jamTimeS}초, 단속 카메라 ${n.cameraAheadM}m, 주의 지점 ${n.cautionType ?: "-"} ${n.cautionAheadM}m")
        Line("TextToSpeech, 오디오", "엔진 $eng, 통화 중 $inCall, 전화 울림 $ringing, 다른 앱의 길 안내 음성 $navVoice")
        Line("SpeechToText", "상태 $stt, 듣는 중 글자 [$sttPartial], 마지막 오류 ${sttErr ?: "-"}, 마이크 문제 ${mic ?: "-"}")
        Line("FusedLocation", "지원 $sup, 위치 ${fix?.pos}, 근접 알림 ${reg}곳, 마지막 오류 ${locErr ?: "-"}")
        Line("CarUxRestrictions", "확인 ${u.known}, DO 필요 ${u.requiresDO}, 영상 제한 ${u.noVideo}, 글 제한 ${u.limitText} (최대 ${u.maxStringLength ?: "-"}자), 규제로 봄 ${u.restricted}")
        Line("그룹 서버", "${g.baseUrl}, 연결 ${g.online}, 그룹 ${g.code}, 휴대폰 ${g.phones}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 시연 영상용 adb 명령(--es demo ...)을 받을지. 출시 빌드 기본은 꺼짐
            Pill(
                if (demoCmds) "시연 명령 받기: 켜짐" else "시연 명령 받기: 꺼짐",
                color = if (demoCmds) G.warn else G.surface2,
                textColor = if (demoCmds) G.bg else G.text,
                onClick = {
                    demoCmds = !demoCmds
                    app.demoCommands = demoCmds
                    GLog.i("Diag", if (demoCmds) "시연 명령 받기 켬" else "시연 명령 받기 끔")
                },
            )
            Act("음성 한국어", canUse, useWhy) { app.speech.speak("잠시 후 오른쪽에 첨성대가 보여요.", "ko") { } }
            Act("음성 영어", canUse, useWhy) { app.speech.speak("On your right is the 63 Building.", "en") { } }
            Act("조명 바다색", canUse, useWhy) { app.vehicle.setAmbient(0xFF00B8D9.toInt(), 85) }
            Act("조명 노을색", canUse, useWhy) { app.vehicle.setAmbient(0xFFFF8A3D.toInt(), 85) }
            Act("조명 별빛", canUse, useWhy) { app.vehicle.setAmbient(0xFF6C5CE7.toInt(), 60) }
            Act("조명 흰색", canUse, useWhy) { app.vehicle.setAmbient(0xFFFFFFFF.toInt(), 70) }
            Act("앞 창문 2", canMove, moveWhy) { app.vehicle.setWindows(2, VehicleBridge.FRONT_WINDOWS) }
            Act("앞 창문 0", canMove, moveWhy) { app.vehicle.setWindows(0, VehicleBridge.FRONT_WINDOWS) }
            Act("내비 첨성대", canUse, useWhy) { app.navi.routeTo("첨성대", LatLng(35.83470, 129.21898)) }
            Act("내비 취소", canUse, useWhy) { app.navi.cancelRoute() }
            Act("효과음", canUse, useWhy) { app.engine.sound.chime() }
            Act("파도 소리", canUse, useWhy) { app.engine.sound.waves(8) }
            Act("듣기 시험", canUse, useWhy) { app.listen.listen("ko", { GLog.i("STT", "듣기 시험 결과: $it") }, { GLog.w("STT", "듣기 시험 실패: $it") }) }
            Act("바깥 공기", canMove, moveWhy) { app.vehicle.setRecirculation(false) }
            Act("내기 순환", canMove, moveWhy) { app.vehicle.setRecirculation(true) }
            Act("온도 -1", canMove, moveWhy) { app.vehicle.nudgeTemperature(-1f) }
            Act("온도 +1", canMove, moveWhy) { app.vehicle.nudgeTemperature(1f) }
            Act("글로브 박스 풀기", canMove, moveWhy) { app.vehicle.setGloveBoxLock(false) { ok -> GLog.i("Diag", "글로브 박스 풀기 ${if (ok) "됨" else "안 됨"}") } }
            Act("글로브 박스 잠금", canMove, moveWhy) { app.vehicle.setGloveBoxLock(true) { ok -> GLog.i("Diag", "글로브 박스 잠금 ${if (ok) "됨" else "안 됨"}") } }
            Act("바꾼 것 되돌리기", canMove, moveWhy) { app.vehicle.restoreAll { ok -> GLog.i("Diag", if (ok) "되돌리기 끝" else "되돌리기: 아직 남은 것 있음") } }
            Act("충전소 화면", canUse, useWhy) { app.navi.openChargers() }
            Act("주유소 찾기", canUse, useWhy) { app.navi.openGasStations() }
            Act("휴게소 검색", canUse, useWhy) { app.navi.searchNearby("휴게소") }
            Act("위치 차단 안내", canUse, useWhy) { app.location.showBlockedPopup() }
            Act("서버 주소 10.0.2.2", canUse, useWhy) { app.engine.companion.setBaseUrl("http://10.0.2.2:8787") }
        }
        logs.takeLast(60).reversed().forEach { Text(it, color = G.muted, fontSize = 16.sp) }
    }
}

/** 조건이 맞을 때만 동작하는 단추. 안 맞으면 흐리게 보이고, 누르면 까닭을 로그에 남김 */
@Composable
private fun Act(label: String, allowed: Boolean, why: String, action: () -> Unit) {
    Pill(
        label,
        color = if (allowed) G.surface2 else G.surface,
        textColor = if (allowed) G.text else G.muted,
        onClick = { if (allowed) action() else GLog.w("Diag", "$label 안 함: $why") },
    )
}

@Composable
private fun Line(name: String, value: String) {
    Panel(padding = 18.dp) {
        Text(name, color = G.sky, fontSize = 20.sp)
        Text(value, color = G.text, fontSize = 19.sp)
    }
}
