package app.gureuneun.guide.ui

import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.net.CompanionClient
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.delay

/**
 * 처음 화면. 할 일은 하나뿐: 여행지를 고르고 [여행 시작].
 * 여행지 카드는 그곳의 3D 장면 사진으로 채우고, 다른 차 그룹 합류와 안내 언어만 곁에 둠.
 * 위치 모드 같은 시연용 설정은 점검 화면으로 옮김.
 */
@Composable
fun HomeScreen(
    ui: TripEngine.Ui,
    scenarios: List<Scenario>,
    vehicle: VehicleBridge.State,
    navi: NaviBridge.State,
    speech: SpeechBridge.Engine,
    locationOk: Boolean?,
    group: CompanionClient.State,
    listenReady: Boolean = false,
    onStart: (scenarioId: String, joinCode: String?) -> Unit,
    onResume: () -> Unit,
    onLang: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDemo: (Boolean) -> Unit,
    onDiag: () -> Unit,
) {
    val lang = ui.lang
    var selected by remember { mutableStateOf(scenarios.firstOrNull()?.id ?: "gyeongju") }
    var joinCode by remember { mutableStateOf("") }
    var showPad by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(3500); hint = null } }
    val sc = scenarios.firstOrNull { it.id == selected }

    BrandBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 1100.dp
            // 넓고 높은 창이면 여행지 카드가 남는 높이를 모두 채움. 낮은 창은 위아래로 밀어 봄
            val fill = wide && maxHeight > 960.dp
            Column(
                Modifier.fillMaxSize().then(if (fill) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 48.dp, vertical = 36.dp),
            ) {
                Header(lang, onLang, onDiag)
                Spacer(Modifier.height(32.dp))
                if (ui.resumable) {
                    ResumeBanner(lang, onResume)
                    Spacer(Modifier.height(28.dp))
                }
                Text(t(lang, "어디로 떠날까요?", "Where to today?"), style = T.titleM, color = C.text)
                Spacer(Modifier.height(20.dp))
                if (wide) {
                    Row(Modifier.then(if (fill) Modifier.weight(1f) else Modifier.height(500.dp)), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        scenarios.forEach { s ->
                            TripCard(s, lang, s.id == selected, Modifier.weight(1f).fillMaxHeight()) {
                                selected = s.id
                                if (s.defaultLang != lang) onLang(s.defaultLang)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        scenarios.forEach { s ->
                            TripCard(s, lang, s.id == selected, Modifier.fillMaxWidth().height(340.dp)) {
                                selected = s.id
                                if (s.defaultLang != lang) onLang(s.defaultLang)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (joinCode.length == 4) {
                        Btn(t(lang, "그룹 ${joinCode}", "Group ${joinCode}"), Modifier.weight(1f), icon = Ic.Close, height = 128.dp) { joinCode = "" }
                    } else {
                        Btn(t(lang, "다른 차 그룹에 합류", "Join another car"), Modifier.weight(1f), icon = Ic.Users, height = 128.dp) {
                            // 번호 입력은 차가 섰을 때만 (달리는 동안 키패드 입력 금지)
                            if (vehicle.parked) showPad = true else hint = t(lang, "차를 세운 뒤에 그룹 번호를 넣을 수 있어요", "Enter a group code once the car is parked")
                        }
                    }
                    Btn(
                        text = if (joinCode.length == 4) t(lang, "그룹 ${joinCode}와 함께 출발", "Start with group ${joinCode}") else t(lang, "여행 시작", "Start trip"),
                        modifier = Modifier.weight(2.2f),
                        icon = Ic.Play,
                        tone = Tone.Primary,
                        height = 128.dp,
                        style = T.titleL,
                        iconSize = 44.dp,
                    ) { onStart(selected, joinCode.takeIf { it.length == 4 }) }
                }
                Spacer(Modifier.height(24.dp))
                // 안내 한 줄은 연결 상태 줄 자리에 잠깐 띄움 (카드 높이가 흔들리지 않게)
                Crossfade(hint, label = "hint") { h ->
                    if (h != null) {
                        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Ic.Info, C.accent, 28.dp)
                            Text(h, style = T.bodyS, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Box(Modifier.height(36.dp), contentAlignment = Alignment.CenterStart) {
                            ReadyLine(lang, vehicle, navi, speech, listenReady, locationOk, group, sc)
                        }
                    }
                }
            }
            if (showPad) JoinPad(lang, joinCode, onChange = { joinCode = it }, onClose = { showPad = false })
        }
    }
}

@Composable
private fun Header(lang: String, onLang: (String) -> Unit, onDiag: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppMark(80.dp)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(t(lang, "구르는 가이드", "Rolling Guide"), style = T.titleL, color = C.text)
            Text(t(lang, "버튼 하나면 차가 통째로 여행 어드벤처가 됩니다", "One button turns the whole car into a travel adventure"), style = T.bodyS, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(24.dp))
        Segmented(listOf("한국어", "English"), if (lang == "en") 1 else 0, Modifier.width(340.dp)) { onLang(if (it == 1) "en" else "ko") }
        Spacer(Modifier.width(16.dp))
        // Pleos 가이드: 아이콘은 늘 글자와 함께
        Btn(t(lang, "점검", "Check"), icon = Ic.Sliders, tone = Tone.Quiet, height = S.touch, style = T.label, iconSize = 28.dp, onClick = onDiag)
    }
}

/** 앱 표시: 현대 블루 바탕 위 경로 표시와 액티브 블루 점 */
@Composable
fun AppMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Color(0xFF00468F), C.brand, C.brandDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Ic.Route, Color.White, size * 0.56f)
        Box(Modifier.align(Alignment.TopEnd).padding(size * 0.16f).size(size * 0.16f).clip(CircleShape).background(C.accent))
    }
}

