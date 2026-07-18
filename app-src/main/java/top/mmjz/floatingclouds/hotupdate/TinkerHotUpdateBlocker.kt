package top.mmjz.floatingclouds.hotupdate

import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.FeatureDiagnostics
import top.mmjz.floatingclouds.util.StealthLog
import top.mmjz.floatingclouds.util.WechatPaths
import java.io.File

/**
 * Tinker 热更新拦截核心服务。
 *
 * 设计要点（详见 docs/system_design.md）：
 * - 本服务承载"必须早于宿主加载点注册"的 Tinker 拦截逻辑，从 [BlockHotUpdatePluginPart]
 *   （原异步 Part）中剥离，改为在 [XposedEntry.initWeChatHooks] 创建 [HookSession] **之后、
 *   `Application.attach` 的 `chain.proceed()` 之前**同步调用 [install] / [cleanupPatchFiles]。
 * - 注册时序是本次修复的胜负手：所有 Tinker 相关 Hook 必须在 `onPackageReady` 同步注册，
 *   且早于 `Application.attach` 的 `chain.proceed()`；禁止经 `Handler.post` 异步或推迟到 `initPlugins`。
 * - 按进程收敛：主进程安装 L0–L3；`:patch` 进程仅安装 L4 + 清理。
 * - 激活判定统一走 [ConfigUtil.isHotUpdateBlockEnabled]（脱离 masterEnabled，默认 true）。
 *
 * 日志约定：拦截统一使用 [StealthLog]（`[BlockHotUpdate]` TAG）；诊断经 [FeatureDiagnostics]。
 */
object TinkerHotUpdateBlocker {

    private const val TAG = "BlockHotUpdate"
    private const val PATCH_PROCESS_SUFFIX = ":patch"

    // ── 运行时拦截计数（用于诊断"已拦截"三态）──
    @Volatile private var l0BlockedCount: Int = 0
    @Volatile private var l1Blocked: Boolean = false
    @Volatile private var l2Blocked: Boolean = false
    @Volatile private var l3ProceededCount: Int = 0
    @Volatile private var l4Blocked: Boolean = false

    // ── 各层"已 Hook"绑定结果（install 时记录，diagnose 时读取）──
    private val layerHooked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ── 注册时序健康标记（install 在首次 tryLoad 前完成 → true）──
    @Volatile private var registeredBeforeTryLoad: Boolean = false

    /**
     * 是否激活拦截。脱离 masterEnabled 门控（REQ-P1-4 方案 a），默认 true（REQ-P1-3）。
     */
    fun isActive(): Boolean = ConfigUtil.isHotUpdateBlockEnabled()

    /**
     * 安装 Tinker 拦截层。必须在 `Application.attach` 的 `chain.proceed()` **之前**调用。
     *
     * 按进程收敛：
     * - 主进程（`com.tencent.mm`）：同步安装 L0（文件系统）/L1（签名）/L2（核心加载）/L3（入口放行）。
     * - `:patch` 进程（`com.tencent.mm:patch`）：仅安装 L4（补丁合成拦截）。
     */
    fun install(session: HookSession) {
        registeredBeforeTryLoad = true
        if (!isActive()) {
            StealthLog.i("[$TAG] install skipped (isActive=false)")
            return
        }
        val processName = session.processName
        if (processName.endsWith(PATCH_PROCESS_SUFFIX)) {
            StealthLog.i("[$TAG] install (patch process) -> L4 only")
            installLayer4(session)
        } else {
            StealthLog.i("[$TAG] install (main process) -> L0..L3")
            installLayer0(session)
            installLayer1(session)
            installLayer2(session)
            installLayer3(session)
        }
    }

