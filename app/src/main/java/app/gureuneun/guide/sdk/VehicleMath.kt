package app.gureuneun.guide.sdk

/** 차 값 계산 가운데 안드로이드, SDK 없이 시험할 수 있는 부분 (VehicleMathTest) */
object VehicleMath {

    /** SDK 기어 이름(VehicleGear.name)을 화면에 쓰는 한 글자로. 모르면 "-" */
    fun gearLetter(name: String?): String = when (name) {
        "GEAR_PARK" -> "P"
        "GEAR_REVERSE" -> "R"
        "GEAR_NEUTRAL" -> "N"
        null, "GEAR_UNKNOWN" -> "-"
        else -> if (name.startsWith("GEAR_")) "D" else "-"
    }

    /** 기어가 P 로 확인됐을 때만 정차. 모르는 기어("-")는 정차가 아님 */
    fun isParked(gearLetter: String): Boolean = gearLetter == "P"

    /** 실내 조명 색이 같은지 (차가 색을 조금 다르게 맞추는 것을 감안해 채널마다 16 까지는 같다고 봄) */
    fun sameColor(a: Int, b: Int, tolerance: Int = 16): Boolean =
        (0..2).all { i -> kotlin.math.abs(((a shr (i * 8)) and 0xFF) - ((b shr (i * 8)) and 0xFF)) <= tolerance }
}
