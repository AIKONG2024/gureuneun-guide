package app.gureuneun.guide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.Lang
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.delay

/**
 * 처음 화면: 어디로 갈지 고르고 [여행 시작].
 * 넓은 창은 여행지 사진 석 장을 나란히, 내비게이션 옆 절반 창(세로)은 가로 띠 셋으로 쌓아 스크롤 없이 한 화면에 둠.
 */
@Composable
fun HomeScreen(
    ui: TripEngine.Ui,
    scenarios: List<Scenario>,
    vehicle: VehicleBridge.State,
    navi: NaviBridge.State,
    speech: SpeechBridge.Engine,
    locationOk: Boolean?,
    onStart: (scenarioId: String, joinCode: String?) -> Unit,
    onResume: () -> Unit,
    onLang: (String) -> Unit,
    onDiag: () -> Unit,
) {
    val lang = Lang.screen
    var selected by remember { mutableStateOf(scenarios.firstOrNull()?.id ?: "gyeongju") }
    var joinCode by remember { mutableStateOf("") }
    var showPad by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 뒤로 키는 떠 있는 창부터 닫음 (덜 넣은 번호는 지움)
    BackHandler(enabled = showPad || showCredits) {
        if (joinCode.length != 4) joinCode = ""
        showPad = false
        showCredits = false
    }
    LaunchedEffect(hint) { if (hint != null) { delay(3500); hint = null } }
    // 앱을 막 켰을 때는 차와 붙는 데 몇 초 걸리므로 그동안은 연결 알림을 띄우지 않음
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(8000); settled = true }
    val onPick: (Scenario) -> Unit = { s ->
        selected = s.id
        if (s.defaultLang != ui.lang) onLang(s.defaultLang)
    }
    val onJoin: () -> Unit = {
        // 번호 입력은 차가 섰을 때만 (달리는 동안 키패드 입력 금지)
        if (vehicle.parked) showPad = true else hint = t(lang, "그룹 번호는 차를 세우고 넣어 주세요", "Park the car to enter a group code")
    }
    val start: () -> Unit = { onStart(selected, joinCode.takeIf { it.length == 4 }) }
    // 연결이 안 된 것이 있을 때만 알림 (다 되면 아무것도 띄우지 않음)
    val missing = listOfNotNull(
        t(lang, "차량", "vehicle").takeIf { !vehicle.connected },
        t(lang, "내비게이션", "navigation").takeIf { !navi.connected },
        t(lang, "음성", "voice").takeIf { speech == SpeechBridge.Engine.NONE },
        t(lang, "위치", "location").takeIf { locationOk == false },
    )
    val note = hint ?: missing.takeIf { settled && it.isNotEmpty() }?.let { t(lang, "연결 확인: ${it.joinToString(", ")}", "Not connected: ${it.joinToString(", ")}") }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val wide = maxWidth > 1100.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 36.dp, vertical = if (wide) 36.dp else 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppMark(if (wide) 64.dp else 56.dp)
                Text(t(lang, "구르는 가이드", "Rolling Guide"), style = T.titleM, color = C.text, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp))
                if (wide) LangToggle(ui.lang, lang, onLang)
                IconBtn(Ic.Sliders, label = t(lang, "점검", "Check"), onClick = onDiag)
            }
            Spacer(Modifier.height(if (wide) 40.dp else 28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(lang, "어디로 떠날까요?", "Where to today?"), style = if (wide) T.headM else T.titleL, color = C.text, maxLines = 1, modifier = Modifier.weight(1f))
                if (!wide) LangToggle(ui.lang, lang, onLang)
            }
            Spacer(Modifier.height(if (wide) 28.dp else 20.dp))
            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    scenarios.forEach { s -> TripCard(s, lang, s.id == selected, tall = true, Modifier.weight(1f).fillMaxHeight()) { onPick(s) } }
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    scenarios.forEach { s -> TripCard(s, lang, s.id == selected, tall = false, Modifier.fillMaxWidth().weight(1f)) { onPick(s) } }
                }
            }
            Spacer(Modifier.height(if (wide) 28.dp else 20.dp))
            val h = if (wide) 124.dp else 108.dp
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (joinCode.length == 4) {
                    Btn(t(lang, "그룹 ${joinCode}", "Group ${joinCode}"), Modifier.weight(1f), icon = Ic.Close, height = h, style = T.label) { joinCode = "" }
                } else {
                    Btn(t(lang, "그룹 번호로 합류", "Join with a code"), Modifier.weight(1f), height = h, style = T.label, onClick = onJoin)
                }
                if (ui.resumable) Btn(t(lang, "이어서 가기", "Continue trip"), Modifier.weight(1f), height = h, style = T.label, onClick = onResume)
                Btn(
                    if (joinCode.length == 4) t(lang, "같이 출발", "Start together") else t(lang, "여행 시작", "Start trip"),
                    Modifier.weight(if (wide) 2.2f else 1.5f), tone = Tone.Primary, height = h, style = if (wide) T.titleL else T.titleM, onClick = start,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note.orEmpty(), style = T.caption, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(t(lang, "사진 출처", "Photo credits"), style = T.caption, color = C.text3, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showCredits = true }.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        if (showPad) JoinPad(lang, joinCode, onChange = { joinCode = it }, onClose = { showPad = false })
        if (showCredits) Credits(lang) { showCredits = false }
    }
}

