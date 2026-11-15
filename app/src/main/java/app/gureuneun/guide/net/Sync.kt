package app.gureuneun.guide.net

import android.os.SystemClock
import app.gureuneun.guide.BuildConfig
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.GLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 동반 서버(여행 링크)와 맞춤. 차량이 원본이고 서버는 거울.
 * - 휴대폰이 담은 장소, 들르기, 사진은 서버의 inbox 에서 가져옴
 * - 차량의 여행 상태(일정, 지금 설명 중인 명소, 여행 모드, 기록)는 서버로 올림
 * 서버가 없어도 앱은 그대로 동작함 (휴대폰 연동만 빠짐)
 */
class Sync(private val engine: TripEngine) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
    private val base = BuildConfig.COMPANION_URL
    @Volatile private var dirty = true
    private var after = 0
    @Volatile private var lastOk = 0L

    fun dirty() {
        dirty = true
    }

    fun start() {
        engine.scope.launch(Dispatchers.IO) {
            var lastPush = 0L
            var infoAt = 0L
            var gotInfo = false
            var presenceAt = 0L
            while (isActive) {
                val t = SystemClock.elapsedRealtime()
                val code = engine.tripCode
                if (!gotInfo && t - infoAt > 8000) {
                    infoAt = t
                    get("/api/info")?.let { s ->
                        runCatching { JSONObject(s) }.getOrNull()?.let { o ->
                            gotInfo = true
                            withContext(Dispatchers.Main) { engine.onInfo(o.optString("lanUrl")) }
                        }
                    }
                }
                get("/api/trip/$code/inbox?after=$after")?.let { s ->
                    val arr = runCatching { JSONArray(s) }.getOrNull()
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until arr.length()) after = maxOf(after, arr.optJSONObject(i)?.optInt("seq") ?: 0)
                        withContext(Dispatchers.Main) { engine.onInbox(arr) }
                    }
                }
                if (dirty || t - lastPush > 3000) {
                    val body = withContext(Dispatchers.Main) { engine.stateJson().toString() }
                    if (put("/api/trip/$code/state", body)) {
                        dirty = false
                        lastPush = t
                    }
                }
                if (t - presenceAt > 2500) {
                    presenceAt = t
                    get("/api/trip/$code/presence")?.let { s ->
                        val n = runCatching { JSONObject(s).optInt("phones", 0) }.getOrDefault(0)
                        withContext(Dispatchers.Main) { engine.onPresence(n) }
                    }
                }
                val online = SystemClock.elapsedRealtime() - lastOk < 6000
                withContext(Dispatchers.Main) { engine.onOnline(online) }
                delay(if (dirty) 300 else 1100)
            }
        }
    }

    private fun get(path: String): String? = runCatching {
        http.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string().also { lastOk = SystemClock.elapsedRealtime() }
        }
    }.getOrNull()

    private fun put(path: String, json: String): Boolean = runCatching {
        val req = Request.Builder().url(base + path).put(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) lastOk = SystemClock.elapsedRealtime()
            r.isSuccessful
        }
    }.getOrElse {
        GLog.i("Sync", "동반 서버에 올리지 못함 (${it.javaClass.simpleName})")
        false
    }
}
