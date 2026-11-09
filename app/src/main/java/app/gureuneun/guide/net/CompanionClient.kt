package app.gureuneun.guide.net

import android.content.SharedPreferences
import app.gureuneun.guide.BuildConfig
import app.gureuneun.guide.sdk.GLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 여행 그룹 서버 연결부.
 * 차는 그룹을 만들거나(네 자리 번호) 다른 차의 그룹에 들어가고,
 * 이야기가 시작될 때 휴대폰 화면을 같이 바꾸고, 휴대폰에서 고른 장소를 받음.
 * 차의 실시간 위치는 보내지 않음.
 */
class CompanionClient(private val scope: CoroutineScope, private val prefs: SharedPreferences) {

    data class State(
        val baseUrl: String,
        val online: Boolean = false,
        val code: String? = null,
        val joinUrl: String? = null,
        val publicBase: String? = null,
        val phones: Int = 0,
        val cars: List<String> = emptyList(),
    )

    val state = MutableStateFlow(State(baseUrl = prefs.getString("companion_url", null) ?: BuildConfig.COMPANION_URL))
    val incoming = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var carName: String = "1호차"
    private val json = "application/json; charset=utf-8".toMediaType()

    fun setBaseUrl(url: String) {
        prefs.edit().putString("companion_url", url).apply()
        state.update { it.copy(baseUrl = url) }
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(state.value.baseUrl.trimEnd('/') + path)
                .method(method, body?.toString()?.toRequestBody(json))
                .build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null }
        }.getOrNull()
    }

    /** 새 그룹을 만들고 접속. 서버가 없으면 null */
    suspend fun createGroup(scenarioId: String, lang: String, carName: String): String? {
        this.carName = carName
        val res = call("POST", "/api/groups", JSONObject().put("scenarioId", scenarioId).put("lang", lang).put("carName", carName)) ?: return null
        val code = res.optString("code").ifBlank { return null }
        state.update { it.copy(code = code, joinUrl = res.optString("joinUrl").ifBlank { null }, publicBase = res.optString("publicBase").ifBlank { null }) }
        connect(code)
        return code
    }

    /** 다른 차가 만든 그룹에 들어감 */
    suspend fun joinGroup(code: String, carName: String): JSONObject? {
        this.carName = carName
        val res = call("GET", "/api/groups/$code") ?: return null
        state.update { it.copy(code = code, joinUrl = res.optString("joinUrl").ifBlank { null }, publicBase = res.optString("publicBase").ifBlank { null }) }
        connect(code)
        return res
    }

    private fun connect(code: String) {
        socket?.cancel()
        val wsUrl = state.value.baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws?code=$code&role=car&name=" + java.net.URLEncoder.encode(carName, "UTF-8")
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                GLog.i("Group", "그룹 서버 연결됨 ($code)")
                state.update { it.copy(online = true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (msg.optString("type") == "members") {
                    val cars = msg.optJSONArray("cars")
                    state.update {
                        it.copy(
                            phones = msg.optInt("phones"),
                            cars = (0 until (cars?.length() ?: 0)).map { i -> cars!!.optString(i) },
                        )
                    }
                }
                incoming.tryEmit(msg)
            }

            override fun onClosed(webSocket: WebSocket, code2: Int, reason: String) {
                state.update { it.copy(online = false) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                GLog.w("Group", "그룹 서버 연결 끊김", t)
                state.update { it.copy(online = false) }
                scheduleReconnect(code)
            }
        })
    }

    private fun scheduleReconnect(code: String) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(4000)
            if (state.value.code == code && !state.value.online) connect(code)
        }
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        val s = socket ?: return
        payload.put("type", type)
        payload.put("from", carName)
        s.send(payload.toString())
    }

    suspend fun uploadRecord(record: JSONObject): Boolean {
        val code = state.value.code ?: return false
        return call("POST", "/api/groups/$code/record", record) != null
    }

    fun leave() {
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        state.update { it.copy(online = false, code = null, joinUrl = null, phones = 0, cars = emptyList()) }
    }

    suspend fun ping(): Boolean = call("GET", "/api/info") != null
}
