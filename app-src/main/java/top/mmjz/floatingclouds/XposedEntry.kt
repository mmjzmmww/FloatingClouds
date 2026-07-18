package top.mmjz.floatingclouds

import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.CommonPlugin
import top.mmjz.floatingclouds.plugin.PluginProviders
import top.mmjz.floatingclouds.plugin.WXDbPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppLog
import top.mmjz.floatingclouds.util.ConfigUtil

/**
 * Floatingclouds API 102 统一入口，替代 MainHook.java。
 *
 * 核心变更：
 * - extends XposedModule（替代 IXposedHookLoadPackage）
 * - 生命周期：onModuleLoaded → onPackageLoaded → onPackageReady
 * - 插件通过 HookSession 接入，不再依赖 XposedHelpers2 / XC_MethodHook2
 * - 完整热重载支持：onHotReloading / onHotReloaded
 */
class XposedEntry : XposedModule() {

    companion object {
        private const val TARGET_PACKAGE = "com.tencent.mm"

        internal var self: XposedEntry? = null
            private set
    }

    // 存储带 ID 的 HookHandle，供热重载时原子替换
    private val hookHandles = mutableMapOf<String, XposedInterface.HookHandle>()

    init {
        self = this
        // ★ 关键修复：init 阶段框架日志通道尚未就绪，调用 AppLog.i 会经 AppLog.log →
        // module.log() 命中 libxposed 未就绪的日志通道并抛异常，导致 XposedEntry 类初始化
        // 失败（ExceptionInInitializerError）→ LSPosed 报 "Failed to load class ... No module
        // class loaded" → 模块整体失效（设置入口也调不出来）。故此处只用原生 Log，绝不碰
        // 依赖模块实例的 AppLog。
        android.util.Log.i("Floatingclouds", "Floatingclouds XposedEntry constructed")
    }

    // ═══════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        AppLog.i("onModuleLoaded:", param.processName, "pid=", Process.myPid())

