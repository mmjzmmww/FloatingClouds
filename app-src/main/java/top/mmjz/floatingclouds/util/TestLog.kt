package top.mmjz.floatingclouds.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import top.mmjz.floatingclouds.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对外测试版统一日志核心（Floatingclouds TestLog）。
 *
 * 设计目标（区别于 AppLog）：
 *  - 同时输出到 logcat（标签 FC_Trace / FC_Error）与文件（微信私有目录 files/fc_log/）。
 *  - 异步单线程写入（HandlerThread），不阻塞微信主线程。
 *  - 带构建指纹头（versionCode / gitCommit / buildTime / IS_TEST_BUILD），
 *    使回传日志可对应到唯一的 mapping.txt，在 R8 混淆下 100% 定位错误。
 *  - 支持结构化 trace：key=value 形式，便于 grep / 脚本解析。
 *  - 按天滚动 + 单文件大小上限（2MB）+ 最多保留 3 个文件，避免撑爆存储。
 *
 * Release 正式版不会调用本类（proguard-rules.pro 仍剥离 AppLog），
 * 仅 debug buildType（IS_TEST_BUILD=true，minifyEnabled=false）接入（见 XposedEntry / FeatureDiagnostics 调用处）。
 */
object TestLog {

    private const val TAG_TRACE = "FC_Trace"
    private const val TAG_ERROR = "FC_Error"
    private const val DIR_NAME = "fc_log"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 // 2MB
    private const val MAX_FILES = 3

    @Volatile private var initialized = AtomicBoolean(false)
    private var logDir: File? = null
    private var currentFile: File? = null
    private var writer: FileWriter? = null
    private var bytesWritten = 0L

    private val thread = HandlerThread("FC-LogWriter").apply { start() }
    private val handler = Handler(thread.looper)

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** 构建指纹字符串（一次性生成，所有日志共享）。 */
    private val buildFingerprint: String by lazy {
        "v=${BuildConfig.VERSION_CODE}(${BuildConfig.VERSION_NAME}) " +
            "git=${BuildConfig.GIT_COMMIT} build=${BuildConfig.BUILD_TIME} " +
            "test=${BuildConfig.IS_TEST_BUILD} flavor=${BuildConfig.FLAVOR}"
    }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        handler.post {
            try {
                // 微信私有目录：context.filesDir 由微信进程持有，可写
                val dir = File(context.filesDir, DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                logDir = dir
                openNewFileIfNeeded()
                // 写入构建指纹头，作为本会话起始标记
                appendRaw("========== SESSION START ==========")
                appendRaw("FINGERPRINT: $buildFingerprint")
                appendRaw("PROCESS: ${android.os.Process.myPid()} device=${android.os.Build.MODEL}")
                appendRaw("===================================")
            } catch (t: Throwable) {
                Log.e(TAG_ERROR, "TestLog init failed", t)
            }
        }
    }

    // ═══════════ 公开 API ═══════════

    @JvmStatic
    fun trace(tag: String, msg: String) {
        emit(Log.DEBUG, TAG_TRACE, "[$tag] $msg")
    }

    @JvmStatic
    fun info(tag: String, msg: String) {
        emit(Log.INFO, TAG_TRACE, "[$tag] $msg")
    }

    @JvmStatic
    fun warn(tag: String, msg: String) {
        emit(Log.WARN, TAG_ERROR, "[$tag] $msg")
    }

    /**
     * 错误日志 + 唯一错误码（如 [E-SNS-001]），配合 mapping.txt 可定位到具体源码行。
     */
    @JvmStatic
    fun error(code: String, tag: String, msg: String, t: Throwable? = null) {
        val body = "[$tag][$code] $msg"
        emit(Log.ERROR, TAG_ERROR, body, t)
    }

    @JvmStatic
    fun error(tag: String, msg: String, t: Throwable? = null) {
        emit(Log.ERROR, TAG_ERROR, "[$tag] $msg", t)
    }

    /**
     * 结构化 trace：变参 key=value 形式。
     * 用法：TestLog.traceStruct("SNS", "hookHit", true, "class", "xxx.yyy", "wxVer", 8030)
     */
    @JvmStatic
    fun traceStruct(tag: String, vararg kv: Any?) {
        val sb = StringBuilder()
        var i = 0
        while (i < kv.size - 1) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append("${kv[i]}=${kv[i + 1]}")
            i += 2
        }
        trace(tag, sb.toString())
    }

    // ═══════════ 内部实现 ═══════════

    private fun emit(level: Int, logcatTag: String, msg: String, t: Throwable? = null) {
        // 1) logcat 输出：优先走 libxposed 框架日志通道（重定向到 Xposed 标签，
        //    LSPosed 管理器可见）；框架通道不可用时 fallback 到 android.util.Log。
        //    注意：直接用 android.util.Log 在微信进程里会被日志重定向吞掉，抓不到。
        val module = top.mmjz.floatingclouds.XposedEntry.self
        if (module != null) {
            if (t != null) module.log(level, logcatTag, msg + "\n" + Log.getStackTraceString(t))
            else module.log(level, logcatTag, msg)
        } else {
            if (t != null) Log.println(level, logcatTag, msg + "\n" + Log.getStackTraceString(t))
            else Log.println(level, logcatTag, msg)
        }
        // 2) 异步落文件
        handler.post { appendRaw("${dateFmt.format(Date())} ${levelChar(level)} $msg${t?.let { " | ${Log.getStackTraceString(it)}" } ?: ""}") }
    }

    private fun levelChar(level: Int): String = when (level) {
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "V"
    }

    private fun openNewFileIfNeeded() {
        val dir = logDir ?: return
        val today = fileDateFmt.format(Date())
        val candidate = File(dir, "fc_$today.log")
        if (currentFile == null || !currentFile!!.exists() || currentFile!!.name != candidate.name) {
            // 关闭旧 writer
            closeWriter()
            currentFile = candidate
            bytesWritten = if (candidate.exists()) candidate.length() else 0L
            writer = FileWriter(candidate, true)
        }
        // 轮换：超出上限则新建带序号文件
        if (bytesWritten > MAX_FILE_BYTES && currentFile != null) {
            closeWriter()
            val rotated = File(dir, "fc_${today}_${System.currentTimeMillis()}.log")
            currentFile = rotated
            bytesWritten = 0L
            writer = FileWriter(rotated, true)
            pruneOldFiles()
        }
    }

    private fun appendRaw(line: String) {
        try {
            openNewFileIfNeeded()
            writer?.apply {
                write(line)
                write("\n")
                flush()
            }
            bytesWritten += line.length + 1
        } catch (t: Throwable) {
            Log.e(TAG_ERROR, "TestLog append failed", t)
        }
    }

    private fun pruneOldFiles() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith("fc_") && f.extension == "log" }
            ?.sortedBy { it.name } ?: return
        // 保留最新的 MAX_FILES 个
        files.take(maxOf(0, files.size - MAX_FILES)).forEach { it.delete() }
    }

    private fun closeWriter() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }

    /** 返回日志目录绝对路径（供导出使用）。 */
    @JvmStatic
    fun getLogDir(): File? = logDir

    /** 返回所有日志文件（供导出使用）。 */
    @JvmStatic
    fun getLogFiles(): Array<File> {
        val dir = logDir ?: return emptyArray()
        return dir.listFiles { f -> f.name.startsWith("fc_") && f.extension == "log" } ?: emptyArray()
    }
}
