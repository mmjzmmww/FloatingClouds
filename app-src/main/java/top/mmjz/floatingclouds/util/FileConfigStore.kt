package top.mmjz.floatingclouds.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 跨进程配置存储 — 直接读写模块自身数据目录下的文件，绕过 SharedPreferences 进程隔离。
 */
object FileConfigStore {
    private const val PKG = "top.mmjz.floatingclouds"
    private const val FILE = "floatingclouds_config.json"

    private var rootDir: File? = null

    fun init(ctx: Context) {
        if (rootDir != null) return
        rootDir = try {
            val ai = ctx.packageManager.getApplicationInfo(PKG, 0)
            File(ai.dataDir, "files")
        } catch (_: Exception) {
            // 包不可见（如 LSPosed 注入场景），使用硬编码路径
            File(Environment.getDataDirectory(), "data/$PKG/files")
        }
        rootDir!!.mkdirs()
        android.util.Log.d("FileConfigStore", "init: rootDir=$rootDir")
    }

    fun read(): String? {
        val f = file() ?: return null
        val result = if (f.exists()) f.readText() else null
        android.util.Log.d("FileConfigStore", "read: ${if (result != null) result.take(50) else "null"}")
        return result
    }

    fun write(json: String) {
        val f = file() ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        val ok = tmp.renameTo(f)
        android.util.Log.d("FileConfigStore", "write: ok=$ok, size=${json.length}")
    }

    private fun file(): File? = rootDir?.let { File(it, FILE) }
}
