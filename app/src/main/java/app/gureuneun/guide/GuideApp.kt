package app.gureuneun.guide

import android.app.Application
import app.gureuneun.guide.engine.TripEngine

/** 앱 전체에서 여행 하나를 맡는 엔진을 하나만 둠 (화면을 다시 만들어도 여행 상태가 이어지게) */
class GuideApp : Application() {
    lateinit var engine: TripEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = TripEngine(this)
    }
}
