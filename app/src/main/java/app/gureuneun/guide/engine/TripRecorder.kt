package app.gureuneun.guide.engine

import android.content.Context
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 여행 기록: 지나온 길, 명소를 지난 시각, 들은 이야기, 고른 장소, 창문과 날씨 같은 순간.
 * 끝나면 앱 안에 JSON 으로 저장하고, 그룹 서버가 있으면 그룹 페이지로 올림.
 */
class TripRecorder(private val context: Context) {

    data class Event(val time: Long, val type: String, val placeId: String?, val title: String, val detail: String = "")

    var tripId: String = ""
        private set
    var scenarioId: String = ""
        private set
    var lang: String = "ko"
        private set
    var startedAt: Long = 0
        private set
    var endedAt: Long = 0
        private set
    var distanceM: Double = 0.0
        private set

    val route = ArrayList<Pair<Long, LatLng>>()
    val events = ArrayList<Event>()

    fun begin(scenarioId: String, lang: String) {
        this.scenarioId = scenarioId
        this.lang = lang
        startedAt = System.currentTimeMillis()
        endedAt = 0
        tripId = "trip-$startedAt"
        distanceM = 0.0
        route.clear()
        events.clear()
    }

    fun point(p: LatLng, time: Long = System.currentTimeMillis()) {
        val last = route.lastOrNull()?.second
        if (last == null) {
            route.add(time to p)
            return
        }
        val d = Geo.distanceM(last, p)
        if (d >= 25) {
            distanceM += d
            route.add(time to p)
        }
    }

    fun event(type: String, placeId: String?, title: String, detail: String = "") {
        events.add(Event(System.currentTimeMillis(), type, placeId, title, detail))
    }

    fun end() {
        endedAt = System.currentTimeMillis()
        save()
    }

    fun passTime(placeId: String): Long? = events.firstOrNull { it.type == "pass" && it.placeId == placeId }?.time

    fun toJson(): JSONObject = JSONObject().apply {
        put("tripId", tripId)
        put("scenarioId", scenarioId)
        put("lang", lang)
        put("startedAt", startedAt)
        put("endedAt", if (endedAt == 0L) System.currentTimeMillis() else endedAt)
        put("distanceM", distanceM.toInt())
        put("route", JSONArray().apply {
            route.forEach { (t, p) -> put(JSONArray().put(t).put(p.lat).put(p.lon)) }
        })
        put("events", JSONArray().apply {
            events.forEach { e ->
                put(JSONObject().put("t", e.time).put("type", e.type).put("placeId", e.placeId ?: "").put("title", e.title).put("detail", e.detail))
            }
        })
    }

    private fun save() {
        runCatching {
            val dir = File(context.filesDir, "trips").apply { mkdirs() }
            File(dir, "$tripId.json").writeText(toJson().toString())
        }
    }
}
