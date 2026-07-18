package top.mmjz.floatingclouds.util

/**
 * 独立日志 — 使用android.util.Log.e输出(FCloud_标签)，
 * 配合 adb logcat -d > file 读取，ERROR级别确保不过滤。
 */
object FileLog {
    fun init() {
        android.util.Log.e("FCloud_FileLog", "init OK")
    }
    fun log(msg: String) {
        android.util.Log.e("FCloud_Root", msg)
    }
    fun i(tag: String, msg: String) {
        android.util.Log.e("FCloud_" + tag, msg)
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e("FCloud_" + tag, msg + (t?.let { " ${it.message}" } ?: ""))
    }
}