/** 안내 음성의 언어. 화면 글자는 차의 시스템 언어 그대로라 바뀌지 않음 */
@Composable
private fun LangToggle(guide: String, lang: String, onLang: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t(lang, "안내 언어", "Guide voice"), style = T.label, color = C.text3, maxLines = 1)
        Segmented(listOf("한국어", "English"), if (guide == "en") 1 else 0, Modifier.width(280.dp)) { onLang(if (it == 1) "en" else "ko") }
    }
}

/** 앱 표시: 현대 블루 바탕 위 굽이치는 길 */
@Composable
fun AppMark(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Color(0xFF00468F), C.brand, C.brandDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Ic.Route, Color.White, size * 0.56f)
        Box(Modifier.align(Alignment.TopEnd).padding(size * 0.16f).size(size * 0.16f).clip(CircleShape).background(C.accent))
    }
}

/** 여행지 카드: 사진 위에 이름과 지나갈 곳. 고른 카드에만 테두리 */
@Composable
private fun TripCard(sc: Scenario, lang: String, selected: Boolean, tall: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    val ring by animateColorAsState(if (selected) C.accent else Color.Transparent, tween(180), label = "ring")
    val stops = sc.places.filter { it.triggers && it.kind != PlaceKind.CITY }.joinToString(" · ") { it.name.pick(lang) }
    Box(modifier.clip(shape).border(5.dp, ring, shape).clickable(onClick = onClick)) {
        Photo(sc.cover, night = false, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                if (tall) Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE605090F))
                else Brush.horizontalGradient(0f to Color(0xD905090F), 0.7f to Color.Transparent),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(if (tall) 32.dp else 26.dp)) {
            Text(sc.title.pick(lang), style = T.titleL.copy(shadow = T.onImage), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(stops, style = T.bodyS.copy(shadow = T.onImage), color = Color(0xE6FFFFFF), maxLines = if (tall) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(22.dp).size(56.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Ic.Check, C.onAccent, 32.dp)
            }
        }
    }
}

/** 다른 차의 네 자리 그룹 번호 넣기 */
@Composable
private fun JoinPad(lang: String, code: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.scrim).clickable(enabled = false) {}.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xFF1B1F26)).padding(44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(t(lang, "앞차 화면의 그룹 번호", "Group code from the other car"), style = T.titleL, color = C.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(104.dp, 116.dp).clip(RoundedCornerShape(22.dp)).background(C.raised)
                            .border(3.dp, if (i == code.length) C.accent else Color.Transparent, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(code.getOrNull(i)?.toString().orEmpty(), style = T.headM, color = C.text) }
                }
            }
            Spacer(Modifier.height(28.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "<", "0", "ok")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                keys.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { k ->
                            val ready = code.length == 4
                            Box(
                                Modifier.size(200.dp, 92.dp).clip(RoundedCornerShape(22.dp)).background(if (k == "ok" && ready) C.accent else C.raised).clickable {
                                    when (k) {
                                        "<" -> onChange(code.dropLast(1))
                                        "ok" -> if (ready) onClose()
                                        else -> if (code.length < 4) onChange(code + k)
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                when (k) {
                                    "<" -> Icon(Ic.Backspace, C.text, 40.dp)
                                    "ok" -> Icon(Ic.Check, if (ready) C.onAccent else C.text3, 40.dp)
                                    else -> Text(k, style = T.titleL, color = C.text)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Btn(t(lang, "닫기", "Close"), Modifier.width(300.dp), tone = Tone.Quiet, height = 80.dp) { if (code.length != 4) onChange(""); onClose() }
        }
    }
}

/** 사진을 찍은 사람과 라이선스 */
@Composable
private fun Credits(lang: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember { Photos.allCredits(ctx).values.sortedBy { it.file } }
    Box(Modifier.fillMaxSize().background(C.scrim).clickable(enabled = false) {}.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 1200.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xFF1B1F26)).padding(44.dp)) {
            Text(t(lang, "사진 출처", "Photo credits"), style = T.titleL, color = C.text)
            Spacer(Modifier.height(6.dp))
            Text(t(lang, "위키미디어 공용의 사진을 크기만 줄여 썼어요", "Photos from Wikimedia Commons, resized only"), style = T.bodyS, color = C.text2)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { c ->
                    Text("${c.title}  ·  ${c.author}  ·  ${c.license}", style = T.caption, color = C.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(24.dp))
            Btn(t(lang, "닫기", "Close"), Modifier.width(300.dp).align(Alignment.CenterHorizontally), tone = Tone.Quiet, height = 80.dp, onClick = onClose)
        }
    }
}

