package app.gureuneun.guide.core

data class LatLng(val lat: Double, val lon: Double)

/** 근접 알림 구역 (FusedLocation 의 Proximity Alert 에 등록) */
data class Place(val id: String, val pos: LatLng, val radiusM: Float)