@Composable
private fun ResumeBanner(lang: String, onResume: () -> Unit) {
    BrandCard(Modifier.fillMaxWidth(), padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(C.onBrandRaised), contentAlignment = Alignment.Center) { Icon(Ic.Route, Color.White, 38.dp) }
            Column(Modifier.weight(1f)) {
                Text(t(lang, "멈췄던 여행이 있어요", "You have a paused trip"), style = T.label, color = C.onBrand2)
                Text(t(lang, "지난번에 멈춘 곳부터 이어서 들려 드릴게요", "We'll pick up right where you stopped"), style = T.bodyL, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Btn(t(lang, "이어서 가기", "Continue"), Modifier.width(300.dp), icon = Ic.Play, tone = Tone.OnBrand, height = 88.dp, onClick = onResume)
        }
    }
}

/** 여행지 표지로 쓸 장면 (모형 이름, 밤 장면인지). 기록 카드도 같은 표지를 씀 */
internal fun coverOf(sc: Scenario): Pair<String?, Boolean> = when (sc.id) {
    "gyeongju" -> "cheomseongdae" to false
    "jeju" -> "horse_lighthouse" to false
    "seoul" -> "assembly" to false
    else -> (sc.landmarks.firstOrNull { it.model != null }?.model ?: sc.places.firstOrNull { it.model != null }?.model) to false
}

