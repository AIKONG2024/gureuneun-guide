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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.GuideApp
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.sdk.GLog

/** SDK 연결 상태를 한눈에 보고 하나씩 눌러 확인하는 점검 화면 (PT 시연용) */
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
    val g by app.engine.companion.state.collectAsState()
    val logs by GLog.lines.collectAsState()
    val stt by app.listen.status.collectAsState()
    val sttPartial by app.listen.partial.collectAsState()
    val sttErr by app.listen.lastError.collectAsState()
    Column(
        Modifier.fillMaxSize().background(G.bg).padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            Text("SDK 점검", color = G.text, fontSize = 40.sp)
            Spacer(Modifier.weight(1f))
            Pill("돌아가기", onClick = onBack)
        }
        Line("Vehicle", "연결 ${v.connected}, 기어 ${v.gear}, 방향지시등 ${v.turnSignal}, 와이퍼 ${v.wiperOn}, 전조등 ${v.lowBeamOn}, 착석 ${v.occupied}, 벨트 ${v.belts}, 창문 ${v.windows}, 조명 색 ${v.ambientColor?.let { "%06X".format(it and 0xFFFFFF) }} 밝기 ${v.ambientBrightness}")
        Line("Vehicle 주행, 배터리", "이번 주행 ${v.drivingMin}분, 배터리 ${v.socPct}%, 갈 수 있는 거리 ${v.rangeKm?.let { "%.1f".format(it) }}km")
        Line("Vehicle 공조, 글로브 박스, 선루프", "내기 순환 ${v.recirc}, 바람 ${v.fanSpeed}/${v.fanMax}, 온도 ${v.tempC}℃ (${v.tempMin}~${v.tempMax}), 글로브 박스 잠금 지원 ${v.gloveSupported} 잠김 ${v.gloveLocked}, 선루프 ${v.sunroof} 위치 ${v.sunroofPos}")
        Line("NaviHelper", "연결 ${n.connected}, 상태 ${n.status}, 다음 안내 ${n.tbtDistanceM}m ${n.tbtType ?: ""} ${n.tbtText ?: ""}, 남은 거리 ${n.remainDistanceM}, 목적지 ${n.destinationName}")
        Line("NaviHelper 길 상황", "제한 속도 ${n.speedLimit}, 막히는 구간 ${n.jamDistM}m ${n.jamTimeS}초, 단속 카메라 ${n.cameraAheadM}m, 주의 지점 ${n.cautionType ?: "-"} ${n.cautionAheadM}m")
        Line("TextToSpeech", "엔진 $eng")
        Line("SpeechToText", "상태 $stt, 듣는 중 글자 [$sttPartial], 마지막 오류 ${sttErr ?: "-"}")
        Line("FusedLocation", "지원 $sup, 위치 ${fix?.pos}, 근접 알림 ${reg}곳")
        Line("CarUxRestrictions", "확인 ${u.known}, DO 필요 ${u.requiresDO}, 영상 제한 ${u.noVideo}, 글 제한 ${u.limitText}")
        Line("그룹 서버", "${g.baseUrl}, 연결 ${g.online}, 그룹 ${g.code}, 휴대폰 ${g.phones}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("음성 한국어", onClick = { app.speech.speak("잠시 후 오른쪽에 첨성대가 보여요.", "ko") { } })
            Pill("음성 영어", onClick = { app.speech.speak("On your right is the 63 Building.", "en") { } })
            Pill("조명 바다색", onClick = { app.vehicle.setAmbient(0xFF00B8D9.toInt(), 85) })
            Pill("조명 노을색", onClick = { app.vehicle.setAmbient(0xFFFF8A3D.toInt(), 85) })
            Pill("조명 별빛", onClick = { app.vehicle.setAmbient(0xFF6C5CE7.toInt(), 60) })
            Pill("조명 흰색", onClick = { app.vehicle.setAmbient(0xFFFFFFFF.toInt(), 70) })
            Pill("창문 3", onClick = { app.vehicle.setWindows(3) })
            Pill("창문 0", onClick = { app.vehicle.setWindows(0) })
            Pill("내비 첨성대", onClick = { app.navi.routeTo("첨성대", LatLng(35.83470, 129.21898)) })
            Pill("내비 취소", onClick = { app.navi.cancelRoute() })
            Pill("효과음", onClick = { app.engine.sound.chime() })
            Pill("파도 소리", onClick = { app.engine.sound.waves(8) })
            Pill("듣기 시험", onClick = { app.listen.listen("ko", { GLog.i("STT", "듣기 시험 결과: $it") }, { GLog.w("STT", "듣기 시험 실패: $it") }) })
            Pill("바깥 공기", onClick = { app.vehicle.setRecirculation(false) })
            Pill("내기 순환", onClick = { app.vehicle.setRecirculation(true) })
            Pill("온도 -1", onClick = { app.vehicle.nudgeTemperature(-1f) })
            Pill("온도 +1", onClick = { app.vehicle.nudgeTemperature(1f) })
            Pill("글로브 박스 풀기", onClick = { app.vehicle.setGloveBoxLock(false) })
            Pill("글로브 박스 잠금", onClick = { app.vehicle.setGloveBoxLock(true) })
            Pill("충전소 화면", onClick = { app.navi.openChargers() })
            Pill("휴게소 검색", onClick = { app.navi.searchNearby("휴게소") })
            Pill("서버 주소 10.0.2.2", onClick = { app.engine.companion.setBaseUrl("http://10.0.2.2:8787") })
        }
        logs.takeLast(60).reversed().forEach { Text(it, color = G.muted, fontSize = 16.sp) }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Panel(padding = 18.dp) {
        Text(name, color = G.sky, fontSize = 20.sp)
        Text(value, color = G.text, fontSize = 19.sp)
    }
}
