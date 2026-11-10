package app.gureuneun.guide

import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import app.gureuneun.guide.sdk.VehicleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 창문 눈금 환산, 선루프 닫힘 값, 기어 판정, 동력원 판정 (안드로이드 없이) */
class VehicleMathTest {

    @Test
    fun windowStepsOnPercentRange() {
        // 엔진은 앞 창문을 2 로 엶: 범위 0..100 이면 20
        assertEquals(20, VehicleMath.windowValue(2, 0, 100))
        assertEquals(0, VehicleMath.windowValue(0, 0, 100))
        assertEquals(100, VehicleMath.windowValue(10, 0, 100))
        // 눈금 밖은 끝으로 맞춤
        assertEquals(100, VehicleMath.windowValue(15, 0, 100))
        assertEquals(0, VehicleMath.windowValue(-3, 0, 100))
    }

    @Test
    fun openCloseOnlyWindowOpensAtAnyStep() {
        assertEquals(1, VehicleMath.windowValue(2, 0, 1))
        assertEquals(0, VehicleMath.windowValue(0, 0, 1))
    }

    @Test
    fun tiltRangeClosesAtZeroNotAtMin() {
        // 최소값이 음수(틸트)인 차: 닫힘은 0
        assertEquals(0, VehicleMath.closedValue(-10, 10))
        assertEquals(0, VehicleMath.windowValue(0, -10, 10))
        assertEquals(2, VehicleMath.windowValue(2, -10, 10))
        assertEquals(0, VehicleMath.sunroofValue(0, -10, 10))
        assertEquals(5, VehicleMath.sunroofValue(50, -10, 10))
        assertEquals(10, VehicleMath.sunroofValue(100, -10, 10))
    }

    @Test
    fun rangeWithoutZeroUsesNearestEndAsClosed() {
        assertEquals(5, VehicleMath.closedValue(5, 20))
        assertEquals(5, VehicleMath.windowValue(0, 5, 20))
        assertEquals(8, VehicleMath.windowValue(2, 5, 20))
        assertEquals(20, VehicleMath.windowValue(10, 5, 20))
        // 잘못된 범위는 움직이지 않는 값으로
        assertEquals(3, VehicleMath.windowValue(5, 3, 3))
    }

    @Test
    fun sunroofPercent() {
        assertEquals(0, VehicleMath.sunroofValue(0, 0, 100))
        assertEquals(100, VehicleMath.sunroofValue(100, 0, 100))
        assertEquals(1, VehicleMath.sunroofValue(1, 0, 1))
    }

    @Test
    fun gearLetters() {
        assertEquals("P", VehicleMath.gearLetter("GEAR_PARK"))
        assertEquals("R", VehicleMath.gearLetter("GEAR_REVERSE"))
        assertEquals("N", VehicleMath.gearLetter("GEAR_NEUTRAL"))
        assertEquals("D", VehicleMath.gearLetter("GEAR_DRIVE"))
        assertEquals("D", VehicleMath.gearLetter("GEAR_3"))
        assertEquals("-", VehicleMath.gearLetter("GEAR_UNKNOWN"))
        assertEquals("-", VehicleMath.gearLetter(null))
        assertEquals("-", VehicleMath.gearLetter("SOMETHING"))
    }

    @Test
    fun parkedOnlyWhenGearIsConfirmedPark() {
        assertTrue(VehicleMath.isParked("P"))
        assertFalse(VehicleMath.isParked("-"))
        assertFalse(VehicleMath.isParked("N"))
        assertFalse(VehicleMath.isParked("D"))
        assertTrue(VehicleBridge.State(gear = "P").parked)
        // 기어를 못 받은 처음 상태는 정차가 아님
        assertFalse(VehicleBridge.State().parked)
    }

    @Test
    fun powertrainFromSignals() {
        assertEquals("EV", VehicleMath.powertrain(ev = true, fuel = false))
        assertEquals("HEV", VehicleMath.powertrain(ev = true, fuel = true))
        assertEquals("ICE", VehicleMath.powertrain(ev = false, fuel = true))
        assertEquals("UNKNOWN", VehicleMath.powertrain(ev = false, fuel = false))
        assertEquals("UNKNOWN", VehicleMath.powertrain(ev = null, fuel = true))
        assertEquals("UNKNOWN", VehicleBridge.State().powertrain)
    }

    @Test
    fun unknownGloveStateRestoresToLocked() {
        assertTrue(VehicleMath.gloveRestoreTarget(null))
        assertTrue(VehicleMath.gloveRestoreTarget(true))
        assertFalse(VehicleMath.gloveRestoreTarget(false))
    }

    @Test
    fun colorTolerance() {
        assertTrue(VehicleMath.sameColor(0xFF00B8D9.toInt(), 0x0004B4DD))
        assertFalse(VehicleMath.sameColor(0xFF00B8D9.toInt(), 0xFFFF8A3D.toInt()))
    }

    @Test
    fun unknownUxRestrictionsCountAsRestricted() {
        assertTrue(UxBridge.Ux().restricted)
        assertFalse(UxBridge.Ux(known = true).restricted)
        assertTrue(UxBridge.Ux(known = true, requiresDO = true, noVideo = true).restricted)
    }
}
