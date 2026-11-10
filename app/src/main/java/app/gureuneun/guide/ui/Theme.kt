package app.gureuneun.guide.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 구르는 가이드 디자인 토큰.
 *
 * 색: 현대자동차 CI 전용색상(hyundai.com/kr/ko/info/ci/ci-color, 2026-11-11 확인)을 브랜드 색으로,
 *     Pleos Connect 다크 테마 회색 단계(basic_00 #131417 ~ basic_900 #FFFFFF)를 바탕과 글자 색으로 씀.
 *     역할은 섞지 않음: 현대 블루는 큰 브랜드 면, 액티브 블루는 하나뿐인 강조(누르는 것, 지금 진행 중인 것),
 *     샌드는 보물 상자와 별, 액티브 레드는 경고에만 조금.
 * 글자: Pleos 시스템 글꼴(Asta Sans)을 그대로 씀. Pleos 글꼴 단계 Headline 60, Title 40/36, Body 30/28/26, Label 24 를 따름.
 *     시스템 글꼴 설정상 굵기 500 은 보통체, 600 은 Bold, 700 이상은 ExtraBold 로 보임.
 * 대비: 바탕과 글자, 아이콘은 4.5:1 이상 (Pleos, Android for Cars).
 */
object HC {
    val HyundaiBlue = Color(0xFF002C5F)
    val ActiveBlue = Color(0xFF00AAD2)
    val SkyBlue = Color(0xFFAACAE6)
    val Sand = Color(0xFFE4DCD3)
    val LightSand = Color(0xFFF6F3F2)
    val Gold = Color(0xFFA36B4F)
    val ActiveRed = Color(0xFFE63312)
}

/** 화면 역할 색 */
object C {
    /** 앱 바탕 (Pleos basic_00 보다 조금 어둡게, 차 실내와 어울리게) */
    val bg = Color(0xFF0E1015)
    /** 카드 면 */
    val surface = Color(0xFF171A20)
    /** 단추, 칩 면 */
    val raised = Color(0xFF22262E)
    val raised2 = Color(0xFF2C313B)
    /** 1px 나눔선 (흰색 10%) */
    val line = Color(0x1AFFFFFF)
    val text = Color(0xFFF7F8FA)
    /** 보조 글자 (Pleos basic_500 다크) */
    val text2 = Color(0xFFC7C9CE)
    /** 덜 중요한 글자 (카드 위 대비 6.6:1) */
    val text3 = Color(0xFF9AA0AA)

    /** 브랜드 면: 현대 블루에서 더 깊은 남색으로 */
    val brand = HC.HyundaiBlue
    val brandDeep = Color(0xFF001A3A)
    /** 브랜드 면 위의 보조 글자 (현대 블루 위 대비 8:1) */
    val onBrand2 = HC.SkyBlue
    /** 브랜드 면 위의 단추 면 (흰색 12%) */
    val onBrandRaised = Color(0x1FFFFFFF)

    /** 하나뿐인 강조색 */
    val accent = HC.ActiveBlue
    /** 강조색 위 글자 (대비 5.5:1) */
    val onAccent = Color(0xFF00213F)
    val accentSoft = Color(0x2900AAD2)

    val warn = HC.ActiveRed
    val warnSoft = Color(0x33E63312)
    val sand = HC.Sand
    val scrim = Color(0xB3000000)
}

/**
 * Pleos 글꼴 단계.
 * 줄바꿈은 어절 단위(WordBreak.Phrase)로 해서 "그룹으/로"처럼 낱말 가운데서 끊기지 않게 함.
 * 제목은 줄 길이를 고르게(Balanced) 나눔
 */
object T {
    private val tight = (-0.4).sp
    private val heading = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)
    private val para = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)
    val headL = TextStyle(fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = tight, lineBreak = heading)
    val headM = TextStyle(fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = tight, lineBreak = heading)
    val titleL = TextStyle(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = tight, lineBreak = heading)
    val titleM = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = tight, lineBreak = heading)
    val bodyL = TextStyle(fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Normal, lineBreak = para)
    val bodyM = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal, lineBreak = para)
    val bodyS = TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal, lineBreak = para)
    val label = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, lineBreak = para)
    val button = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, lineBreak = para)
    /** 덜 중요한 설명 (Pleos 보조 글자 최소 높이 3mm 이상) */
    val caption = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal, lineBreak = para)
}

/** 모서리와 간격 */
object S {
    val cardRadius = 28.dp
    val buttonRadius = 22.dp
    val chipRadius = 18.dp
    val gap = 24.dp
    val pad = 32.dp
    /** Android for Cars 최소 터치 영역 */
    val touch = 76.dp
}

fun t(lang: String, ko: String, en: String) = if (lang == "en") en else ko
