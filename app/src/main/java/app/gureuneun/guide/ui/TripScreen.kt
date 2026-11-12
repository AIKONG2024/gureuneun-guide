package app.gureuneun.guide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.Lang
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.StoryPlayer
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.net.CompanionClient
import app.gureuneun.guide.sdk.MediaKeys
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge

/**
 * 여행 중 차 화면.
 * 명소 사진 한 장이 화면을 채우고 그 위에 이름과 보이는 쪽만 올림. 달리는 동안에는 사진을 가만히 두고 글은 한 줄씩.
 * 차가 서면 지금 읽는 문장과 옆 칸(QR, 오늘 코스)이 나옴.
 * 차가 묻는 것(고른 장소, 여행 끝)은 한 번에 하나만. 달리는 동안에는 같은 자리에서 큰 단추 둘이나 "응" 한마디로 답함.
 */
@Composable
fun TripScreen(
    ui: TripEngine.Ui,
    vehicle: VehicleBridge.State,
    ux: UxBridge.Ux,
    group: CompanionClient.State,
    engine: TripEngine,
    partial: String = "",
    level: Double = 0.0,
) {
    ui.scenario ?: return
    val parked = vehicle.parked && !ux.restricted
    var showDemo by remember { mutableStateOf(false) }
    BackHandler(enabled = showDemo) { showDemo = false }
    val toast = rememberToast(ui.toast, ui.toastSeq)
    val prompt = promptOf(ui, engine)

    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val wide = maxWidth > 1100.dp
        val pad = if (wide) 28.dp else 22.dp
        when {
            wide && parked -> Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Stage(ui, engine, parked = true, compact = false, prompt = null, toast, partial, level, Modifier.weight(1.7f).fillMaxHeight()) { showDemo = true }
                SidePanel(ui, group, engine, Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 12.dp, end = 12.dp))
            }
            parked -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                Stage(ui, engine, parked = true, compact = true, prompt = null, toast, partial, level, Modifier.fillMaxWidth().height(660.dp)) { showDemo = true }
                SidePanel(ui, group, engine, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
            }
            // 달리는 동안에는 사진 한 장뿐
            else -> Stage(ui, engine, parked = false, compact = !wide, prompt, toast, partial, level, Modifier.fillMaxSize().padding(pad)) { showDemo = true }
        }
        if (parked && prompt != null) PromptDialog(prompt, ui)
        if (showDemo && ui.demoMode) DemoSheet(ui, vehicle, engine) { showDemo = false }
    }
}

// ---------------------------------------------------------------- 사진 무대

/** 지금 무대에 올릴 것: 어느 곳 사진, 이름, 보이는 쪽, 덧붙이는 한 줄 */
private class Scene(val photoId: String?, val eyebrow: String?, val title: String, val side: String?, val sub: String?)

