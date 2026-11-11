package app.gureuneun.guide.core

import org.json.JSONArray
import org.json.JSONObject

data class LatLng(val lat: Double, val lon: Double)

enum class PlaceKind {
    CITY, LANDMARK, SEA, CAFE, PARKING;

    companion object {
        fun of(s: String?): PlaceKind = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: LANDMARK
    }
}

enum class StoryLength { SHORT, MEDIUM, LONG }

/** 시연 경로 위의 길 안내 지점(회전, 분기 등) */
data class Maneuver(val pos: LatLng, val type: String, val modifier: String)

typealias L10n = Map<String, String>

/** 화면 글자의 언어는 차의 시스템 언어를 따름. 안내 음성의 언어(여행을 시작할 때 고름)와는 따로임 */
object Lang {
    val screen: String get() = if (java.util.Locale.getDefault().language == "ko") "ko" else "en"
}

fun L10n.pick(lang: String): String = this[lang] ?: this["ko"] ?: this.values.firstOrNull() ?: ""

data class Story(val short: String, val medium: String, val long: String) {
    fun of(len: StoryLength): String = when (len) {
        StoryLength.SHORT -> short
        StoryLength.MEDIUM -> medium.ifBlank { short }
        StoryLength.LONG -> long.ifBlank { medium.ifBlank { short } }
    }
}

data class Place(
    val id: String,
    val name: L10n,
    val kind: PlaceKind,
    val pos: LatLng,
    val radiusM: Float,
    val color: String,
    val announce: L10n,
    val stories: Map<String, Story>,
    val rain: L10n,
    val night: L10n,
    val stop: Boolean,
    val source: String,
    /** 사진을 빌려 쓸 명소 id (자기 사진이 없는 카페, 주차장) */
    val photo: String? = null,
) {
    fun story(lang: String): Story? = stories[lang] ?: stories["ko"] ?: stories.values.firstOrNull()
    /** 가까이 가면 차가 먼저 이야기를 꺼내는 곳 (카페, 주차장은 휴대폰에서 고르는 곳이라 아님) */
    val triggers: Boolean
        get() = kind == PlaceKind.CITY || kind == PlaceKind.LANDMARK || kind == PlaceKind.SEA
}

/** 이야기 정류장은 아니지만 창밖으로 보이는 곳. "저거 뭐야?"에 답할 때 씀 */
data class Sight(
    val id: String,
    val name: L10n,
    val pos: LatLng,
    val fact: L10n,
    /** 이 거리 안이면 창밖으로 보인다고 봄 (섬처럼 멀리서도 보이는 곳은 크게) */
    val visibleM: Double,
    val source: String,
)

/** 명소마다 아이들에게 내는 퀴즈 */
data class Quiz(
    val question: L10n,
    val choices: Map<String, List<String>>,
    val answer: Int,
) {
    fun choices(lang: String): List<String> = choices[lang] ?: choices["ko"] ?: emptyList()
}

data class Scenario(
    val id: String,
    val title: L10n,
    val subtitle: L10n,
    val who: L10n,
    val defaultLang: String,
    val cars: Int,
    val start: LatLng,
    val destinationId: String,
    val waypointIds: List<String>,
    val places: List<Place>,
    val pickables: List<Place>,
    val route: List<LatLng>,
    val maneuvers: List<Maneuver>,
    val greeting: L10n,
    val departure: L10n,
    val chapter: L10n,
    val finale: L10n,
    val sights: List<Sight> = emptyList(),
    /** 뒷자리 아이들을 위한 짧은 이야기 (명소 id 별) */
    val kid: Map<String, L10n> = emptyMap(),
    val quiz: Map<String, Quiz> = emptyMap(),
    /** 가족 코스: 뒷좌석에 사람이 앉아 있으면 명소 이야기 끝에 아이 이야기를 덧붙임 */
    val kidAuto: Boolean = false,
    /** 여행지 카드와 기록에 쓰는 표지 사진 (사진 파일 이름에서 .jpg 를 뺀 것) */
    val cover: String = "",
) {
    fun findPlace(id: String?): Place? = places.firstOrNull { it.id == id } ?: pickables.firstOrNull { it.id == id }
    /** 명소든 코스 밖 볼거리든 이름을 찾음 (물어본 것에 답한 곳) */
    fun nameOf(id: String?): L10n? = findPlace(id)?.name ?: sights.firstOrNull { it.id == id }?.name
    /** 그곳 사진의 id. 사진을 빌려 쓰는 곳이면 빌린 곳의 id */
    fun photoOf(id: String?): String? = findPlace(id)?.photo ?: id?.ifBlank { null }
    val destination: Place? get() = findPlace(destinationId)
    val waypoints: List<Place> get() = waypointIds.mapNotNull { findPlace(it) }
    val landmarks: List<Place> get() = places.filter { it.kind != PlaceKind.CITY }
}

