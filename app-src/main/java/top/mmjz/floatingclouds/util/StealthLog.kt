package top.mmjz.floatingclouds.util

import android.util.Log
import top.mmjz.floatingclouds.BuildConfig

/**
 * 调试日志封装。直接使用 android.util.Log（绕过 AppLog），
 * 确保 Release 构建中 ProGuard 不会剥离日志输出。
 * DEBUG 模式下输出 Debug 级别；Release 始终输出 Info/Error。
 */
object StealthLog {
    private const val TAG = "Floatingclouds"
    private val DEBUG = BuildConfig.DEBUG

    @JvmStatic
    fun d(vararg args: Any?) {
        if (DEBUG) Log.d(TAG, args.joinToString(" "))
    }

    @JvmStatic
    fun w(vararg args: Any?) {
        if (DEBUG) Log.w(TAG, args.joinToString(" "))
    }

    @JvmStatic
    fun i(vararg args: Any?) {
        Log.i(TAG, args.joinToString(" "))
    }

    @JvmStatic
    fun e(vararg args: Any?) {
        val msg = args.joinToString(" ") { it?.toString() ?: "null" }
        val t = args.find { it is Throwable } as? Throwable
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    }
}
