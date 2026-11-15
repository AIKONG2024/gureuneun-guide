package app.gureuneun.guide.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 명소 사진(assets/photos)을 줄여서 읽고 기억해 둠 */
object Photos {
    private val cache = LruCache<String, ImageBitmap>(28)
    private var credits: Map<String, String>? = null

    fun load(ctx: Context, name: String, maxW: Int): ImageBitmap? {
        val key = "$name@$maxW"
        cache.get(key)?.let { return it }
        val path = if (name.contains("/")) name else "photos/$name"
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }!!.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }

    /** 사진 출처 한 줄 ("사진 Basile Morin · CC BY-SA 4.0") */
    fun credit(ctx: Context, name: String?): String? {
        if (name.isNullOrBlank()) return null
        val m = credits ?: runCatching {
            val a = JSONArray(ctx.assets.open("photos/credits.json").bufferedReader().use { it.readText() })
            (0 until a.length()).associate { i ->
                val o = a.getJSONObject(i)
                o.getString("file") to "사진 " + o.optString("author").substringBefore(" from ") + " · " + o.optString("license")
            }
        }.getOrDefault(emptyMap()).also { credits = it }
        return m[name]
    }
}

@Composable
fun Photo(name: String?, modifier: Modifier = Modifier, maxW: Int = 1000, align: Alignment = Alignment.Center) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, name, maxW) {
        value = withContext(Dispatchers.IO) { name?.takeIf { it.isNotBlank() }?.let { Photos.load(ctx, it, maxW) } }
    }
    val b = img
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop, alignment = align)
    else Box(modifier.background(C.card2))
}
