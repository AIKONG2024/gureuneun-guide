package app.gureuneun.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.TripRecorder
import app.gureuneun.guide.net.CompanionClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 여행이 끝나면 보는 하루 기록 카드. 엽서 한 장처럼 읽히게 만듦.
 * 왼쪽: 오늘 간 곳 장면, 숫자 넷, 있었던 일을 시간순으로.
 * 오른쪽: 할 일 하나(기록을 그룹 휴대폰으로 보낼지)와 지나온 길. [처음 화면으로]는 늘 맨 아래에 보임.
 */
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
    val s = summarize(sc, recorder)
    val url = group.recordUrl ?: group.joinUrl?.let { recordUrl(it) }
    // 보낼지 묻는 동안은 [보내기]가 이 화면의 주인공, 답한 뒤에는 [처음 화면으로]
    val asking = url != null && ui.recordShared == null
    val shown = recorder.events.filter { it.type in SHOWN }

    BrandBackground {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp)) {
            if (maxWidth > 1100.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(
                        Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Hero(sc, lang, recorder, Modifier.fillMaxWidth().height(400.dp))
                        StatsCard(s, lang)
                        Extras(s, sc, lang)
                        Timeline(shown, lang)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        // 할 일 카드와 지나온 길, [처음 화면으로]가 한 화면에 다 들어감. 지나온 길이 남는 높이를 채움
                        ShareCard(ui, url, onShare, onKeepLocal)
                        if (ui.windowOpened) WindowCard(lang, onCloseWindows)
                        RouteCard(sc, ui, recorder, Modifier.weight(1f))
                        HomeButton(lang, primary = !asking, onHome)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Hero(sc, lang, recorder, Modifier.fillMaxWidth().height(360.dp))
                    StatsCard(s, lang)
                    Extras(s, sc, lang)
                    ShareCard(ui, url, onShare, onKeepLocal)
                    if (ui.windowOpened) WindowCard(lang, onCloseWindows)
                    RouteCard(sc, ui, recorder, Modifier.height(420.dp))
                    Timeline(shown, lang)
                    HomeButton(lang, primary = !asking, onHome)
                }
            }
        }
    }
}

private class Summary(
    val distanceKm: Double,
    val minutes: Long,
    val places: Int,
    val stories: Int,
    val asked: Int,
    val stars: Int,
    val quizzes: Int,
    val treasure: Boolean,
)

private fun summarize(sc: Scenario, r: TripRecorder): Summary {
    val end = if (r.endedAt > 0) r.endedAt else System.currentTimeMillis()
    return Summary(
        distanceKm = r.distanceM / 1000,
        minutes = ((end - r.startedAt) / 60000).coerceAtLeast(0),
        places = r.events.filter { it.type == "pass" && it.placeId != null && sc.findPlace(it.placeId)?.kind != PlaceKind.CITY }
            .map { it.placeId }.distinct().size,
        stories = r.events.count { it.type == "story" },
        // 말로 물어본 것은 진짜 물음만 셈 (차가 물은 것에 "응", "나중에"로 답한 것은 빼고)
        asked = r.events.count { it.type == "ask" && !it.detail.startsWith("REPLY_") },
        stars = r.events.filter { it.type == "quiz_ok" }.map { it.placeId }.distinct().size,
        quizzes = r.events.count { it.type == "quiz" },
        treasure = r.events.any { it.type == "treasure" },
    )
}

// ---------------------------------------------------------------- 왼쪽: 엽서

@Composable
private fun Hero(sc: Scenario, lang: String, r: TripRecorder, modifier: Modifier) {
    val (model, night) = coverOf(sc)
    val en = lang == "en"
    val day = SimpleDateFormat(if (en) "EEEE, MMM d" else "M월 d일 EEEE", if (en) Locale.US else Locale.KOREA).format(Date(r.startedAt))
    val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    val from = hm.format(Date(r.startedAt))
    val to = hm.format(Date(if (r.endedAt > 0) r.endedAt else System.currentTimeMillis()))
    Box(modifier.clip(RoundedCornerShape(S.cardRadius))) {
        Poster(model, night, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x59000000), 0.3f to Color.Transparent, 0.56f to Color(0x8C061226), 1f to Color(0xF5061226)),
            ),
        )
        Tag(
            t(lang, "오늘의 여행 기록", "Today's trip record"), Ic.Flag, bg = Color(0xB30E1015), fg = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(26.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 36.dp, vertical = 32.dp)) {
            Text(day, style = T.label, color = C.onBrand2)
            Spacer(Modifier.height(6.dp))
            Text(sc.title.pick(lang), style = T.headL, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(t(lang, "${from}부터 ${to}까지", "From ${from} to ${to}"), style = T.bodyM, color = Color(0xE6FFFFFF))
        }
    }
}

