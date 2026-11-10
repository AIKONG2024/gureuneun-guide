package app.gureuneun.guide.sdk

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면의 점검 탭과 logcat 에 같이 남기는 간단한 로그. 여러 스레드(차량, 내비, 음성 콜백)에서 불러도 됨 */
object GLog {
    private const val TAG = "Gureuneun"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    val lines = MutableStateFlow<List<String>>(emptyList())

    fun i(where: String, msg: String) {
        Log.i(TAG, "[$where] $msg")
        push("${now()} $where  $msg")
    }

    fun w(where: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$where] $msg", t)
        val cause = t?.let { " (" + (it.message ?: it.javaClass.simpleName) + ")" } ?: ""
        push("${now()} $where  ! $msg$cause")
    }

    // SimpleDateFormat 은 여러 스레드에서 같이 쓰면 안 됨
    private fun now(): String = synchronized(fmt) { fmt.format(Date()) }

    private fun push(s: String) {
        lines.update { (it + s).takeLast(200) }
    }
}
