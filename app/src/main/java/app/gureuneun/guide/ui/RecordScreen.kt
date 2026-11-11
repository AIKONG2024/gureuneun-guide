package app.gureuneun.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.Lang
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
 * 여행이 끝나면 보는 하루 기록. 엽서 한 장처럼: 오늘 간 곳 사진, 날짜와 거리, 지나온 곳들.
 * 할 일은 하나, 같이 탄 사람 휴대폰으로 보내기. 누르기 전에는 차 밖으로 나가는 것이 없음
 */
@Composable
fun RecordScreen(
    ui: TripEngine.Ui,
    recorder: TripRecorder,
    group: CompanionClient.State,
    onShare: () -> Unit = {},
    onHome: () -> Unit,
) {
    val sc = ui.scenario ?: return
    val lang = Lang.screen
    val seen = remember(recorder.tripId, recorder.events.size) { seenPlaces(sc, recorder, lang) }
    val toast = rememberToast(ui.toast, ui.toastSeq)
    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        if (maxWidth > 1100.dp) {
            Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Postcard(sc, ui, recorder, seen.count { !it.asked }, Modifier.weight(1.7f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 12.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SeenList(seen, ui.night, lang) }
                    Spacer(Modifier.height(24.dp))
                    Actions(ui, group, onShare, onHome)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Postcard(sc, ui, recorder, seen.count { !it.asked }, Modifier.fillMaxWidth().height(560.dp))
                    Spacer(Modifier.height(28.dp))
                    Column(Modifier.padding(horizontal = 10.dp)) { SeenList(seen, ui.night, lang) }
                }
                Spacer(Modifier.height(20.dp))
                Actions(ui, group, onShare, onHome)
            }
        }
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) { ToastBubble(toast) }
    }
}

/** 오늘 지나온 곳, 또는 물어봐서 알게 된 곳 */
private class Seen(val id: String, val name: String, val asked: Boolean)

private fun seenPlaces(sc: Scenario, r: TripRecorder, lang: String): List<Seen> {
    val out = LinkedHashMap<String, Seen>()
    r.events.forEach { e ->
        val id = e.placeId ?: return@forEach
        when (e.type) {
            "pass" -> if (sc.findPlace(id)?.kind != PlaceKind.CITY) out.getOrPut(id) { Seen(id, sc.nameOf(id)?.pick(lang) ?: e.title, false) }
            "qa" -> out.getOrPut(id) { Seen(id, sc.nameOf(id)?.pick(lang) ?: e.title, true) }
        }
    }
    return out.values.toList()
}

@Composable
private fun Postcard(sc: Scenario, ui: TripEngine.Ui, r: TripRecorder, places: Int, modifier: Modifier) {
    val lang = Lang.screen
    val day = remember(r.startedAt) {
        SimpleDateFormat(if (lang == "en") "EEE, MMM d" else "M월 d일 EEEE", if (lang == "en") Locale.ENGLISH else Locale.KOREAN).format(Date(r.startedAt))
    }
    val facts = listOfNotNull(
        "%.1fkm".format(r.distanceM / 1000).takeIf { r.distanceM >= 100 },
        t(lang, "명소 ${places}곳", if (places == 1) "1 stop" else "${places} stops").takeIf { places > 0 },
        t(lang, "퀴즈 별 ${ui.stars}개", if (ui.stars == 1) "1 quiz star" else "${ui.stars} quiz stars").takeIf { ui.stars > 0 },
    ).joinToString("  ·  ")
    Box(modifier.clip(RoundedCornerShape(36.dp))) {
        PhotoStage(sc.cover, night = false, moving = true, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x59000000), 0.35f to Color.Transparent, 0.6f to Color(0x6605090F), 1f to Color(0xF205090F))))
        Text(day, style = T.bodyL.copy(shadow = T.onImage), color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(44.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(44.dp)) {
            Text(sc.title.pick(lang), style = T.headL.copy(shadow = T.onImage), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(facts, style = T.bodyL.copy(shadow = T.onImage), color = Color(0xF2FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeenList(seen: List<Seen>, night: Boolean, lang: String) {
    if (seen.isEmpty()) {
        Text(t(lang, "오늘은 지나온 명소가 없어요", "No stops passed today"), style = T.bodyM, color = C.text3)
        return
    }
    // 오늘 본 곳들을 사진 띠로 (지나온 차례대로)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        seen.forEach { s ->
            Box(Modifier.fillMaxWidth().height(if (seen.size > 4) 150.dp else 190.dp).clip(RoundedCornerShape(26.dp))) {
                Photo(s.id, night, Modifier.fillMaxSize(), shrink = 2)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xCC05090F), 0.75f to Color.Transparent)))
                Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 28.dp)) {
                    Text(s.name, style = T.titleM.copy(shadow = T.onImage), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.asked) Text(t(lang, "물어본 곳", "You asked"), style = T.bodyS.copy(shadow = T.onImage), color = Color(0xD9FFFFFF))
                }
            }
        }
    }
}

@Composable
private fun Actions(ui: TripEngine.Ui, group: CompanionClient.State, onShare: () -> Unit, onHome: () -> Unit) {
    val lang = Lang.screen
    val url = group.recordUrl ?: group.joinUrl
    val canSend = url != null && ui.recordShared == null
    if (url != null && ui.recordShared == true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            QrCode(url, 200.dp)
            Column {
                Text(t(lang, "휴대폰으로 보냈어요", "Sent to your phones"), style = T.titleM, color = C.text)
                Spacer(Modifier.height(6.dp))
                Text(t(lang, "QR을 찍어도 열려요. 3일 뒤 지워져요", "Scan to open. Kept for 3 days"), style = T.bodyS, color = C.text2)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (canSend) {
        Btn(t(lang, "휴대폰으로 보내기", "Send to phones"), Modifier.fillMaxWidth(), icon = Ic.Send, tone = Tone.Primary, height = 112.dp, style = T.titleM, iconSize = 38.dp, onClick = onShare)
        Spacer(Modifier.height(16.dp))
    }
    Btn(t(lang, "처음으로", "Home"), Modifier.fillMaxWidth(), tone = if (canSend) Tone.Quiet else Tone.Secondary, height = 96.dp, onClick = onHome)
}

