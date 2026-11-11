package app.gureuneun.guide.ui

import androidx.activity.compose.BackHandler
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
 * 넓은 창(앱 영역 전체)에서는 세로로 긴 카드 셋을 나란히, 내비게이션 옆 절반 창(Pleos 1:1 분할, 세로)에서는
 * 가로로 긴 띠 카드 셋을 위아래로 두고 [여행 시작]이 늘 보이게 함 (스크롤 없음).
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
    onDiag: () -> Unit,
) {
    val lang = ui.lang
    var selected by remember { mutableStateOf(scenarios.firstOrNull()?.id ?: "gyeongju") }
    var joinCode by remember { mutableStateOf("") }
    var showPad by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 뒤로 키는 번호 입력 창부터 닫음 ([닫기]와 같이 덜 넣은 번호는 지움)
    BackHandler(enabled = showPad) {
        if (joinCode.length != 4) joinCode = ""
        showPad = false
    }
    LaunchedEffect(hint) { if (hint != null) { delay(3500); hint = null } }
    val sc = scenarios.firstOrNull { it.id == selected }
    val onPick: (Scenario) -> Unit = { s ->
        selected = s.id
        if (s.defaultLang != lang) onLang(s.defaultLang)
    }
    val onJoin: () -> Unit = {
        // 번호 입력은 차가 섰을 때만 (달리는 동안 키패드 입력 금지)
        if (vehicle.parked) showPad = true else hint = t(lang, "차를 세운 뒤에 그룹 번호를 넣을 수 있어요", "Enter a group code once the car is parked")
    }
    val start: () -> Unit = { onStart(selected, joinCode.takeIf { it.length == 4 }) }
    val status: @Composable (Boolean) -> Unit = { compact ->
        // 안내 한 줄은 연결 상태 줄 자리에 잠깐 띄움 (카드 높이가 흔들리지 않게)
        Crossfade(hint, label = "hint") { h ->
            if (h != null) {
                Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Ic.Info, C.accent, 28.dp)
                    Text(h, style = T.bodyS, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Box(Modifier.height(36.dp), contentAlignment = Alignment.CenterStart) {
                    ReadyLine(lang, vehicle, navi, speech, listenReady, locationOk, group, sc, compact)
                }
            }
        }
    }

    BrandBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 1100.dp
            if (wide) {
                // 넓고 높은 창이면 여행지 카드가 남는 높이를 모두 채움. 낮은 창은 위아래로 밀어 봄
                val fill = maxHeight > 960.dp
                Column(
                    Modifier.fillMaxSize().then(if (fill) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .padding(horizontal = 48.dp, vertical = 36.dp),
                ) {
                    Header(lang, onLang, onDiag)
                    Spacer(Modifier.height(32.dp))
                    if (ui.resumable) {
                        ResumeBanner(lang, onResume, compact = false)
                        Spacer(Modifier.height(28.dp))
                    }
                    Text(t(lang, "어디로 떠날까요?", "Where to today?"), style = T.titleM, color = C.text)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.then(if (fill) Modifier.weight(1f) else Modifier.height(500.dp)), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        scenarios.forEach { s -> TripCard(s, lang, s.id == selected, Modifier.weight(1f).fillMaxHeight()) { onPick(s) } }
                    }
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        JoinButton(lang, joinCode, compact = false, Modifier.weight(1f), onClear = { joinCode = "" }, onJoin = onJoin)
                        StartButton(lang, joinCode, compact = false, Modifier.weight(2.2f), start)
                    }
                    Spacer(Modifier.height(24.dp))
                    status(false)
                }
            } else {
                // 절반 창(세로): 스크롤 없이 한 화면. 카드 셋이 남는 높이를 나눠 쓰고 [여행 시작]은 늘 아래에 보임
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 30.dp)) {
                    NarrowHeader(lang, onDiag)
                    Spacer(Modifier.height(24.dp))
                    if (ui.resumable) {
                        ResumeBanner(lang, onResume, compact = true)
                        Spacer(Modifier.height(20.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(lang, "어디로 떠날까요?", "Where to today?"), style = T.titleM, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        LangToggle(lang, onLang, Modifier.width(290.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        scenarios.forEach { s -> TripBanner(s, lang, s.id == selected, Modifier.fillMaxWidth().weight(1f)) { onPick(s) } }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        JoinButton(lang, joinCode, compact = true, Modifier.weight(1f), onClear = { joinCode = "" }, onJoin = onJoin)
                        StartButton(lang, joinCode, compact = true, Modifier.weight(1.7f), start)
                    }
                    Spacer(Modifier.height(16.dp))
                    status(true)
                }
            }
            if (showPad) JoinPad(lang, joinCode, onChange = { joinCode = it }, onClose = { showPad = false })
        }
    }
}

