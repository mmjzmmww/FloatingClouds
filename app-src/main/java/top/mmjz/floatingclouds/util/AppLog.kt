package top.mmjz.floatingclouds.util

import android.util.Log
import top.mmjz.floatingclouds.BuildConfig
import top.mmjz.floatingclouds.XposedEntry

/**
 * MasksWechat 统一日志系统，替换 com.lu.magic.util.log.LogUtil。
 *
 * 关键：libxposed 框架下，模块进程内直接调用 android.util.Log 输出会被框架/微信
 * 的日志重定向机制吞掉，logcat 中抓不到（LSPosed 管理器默认只收集 Xposed 标签日志）。
 * 因此所有日志统一走 XposedModule.log() 通道（框架重定向到 Xposed 标签），
 * 这样 LSPosed 管理器 → 模块 → 日志 才能看到。
 * 若模块实例尚未就绪（XposedEntry.self == null），fallback 到 android.util.Log。
 */
object AppLog {
    private const val TAG = "Floatingclouds"
    private val DEBUG = BuildConfig.DEBUG

    @JvmStatic
    fun d(vararg args: Any?) {
        if (DEBUG) log(Log.DEBUG, *args)
    }

    @JvmStatic
    fun i(vararg args: Any?) {
        log(Log.INFO, *args)
    }

    @JvmStatic
    fun w(vararg args: Any?) {
        log(Log.WARN, *args)
    }

    @JvmStatic
    fun e(vararg args: Any?) {
        log(Log.ERROR, *args)
    }

    private fun log(level: Int, vararg args: Any?) {
        val msg = buildString {
            for (arg in args) {
                if (isNotEmpty()) append(" ")
                when (arg) {
                    is Throwable -> append(Log.getStackTraceString(arg))
                    else -> append(arg?.toString() ?: "null")
                }
            }
        }
        // ★ 优先走 libxposed 框架日志通道（重定向到 Xposed 标签，LSPosed 管理器可见）
        val module = XposedEntry.self
        if (module != null) {
            try {
                module.log(level, TAG, msg)
            } catch (t: Throwable) {
                // 模块日志通道在某些生命周期阶段（如 XposedEntry 类初始化期）可能尚未就绪，
                // 任何异常都必须兜底到原生 logcat，绝不能让日志调用本身导致调用方崩溃。
                Log.println(level, TAG, msg)
            }
        } else {
            // fallback：模块未初始化时仍尝试原生 logcat
            Log.println(level, TAG, msg)
        }
    }

    /**
     * 带自定义 tag 的原生日志桥接：保留调用方原有 tag（如 Floatingclouds_Init /
     * Floatingclouds_Config），统一走 libxposed 框架日志通道。
     * 用于替换散落在项目中的 android.util.Log.x(tag, msg) 调用。
     */
    @JvmStatic
    fun logRaw(tag: String, level: Int, msg: String, t: Throwable? = null) {
        val module = XposedEntry.self
        if (module != null) {
            try {
                module.log(level, tag, if (t != null) msg + "\n" + Log.getStackTraceString(t) else msg)
            } catch (e: Throwable) {
                if (t != null) Log.println(level, tag, msg + "\n" + Log.getStackTraceString(t))
                else Log.println(level, tag, msg)
            }
        } else {
            if (t != null) Log.println(level, tag, msg + "\n" + Log.getStackTraceString(t))
            else Log.println(level, tag, msg)
        }
    }
}