private fun sceneOf(ui: TripEngine.Ui, prompt: Prompt?, parked: Boolean, partial: String, heard: String?, hasPhoto: (String?) -> Boolean): Scene {
    val sc = ui.scenario!!
    val lang = Lang.screen
    val now = ui.nowPlace
    val next = ui.nextPlace
    val playing = ui.sentenceCount > 0
    // 창밖을 물어 답하는 중. 남은 길처럼 곳이 없는 답은 소리로만 하고 무대는 그대로 둠
    val answering = playing && ui.nowKind == StoryPlayer.Kind.ANSWER && ui.answerPlaceId != null
    // 명소 안내나 이야기, 아이 이야기를 들려주는 중 (인사나 알림은 무대를 바꾸지 않음)
    val telling = playing && now != null && ui.nowKind in setOf(StoryPlayer.Kind.ANNOUNCE, StoryPlayer.Kind.STORY, StoryPlayer.Kind.KID)
    val listening = ui.listening && !ui.asking

    val photo = when {
        // 창밖에서 찾는 동안에는 답이 될 사진을 띄우지 않음 (뒷자리에서도 이 화면이 보이니까)
        prompt?.hunt == true -> null
        // 고른 곳을 묻는 동안에는 그곳 사진, 물어본 것에 답하는 동안에는 답한 곳 사진 (사진이 있을 때만)
        prompt?.photoId != null && hasPhoto(prompt.photoId) -> prompt.photoId
        answering && hasPhoto(ui.answerPlaceId) -> ui.answerPlaceId
        telling -> now?.id
        !ui.departed -> sc.cover
        next != null -> next.id
        else -> now?.id ?: sc.cover
    }
    val title = when {
        prompt != null -> prompt.title
        listening -> t(lang, "듣고 있어요", "Listening")
        answering -> sc.nameOf(ui.answerPlaceId)?.pick(lang) ?: ui.nowTitle.orEmpty()
        telling -> now?.name?.pick(lang).orEmpty()
        !ui.departed -> sc.title.pick(lang)
        next != null -> next.name.pick(lang)
        else -> now?.name?.pick(lang) ?: sc.title.pick(lang)
    }
    val eyebrow = when {
        prompt != null -> prompt.eyebrow
        listening || answering -> null
        telling -> if (ui.nowKind == StoryPlayer.Kind.KID) t(lang, "뒷자리 친구들에게", "For the back seat") else null
        !ui.departed -> null
        next != null -> t(lang, "다음", "Next")
        else -> null
    }
    val side = if (prompt == null && !listening && (telling || answering)) ui.nowSide else null
    val sub = when {
        prompt != null -> prompt.sub
        listening -> partial.ifBlank { t(lang, "창밖에 보이는 걸 물어보세요", "Ask about anything outside") }
        heard != null -> "“${heard}”"
        // 글은 차가 섰을 때만: 지금 읽는 문장
        parked && ui.nowSentence != null -> ui.nowSentence
        !ui.departed -> t(lang, "출발하면 안내를 시작해요", "The guide starts when we set off")
        !playing && next != null -> ui.nextDistanceM?.let { formatDist(it) }
        else -> null
    }
    return Scene(photo, eyebrow, title, side, sub)
}

@Composable
private fun Stage(
    ui: TripEngine.Ui, engine: TripEngine, parked: Boolean, compact: Boolean, prompt: Prompt?,
    toast: ToastState, partial: String, level: Double, modifier: Modifier, onDemo: () -> Unit,
) {
    val lang = Lang.screen
    // 방금 알아들은 질문 (엔진이 3초 뒤에 지움)
    val heard = ui.heardText?.takeIf { it.isNotBlank() }
    val ctx = LocalContext.current
    val scene = sceneOf(ui, prompt, parked, partial, heard) { Photos.has(ctx, it) }
    var file by remember { mutableStateOf<String?>(null) }
    val pad = if (compact) 30.dp else 44.dp

    Box(modifier.clip(RoundedCornerShape(36.dp))) {
        PhotoStage(scene.photoId, ui.night, moving = parked, Modifier.fillMaxSize()) { file = it }
        // 아래로 갈수록 어둡게 해서 글자가 읽히게 함. 달리는 동안에는 사진을 한 번 더 눌러 글이 먼저 보이게
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x40000000), 0.28f to Color.Transparent, 0.52f to Color(0x4D05090F), 0.78f to Color(0xD905090F), 1f to Color(0xF205090F)),
            ),
        )
        if (!parked) Box(Modifier.fillMaxSize().background(Color(0x1F000000)))
        SideGlow(scene.side)
        // 아이 모드: 찾는 동안에는 사진 대신 그림자만 보여 주고 찾으면 스티커가 잠깐 크게 뜸
        val big = if (compact) 0.7f else 1f
        ui.hunt?.sticker?.takeIf { it.isNotBlank() && prompt?.hunt == true && ui.newSticker == null }?.let {
            Sticker(it, Modifier.align(Alignment.TopCenter).padding(top = 110.dp * big).size(width = 760.dp * big, height = 520.dp * big), shadow = true)
        }
        ui.newSticker?.let { NewSticker(it, big, Modifier.align(Alignment.TopCenter).padding(top = 80.dp * big)) }

        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(pad), verticalAlignment = Alignment.CenterVertically) {
            if (ui.stickers.isNotEmpty()) StickerTray(ui.stickers)
            // 아이 모드는 차가 섰을 때 켜고 끌 수 있음 (찾을 것이 있는 코스에서만)
            if (parked && ui.scenario?.hunt?.isNotEmpty() == true && !ui.kidOffer) {
                if (ui.stickers.isNotEmpty()) Spacer(Modifier.width(14.dp))
                KidChip(ui.kidMode, lang) { engine.setKidMode(!ui.kidMode) }
            }
            Spacer(Modifier.weight(1f))
            // 사진을 찍은 사람과 라이선스 (글자는 차가 섰을 때만)
            if (parked) PhotoCredit(file)
            if (ui.demoMode) {
                Spacer(Modifier.width(16.dp))
                IconBtn(Ic.Sliders, tint = Color(0xB3FFFFFF), bg = Color(0x33000000), size = 72.dp, label = t(lang, "시연 도구", "Demo tools"), onClick = onDemo)
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(pad)) {
            // 운전자가 멈춰 둔 것만 알림. 길 안내나 방향지시등 때문에 잠깐 쉬는 것은 곧 이어 가므로 띄우지 않음
            val paused = TripEngine.HOLD_USER in ui.holds && ui.sentenceCount > 0 && prompt == null
            if (scene.side != null || paused || scene.eyebrow != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    scene.side?.let { SideChip(it, lang) }
                    scene.eyebrow?.let { Text(it, style = T.label, color = Color(0xE6FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (paused) Text(t(lang, "멈춤", "Paused"), style = T.label, color = Color(0xB3FFFFFF), maxLines = 1)
                }
                Spacer(Modifier.height(12.dp))
            }
            // 곳 이름은 한 줄, 물음은 두 줄까지
            Text(
                scene.title, style = (if (compact) T.headM else T.headL).copy(shadow = T.onImage), color = Color.White,
                maxLines = if (prompt != null) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            scene.sub?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it, style = (if (compact) T.bodyM else T.bodyL).copy(shadow = T.onImage), color = Color(0xF2FFFFFF),
                    maxLines = if (parked) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.dp),
                )
            }
            if (toast.visible) {
                Spacer(Modifier.height(20.dp))
                NoticeLine(toast.message)
            }
            when {
                prompt != null -> {
                    Spacer(Modifier.height(28.dp))
                    AnswerButtons(prompt, onPhoto = true)
                    Spacer(Modifier.height(18.dp))
                    VoiceHint(ui, prompt.words, onPhoto = true)
                }
                ui.departed -> {
                    Spacer(Modifier.height(if (ui.sentenceCount > 1) 26.dp else 30.dp))
                    if (ui.sentenceCount > 1) {
                        Segments(ui.sentenceCount, ui.sentenceIndex, Modifier.fillMaxWidth(), on = Color.White, off = Color(0x40FFFFFF))
                        Spacer(Modifier.height(24.dp))
                    }
                    Controls(ui, engine, level)
                }
            }
        }
    }
}

