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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 여행 그룹 서버 연결부.
 * 차는 그룹을 만들거나(네 자리 번호) 다른 차의 그룹에 들어가고,
 * 이야기가 시작될 때 휴대폰 화면을 같이 바꾸고, 휴대폰에서 고른 장소를 받음.
 * 차의 실시간 위치는 보내지 않음.
 *
 * 서버 규약 (companion/server.js)
 * - 그룹 만들기: POST /api/groups → { code, carKey, carNo, carName, joinUrl, recordUrl, publicBase }
 * - 다른 차 그룹에 들어가기: POST /api/groups/{code}/join → 같은 모양 (차 이름은 서버가 "2호차" 식으로 붙임)
 * - WebSocket /ws?code=&role=car&key=carKey, 기록 올리기는 X-Car-Key 머리글
 * - 서버가 꺼져 있어 그룹을 못 만들었으면 기다렸다가 서버가 켜지면 다시 만들고 state.code 를 바꿈
 */
class CompanionClient(private val scope: CoroutineScope, private val prefs: SharedPreferences) {

    data class State(
        val baseUrl: String,
        val online: Boolean = false,
        val reachable: Boolean = false,
        val code: String? = null,
        val joinUrl: String? = null,
        val publicBase: String? = null,
        val phones: Int = 0,
        val cars: List<String> = emptyList(),
        /** 서버가 붙인 이 차의 이름과 번호. 예: "2호차", 2 */
        val carName: String? = null,
        val carNo: Int? = null,
        /** 휴대폰에서 기록 탭으로 바로 여는 주소 (기록 카드 QR용) */
        val recordUrl: String? = null,
        /** 서버가 꺼져 있어 그룹 만들기(들어가기)를 다시 해 보는 중 */
        val waiting: Boolean = false,
    )

    val state = MutableStateFlow(State(baseUrl = prefs.getString("companion_url", null) ?: BuildConfig.COMPANION_URL))
    val incoming = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var generation = 0
    private var watching = false
    private var reconnectJob: Job? = null
    private var retryJob: Job? = null
    @Volatile private var reconnectDelayMs = FIRST_RECONNECT_MS
    @Volatile private var stopReconnect = false
    @Volatile private var carKey: String? = null
    @Volatile private var carName: String = "1호차"
    /** 이 차가 만든 그룹이면, 서버 자료가 지워져 그룹이 없어졌을 때 다시 만들 값 */
    @Volatile private var lastCreate: Triple<String, String, String>? = null
    private val json = "application/json; charset=utf-8".toMediaType()

    private class Reply(val code: Int, val body: JSONObject?)

    /** 한 번 해 본 결과: 됨, 서버가 없거나 바빠서 나중에 다시, 다시 해도 안 됨 */
    private enum class Outcome { OK, RETRY, FAIL }

    fun setBaseUrl(url: String) {
        prefs.edit().putString("companion_url", url).apply()
        state.update { it.copy(baseUrl = url) }
    }

