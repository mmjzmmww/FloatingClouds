package top.mmjz.floatingclouds.util

import android.content.Context
import org.json.JSONObject
import java.io.File
import top.mmjz.floatingclouds.util.WechatPaths

/**
 * 统一功能自检诊断系统。
 *
 * 每个功能模块在初始化时注册诊断项，启动后可通过日志过滤 "[DIAG-HEALTH]"
 * 查看所有功能的一键健康报告。
 *
 * 用法：
 *   FeatureDiagnostics.healthCheck(context, classLoader)
 *
 * 输出格式（每行一条独立日志）：
 *   [DIAG-HEALTH] module=xxx status=OK|WARN|FAIL key=value...
 */
object FeatureDiagnostics {

    // ═════════════════════════════════
    // 健康报告入口
    // ═════════════════════════════════

    fun healthCheck(context: Context, classLoader: ClassLoader?) {
        AppLog.i("[DIAG-HEALTH] ===============================")
        AppLog.i("[DIAG-HEALTH] Feature Health Report START")
        AppLog.i("[DIAG-HEALTH] ===============================")

        checkTinkerFixHealth()
        checkTinkerFixLayersHealth()
        checkTinkerTimingHealth()
        checkRCRHealth()
        checkDexKitCacheHealth()
        checkDexKitScanHealth()
        checkPartsInitHealth()
        checkClassResolverHealth(classLoader)
        checkConfigHealth(context)

        AppLog.i("[DIAG-HEALTH] ===============================")
        AppLog.i("[DIAG-HEALTH] Feature Health Report END")
        AppLog.i("[DIAG-HEALTH] ===============================")
    }

    // ═════════════════════════════════
    // 1. TinkerFix 热补丁清理诊断
    // ═════════════════════════════════

    @Volatile private var tinkerHadPatch = false
    @Volatile private var tinkerDeletedCount = 0
    @Volatile private var tinkerBlockEnabled = false
    @Volatile private var tinkerError: String? = null

    fun reportTinkerFix(hadPatch: Boolean, deletedCount: Int, blockEnabled: Boolean, error: String?) {
        tinkerHadPatch = hadPatch
        tinkerDeletedCount = deletedCount
        tinkerBlockEnabled = blockEnabled
        tinkerError = error
        AppLog.i("[DIAG-HEALTH] module=TinkerFix status=${if (error != null) "FAIL" else "OK"} " +
            "hadPatch=$hadPatch deleted=$deletedCount blockEnabled=$blockEnabled error=$error")
    }

    private fun checkTinkerFixHealth() {
        val patchDir = File(WechatPaths.getWechatDataDir(), "tinker")
        val remainingPatches = if (patchDir.isDirectory) {
            patchDir.listFiles()?.filter { it.name.startsWith("patch-") }?.size ?: 0
        } else 0
        val status = when {
            tinkerError != null -> "FAIL"
            remainingPatches > 0 -> "WARN"
            else -> "OK"
        }
        AppLog.i("[DIAG-HEALTH] module=TinkerFix status=$status " +
            "hadPatch=$tinkerHadPatch deleted=$tinkerDeletedCount " +
            "blockEnabled=$tinkerBlockEnabled remainingPatches=$remainingPatches error=$tinkerError")
    }

    // ═════════════════════════════════
    // 1.5 TinkerFix 分层三态 + 注册时序健康
    // ═════════════════════════════════

    /**
     * 单层拦截状态（三态）：类存在 / 已 Hook / 已拦截。
     * 用于纠正原 "✅ 类存在" 误导（LOW-8）与 "5 OK" 误报（HIGH-3）。
     */
    data class LayerStatus(
        val name: String,
        val classExists: Boolean,
        val hooked: Boolean,
        val blocked: Boolean
    )

    @Volatile private var tinkerLayerStatuses: List<LayerStatus>? = null
    @Volatile private var tinkerTimingHealthy: Boolean? = null

    /**
     * 上报 Tinker 拦截层逐层三态（类存在 / 已 Hook / 已拦截）。
     * 真实 OK/FAIL 计数：hooked=OK，classExists 但未 hooked=FAIL。
     */
    fun reportTinkerFixLayers(layers: List<LayerStatus>) {
        tinkerLayerStatuses = layers
        val okCount = layers.count { it.hooked }
        val failCount = layers.count { it.classExists && !it.hooked }
        val status = when {
            failCount > 0 -> "WARN"
            okCount == 0 -> "FAIL"
            else -> "OK"
        }
        AppLog.i("[DIAG-HEALTH] module=TinkerFixLayers status=$status " +
            "ok=$okCount fail=$failCount total=${layers.size}")
        for (layer in layers) {
            val state = when {
                !layer.classExists -> "CLASS_MISSING"
                !layer.hooked -> "NOT_HOOKED"
                layer.blocked -> "BLOCKED"
                else -> "HOOKED_IDLE"
            }
            AppLog.i("[DIAG-HEALTH]   layer=${layer.name} classExists=${layer.classExists} " +
                "hooked=${layer.hooked} blocked=${layer.blocked} state=$state")
        }
        // 非 Tinker 通道未覆盖标注（REQ-P2-3 / Open Q4）
        AppLog.i("[DIAG-HEALTH]   note=nonTinkerChannel(NOT_COVERED) " +
            "reason=仅覆盖 Tinker / LegacyTinkerCore 热更新通道")
    }