/** 그곳이 보이는 쪽 가장자리를 밝힘. 글을 읽지 않고 곁눈으로도 어느 쪽인지 알게 */
@Composable
private fun BoxScope.SideGlow(side: String?) {
    val soft = C.accent.copy(alpha = 0.5f)
    when (side) {
        "right" -> {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(180.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, soft))))
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(12.dp).background(C.accent))
        }
        "left" -> {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(180.dp).background(Brush.horizontalGradient(listOf(soft, Color.Transparent))))
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(12.dp).background(C.accent))
        }
        "ahead" -> {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(soft, Color.Transparent))))
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(12.dp).background(C.accent))
        }
    }
}

@Composable
private fun SideChip(side: String, lang: String) {
    val turn = when (side) { "left" -> 180f; "ahead" -> -90f; "behind" -> 90f; else -> 0f }
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(C.accent).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Ic.Arrow, C.onAccent, 30.dp, Modifier.graphicsLayer { rotationZ = turn })
        Text(sideWord(side, lang), style = T.label.copy(fontWeight = FontWeight.Bold), color = C.onAccent, maxLines = 1)
    }
}

/** 아이 모드에서 모은 스티커 */
@Composable
private fun StickerTray(names: List<String>) {
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x66000000)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        names.takeLast(5).forEach { Sticker(it, Modifier.size(width = 76.dp, height = 60.dp)) }
    }
}

@Composable
private fun KidChip(on: Boolean, lang: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).background(if (on) Color(0xE6F2B632) else Color(0x66000000)).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Ic.Kid, if (on) Color(0xFF12233F) else Color.White, 30.dp)
        Text(if (on) t(lang, "아이 모드 켜짐", "Kid mode on") else t(lang, "아이 모드", "Kid mode"), style = T.label, color = if (on) Color(0xFF12233F) else Color.White, maxLines = 1)
    }
}