    /** 서버에 닿지 못하면 null. 닿으면 응답 코드와 JSON 본문 */
    private suspend fun request(method: String, path: String, body: JSONObject? = null, withCarKey: Boolean = false): Reply? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = (body ?: if (method == "POST") JSONObject() else null)?.toString()?.toRequestBody(json)
            val builder = Request.Builder().url(state.value.baseUrl.trimEnd('/') + path).method(method, payload)
            if (withCarKey) carKey?.let { builder.header("X-Car-Key", it) }
            http.newCall(builder.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                Reply(r.code, runCatching { JSONObject(text) }.getOrNull())
            }
        }.getOrNull()
    }

    /** 새 그룹을 만들고 접속. 서버가 없으면 null 을 돌려주고, 서버가 켜질 때까지 기다렸다가 다시 만들어 state.code 를 바꿈 */
    suspend fun createGroup(scenarioId: String, lang: String, carName: String): String? {
        cancelRetry()
        this.carName = carName
        lastCreate = Triple(scenarioId, lang, carName)
        return when (tryCreate(scenarioId, lang, carName)) {
            Outcome.OK -> state.value.code
            Outcome.RETRY -> { retryLater { tryCreate(scenarioId, lang, carName) }; null }
            Outcome.FAIL -> null
        }
    }

    private suspend fun tryCreate(scenarioId: String, lang: String, carName: String): Outcome {
        val r = request("POST", "/api/groups", JSONObject().put("scenarioId", scenarioId).put("lang", lang).put("carName", carName))
            ?: return Outcome.RETRY
        val body = r.body
        val code = body?.optString("code").orEmpty()
        return when {
            r.code in 200..299 && body != null && CODE.matches(code) -> { adopt(code, body); Outcome.OK }
            r.code == 429 || r.code >= 500 -> Outcome.RETRY
            else -> Outcome.FAIL
        }
    }

    /** 다른 차가 만든 그룹에 들어감. 서버가 없으면 null 을 돌려주고, 서버가 켜지면 다시 들어가 state.code 를 바꿈 */
    suspend fun joinGroup(code: String, carName: String): JSONObject? {
        cancelRetry()
        this.carName = carName
        lastCreate = null
        if (!CODE.matches(code)) return null
        var joined: JSONObject? = null
        when (tryJoin(code, carName) { joined = it }) {
            Outcome.OK -> return joined
            Outcome.RETRY -> retryLater { tryJoin(code, carName) {} }
            Outcome.FAIL -> {}
        }
        return null
    }

    private suspend fun tryJoin(code: String, carName: String, onJoined: (JSONObject) -> Unit): Outcome {
        val r = request("POST", "/api/groups/$code/join", JSONObject().put("carName", carName)) ?: return Outcome.RETRY
        val body = r.body
        return when {
            r.code in 200..299 && body != null -> { adopt(code, body); onJoined(body); Outcome.OK }
            // 예전 서버(차 키가 없던 때)는 join 이 없어 "unknown" 으로 답함. 그때는 예전처럼 들어감
            r.code == 404 && body?.optString("error") == "unknown" -> legacyJoin(code, onJoined)
            r.code == 429 || r.code >= 500 -> Outcome.RETRY
            else -> Outcome.FAIL
        }
    }

    private suspend fun legacyJoin(code: String, onJoined: (JSONObject) -> Unit): Outcome {
        val r = request("GET", "/api/groups/$code") ?: return Outcome.RETRY
        val body = r.body
        return when {
            r.code in 200..299 && body != null -> { adopt(code, body); onJoined(body); Outcome.OK }
            r.code >= 500 -> Outcome.RETRY
            else -> Outcome.FAIL
        }
    }

    /** 서버가 준 번호와 키로 접속을 걸고 state 를 바꿈. 접속을 먼저 걸어, 엔진이 번호를 보고 바로 보내는 메시지도 줄에 들어가게 함 */
    private fun adopt(code: String, body: JSONObject) {
        carKey = body.optString("carKey").ifBlank { null }
        body.optString("carName").ifBlank { null }?.let { carName = it }
        connect(code)
        state.update {
            it.copy(
                code = code,
                joinUrl = body.optString("joinUrl").ifBlank { null },
                recordUrl = body.optString("recordUrl").ifBlank { null },
                publicBase = body.optString("publicBase").ifBlank { null },
                carName = carName,
                carNo = body.optInt("carNo", 0).takeIf { n -> n > 0 },
                reachable = true,
                waiting = false,
            )
        }
    }

    /** 서버가 꺼져 있으면 3초, 6초, 12초, 15초... 마다 다시 해 봄 */
    private fun retryLater(attempt: suspend () -> Outcome) {
        retryJob?.cancel()
        state.update { it.copy(waiting = true) }
        GLog.i("Group", "그룹 서버를 기다리는 중. 서버가 켜지면 그룹에 다시 들어가요")
        retryJob = scope.launch {
            var wait = 3000L
            while (isActive) {
                delay(wait)
                when (attempt()) {
                    Outcome.OK -> return@launch
                    Outcome.FAIL -> { state.update { it.copy(waiting = false) }; return@launch }
                    Outcome.RETRY -> wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        state.update { it.copy(waiting = false) }
    }

    private fun connect(code: String) {
        val gen = synchronized(lock) {
            stopReconnect = false
            reconnectJob?.cancel()
            reconnectJob = null
            socket?.cancel()
            socket = null
            ++generation
        }
        val key = carKey
        val url = wsBase() + "/ws?code=$code&role=car" + (if (key != null) "&key=" + enc(key) else "") + "&name=" + enc(carName)
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            // 다시 접속하면 예전 소켓의 알림은 버림 (번호로 구분해서, 소켓을 넣기 전에 온 알림도 놓치지 않음)
            private fun current() = synchronized(lock) { gen == generation }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!current()) return
                reconnectDelayMs = FIRST_RECONNECT_MS
                GLog.i("Group", "그룹 서버 연결됨 ($code)")
                state.update { it.copy(online = true, reachable = true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!current()) return
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "members", "hello" -> state.update { s ->
                        val you = msg.optJSONObject("you")
                        s.copy(
                            phones = if (msg.has("phones")) msg.optInt("phones") else s.phones,
                            cars = msg.optJSONArray("cars")?.let { names(it) } ?: s.cars,
                            carName = you?.optString("carName")?.ifBlank { null } ?: s.carName,
                            carNo = you?.optInt("carNo", 0)?.takeIf { n -> n > 0 } ?: s.carNo,
                        )
                    }
                    "error" -> refused(code, msg.optString("error"))
                }
                incoming.tryEmit(msg)
            }

            override fun onClosing(webSocket: WebSocket, code2: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code2: Int, reason: String) {
                if (!current()) return
                state.update { it.copy(online = false) }
                when (code2) {
                    4401 -> refused(code, "bad key")
                    4404 -> refused(code, "no such group")
                    4000 -> stopReconnect = true // 같은 차가 새로 붙어 이 연결은 밀려남
                }
                scheduleReconnect(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!current()) return
                GLog.w("Group", "그룹 서버 연결 끊김", t)
                state.update { it.copy(online = false) }
                scheduleReconnect(code)
            }
        })
        synchronized(lock) { if (gen == generation) socket = ws else ws.cancel() }
    }

    /** 서버가 접속을 받지 않음(키가 틀렸거나 그룹이 없어짐). 이 차가 만든 그룹이 없어졌으면 새로 만들어 이어 감(번호가 바뀜) */
    private fun refused(code: String, error: String) {
        if (error != "bad key" && error != "no such group") return
        if (stopReconnect && state.value.code != code) return
        stopReconnect = true
        GLog.w("Group", "그룹 서버가 접속을 받지 않음 ($error)")
        val create = lastCreate
        if (error == "no such group" && create != null && state.value.code == code) {
            state.update { it.copy(code = null, joinUrl = null, recordUrl = null, online = false) }
            retryLater { tryCreate(create.first, create.second, create.third) }
        }
    }

    /** 끊기면 2초, 4초, 8초, 15초... 뒤에 다시 붙음 (서버가 닫아도 다시 붙음) */
    private fun scheduleReconnect(code: String) {
        if (stopReconnect) return
        synchronized(lock) {
            if (reconnectJob?.isActive == true) return
            val wait = reconnectDelayMs
            reconnectDelayMs = (wait * 2).coerceAtMost(MAX_RETRY_MS)
            reconnectJob = scope.launch {
                delay(wait)
                if (state.value.code == code && !state.value.online && !stopReconnect) connect(code)
            }
        }
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        val s = synchronized(lock) { socket } ?: return
        payload.put("type", type)
        payload.put("from", carName)
        s.send(payload.toString())
    }

    /** 여행 기록 올리기. 서버가 준 차 키를 X-Car-Key 머리글로 보냄 */
    suspend fun uploadRecord(record: JSONObject): Boolean {
        val code = state.value.code ?: return false
        val r = request("POST", "/api/groups/$code/record", record, withCarKey = true) ?: return false
        return r.code in 200..299
    }

    fun leave() {
        cancelRetry()
        lastCreate = null
        synchronized(lock) {
            generation++
            stopReconnect = true
            reconnectJob?.cancel()
            reconnectJob = null
            socket?.close(1000, "bye")
            socket = null
        }
        carKey = null
        reconnectDelayMs = FIRST_RECONNECT_MS
        state.update { it.copy(online = false, code = null, joinUrl = null, recordUrl = null, carName = null, carNo = null, phones = 0, cars = emptyList(), waiting = false) }
    }

    suspend fun ping(): Boolean {
        val code = request("GET", "/api/info")?.code ?: 0
        val ok = code in 200..299
        state.update { it.copy(reachable = ok) }
        return ok
    }

    /** 처음 화면에서 그룹 서버가 켜져 있는지 주기적으로 확인 */
    fun watchReachability() {
        if (watching) return
        watching = true
        scope.launch {
            while (true) {
                if (state.value.code == null) ping()
                delay(8000)
            }
        }
    }

    private fun wsBase(): String {
        val base = state.value.baseUrl.trimEnd('/')
        return when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
            else -> base
        }
    }

    private fun names(a: JSONArray): List<String> = (0 until a.length()).map { i -> a.optString(i) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val CODE = Regex("^\\d{4}$")
        private const val FIRST_RECONNECT_MS = 2000L
        private const val MAX_RETRY_MS = 15000L
    }
}