        // 非目标进程直接跳过
        if (param.processName != TARGET_PACKAGE && param.processName != BuildConfig.APPLICATION_ID) {
            AppLog.i("Skipping non-target process:", param.processName)
            return
        }
        // 此时 classLoader 未就绪，等待 onPackageLoaded
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE && param.packageName != BuildConfig.APPLICATION_ID) {
            return
        }
        AppLog.i("onPackageLoaded:", param.packageName, "isFirst=", param.isFirstPackage)
        // classLoader 在此阶段尚未完全就绪，等待 onPackageReady
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE && param.packageName != BuildConfig.APPLICATION_ID) {
            return
        }
        // ★ libxposed API 102：PackageReadyParam 无 processName 字段（仅 ModuleLoadedParam 有）。
        // 进程名从 ApplicationInfo.processName 解析：主进程 == packageName，:patch 等 == packageName + 后缀。
        val processName = param.applicationInfo?.processName ?: param.packageName
        AppLog.i("onPackageReady:", param.packageName, "process=", processName, "classLoader ready")

        if (param.packageName != BuildConfig.APPLICATION_ID) {
            // ★ 传入真实 processName（含 com.tencent.mm:patch），供 HookSession / 进程收敛使用
            initWeChatHooks(param.classLoader, processName)
        }
    }

    // ═══════════════════════════════════════════════════════
    // Hot Reload 生命周期
    // ═══════════════════════════════════════════════════════

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        AppLog.i("onHotReloading triggered — returning true to allow hot reload")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // ★ libxposed API 102：HotReloadedParam extends ModuleLoadedParam，含 getProcessName()。
        AppLog.i("onHotReloaded: process=${param.processName}")

        // 核心：热重载后，用相同 ID 创建新 Hook 即可原子替换旧 Hook
        hookHandles.clear()

        // ★ 清理全局缓存确保适配器重新过滤
        top.mmjz.floatingclouds.plugin.part.HideMainUIListPluginPart.tempUnhideMainConv = false
        top.mmjz.floatingclouds.plugin.part.HideMainUIListPluginPart.hasEnteredMaskChatting = false
    }

    // ═══════════════════════════════════════════════════════
    // Hook Handle 注册
    // ═══════════════════════════════════════════════════════

    internal fun registerHookHandle(id: String, handle: XposedInterface.HookHandle) {
        hookHandles[id] = handle
    }

    // ═══════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════

    /**
     * 为 WeChat 进程初始化所有插件 Hook。
     *
     * @param processName 真实进程名（如 `com.tencent.mm` 主进程 / `com.tencent.mm:patch` 补丁进程），
     *                     供 [HookSession] 与 Tinker 拦截层按进程收敛使用。
     */
    private fun initWeChatHooks(classLoader: ClassLoader, processName: String) {
        AppLog.i("initWeChatHooks start process=$processName")

        // ★ P0: 设置全局 classLoader，确保 ClazzN.from() 无参调用正常工作
        ClazzN.defaultClassLoader = classLoader

        try {
            val session = HookSession(classLoader, processName) { executable ->
                hook(executable)
            }

            // ★★★ P0 决定性修复：在 `Application.attach` 的 `chain.proceed()` **之前**同步安装
            // Tinker 拦截层并提前清理补丁文件。
            // 微信 Tinker 补丁在 proceed 期间的 attachBaseContext → onBaseContextAttached →
            // TinkerLoader.tryLoad 内加载；若注册晚于 proceed，首次 tryLoad 永远早于 L2 → 结构性失效。
            // 故拦截层必须在 proceed 之前就位（详见 docs/system_design.md CRITICAL-1）。
            top.mmjz.floatingclouds.hotupdate.TinkerHotUpdateBlocker.install(session)
            top.mmjz.floatingclouds.hotupdate.TinkerHotUpdateBlocker.cleanupPatchFiles()

            // ★ 关键：捕获 WeChat Application Context，初始化 AppContext
            // 同时在此回调内初始化所有插件（确保 AppContext 已就绪）
            session.findAndHook("android.app.Application", "attach",
                android.content.Context::class.java
            ) { chain ->
                val result = chain.proceed()
                val context = chain.args[0] as? android.content.Context
                if (context != null) {
                    // ★ 直接使用微信进程自身的 Context
                    // 不能使用 createPackageContext，因为微信进程 UID 与模块 UID 不同，
                    // 无法写入模块数据目录，导致 SP 永远为空（之前所有"配置重置"的根因）
                    // SP 文件落在: /data/data/com.tencent.mm/shared_prefs/mask_wechat_config.xml
                    top.mmjz.floatingclouds.util.AppContext.context = context
                    top.mmjz.floatingclouds.util.FileConfigStore.init(context)
                    AppLog.i("AppContext attached, using WeChat context: ${context.packageName}")

                    // ★★★ 初始化 DexKitCache 统一缓存层 ★★★
                    top.mmjz.floatingclouds.util.DexKitCache.init(context)
                    top.mmjz.floatingclouds.util.FeatureDiagnostics.reportDexKitCache(
                        selfKeys = context.getSharedPreferences("dexkit_scan_cache", android.content.Context.MODE_PRIVATE).all.size,
                        inkKeys = context.getSharedPreferences("hook_point_cache", android.content.Context.MODE_PRIVATE).all.size,
                        isReady = top.mmjz.floatingclouds.util.DexKitCache.isReady()
                    )

                    // ★★★ WXDbPlugin 必须同步注册：EnMicroMsg.db 在 chain.proceed() 期间
                    // 就被微信打开，若等到 Handler.post 才注册 openDatabase hook 会错过 →
                    // contactCache 永远为空 → 配置名单无法反查备注。
                    // WXDbPlugin.handleHook() 本身极轻量（仅注册 hook，无重查询），
                    // 不会影响启动热窗口；真正的重活（loadAllContacts）在 hook 回调内执行。
                    try {
                        val dbPlugin = WXDbPlugin()
                        dbPlugin.handleHook(session)
                        PluginProviders.register("WXDbPlugin", dbPlugin)
                        AppLog.i("WXDbPlugin registered early (before delayed init)")
                    } catch (t: Throwable) {
                        AppLog.e("WXDbPlugin early register failed", t)
                    }

                    // ★ 延迟初始化其余插件：参照 QAuxiliary/WAuxiliary 成熟做法，把重量级初始化
                    // （getOptionData 长 JSON + handleHook 反射加载微信类）从 Application.attach
                    // 的 proceed 之后移出，避免压在微信启动最脆弱的热窗口（Tinker 加载/多进程初始化），
                    // 从而规避"进程在启动路径被回收 → 后续 hook 全未注册 → 功能失效"的根因。
                    // Tinker 拦截层已在 proceed 之前就位（见上方 P0），此处仅延迟插件注册。
                    // post 到主线程消息队列尾部：此时 attach proceed 已返回、微信继续走 onCreate，
                    // 启动热窗口已过；且所有 plugin 的 handleHook 均 hook Activity 级别方法，
                    // 晚于首个 Activity 执行不会错过任何回调（GlobalLifecycleHook 自身即 hook 全部
                    // Activity 的 onCreate/onResume，而非特定首个 Activity）。
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            initPlugins(session)
                        } catch (t: Throwable) {
                            AppLog.e("initPlugins (delayed) failed", t)
                        }
                    }
                }
                result
            }
        } catch (t: Throwable) {
            AppLog.e("initWeChatHooks failed", t)
        }

        AppLog.i("initWeChatHooks done")
    }

    private fun initPlugins(session: HookSession) {
        // ★ 注意：以下诊断打印（LocalKVUtil.dumpSpPath / ConfigUtil.dumpSpContent /
        // AppLog.i("Floatingclouds_Init", ...)）已在排查期确认：during 微信启动热窗口做整段
        // SP dump 会增加被杀风险且日志价值有限。故移除启动路径上的 dump 调用，仅保留
        // 必要的 AppLog 通道日志（无不可见标签）。
        // 如需调试 SP 内容，改走 AppLog 或 TestLog（落文件），或手动从 MaskManagerCenterUI 触发。

        val migrated = top.mmjz.floatingclouds.util.ConfigUtil.tryMigrateFromLegacyPackages()
        if (migrated) {
            AppLog.i("Legacy config migrated")
        }

        top.mmjz.floatingclouds.util.ConfigUtil.invalidateCache()

        // ★ 远程停运开关：后台线程启动，30 分钟轮询，不阻塞初始化
        top.mmjz.floatingclouds.plugin.RemoteKillSwitch.startMonitoring()

        val opt = top.mmjz.floatingclouds.util.ConfigUtil.getOptionData()
        AppLog.i(
            "Loaded: master=${opt.masterEnabled} hideConv=${opt.hideMainConvList} " +
            "blockChat=${opt.blockEnterChat} hideContact=${opt.hideContactList} " +
            "hideSns=${opt.hideSnsEntry}")

        // ★ WXDbPlugin 已在 Application.attach 回调中提前同步注册（确保 EnMicroMsg 不丢失）
        listOf(
            CommonPlugin(), WXMaskPlugin()
        ).forEachIndexed { index, plugin ->
            try {
                PluginProviders.register(plugin.javaClass.simpleName, plugin)
                AppLog.i("init plugin [${index}]: ${plugin.javaClass.simpleName}")
                plugin.handleHook(session)
            } catch (t: Throwable) {
                AppLog.e("init plugin failed: ${plugin.javaClass.simpleName}", t)
            }
        }

        // ★ 启动后打印一键健康报告
        top.mmjz.floatingclouds.util.FeatureDiagnostics.healthCheck(
            top.mmjz.floatingclouds.util.AppContext.context!!,
            session.classLoader
        )
    }
}