/** 방금 모은 스티커: 금빛 바탕 위로 통 하고 한 번 튀어나옴 */
@Composable
private fun NewSticker(name: String, big: Float, modifier: Modifier) {
    val pop = remember(name) { Animatable(0.5f) }
    LaunchedEffect(name) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f)) }
    Box(modifier.size(width = 820.dp * big, height = 600.dp * big), contentAlignment = Alignment.Center) {
        Box(Modifier.size(640.dp * big).background(Brush.radialGradient(listOf(Color(0xCCFFC94D), Color.Transparent)), CircleShape))
        Sticker(name, Modifier.size(width = 760.dp * big, height = 540.dp * big).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
    }
}

@Composable
private fun PhotoCredit(file: String?) {
    val ctx = LocalContext.current
    val credit = remember(file) { file?.let { Photos.credit(ctx, it) } } ?: return
    Text(
        "사진 ${credit.author} · ${credit.license}", style = T.caption.copy(shadow = T.onImage), color = Color(0xB3FFFFFF),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 620.dp),
    )
}

/** 짧은 알림 한 줄 (4초) */
@Composable
private fun NoticeLine(msg: String) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x99000000)).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Ic.Info, Color.White, 28.dp)
        Text(msg, style = T.bodyM, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 이야기 조작: 다시, 멈춤, 다음은 운전대 버튼과 같은 일. 물어보기만 색을 줌 */
@Composable
private fun Controls(ui: TripEngine.Ui, engine: TripEngine, level: Double) {
    val lang = Lang.screen
    val paused = TripEngine.HOLD_USER in ui.holds
    // 들려주는 것이 없을 때는 멈춤과 다음을 흐리게 (다시 듣기는 방금 끝난 이야기를 다시 들려줌)
    val playing = ui.sentenceCount > 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoundBtn(Ic.Replay, t(lang, "다시 듣기", "Replay")) { engine.onMediaCommand(MediaKeys.Command.PREVIOUS) }
        RoundBtn(if (paused) Ic.Play else Ic.Pause, if (paused) t(lang, "계속", "Resume") else t(lang, "멈춤", "Pause"), enabled = playing || paused) { engine.togglePause() }
        RoundBtn(Ic.Next, t(lang, "다음 이야기", "Next"), enabled = playing) { engine.onMediaCommand(MediaKeys.Command.NEXT) }
        Spacer(Modifier.weight(1f))
        MicButton(ui, level) { engine.ask() }
    }
}

@Composable
private fun RoundBtn(icon: Ic, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(90), label = "press")
    Box(
        Modifier.size(104.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.35f }.clip(CircleShape)
            .background(if (pressed) Color(0x59FFFFFF) else Color(0x33FFFFFF))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, Color.White, 42.dp) }
}

@Composable
private fun MicButton(ui: TripEngine.Ui, level: Double, onClick: () -> Unit) {
    val lang = Lang.screen
    val listening = ui.listening && !ui.asking
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    Row(
        Modifier.height(104.dp).widthIn(min = 300.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(52.dp)).background(C.accent)
            .clickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        if (listening) LevelBars(level, Modifier.size(56.dp, 40.dp)) else Icon(Ic.Mic, C.onAccent, 42.dp)
        Text(if (listening) t(lang, "듣는 중", "Listening") else t(lang, "물어보기", "Ask"), style = T.button, color = C.onAccent, maxLines = 1)
    }
}

/** 소리 크기 막대 다섯 개. 음성 인식 엔진이 알려 주는 소리 크기에 맞춰 움직임 (듣는 동안에만) */
@Composable
private fun LevelBars(level: Double, modifier: Modifier) {
    val norm = ((level + 2.0) / 12.0).coerceIn(0.0, 1.0).toFloat()
    val amp by animateFloatAsState(maxOf(norm, 0.12f), tween(120), label = "level")
    val wave = rememberInfiniteTransition(label = "bars")
    val phase by wave.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val n = 5
        val bw = size.width / (n * 2f - 1f)
        for (i in 0 until n) {
            val wobble = 0.4f + 0.6f * ((kotlin.math.sin(phase + i * 1.3f) + 1f) / 2f)
            val bh = size.height * (0.14f + 0.86f * amp * wobble)
            drawRoundRect(C.onAccent, topLeft = Offset(i * bw * 2f, (size.height - bh) / 2f), size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2f))
        }
    }
}