    /**
     * 上报注册时序健康：拦截层是否在首次 `tryLoad` 之前同步注册。
     * 本次修复后恒为 true（install 在 `chain.proceed()` 之前完成）。
     */
    fun reportTinkerTimingHealth(registeredBeforeTryLoad: Boolean) {
        tinkerTimingHealthy = registeredBeforeTryLoad
        AppLog.i("[DIAG-HEALTH] module=TinkerFixTiming status=${if (registeredBeforeTryLoad) "OK" else "FAIL"} " +
            "registeredBeforeTryLoad=$registeredBeforeTryLoad " +
            "note=拦截层已在首次 tryLoad 前同步注册（onPackageReady）")
    }

    private fun checkTinkerFixLayersHealth() {
        val layers = tinkerLayerStatuses ?: return
        val okCount = layers.count { it.hooked }
        val failCount = layers.count { it.classExists && !it.hooked }
        AppLog.i("[DIAG-HEALTH] module=TinkerFixLayers(healthCheck) status=${if (failCount > 0) "WARN" else "OK"} " +
            "ok=$okCount fail=$failCount")
    }

    private fun checkTinkerTimingHealth() {
        val healthy = tinkerTimingHealthy ?: return
        AppLog.i("[DIAG-HEALTH] module=TinkerFixTiming(healthCheck) status=${if (healthy) "OK" else "FAIL"}")
    }

    // ═════════════════════════════════
    // 2. RCR (Runtime Class Resolver) 诊断
    // ═════════════════════════════════

    @Volatile private var rcrIsReady = false
    @Volatile private var rcrCachedCount = 0
    @Volatile private var rcrFallbackCount = 0
    @Volatile private var rcrTotalCount = 0
    @Volatile private var rcrDiagInfo: String? = null

    fun reportRCR(isReady: Boolean, cachedCount: Int, fallbackCount: Int, totalCount: Int, diagInfo: String?) {
        rcrIsReady = isReady
        rcrCachedCount = cachedCount
        rcrFallbackCount = fallbackCount
        rcrTotalCount = totalCount
        rcrDiagInfo = diagInfo
        AppLog.i("[DIAG-HEALTH] module=RCR status=${if (isReady) "OK" else "WARN"} " +
            "total=$totalCount cached=$cachedCount fallback=$fallbackCount")
    }

    private fun checkRCRHealth() {
        val status = when {
            !rcrIsReady -> "WARN"
            rcrFallbackCount > 0 -> "WARN"
            else -> "OK"
        }
        AppLog.i("[DIAG-HEALTH] module=RCR status=$status " +
            "ready=$rcrIsReady total=$rcrTotalCount cached=$rcrCachedCount fallback=$rcrFallbackCount " +
            "diag=$rcrDiagInfo")
    }

    // ═════════════════════════════════
    // 3. DexKit 缓存健康
    // ═════════════════════════════════

    @Volatile private var dkSelfKeys = 0
    @Volatile private var dkInkKeys = 0
    @Volatile private var dkCacheReady = false

    fun reportDexKitCache(selfKeys: Int, inkKeys: Int, isReady: Boolean) {
        dkSelfKeys = selfKeys
        dkInkKeys = inkKeys
        dkCacheReady = isReady
        AppLog.i("[DIAG-HEALTH] module=DexKitCache status=${if (isReady) "OK" else (if (selfKeys + inkKeys > 0) "WARN" else "FAIL")} " +
            "selfKeys=$selfKeys inkKeys=$inkKeys ready=$isReady")
    }

    private fun checkDexKitCacheHealth() {
        AppLog.i("[DIAG-HEALTH] module=DexKitCache " +
            "selfKeys=$dkSelfKeys inkKeys=$dkInkKeys ready=$dkCacheReady")
    }

    // ═════════════════════════════════
    // 4. DexKit 扫描状态
    // ═════════════════════════════════

    @Volatile private var dkScanCompleted = false
    @Volatile private var dkScanTaskResults = mutableMapOf<String, String>() // task -> OK/FAIL/SKIP
    @Volatile private var dkScanError: String? = null

    fun reportDexKitScan(taskName: String, status: String, detail: String = "") {
        synchronized(dkScanTaskResults) {
            dkScanTaskResults[taskName] = status
        }
        AppLog.i("[DIAG-HEALTH] module=DexKitScan task=$taskName status=$status $detail")
    }

    fun reportDexKitScanDone(completed: Boolean, error: String?) {
        dkScanCompleted = completed
        dkScanError = error
    }