/** 다른 차 그룹에 합류. 번호를 넣었으면 그 번호와 지우기 */
@Composable
private fun JoinButton(lang: String, code: String, compact: Boolean, modifier: Modifier, onClear: () -> Unit, onJoin: () -> Unit) {
    val h = if (compact) 112.dp else 128.dp
    val style = if (compact) T.label else T.button
    if (code.length == 4) {
        Btn(t(lang, "그룹 ${code}", "Group ${code}"), modifier, icon = Ic.Close, height = h, style = style, onClick = onClear)
    } else {
        Btn(if (compact) t(lang, "그룹 합류", "Join a group") else t(lang, "다른 차 그룹에 합류", "Join another car"), modifier, icon = Ic.Users, height = h, style = style, onClick = onJoin)
    }
}

/** 이 화면에 하나뿐인 주 단추 */
@Composable
private fun StartButton(lang: String, code: String, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val grouped = code.length == 4
    Btn(
        text = when {
            !grouped -> t(lang, "여행 시작", "Start trip")
            compact -> t(lang, "그룹 ${code}와 출발", "Go with group ${code}")
            else -> t(lang, "그룹 ${code}와 함께 출발", "Start with group ${code}")
        },
        modifier = modifier,
        icon = Ic.Play,
        tone = Tone.Primary,
        height = if (compact) 112.dp else 128.dp,
        style = when {
            !compact -> T.titleL
            grouped -> T.button
            else -> T.titleM
        },
        iconSize = if (compact) 40.dp else 44.dp,
        onClick = onClick,
    )
}

@Composable
private fun LangToggle(lang: String, onLang: (String) -> Unit, modifier: Modifier) {
    Segmented(listOf("한국어", "English"), if (lang == "en") 1 else 0, modifier) { onLang(if (it == 1) "en" else "ko") }
}

@Composable
private fun DiagButton(lang: String, onDiag: () -> Unit) {
    // Pleos 가이드: 아이콘은 늘 글자와 함께
    Btn(t(lang, "점검", "Check"), icon = Ic.Sliders, tone = Tone.Quiet, height = S.touch, style = T.label, iconSize = 28.dp, onClick = onDiag)
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
        LangToggle(lang, onLang, Modifier.width(340.dp))
        Spacer(Modifier.width(16.dp))
        DiagButton(lang, onDiag)
    }
}