// ---------------------------------------------------------------- 차가 물어본 것

/** 차가 물어본 것 하나 (고른 장소, 여행 끝). 답은 늘 둘: 그러자, 아니면 나중에 */
private class Prompt(
    val eyebrow: String?,
    val title: String,
    val sub: String?,
    val yes: String,
    val no: String,
    /** 말로 답할 때 쓸 낱말 */
    val words: String,
    val onYes: () -> Unit,
    val onNo: () -> Unit,
    val photoId: String? = null,
    /** 창밖 보물찾기 물음 (무대에 사진 대신 그림자를 띄움) */
    val hunt: Boolean = false,
)

private fun promptOf(ui: TripEngine.Ui, engine: TripEngine): Prompt? {
    val lang = Lang.screen
    val pick = ui.pick
    val hunt = ui.hunt
    return when {
        ui.confirmEnd -> Prompt(
            null, t(lang, "오늘 여행을 끝낼까요?", "End today's trip?"), null,
            t(lang, "끝내고 기록 보기", "End and see the record"), t(lang, "계속 가기", "Keep going"),
            t(lang, "‘응’ · ‘아니’", "“yes” · “no”"),
            { engine.endTrip() }, { engine.cancelEndTrip() },
        )
        pick != null -> {
            // 주차장은 그리로 안내, 카페와 볼거리는 가는 길에 들르기 ("응"이라고 하면 이것)
            val parking = pick.kind.equals("parking", ignoreCase = true)
            val word = when (pick.kind.lowercase()) {
                "cafe" -> t(lang, "카페", "Café")
                "parking" -> t(lang, "주차장", "Parking")
                else -> t(lang, "볼거리", "Sight")
            }
            val d = ui.carPos?.let { Geo.distanceM(it, pick.pos) }
            Prompt(
                pickLabel(pick, lang), engine.pickName(pick), if (d != null) "$word · ${formatDist(d)}" else word,
                if (parking) t(lang, "그리로 안내", "Take us there") else t(lang, "들르기", "Stop there"), t(lang, "나중에", "Later"),
                t(lang, "‘응’ · ‘나중에’", "“yes” · “later”"),
                { engine.answerPick(if (parking) "destination" else "waypoint") }, { engine.answerPick("later") },
                photoId = ui.scenario?.photoOf(pick.placeId),
            )
        }
        ui.kidOffer -> Prompt(
            null, t(lang, "아이 모드로 갈까요?", "Turn on kid mode?"),
            t(lang, "뒷자리에 누가 탔어요. 창밖에서 찾을 것을 내 드려요", "Someone's in the back. I'll give them things to find outside"),
            t(lang, "아이 모드 켜기", "Turn it on"), t(lang, "괜찮아요", "No thanks"),
            t(lang, "‘응’ · ‘아니’", "“yes” · “no”"),
            { engine.setKidMode(true) }, { engine.setKidMode(false) },
        )
        hunt != null -> Prompt(
            t(lang, "창밖 보물찾기", "Look outside"), hunt.text, null,
            t(lang, "찾았다!", "Found it!"), t(lang, "넘어가기", "Skip"),
            t(lang, "‘찾았다’", "“found it”"),
            { engine.onHuntFound(hunt.placeId) }, { engine.skipHunt() }, hunt = true,
        )
        else -> null
    }
}

@Composable
private fun AnswerButtons(p: Prompt, onPhoto: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Btn(p.yes, Modifier.weight(1.4f), tone = if (p.hunt) Tone.Gold else Tone.Primary, height = 108.dp, onClick = p.onYes)
        Btn(p.no, Modifier.weight(1f), tone = if (onPhoto) Tone.OnBrand else Tone.Secondary, height = 108.dp, onClick = p.onNo)
    }
}

/** 말로도 답할 수 있다는 표시. 차가 듣는 중이면 색을 줌 */
@Composable
private fun VoiceHint(ui: TripEngine.Ui, words: String, onPhoto: Boolean) {
    val listening = ui.asking && ui.listening
    val color = when {
        listening -> C.accent
        onPhoto -> Color(0xCCFFFFFF)
        else -> C.text3
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Ic.Mic, color, 30.dp)
        Text(words, style = T.bodyM.copy(fontWeight = if (listening) FontWeight.SemiBold else FontWeight.Normal), color = color, maxLines = 1)
    }
}

