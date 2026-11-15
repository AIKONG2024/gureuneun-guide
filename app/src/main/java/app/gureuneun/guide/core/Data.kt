package app.gureuneun.guide.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 지나는 명소 (한국관광공사 관광 정보를 바탕으로 정리한 시연용 문장) */
data class Sight(
    val id: String,
    val name: String,
    val tag: String,
    val pos: LatLng,
    val radiusM: Double,
    val photo: String,
    val short: String,
    val text: String,
    val kid: String,
)

data class Theme(
    val name: String,
    val color: Int,
    val deep: Int,
    val instrument: String,
    val music: String,
    val prelude: String,
    val welcome: String,
    val line: String,
)

data class OtherTheme(val id: String, val name: String, val theme: String, val color: Int, val instrument: String)

data class Maneuver(val pos: LatLng, val modifier: String)

class Region(
    val id: String,
    val name: String,
    val gateName: String,
    val gate: LatLng,
    val theme: Theme,
    val others: List<OtherTheme>,
    val sights: List<Sight>,
    val route: List<LatLng>,
    val approach: List<LatLng>,
    val maneuvers: List<Maneuver>,
) {
    fun sight(id: String) = sights.firstOrNull { it.id == id }

    companion object {
        fun color(s: String?, fallback: Int): Int = runCatching { android.graphics.Color.parseColor(s) }.getOrDefault(fallback)

        private fun pts(a: JSONArray?): List<LatLng> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { i -> a.getJSONArray(i).let { LatLng(it.getDouble(0), it.getDouble(1)) } }
        }

        fun load(ctx: Context): Region {
            val o = JSONObject(ctx.assets.open("data/gyeongju.json").bufferedReader().use { it.readText() })
            val g = o.getJSONObject("gate")
            val t = o.getJSONObject("theme")
            val route = pts(o.getJSONArray("route"))
            val approach = runCatching {
                pts(JSONArray(ctx.assets.open("data/approach.json").bufferedReader().use { it.readText() }))
            }.getOrDefault(emptyList())
            val gate = LatLng(g.getDouble("lat"), g.getDouble("lon"))
            val appr = approach.ifEmpty {
                // 접근 경로 자료가 없으면 요금소 북서쪽 1.5km 에서 곧게 들어옴
                val start = Geo.dest(gate, 318.0, 1500.0)
                (0..30).map { Geo.lerp(start, gate, it / 30.0) }
            }
            val sights = o.getJSONArray("sights").let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    Sight(
                        id = s.getString("id"), name = s.getString("name"), tag = s.optString("tag"),
                        pos = LatLng(s.getDouble("lat"), s.getDouble("lon")), radiusM = s.optDouble("radiusM", 300.0),
                        photo = s.optString("photo"), short = s.optString("short"), text = s.optString("text"), kid = s.optString("kid"),
                    )
                }
            }
            val others = o.optJSONArray("others")?.let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    OtherTheme(x.getString("id"), x.getString("name"), x.optString("theme"), color(x.optString("color"), 0xFF888888.toInt()), x.optString("instrument"))
                }
            } ?: emptyList()
            val mans = o.optJSONArray("maneuvers")?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONArray(i)
                    Maneuver(LatLng(m.getDouble(0), m.getDouble(1)), m.optString(3, "straight"))
                }
            } ?: emptyList()
            return Region(
                id = o.getString("id"), name = o.getString("name"), gateName = g.optString("name", "요금소"), gate = gate,
                theme = Theme(
                    name = t.getString("name"), color = color(t.optString("color"), 0xFFE3B35A.toInt()), deep = color(t.optString("deep"), 0xFFB8862F.toInt()),
                    instrument = t.optString("instrument"), music = t.optString("music"), prelude = t.optString("prelude"),
                    welcome = t.optString("welcome"), line = t.optString("line"),
                ),
                others = others, sights = sights, route = route, approach = appr, maneuvers = mans,
            )
        }
    }
}

data class Member(val id: String, val name: String, val color: Int, val driver: Boolean, val sharing: Boolean)

data class TripPlace(
    val id: String,
    val name: String,
    val kind: String,
    val area: String,
    val pos: LatLng,
    val by: String,
    val source: String,
    val photo: String,
    val time: String?,
    val status: String,
    val note: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind); put("area", area)
        put("lat", pos.lat); put("lon", pos.lon); put("by", by); put("source", source)
        put("photo", photo); put("time", time ?: JSONObject.NULL); put("status", status); put("note", note)
    }

    companion object {
        fun from(o: JSONObject, by: String? = null, status: String? = null): TripPlace = TripPlace(
            id = o.optString("id").ifBlank { "p" + System.currentTimeMillis() },
            name = o.optString("name"),
            kind = o.optString("kind", "sight"),
            area = o.optString("area"),
            pos = LatLng(o.optDouble("lat"), o.optDouble("lon")),
            by = by ?: o.optString("by", "mom"),
            source = o.optString("source", "지도 앱"),
            photo = o.optString("photo"),
            time = if (o.isNull("time")) null else o.optString("time").ifBlank { null },
            status = status ?: o.optString("status", "open"),
            note = o.optString("note"),
        )
    }
}

data class Record(
    val ready: Boolean,
    val km: Int,
    val visited: List<String>,
    val stories: List<String>,
    val photos: List<String>,
    val entryAt: String,
    val startAt: String,
    val sentTo: Int,
)

data class Trip(
    val code: String,
    val title: String,
    val region: String,
    val date: String,
    val from: String,
    val to: String,
    val members: List<Member>,
    val places: List<TripPlace>,
) {
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun who(id: String) = member(id)?.name ?: id

    companion object {
        fun load(ctx: Context): Trip {
            val o = JSONObject(ctx.assets.open("data/trip_seed.json").bufferedReader().use { it.readText() })
            val ms = o.getJSONArray("members").let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    Member(m.getString("id"), m.getString("name"), Region.color(m.optString("color"), 0xFF888888.toInt()), m.optBoolean("driver"), m.optBoolean("sharing"))
                }
            }
            val ps = o.getJSONArray("places").let { a -> (0 until a.length()).map { TripPlace.from(a.getJSONObject(it)) } }
            return Trip(
                code = o.getString("code"), title = o.getString("title"), region = o.getString("region"), date = o.getString("date"),
                from = o.optString("from", "서울"), to = o.optString("to", "경주"), members = ms, places = ps,
            )
        }
    }
}
