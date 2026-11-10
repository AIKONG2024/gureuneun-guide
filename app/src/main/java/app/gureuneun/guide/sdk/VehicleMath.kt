package app.gureuneun.guide.sdk

/**
 * 차 값 계산 가운데 안드로이드, SDK 없이 시험할 수 있는 부분 (VehicleMathTest).
 *
 * 창문과 선루프 위치는 차마다 범위(AreaBounds)가 다름. 안드로이드 차량 속성 규칙상 0 이 닫힘이고,
 * 양수는 열림(최대값이 끝까지), 음수는 바깥으로 들어 올리는 틸트(환기) 위치. 범위에 0 이 없으면 가장 가까운 끝을 닫힘으로 봄.
 */
object VehicleMath {

    /** 창문 눈금의 끝 (0 닫힘 ~ 10 끝까지) */
    const val WINDOW_STEPS = 10

    /** 이 범위에서 닫힘에 해당하는 값 */
    fun closedValue(min: Int, max: Int): Int = if (max < min) min else 0.coerceIn(min, max)

    /**
     * 닫힘에서 끝까지 열림 사이의 [num]/[den] 지점 값. 0 이면 닫힘.
     * 0 보다 크면 적어도 닫힘 다음 값(0..1 처럼 열림/닫힘만 있는 차는 1)이 되게 함. 틸트(음수) 쪽으로는 가지 않음
     */
    fun openValue(num: Int, den: Int, min: Int, max: Int): Int {
        val closed = closedValue(min, max)
        if (den <= 0 || max <= closed) return closed
        val n = num.coerceIn(0, den)
        if (n == 0) return closed
        val span = max.toLong() - closed
        val v = closed + (span * n + den / 2) / den
        return v.toInt().coerceIn(closed + 1, max)
    }

    /** 0~10 눈금을 창문 범위 값으로. 범위 0..100 에서 2 는 20, 0 은 0, 10 은 100 */
    fun windowValue(step: Int, min: Int, max: Int): Int = openValue(step, WINDOW_STEPS, min, max)

    /** 0~100% 를 선루프 범위 값으로. 0 은 닫힘(틸트가 있는 차에서 최소값이 아님) */
    fun sunroofValue(percent: Int, min: Int, max: Int): Int = openValue(percent, 100, min, max)

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

    /**
     * 동력원 판정. ev, fuel 은 배터리 잔량(SOC), 연료 잔량(%) 신호를 읽을 수 있는지 (null 은 아직 확인 중).
     * 배터리만 있으면 EV, 둘 다 있으면 HEV(플러그인 포함), 연료만 있으면 ICE
     */
    fun powertrain(ev: Boolean?, fuel: Boolean?): String = when {
        ev == true && fuel == true -> "HEV"
        ev == true && fuel == false -> "EV"
        ev == false && fuel == true -> "ICE"
        else -> "UNKNOWN"
    }

    /** 되돌릴 글로브 박스 잠금 값. 원래 상태를 모르면 잠금 */
    fun gloveRestoreTarget(original: Boolean?): Boolean = original ?: true

    /** 실내 조명 색이 같은지 (차가 색을 조금 다르게 맞추는 것을 감안해 채널마다 16 까지는 같다고 봄) */
    fun sameColor(a: Int, b: Int, tolerance: Int = 16): Boolean =
        (0..2).all { i -> kotlin.math.abs(((a shr (i * 8)) and 0xFF) - ((b shr (i * 8)) and 0xFF)) <= tolerance }
}
