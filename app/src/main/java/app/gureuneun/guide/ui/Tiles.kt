package app.gureuneun.guide.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * 오프라인 지도 타일 (assets/tiles). 경주 시연 구간을 미리 렌더해 둔 웹 메르카토르 타일을 씀.
 * 좌표는 '타일 묶음 왼쪽 위' 기준 픽셀(local)로 다룸 (실수 정밀도 때문에 세계 좌표를 그대로 쓰지 않음).
 */
class Tiles private constructor(private val ctx: Context) {
    val z: Int
    val tile: Int
    val txMin: Int
    val txMax: Int
    val tyMin: Int
    val tyMax: Int
    val bg: Int
    val ready: Boolean
    private val world: Double

    init {
        val m = runCatching { JSONObject(ctx.assets.open("tiles/meta.json").bufferedReader().use { it.readText() }) }.getOrNull()
        z = m?.optInt("z", 16) ?: 16
        tile = m?.optInt("tile", 512) ?: 512
        world = tile.toDouble() * (1 shl z)
        if (m != null) {
            txMin = m.getInt("txMin"); txMax = m.getInt("txMax"); tyMin = m.getInt("tyMin"); tyMax = m.getInt("tyMax")
            bg = runCatching { android.graphics.Color.parseColor(m.optString("bg", "#10161C")) }.getOrDefault(0xFF10161C.toInt())
            ready = true
        } else {
            txMin = floor(Geo.worldX(129.160, world) / tile).toInt()
            txMax = floor(Geo.worldX(129.236, world) / tile).toInt()
            tyMin = floor(Geo.worldY(35.850, world) / tile).toInt()
            tyMax = floor(Geo.worldY(35.800, world) / tile).toInt()
            bg = 0xFF10161C.toInt()
            ready = false
        }
    }

    val version = MutableStateFlow(0)
    private val cache = LruCache<Long, ImageBitmap>(90)
    private val loading = ConcurrentHashMap.newKeySet<Long>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "tiles").apply { isDaemon = true } }

    fun local(p: LatLng): Offset =
        Offset((Geo.worldX(p.lon, world) - txMin.toDouble() * tile).toFloat(), (Geo.worldY(p.lat, world) - tyMin.toDouble() * tile).toFloat())

    /** 있으면 바로 돌려주고, 없으면 읽기를 걸어 둠 (다 읽으면 version 이 올라감) */
    fun get(tx: Int, ty: Int): ImageBitmap? {
        if (!ready || tx < txMin || tx > txMax || ty < tyMin || ty > tyMax) return null
        val key = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
        cache.get(key)?.let { return it }
        if (key in missing || !loading.add(key)) return null
        io.execute {
            val img = runCatching {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                ctx.assets.open("tiles/z$z/${tx}_${ty}.jpg").use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
            }.getOrNull()
            if (img != null) cache.put(key, img) else missing.add(key)
            loading.remove(key)
            version.value = version.value + 1
        }
        return null
    }

    companion object {
        @Volatile private var inst: Tiles? = null
        fun get(ctx: Context): Tiles = inst ?: synchronized(this) { inst ?: Tiles(ctx.applicationContext).also { inst = it } }
    }
}
