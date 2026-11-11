package app.gureuneun.guide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 앱 아이콘 모음. 24칸 격자에 2.2칸 굵기의 둥근 끝 선으로 그림 (마이현대 앱처럼 단정한 선 아이콘).
 * Pleos 가이드대로 아이콘은 늘 글자 라벨과 함께 씀.
 */
enum class Ic {
    Replay, Pause, Play, Next, Mic, Pin, Star, StarOutline, Car, Users, Phone, Qr, Window, Check, Close,
    ChevronLeft, Sliders, Rain, Moon, Clock, Kid, Flag, Route, Gift, Cup, Parking,
    Speaker, Send, Plus, Backspace, Bookmark, Navigation, Info, Home, Arrow,
}

@Composable
fun Icon(ic: Ic, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIcon(ic, tint) }
}

fun DrawScope.drawIcon(ic: Ic, c: Color) {
    val u = size.minDimension / 24f
    val sw = 2.2f * u
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), sw, StrokeCap.Round)
    fun pathOf(block: Path.() -> Unit) = Path().apply(block)
    fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun Path.cc(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = cubicTo(x1 * u, y1 * u, x2 * u, y2 * u, x * u, y * u)
    fun stroke(p: Path) = drawPath(p, c, style = st)
    fun fill(p: Path) = drawPath(p, c)
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(c, r * u, o(cx, cy), style = st)
    fun dot(cx: Float, cy: Float, r: Float) = drawCircle(c, r * u, o(cx, cy))
    fun box(l: Float, t: Float, r: Float, b: Float, rad: Float, filled: Boolean = false) =
        if (filled) drawRoundRect(c, o(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(rad * u))
        else drawRoundRect(c, o(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(rad * u), style = st)
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
        drawArc(c, start, sweep, false, o(cx - r, cy - r), Size(2 * r * u, 2 * r * u), style = st)
    fun star(cx: Float, cy: Float, outer: Float, inner: Float) = pathOf {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 5
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (i == 0) m(x, y) else l(x, y)
        }
        close()
    }

    when (ic) {
        // 다시 듣기: 위에서 왼쪽으로 도는 화살표
        Ic.Replay -> {
            arc(12f, 12.5f, 7.5f, -90f, 300f)
            stroke(pathOf { m(13.8f, 2.2f); l(11f, 5f); l(13.8f, 7.8f) })
        }
        // 오른쪽을 가리키는 화살표 (보이는 쪽에 맞춰 돌려 씀)
        Ic.Arrow -> {
            line(4f, 12f, 19f, 12f)
            stroke(pathOf { m(13f, 5.5f); l(19.5f, 12f); l(13f, 18.5f) })
        }
        Ic.Pause -> { box(6.5f, 5f, 10f, 19f, 1.4f, true); box(14f, 5f, 17.5f, 19f, 1.4f, true) }
        Ic.Play -> {
            val p = pathOf { m(8f, 5f); l(19f, 12f); l(8f, 19f); close() }
            fill(p); stroke(p)
        }
        Ic.Next -> {
            val p = pathOf { m(5.5f, 6f); l(14.5f, 12f); l(5.5f, 18f); close() }
            fill(p); stroke(p)
            box(16.6f, 6f, 18.8f, 18f, 1f, true)
        }
        Ic.Mic -> {
            box(9f, 2.8f, 15f, 14.2f, 3f, true)
            arc(12f, 11f, 6.2f, 0f, 180f)
            line(12f, 17.2f, 12f, 20.6f)
            line(8.6f, 20.6f, 15.4f, 20.6f)
        }
        Ic.Pin -> {
            stroke(pathOf {
                m(12f, 21.5f)
                cc(9.4f, 18.6f, 5.5f, 14.4f, 5.5f, 9.6f)
                arcTo(Rect(5.5f * u, 3.1f * u, 18.5f * u, 16.1f * u), 180f, 180f, false)
                cc(18.5f, 14.4f, 14.6f, 18.6f, 12f, 21.5f)
                close()
            })
            dot(12f, 9.6f, 2.4f)
        }
        Ic.Star -> { val p = star(12f, 12.6f, 9.6f, 4.3f); fill(p); stroke(p) }
        Ic.StarOutline -> stroke(star(12f, 12.6f, 9.4f, 4.3f))
        Ic.Car -> {
            stroke(pathOf { m(5f, 13.2f); l(6.6f, 8.4f); cc(7f, 7.3f, 8f, 6.6f, 9.2f, 6.6f); l(14.8f, 6.6f); cc(16f, 6.6f, 17f, 7.3f, 17.4f, 8.4f); l(19f, 13.2f) })
            box(3.5f, 12.6f, 20.5f, 18.2f, 2.2f)
            line(6.2f, 18.6f, 6.2f, 20.4f); line(17.8f, 18.6f, 17.8f, 20.4f)
            dot(7.4f, 15.4f, 1.2f); dot(16.6f, 15.4f, 1.2f)
        }
        Ic.Users -> {
            ring(9f, 8.2f, 3.3f)
            stroke(pathOf { m(3.2f, 19.5f); cc(3.2f, 15.8f, 5.8f, 13.6f, 9f, 13.6f); cc(12.2f, 13.6f, 14.8f, 15.8f, 14.8f, 19.5f) })
            ring(16.6f, 8.8f, 2.6f)
            stroke(pathOf { m(17f, 13.7f); cc(19.2f, 14f, 20.8f, 15.9f, 20.8f, 18.6f) })
        }
        Ic.Phone -> { box(7f, 2.6f, 17f, 21.4f, 2.6f); line(10.6f, 18.2f, 13.4f, 18.2f) }
        Ic.Qr -> {
            box(3.6f, 3.6f, 10.2f, 10.2f, 1.4f); box(13.8f, 3.6f, 20.4f, 10.2f, 1.4f); box(3.6f, 13.8f, 10.2f, 20.4f, 1.4f)
            box(6f, 6f, 7.8f, 7.8f, 0.4f, true); box(16.2f, 6f, 18f, 7.8f, 0.4f, true); box(6f, 16.2f, 7.8f, 18f, 0.4f, true)
            box(13.8f, 13.8f, 16f, 16f, 0.4f, true); box(18.2f, 13.8f, 20.4f, 16f, 0.4f, true)
            box(13.8f, 18.2f, 16f, 20.4f, 0.4f, true); box(18.2f, 18.2f, 20.4f, 20.4f, 0.4f, true)
        }
        // 창문: 옆 창문 틀과 내려가는 화살표
        Ic.Window -> {
            stroke(pathOf { m(3.5f, 16f); l(3.5f, 10.4f); l(8.8f, 5f); l(20.5f, 5f); l(20.5f, 16f); close() })
            line(12.5f, 8.6f, 12.5f, 13.4f)
            stroke(pathOf { m(10.2f, 11.4f); l(12.5f, 13.7f); l(14.8f, 11.4f) })
            line(3.5f, 19.6f, 20.5f, 19.6f)
        }
        Ic.Check -> stroke(pathOf { m(5f, 12.6f); l(9.8f, 17.2f); l(19f, 7.2f) })
        Ic.Close -> { line(6.2f, 6.2f, 17.8f, 17.8f); line(17.8f, 6.2f, 6.2f, 17.8f) }
        Ic.ChevronLeft -> stroke(pathOf { m(15f, 5.5f); l(8.5f, 12f); l(15f, 18.5f) })
        Ic.Sliders -> {
            line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
            dot(15f, 7f, 2.6f); dot(8.5f, 12f, 2.6f); dot(13f, 17f, 2.6f)
        }
        Ic.Rain -> {
            stroke(pathOf {
                m(7f, 15f); cc(4.6f, 15f, 3f, 13.4f, 3f, 11.4f); cc(3f, 9.5f, 4.5f, 8f, 6.4f, 7.9f)
                cc(7.1f, 5.5f, 9.3f, 3.8f, 11.9f, 3.8f); cc(15f, 3.8f, 17.5f, 6.1f, 17.8f, 9.1f)
                cc(19.6f, 9.3f, 21f, 10.8f, 21f, 12.5f); cc(21f, 14f, 19.8f, 15f, 18.3f, 15f); close()
            })
            line(8.2f, 18f, 7.2f, 20.6f); line(12.2f, 18f, 11.2f, 20.6f); line(16.2f, 18f, 15.2f, 20.6f)
        }
        Ic.Moon -> {
            val full = Path().apply { addOval(Rect(3.5f * u, 4f * u, 20f * u, 20.5f * u)) }
            val cut = Path().apply { addOval(Rect(9.5f * u, 1.5f * u, 23.5f * u, 15.5f * u)) }
            fill(Path().apply { op(full, cut, PathOperation.Difference) })
        }
        Ic.Clock -> { ring(12f, 12f, 8.8f); line(12f, 12f, 12f, 7.2f); line(12f, 12f, 15.6f, 14.2f) }
        Ic.Kid -> {
            ring(12f, 12f, 8.8f)
            dot(9f, 10.4f, 1.25f); dot(15f, 10.4f, 1.25f)
            arc(12f, 12.6f, 4f, 25f, 130f)
        }
        Ic.Flag -> {
            line(6f, 21f, 6f, 3.6f)
            stroke(pathOf { m(6f, 4.4f); l(18.6f, 4.4f); l(15.6f, 8.5f); l(18.6f, 12.6f); l(6f, 12.6f) })
        }
        Ic.Route -> {
            ring(6f, 18f, 2.4f); ring(18f, 6f, 2.4f)
            stroke(pathOf { m(8.4f, 18f); l(14.6f, 18f); cc(16.6f, 18f, 17.8f, 16.6f, 17.8f, 14.9f); cc(17.8f, 13.1f, 16.5f, 11.8f, 14.8f, 11.8f); l(9.2f, 11.8f); cc(7.5f, 11.8f, 6.2f, 10.5f, 6.2f, 8.8f); cc(6.2f, 7.1f, 7.4f, 6f, 9.2f, 6f); l(15.6f, 6f) })
        }
        Ic.Gift -> {
            box(4.2f, 10.6f, 19.8f, 20.6f, 1.6f)
            box(3f, 7.2f, 21f, 10.6f, 1.2f)
            line(12f, 7.2f, 12f, 20.6f)
            stroke(pathOf { m(12f, 7.2f); cc(10.4f, 3.6f, 6.6f, 3.8f, 7.1f, 6f); cc(7.4f, 7.2f, 9.8f, 7.2f, 12f, 7.2f) })
            stroke(pathOf { m(12f, 7.2f); cc(13.6f, 3.6f, 17.4f, 3.8f, 16.9f, 6f); cc(16.6f, 7.2f, 14.2f, 7.2f, 12f, 7.2f) })
        }
        Ic.Cup -> {
            stroke(pathOf { m(4.6f, 9.4f); l(15.6f, 9.4f); l(15.6f, 15f); cc(15.6f, 17.8f, 13.4f, 20f, 10.6f, 20f); l(9.6f, 20f); cc(6.8f, 20f, 4.6f, 17.8f, 4.6f, 15f); close() })
            stroke(pathOf { m(15.6f, 11.2f); l(17.2f, 11.2f); cc(18.7f, 11.2f, 19.8f, 12.3f, 19.8f, 13.7f); cc(19.8f, 15.1f, 18.7f, 16.2f, 17.2f, 16.2f); l(15.6f, 16.2f) })
            line(8.2f, 3.6f, 8.2f, 6.2f); line(12f, 3.6f, 12f, 6.2f)
        }
        Ic.Parking -> {
            box(4f, 3.5f, 20f, 20.5f, 4f)
            stroke(pathOf { m(9.6f, 16.6f); l(9.6f, 7.4f); l(13.2f, 7.4f); cc(15f, 7.4f, 16.2f, 8.7f, 16.2f, 10.4f); cc(16.2f, 12.1f, 15f, 13.4f, 13.2f, 13.4f); l(9.6f, 13.4f) })
        }
        Ic.Speaker -> {
            val p = pathOf { m(3.8f, 9.4f); l(7.4f, 9.4f); l(12f, 5.4f); l(12f, 18.6f); l(7.4f, 14.6f); l(3.8f, 14.6f); close() }
            fill(p); stroke(p)
            arc(12f, 12f, 4.2f, -45f, 90f)
            arc(12f, 12f, 7.8f, -48f, 96f)
        }
        Ic.Send -> {
            stroke(pathOf { m(3.4f, 11.2f); l(20.6f, 3.8f); l(14.6f, 20.4f); l(11.4f, 12.8f); close() })
            line(11.4f, 12.8f, 20.4f, 4f)
        }
        Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Ic.Backspace -> {
            stroke(pathOf { m(8.6f, 5f); l(19.6f, 5f); cc(20.7f, 5f, 21.4f, 5.7f, 21.4f, 6.8f); l(21.4f, 17.2f); cc(21.4f, 18.3f, 20.7f, 19f, 19.6f, 19f); l(8.6f, 19f); l(2.6f, 12f); close() })
            line(11.6f, 9.2f, 17f, 14.8f); line(17f, 9.2f, 11.6f, 14.8f)
        }
        Ic.Bookmark -> stroke(pathOf { m(6.4f, 3.6f); l(17.6f, 3.6f); l(17.6f, 20.6f); l(12f, 16.6f); l(6.4f, 20.6f); close() })
        Ic.Navigation -> {
            val p = pathOf { m(12f, 2.8f); l(19.6f, 20.4f); l(12f, 16.4f); l(4.4f, 20.4f); close() }
            fill(p); stroke(p)
        }
        Ic.Info -> { ring(12f, 12f, 9f); line(12f, 11f, 12f, 16.6f); dot(12f, 7.6f, 1.3f) }
        Ic.Home -> {
            stroke(pathOf { m(3.6f, 11f); l(12f, 4f); l(20.4f, 11f) })
            stroke(pathOf { m(6f, 9.4f); l(6f, 20f); l(18f, 20f); l(18f, 9.4f) })
            stroke(pathOf { m(10f, 20f); l(10f, 14.4f); l(14f, 14.4f); l(14f, 20f) })
        }
    }
}
