package app.gureuneun.guide.sdk

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 주행 규제(CarUxRestrictions) 연결부.
 * 달리는 동안에는 차 화면의 영상과 QR, 긴 글을 끄고 소리로만 안내함.
 * 규제 상태를 아직 모르거나(연결 전, 차량 서비스가 다시 시작되는 중) 연결하지 못하면 규제가 있는 것으로 봄 (안전한 쪽).
 * 차량 서비스가 죽었다 살아나면 다시 붙어 구독함 (API 30 이상).
 */
class UxBridge(private val context: Context) {

    data class Ux(
        val known: Boolean = false,
        val requiresDO: Boolean = false,
        val noVideo: Boolean = false,
        val limitText: Boolean = false,
        val noSetup: Boolean = false,
        /** 글자 수 제한이 있을 때 한 줄에 보여도 되는 최대 글자 수 (모르면 null) */
        val maxStringLength: Int? = null,
    ) {
        /** 영상 대신 소리로만 안내해야 하는 상태. 규제 상태를 모르면 규제가 있는 것으로 봄 */
        val restricted: Boolean get() = !known || (requiresDO && (noVideo || limitText || noSetup))
    }

    val state = MutableStateFlow(Ux())
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        if (car != null) return
        runCatching {
            val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 기다리지 않고 바로 돌아옴. 준비되면(다시 살아날 때도) onLifecycleChanged(ready = true)
                Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { cr, ready -> onCar(cr, ready) }
            } else {
                Car.createCar(context.applicationContext).also { onCar(it, true) }
            }
            car = c
        }.onFailure {
            GLog.w("Ux", "주행 규제 서비스 연결 실패 (규제 있는 것으로 봄)", it)
            state.value = Ux()
        }
    }

    private fun onCar(c: Car, ready: Boolean) {
        if (!ready) {
            GLog.w("Ux", "차량 서비스가 끊김. 다시 붙을 때까지 규제 있는 것으로 봄")
            manager = null
            state.value = Ux()
            return
        }
        runCatching {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            manager = m
            m?.registerListener { r -> main.post { update(r) } }
            m?.currentCarUxRestrictions?.let { update(it) }
            GLog.i("Ux", "주행 규제 구독 시작")
        }.onFailure { GLog.w("Ux", "주행 규제 구독 실패 (규제 있는 것으로 봄)", it) }
    }

    private fun update(r: CarUxRestrictions) {
        val a = r.activeRestrictions
        val limit = a and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0
        val ux = Ux(
            known = true,
            requiresDO = r.isRequiresDistractionOptimization,
            noVideo = a and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0,
            limitText = limit,
            noSetup = a and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP != 0,
            maxStringLength = if (limit) runCatching { r.maxRestrictedStringLength }.getOrNull() else null,
        )
        if (ux != state.value) GLog.i("Ux", "주행 규제 ${if (ux.restricted) "켜짐" else "꺼짐"} (flags=$a)")
        state.value = ux
    }

    fun release() {
        runCatching {
            manager?.unregisterListener()
            car?.disconnect()
        }
        manager = null
        car = null
    }
}
