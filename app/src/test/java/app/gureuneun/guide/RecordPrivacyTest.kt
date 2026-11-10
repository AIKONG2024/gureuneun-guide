package app.gureuneun.guide

import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.engine.TripRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기록을 그룹 페이지에 올릴 때 출발, 도착 근처의 길을 잘라 내는지 */
class RecordPrivacyTest {

    // 위도 0.001도 ≈ 111m 간격으로 북쪽으로 곧게 가는 길 (약 2.2km)
    private val route = (0..20).map { i -> (1000L * i) to LatLng(35.80 + i * 0.001, 129.20) }

    @Test fun cutsBothEnds() {
        val trimmed = TripRecorder.trimEnds(route, 300.0)
        assertTrue(trimmed.isNotEmpty())
        val startGap = Geo.distanceM(route.first().second, trimmed.first().second)
        val endGap = Geo.distanceM(route.last().second, trimmed.last().second)
        assertTrue("출발 쪽 ${startGap.toInt()}m", startGap >= 300.0)
        assertTrue("도착 쪽 ${endGap.toInt()}m", endGap >= 300.0)
        // 시각은 그대로 남아 사진을 길 위에 놓을 수 있음
        assertEquals(route[trimmed.size / 2 + 3].first, trimmed[trimmed.size / 2].first)
    }

    @Test fun shortTripIsDropped() {
        val short = route.take(5) // 약 440m
        assertTrue(TripRecorder.trimEnds(short, 300.0).isEmpty())
        assertTrue(TripRecorder.trimEnds(route.take(2), 300.0).isEmpty())
    }
}

