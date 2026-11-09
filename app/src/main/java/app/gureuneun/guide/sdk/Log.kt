package app.gureuneun.guide.sdk

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면의 점검 탭과 logcat 에 같이 남기는 간단한 로그 */
object GLog {
    private const val TAG = "Gureuneun"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    val lines = MutableStateFlow<List<String>>(emptyList())

    fun i(where: String, msg: String) {
        Log.i(TAG, "[$where] $msg")
        push("${fmt.format(Date())} $where  $msg")
    }

    fun w(where: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$where] $msg", t)
        val cause = t?.let { " (" + (it.message ?: it.javaClass.simpleName) + ")" } ?: ""
        push("${fmt.format(Date())} $where  ! $msg$cause")
    }

    private fun push(s: String) {
        lines.value = (lines.value + s).takeLast(200)
    }
}