/** 차가 서 있을 때는 같은 물음을 화면 가운데에 띄움 */
@Composable
private fun PromptDialog(p: Prompt, ui: TripEngine.Ui) {
    Box(Modifier.fillMaxSize().background(C.scrim).clickable(enabled = false) {}.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 920.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xFF1B1F26)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            p.eyebrow?.let {
                Text(it, style = T.label, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
            }
            Text(p.title, style = T.titleL, color = C.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.sub?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = T.bodyM, color = C.text2, textAlign = TextAlign.Center, maxLines = 1)
            }
            Spacer(Modifier.height(36.dp))
            AnswerButtons(p, onPhoto = false)
            Spacer(Modifier.height(22.dp))
            VoiceHint(ui, p.words, onPhoto = false)
        }
    }
}

// ---------------------------------------------------------------- 차가 섰을 때 옆 칸

@Composable
private fun SidePanel(ui: TripEngine.Ui, group: CompanionClient.State, engine: TripEngine, modifier: Modifier) {
    val lang = Lang.screen
    val sc = ui.scenario ?: return
    Column(modifier) {
        val joinUrl = group.joinUrl
        if (joinUrl != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                QrCode(joinUrl, 232.dp)
                Column {
                    Text(t(lang, "휴대폰으로 같이 봐요", "Follow along on your phone"), style = T.titleM, color = C.text)
                    ui.groupCode?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(t(lang, "그룹 번호 ${it}", "Group code ${it}"), style = T.bodyM, color = C.text2)
                    }
                    val joined = joinedLine(group, lang)
                    if (joined != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(joined, style = T.bodyM, color = C.text2)
                    }
                }
            }
        } else {
            Text(t(lang, "지금은 휴대폰 연결이 안 돼요", "Phones can't join right now"), style = T.bodyM, color = C.text3)
        }

        // 달리는 동안 '나중에'로 미뤄 둔 곳
        if (ui.laterPicks.isNotEmpty()) {
            Rule()
            Text(t(lang, "담아 둔 곳", "Saved for later"), style = T.label, color = C.text3)
            ui.laterPicks.forEach { p ->
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(engine.pickName(p), style = T.bodyL, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val parking = p.kind.equals("parking", ignoreCase = true)
                    Btn(if (parking) t(lang, "안내", "Go") else t(lang, "들르기", "Stop there"), height = 76.dp, style = T.label) {
                        engine.routeLaterPick(p.id, if (parking) "destination" else "waypoint")
                    }
                    IconBtn(Ic.Close, size = 76.dp, label = t(lang, "지우기", "Remove")) { engine.routeLaterPick(p.id, "drop") }
                }
            }
        }

        Rule()
        sc.places.filter { it.triggers && it.kind != PlaceKind.CITY }.forEach { p ->
            val done = p.id in ui.entered
            val next = !done && p.id == ui.nextPlace?.id
            Row(Modifier.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Photo(p.id, ui.night, Modifier.size(76.dp).clip(CircleShape).graphicsLayer { alpha = if (done || next) 1f else 0.55f }, shrink = 4)
                Text(p.name.pick(lang), style = T.bodyL, color = if (done || next) C.text else C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    when {
                        done -> t(lang, "지남", "Passed")
                        next -> ui.nextDistanceM?.let { formatDist(it) }.orEmpty()
                        else -> ""
                    },
                    style = T.bodyM, color = if (next) C.text else C.text3,
                )
            }
        }
        if (ui.departed) {
            Spacer(Modifier.height(28.dp))
            Btn(t(lang, "여행 끝", "End trip"), Modifier.fillMaxWidth(), tone = Tone.Quiet, height = 88.dp) { engine.requestEndTrip() }
        } else {
            // 출발 전에는 다른 여행지로 바꿀 수 있게
            Spacer(Modifier.height(28.dp))
            Btn(t(lang, "처음으로", "Back"), Modifier.fillMaxWidth(), tone = Tone.Quiet, height = 88.dp) { engine.goHome() }
        }
    }
}

