package top.mmjz.floatingclouds.plugin

import top.mmjz.floatingclouds.ObfuscatedClassResolver
import android.os.Bundle
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.bean.MaskItemBean
import top.mmjz.floatingclouds.plugin.part.ConversationHideHelper
import top.mmjz.floatingclouds.plugin.part.BlockContactInfoPluginPart
import top.mmjz.floatingclouds.plugin.part.EmptySingChatHistoryGalleryPluginPart
import top.mmjz.floatingclouds.plugin.part.EnterChattingUIPluginPart
import top.mmjz.floatingclouds.plugin.part.HideContactListPluginPart
import top.mmjz.floatingclouds.plugin.part.HideMainUIListPluginPart
import top.mmjz.floatingclouds.plugin.part.HideOwnSnsPluginPart
import top.mmjz.floatingclouds.plugin.part.HideRecentForwardPluginPart
import top.mmjz.floatingclouds.plugin.part.HideSearchListUIPluginPart
import top.mmjz.floatingclouds.plugin.part.HideSnsEntryPluginPart
import top.mmjz.floatingclouds.plugin.part.HideStorageChatRecordPluginPart
import top.mmjz.floatingclouds.plugin.part.IgnoreVoipCallPluginPart
import top.mmjz.floatingclouds.plugin.part.LongClickTracePluginPart
import top.mmjz.floatingclouds.plugin.part.LongPressAddMaskPluginPart
import top.mmjz.floatingclouds.plugin.part.ConvAddMaskPluginPart
import top.mmjz.floatingclouds.plugin.part.ContactAddMaskPluginPart
import top.mmjz.floatingclouds.plugin.part.BlockHotUpdatePluginPart
import top.mmjz.floatingclouds.plugin.part.HideSnsInteractionPluginPart
import top.mmjz.floatingclouds.plugin.part.HideSnsGroupIconPluginPart
import top.mmjz.floatingclouds.plugin.part.HideContactLabelPluginPart
import top.mmjz.floatingclouds.plugin.part.RuntimeClassResolver
import top.mmjz.floatingclouds.plugin.part.SearchCommandPluginPart
import top.mmjz.floatingclouds.plugin.part.GlobalLifecycleHook
import top.mmjz.floatingclouds.plugin.part.MaskUIManagerPluginPart
import top.mmjz.floatingclouds.plugin.part.MaskedMsgVibratePluginPart
import top.mmjz.floatingclouds.plugin.part.DiagSnsLabelPluginPart
import top.mmjz.floatingclouds.util.AppContext
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.ConfigUtil.ConfigSetObserver
import top.mmjz.floatingclouds.util.DexKitCache
import top.mmjz.floatingclouds.util.DexKitScanner
import top.mmjz.floatingclouds.util.FeatureDiagnostics
import top.mmjz.floatingclouds.util.FileLog
import top.mmjz.floatingclouds.util.StealthLog
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class WXMaskPlugin : IPlugin, ConfigSetObserver {
    var maskIdList = ArrayList<String?>()
    val maskListMap: LinkedHashMap<String?, MaskItemBean> = LinkedHashMap()

    val hideSearchListPluginPart = HideSearchListUIPluginPart()
    private val enterChattingUIPluginPart = EnterChattingUIPluginPart()
    private val hideMainUIListPluginPart = HideMainUIListPluginPart()
    private val hideContactListPluginPart = HideContactListPluginPart()
    private val blockContactInfoPluginPart = BlockContactInfoPluginPart()
    private val emptySingChatHistoryGalleryPluginPart = EmptySingChatHistoryGalleryPluginPart()
    private val ignoreVoipCallPluginPart = IgnoreVoipCallPluginPart()
    private val hideStorageChatRecordPluginPart = HideStorageChatRecordPluginPart()
    private val hideSnsEntryPluginPart = HideSnsEntryPluginPart()
    private val hideRecentForwardPluginPart = HideRecentForwardPluginPart()
    private val hideOwnSnsPluginPart = HideOwnSnsPluginPart()
    // ★ T1：长按事件入口诊断（专家方案 A），独立 Part，先抓真实长按路径
    private val longClickTracePluginPart = LongClickTracePluginPart()
    private val hideSnsInteractionPluginPart = HideSnsInteractionPluginPart()
    private val hideSnsGroupIconPluginPart = HideSnsGroupIconPluginPart()
    private val hideContactLabelPluginPart = HideContactLabelPluginPart()
    private val globalLifecycleHook = GlobalLifecycleHook()
    private val longPressAddMaskPluginPart = LongPressAddMaskPluginPart()
    private val convAddMaskPluginPart = ConvAddMaskPluginPart()
    private val contactAddMaskPluginPart = ContactAddMaskPluginPart()
    private val blockHotUpdatePluginPart = BlockHotUpdatePluginPart()
    private val maskUIManagerPluginPart = MaskUIManagerPluginPart()
    private val maskedMsgVibratePluginPart = MaskedMsgVibratePluginPart()
    private val diagSnsLabelPluginPart = DiagSnsLabelPluginPart()
    private val searchCommandPluginPart = SearchCommandPluginPart()

    companion object {
        @Volatile
        var classLoader: ClassLoader? = null
            private set

        // ★ T0：Part 初始化看门狗线程池。每个 Part 在独立守护线程执行，
        // 单点挂起/异常只影响自身，绝不拖垮整条 init 链（含 5 个 SNS Part）。
        private val partInitExecutor = Executors.newCachedThreadPool { r ->
            val t = Thread(r, "WXMaskPartInit")
            t.isDaemon = true
            t
        }

        // ★ T0：单 Part 初始化超时（毫秒）。超过即跳过该 Part 并继续下一个。
        private const val INIT_PART_TIMEOUT_MS = 8000L

        fun containChatUser(chatUser: String?): Boolean {
            if (chatUser.isNullOrBlank()) {
                StealthLog.w("chatUser is null or blank")
                return false
            }
            return ConfigUtil.getMaskList().any { it.maskId == chatUser }
        }

        fun getMaskBeamById(id: String): MaskItemBean? {
            return ConfigUtil.getMaskList().find { it.maskId == id }
        }

        fun setConversationMute(wxid: String, mute: Boolean, cl: ClassLoader) {
        runCatching {
            // 与 hide 同源：storage/helper 必须成对解析，复用 resolveConvStoragePair
            // （8.0.74 实测：gm0.j1.s(接口 vg3.x3) → h2 → getter(Ai/Bi) → k4 → z3）。
            // 定位器调用改用 invokeStorageLocator，兼容 s(Class)/s(Object)/s(StorageType) 三种签名。
            val pair = ObfuscatedClassResolver.resolveConvStoragePair(cl) ?: return
            val h2 = ObfuscatedClassResolver.invokeStorageLocator(pair.helper, pair.storageInterface) ?: return
            val dh = AppReflect.callMethod(h2, pair.convGetter) ?: return  // 8.0.74 = Ai/Bi，旧版 = Tg
            val conv = AppReflect.callMethod(dh, "n", wxid, true) ?: return
            val isMuted = AppReflect.callMethod(conv, "m2") as? Boolean ?: return
            if (isMuted == mute) return
            AppReflect.callMethod(conv, "D2", if (mute) 1 else 0)
            AppReflect.callMethod(dh, "p0", wxid, conv)
            }.onFailure {
                android.util.Log.e("WXMaskPlugin", "setConversationMute failed for $wxid mute=$mute", it)
            }
        }

        /**
         * 利用微信原生「不显示该聊天」机制隐藏/显示会话。
         * 状态写入微信 DB，模块重启后依然生效。
         */
        fun setConversationHidden(wxid: String, hidden: Boolean, cl: ClassLoader): Boolean {
            ConversationHideHelper.ensureInitialized(cl)
            return ConversationHideHelper.setHidden(wxid, hidden, cl)
        }

        /**
         * 检查会话是否处于隐藏状态。
         */
        fun isConversationHidden(wxid: String, cl: ClassLoader): Boolean? {
            return ConversationHideHelper.isHidden(wxid, cl)
        }

        /**
         * 回读会话免打扰状态。
         * 复用 setConversationMute 同套解析（storage/helper 成对 + convGetter + n(wxid,true) + m2 字段）。
         * @return true=已免打扰, false=未免打扰, null=无法判定（helper 未就绪/解析失败）。
         */
        fun isConversationMuted(wxid: String, cl: ClassLoader): Boolean? {
            return runCatching {
                val pair = ObfuscatedClassResolver.resolveConvStoragePair(cl) ?: return null
                val h2 = ObfuscatedClassResolver.invokeStorageLocator(pair.helper, pair.storageInterface) ?: return null
                val dh = AppReflect.callMethod(h2, pair.convGetter) ?: return null
                val conv = AppReflect.callMethod(dh, "n", wxid, true) ?: return null
                AppReflect.callMethod(conv, "m2") as? Boolean
            }.getOrNull()
        }
    }

    private fun loadConfigData() {
        maskIdList.clear()
        maskListMap.clear()
        ConfigUtil.getMaskList().forEach {
            maskListMap[it.maskId] = it
            maskIdList.add(it.maskId)
        }
    }

    override fun onCreate() {
        ConfigUtil.registerConfigSetObserver(this)
    }

    override fun onConfigChange() {
        loadConfigData()
    }

    override fun handleHook(session: HookSession) {
        classLoader = session.classLoader

        // ★★★ 最早重载配置：绕过 SP 缓存直接读 XML，确保所有 Part 读到的都是最新值 ★★★
        // Android 14+ MODE_MULTI_PROCESS 失效，SP 内存缓存永不刷新。
        // 必须在 loadConfigData() / 关键 Part init 之前调用，否则它们会读到旧配置。
        ConfigUtil.reloadConfigFromDisk()
        StealthLog.i("WXMaskPlugin: ConfigUtil reloaded from disk (early, before any part init)")

        // ★ NEW-9：`:patch` 进程仅安装诊断 Part（BlockHotUpdatePluginPart.diagnose），
        // 跳过其余 16 个屏蔽/UI Part，避免整套 Hook 图 + DexKit 扫描 + 诊断在 patch 进程冗余注入。
        // 主进程的 Tinker 拦截层（L0–L3）与 patch 进程的 L4 已由 TinkerHotUpdateBlocker 在
        // onPackageReady 同步注册（早于 attach proceed），此处无需重复。
        if (session.processName.endsWith(":patch")) {
            StealthLog.i("WXMaskPlugin: :patch process -> diagnostic-only (skip masking/UI parts)")
            runCatching { blockHotUpdatePluginPart.handleHook(session) }
                .onFailure { StealthLog.e("WXMaskPlugin: :patch diagnostic FAILED", it) }
            return
        }

        loadConfigData()
        val startTime = System.currentTimeMillis()

        // ★ 诊断：使用 DexKitCache 中缓存的类名，report 到诊断系统
        try {
            val contactCache = top.mmjz.floatingclouds.util.DexKitCache.getContactClasses()
            FeatureDiagnostics.reportDexKitScan("contact_classes", if (contactCache != null) "CACHED" else "SKIP",
                "count=${contactCache?.classNames?.size ?: 0}")
            val searchCache = top.mmjz.floatingclouds.util.DexKitCache.getSearchCommand()
            FeatureDiagnostics.reportDexKitScan("search_classes", if (searchCache != null) "CACHED" else "SKIP",
                "count=${searchCache?.searchViewClassNames?.size ?: 0}")
            val fwdCache = top.mmjz.floatingclouds.util.DexKitCache.getRecentForward()
            FeatureDiagnostics.reportDexKitScan("forward_classes", if (fwdCache != null) "CACHED" else "SKIP",
                "count=${fwdCache?.activityClassNames?.size ?: 0}")
            val arCache = top.mmjz.floatingclouds.util.DexKitCache.getAntiRevoke()
            FeatureDiagnostics.reportDexKitScan("anti_revoke", if (arCache != null) "CACHED" else "SKIP",
                "cls=${arCache?.className ?: "none"}")
        } catch (_: Exception) {}

        RuntimeClassResolver.init(null, session.classLoader, null)
        // ★ RCR 诊断上报
        FeatureDiagnostics.reportRCR(
            isReady = RuntimeClassResolver.isReady,
            cachedCount = 0, fallbackCount = 0, totalCount = 0, diagInfo = null
        )

        FileLog.init()
        FileLog.i("WXMaskPlugin", "handleHook started")

        // ★ 重构：前5个关键Part同步初始化（必须在主线程尽早运行），其余异步
        // 关键Part：GlobalLifecycleHook, MaskUIManager, HideMainUIList, ConvAddMask, ContactAddMask
        //   + longClickTracePluginPart：hook AdapterView.setOnItemLongClickListener（framework 类），
        //     与 hideMainUIList 同类，必须同步初始化。原在 asyncParts 因 Handler.post 主线程不可靠
        //     （见下注释）导致 handleHook 永不执行、长按 hook 从未安装 → 不弹框。
        //   + hideOwnSnsPluginPart：长按「加入隐藏」后依赖它做即时刷新，且自身 hook 也需可靠安装。
        val criticalParts = listOf<Pair<String, IPlugin>>(
            "globalLifecycleHook" to globalLifecycleHook,
            "maskUIManagerPluginPart" to maskUIManagerPluginPart,
            "maskedMsgVibratePluginPart" to maskedMsgVibratePluginPart,
            "hideMainUIListPluginPart" to hideMainUIListPluginPart,
            "convAddMaskPluginPart" to convAddMaskPluginPart,
            "contactAddMaskPluginPart" to contactAddMaskPluginPart,
            "longClickTracePluginPart" to longClickTracePluginPart,
            "hideOwnSnsPluginPart" to hideOwnSnsPluginPart,
        )
        val asyncParts = listOf<Pair<String, IPlugin>>(
            "hideContactListPluginPart" to hideContactListPluginPart,
            "blockContactInfoPluginPart" to blockContactInfoPluginPart,
            "enterChattingUIPluginPart" to enterChattingUIPluginPart,
            "emptySingChatHistoryGalleryPluginPart" to emptySingChatHistoryGalleryPluginPart,
            "hideStorageChatRecordPluginPart" to hideStorageChatRecordPluginPart,
            "hideSnsEntryPluginPart" to hideSnsEntryPluginPart,
            // ★ v3.0.129: hideRecentForward 移出 asyncParts，改用 launchHideRecentForwardSeparately
            // 独立线程可靠初始化（asyncParts 的 Handler.post 在主进程冷启动时不可靠）。
            "hideSnsInteractionPluginPart" to hideSnsInteractionPluginPart,
            "hideSnsGroupIconPluginPart" to hideSnsGroupIconPluginPart,
            // ★ T1：长按事件入口诊断，独立 Part，不动 #4 现有逻辑（已移至 criticalParts 同步）
            "hideContactLabelPluginPart" to hideContactLabelPluginPart,
            "diagSnsLabelPluginPart" to diagSnsLabelPluginPart,
            // ★ P0: 注册「屏蔽微信热更新」Part，使其 handleHook 在模块加载时被调用。
            // 该 Part 内部已通过 isActive() 按 opt.blockHotUpdate 开关门控，无需在外部再加门控。
            "blockHotUpdatePluginPart" to blockHotUpdatePluginPart,
            "searchCommandPluginPart" to searchCommandPluginPart
        )

        // 同步初始化关键Part
        initPartsSync(session, criticalParts)

        // ★ T0：ignoreVoipCall 解耦出顺序 init 链，改为独立线程异步初始化，
        // 其 handleHook 内部任何挂起/异常只影响自身，不会阻塞主 init 链（含 5 个 SNS Part）。
        launchIgnoreVoipCallSeparately(session)
        // ★ v3.0.129 修复：hideRecentForward 之前既在 asyncParts 又注释了独立线程启动，
        // 而 asyncParts 走 Handler(mainLooper).post，主进程冷启动时该 post 常被延迟/跳过 ->
        // 该 Part 从未初始化（DIAG 证实无 handleHook START）。改回独立线程可靠初始化。
        launchHideRecentForwardSeparately(session)

        // 异步初始化其余Part
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            initPartsSync(session, asyncParts)
            // 搜索模块最后初始化
            try {
                hideSearchListPluginPart.handleHook(session)
            } catch (t: Throwable) {
                StealthLog.e("WXMaskPlugin: handleHook -> hideSearchListPluginPart FAIL", t)
            }
            // ★ 上报Parts初始化完成
            FeatureDiagnostics.reportPartsInitDone()

            // ★★★ T0：绕过 SP 缓存，直接从 XML 文件重载配置 ★★★
            // Android 14+ MODE_MULTI_PROCESS 失效，SP 内存缓存永不刷新。
            // 绕过 sp.getString()，直接读 XML 文件获取 UI 进程写入的最新配置。
            ConfigUtil.reloadConfigFromDisk()
            StealthLog.i("WXMaskPlugin: ConfigUtil reloaded from disk after async init")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ConfigUtil.reloadConfigFromDisk()
                StealthLog.d("WXMaskPlugin: ConfigUtil reloaded from disk (delayed 2s)")
            }, 2000L)
        }

        val elapsed = System.currentTimeMillis() - startTime
        StealthLog.i("WXMaskPlugin: critical parts done in ${elapsed}ms, async parts scheduled")

        // ★ T5.8: 后台自动 DexKit 扫描（不阻塞 Hook 注册；版本变更/缓存缺失时触发）
        runCatching {
            val ctx = AppContext.context
            if (ctx != null) {
                val vc = runCatching { ctx.packageManager.getPackageInfo("com.tencent.mm", 0).versionCode }.getOrDefault(0)
                val apkPath = runCatching { ctx.packageManager.getApplicationInfo("com.tencent.mm", 0).sourceDir }.getOrNull()
                if (apkPath != null && vc != 0) {
                    val scanner = DexKitScanner(ctx, apkPath)
                    if (scanner.getLastVersionCode() != vc) {
                        Thread {
                            runCatching {
                                if (scanner.scanAll { _, _ -> }) {
                                    DexKitCache.init(ctx) // 刷新内存缓存，使本次扫描的新键立即生效
                                    StealthLog.i("WXMaskPlugin: 后台 DexKit 扫描完成，缓存已刷新")
                                }
                            }
                        }.start()
                    }
                }
            }
        }.onFailure { StealthLog.e("WXMaskPlugin: 自动扫描触发失败", it) }

        // 拦截扫码登录确认弹窗
        runCatching {
            AppReflect.findClassIfExists(
                "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI",
                session.classLoader
            ) ?: return@runCatching
            session.findAndHook(
                "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI",
                "onCreate",
                android.os.Bundle::class.java
            ) { chain ->
                val result = chain.proceed()
                if (!ConfigUtil.getOptionData().blockScanLogin) return@findAndHook result
                (chain.thisObject as? android.app.Activity)?.let { runCatching { it.finish() } }
                StealthLog.i("WXMaskPlugin: blocked scan login dialog")
                result
            }
        }.onFailure {
            StealthLog.i("WXMaskPlugin: hook ExtDeviceWXLoginUI fail", it)
        }
    }

    /**
     * 同步初始化一组 Part，并对每个 Part 设超时看门狗。
     * 每个 Part 在独立工作线程执行，主线程最多等待 [INIT_PART_TIMEOUT_MS]；
     * 超时/异常只跳过该 Part 并继续下一个，绝不因单点挂起拖垮整条 init 链。
     */
    private fun initPartsSync(session: HookSession, parts: List<Pair<String, IPlugin>>) {
        for ((name, part) in parts) {
            val t0 = System.currentTimeMillis()
            StealthLog.i("WXMaskPlugin: handleHook -> $name START")
            val future = partInitExecutor.submit<Unit> { part.handleHook(session) }
            try {
                future.get(INIT_PART_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val dur = System.currentTimeMillis() - t0
                StealthLog.i("WXMaskPlugin: handleHook -> $name DONE (${dur}ms)")
                FeatureDiagnostics.reportPartInit(name, "OK", dur, null)
            } catch (te: TimeoutException) {
                val dur = System.currentTimeMillis() - t0
                StealthLog.e("WXMaskPlugin: handleHook -> $name TIMEOUT(${dur}ms) skip & continue (watchdog)")
                FeatureDiagnostics.reportPartInit(name, "TIMEOUT", dur, "init timeout ${INIT_PART_TIMEOUT_MS}ms")
                // 不 cancel future：让其后台线程自行结束，避免打断其它已安装的 hook
            } catch (ee: ExecutionException) {
                val dur = System.currentTimeMillis() - t0
                val cause = ee.cause ?: ee
                StealthLog.e("WXMaskPlugin: handleHook -> $name FAIL (${dur}ms)", cause)
                FeatureDiagnostics.reportPartInit(name, "FAIL", dur, cause.message)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                val dur = System.currentTimeMillis() - t0
                StealthLog.e("WXMaskPlugin: handleHook -> $name INTERRUPTED (${dur}ms)")
                FeatureDiagnostics.reportPartInit(name, "INTERRUPTED", dur, null)
            }
        }
    }

    /**
     * ★ T0：将 ignoreVoipCall 从顺序 init 链解耦，独立线程异步初始化。
     * 其 handleHook 内部任何挂起/异常只影响自身，不会阻塞主 init 链（含 5 个 SNS Part）。
     */
    private fun launchIgnoreVoipCallSeparately(session: HookSession) {
        partInitExecutor.submit<Unit> {
            StealthLog.i("WXMaskPlugin: handleHook -> ignoreVoipCallPluginPart START (separate thread)")
            val t0 = System.currentTimeMillis()
            runCatching { ignoreVoipCallPluginPart.handleHook(session) }
                .onSuccess {
                    val dur = System.currentTimeMillis() - t0
                    StealthLog.i("WXMaskPlugin: handleHook -> ignoreVoipCallPluginPart DONE (${dur}ms)")
                    FeatureDiagnostics.reportPartInit("ignoreVoipCallPluginPart", "OK", dur, null)
                }
                .onFailure {
                    val dur = System.currentTimeMillis() - t0
                    StealthLog.e("WXMaskPlugin: handleHook -> ignoreVoipCallPluginPart FAIL (separate thread) (${dur}ms)", it)
                    FeatureDiagnostics.reportPartInit("ignoreVoipCallPluginPart", "FAIL", dur, it.message)
                }
        }
    }

    /**
     * ★ T0：将 hideRecentForward 从顺序 init 链解耦，独立线程异步初始化。
     * 其 handleHook 内部 hook ListView.setAdapter/onLayout/dispatchDraw（framework 类）
     * 在微信进程早期可能触发 ART 类验证/JIT 死锁，解耦后挂起只影响自身。
     */
    private fun launchHideRecentForwardSeparately(session: HookSession) {
        partInitExecutor.submit<Unit> {
            StealthLog.i("WXMaskPlugin: handleHook -> hideRecentForwardPluginPart START (separate thread)")
            val t0 = System.currentTimeMillis()
            runCatching { hideRecentForwardPluginPart.handleHook(session) }
                .onSuccess {
                    val dur = System.currentTimeMillis() - t0
                    StealthLog.i("WXMaskPlugin: handleHook -> hideRecentForwardPluginPart DONE (${dur}ms)")
                    FeatureDiagnostics.reportPartInit("hideRecentForwardPluginPart", "OK", dur, null)
                }
                .onFailure {
                    val dur = System.currentTimeMillis() - t0
                    StealthLog.e("WXMaskPlugin: handleHook -> hideRecentForwardPluginPart FAIL (separate thread) (${dur}ms)", it)
                    FeatureDiagnostics.reportPartInit("hideRecentForwardPluginPart", "FAIL", dur, it.message)
                }
        }
    }

}