/** 절반 창의 머리: 앱 이름과 한 줄 소개(두 줄까지), 점검. 안내 언어는 여행지 고르기 줄로 옮김 */
@Composable
private fun NarrowHeader(lang: String, onDiag: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppMark(72.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(t(lang, "구르는 가이드", "Rolling Guide"), style = T.titleL, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t(lang, "버튼 하나면 차가 통째로 여행 어드벤처가 됩니다", "One button turns the whole car into a travel adventure"),
                style = T.bodyS.copy(lineBreak = T.balanced), color = C.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        DiagButton(lang, onDiag)
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
private fun ResumeBanner(lang: String, onResume: () -> Unit, compact: Boolean) {
    BrandCard(Modifier.fillMaxWidth(), padding = if (compact) 24.dp else 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 24.dp)) {
            Box(Modifier.size(if (compact) 60.dp else 72.dp).clip(CircleShape).background(C.onBrandRaised), contentAlignment = Alignment.Center) { Icon(Ic.Route, Color.White, if (compact) 32.dp else 38.dp) }
            Column(Modifier.weight(1f)) {
                Text(t(lang, "멈췄던 여행이 있어요", "You have a paused trip"), style = T.label, color = C.onBrand2)
                Text(
                    t(lang, "지난번에 멈춘 곳부터 이어서 들려 드릴게요", "We'll pick up right where you stopped"),
                    style = if (compact) T.bodyS.copy(lineBreak = T.balanced) else T.bodyL, color = Color.White, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Btn(t(lang, "이어서 가기", "Continue"), Modifier.width(if (compact) 230.dp else 300.dp), icon = Ic.Play, tone = Tone.OnBrand, height = if (compact) 84.dp else 88.dp, style = if (compact) T.label else T.button, onClick = onResume)
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

/**
 * 절반 창의 여행지 카드: 가로로 긴 띠. 장면 사진은 오른쪽 62%에 두어 명소가 잘리지 않게 하고,
 * 글은 왼쪽 남색 바탕 위에. 띠가 낮으면(이어 가기 띠가 있을 때) 한 줄 소개를 뺌
 */
@Composable
private fun TripBanner(sc: Scenario, lang: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    val ring by animateColorAsState(if (selected) C.accent else Color(0x1AFFFFFF), tween(180), label = "ring")
    val (model, night) = coverOf(sc)
    val navy = Color(0xFF061226)
    BoxWithConstraints(modifier.clip(shape).background(navy).border(if (selected) 5.dp else 2.dp, ring, shape).clickable(onClick = onClick)) {
        Poster(model, night, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to navy, 0.38f to navy, 0.62f to navy.copy(alpha = 0.55f), 0.86f to navy.copy(alpha = 0.08f), 1f to Color.Transparent),
            ),
        )
        val roomy = maxHeight >= 196.dp
        Column(
            Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = if (roomy) 22.dp else 16.dp, bottom = if (roomy) 22.dp else 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Tag(sc.who.pick(lang), icon = if (sc.cars > 1) Ic.Car else Ic.Users, bg = Color(0xB30E1015), fg = Color.White)
            Column {
                Text(sc.title.pick(lang), style = T.titleL.copy(shadow = T.onImage), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (roomy) {
                    Spacer(Modifier.height(4.dp))
                    Text(sc.subtitle.pick(lang), style = T.bodyS.copy(shadow = T.onImage), color = Color(0xEBFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(20.dp).size(56.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Ic.Check, C.onAccent, 32.dp)
            }
        }
    }
}

/** 연결 상태 한 줄. 모두 되면 짧게, 안 되는 것만 빨간 점 */
@Composable
private fun ReadyLine(
    lang: String, vehicle: VehicleBridge.State, navi: NaviBridge.State, speech: SpeechBridge.Engine,
    listenReady: Boolean, locationOk: Boolean?, group: CompanionClient.State, sc: Scenario?, compact: Boolean = false,
) {
    val items = listOf(
        t(lang, "차량", "Vehicle") to vehicle.connected,
        t(lang, "내비게이션", "Navigation") to navi.connected,
        t(lang, "음성", "Voice") to (speech != SpeechBridge.Engine.NONE),
        t(lang, "마이크", "Mic") to listenReady,
        t(lang, "위치", "Location") to (locationOk == true),
    )
    val groupOk: Boolean? = if (group.online || group.reachable) true else null
    val groupText = if (groupOk == true) t(lang, "휴대폰 그룹 서버 연결됨", "Phone group server online")
    else if (compact) t(lang, "그룹 서버 없이도 차 안 안내는 돼요", "The car guide works without the group server")
    else t(lang, "그룹 서버가 없어도 차 안 안내는 돼요", "The in-car guide works without the group server")
    if (compact) {
        // 좁은 창: 안 되는 것만 이름으로 모아 한 줄에
        val missing = items.filter { !it.second }.map { it.first }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(missing.isEmpty())
            Text(
                if (missing.isEmpty()) t(lang, "차와 연결이 모두 준비됐어요", "Everything in the car is connected")
                else t(lang, "확인할 것 ${missing.joinToString(", ")}", "Check ${missing.joinToString(", ")}"),
                style = T.caption, color = if (missing.isEmpty()) C.text3 else C.text2, maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            StatusDot(groupOk)
            Text(groupText, style = T.caption, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        return
    }
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
            Text(groupText, style = T.caption, color = C.text3)
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