@Composable
private fun Rule() {
    Spacer(Modifier.height(30.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    Spacer(Modifier.height(26.dp))
}

/** 누가 같이 보고 있는지 한 줄. 아무도 없으면 띄우지 않음 */
private fun joinedLine(group: CompanionClient.State, lang: String): String? {
    val cars = group.cars.size
    val phones = group.phones
    return when {
        cars > 1 && phones > 0 -> t(lang, "차 ${cars}대, 휴대폰 ${phones}대", "${cars} cars, ${phones} phone" + if (phones == 1) "" else "s")
        cars > 1 -> t(lang, "차 ${cars}대", "${cars} cars")
        phones > 0 -> t(lang, "휴대폰 ${phones}대", "${phones} phone" + if (phones == 1) "" else "s")
        else -> null
    }
}

// ---------------------------------------------------------------- 시연 도구 (에뮬레이터)

/** 에뮬레이터는 GPS 가 움직이지 않아서 시연 경로를 달리게 하는 단추를 한곳에 모음. 실제 위치 모드에서는 나오지 않음 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoSheet(ui: TripEngine.Ui, vehicle: VehicleBridge.State, engine: TripEngine, onClose: () -> Unit) {
    val lang = Lang.screen
    PopupFrame(Ic.Sliders, width = 1300.dp) {
        Text(t(lang, "시연 도구", "Demo tools"), style = T.titleL, color = C.text)
        Spacer(Modifier.height(10.dp))
        val signals = buildList {
            add(t(lang, "기어 ${vehicle.gear}", "Gear ${vehicle.gear}"))
            ui.guideDistanceM?.let { add(t(lang, "다음 길 안내 ${it.toInt()}m", "Next turn ${it.toInt()}m")) }
        }
        Text(signals.joinToString("   "), style = T.caption, color = C.text3)
        if (ui.routeLengthM > 0) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.raised)) {
                Box(Modifier.fillMaxWidth((ui.progressM / ui.routeLengthM).toFloat().coerceIn(0f, 1f)).height(10.dp).background(C.accent))
            }
        }
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn(if (ui.demoRunning) t(lang, "주행 멈춤", "Pause drive") else t(lang, "주행 시작", "Drive"), icon = if (ui.demoRunning) Ic.Pause else Ic.Play, tone = Tone.Primary, height = 84.dp, style = T.label, iconSize = 28.dp) {
                if (ui.demoRunning) engine.demoPause() else engine.demoPlay()
            }
            Btn(t(lang, "빨리 감기 x${ui.demoFast}", "Fast x${ui.demoFast}"), icon = Ic.Next, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.demoFaster() }
            Btn(t(lang, "다음 명소 앞으로", "Skip to next place"), icon = Ic.Pin, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.demoJumpToNext() }
            Btn(if (ui.followGear) t(lang, "기어 따라 주행: 켬", "Follow gear: on") else t(lang, "기어 따라 주행: 끔", "Follow gear: off"), icon = Ic.Car, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.toggleFollowGear() }
        }
        // 마이크 입력이 없는 에뮬레이터에서 말로 묻기를 보여 주는 단추 (앱에 넣어 둔 질문 음성을 음성 인식 SDK 에 그대로 넣음)
        val qs = engine.demoQuestions[ui.lang] ?: engine.demoQuestions.getValue("ko")
        val nextQ = qs[ui.demoQuestionIdx % qs.size]
        Spacer(Modifier.height(14.dp))
        Btn(t(lang, "질문 음성 넣기: “${nextQ.text}”", "Play a question: “${nextQ.text}”"), icon = Ic.Mic, height = 84.dp, style = T.label, iconSize = 28.dp) { engine.askDemo() }
        Spacer(Modifier.height(24.dp))
        Btn(t(lang, "닫기", "Close"), Modifier.width(320.dp), tone = Tone.Quiet, height = 84.dp, onClick = onClose)
    }
}

// ---------------------------------------------------------------- 글

private fun sideWord(side: String, lang: String) = when (side) {
    "left" -> t(lang, "왼쪽", "Left")
    "right" -> t(lang, "오른쪽", "Right")
    "behind" -> t(lang, "뒤쪽", "Behind")
    else -> t(lang, "앞쪽", "Ahead")
}

private fun pickLabel(p: TripEngine.Pick, lang: String) =
    if (p.anonymous) t(lang, "함께 탄 분이 고른 곳", "Picked by a passenger") else t(lang, "${p.by} 님이 고른 곳", "Picked by ${p.by}")

