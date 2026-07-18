
package top.mmjz.floatingclouds.util
import top.mmjz.floatingclouds.util.AppContext

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局 Context 持有器，替代 com.lu.magic.util.AppUtil。
 * 在 WeChat 进程中，AppContext.context!! 不会自动初始化，需要由 XposedEntry 显式设置。
 */
object AppContext {
    @Volatile
    @JvmField
    var context: Context? = null

    fun attach(context: Context) {
        if (this.context == null) {
            this.context = context.applicationContext
        }
    }
}

/**
 * SharedPreferences 工具。
 *
 * 存储路径说明：
 * - AppContext.context 在 WeChat 进程中由 XposedEntry 通过 createPackageContext 设置为模块自身 Context
 * - 在自身进程中由 App.onCreate() 设置为 Application Context
 * - SP 统一落在 /data/data/top.mmjz.floatingclouds/shared_prefs/ 下
 * - 因此本方法不再做二次 createPackageContext，避免冗余和退化风险
 */
class LocalKVUtil {
    companion object {
        const val defaultTableName = "app"

        @JvmStatic
        @JvmOverloads
        fun getTable(name: String, mode: Int = Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS): SharedPreferences {
            val ctx = AppContext.context!!
            // ★ 直接使用 AppContext.context（已在 XposedEntry/App 中预先设置为模块自身 Context）
            // 不再做冗余的 createPackageContext（原是双重调用且有退化到微信目录的风险）
            val sp = ctx.getSharedPreferences(name, mode)
            return sp
        }

        /**
         * 诊断日志：打印 SP 文件的实际磁盘路径，用于排查配置丢失问题。
         * 在 XposedEntry 初始化后调用一次即可。
         */
        @JvmStatic
        fun dumpSpPath(tag: String) {
            try {
                val ctx = AppContext.context ?: return
                val dataDir = ctx.applicationInfo?.dataDir ?: ctx.dataDir?.absolutePath ?: "unknown"
                AppLog.logRaw(tag, android.util.Log.INFO, "SP base path: $dataDir/shared_prefs/")
                AppLog.logRaw(tag, android.util.Log.INFO, "AppContext.context.packageName: ${ctx.packageName}")
            } catch (e: Exception) {
                AppLog.logRaw(tag, android.util.Log.WARN, "dumpSpPath failed", e)
            }
        }

        @JvmStatic
        fun getDefaultTable(): SharedPreferences {
            return getTable(defaultTableName)
        }
    }
}
