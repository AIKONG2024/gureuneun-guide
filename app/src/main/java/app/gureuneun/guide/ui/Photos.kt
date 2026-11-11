package app.gureuneun.guide.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 명소 사진. 휴대폰 페이지와 같은 파일(companion/web/photos)을 앱에도 넣어 씀.
 * 파일 이름은 "명소_번호.jpg", 밤 사진은 "명소_night_번호.jpg". 찍은 사람과 라이선스는 credits.json 에 있음
 */
object Photos {
    class Credit(val file: String, val title: String, val author: String, val license: String, val source: String)

    private var names: Set<String>? = null
    private var credits: Map<String, Credit>? = null
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    /** 그 명소의 사진 파일들. 밤이고 밤 사진이 있으면 밤 사진을 씀. 파일 이름을 그대로 주면(표지 사진) 그 한 장 */
    fun files(ctx: Context, id: String?, night: Boolean): List<String> {
        if (id == null) return emptyList()
        val all = names ?: (runCatching { ctx.assets.list("photos")?.toSet() }.getOrNull() ?: emptySet()).also { names = it }
        if ("$id.jpg" in all) return listOf("$id.jpg")
        fun of(prefix: String) = (1..4).map { "${prefix}_$it.jpg" }.filter { it in all }
        return (if (night) of("${id}_night") else emptyList()).ifEmpty { of(id) }
    }

    fun has(ctx: Context, id: String?): Boolean = files(ctx, id, false).isNotEmpty()

    /** shrink 만큼 줄여 읽음 (1 그대로, 2 절반, 4 는 목록의 동그란 사진) */
    fun load(ctx: Context, file: String, shrink: Int = 1): ImageBitmap? {
        val key = "$shrink/$file"
        cache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inSampleSize = shrink }
        val bmp = runCatching { ctx.assets.open("photos/$file").use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull() ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    fun credit(ctx: Context, file: String): Credit? = allCredits(ctx)[file]

    fun allCredits(ctx: Context): Map<String, Credit> = credits ?: runCatching {
        val a = JSONArray(ctx.assets.open("photos/credits.json").bufferedReader().use { it.readText() })
        (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
            o.getString("file") to Credit(o.getString("file"), o.optString("title"), o.optString("author"), o.optString("license"), o.optString("source"))
        }
    }.getOrDefault(emptyMap()).also { credits = it }
}

/** 그 명소의 첫 사진 한 장 */
@Composable
fun Photo(id: String?, night: Boolean, modifier: Modifier = Modifier, shrink: Int = 1) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, id, night, shrink) {
        value = withContext(Dispatchers.IO) { Photos.files(ctx, id, night).firstOrNull()?.let { Photos.load(ctx, it, shrink) } }
    }
    Box(modifier.background(C.brandDeep).clipToBounds()) {
        img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * 화면을 채우는 명소 사진. 곳이 바뀌면 부드럽게 넘어감.
 * moving 이면(차가 서 있을 때만) 사진을 천천히 키우며 몇 초마다 다음 장으로 넘김. 달리는 동안에는 한 장을 가만히 둠.
 * onFile 로 지금 보이는 파일 이름을 알려 줌 (찍은 사람 표시)
 */
@Composable
fun PhotoStage(id: String?, night: Boolean, moving: Boolean, modifier: Modifier = Modifier, onFile: (String?) -> Unit = {}) {
    val ctx = LocalContext.current
    val files = remember(id, night) { Photos.files(ctx, id, night) }
    var idx by remember(id, night) { mutableIntStateOf(0) }
    LaunchedEffect(id, night, moving, files.size) {
        while (moving && files.size > 1) {
            delay(7000)
            idx = (idx + 1) % files.size
        }
    }
    val file = files.getOrNull(idx)
    LaunchedEffect(file) { onFile(file) }
    Box(modifier.background(C.brandDeep).clipToBounds()) {
        Crossfade(targetState = file, animationSpec = tween(700), label = "photo") { f ->
            val img by produceState<ImageBitmap?>(null, f) { value = f?.let { withContext(Dispatchers.IO) { Photos.load(ctx, it) } } }
            val zoom = remember(f) { Animatable(1f) }
            LaunchedEffect(f, moving) { if (moving) zoom.animateTo(1.08f, tween(9000, easing = LinearEasing)) else zoom.snapTo(1f) }
            img?.let {
                Image(
                    it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                )
            }
        }
    }
}

