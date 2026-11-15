package app.gureuneun.guide.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gureuneun.guide.core.Record
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.engine.Ui
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

// 여행 티켓 (휴대폰 여행 티켓과 같은 흰 종이에 네이비 머리)
private val PaperTop = Color(0xFFFFFFFF)
private val PaperBottom = Color(0xFFF2F4F7)
private val PaperInk = Color(0xFF0F1A2B)
private val PaperSub = Color(0xFF6B7280)
private val PaperLine = Color(0xFF9AA4AE)
private val PaperCut = Color(0x380F1A2B)
private val DateBlue = Color(0xFFAACAE6)
private val Cyan2 = Color(0xFF7FD6EA)

/** 도착 후(정차): 왼쪽은 1분 여행 영상, 오른쪽은 여행 티켓과 보낸 곳 */
@Composable
fun RecordLayout(ui: Ui, engine: TripEngine, rec: Record, wide: Boolean) {
    Row(Modifier.fillMaxSize().padding(17.dp), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
        if (wide) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(38.dp)).background(C.panel)
                    .border(1.dp, C.line, RoundedCornerShape(38.dp)),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                        Ico("film", Color(0xFFDDE3E8), 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("오늘의 ${engine.region.name} · 1분 02초", style = ts(30, Bold))
                        Spacer(Modifier.weight(1f))
                        Chip("정차 중에만 재생해요", tone = "gray")
                    }
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(29.dp)).background(Color.Black)) {
                        ReelView(engine, ui.reelSeq, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(30.dp))
                    Scenes(ui, engine, rec)
                    Spacer(Modifier.weight(1f))
                }
                ToastView(ui, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
            }
        }
        Column(
            (if (wide) Modifier.width(660.dp) else Modifier.weight(1f)).fillMaxHeight().clip(RoundedCornerShape(38.dp)).background(C.panel)
                .border(1.dp, C.line, RoundedCornerShape(38.dp)),
        ) {
            PanelHeader(ui, engine)
            Column(Modifier.weight(1f).padding(start = 30.dp, end = 30.dp, bottom = 34.dp)) {
                Spacer(Modifier.height(26.dp))
                Text("여행 영상과 티켓이 나왔어요", style = ts(40, Bold, lh = 1.2f))
                Spacer(Modifier.height(26.dp))
                Ticket(ui, rec)
                Spacer(Modifier.height(20.dp))
                SentCard(ui, rec)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    BigButton("다시 보기", Modifier.weight(1f), primary = false, icon = "replay") { engine.replayRecord() }
                    BigButton("휴대폰으로 받기", Modifier.weight(1.25f), icon = "qr") { engine.toggleQr(true) }
                }
            }
        }
    }
}