/** 숫자 넷. 값은 크게, 단위는 작게 */
@Composable
private fun StatsCard(s: Summary, lang: String) {
    val h = s.minutes / 60
    val m = s.minutes % 60
    val time = when {
        h == 0L -> listOf("${m}" to t(lang, "분", "min"))
        m == 0L -> listOf("${h}" to t(lang, "시간", "h"))
        else -> listOf("${h}" to t(lang, "시간", "h"), "${m}" to t(lang, "분", "min"))
    }
    Card(Modifier.fillMaxWidth(), padding = 0.dp) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 28.dp)) {
            Stat(Ic.Route, listOf("%.1f".format(s.distanceKm) to "km"), t(lang, "달린 거리", "Distance"), Modifier.weight(1f))
            VLine()
            Stat(Ic.Clock, time, t(lang, "여행 시간", "Time"), Modifier.weight(1f))
            VLine()
            Stat(Ic.Pin, listOf("${s.places}" to t(lang, "곳", "")), t(lang, "지난 명소", "Places"), Modifier.weight(1f))
            VLine()
            Stat(Ic.Speaker, listOf("${s.stories}" to t(lang, "편", "")), t(lang, "들은 이야기", "Stories"), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(icon: Ic, parts: List<Pair<String, String>>, label: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 22.dp)) {
        Row {
            parts.forEach { (v, unit) ->
                Text(v, style = T.titleL, color = C.text, maxLines = 1, modifier = Modifier.alignByBaseline())
                if (unit.isNotEmpty()) {
                    Text(unit, style = T.bodyM, color = C.text2, maxLines = 1, modifier = Modifier.alignByBaseline().padding(start = 4.dp, end = 8.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, C.text3, 24.dp)
            Text(label, style = T.label, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VLine() {
    Box(Modifier.width(2.dp).fillMaxHeight().background(C.line))
}

/** 물어본 것, 퀴즈 별, 보물 상자. 있을 때만 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Extras(s: Summary, sc: Scenario, lang: String) {
    val quiz = s.quizzes > 0 || sc.treasureStars > 0
    if (s.asked == 0 && !quiz) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.asked > 0) {
            Tag(t(lang, "말로 물어본 것 ${s.asked}번", if (s.asked == 1) "Asked 1 question" else "Asked ${s.asked} questions"), Ic.Mic, bg = C.surface)
        }
        if (quiz) {
            val total = maxOf(s.quizzes, s.stars)
            // 맞힌 퀴즈 / 휴대폰으로 보낸 퀴즈
            Tag(t(lang, "퀴즈 별 ${s.stars}/${total}", "Quiz stars ${s.stars}/${total}"), Ic.Star, bg = C.surface, fg = C.sand)
        }
        if (sc.treasureStars > 0) {
            Tag(
                if (s.treasure) t(lang, "보물 상자 열었어요", "Opened the treasure box") else t(lang, "보물 상자는 다음에", "Treasure box next time"),
                Ic.Gift, bg = C.surface, fg = if (s.treasure) C.sand else C.text2,
            )
        }
    }
}

/** 있었던 일을 시간순으로. 왼쪽 시각, 가운데 세로줄 위 아이콘, 오른쪽 한 줄 설명 */
@Composable
private fun Timeline(events: List<TripRecorder.Event>, lang: String) {
    val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    val rail = Color(0x33FFFFFF)
    Card(Modifier.fillMaxWidth(), padding = 32.dp) {
        Eyebrow(t(lang, "오늘 있었던 일", "What happened today"), Ic.Clock)
        Spacer(Modifier.height(14.dp))
        if (events.isEmpty()) {
            Text(t(lang, "남은 기록이 없어요", "Nothing was recorded"), style = T.bodyM, color = C.text3)
        }
        events.forEachIndexed { i, e ->
            val (ic, tint) = eventIcon(e.type)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Text(hm.format(Date(e.time)), style = T.caption, color = C.text3, maxLines = 1, modifier = Modifier.width(84.dp).padding(top = 20.dp))
                Box(
                    Modifier.width(56.dp).fillMaxHeight().drawBehind {
                        val x = size.width / 2
                        val cy = 34.dp.toPx()
                        val w = 3.dp.toPx()
                        if (i > 0) drawLine(rail, Offset(x, 0f), Offset(x, cy), w, StrokeCap.Butt)
                        if (i < events.lastIndex) drawLine(rail, Offset(x, cy), Offset(x, size.height), w, StrokeCap.Butt)
                    },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.padding(top = 12.dp).size(44.dp).clip(CircleShape).background(C.raised), contentAlignment = Alignment.Center) {
                        Icon(ic, tint, 24.dp)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(describe(e, lang), style = T.bodyM, color = C.text, modifier = Modifier.weight(1f).padding(vertical = 15.dp))
            }
        }
    }
}

private fun eventIcon(type: String): Pair<Ic, Color> = when (type) {
    "depart" -> Ic.Play to C.accent
    "pass" -> Ic.Pin to C.accent
    "arrive", "end" -> Ic.Flag to C.accent
    "story" -> Ic.Speaker to C.text2
    "kid" -> Ic.Kid to C.text2
    "pick" -> Ic.Bookmark to C.text2
    "share" -> Ic.Send to C.text2
    "window" -> Ic.Window to C.text2
    "air" -> Ic.Wind to C.text2
    "sunroof" -> Ic.Sun to C.text2
    "rain" -> Ic.Rain to C.text2
    "night" -> Ic.Moon to C.text2
    "qa" -> Ic.Mic to C.text2
    "quiz" -> Ic.Phone to C.text2
    "quiz_ok" -> Ic.Star to C.sand
    "treasure" -> Ic.Gift to C.sand
    "jam" -> Ic.Clock to C.text2
    "rest" -> Ic.Cup to C.text2
    "charge" -> Ic.Bolt to C.text2
    "fuel" -> Ic.Fuel to C.text2
    "rest_nav", "charge_nav", "fuel_nav" -> Ic.Navigation to C.text2
    "climate" -> Ic.Sliders to C.text2
    else -> Ic.Info to C.text2
}

// ---------------------------------------------------------------- 오른쪽: 할 일과 지나온 길

@Composable
private fun ShareCard(ui: TripEngine.Ui, url: String?, onShare: () -> Unit, onKeepLocal: () -> Unit) {
    val lang = ui.lang
    when {
        // 지나온 길은 위치 정보라서, 그룹 휴대폰으로 보낼지 먼저 물어봄
        url != null && ui.recordShared == null -> BrandCard(Modifier.fillMaxWidth(), padding = 36.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(68.dp).clip(CircleShape).background(C.onBrandRaised), contentAlignment = Alignment.Center) {
                    Icon(Ic.Phone, Color.White, 36.dp)
                }
                Text(t(lang, "오늘 기록을 그룹 휴대폰으로 보낼까요?", "Send today's record to the group's phones?"), style = T.titleM, color = Color.White, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                t(
                    lang,
                    "지나온 길, 명소를 지난 시각, 들은 이야기가 이 그룹 사람만 보는 페이지에 올라가요. 출발과 도착 근처의 길은 빼고 올리고, 3일 뒤 서버에서 지워요.",
                    "Your route, the times you passed each place and the stories go to a page only this group can see. The route near the start and end is cut off, and it's deleted from the server after 3 days.",
                ),
                style = T.bodyS, color = C.onBrand2,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Btn(t(lang, "보내기", "Send"), Modifier.weight(1f), icon = Ic.Send, tone = Tone.Primary, onClick = onShare)
                Btn(t(lang, "차에만 두기", "Keep in the car"), Modifier.weight(1f), tone = Tone.OnBrand, onClick = onKeepLocal)
            }
        }
        url != null && ui.recordShared == true -> Card(Modifier.fillMaxWidth(), padding = 30.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                QrCode(url, 220.dp)
                Column(Modifier.weight(1f)) {
                    Eyebrow(t(lang, "그룹 휴대폰에 보냈어요", "Sent to the group's phones"), Ic.Check, color = C.accent)
                    Spacer(Modifier.height(10.dp))
                    Text(t(lang, "기록을 휴대폰으로 가져가세요", "Take the record with you"), style = T.titleM, color = C.text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t(
                            lang,
                            "사진을 올리면 찍은 시각에 맞춰 길 위에 붙고, 빌트인 캠 영상을 올리면 명소를 지난 장면만 모아 짧은 영상으로 만들어요.",
                            "Upload photos to pin them on the route by time. Upload built-in cam video to get a short highlight of the places.",
                        ),
                        style = T.bodyS, color = C.text2,
                    )
                }
            }
        }
        url != null -> NoteCard(Ic.Check, t(lang, "기록은 차에만 저장해 두었어요", "The record is saved only in the car"), t(lang, "휴대폰에는 올리지 않았어요", "Nothing was uploaded to phones"))
        else -> NoteCard(Ic.Info, t(lang, "기록은 차에 저장해 두었어요", "The record is saved in the car"), t(lang, "그룹 서버에 연결되면 기록 페이지 QR이 떠요", "A QR for the record page appears once the group server is reachable"))
    }
}

@Composable
private fun NoteCard(icon: Ic, title: String, sub: String) {
    Card(Modifier.fillMaxWidth(), padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) { Icon(icon, C.accent, 32.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = T.bodyL.copy(fontWeight = FontWeight.SemiBold), color = C.text)
                Text(sub, style = T.bodyS, color = C.text3)
            }
        }
    }
}

/** 앱이 열어 둔 앞 창문은 저절로 올리지 않으므로 내리기 전에 닫게 알림 */
@Composable
private fun WindowCard(lang: String, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth(), padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) { Icon(Ic.Window, C.accent, 32.dp) }
            Column(Modifier.weight(1f)) {
                Text(t(lang, "앞 창문이 조금 열려 있어요", "The front windows are still a little open"), style = T.bodyL.copy(fontWeight = FontWeight.SemiBold), color = C.text)
                Text(t(lang, "내리기 전에 닫아 두세요", "Close them before you get out"), style = T.bodyS, color = C.text3)
            }
        }
        Spacer(Modifier.height(20.dp))
        Btn(t(lang, "앞 창문 닫기", "Close front windows"), Modifier.fillMaxWidth(), icon = Ic.Window, height = 88.dp, onClick = onClose)
    }
}

