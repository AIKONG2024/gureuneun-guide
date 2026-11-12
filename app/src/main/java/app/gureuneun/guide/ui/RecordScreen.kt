package app.gureuneun.guide.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gureuneun.guide.core.Lang
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.TripRecorder
import app.gureuneun.guide.net.CompanionClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 여행이 끝나면 보는 화면. 오늘 지나온 길과 들른 곳, 모은 스티커, 함께 탄 사람이 올린 사진이 영상 한 편으로 이어짐.
 * 영상은 차가 서 있을 때만 틀고(달리는 중이면 첫 장면만), 휴대폰으로 보내면 휴대폰에서 파일로 저장할 수 있음
 */
@Composable
fun RecordScreen(
    ui: TripEngine.Ui,
    recorder: TripRecorder,
    group: CompanionClient.State,
    parked: Boolean,
    payload: suspend () -> String,
    onShare: () -> Unit = {},
    onHome: () -> Unit,
) {
    val sc = ui.scenario ?: return
    val lang = Lang.screen
    val toast = rememberToast(ui.toast, ui.toastSeq)
    // 누를 때마다 처음부터 다시 틂
    var replay by remember { mutableIntStateOf(0) }
    val places = remember(recorder.tripId, recorder.events.size) {
        recorder.events.filter { it.type == "pass" && sc.findPlace(it.placeId)?.kind != PlaceKind.CITY }.mapNotNull { it.placeId }.distinct().size
    }
    val day = remember(recorder.startedAt) {
        SimpleDateFormat(if (lang == "en") "EEE, MMM d" else "M월 d일 EEEE", if (lang == "en") Locale.ENGLISH else Locale.KOREAN).format(Date(recorder.startedAt))
    }
    val facts = listOfNotNull(
        "%.1fkm".format(recorder.distanceM / 1000).takeIf { recorder.distanceM >= 100 },
        t(lang, "명소 ${places}곳", if (places == 1) "1 stop" else "${places} stops").takeIf { places > 0 },
        t(lang, "스티커 ${ui.stickers.size}장", if (ui.stickers.size == 1) "1 sticker" else "${ui.stickers.size} stickers").takeIf { ui.stickers.isNotEmpty() },
    ).joinToString("  ·  ")

    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val side: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(day, style = T.bodyM, color = C.text3, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text(sc.title.pick(lang), style = T.titleL, color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (facts.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(facts, style = T.bodyL, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!parked) {
                    Spacer(Modifier.height(18.dp))
                    Text(t(lang, "차를 세우면 오늘의 영상이 시작돼요", "The video starts once the car is parked"), style = T.bodyM, color = C.text3)
                }
                Spacer(Modifier.weight(1f))
                Actions(ui, group, parked, onReplay = { replay++ }, onShare = onShare, onHome = onHome)
            }
        }
        if (maxWidth > 1100.dp) {
            Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Reel(payload, parked, replay, Modifier.weight(1.9f).fillMaxHeight().clip(RoundedCornerShape(36.dp)))
                side(Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 12.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Reel(payload, parked, replay, Modifier.fillMaxWidth().weight(1.3f).clip(RoundedCornerShape(30.dp)))
                Spacer(Modifier.height(24.dp))
                side(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp))
            }
        }
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) { ToastBubble(toast) }
    }
}

/**
 * 여행 영상. 휴대폰 페이지와 같은 웹 화면(assets/reel)을 그대로 씀: 여행 기록과 코스, 사진 주소를 넘기면 캔버스에 그림.
 * 달리는 동안에는 움직이는 화면을 틀지 않고 첫 장면만 보여 줌
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Reel(payload: suspend () -> String, parked: Boolean, replay: Int, modifier: Modifier) {
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var data by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { data = payload() }
    // 화면이 준비되고 자료를 받았으면 시작. 차가 서면(또는 [다시 보기]) 처음부터 틂
    LaunchedEffect(ready, data, parked, replay) {
        val w = web ?: return@LaunchedEffect
        val d = data ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        app.gureuneun.guide.sdk.GLog.i("Reel", "여행 영상 시작 (자료 " + d.length + "자, " + (if (parked) "재생" else "첫 장면만") + ")")
        w.evaluateJavascript("startReel(Object.assign(" + d + ", { still: " + (!parked) + " }))", null)
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.let { w ->
                w.evaluateJavascript("stopReel()", null)
                w.destroy()
            }
            web = null
        }
    }
    AndroidView(
        modifier = modifier.background(Color(0xFFF8F4E8)),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0xFFF8F4E8.toInt())
                // 캔버스 하나만 그리는 화면이라 소프트웨어로 그림 (에뮬레이터의 GPU 합성에서는 웹 화면이 비어 보임)
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = WebViewClient()
                // 웹 화면의 오류는 앱 로그로 (진단 화면에서 봄)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        app.gureuneun.guide.sdk.GLog.i("Reel", m.message() + " (" + m.sourceId().substringAfterLast('/') + ":" + m.lineNumber() + ")")
                        return true
                    }
                }
                if (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu")) WebView.setWebContentsDebuggingEnabled(true)
                addJavascriptInterface(object {
                    @JavascriptInterface fun onReelReady() { app.gureuneun.guide.sdk.GLog.i("Reel", "웹 화면 준비됨"); post { ready = true } }
                    @JavascriptInterface fun onReelEnd() {}
                }, "RG")
                loadUrl("file:///android_asset/reel/reel.html")
                web = this
            }
        },
    )
}

@Composable
private fun Actions(ui: TripEngine.Ui, group: CompanionClient.State, parked: Boolean, onReplay: () -> Unit, onShare: () -> Unit, onHome: () -> Unit) {
    val lang = Lang.screen
    val url = group.recordUrl ?: group.joinUrl
    val canSend = url != null && ui.recordShared == null
    if (url != null && ui.recordShared == true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            QrCode(url, 200.dp)
            Column {
                Text(t(lang, "휴대폰으로 보냈어요", "Sent to your phones"), style = T.titleM, color = C.text)
                Spacer(Modifier.height(6.dp))
                Text(t(lang, "휴대폰에서 영상으로 저장돼요. QR을 찍어도 열려요", "Save it as a video on your phone. Or scan to open"), style = T.bodyS, color = C.text2)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (canSend) {
        Btn(t(lang, "휴대폰으로 보내기", "Send to phones"), Modifier.fillMaxWidth(), icon = Ic.Send, tone = Tone.Primary, height = 112.dp, style = T.titleM, iconSize = 38.dp, onClick = onShare)
        Spacer(Modifier.height(16.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (parked) Btn(t(lang, "다시 보기", "Play again"), Modifier.weight(1f), icon = Ic.Replay, tone = Tone.Secondary, height = 96.dp, onClick = onReplay)
        Btn(t(lang, "처음으로", "Home"), Modifier.weight(1f), tone = if (canSend) Tone.Quiet else Tone.Secondary, height = 96.dp, onClick = onHome)
    }
}