@Composable
private fun TripCard(sc: Scenario, lang: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    val ring by animateColorAsState(if (selected) C.accent else Color(0x1AFFFFFF), tween(180), label = "ring")
    val (model, night) = coverOf(sc)
    Box(modifier.clip(shape).border(if (selected) 5.dp else 2.dp, ring, shape).clickable(onClick = onClick)) {
        Poster(model, night, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x66000000), 0.32f to Color.Transparent, 0.55f to Color(0x8C061226), 1f to Color(0xF5061226)),
            ),
        )
        Tag(sc.who.pick(lang), icon = if (sc.cars > 1) Ic.Car else Ic.Users, bg = Color(0xB30E1015), fg = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(22.dp))
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(22.dp).size(60.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Ic.Check, C.onAccent, 34.dp)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(30.dp)) {
            Text(sc.title.pick(lang), style = T.titleL, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(sc.subtitle.pick(lang), style = T.bodyS, color = Color(0xE6FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Ic.Pin, C.onBrand2, 26.dp)
                Text(
                    sc.places.filter { it.triggers && it.kind != PlaceKind.CITY }.joinToString(", ") { it.name.pick(lang) },
                    style = T.label, color = C.onBrand2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 연결 상태 한 줄. 모두 되면 짧게, 안 되는 것만 빨간 점 */
@Composable
private fun ReadyLine(
    lang: String, vehicle: VehicleBridge.State, navi: NaviBridge.State, speech: SpeechBridge.Engine,
    listenReady: Boolean, locationOk: Boolean?, group: CompanionClient.State, sc: Scenario?,
) {
    val items = listOf(
        t(lang, "차량", "Vehicle") to vehicle.connected,
        t(lang, "내비게이션", "Navigation") to navi.connected,
        t(lang, "음성", "Voice") to (speech != SpeechBridge.Engine.NONE),
        t(lang, "마이크", "Mic") to listenReady,
        t(lang, "위치", "Location") to (locationOk == true),
    )
    val groupOk: Boolean? = if (group.online || group.reachable) true else null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        if (items.all { it.second }) {
            StatusDot(true)
            Text(t(lang, "차와 연결이 모두 준비됐어요", "Everything in the car is connected"), style = T.caption, color = C.text3)
        } else {
            items.forEach { (name, ok) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(ok)
                    Text(name, style = T.caption, color = if (ok) C.text3 else C.text2)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(groupOk)
            Text(
                if (groupOk == true) t(lang, "휴대폰 그룹 서버 연결됨", "Phone group server online") else t(lang, "그룹 서버가 없어도 차 안 안내는 돼요", "The in-car guide works without the group server"),
                style = T.caption, color = C.text3,
            )
        }
        if (sc != null && sc.cars > 1) {
            Spacer(Modifier.weight(1f))
            Text(t(lang, "차 ${sc.cars}대 코스", "${sc.cars}-car course"), style = T.caption, color = C.text3)
        }
    }
}

/** 다른 차의 네 자리 그룹 번호 넣기 (차가 섰을 때만 열림) */
@Composable
private fun JoinPad(lang: String, code: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    PopupFrame(Ic.Users, width = 860.dp) {
        Text(t(lang, "다른 차 그룹에 합류", "Join another car's group"), style = T.titleL, color = C.text, textAlign = TextAlign.Center)
        Text(t(lang, "앞차 화면에 뜬 네 자리 번호를 넣어 주세요", "Enter the 4-digit code shown on the other car's screen"), style = T.bodyS, color = C.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(4) { i ->
                val d = code.getOrNull(i)?.toString() ?: ""
                Box(
                    Modifier.size(104.dp, 116.dp).clip(RoundedCornerShape(22.dp)).background(C.raised)
                        .border(3.dp, if (i == code.length) C.accent else Color.Transparent, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(d, style = T.headM, color = C.text) }
            }
        }
        Spacer(Modifier.height(28.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "<", "0", "ok")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { k ->
                        val bg = if (k == "ok") (if (code.length == 4) C.accent else C.raised) else C.raised
                        Box(
                            Modifier.size(200.dp, 92.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable {
                                when (k) {
                                    "<" -> onChange(code.dropLast(1))
                                    "ok" -> if (code.length == 4) onClose()
                                    else -> if (code.length < 4) onChange(code + k)
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "<" -> Icon(Ic.Backspace, C.text, 40.dp)
                                "ok" -> Icon(Ic.Check, if (code.length == 4) C.onAccent else C.text3, 40.dp)
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