    /**
     * 提前（proceed 之前）物理删除已落盘补丁，使当次会话即生效（原 `doTinkerFix` 逻辑迁移）。
     * 须早于 `Application.attach` 的 `chain.proceed()` 调用。
     */
    fun cleanupPatchFiles() {
        if (!isActive()) {
            StealthLog.i("[$TAG] cleanupPatchFiles skipped (isActive=false)")
            FeatureDiagnostics.reportTinkerFix(false, 0, false, null)
            return
        }
        var hadPatch = false
        var deletedCount = 0
        var error: String? = null
        try {
            val tinkerDirs = WechatPaths.getTinkerDirs()
            val tempAndServerDirs = tinkerDirs.filter { it.name == "tinker_temp" || it.name == "tinker_server" }
            for (d in tinkerDirs) {
                if (d.isDirectory) {
                    for (f in d.listFiles() ?: emptyArray()) {
                        if (f.name.startsWith("patch-")) {
                            hadPatch = true
                            if (f.deleteRecursively()) deletedCount++
                        }
                    }
                }
            }
            for (d in tempAndServerDirs) {
                if (d.isDirectory) {
                    for (f in d.listFiles() ?: emptyArray()) {
                        hadPatch = true
                        if (f.deleteRecursively()) deletedCount++
                    }
                }
            }
            StealthLog.i("[$TAG] cleanupPatchFiles hadPatch=$hadPatch deleted=$deletedCount")
        } catch (t: Throwable) {
            error = t.message
            StealthLog.w("[$TAG] cleanupPatchFiles error", t)
        }
        FeatureDiagnostics.reportTinkerFix(hadPatch, deletedCount, true, error)
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer 0: 文件系统层 — File.mkdirs / mkdir / createNewFile
    // ═══════════════════════════════════════════════════════════════

    /**
     * L0 文件系统层：阻止在 Tinker 关键目录下创建补丁目录/文件。
     * 除原 `mkdirs` 外，扩展 Hook `mkdir()` / `createNewFile()`（REQ-P2-1），
     * 统一经 [shouldBlockPath] 判断，防止 `mkdir()` 绕过。
     */
    fun installLayer0(session: HookSession) {
        val mkdirs = session.findAndHook("java.io.File", "mkdirs") { chain -> interceptFileCreate(chain) }
        val mkdir = session.findAndHook("java.io.File", "mkdir") { chain -> interceptFileCreate(chain) }
        val createNewFile = session.findAndHook("java.io.File", "createNewFile") { chain -> interceptFileCreate(chain) }
        reportLayer("L0", listOf(mkdirs, mkdir, createNewFile))
    }

    private fun interceptFileCreate(chain: XposedInterface.Chain): Any? {
        if (!isActive()) return chain.proceed()
        val path = (chain.thisObject as? File)?.absolutePath ?: ""
        if (shouldBlockPath(path)) {
            l0BlockedCount++
            StealthLog.i("[$TAG] L0 blocked file create: $path")
            return false
        }
        return chain.proceed()
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer 1: 签名验证层 — ShareSecurityCheck.verifyPatchMetaSignature
    // ═══════════════════════════════════════════════════════════════

    /**
     * L1 签名验证层：Hook `ShareSecurityCheck.verifyPatchMetaSignature(java.io.File):Boolean`，
     * 直接返回 `false`（**不 proceed**），使微信自行判定补丁非法（REQ-P0-5）。
     */
    fun installLayer1(session: HookSession) {
        val handle = session.findAndHook(
            "com.tencent.tinker.loader.shareutil.ShareSecurityCheck",
            "verifyPatchMetaSignature",
            java.io.File::class.java
        ) { chain ->
            if (!isActive()) return@findAndHook chain.proceed()
            l1Blocked = true
            StealthLog.i("[$TAG] L1 blocked verifyPatchMetaSignature (return false)")
            false
        }
        reportLayer("L1", listOf(handle))
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer 2: 核心加载层 — TinkerLoader.tryLoad（决定性层）
    // ═══════════════════════════════════════════════════════════════

    /**
     * L2 核心加载层：Hook `TinkerLoader.tryLoad(TinkerApplication):Intent`。
     * 微信重写了 TinkerLoader，参数类型实为 `com.tencent.tinker.loader.app.TinkerApplication`
     * （反编译 8.0.74 确证：TinkerLoader.java:526 `public Intent tryLoad(TinkerApplication)`），
     * 而非抽象基类 `android.app.Application`。
     *
     * **实现策略（关键，经真机两轮迭代校正）**：
     * - 不能返回 `null`：`loadTinker()` 把返回值赋给 `tinkerResultIntent` 并透传 `ApplicationLike`，
     *   null 触发 `TinkerRuntimeException: intentResult must not be null`（`FATAL EXCEPTION: initThread`）。
     * - 不能手搓"裸"无补丁 Intent：缺 Tinker 正常流程设置的其它 extras，导致 `ApplicationLike`
     *   后续初始化走到坏路径（`nq1.a.onCreate` NPE），真机实测崩溃。
     * - **正确做法：直接 `chain.proceed()` 让 Tinker 自己跑 `tryLoad`**。补丁已被 `cleanupPatchFiles`
     *   在 `onPackageReady` 阶段删除、且 L1（`verifyPatchMetaSignature` 返回 false）使签名校验失败，
     *   Tinker 必然返回它**自身合法的无补丁 Intent**（与官方"无补丁启动"路径 100% 一致、含全部正确
     *   extras）→ 微信正常初始化、补丁 dex 永不加载。热更新拦截由"删除 + 签名校验失败 + 文件创建阻断
     *   + :patch 进程合成阻断"共同保证，L2 透明放行即可。
     * - 捕获返回码 `intent_return_code` 用于三态诊断：`!= 0` 即补丁未加载（拦截成功）。
     */
    fun installLayer2(session: HookSession) {
        val tinkerAppCls =
            AppReflect.findClassIfExists("com.tencent.tinker.loader.app.TinkerApplication", session.classLoader)
                ?: android.app.Application::class.java
        val handle = session.findAndHook(
            "com.tencent.tinker.loader.TinkerLoader",
            "tryLoad",
            tinkerAppCls
        ) { chain ->
            if (!isActive()) return@findAndHook chain.proceed()
            val result = chain.proceed() as? android.content.Intent
            val code = result?.getIntExtra("intent_return_code", 0) ?: -1
            l2Blocked = (code != 0)
            StealthLog.i("[$TAG] L2 TinkerLoader.tryLoad proceeded, returnCode=$code (patch loaded=${code == 0})")
            result
        }
        reportLayer("L2", listOf(handle))
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer 3: 入口层 — TinkerApplication.onBaseContextAttached（放行）
    // ═══════════════════════════════════════════════════════════════

    /**
     * L3 入口层：Hook `TinkerApplication.onBaseContextAttached(Context, long, long)`。
     * 改为 `chain.proceed()` **放行**（不再 return null），避免跳过微信 `ensureDelegate()` /
     * `ApplicationLike` 代理初始化导致微信无法启动（§6.4）；拦截完全由 L2 `tryLoad` 返回失败实现。
     */
    fun installLayer3(session: HookSession) {
        val handle = session.findAndHook(
            "com.tencent.tinker.loader.app.TinkerApplication",
            "onBaseContextAttached",
            android.content.Context::class.java,
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!
        ) { chain ->
            if (!isActive()) return@findAndHook chain.proceed()
            l3ProceededCount++
            StealthLog.i("[$TAG] L3 proceeded onBaseContextAttached (allow WeChat init)")
            chain.proceed()
        }
        reportLayer("L3", listOf(handle))
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer 4: 自研补丁服务层 — LegacyTinkerCore$PatchService（:patch 进程）
    // ═══════════════════════════════════════════════════════════════

    /**
     * L4 自研补丁服务层：Hook `LegacyTinkerCore$PatchService` 的入口方法，阻断补丁合成。
     * 仅在 `:patch` 进程的 `onPackageReady` **同步**注册（替代原 `Handler.post` 异步，防早到 intent 漏拦）。
     *
     * 方法名验证（Open Q1）：优先 `onHandleIntent`（`IntentService` 约定）；
     * 若该类已改为非 `IntentService`，回退候选 `handleIntent` / `onStartCommand`。
     * `LegacyTinkerCore` 仅 8.0.71+ 存在，低版本类不存在时自动跳过。
     */
    fun installLayer4(session: HookSession) {
        val cl = session.classLoader
        val patchSvcCls = AppReflect.findClassIfExists(
            "com.tencent.mm.hotpatch.LegacyTinkerCore\$PatchService", cl
        )
        if (patchSvcCls == null) {
            StealthLog.i("[$TAG] L4 skipped (LegacyTinkerCore not found, wx<8.0.71)")
            layerHooked["L4"] = false
            return
        }
        val entryMethod = resolvePatchServiceEntry(patchSvcCls)
        val handle = session.findAndHook(
            "com.tencent.mm.hotpatch.LegacyTinkerCore\$PatchService",
            entryMethod,
            android.content.Intent::class.java
        ) { chain ->
            if (!isActive()) return@findAndHook chain.proceed()
            l4Blocked = true
            StealthLog.i("[$TAG] L4 blocked LegacyTinkerCore.PatchService.$entryMethod")
            null
        }
        reportLayer("L4", listOf(handle))
    }

    /**
     * 解析补丁服务入口方法名：优先 `onHandleIntent`，回退 `handleIntent` / `onStartCommand`。
     */
    private fun resolvePatchServiceEntry(patchSvcCls: Class<*>): String {
        if (AppReflect.findMethodExact(patchSvcCls, "onHandleIntent", android.content.Intent::class.java) != null) {
            return "onHandleIntent"
        }
        if (AppReflect.findMethodExact(patchSvcCls, "handleIntent", android.content.Intent::class.java) != null) {
            return "handleIntent"
        }
        if (AppReflect.findMethodExact(
                patchSvcCls, "onStartCommand",
                android.content.Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ) != null
        ) {
            return "onStartCommand"
        }
        // 兜底：沿用 IntentService 约定
        return "onHandleIntent"
    }

    // ═══════════════════════════════════════════════════════════════
    // 诊断：三态（类存在 / 已Hook / 已拦截）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 诊断逐层状态：探测"类存在 / 已 Hook（install 时 findAndHook 返回非 null）/ 已拦截（运行时命中）"，
     * 经 [FeatureDiagnostics.reportTinkerFixLayers] 输出真实 OK/FAIL 计数（纠正 "5 OK" 误报，HIGH-3）。
     *
     * 注意：仅 `File` 一定存在；其余层依赖微信 Tinker 类是否存在。
     */
    fun diagnose(cl: ClassLoader, session: HookSession) {
        val isPatch = session.processName.endsWith(PATCH_PROCESS_SUFFIX)
        // 按进程收敛诊断范围：主进程关注 L0–L3，:patch 进程仅关注 L4（避免无关层误报 NOT_HOOKED）。
        val layers = if (isPatch) {
            listOf(
                LayerStatusLocal(
                    name = "L4",
                    classExists = classExists(cl, "com.tencent.mm.hotpatch.LegacyTinkerCore\$PatchService"),
                    hooked = layerHooked["L4"] ?: false,
                    blocked = l4Blocked
                )
            )
        } else {
            listOf(
                LayerStatusLocal(
                    name = "L0",
                    classExists = true,
                    hooked = layerHooked["L0"] ?: false,
                    blocked = l0BlockedCount > 0
                ),
                LayerStatusLocal(
                    name = "L1",
                    classExists = classExists(cl, "com.tencent.tinker.loader.shareutil.ShareSecurityCheck"),
                    hooked = layerHooked["L1"] ?: false,
                    blocked = l1Blocked
                ),
                LayerStatusLocal(
                    name = "L2",
                    classExists = classExists(cl, "com.tencent.tinker.loader.TinkerLoader"),
                    hooked = layerHooked["L2"] ?: false,
                    blocked = l2Blocked
                ),
                LayerStatusLocal(
                    name = "L3",
                    classExists = classExists(cl, "com.tencent.tinker.loader.app.TinkerApplication"),
                    hooked = layerHooked["L3"] ?: false,
                    blocked = l3ProceededCount > 0
                )
            )
        }
        StealthLog.i("[$TAG] diagnose (${if (isPatch) "patch" else "main"} process) layers=${layers.size}")
        FeatureDiagnostics.reportTinkerFixLayers(layers.map {
            FeatureDiagnostics.LayerStatus(it.name, it.classExists, it.hooked, it.blocked)
        })
        FeatureDiagnostics.reportTinkerTimingHealth(registeredBeforeTryLoad)
    }

    // ── 内部工具 ──

    private fun classExists(cl: ClassLoader, className: String): Boolean =
        AppReflect.findClassIfExists(className, cl) != null

    private fun reportLayer(name: String, handles: List<XposedInterface.HookHandle?>) {
        val hooked = handles.any { it != null }
        layerHooked[name] = hooked
        if (hooked) {
            StealthLog.i("[$TAG] $name OK (hooked)")
        } else {
            // 纠正 "FAILED (method not found)"：findAndHook 返回 null = 未绑定（HIGH-3）
            StealthLog.e("[$TAG] $name FAILED (method not found)")
        }
    }

    /**
     * 路径是否应被拦截：统一走 [WechatPaths.isTinkerPath]（路径含 Tinker 关键段即命中，防绕过）。
     */
    private fun shouldBlockPath(path: String): Boolean = WechatPaths.isTinkerPath(path)

    /** 诊断内部用轻量三态结构（对外经 [FeatureDiagnostics.LayerStatus] 上报）。 */
    private data class LayerStatusLocal(
        val name: String,
        val classExists: Boolean,
        val hooked: Boolean,
        val blocked: Boolean
    )
}
