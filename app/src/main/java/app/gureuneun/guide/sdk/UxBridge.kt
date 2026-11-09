package app.gureuneun.guide.sdk

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 주행 규제(CarUxRestrictions) 연결부.
 * 달리는 동안에는 차 화면의 영상과 QR, 긴 글을 끄고 소리로만 안내함.
 */
class UxBridge(private val context: Context) {

    data class Ux(
        val known: Boolean = false,
        val requiresDO: Boolean = false,
        val noVideo: Boolean = false,
        val limitText: Boolean = false,
        val noSetup: Boolean = false,
    ) {
        /** 영상 대신 소리로만 안내해야 하는 상태 */
        val restricted: Boolean get() = requiresDO && (noVideo || limitText || noSetup)
    }

    val state = MutableStateFlow(Ux())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        if (car != null) return
        runCatching {
            val c = Car.createCar(context.applicationContext)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            manager = m
            m?.registerListener { r -> update(r) }
            m?.currentCarUxRestrictions?.let { update(it) }
            GLog.i("Ux", "주행 규제 구독 시작")
        }.onFailure { GLog.w("Ux", "주행 규제 서비스 연결 실패", it) }
    }

    private fun update(r: CarUxRestrictions) {
        val a = r.activeRestrictions
        val ux = Ux(
            known = true,
            requiresDO = r.isRequiresDistractionOptimization,
            noVideo = a and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0,
            limitText = a and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0,
            noSetup = a and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP != 0,
        )
        if (ux != state.value) GLog.i("Ux", "주행 규제 ${if (ux.restricted) "켜짐" else "꺼짐"} (flags=$a)")
        state.value = ux
    }

    fun release() {
        runCatching {
            manager?.unregisterListener()
            car?.disconnect()
        }
        car = null
    }
}
