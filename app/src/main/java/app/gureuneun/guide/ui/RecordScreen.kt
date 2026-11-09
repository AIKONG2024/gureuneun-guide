package app.gureuneun.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.TripRecorder
import app.gureuneun.guide.net.CompanionClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 하루 기록 카드 */
@Composable
fun RecordScreen(ui: TripEngine.Ui, recorder: TripRecorder, group: CompanionClient.State, onHome: () -> Unit) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    val accent = Color(ui.accent)
    val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
    val minutes = ((if (recorder.endedAt > 0) recorder.endedAt else System.currentTimeMillis()) - recorder.startedAt) / 60000
    val stories = recorder.events.count { it.type == "story" }
    val passed = recorder.events.filter { it.type == "pass" && sc.findPlace(it.placeId)?.kind?.name != "CITY" }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1A1F2B), G.bg)))) {
        Row(Modifier.fillMaxSize().padding(36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(t(lang, "하루 기록 카드", "Today's trip card"), color = G.sky, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(sc.title.pick(lang), color = G.text, fontSize = 52.sp, fontWeight = FontWeight.Black)
                Text(SimpleDateFormat(if (lang == "en") "MMM d, yyyy" else "yyyy년 M월 d일", if (lang == "en") Locale.US else Locale.KOREA).format(Date(recorder.startedAt)), color = G.muted, fontSize = 22.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat(t(lang, "달린 거리", "Distance"), "%.1f km".format(recorder.distanceM / 1000), Modifier.weight(1f))
                    Stat(t(lang, "여행 시간", "Time"), t(lang, "${minutes}분", "${minutes} min"), Modifier.weight(1f))
                    Stat(t(lang, "지난 명소", "Places"), t(lang, "${passed.size}곳", "${passed.size}"), Modifier.weight(1f))
                    Stat(t(lang, "들은 이야기", "Stories"), t(lang, "${stories}편", "$stories"), Modifier.weight(1f))
                }
                Panel(padding = 24.dp) {
                    SectionTitle(t(lang, "오늘 있었던 일", "What happened today"))
                    recorder.events.filter { it.type in setOf("depart", "pass", "story", "pick", "share", "window", "rain", "night", "arrive", "end") }.forEach { e ->
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt.format(Date(e.time)), color = G.muted, fontSize = 20.sp, modifier = Modifier.width(90.dp))
                            Text(describe(e, lang), color = G.text, fontSize = 22.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel(padding = 18.dp) {
                    SectionTitle(t(lang, "지나온 길", "Our route"))
                    val route = recorder.route.map { it.second }.ifEmpty { sc.route }
                    RouteMap(route = route, places = sc.places.filter { it.triggers }, entered = ui.entered, car = route.lastOrNull(), heading = 0.0, accent = accent, modifier = Modifier.fillMaxWidth().height(320.dp))
                }
                Panel(padding = 22.dp) {
                    val url = group.joinUrl?.let { "$it/record" }
                    if (url != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            QrImage(url, 190.dp)
                            Column(Modifier.weight(1f)) {
                                Text(t(lang, "기록을 휴대폰으로 가져가세요", "Take the record with you"), color = G.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                Text(t(lang, "사진을 올리면 찍은 시각에 맞춰 길 위에 붙고, 빌트인 캠 영상을 올리면 명소를 지난 장면만 모아 짧은 영상으로 만들어요.", "Upload photos to pin them on the route by time. Upload built-in cam video to get a short highlight of the places."), color = G.muted, fontSize = 19.sp, lineHeight = 27.sp)
                            }
                        }
                    } else {
                        Text(t(lang, "그룹 서버에 연결되면 기록 페이지 QR이 떠요. 기록은 차에도 저장해 두었어요.", "The record QR appears when the group server is reachable. The record is also saved in the car."), color = G.muted, fontSize = 20.sp)
                    }
                }
                BigButton(t(lang, "처음 화면으로", "Back to start"), Modifier.fillMaxWidth(), onClick = onHome)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun describe(e: TripRecorder.Event, lang: String): String = when (e.type) {
    "depart" -> t(lang, "출발", "Departed")
    "pass" -> t(lang, "${e.title} 지남", "Passed ${e.title}")
    "story" -> t(lang, "${e.title} 이야기 들음 (${lenWord(e.detail, lang)})", "Heard about ${e.title} (${lenWord(e.detail, lang)})")
    "pick" -> t(lang, "${e.detail}님이 고른 ${e.title} 안내", "Navigated to ${e.title}, picked by ${e.detail}")
    "share" -> t(lang, "${e.title} 다른 차에 보냄", "Sent ${e.title} to the other cars")
    "window" -> t(lang, "${e.title}에서 창문 열고 바다 소리 들음", "Opened the windows at ${e.title}")
    "rain" -> t(lang, "비가 와서 비 오는 날 이야기로 바꿈", "Switched to rain stories")
    "night" -> t(lang, "밤이 되어 밤 이야기로 바꿈", "Switched to night stories")
    "arrive" -> t(lang, "목적지 도착", "Arrived")
    "end" -> t(lang, "여행 끝", "Trip ended")
    else -> e.title
}

private fun lenWord(d: String, lang: String) = when (d) {
    "SHORT" -> t(lang, "짧게", "short")
    "MEDIUM" -> t(lang, "보통", "medium")
    "LONG" -> t(lang, "길게", "long")
    else -> d
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Panel(modifier, padding = 20.dp) {
        Text(label, color = G.muted, fontSize = 18.sp)
        Text(value, color = G.text, fontSize = 34.sp, fontWeight = FontWeight.Black)
    }
}
