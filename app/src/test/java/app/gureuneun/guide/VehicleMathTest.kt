package app.gureuneun.guide

import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import app.gureuneun.guide.sdk.VehicleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기어 판정, 조명 색 비교, 주행 규제 판정 (안드로이드 없이) */
class VehicleMathTest {

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
