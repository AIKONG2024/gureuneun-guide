package app.gureuneun.guide.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * 차가 서 있을 때 차 화면에 크게 띄우는 명소 영상(3D 모형을 천천히 돌며 보여 주는 장면).
 * 휴대폰 그룹 페이지와 같은 뷰어(assets/web/viewer.html)를 씀.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlaceViewer(model: String, name: String, caption: String?, lang: String, rain: Boolean, night: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val web = remember {
        WebView(ctx).apply {
            setBackgroundColor(AColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/viewer.html?embed=car")
        }
    }
    LaunchedEffect(model, name, caption, lang, rain, night) {
        val payload = JSONObject().put("model", model).put("name", name).put("caption", caption ?: "").put("lang", lang).put("rain", rain).put("night", night)
        web.evaluateJavascript("window.GG && GG.show($payload)", null)
        // 페이지가 아직 안 떴을 때를 대비해 한 번 더 보냄
        kotlinx.coroutines.delay(1200)
        web.evaluateJavascript("window.GG && GG.show($payload)", null)
    }
    AndroidView(factory = { web }, modifier = modifier)
}
