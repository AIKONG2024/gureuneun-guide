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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 차가 서 있을 때 차 화면에 크게 띄우는 명소 영상.
 * 휴대폰과 같은 3D 장면에서 뽑은 장면 세 컷을 천천히 확대하며 이어 보여 줌.
 * 차 화면에서 3D 나 동영상을 실시간으로 돌리지 않아 가볍고 안정적임.
 */
@Composable
fun PlaceClip(model: String, name: String, night: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val frames = remember(model, night) { loadPosters(ctx, model, night) }
    var idx by remember(model, night) { mutableIntStateOf(0) }
    LaunchedEffect(model, night, frames.size) {
        while (frames.size > 1) {
            delay(3800)
            idx = (idx + 1) % frames.size
        }
    }
    Box(modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), G.bg)))) {
        if (frames.isNotEmpty()) {
            Crossfade(targetState = idx, animationSpec = tween(900), label = "poster") { i ->
                val zoom = remember(i) { Animatable(1.0f) }
                LaunchedEffect(i) { zoom.animateTo(1.12f, tween(4700, easing = LinearEasing)) }
                Image(
                    bitmap = frames[i.coerceIn(0, frames.size - 1)],
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        translationX = (zoom.value - 1f) * 220f * (if (i % 2 == 0) 1f else -1f)
                    },
                )
            }
        }
        Text(
            name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
        )
    }
}

private fun loadPosters(ctx: Context, model: String, night: Boolean): List<ImageBitmap> {
    val names = runCatching { ctx.assets.list("posters")?.toSet() }.getOrNull() ?: emptySet()
    val prefix = if (night && "${model}_night_0.jpg" in names) "${model}_night" else model
    return (0..2).mapNotNull { i ->
        val f = "${prefix}_$i.jpg"
        if (f !in names) null else runCatching {
            ctx.assets.open("posters/$f").use { BitmapFactory.decodeStream(it).asImageBitmap() }
        }.getOrNull()
    }
}