    private fun checkDexKitScanHealth() {
        val summary = synchronized(dkScanTaskResults) { dkScanTaskResults.toMap() }
        val okCount = summary.values.count { it == "OK" }
        val failCount = summary.values.count { it == "FAIL" }
        val status = when {
            !dkScanCompleted -> "PENDING"
            failCount > 0 -> "WARN"
            okCount == 0 -> "FAIL"
            else -> "OK"
        }
        AppLog.i("[DIAG-HEALTH] module=DexKitScan status=$status " +
            "completed=$dkScanCompleted tasksTotal=${summary.size} ok=$okCount fail=$failCount " +
            "error=$dkScanError details=${summary.entries.joinToString(",") { "${it.key}=${it.value}" }}")
    }

    // ═════════════════════════════════
    // 5. Parts 初始化状态
    // ═════════════════════════════════

    @Volatile private var partsInitComplete = false
    private val partResults = LinkedHashMap<String, PartResult>()

    data class PartResult(
        val status: String,  // OK/FAIL/TIMEOUT
        val durationMs: Long,
        val error: String?
    )

    fun reportPartInit(partName: String, status: String, durationMs: Long, error: String?) {
        synchronized(partResults) {
            partResults[partName] = PartResult(status, durationMs, error)
        }
        if (status == "FAIL" || status == "TIMEOUT") {
            AppLog.w("[DIAG-HEALTH] module=PartsInit part=$partName status=$status duration=${durationMs}ms error=$error")
        }
    }

    fun reportPartsInitDone() {
        partsInitComplete = true
    }

    private fun checkPartsInitHealth() {
        val results = synchronized(partResults) { partResults.toMap() }
        val okCount = results.values.count { it.status == "OK" }
        val failCount = results.values.count { it.status == "FAIL" }
        val timeoutCount = results.values.count { it.status == "TIMEOUT" }
        val status = when {
            !partsInitComplete -> "PENDING"
            failCount > 0 -> "FAIL"
            timeoutCount > 0 -> "WARN"
            okCount == results.size -> "OK"
            else -> "WARN"
        }
        val summary = results.entries.joinToString(";") { (name, r) ->
            "$name=${r.status}(${r.durationMs}ms)"
        }
        AppLog.i("[DIAG-HEALTH] module=PartsInit status=$status " +
            "total=${results.size} ok=$okCount fail=$failCount timeout=$timeoutCount " +
            "complete=$partsInitComplete [${summary.take(200)}]")
    }

    // ═════════════════════════════════
    // 6. 类解析器健康
    // ═════════════════════════════════

    @Volatile private var clsResolverVersion: String? = null
    @Volatile private var clsResolverEntries = mutableMapOf<String, String>() // key -> FOUND/MISSING/PENDING

    fun reportClassResolved(key: String, found: Boolean, className: String?) {
        synchronized(clsResolverEntries) {
            clsResolverEntries[key] = if (found) "FOUND:$className" else "MISSING"
        }
        if (!found) {
            AppLog.w("[DIAG-HEALTH] module=ClassResolver key=$key status=MISSING class=$className")
        }
    }

    fun reportClassResolverVersion(version: String?) {
        clsResolverVersion = version
    }

    private fun checkClassResolverHealth(classLoader: ClassLoader?) {
        val entries = synchronized(clsResolverEntries) { clsResolverEntries.toMap() }
        val foundCount = entries.count { it.value.startsWith("FOUND") }
        val missCount = entries.count { it.value == "MISSING" }
        val status = when {
            missCount > 0 -> "WARN"
            foundCount > 0 -> "OK"
            else -> "PENDING"
        }
        AppLog.i("[DIAG-HEALTH] module=ClassResolver status=$status " +
            "version=$clsResolverVersion total=${entries.size} found=$foundCount miss=$missCount " +
            "missing=${entries.filter { it.value == "MISSING" }.keys.joinToString(",")}")
    }

    // ═════════════════════════════════
    // 7. 配置健康
    // ═════════════════════════════════

    private fun checkConfigHealth(context: Context) {
        try {
            val sp = context.getSharedPreferences(
                "top.mmjz.floatingclouds_preferences", Context.MODE_PRIVATE
            )
            val optsJson = sp.getString("options", null)
            if (optsJson != null) {
                val opts = JSONObject(optsJson)
                val keys = listOf("masterEnabled", "blockHotUpdate", "hideConversation",
                    "hideMainConvList", "hideContactList", "hideMainSearch", "hideRecentForward",
                    "hideSnsEntry", "blockEnterChat", "blockContactInfo")
                val summary = keys.map { "$it=${opts.optBoolean(it, false)}" }.joinToString(" ")
                AppLog.i("[DIAG-HEALTH] module=Config status=OK $summary")
            } else {
                AppLog.w("[DIAG-HEALTH] module=Config status=WARN options=null")
            }
            val maskList = sp.getString("maskList", null)
            val maskCount = if (maskList != null) {
                try { JSONObject("{a:$maskList}").optJSONArray("a")?.length() ?: 0 } catch (_: Exception) { 0 }
            } else 0
            AppLog.i("[DIAG-HEALTH] module=Config maskCount=$maskCount")
        } catch (e: Exception) {
            AppLog.w("[DIAG-HEALTH] module=Config status=FAIL error=${e.message}")
        }
    }
}
