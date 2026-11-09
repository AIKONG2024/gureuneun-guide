package app.gureuneun.guide

import android.app.Application
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.LocationBridge
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GuideApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val vehicle by lazy { VehicleBridge(this, scope) }
    val navi by lazy { NaviBridge(this, scope) }
    val speech by lazy { SpeechBridge(this) }
    val location by lazy { LocationBridge(this) }
    val ux by lazy { UxBridge(this) }
    val engine by lazy { TripEngine(this) }
}
