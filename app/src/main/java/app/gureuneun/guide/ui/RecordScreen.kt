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
fun RecordScreen(
    ui: TripEngine.Ui,
    recorder: TripRecorder,
    group: CompanionClient.State,
    onShare: () -> Unit = {},
    onKeepLocal: () -> Unit = {},
    onCloseWindows: () -> Unit = {},
    onHome: () -> Unit,
) {
    val lang = ui.lang
    val sc = ui.scenario ?: return
    val accent = Color(ui.accent)
    val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
    val minutes = ((if (recorder.endedAt > 0) recorder.endedAt else System.currentTimeMillis()) - recorder.startedAt) / 60000
    val stories = recorder.events.count { it.type == "story" }
    val passed = recorder.events.filter { it.type == "pass" && sc.findPlace(it.placeId)?.kind?.name != "CITY" }
    // 말로 물어본 것은 진짜 물음만 셈 (차가 물은 것에 "응", "나중에"로 답한 것은 빼고)
    val asked = recorder.events.count { it.type == "ask" && !it.detail.startsWith("REPLY_") }
    val stars = recorder.events.filter { it.type == "quiz_ok" }.map { it.placeId }.distinct().size
    val quizzes = recorder.events.count { it.type == "quiz" }
    val treasure = recorder.events.any { it.type == "treasure" }

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
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat(t(lang, "말로 물어본 것", "Questions"), t(lang, "${asked}번", "$asked"), Modifier.weight(1f))
                    if (quizzes > 0 || sc.treasureStars > 0) {
                        // 맞힌 퀴즈 / 휴대폰으로 보낸 퀴즈
                        Stat(t(lang, "퀴즈 별", "Quiz stars"), "$stars/${maxOf(quizzes, stars)}", Modifier.weight(1f))
                        Stat(t(lang, "보물 상자", "Treasure"), if (treasure) t(lang, "열었어요", "Opened") else t(lang, "다음에", "Next time"), Modifier.weight(1f))
                    }
                }
                Panel(padding = 24.dp) {
                    SectionTitle(t(lang, "오늘 있었던 일", "What happened today"))
                    recorder.events.filter { it.type in SHOWN }.forEach { e ->
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
                    val url = group.joinUrl?.let { recordUrl(it) }
                    when {
                        // 지나온 길은 위치 정보라서, 그룹 휴대폰으로 보낼지 먼저 물어봄
                        url != null && ui.recordShared == null -> {
                            Text(t(lang, "오늘 기록을 그룹 휴대폰으로 보낼까요?", "Send today's record to the group's phones?"), color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text(
                                t(lang, "지나온 길, 명소를 지난 시각, 들은 이야기가 이 그룹 사람만 보는 페이지에 올라가요. 출발과 도착 근처의 길은 빼고 올리고, 3일 뒤 서버에서 지워요.", "Your route, the times you passed each place and the stories go to a page only this group can see. The route near the start and end is cut off, and it's deleted from the server after 3 days."),
                                color = G.muted, fontSize = 22.sp, lineHeight = 31.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                BigButton(t(lang, "보내기", "Send"), Modifier.weight(1f), height = 88.dp, size = 28.sp, onClick = onShare)
                                BigButton(t(lang, "차에만 두기", "Keep in the car"), Modifier.weight(1f), height = 88.dp, size = 28.sp, color = G.surface2, textColor = G.text, onClick = onKeepLocal)
                            }
                        }
                        url != null && ui.recordShared == true -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            QrImage(url, 190.dp)
                            Column(Modifier.weight(1f)) {
                                Text(t(lang, "기록을 휴대폰으로 가져가세요", "Take the record with you"), color = G.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text(t(lang, "사진을 올리면 찍은 시각에 맞춰 길 위에 붙고, 빌트인 캠 영상을 올리면 명소를 지난 장면만 모아 짧은 영상으로 만들어요.", "Upload photos to pin them on the route by time. Upload built-in cam video to get a short highlight of the places."), color = G.muted, fontSize = 22.sp, lineHeight = 30.sp)
                            }
                        }
                        }
                        url != null -> Text(t(lang, "기록은 차에만 저장해 두었어요. 휴대폰에는 올리지 않았어요.", "The record is saved only in the car. Nothing was uploaded."), color = G.muted, fontSize = 22.sp)
                        else -> Text(t(lang, "그룹 서버에 연결되면 기록 페이지 QR이 떠요. 기록은 차에도 저장해 두었어요.", "The record QR appears when the group server is reachable. The record is also saved in the car."), color = G.muted, fontSize = 22.sp)
                    }
                }
                // 앱이 열어 둔 앞 창문은 저절로 올리지 않으므로 내리기 전에 닫게 알림
                if (ui.windowOpened) {
                    Panel(padding = 22.dp, accent = Color(0xFF00E5FF)) {
                        Text(t(lang, "앞 창문이 조금 열려 있어요", "The front windows are still a little open"), color = G.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        ChipButton(t(lang, "앞 창문 닫기", "Close front windows"), color = Color(0x3300E5FF), onClick = onCloseWindows)
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
    "pass" -> if (e.placeId == null || e.placeId in CITY_IDS) t(lang, "${e.title}에 들어섬", "Arrived in ${e.title}") else t(lang, "${e.title} 지남", "Passed ${e.title}")
    "story" -> t(lang, "${e.title} 이야기 들음 (${lenWord(e.detail, lang)})", "Heard about ${e.title} (${lenWord(e.detail, lang)})")
    "pick" -> t(lang, "${e.detail} 님이 고른 ${e.title} 안내", "Navigated to ${e.title}, picked by ${e.detail}")
    "share" -> t(lang, "${e.title} 다른 차에 보냄", "Sent ${e.title} to the other cars")
    "window" -> t(lang, "${e.title}에서 창문 열고 바다 소리 들음", "Opened the windows at ${e.title}")
    "rain" -> t(lang, "비가 와서 비 오는 날 이야기로 바꿈", "Switched to rain stories")
    "night" -> t(lang, "밤이 되어 밤 이야기로 바꿈", "Switched to night stories")
    "arrive" -> t(lang, "목적지 도착", "Arrived")
    "end" -> t(lang, "여행 끝", "Trip ended")
    "qa" -> t(lang, "“${e.detail}” 물어봐서 ${e.title} 알려 줌", "Asked “${e.detail}”: ${e.title}")
    "quiz" -> t(lang, "${e.title} 퀴즈를 휴대폰으로 보냄", "Sent the ${e.title} quiz to phones")
    "quiz_ok" -> if (e.detail.isBlank()) t(lang, "${e.title} 퀴즈 정답, 별 하나", "Got the ${e.title} quiz right, one star") else t(lang, "${e.detail} 님이 ${e.title} 퀴즈를 맞혀 별 하나", "${e.detail} got the ${e.title} quiz right")
    "treasure" -> t(lang, "별 ${e.detail}개로 보물 상자 열림", "Opened the treasure box with ${e.detail} stars")
    "kid" -> t(lang, "${e.title}에서 뒷자리 친구들 이야기", "A story for the back seat at ${e.title}")
    "air" -> t(lang, "${e.title}에서 바깥 공기 들임", "Let in the air at ${e.title}")
    "jam" -> t(lang, "길이 막혀 긴 이야기로 바꿈", "Traffic, switched to longer stories")
    "rest" -> t(lang, "오래 달려서 쉬어 가자고 함", "Suggested a break")
    "rest_nav" -> t(lang, "내비게이션에 가까운 ${e.title} 띄움", "Showed nearby ${e.title} in navigation")
    "charge" -> t(lang, "배터리가 빠듯해 충전하자고 함", "Suggested charging")
    "charge_nav" -> t(lang, "내비게이션에 가까운 충전소 띄움", "Showed chargers nearby in navigation")
    "fuel" -> t(lang, "기름이 빠듯해 주유하자고 함", "Suggested a fuel stop")
    "fuel_nav" -> t(lang, "내비게이션에 가까운 주유소 띄움", "Showed gas stations nearby in navigation")
    "sunroof" -> t(lang, "${e.title}에서 선루프 열고 하늘 봄", "Opened the sunroof at ${e.title}")
    "climate" -> t(lang, "말로 온도를 ${e.detail}도로 맞춤", "Set the temperature to ${e.detail} by voice")
    else -> e.title
}

private val SHOWN = setOf(
    "depart", "pass", "story", "pick", "share", "window", "rain", "night", "arrive", "end",
    "qa", "quiz", "quiz_ok", "treasure", "kid", "air", "jam", "rest", "rest_nav", "charge", "charge_nav", "sunroof", "climate",
)

private val CITY_IDS = setOf("gyeongju", "jejucity", "yeouido")

/** 그룹 주소(…/g/1234?k=…)에서 기록 페이지 주소(…/g/1234/record?k=…)를 만듦 */
private fun recordUrl(join: String): String {
    val q = join.indexOf('?')
    return if (q < 0) "$join/record" else join.substring(0, q) + "/record" + join.substring(q)
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
