package app.gureuneun.guide

import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.Ko
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.ScenarioParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

object Scenarios {
    val ids = listOf("gyeongju", "jeju", "seoul")
    fun load(id: String): Scenario = ScenarioParser.parse(File("src/main/assets/scenarios/$id.json").readText())
}

/** 시나리오 자료가 서로 맞물리는지 점검. 자료를 고친 뒤 하나라도 어긋나면 빌드 단계에서 바로 알 수 있게 함 */
class ScenarioDataTest {

    @Test fun everyStoryHasBothLanguagesAndAllLengths() {
        Scenarios.ids.map(Scenarios::load).forEach { sc ->
            sc.places.filter { it.triggers }.forEach { p ->
                listOf("ko", "en").forEach { lang ->
                    val s = p.stories[lang]
                    assertTrue("${sc.id}/${p.id} $lang 이야기 없음", s != null)
                    assertTrue("${sc.id}/${p.id} $lang 짧은 이야기", s!!.short.isNotBlank())
                    if (p.kind != PlaceKind.CITY) {
                        assertTrue("${sc.id}/${p.id} $lang 보통 이야기", s.medium.isNotBlank())
                        assertTrue("${sc.id}/${p.id} $lang 긴 이야기", s.long.isNotBlank())
                    }
                }
            }
        }
    }

    /** 창밖 볼거리는 실제로 경로 어딘가에서 보이는 거리 안에 있어야 "저거 뭐야?"에 답할 수 있음 */
    @Test fun everySightIsVisibleFromTheRoute() {
        Scenarios.ids.map(Scenarios::load).forEach { sc ->
            sc.sights.forEach { s ->
                val nearest = sc.route.minOf { Geo.distanceM(it, s.pos) }
                assertTrue("${sc.id}/${s.id} 경로에서 가장 가까운 곳이 ${nearest.toInt()}m (보이는 거리 ${s.visibleM.toInt()}m)", nearest <= s.visibleM)
                assertTrue("${sc.id}/${s.id} 이름", s.name["ko"]!!.isNotBlank() && s.name["en"]!!.isNotBlank())
                assertTrue("${sc.id}/${s.id} 설명", s.fact["ko"]!!.isNotBlank() && s.fact["en"]!!.isNotBlank())
                assertTrue("${sc.id}/${s.id} 출처", s.source.isNotBlank())
            }
        }
    }

    @Test fun kidLinesHuntsAndStickersPointToRealPlaces() {
        val drawn = setOf("tombs", "tower", "pavilion", "bridge", "lighthouse", "sea", "tower63", "assembly")
        Scenarios.ids.map(Scenarios::load).forEach { sc ->
            sc.kid.keys.forEach { id -> assertTrue("${sc.id} 아이 이야기 $id", sc.findPlace(id) != null) }
            sc.hunt.forEach { (id, line) ->
                assertTrue("${sc.id} 보물찾기 $id 명소", sc.findPlace(id) != null)
                assertTrue("${sc.id} 보물찾기 $id 문장", line["ko"]!!.isNotBlank() && line["en"]!!.isNotBlank())
                assertTrue("${sc.id} 보물찾기 $id 스티커", sc.stickers[id] != null)
            }
            sc.stickers.forEach { (id, name) ->
                assertTrue("${sc.id} 스티커 $id 명소", sc.findPlace(id) != null)
                assertTrue("${sc.id} 스티커 $id 그림 $name", name in drawn)
            }
            if (sc.hunt.isNotEmpty()) assertTrue("${sc.id} 아이 모드 묻는 말", sc.kidOffer["ko"]!!.isNotBlank() && sc.kidOffer["en"]!!.isNotBlank())
        }
    }

    @Test fun koreanParticles() {
        assertEquals("가", Ko.iGa("첨성대"))
        assertEquals("이", Ko.iGa("63빌딩"))
        assertEquals("이에요", Ko.ieyo("천마총"))
        assertEquals("예요", Ko.ieyo("비양도"))
        assertEquals("로", Ko.euro("서울"))
        assertEquals("으로", Ko.euro("경주 시내 남쪽"))
        assertEquals("로", Ko.euro("바다"))
        assertEquals("약 300미터", Ko.distance(320.0, "ko"))
        assertEquals("약 1.2킬로미터", Ko.distance(1240.0, "ko"))
        assertEquals("약 2킬로미터", Ko.distance(2010.0, "ko"))
        assertEquals("about 1 kilometer", Ko.distance(1020.0, "en"))
        assertEquals("about 2.5 kilometers", Ko.distance(2470.0, "en"))
        assertEquals("about 300 meters", Ko.distance(310.0, "en"))
        assertEquals("1시간 20분", Ko.minutes(80, "ko"))
        assertEquals("25 minutes", Ko.minutes(25, "en"))
    }
}