@Composable
private fun Ticket(ui: Ui, rec: Record) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(29.dp)).background(Brush.verticalGradient(listOf(PaperTop, PaperBottom)))) {
        Row(
            Modifier.fillMaxWidth().height(80.dp).background(C.navy).padding(horizontal = 29.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico("ticket", Color.White, 30.dp)
            Spacer(Modifier.width(12.dp))
            Text("여행 티켓", style = ts(26, Bold, Color.White, 1.1f))
            Spacer(Modifier.weight(1f))
            Text(ui.trip.date, style = ts(23, Semi, DateBlue, 1.1f))
        }
        Row(Modifier.padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(ui.trip.from, style = ts(58, Heavy, PaperInk, 1.0f))
                Spacer(Modifier.height(9.dp))
                Text("${rec.startAt} 출발", style = ts(22, Bold, PaperSub, 1.1f))
            }
            Canvas(Modifier.weight(1f).height(4.dp).padding(horizontal = 23.dp)) {
                drawLine(PaperLine, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(13f, 10f)))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(ui.trip.to, style = ts(58, Heavy, PaperInk, 1.0f))
                Spacer(Modifier.height(9.dp))
                Text("${rec.entryAt} 도착", style = ts(22, Bold, PaperSub, 1.1f))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 26.dp)) {
            drawLine(PaperCut, Offset(0f, 1f), Offset(size.width, 1f), 3.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)))
        }
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row {
                TicketStat("달린 길", "${rec.km}km", Modifier.weight(1f))
                TicketStat("들른 곳", "${rec.visited.size}곳", Modifier.weight(1f))
            }
            Row {
                TicketStat("들은 설명", "${rec.stories.size}편", Modifier.weight(1f))
                if (rec.photos.isNotEmpty()) TicketStat("사진", "${rec.photos.size}장", Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 영상에 들어간 장면: 들른 곳과 들려준 명소를 시간 순서로 */
@Composable
private fun Scenes(ui: Ui, engine: TripEngine, rec: Record) {
    val items = buildList {
        add(Triple("${engine.region.name} 요금소", "gyeongju_2.jpg", rec.entryAt))
        for (id in rec.visited) {
            val p = ui.trip.places.firstOrNull { it.id == id } ?: continue
            add(Triple(p.name, p.photo, if (p.kind == "food") "점심" else "들른 곳"))
        }
        for (id in rec.stories) {
            val s = engine.region.sight(id) ?: continue
            add(Triple(s.name, s.photo, "들은 설명"))
        }
    }.take(6)
    Text("영상에 담긴 장면", style = ts(24, Semi, C.faint))
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (name, photo, tag) ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(C.card2), contentAlignment = Alignment.Center) {
                    if (photo.isNotBlank() && !photo.startsWith("food")) Photo(photo, Modifier.fillMaxSize(), maxW = 360)
                    else Ico("food", C.sub, 34.dp)
                }
                Spacer(Modifier.height(8.dp))
                Text(name, style = ts(22, Bold, lh = 1.1f), maxLines = 1)
                Text(tag, style = ts(19, Medium, C.faint, 1.1f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TicketStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = ts(20, Bold, PaperSub, 1.1f))
        Spacer(Modifier.height(3.dp))
        Text(value, style = ts(35, Heavy, PaperInk, 1.1f), maxLines = 1)
    }
}

@Composable
private fun SentCard(ui: Ui, rec: Record) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(C.card).padding(horizontal = 26.dp, vertical = 23.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico("send", Cyan2, 29.dp)
            Spacer(Modifier.width(12.dp))
            Text("가족 휴대폰 ${rec.sentTo}대에 보냈어요", style = ts(28, Bold, lh = 1.2f))
        }
        Spacer(Modifier.height(17.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ms = ui.trip.members
            Box(Modifier.width((43 * (ms.size - 1) + 52).dp).height(52.dp)) {
                ms.forEachIndexed { i, m ->
                    Box(Modifier.offset(x = (43 * i).dp).size(52.dp).clip(CircleShape).background(C.card), contentAlignment = Alignment.Center) {
                        Avatar(m.name, Color(m.color), 46.dp)
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Text("여행 링크로 바로 저장", style = ts(23, Semi, C.sub))
        }
    }
}

/** 여행 영상(HTML 애니메이션)을 앱 안 WebView 로 재생. 휴대폰 웹과 같은 파일을 씀 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReelView(engine: TripEngine, seq: Int, modifier: Modifier) {
    key(seq) {
        val json = remember { engine.reelJson() }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(CarReel(json, engine), "CarReel")
                    loadUrl("file:///android_asset/reel/index.html?code=${engine.tripCode}&car=1")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private class CarReel(private val json: String, private val engine: TripEngine) {
    @JavascriptInterface
    fun data(): String = json

    @JavascriptInterface
    fun ended() {
        engine.scope.launch(kotlinx.coroutines.Dispatchers.Main) { engine.onReelEnded() }
    }
}

object Qr {
    fun make(text: String, size: Int = 520): ImageBitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text.ifBlank { " " }, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m[x, y]) 0xFF0B0E11.toInt() else -1
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp.asImageBitmap()
    }
}

