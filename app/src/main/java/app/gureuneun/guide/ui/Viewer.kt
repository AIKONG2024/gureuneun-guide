package app.gureuneun.guide.ui

import android.content.Context
import android.graphics.BitmapFactory
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

/**
 * 명소 장면 사진. 휴대폰과 같은 3D 장면에서 뽑은 컷(posters/모형_번호.jpg)을 씀.
 * 사진 풀기는 화면 스레드 밖에서 하고, 작은 카드에는 반으로 줄여 읽어 메모리를 아낌
 */
@Composable
fun rememberPosters(model: String?, night: Boolean, count: Int = 3, half: Boolean = false): List<ImageBitmap> {
    val ctx = LocalContext.current
    val frames by produceState(emptyList<ImageBitmap>(), model, night, count, half) {
        value = if (model == null) emptyList() else withContext(Dispatchers.IO) { loadPosters(ctx, model, night, count, half) }
    }
    return frames
}

/** 한 장짜리 표지 (처음 화면의 여행지 카드) */
@Composable
fun Poster(model: String?, night: Boolean, modifier: Modifier = Modifier) {
    val img = rememberPosters(model, night, 1, half = true).firstOrNull()
    Box(modifier.background(C.brandDeep).clipToBounds()) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 차가 서 있을 때 크게 띄우는 명소 장면. 컷을 천천히 확대하며 이어 보여 줌 (달리는 동안에는 쓰지 않음).
 * 차 화면에서 3D 나 동영상을 실시간으로 돌리지 않아 가볍고 안정적임
 */
@Composable
fun PlaceClip(model: String, night: Boolean, modifier: Modifier = Modifier) {
    val frames = rememberPosters(model, night)
    var idx by remember(model, night) { mutableIntStateOf(0) }
    LaunchedEffect(model, night, frames.size) {
        while (frames.size > 1) {
            delay(4200)
            idx = (idx + 1) % frames.size
        }
    }
    // 천천히 확대하는 사진이 칸 밖으로 넘쳐 그려지지 않게 자름
    Box(modifier.background(C.brandDeep).clipToBounds()) {
        if (frames.isNotEmpty()) {
            Crossfade(targetState = idx, animationSpec = tween(900), label = "poster") { i ->
                val zoom = remember(i) { Animatable(1.0f) }
                LaunchedEffect(i) { zoom.animateTo(1.10f, tween(5100, easing = LinearEasing)) }
                Image(
                    bitmap = frames[i.coerceIn(0, frames.size - 1)],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        translationX = (zoom.value - 1f) * 200f * (if (i % 2 == 0) 1f else -1f)
                    },
                )
            }
        }
    }
}

private fun loadPosters(ctx: Context, model: String, night: Boolean, count: Int, half: Boolean): List<ImageBitmap> {
    val names = runCatching { ctx.assets.list("posters")?.toSet() }.getOrNull() ?: emptySet()
    val prefix = if (night && "${model}_night_0.jpg" in names) "${model}_night" else model
    val opts = BitmapFactory.Options().apply { if (half) inSampleSize = 2 }
    return (0 until count).mapNotNull { i ->
        val f = "${prefix}_${i}.jpg"
        if (f !in names) null else runCatching {
            ctx.assets.open("posters/${f}").use { BitmapFactory.decodeStream(it, null, opts)!!.asImageBitmap() }
        }.getOrNull()
    }
}