@Composable
private fun RouteCard(sc: Scenario, ui: TripEngine.Ui, r: TripRecorder, modifier: Modifier) {
    val route = r.route.map { it.second }.ifEmpty { sc.route }
    Card(modifier.fillMaxWidth(), padding = 28.dp) {
        Eyebrow(t(ui.lang, "지나온 길", "Our route"), Ic.Route)
        Spacer(Modifier.height(10.dp))
        RouteMap(
            route = route, places = sc.places.filter { it.triggers }, entered = ui.entered, car = route.lastOrNull(), heading = 0.0,
            modifier = Modifier.fillMaxWidth().weight(1f), showCar = false,
        )
    }
}

@Composable
private fun HomeButton(lang: String, primary: Boolean, onHome: () -> Unit) {
    Btn(
        t(lang, "처음 화면으로", "Back to start"), Modifier.fillMaxWidth(), icon = Ic.Home,
        tone = if (primary) Tone.Primary else Tone.Secondary, height = 104.dp, onClick = onHome,
    )
}

// ---------------------------------------------------------------- 글

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
    "qa", "quiz", "quiz_ok", "treasure", "kid", "air", "jam", "rest", "rest_nav",
    "charge", "charge_nav", "fuel", "fuel_nav", "sunroof", "climate",
)

private val CITY_IDS = setOf("gyeongju", "jejucity", "yeouido")

/** 그룹 주소(…/g/1234?k=…)에서 기록 페이지 주소(…/g/1234/record?k=…)를 만듦 (서버가 기록 주소를 안 줄 때) */
private fun recordUrl(join: String): String {
    val q = join.indexOf('?')
    return if (q < 0) "${join}/record" else join.substring(0, q) + "/record" + join.substring(q)
}

private fun lenWord(d: String, lang: String) = when (d) {
    "SHORT" -> t(lang, "짧게", "short")
    "MEDIUM" -> t(lang, "보통", "medium")
    "LONG" -> t(lang, "길게", "long")
    else -> d
}

