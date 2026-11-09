package app.gureuneun.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.net.CompanionClient

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    ui: TripEngine.Ui,
    scenarios: List<Scenario>,
    vehicle: VehicleBridge.State,
    navi: NaviBridge.State,
    speech: SpeechBridge.Engine,
    locationOk: Boolean?,
    group: CompanionClient.State,
    onStart: (scenarioId: String, joinCode: String?) -> Unit,
    onResume: () -> Unit,
    onLang: (String) -> Unit,
    onDemo: (Boolean) -> Unit,
    onDiag: () -> Unit,
) {
    val lang = ui.lang
    var selected by remember { mutableStateOf(scenarios.firstOrNull()?.id ?: "gyeongju") }
    var joinCode by remember { mutableStateOf("") }
    var showPad by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF101722), G.bg)))
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t(lang, "구르는 가이드", "Rolling Guide"), color = G.text, fontSize = 52.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text(t(lang, "버튼 하나면 차가 통째로 여행 어드벤처가 됩니다", "One button turns the whole car into a travel adventure"), color = G.muted, fontSize = 24.sp)
                }
                Pill(t(lang, "점검", "Check"), onClick = onDiag)
            }

            if (ui.resumable) {
                Panel(accent = G.warn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t(lang, "다시 타면 다음 장", "Pick up where you left off"), color = G.warn, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(t(lang, "멈췄던 여행을 이어서 들려 드릴게요", "Continue the trip you paused"), color = G.text, fontSize = 26.sp)
                        }
                        BigButton(t(lang, "이어서 가기", "Continue"), Modifier.width(260.dp), color = G.warn, height = 80.dp, size = 26.sp, onClick = onResume)
                    }
                }
            }

            Text(t(lang, "어디로 떠나요?", "Where to today?"), color = G.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth > 1000.dp
                val cardW = if (wide) (maxWidth - 48.dp) / 3 else maxWidth
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    scenarios.forEach { sc ->
                        ScenarioCard(sc, lang, sc.id == selected, Modifier.width(cardW)) { selected = sc.id; if (sc.defaultLang != lang) onLang(sc.defaultLang) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(t(lang, "안내 언어", "Guide language"))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Toggle("한국어", lang == "ko") { onLang("ko") }
                        Toggle("English", lang == "en") { onLang("en") }
                    }
                }
                Column(Modifier.weight(1f)) {
                    SectionTitle(t(lang, "다른 차 그룹에 합류", "Join another car's group"))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.height(72.dp).width(220.dp).clip(RoundedCornerShape(20.dp)).background(G.surface2)
                                .clickable { showPad = !showPad },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(joinCode.padEnd(4, '_').chunked(1).joinToString(" "), color = G.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        }
                        if (joinCode.isNotEmpty()) Pill(t(lang, "지우기", "Clear"), onClick = { joinCode = "" })
                    }
                }
                Column(Modifier.weight(1f)) {
                    SectionTitle(t(lang, "위치", "Location"))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Toggle(t(lang, "시연 경로", "Demo route"), ui.demoMode) { onDemo(true) }
                        Toggle(t(lang, "실제 위치", "Real GPS"), !ui.demoMode) { onDemo(false) }
                    }
                }
            }
            if (showPad) NumberPad(onDigit = { d -> if (joinCode.length < 4) joinCode += d }, onBack = { joinCode = joinCode.dropLast(1) })

            BigButton(
                text = if (joinCode.length == 4) t(lang, "그룹 ${joinCode} 와 함께 여행 시작", "Start trip with group ${joinCode}") else t(lang, "여행 시작", "Start trip"),
                modifier = Modifier.fillMaxWidth(),
                height = 132.dp,
                size = 44.sp,
            ) { onStart(selected, joinCode.takeIf { it.length == 4 }) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Status(t(lang, "차량", "Vehicle"), vehicle.connected)
                Status(t(lang, "내비게이션", "Navigation"), navi.connected)
                Status(t(lang, "음성", "Voice") + if (speech == SpeechBridge.Engine.PLEOS) " (Pleos)" else "", speech != SpeechBridge.Engine.NONE)
                Status(t(lang, "위치", "Location"), locationOk == true)
                Status(t(lang, "그룹 서버", "Group server"), group.online || group.code != null, maybeOk = true)
            }
        }
    }
}

@Composable
private fun ScenarioCard(sc: Scenario, lang: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(sc.places.firstOrNull()?.color ?: "#7FE3FF")) }.getOrDefault(G.sky)
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else G.surface)
            .border(if (selected) 3.dp else 1.dp, if (selected) accent else G.line, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(sc.who.pick(lang), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sc.title.pick(lang), color = G.text, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text(sc.subtitle.pick(lang), color = G.muted, fontSize = 21.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(4.dp))
        Text(sc.places.filter { it.triggers && it.kind.name != "CITY" }.joinToString(", ") { it.name.pick(lang) }, color = G.text, fontSize = 20.sp)
    }
}

@Composable
fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(72.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) G.sky else G.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) Color(0xFF06121A) else G.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Status(name: String, ok: Boolean, maybeOk: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(G.surface).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(if (ok) G.good else if (maybeOk) G.muted else G.bad, 12.dp)
        Text(name, color = G.text, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberPad(onDigit: (String) -> Unit, onBack: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (1..9).map { it.toString() }.plus(listOf("0")).forEach { d ->
            Box(
                Modifier.size(96.dp, 80.dp).clip(RoundedCornerShape(18.dp)).background(G.surface2).clickable { onDigit(d) },
                contentAlignment = Alignment.Center,
            ) { Text(d, color = G.text, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        }
        Box(
            Modifier.size(140.dp, 80.dp).clip(RoundedCornerShape(18.dp)).background(G.surface2).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("⌫", color = G.text, fontSize = 30.sp) }
    }
}