object ScenarioParser {
    fun parse(text: String): Scenario {
        val o = JSONObject(text)
        return Scenario(
            id = o.getString("id"),
            title = l10n(o.optJSONObject("title")),
            subtitle = l10n(o.optJSONObject("subtitle")),
            who = l10n(o.optJSONObject("who")),
            defaultLang = o.optString("defaultLang", "ko"),
            cars = o.optInt("cars", 1),
            start = latLng(o.getJSONArray("start")),
            destinationId = o.getString("destination"),
            waypointIds = o.optJSONArray("waypoints")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            places = objects(o.getJSONArray("places")).map(::place),
            pickables = o.optJSONArray("pickables")?.let { objects(it).map(::place) } ?: emptyList(),
            route = o.getJSONArray("route").let { a -> (0 until a.length()).map { latLng(a.getJSONArray(it)) } },
            maneuvers = o.optJSONArray("maneuvers")?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONArray(i)
                    Maneuver(LatLng(m.getDouble(0), m.getDouble(1)), m.optString(2), m.optString(3))
                }
            } ?: emptyList(),
            greeting = l10n(o.optJSONObject("greeting")),
            departure = l10n(o.optJSONObject("departure")),
            chapter = l10n(o.optJSONObject("chapter")),
            finale = l10n(o.optJSONObject("finale")),
            sights = o.optJSONArray("sights")?.let { a ->
                objects(a).map { s ->
                    Sight(
                        id = s.getString("id"),
                        name = l10n(s.optJSONObject("name")),
                        pos = LatLng(s.getDouble("lat"), s.getDouble("lon")),
                        fact = l10n(s.optJSONObject("fact")),
                        visibleM = s.optDouble("visible", 900.0),
                        source = s.optString("source"),
                    )
                }
            } ?: emptyList(),
            kid = o.optJSONObject("kid")?.let { k -> k.keys().asSequence().associateWith { id -> l10n(k.optJSONObject(id)) } } ?: emptyMap(),
            quiz = o.optJSONObject("quiz")?.let { q ->
                q.keys().asSequence().associateWith { id ->
                    val z = q.getJSONObject(id)
                    val ch = z.optJSONObject("choices")
                    Quiz(
                        question = l10n(z.optJSONObject("q")),
                        choices = ch?.keys()?.asSequence()?.associateWith { lang -> ch.getJSONArray(lang).let { a -> (0 until a.length()).map { a.getString(it) } } } ?: emptyMap(),
                        answer = z.optInt("answer", 0),
                    )
                }
            } ?: emptyMap(),
            kidAuto = o.optBoolean("kidAuto", false),
            cover = o.optString("cover").ifBlank { o.getString("destination") + "_1" },
        )
    }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    private fun latLng(a: JSONArray) = LatLng(a.getDouble(0), a.getDouble(1))

    private fun l10n(o: JSONObject?): L10n {
        if (o == null) return emptyMap()
        val m = LinkedHashMap<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k) }
        return m
    }

    private fun place(o: JSONObject): Place {
        val stories = LinkedHashMap<String, Story>()
        o.optJSONObject("story")?.let { s ->
            s.keys().forEach { lang ->
                val v = s.getJSONObject(lang)
                stories[lang] = Story(v.optString("short"), v.optString("medium"), v.optString("long"))
            }
        }
        return Place(
            id = o.getString("id"),
            name = l10n(o.optJSONObject("name")),
            kind = PlaceKind.of(o.optString("kind")),
            pos = LatLng(o.getDouble("lat"), o.getDouble("lon")),
            radiusM = o.optDouble("radius", 300.0).toFloat(),
            color = o.optString("color", "#7FE3FF"),
            announce = l10n(o.optJSONObject("announce")),
            stories = stories,
            rain = l10n(o.optJSONObject("rain")),
            night = l10n(o.optJSONObject("night")),
            stop = o.optBoolean("stop", false),
            source = o.optString("source"),
            photo = o.optString("photo").ifBlank { null },
        )
    }
}
