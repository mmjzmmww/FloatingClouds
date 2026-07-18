package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.ClazzN
import top.mmjz.floatingclouds.Constrant
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.plugin.part.HideContactListPluginPart
import top.mmjz.floatingclouds.util.AppLog
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.AppVersionUtil
import top.mmjz.floatingclouds.util.ChildDeepCheck
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.FileLog
import top.mmjz.floatingclouds.util.DexKitCache
import top.mmjz.floatingclouds.util.QuickCountClickListenerUtil
import top.mmjz.floatingclouds.util.StealthLog
import top.mmjz.floatingclouds.util.ext.getViewId
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 主页UI（即微信底部"微信"Tab选中时所在页面）处理，消息、小红点相关逻辑
 *
 * API 102 重构版：全部 60+ Hook 点改用 HookSession 拦截器链模型
 * - hook(executable).intercept { chain -> ... } 替代 XC_MethodHook before/after 回调
 * - AppReflect 替代 XposedHelpers2 的反射调用
 * - ChildDeepCheck 替代私有库 view 遍历工具
 * - getViewId 扩展替代 ResUtil
 */
class HideMainUIListPluginPart : IPlugin {
    @Volatile private var isFiltering = false

    // ★ 动画冷冻标志：返回动画期间冻结所有内容擦除，动画完成后收网
    private val isInBackAnimation = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        @Volatile var tempUnhideMainConv = false
        @Volatile var hasEnteredMaskChatting = false
        /** 单例引用，供搜索指令等外部 Part 触发临时解除 */
        var instance: HideMainUIListPluginPart? = null

        /** 设置 UI 调用：统一设置/解除所有密友的"不显示该聊天"原生状态（独立于总开关）。 */
        fun setNativeHideForAll(hide: Boolean) {
            instance?.apply {
                applyNativeHideForAll(hide)
                Handler(Looper.getMainLooper()).postDelayed({
                    currentRootView?.let { rv -> runCatching { findListViewAndNotifyNoCache(rv) } }
                }, 300)
            }
        }

        /** 搜索指令触发的临时解除：不依赖多击/长按开关，仅受总开关门控。 */
        fun triggerTempUnhideViaCommand() {
            instance?.apply {
                if (!ConfigUtil.isMasterEnabled()) return
                tempUnhideMainConv = true
                toggleMuteForAllMasked(false)
                toggleHideForAllMasked(false)
                cacheInvalid.clear()
                chatUserCache.clear()
                Handler(Looper.getMainLooper()).postDelayed({
                    StealthLog.i("HideMainUI: tempUnhide via command, refresh rootView=${currentRootView?.javaClass?.simpleName}")
                    currentRootView?.let { rv ->
                        runCatching { findListViewAndNotifyNoCache(rv) }
                    }
                    syncContactListTempUnhide()
                }, 300)
            }
        }

        /** 当密友列表变更时清除所有 adapter 缓存，保证过滤立即生效 */
        @Volatile var onMaskListChanged: (() -> Unit)? = null

        // ═══ 静态反射句柄缓存（空间换时间，避免 getDeclaredField/Method 高频调用） ═══
        /** 一次性缓存的 setText Method，避免 per-item 反射查找 */
        @Volatile private var cachedSetTextMethod: Method? = null
        /** 一次性缓存的 field_username Field，避免 per-item getDeclaredField */
        @Volatile private var cachedUsernameField: Field? = null
        /** 一次性缓存的 field_content Field */
        @Volatile private var cachedContentField: Field? = null
        /** 缓存 adapter.getItem(int) Method — 扫描回路最大瓶颈（273项×每次getDeclaredMethod） */
        @Volatile private var cachedGetItemMethod: Method? = null

        /**
         * 延迟初始化反射句柄：在第一次需要时通过 item 实例拿到 Class，之后永久复用。
         * @param sample 任意一个 WeChat 会话数据对象实例
         * @param classLoader WeChat 的 ClassLoader
         */
        fun initReflectCache(sample: Any, classLoader: ClassLoader) {
            if (cachedSetTextMethod != null) return  // 已初始化
            try {
                // setText(CharSequence) on TextView
                cachedSetTextMethod = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
                cachedSetTextMethod?.isAccessible = true
                // field_username → 沿继承链查找
                var clz: Class<*>? = sample.javaClass
                while (clz != null) {
                    try { cachedUsernameField = clz.getDeclaredField("field_username"); cachedUsernameField?.isAccessible = true; break }
                    catch (_: NoSuchFieldException) { clz = clz.superclass }
                }
                // field_content → 沿继承链查找
                clz = sample.javaClass
                while (clz != null) {
                    try { cachedContentField = clz.getDeclaredField("field_content"); cachedContentField?.isAccessible = true; break }
                    catch (_: NoSuchFieldException) { clz = clz.superclass }
                }
                // ★ 缓存 getItem(int) Method（扫描回路每项都要调，273个item = 273次反射查找 → 改为1次）
                try { cachedGetItemMethod = sample.javaClass.getMethod("getItem", Int::class.javaPrimitiveType); cachedGetItemMethod?.isAccessible = true }
                catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    @Volatile private var pendingRefreshNeeded = false

    private val CONV_ADAPTER_CLASSES_8070 = listOf(
        "com.tencent.mm.ui.conversation.p3",  // 8.0.70
        "va5.v0",                              // 8.0.70
        "com.tencent.mm.ui.k3",               // 8.0.70
        "com.tencent.mm.ui.contact.e",         // 8.0.70
        "com.tencent.mm.ui.conversation.adapter.MvvmConvList",  // 8.0.74 MVVM架构
        "sc3.x3",                              // 8.0.74 DEX确认: 会话隐藏
        "sc3.x",                               // 8.0.74 DEX确认: 会话隐藏
        "va5.a"                                // 8.0.74 DEX确认: 适配器
    )

    val GetItemMethodName = when (AppVersionUtil.getVersionCode()) {
        Constrant.WX_CODE_8_0_22 -> "aCW"
        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_43 -> "k"
        Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_56 -> "l"
        Constrant.WX_CODE_8_0_50 -> "n"
        Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_58 -> "m"
        Constrant.WX_CODE_8_0_70 -> "l"
        Constrant.WX_CODE_8_0_74 -> "l"  // 8.0.74 使用与8.0.70相同的方法名
        else -> "m"
    }

    private var currentRootView: View? = null
    private var cachedListViewRef: WeakReference<ListView>? = null
    @Volatile private var hasRegisteredMultiClick = false
    private var classLoader: ClassLoader? = null
    // ★ 动态 Hook 句柄：暂停时 Unhook 注销，Idle 时重新挂钩
    private val activeHooks = java.util.concurrent.CopyOnWriteArrayList<io.github.libxposed.api.XposedInterface.HookHandle>()
    // 标题栏多击/长按状态（直接在标题 View 上挂 listener，不拦截 dispatchTouchEvent）
    private var titleLastClickTime = 0L
    private var titleClickCounter = 0
    /** 标题"微信"TextView 弱引用，用于去重挂 listener（避免重复 setListener 叠加） */
    private var titleViewRef: WeakReference<View>? = null
    // 缓存 LauncherUI 类，供多个 hook 复用
    private var launcherUIClazz: Class<*>? = null
    /** 缓存 LauncherUI Activity 弱引用，供 getCurrentTab() 反射调用（判断当前底部选中 Tab） */
    private var launcherUIActivityRef: WeakReference<Activity>? = null

    // 标题长按自计时（替代 setOnLongClickListener：支持自定义时长 + 澎湃 OS3 CANCEL 容错）
    private var titleLongPressPending = false
    private var titleCancelPending = false
    @Volatile private var titleConsumedByLongPress = false
    private var titlePendingX = 0f
    private var titlePendingY = 0f
    private val titleLongPressHandler = Handler(Looper.getMainLooper())
    private val CANCEL_TOLERANCE_MS = 180L
    private val titleLongPressRunnable = Runnable {
        if (titleLongPressPending) {
            titleLongPressPending = false
            titleConsumedByLongPress = true
            if (titleCancelPending) {
                // CANCEL 来了但长按计时器在容错窗口内到期 → 容错放行
                titleCancelPending = false
                titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                AppLog.i("HideMainUI: long-press completed within CANCEL tolerance window")
            }
            AppLog.i("HideMainUI: long-press triggered (threshold=${ConfigUtil.getOptionData().longPressDuration}ms)")
            onLongPressUnhideTriggered()
        }
    }
    private val titleCancelToleranceRunnable = Runnable {
        if (titleCancelPending) {
            titleCancelPending = false
            cancelTitleLongPress()
        }
    }

    // 数据源过滤缓存
    private val filterCache = java.util.concurrent.ConcurrentHashMap<Any, IntArray>()
    private val cacheInvalid = java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
    private val cacheOriginalCount = java.util.concurrent.ConcurrentHashMap<Any, Int>()
    // ★ 记录每次扫描时的微信真实总数，用于对账 originalCount 是否已变化
    private val lastScannedTotal = java.util.concurrent.ConcurrentHashMap<Any, Int>()
    private val lastCacheInvalidTime = java.util.concurrent.ConcurrentHashMap<Any, Long>()
    private val lastRebuildTime = java.util.concurrent.ConcurrentHashMap<Any, Long>()
    private val hookedClasses = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>())
    private val isScanning = ThreadLocal<Boolean>()
    private val chatUserCache = java.util.IdentityHashMap<Any, String>()

    // ═══════════════════════════════════════════════════════
    // 入口
    // ═══════════════════════════════════════════════════════

    override fun handleHook(session: HookSession) {
        instance = this
        classLoader = session.classLoader
        // 注册密友列表变更回调，让 LongPressAddMask 添加密友后立即写入 DB
        onMaskListChanged = {
            cacheInvalid.clear()
            cacheOriginalCount.clear()
            lastScannedTotal.clear()
            lastRebuildTime.clear()
            chatUserCache.clear()
            // ★ 新增密友时立即写入 DB（hide + mute 双重兜底）
            //    确保冷启动窗口期内 Hook 未就绪时 DB 已有正确状态
            toggleMuteForAllMasked(true)
            toggleHideForAllMasked(true)
            // ★ 立即通知 adapter 刷新，不等下次布局
            cachedListViewRef?.get()?.let { lv ->
                if (lv.windowToken != null) {
                    notifyAdapter(lv.adapter)
                }
            }
        }
        StealthLog.i("=== HideMainUIListPluginPart handleHook START, wxVer=${AppVersionUtil.getSmartVersionName()} ===")

        // ★ 参照 InkHide: hook LauncherUI.onKeyDown 检测返回键
        // ★ 修复：直接 hook LauncherUI 自身（系统类 android.app.Activity.onKeyDown 不被 LauncherUI 触发）
        runCatching {
            val luOnKeyDown = AppReflect.findClassIfExists(ClazzN.LauncherUI, session.classLoader)
            val candidates = if (luOnKeyDown != null) listOf(luOnKeyDown.name, "android.app.Activity") else listOf("android.app.Activity")
            var hooked = false
            for (name in candidates) {
                val handle = session.findAndHook(name, "onKeyDown", Integer.TYPE, android.view.KeyEvent::class.java) { chain ->
                    val act = chain.thisObject as? Activity
                    val keyCode = (chain.args[0] as? Number)?.toInt() ?: 0
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && act != null && luOnKeyDown != null && luOnKeyDown.isInstance(act) && tempUnhideMainConv) {
                        val opt = ConfigUtil.getOptionData()
                        if (opt.rehideOnLeaveChat) {
                            StealthLog.i("onKeyDown BACK in LauncherUI: rehiding")
                            tempUnhideMainConv = false
                            toggleMuteForAllMasked(true)
                            toggleHideForAllMasked(true)
                            hasEnteredMaskChatting = false
                            pendingRefreshNeeded = true
                            syncContactListTempUnhide()
                        }
                    }
                    chain.proceed()
                }
                if (handle != null) { hooked = true; AppLog.i("[DIAG] onKeyDown hooked on $name"); break }
            }
            if (!hooked) AppLog.e("[DIAG] onKeyDown hook failed on all candidates")
        }.onFailure { AppLog.e("onKeyDown hook fail", it) }
        val hasMaskedUser = ConfigUtil.getMaskList().isNotEmpty()
        val option = ConfigUtil.getOptionData()

        // ★ 缓存 LauncherUI 类引用，供 onResume/onPause/onWindowFocusChanged 复用
        launcherUIClazz = AppReflect.findClassIfExists(ClazzN.LauncherUI, session.classLoader)

        // ★ 标题多击/长按：仅采用「标题 TextView 直接挂 listener」方案（对齐 InkHide 稳健思路）。
        //   不引入 Activity.dispatchTouchEvent 全 Activity 兜底——因为通讯录/发现页同属
        //   LauncherUI 实例，兜底矩形会跨页面命中，导致点击这些页顶部文字也触发临时解除。
        //   纯 listener 方案在微信页已实测可用，且监听挂在具体 View 上，Fragment 切换后视图
        //   被移除即自然失效，绝不会跨页面误触。

        // ★ 始终注册 ListView/RecyclerView Hook（内容清除与列表过滤独立）
        //   列表过滤仅在 hideMainConvList=true 时生效，内容清除始终对密友执行
        runCatching { hookListViewSetAdapter(session) }
            .onFailure { AppLog.e("hook ListView.setAdapter fail", it) }
        runCatching { handleMainUIChattingListView2(session) }
            .onFailure {
                AppLog.e("hide mainUI listview fail, try to old function.", it)
                handleMainUIChattingListView(session)
            }

        // 8.0.74: hook yf5.w.d() 数据源过滤（参考 InkHide C0240z.java）
        runCatching { hookConvDataSource(session) }
            .onFailure { StealthLog.e("hookConvDataSource fail", it) }

        // LauncherUI.onResume — 直接 hook LauncherUI 自身的 onResume
        // ★ 修复：原写法 hook 系统类 android.app.Activity.onResume 在微信上从不触发（LauncherUI 重写的
        //   onResume 不走该拦截点），导致 applyNativeHideForAll 永不执行、"不显示该聊天"失效。
        //   改为 hook LauncherUI 类自身，fallback 系统类；并打诊断日志确认触发。
        val launcherUIClass = AppReflect.findClassIfExists(ClazzN.LauncherUI, session.classLoader)
        AppLog.i("[DIAG-T2-1] LauncherUI class lookup: ${launcherUIClass?.name ?: "NULL"}")
        runCatching {
            val luClass = launcherUIClass
            val candidates = if (luClass != null) listOf(luClass.name, "android.app.Activity") else listOf("android.app.Activity")
            var hooked = false
            for (name in candidates) {
                val handle = session.findAndHook(name, "onResume") { chain ->
                    val result = chain.proceed()
                    if (luClass != null && !luClass.isInstance(chain.thisObject)) return@findAndHook result
                    val activity = chain.thisObject as? Activity ?: return@findAndHook result
                    launcherUIActivityRef = WeakReference(activity)
                    hasEnteredMaskChatting = false
                    val rootView = activity.window.decorView
                    currentRootView = rootView
                    // 8.0.74: 通过 yf5.w0.q() 在数据源头过滤，无需再扫描 View 树
                    val masterOk = ConfigUtil.isMasterEnabled()
                    val hideConv = ConfigUtil.getOptionData().hideMainConvList
                    val hideConversation = ConfigUtil.getOptionData().hideConversation
                    StealthLog.i("[HideMainUI] DEBUG onResume: masterEnabled=$masterOk hideMainConvList=$hideConv hideConversation=$hideConversation masks=${ConfigUtil.getMaskList().size}")
                    // ★ 原生"不显示该聊天"兜底：独立于总开关，按 hideConversation 开关强制对齐 DB 状态。
                    //   开启→把密友写入微信DB隐藏状态（持久化，关总开关/重启均生效）；关闭→解除隐藏让密友显示。
                    //   状态已一致时 applyNativeHideForAll 内部跳过写盘，无冗余开销。
                    applyNativeHideForAll(hideConversation)
                    if (pendingRefreshNeeded) {
                        findListViewAndNotify(rootView)
                        pendingRefreshNeeded = false
                    }
                    val optForMulti = ConfigUtil.getOptionData()
                    if (ConfigUtil.isMasterEnabled() && (optForMulti.enableMultiClickTempUnhide || optForMulti.enableLongPressTempUnhide)) {
                        attachTitleListeners(rootView)
                        // 布局可能尚未就绪，post 两次兜底（对齐 InkHide decorView.post 时机）
                        rootView.postDelayed({
                            if (rootView.windowToken != null) attachTitleListeners(rootView)
                        }, 500)
                        rootView.postDelayed({
                            if (rootView.windowToken != null) attachTitleListeners(rootView)
                        }, 1500)
                    }
                    // ★ 动画后收网：仅解冻，不做任何强制刷新（filterGetView 内分流自行对账还原）
                    if (isInBackAnimation.get()) {
                        Looper.myQueue().addIdleHandler {
                            isInBackAnimation.set(false)
                            hasEnteredMaskChatting = false
                            false
                        }
                    }
                    result
                }
                if (handle != null) { hooked = true; AppLog.i("[DIAG-T2-1] onResume hooked on $name"); break }
            }
            if (!hooked) AppLog.e("[DIAG-T2-1] onResume hook failed on all candidates")
        }.onFailure { AppLog.e("LauncherUI.onResume hook fail", it) }

        // Activity.onPause → only act when it's LauncherUI
        // ★ 修复：同 onResume，直接 hook LauncherUI 自身 onPause（系统类 hook 不被触发）
        runCatching {
            val luClass = launcherUIClass
            val candidates = if (luClass != null) listOf(luClass.name, "android.app.Activity") else listOf("android.app.Activity")
            var hooked = false
            for (name in candidates) {
                val handle = session.findAndHook(name, "onPause") { chain ->
                    val result = chain.proceed()
                    if (luClass != null && !luClass.isInstance(chain.thisObject)) return@findAndHook result
                    val wasTempUnhide = tempUnhideMainConv
                    StealthLog.i("[DIAG] onPause fired, tempUnhideMainConv=$wasTempUnhide")
                    isInBackAnimation.set(true)
                    hasEnteredMaskChatting = false
                    if (wasTempUnhide) {
                        tempUnhideMainConv = false
                        toggleMuteForAllMasked(true)
                        toggleHideForAllMasked(true)
                        pendingRefreshNeeded = true
                        isInBackAnimation.set(false)
                        // ★ 先同步隐藏可见子View（避免异步layout延迟）
                        hideMaskedConvViewsSync()
                        // ★ 再异步通知adapter刷新（确保后续layout也正确）
                            currentRootView?.let { rv ->
                            runCatching { findListViewAndNotify(rv) }
                                .onFailure { StealthLog.w("onPause immediate refresh failed", it) }
                        }
                        syncContactListTempUnhide()
                    }
                    hasRegisteredMultiClick = false
                    titleClickCounter = 0
                    titleLastClickTime = 0L
                    titleViewRef = null
                    cancelTitleLongPress()
                    currentRootView = null
                    result
                }
                if (handle != null) { hooked = true; AppLog.i("[DIAG] onPause hooked on $name"); break }
            }
            if (!hooked) AppLog.e("[DIAG] onPause hook failed on all candidates")
        }.onFailure { StealthLog.i("Activity.onPause hook fail", it) }

        // onWindowFocusChanged
        // ★ 修复：直接 hook LauncherUI 自身 onWindowFocusChanged（系统类 hook 不被触发）
        runCatching {
            val luClass = launcherUIClass
            val candidates = if (luClass != null) listOf(luClass.name, "android.app.Activity") else listOf("android.app.Activity")
            var hooked = false
            for (name in candidates) {
                val handle = session.findAndHook(name, "onWindowFocusChanged", Boolean::class.java) { chain ->
                    val result = chain.proceed()
                    val act = chain.thisObject as? Activity ?: return@findAndHook result
                    if (luClass != null && act.javaClass.name != luClass.name) return@findAndHook result
                    val hasFocus = chain.args[0] as Boolean
                    val opt = ConfigUtil.getOptionData()
                    if (!hasFocus && tempUnhideMainConv) {
                        // ★ 开关2：离开微信隐藏
                        if (opt.rehideOnLeaveApp) {
                            StealthLog.i("rehideOnLeaveApp: focus lost, rehiding")
                            tempUnhideMainConv = false
                            toggleMuteForAllMasked(true)
                            toggleHideForAllMasked(true)
                            hasEnteredMaskChatting = false
                            pendingRefreshNeeded = true
                            isInBackAnimation.set(false)
                            // ★ 修复：清除残留状态。必须放在 findListViewAndNotify 之后，否则它又会设回错误引用
                            titleClickCounter = 0
                            titleLastClickTime = 0L
                            currentRootView?.let { findListViewAndNotify(it); hideMaskedConvViewsSync() }
                            cachedListViewRef = null  // ★ 必须放在 findListViewAndNotify 之后！
                            syncContactListTempUnhide()
                        }
                    } else if (hasFocus && !tempUnhideMainConv) {
                        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getMaskList().isNotEmpty() || !ConfigUtil.getOptionData().hideMainConvList)
                            return@findAndHook result
                        StealthLog.i("window focus gained, refreshing ListView")
                        currentRootView?.let { rv ->
                            runCatching {
                                cachedListViewRef?.get()?.let { lv ->
                                    if (lv.windowToken != null) {
                                        if (System.currentTimeMillis() - (lastRebuildTime[lv.adapter] ?: 0L) < 500) return@let
                                        notifyAdapter(lv.adapter)
                                    } else cachedListViewRef = null
                                }
                            }
                        }
                    }
                    result
                }
                if (handle != null) { hooked = true; AppLog.i("[DIAG] onWindowFocusChanged hooked on $name"); break }
            }
            if (!hooked) AppLog.e("[DIAG] onWindowFocusChanged hook failed on all candidates")
        }.onFailure { StealthLog.i("hook onWindowFocusChanged fail", it) }

        // ChattingUIProxy.onEnterBegin 白名单
        runCatching {
            val chattingUIProxyClass = AppReflect.findClassIfExists("com.tencent.mm.ui.chatting.ChattingUIProxy", session.classLoader)
            if (chattingUIProxyClass != null) {
                val proxyClass = chattingUIProxyClass
                val method = AppReflect.findMethodExact(proxyClass, "onEnterBegin")
                if (method != null) {
                    session.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (!ConfigUtil.isMasterEnabled() || !tempUnhideMainConv) return@intercept result
                        var chatUser: String? = null
                        runCatching {
                            val bundle = chain.args.firstOrNull { it is Bundle } as? Bundle
                            chatUser = bundle?.getString("Chat_User")
                        }
                        if (chatUser == null) {
                            runCatching {
                                val fragmentClass = AppReflect.findClassIfExists(ClazzN.BaseChattingUIFragment, session.classLoader)
                                val fragmentField = AppReflect.findFirstFieldByExactType(proxyClass, fragmentClass!!)
                                fragmentField?.isAccessible = true
                                val fragment = fragmentField?.get(chain.thisObject)
                                val args = AppReflect.callMethod(fragment, "getArguments") as? Bundle
                                chatUser = args?.getString("Chat_User")
                            }.onFailure { StealthLog.i("get Chat_User from fragment field fail", it) }
                        }
                        handleWhitelistOnEnterChat(chatUser, "onEnterBegin")
                        result
                    }
                }
            }
        }.onFailure { StealthLog.i("ChattingUIProxy.onEnterBegin hook fail", it) }

        // BaseChattingUIFragment.onActivityCreated 兜底
        runCatching {
            val baseChattingClass = AppReflect.findClassIfExists(ClazzN.BaseChattingUIFragment, session.classLoader)
            if (baseChattingClass != null) {
                session.findAndHook(ClazzN.BaseChattingUIFragment, "onActivityCreated", Bundle::class.java) { chain ->
                    val result = chain.proceed()
                    if (!tempUnhideMainConv) return@findAndHook result
                    var chatUser: String? = null
                    runCatching {
                        val args = AppReflect.callMethod(chain.thisObject, "getArguments") as? Bundle
                        chatUser = args?.getString("Chat_User")
                    }
                    handleWhitelistOnEnterChat(chatUser, "onActivityCreated")
                    result
                }
            }
        }.onFailure { StealthLog.i("hook BaseChattingUIFragment.onActivityCreated fail", it) }

        // ★ 标题栏多击/长按触发采用「在标题 TextView 上直接挂 listener」方案（对齐 InkHide）。
        // 旧方案 A：hook Activity.dispatchTouchEvent + 全树搜 text=="微信"，在澎湃 OS 上失效：
        //   ① 标题 TextView 在屏幕最顶部，全面屏手势/导航栏会吞掉触摸事件，dispatchTouchEvent 收不到；
        //   ② 标题文本本地化/版本差异导致匹配不到"微信"；③ 每帧全树遍历且依赖布局时机。
        // 旧方案 B：dispatchTouchEvent 兜底 + 缓存标题矩形——会因通讯录/发现页同属 LauncherUI 实例，
        //   缓存矩形被这些页面复用命中，导致点击通讯录/发现页顶部文字也触发临时解除（跨页误触）。
        // 最终方案：仅在 LauncherUI.onResume 的 decorView.post 里定位标题 TextView，直接挂
        //   setOnLongClickListener（长按解除）+ setOnClickListener（多击计数解除）。
        //   标题 View 自身就是触摸目标，事件由系统正常派发，与系统手势/导航栏无关，不依赖文本匹配；
        //   监听挂在具体 View 上，Fragment 切换（切到通讯录/发现页）后旧标题 View 被移除即自然失效，
        //   绝不会跨页面误触——只让微信主页顶部"微信"二字生效。

        if (hasMaskedUser && ConfigUtil.getOptionData().hideMainConvList) {
            pendingRefreshNeeded = true
        }
    }

    // ═══════════════════════════════════════════════════════
    // 免打扰/隐藏会话/白名单逻辑
    // ═══════════════════════════════════════════════════════

    private val muteHandler by lazy {
        val thread = android.os.HandlerThread("MaskMuteThread")
        thread.start()
        Handler(thread.looper)
    }

    private fun toggleMuteForAllMasked(mute: Boolean) {
        muteHandler.post {
            // 重新静音(hide=true 方向)仅在不显示该聊天开关开启时生效；临时解除(mute=false)始终执行
            if (mute && !ConfigUtil.getOptionData().hideConversation) return@post
            val cl = classLoader ?: return@post
            for (wxid in ConfigUtil.getMaskList().map { it.maskId }) {
                WXMaskPlugin.setConversationMute(wxid, mute, cl)
            }
        }
    }

    /**
     * 利用微信原生"不显示该聊天"API 批量隐藏/显示密友会话。
     * 与 mute 并行执行，互相独立。
     *
     * 关键设计：
     * - 永久隐藏（hide=true）：即使在冷启动窗口期内 Hook 未激活，会话也不在列表显示
     * - 临时解除（hide=false）：与 mute 一起取消，用户可以看到会话
     * - 重新隐藏（hide=true）受独立开关 hideConversation 门控；解除(hide=false)始终执行
     * - 容错：如果 ConversationHideHelper 未就绪，静默跳过
     */
    private fun toggleHideForAllMasked(hide: Boolean) {
        muteHandler.post {
            // 重新隐藏仅在不显示该聊天开关开启时生效；临时解除(hide=false)始终执行
            if (hide && !ConfigUtil.getOptionData().hideConversation) return@post
            if (!ConversationHideHelper.isEnabled()) {
                // 首次调用时尝试初始化（延迟到此时微信 Storage 层已就绪）
                val cl = classLoader ?: return@post
                ConversationHideHelper.ensureInitialized(cl)
                if (!ConversationHideHelper.isEnabled()) return@post
            }
            val cl = classLoader ?: return@post
            for (wxid in ConfigUtil.getMaskList().map { it.maskId }) {
                WXMaskPlugin.setConversationHidden(wxid, hide, cl)
            }
        }
    }

    /**
     * 统一设置/解除所有密友的"不显示该聊天"原生状态（mute + parentRef）。
     * 该状态写入微信自身 DB，模块重启/关闭总开关后依然生效 —— 即最终兜底。
     *
     * 关键设计：
     * - 状态已一致则跳过写盘（避免每次 onResume 重复写 DB）
     * - hide=true 仅当 hideConversation 开关开启时执行；hide=false 始终执行（含用户关闭开关、临时解除）
     */
    private fun applyNativeHideForAll(hide: Boolean) {
        muteHandler.post {
            if (hide && !ConfigUtil.getOptionData().hideConversation) return@post
            val cl = classLoader ?: return@post
            ConversationHideHelper.ensureInitialized(cl)
            if (!ConversationHideHelper.isEnabled()) return@post
            val masked = ConfigUtil.getMaskList().map { it.maskId }
            if (masked.isEmpty()) return@post
            for (wxid in masked) {
                val cur = ConversationHideHelper.isHidden(wxid, cl)
                if (cur == hide) continue  // 状态已一致，跳过写盘
                StealthLog.i("[HideMainUI] applyNativeHide: $wxid -> hidden=$hide (master-independent fallback)")
                WXMaskPlugin.setConversationMute(wxid, hide, cl)
                WXMaskPlugin.setConversationHidden(wxid, hide, cl)
            }
        }
    }

    private fun handleWhitelistOnEnterChat(chatUser: String?, source: String) {
        val opt = ConfigUtil.getOptionData()
        StealthLog.i("enter chatting [$source], Chat_User=$chatUser, tempUnhide=$tempUnhideMainConv rehideOnLeaveChat=${opt.rehideOnLeaveChat}")
        if (chatUser != null && WXMaskPlugin.containChatUser(chatUser)) {
            if (opt.rehideOnLeaveChat) {
                hasEnteredMaskChatting = true
                tempUnhideMainConv = false
                toggleMuteForAllMasked(true)
                toggleHideForAllMasked(true)
                pendingRefreshNeeded = true
                StealthLog.i("enter masked chat, rehideOnLeaveChat=true → rehiding")
            }
        } else {
            if (chatUser == null) {
                StealthLog.w("Chat_User is null, keep tempUnhide=$tempUnhideMainConv")
                return
            }
            if (opt.rehideOnLeaveChat) {
                tempUnhideMainConv = false
                toggleMuteForAllMasked(true)
                toggleHideForAllMasked(true)
                hasEnteredMaskChatting = false
                pendingRefreshNeeded = true
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Adapter Hook 体系
    // ═══════════════════════════════════════════════════════

    private fun hookContactAdapterDirectly(session: HookSession) {
        val contactAdapterName = "com.tencent.mm.ui.contact.e"
        val contactClass = AppReflect.findClassIfExists(contactAdapterName, session.classLoader)
        if (contactClass == null) {
            StealthLog.i("hookContactAdapterDirectly: $contactAdapterName not found")
            return
        }
        hookAdapterClassDynamic(session, contactClass)
    }

    private fun hookListViewSetAdapter(session: HookSession) {
        AppLog.i("[DIAG-T3-LV] hooking ListView.setAdapter")
        session.findAndHook(
            "android.widget.ListView", "setAdapter",
            ListAdapter::class.java
        ) { chain ->
            val result = chain.proceed()
            val adapter = chain.args[0] ?: return@findAndHook result
            val adapterName = adapter.javaClass.name
            AppLog.i("[DIAG-T3-LV] ListView.setAdapter called, adapter=$adapterName")
            if (!isTargetAdapterName(adapterName)) return@findAndHook result
            AppLog.i("[DIAG-T3-LV] target adapter matched: $adapterName")
            // ★ 缓存主会话列表 ListView 引用，供标题栏多击/长按刷新
            (chain.thisObject as? ListView)?.let { cachedListViewRef = WeakReference(it) }
            hookAdapterClassDynamic(session, adapter.javaClass)
            result
        }
    }

    private fun isTargetAdapterName(name: String): Boolean {
        // ★ 优先从 DexKitCache 获取真实适配器类名
        val cache = DexKitCache.getConvMvvmList()
        if (cache != null) {
            if (cache.adapterClassNames.contains(name)) return true
            if (cache.dataSourceClassNames.contains(name)) return true
        }
        // Fallback 硬编码候选
        if (name.startsWith("com.tencent.mm.ui.conversation")) return true
        if (name.startsWith("va5.")) return true
        if (name == "yf5.w0") return true
        // ★ 8.0.74 新增：sc3.x3 / sc3.x 系列（DexKit扫描到的会话adapter）
        if (name.startsWith("sc3.") || name == "sc3.x" || name == "sc3.x3") return true
        return false
    }

    /**
     * 动态 hook adapter 类的 getCount / getItem / getView / notifyDataSetChanged。
     * 每个类仅 hook 一次。
     */
    private fun hookAdapterClassDynamic(session: HookSession, adapterClass: Class<*>) {
        if (hookedClasses.contains(adapterClass)) return
        hookedClasses.add(adapterClass)
        val className = adapterClass.name

        // getCount
        runCatching {
            val m = AppReflect.findMethodExact(adapterClass, "getCount")
            if (m != null) {
                activeHooks.add(session.hook(m).intercept { chain ->
                    filterGetCount(session, chain)
                })
            } else {
                adapterClass.superclass?.let { hookAdapterClassDynamic(session, it) }
            }
        }

        // getItem(int)
        runCatching {
            val m = AppReflect.findMethodExact(adapterClass, "getItem", Int::class.javaPrimitiveType)
            if (m != null) {
                activeHooks.add(session.hook(m).intercept { chain ->
                    filterGetItem(session, chain)
                })
            }
        }

        // getView(int, View, ViewGroup)
        runCatching {
            val m = AppReflect.findMethodExact(adapterClass, "getView",
                Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java)
            if (m != null) {
                activeHooks.add(session.hook(m).intercept { chain ->
                    filterGetView(session, chain)
                })
            }
        }

        // notifyDataSetChanged
        runCatching {
            val m = AppReflect.findMethodExact(adapterClass, "notifyDataSetChanged")
            if (m != null) {
                activeHooks.add(session.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    val adapter = chain.thisObject ?: return@intercept result
                    val now = System.currentTimeMillis()
                    val lastTime = lastCacheInvalidTime[adapter] ?: 0L
                    if (now - lastTime > 50) {
                        val rebuildTime = lastRebuildTime[adapter] ?: 0L
                        if (now - rebuildTime < 200) return@intercept result
                        chatUserCache.clear()
                        cacheInvalid[adapter] = true
                        lastCacheInvalidTime[adapter] = now
                    }
                    result
                })
            }
        }
    }

    private fun getAdapterFilterType(adapter: Any, option: top.mmjz.floatingclouds.bean.OptionData): Int {
        val name = adapter.javaClass.name
        if (name.startsWith("com.tencent.mm.ui.contact")) return 0
        // ★ 8.0.74: 已经过 filterGetView 的 adapter 必定是会话 adapter
        if (filterCache.containsKey(adapter)) return if (option.hideMainConvList) 1 else 0
        // ★ 优先用缓存匹配
        val cache = DexKitCache.getConvMvvmList()
        val isConv = if (cache != null) {
            cache.adapterClassNames.contains(name) || cache.dataSourceClassNames.contains(name)
        } else {
            name.startsWith("com.tencent.mm.ui.conversation") || name.startsWith("va5.") || name.startsWith("yf5.")
        }
        if (isConv) return if (option.hideMainConvList) 1 else 0
        return 0
    }

    // ═══════════════════════════════════════════════════════
    // 数据过滤 — filterGetCount / filterGetItem / filterGetView
    // ═══════════════════════════════════════════════════════

    private fun filterGetCount(session: HookSession, chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        val animState = isInBackAnimation.get()
        if (animState) {
            StealthLog.i("[DIAG] filterGetCount blocked by isInBackAnimation=true")
        }
        val option = ConfigUtil.getOptionData()
        if (!option.hideMainConvList || !ConfigUtil.isMasterEnabled()) return chain.proceed()
        if (ConfigUtil.isMaskListEmpty()) return chain.proceed()
        if (isScanning.get() == true || tempUnhideMainConv) return chain.proceed()
        val adapter = chain.thisObject ?: return chain.proceed()
        if (getAdapterFilterType(adapter, option) == 0) return chain.proceed()
        val originalCount = chain.proceed() as? Int ?: return null
        // ★ 对账：只要微信原始总数未变 + 缓存有效，零消耗复用
        val cachedCnt = cacheOriginalCount[adapter]
        val lastTotal = lastScannedTotal[adapter]
        if (cacheInvalid[adapter] != true && cachedCnt != null && lastTotal != null && lastTotal == originalCount) {
            return cachedCnt
        }
        if (originalCount <= 0) {
            filterCache[adapter] = IntArray(0)
            cacheOriginalCount[adapter] = 0
            cacheInvalid[adapter] = false
            return 0
        }
        isScanning.set(true)
        try {
            val t0 = System.nanoTime()
            // ★ 预分配数组替代 mutableListOf，消除 toIntArray() 的二次拷贝和 GC 压力
            val temp = IntArray(originalCount)
            var idx = 0
            val getItemM = cachedGetItemMethod
            // ★ 构建 maskId HashSet → O(1) 查找替代 O(n*m) cross product
            val maskSet = HashSet<String>(ConfigUtil.getMaskList().map { it.maskId })
            val matchedWxids = mutableListOf<String>()
            val unmatchedSamples = mutableListOf<String>()
            var sampleCount = 0
            for (i in 0 until originalCount) {
                val item = if (getItemM != null) runCatching { getItemM.invoke(adapter, i) }.getOrNull()
                           else runCatching { AppReflect.callMethod(adapter, "getItem", i) }.getOrNull()
                if (item == null) continue
                val chatUser = extractChatUserFromItem(item) ?: continue
                if (chatUser in maskSet) {
                    // 匹配到密友 → 不加入可见列表（隐藏），记录匹配
                    if (matchedWxids.size < 10) matchedWxids.add(chatUser)
                } else {
                    // 非密友 → 加入可见列表
                    temp[idx++] = i
                    if (sampleCount < 5) { unmatchedSamples.add("$i:$chatUser"); sampleCount++ }
                }
            }
            val scanDuration = (System.nanoTime() - t0) / 1000
            StealthLog.i("[DIAG-FILTER] adapter=${adapter.javaClass.name} total=${originalCount} vis=$idx filtered=${originalCount - idx} masks=${maskSet.size} ${scanDuration}μs")
            if (matchedWxids.isNotEmpty()) StealthLog.i("[DIAG-FILTER] matched (hidden): ${matchedWxids.joinToString(",")}")
            if (unmatchedSamples.isNotEmpty()) StealthLog.i("[DIAG-FILTER] unmatched sample: ${unmatchedSamples.joinToString(",")}")
            if (originalCount - idx > 0) StealthLog.i("[DIAG-FILTER] maskSet items: ${maskSet.take(10).joinToString(",")}")
            val elapsed = scanDuration
            if (elapsed > 1000) AppLog.d("filterGetCount scan: ${originalCount}items → ${idx}visible, ${elapsed}μs ⚠️ >1ms")
            // ★ 零拷贝：直接存 temp + 用 cacheOriginalCount 记录有效长度
            filterCache[adapter] = temp
            cacheOriginalCount[adapter] = idx
            lastScannedTotal[adapter] = originalCount  // ★ 记录扫描时的微信真实总数
            cacheInvalid[adapter] = false
            lastRebuildTime[adapter] = System.currentTimeMillis()
            return idx
        } finally { isScanning.set(false) }
    }

    private fun filterGetItem(session: HookSession, chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        val option = ConfigUtil.getOptionData()
        if (!option.hideMainConvList || !ConfigUtil.isMasterEnabled()) return chain.proceed()
        if (isScanning.get() == true || tempUnhideMainConv || ConfigUtil.getMaskList().isEmpty())
            return chain.proceed()
        val adapter = chain.thisObject ?: return chain.proceed()
        if (getAdapterFilterType(adapter, option) == 0) return chain.proceed()
        val filteredPositions = filterCache[adapter] ?: return chain.proceed()
        val validSize = cacheOriginalCount[adapter] ?: filteredPositions.size
        val visiblePos = chain.args[0] as? Int ?: return chain.proceed()
        if (visiblePos < 0 || visiblePos >= validSize) return chain.proceed()
        return chain.proceed(arrayOf(filteredPositions[visiblePos]))
    }

    private fun filterGetView(session: HookSession, chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        // ★ 守卫：非动画 + (开关关闭 或 总开关关闭) = 零干预
        if (!isInBackAnimation.get() && (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideMainConvList)) return chain.proceed()
        if (tempUnhideMainConv || ConfigUtil.getMaskList().isEmpty() || isScanning.get() == true) return chain.proceed()
        val adapter = chain.thisObject ?: return chain.proceed()

        // ★ 8.0.74: hookAdapterClassDynamic 因混淆类层级可能找不到 getCount，
        //   在此直接对 ListAdapter 安装 getCount hook 确保过滤计数生效
        if (!hookedClasses.contains(adapter.javaClass) && adapter is ListAdapter) {
            runCatching {
                val gcm = adapter.javaClass.getDeclaredMethod("getCount")
                session.hook(gcm).intercept { c -> filterGetCount(session, c) }
                hookedClasses.add(adapter.javaClass)
            }
        }

        if (!filterCache.containsKey(adapter) || cacheInvalid[adapter] == true) {
            rebuildFilterCache(session, adapter)
        }
        val filteredPositions = filterCache[adapter] ?: return chain.proceed()
        val visiblePos = chain.args[0] as? Int ?: return chain.proceed()
        val validSize = cacheOriginalCount[adapter] ?: filteredPositions.size
        if (visiblePos < 0 || visiblePos >= validSize) return chain.proceed()
        val originalPos = filteredPositions.getOrNull(visiblePos) ?: visiblePos

        val modifiedArgs = arrayOf(originalPos, chain.args[1], chain.args[2])
        val viewResult = chain.proceed(modifiedArgs)
        if (viewResult !is View) return viewResult

        // ★ 动画期零 View 污染——不碰 layoutParams/alpha，保护 RecycledViewPool
        if (isInBackAnimation.get()) return viewResult

        // ★ 休息态：密友内容清洗（原版安全路径，只清数据和文本）
        val itemData = runCatching { (adapter as? ListAdapter)?.getItem(originalPos) }.getOrNull()
        val chatUser = itemData?.let { extractChatUserFromItem(it) }
        if (chatUser != null && WXMaskPlugin.containChatUser(chatUser)) {
            val contentF = cachedContentField
            if (contentF != null) runCatching { contentF.set(itemData, "") }
            else runCatching { AppReflect.setObjectField(itemData, "field_content", "") }
            runCatching { AppReflect.setObjectField(itemData, "field_digest", "") }
            runCatching { AppReflect.setObjectField(itemData, "field_unReadCount", 0) }
            hideUnReadTipView(viewResult)
            hideMsgViewItemText(viewResult)
        }
        return viewResult
    }

    private fun rebuildFilterCache(session: HookSession, adapter: Any) {
        if (isScanning.get() == true || tempUnhideMainConv) return
        isScanning.set(true)
        try {
            val originalCount = runCatching {
                AppReflect.callMethod(adapter, "getCount") as? Int
            }.getOrNull() ?: return
            if (originalCount <= 0) {
                filterCache[adapter] = IntArray(0)
                cacheInvalid[adapter] = false
                return
            }
            val temp = IntArray(originalCount)
            var idx = 0
            val getItemM = cachedGetItemMethod
            val maskList = ConfigUtil.getMaskList()
            val maskSet = HashSet<String>(maskList.map { it.maskId })
            StealthLog.i("[DIAG] rebuildFilterCache: masks=${maskList.size} items=$originalCount")
            var mismatchCount = 0
            val mismatches = StringBuilder()
            for (i in 0 until originalCount) {
                val item = if (getItemM != null) runCatching { getItemM.invoke(adapter, i) }.getOrNull()
                           else runCatching { AppReflect.callMethod(adapter, "getItem", i) }.getOrNull()
                if (item == null) continue
                val chatUser = extractChatUserFromItem(item) ?: continue
                if (chatUser !in maskSet) { temp[idx++] = i }
                else if (mismatchCount < 5 && !chatUser.startsWith("wxid_", true)) {
                    // 记录不在名单但wxid非标准的项
                    mismatches.append("$chatUser, ")
                    mismatchCount++
                }
            }
            if (mismatchCount > 0) StealthLog.i("[DIAG] non-standard wxid in mask: $mismatches")
            val maskListStr = maskList.filter { !it.maskId.startsWith("wxid_", true) }
            if (maskListStr.isNotEmpty()) StealthLog.i("[DIAG] non-standard wxid in scan: ${maskListStr.map{it.maskId}}")
            filterCache[adapter] = temp
            cacheOriginalCount[adapter] = idx
            lastScannedTotal[adapter] = originalCount
            cacheInvalid[adapter] = false
        } finally { isScanning.set(false) }
    }

    private var extractChatDebugDone = false
    private fun extractChatUserFromItem(item: Any): String? {
        chatUserCache[item]?.let { return it }
        initReflectCache(item, classLoader ?: return runCatching {
            (AppReflect.getObjectField(item, "field_username") as? String)?.takeIf { it.isNotBlank() }
                ?: (AppReflect.getAdditionalInstanceField(item, "wxmask_origin_user") as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull().also { if (it != null) chatUserCache[item] = it })
        val user = runCatching {
            val f = cachedUsernameField
            if (f != null) {
                runCatching { f[item] as? String }.getOrNull()?.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
        val origin = runCatching { AppReflect.getAdditionalInstanceField(item, "wxmask_origin_user") as? String }.getOrNull()?.takeIf { it.isNotBlank() }
        val result = (user ?: origin) ?: runCatching {
            val l4 = AppReflect.getObjectField(item, "d") ?: return@runCatching null
            val f = AppReflect.getObjectField(l4, "field_username") as? String
            val final = if (f != null && f.startsWith("wxid_", true)) f
                        else findWxidInL4(l4) ?: f?.takeIf { it.isNotBlank() }
            // 仅首次扫描时 dump 3 个 item 的 wxid 提取详情
            if (!extractChatDebugDone) {
                StealthLog.i("[DIAG] extractChat: item=${item.javaClass.simpleName} cachedFieldUser=$user origin=$origin l4.field=$f final=$final")
            }
            final
        }.getOrNull()
        if (!extractChatDebugDone) extractChatDebugDone = true
        return result.also { if (it != null) chatUserCache[item] = it }
    }

    /** 在 l4 存储对象中搜索以 wxid_ 开头的标准 wxid */
    private fun findWxidInL4(l4: Any): String? {
        var cls: Class<*>? = l4.javaClass
        while (cls != null && cls != Any::class.java && cls != Object::class.java) {
            for (fd in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(fd.modifiers)) continue
                fd.isAccessible = true
                try {
                    val v = fd.get(l4)
                    if (v is String && v.startsWith("wxid_", true)) return v
                } catch (_: Exception) {}
            }
            cls = cls.superclass
        }
        return null
    }
    // ═══════════════════════════════════════════════════════
    // View 内容清除
    // ═══════════════════════════════════════════════════════

    private fun hideUnReadTipView(itemView: View) {
        if (isInBackAnimation.get()) return
        val tipTvId = getViewId(when (AppVersionUtil.getVersionCode()) {
            in 0..Constrant.WX_CODE_8_0_22 -> "tipcnt_tv"
            Constrant.WX_CODE_PLAY_8_0_42 -> "oqu"
            in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_41 -> "kmv"
            else -> "kmv"
        })
        if (tipTvId != 0) itemView.findViewById<View>(tipTvId)?.visibility = View.INVISIBLE
        val smallRedId = getViewId(when (AppVersionUtil.getVersionCode()) {
            in 0..Constrant.WX_CODE_8_0_40 -> "a2f"
            Constrant.WX_CODE_PLAY_8_0_42 -> "a_w"
            Constrant.WX_CODE_8_0_41 -> "o_u"
            else -> "o_u"
        })
        if (smallRedId != 0) itemView.findViewById<View>(smallRedId)?.visibility = View.INVISIBLE
    }

    // ★ 内存单例 Field 句柄：首次通过反射找到 itemView 上存储消息文本的成员变量
    @Volatile private var cachedMsgTvField: Field? = null

    private fun hideMsgViewItemText(itemView: View) {
        if (isInBackAnimation.get()) return
        val setTextMethod = cachedSetTextMethod ?: return
        runCatching {
            // O(1) 缓存句柄：只在首次查找 itemView 类中类型为 TextView/NoMeasuredTextView 的成员 Field
            var field = cachedMsgTvField
            if (field == null) {
                field = itemView.javaClass.declaredFields.firstOrNull { f ->
                    f.type.name.contains("NoMeasuredTextView") || f.type.name.contains("TextView")
                }
                if (field != null) {
                    field.isAccessible = true
                    cachedMsgTvField = field
                }
            }
            val msgTv = field?.get(itemView) as? View ?: return@runCatching
            setTextMethod.invoke(msgTv, "")
        }
    }

    // ═══════════════════════════════════════════════════════
    // 旧版兜底
    // ═══════════════════════════════════════════════════════

    private fun handleMainUIChattingListView(session: HookSession) {
        val adapterName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.conversation.k"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 ->
                if (AppVersionUtil.getVersionName() == "8.0.35") "com.tencent.mm.ui.conversation.r" else "com.tencent.mm.ui.conversation.p"
            Constrant.WX_CODE_8_0_35 -> "com.tencent.mm.ui.conversation.r"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_41 -> "com.tencent.mm.ui.conversation.x"
            Constrant.WX_CODE_8_0_47 -> "com.tencent.mm.ui.conversation.p3"
            Constrant.WX_CODE_8_0_50 -> "com.tencent.mm.ui.conversation.q3"
            else -> null
        }
        adapterName?.let { ClazzN.from(it, session.classLoader) }?.let { hookListViewAdapter(session, it); return }
        session.findAndHook("android.widget.ListView", "setAdapter", ListAdapter::class.java) { chain ->
            val r = chain.proceed()
            val a = chain.args[0] ?: return@findAndHook r
            if (a.javaClass.name.startsWith("com.tencent.mm.ui.conversation")) hookListViewAdapter(session, a.javaClass)
            r
        }
    }

    private fun hookListViewAdapter(session: HookSession, adapterClazz: Class<*>) {
        val gvm = AppReflect.findMethodExact(adapterClazz, "getView", Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java) ?: return
        val baseConvClazz = ClazzN.from(ClazzN.BaseConversation)
        session.hook(gvm).intercept { chain ->
            val result = chain.proceed()
            if (!ConfigUtil.getOptionData().hideMainConvList || ConfigUtil.getMaskList().isEmpty()) return@intercept result
            val adapter = chain.thisObject as? ListAdapter ?: return@intercept result
            val position = chain.args[0] as? Int ?: return@intercept result
            val itemData = adapter.getItem(position) ?: return@intercept result
            val itemView = result as? View ?: return@intercept result
            val origin = AppReflect.getAdditionalInstanceField(itemData, "wxmask_origin_user") as? String
            val chatUser = origin ?: (AppReflect.getObjectField(itemData, "field_username") as? String ?: return@intercept result)
            if (WXMaskPlugin.containChatUser(chatUser)) {
                // ★ 使用直接反射写入确保兼容 8.0.70 的混淆字段名
                val fieldsToClear = listOf("field_content", "field_digest")
                fieldsToClear.forEach { fieldName ->
                    runCatching {
                        var clz: Class<*>? = itemData.javaClass
                        while (clz != null && clz != Any::class.java) {
                            try {
                                val f = clz!!.getDeclaredField(fieldName)
                                f.isAccessible = true
                                f.set(itemData, "")
                                break
                            } catch (_: NoSuchFieldException) { clz = clz.superclass }
                        }
                    }
                }
                val intFields = mapOf("field_unReadCount" to 0, "field_UnReadInvite" to 0, "field_unReadMuteCount" to 0)
                intFields.forEach { (k, v) ->
                    runCatching {
                        var clz: Class<*>? = itemData.javaClass
                        while (clz != null && clz != Any::class.java) {
                            try {
                                val f = clz!!.getDeclaredField(k)
                                f.isAccessible = true
                                f.setInt(itemData, v)
                                break
                            } catch (_: NoSuchFieldException) { clz = clz.superclass }
                        }
                    }
                }
                runCatching {
                    var clz: Class<*>? = itemData.javaClass
                    while (clz != null && clz != Any::class.java) {
                        try { val f = clz!!.getDeclaredField("field_msgType"); f.isAccessible = true; f.set(itemData, "1"); break }
                        catch (_: NoSuchFieldException) { clz = clz.superclass }
                    }
                }
                hideUnReadTipView(itemView)
                hideMsgViewItemText(itemView)
            }
            result
        }
    }

    // ═══════════════════════════════════════════════════════
    // RecyclerView Hook (8.0.47+)
    // ═══════════════════════════════════════════════════════

    private fun handleMainUIChattingListView2(session: HookSession) {
        if (AppVersionUtil.getVersionCode() >= Constrant.WX_CODE_8_0_47) hookRecyclerView(session)
        hookListViewSetAdapter(session)
    }

    private fun hookRecyclerView(session: HookSession) {
        AppLog.i("[DIAG-T3-RV] hookRecyclerView entered")
        runCatching {
            val rvClazz = AppReflect.findClassIfExists("androidx.recyclerview.widget.RecyclerView", session.classLoader)
                ?: AppReflect.findClassIfExists("android.support.v7.widget.RecyclerView", session.classLoader)
            if (rvClazz == null) { AppLog.i("[DIAG-T3-RV] RecyclerView class not found"); return }
            val adapterClazz = AppReflect.findClassIfExists("androidx.recyclerview.widget.RecyclerView\$Adapter", session.classLoader)
                ?: AppReflect.findClassIfExists("android.support.v7.widget.RecyclerView\$Adapter", session.classLoader)
            if (adapterClazz == null) { AppLog.i("[DIAG-T3-RV] RecyclerView.Adapter class not found"); return }
            val setAdapterMethod = AppReflect.findMethodExact(rvClazz, "setAdapter", adapterClazz)
            if (setAdapterMethod == null) { AppLog.i("[DIAG-T3-RV] RecyclerView.setAdapter method not found"); return }
            AppLog.i("[DIAG-T3-RV] RecyclerView.setAdapter hooked")
            session.hook(setAdapterMethod).intercept { chain ->
                val result = chain.proceed()
                val adapterObj = chain.args[0] ?: return@intercept result
                val name = adapterObj.javaClass.name
                FileLog.i("SetAdapter", name)
                val isTarget = name.startsWith("com.tencent.mm.ui") || name.startsWith("va5.")
                    || name == "zk3.m" || name == "lr.f1" || name.startsWith("rh4.")
                    || name.startsWith("sc3.") || name == "sc3.x" || name == "sc3.x3"
                if (!isTarget) return@intercept result
                // ★ Immediately process lr.f1's data list (main conv data)
                if (name == "lr.f1") {
                    runCatching {
                        val eField = adapterObj.javaClass.getDeclaredField("e"); eField.isAccessible = true
                        val dataList = eField.get(adapterObj) as? ArrayList<*>
                        if (dataList != null && dataList.isNotEmpty()) {
                            val masked = HashSet(ConfigUtil.getMaskList().map { it.maskId })
                            FileLog.i("ConvRV", "lr.f1: ${dataList.size} items, masked=${masked.size}")
                            // Try to find wxid in each item and remove masked ones
                            val toRemove = ArrayList<Any?>()
                            for (item in dataList) {
                                if (item == null) continue
                                // Item is ir.y0, try extracting wxid from y0.a field
                                var wxid: String? = null
                                runCatching {
                                    // Search y0.a field up class hierarchy
                                    var ic: Class<*>? = item.javaClass
                                    while (ic != null) {
                                        try {
                                            val af = ic.getDeclaredField("a"); af.isAccessible = true
                                            val aObj = af.get(item)
                                            if (aObj != null) {
                                                wxid = extractChatUserFromItem(aObj)
                                                if (wxid != null) break
                                            }
                                        } catch (_: Exception) {}
                                        ic = ic.superclass
                                    }
                                }
                                if (wxid != null && wxid in masked) {
                                    toRemove.add(item)
                                    FileLog.i("ConvRV", "lr.f1 HIDE: $wxid")
                                }
                            }
                            if (toRemove.isNotEmpty()) {
                                dataList.removeAll(toRemove)
                                adapterObj.javaClass.getMethod("notifyDataSetChanged").invoke(adapterObj)
                            }
                        }
                    }.onFailure { FileLog.e("ConvRV", "lr.f1 process fail", it) }
                }
                if (hookedClasses.contains(adapterObj.javaClass)) return@intercept result
                hookedClasses.add(adapterObj.javaClass)
                // getItemCount — 保留原始数量，不缩减（避免 RecyclerView 位置追踪错乱）
                // 内容隐藏交由 onBindViewHolder 处理
                //（保留 getItemCount hook 用于缓存扫描，但不再减数）
                // ★ onBindViewHolder — 核心内容清除（RecyclerView 版 getView）
                runCatching {
                    val onBindVHMethods = AppReflect.findMethodsByExactPredicate(adapterObj.javaClass) { m ->
                        m.name == "onBindViewHolder" && m.parameterTypes.size == 2
                    }
                    if (onBindVHMethods.isEmpty()) return@runCatching
                    val onBindVH = onBindVHMethods[0]
                    session.hook(onBindVH).intercept { c ->
                        val result = c.proceed()
                        if (tempUnhideMainConv || ConfigUtil.getMaskList().isEmpty() || !ConfigUtil.getOptionData().hideMainConvList) return@intercept result
                        val pos = c.args.getOrNull(1) as? Int ?: return@intercept result
                        val adapter = c.thisObject
                        val dataList = runCatching { AppReflect.getObjectField(adapter, "data") as? List<*> }.getOrNull() ?: return@intercept result
                        if (pos < 0 || pos >= dataList.size) return@intercept result
                        val item = dataList[pos] ?: return@intercept result
                        val chatUser = extractChatUserFromItem(item)
                        if (chatUser != null && WXMaskPlugin.containChatUser(chatUser)) {
                            val holder = c.args[0] ?: return@intercept result
                            val itemView = runCatching { AppReflect.getObjectField(holder, "itemView") as? View }.getOrNull()
                            if (itemView != null) {
                                // 像素级照抄原版：字段清除
                                listOf("field_content", "field_digest", "field_msgContent", "field_summary").forEach { fn ->
                                    runCatching { AppReflect.setObjectField(item, fn, "") }
                                }
                                listOf("field_unReadCount" to 0, "field_UnReadInvite" to 0, "field_unReadMuteCount" to 0).forEach { (fn, v) ->
                                    runCatching { AppReflect.setObjectField(item, fn, v) }
                                }
                                runCatching { AppReflect.setObjectField(item, "field_msgType", "1") }
                                runCatching { AppReflect.callMethod(item, "setDigest", "") }
                                // 内容清除
                                hideMsgViewItemText(itemView)
                                hideUnReadTipView(itemView)
                            }
                        }
                        result
                    }
                }
                result
            }
        }.onFailure { StealthLog.i("hook RecyclerView fail", it) }
    }

    // ═══════════════════════════════════════════════════════
    // UI 刷新
    // ═══════════════════════════════════════════════════════

    private fun findListViewAndNotify(rootView: View) {
        when {
            rootView is ListView -> { cachedListViewRef = WeakReference(rootView); notifyAdapter(rootView.adapter) }
            rootView.javaClass.name.contains("RecyclerView") -> notifyRecyclerAdapter(rootView)
            rootView is ViewGroup -> for (i in 0 until rootView.childCount) findListViewAndNotify(rootView.getChildAt(i))
        }
    }

    /** 同 findListViewAndNotify，但不更新 cachedListViewRef（解除隐藏专用） */
    private fun findListViewAndNotifyNoCache(rootView: View) {
        when {
            rootView is ListView -> notifyAdapter(rootView.adapter)
            rootView.javaClass.name.contains("RecyclerView") -> notifyRecyclerAdapter(rootView)
            rootView is ViewGroup -> for (i in 0 until rootView.childCount) findListViewAndNotifyNoCache(rootView.getChildAt(i))
        }
    }

    /** 同步遍历 ListView 可见子 View，直接隐藏密友项（不等异步 layout）。
     *  解决全面屏手势动画期间 layout 被推迟导致无法即时隐藏的问题。 */
    private fun hideMaskedConvViewsSync() {
        val lv = cachedListViewRef?.get() ?: return
        if (lv.windowToken == null) return
        val adapter = lv.adapter ?: return
        val maskSet = HashSet(ConfigUtil.getMaskList().map { it.maskId })
        val getItemM = runCatching {
            var cls: Class<*>? = adapter.javaClass
            while (cls != null && cls != Object::class.java) {
                try { return@runCatching cls.getDeclaredMethod("getItem", Int::class.javaPrimitiveType).also { it.isAccessible = true } }
                catch (_: Exception) { cls = cls.superclass }
            }
            null
        }.getOrNull() ?: return
        var hidden = 0
        for (i in 0 until lv.childCount) {
            val child = lv.getChildAt(i) ?: continue
            val pos = lv.getPositionForView(child)
            if (pos < 0) continue
            val item = runCatching { getItemM.invoke(adapter, pos) }.getOrNull() ?: continue
            val wxid = extractWxidFromYf5DataItem(item) ?: continue
            if (wxid !in maskSet) continue
            child.layoutParams = child.layoutParams.apply { height = 0 }
            child.visibility = View.GONE
            hidden++
        }
        if (hidden > 0) StealthLog.i("[DIAG] hideMaskedConvViewsSync: hid $hidden masked views in ListView")
    }

    private fun notifyAdapter(adapter: ListAdapter?) {
        runCatching {
            val a = adapter ?: return
            val real = runCatching { AppReflect.callMethod(a, "getWrappedAdapter") }.getOrNull() ?: a
            cacheInvalid[real] = true
            chatUserCache.clear()
            AppReflect.callMethod(real, "notifyDataSetChanged")
        }
    }

    private fun notifyRecyclerAdapter(rv: View) {
        runCatching {
            val adapter = rv.javaClass.getMethod("getAdapter").invoke(rv) ?: return
            cacheInvalid[adapter] = true
            chatUserCache.clear()
            AppReflect.callMethod(adapter, "notifyDataSetChanged")
        }
    }

    /**
     * 判断当前是否处于微信主页（底部"微信"Tab 选中）。
     * 采用三层判断（任意一层命中即认定）：
     *  1) 反射 LauncherUI.getCurrentTab()，返回 0 即微信 Tab（微信公开 API，最稳）。
     *  2) 底部 Tab 栏中当前 isSelected 的 Tab 文本；选中"微信"即在微信页。
     *  3) 兜底：顶部标题文本为"微信"。
     * 只有微信主页才允许触发临时解除，避免通讯录/发现页顶部误触。
     */
    @Suppress("DEPRECATION")
    private fun isCurrentTabWeChat(): Boolean {
        val act = launcherUIActivityRef?.get()
        // —— 第①层：getCurrentTab() 反射 ——
        if (act != null && launcherUIClazz?.isInstance(act) == true) {
            runCatching {
                val m = AppReflect.findMethodExact(launcherUIClazz!!, "getCurrentTab")
                if (m != null) {
                    val idx = AppReflect.callMethod(act, "getCurrentTab") as? Int
                    StealthLog.i("[HideMainUI] getCurrentTab()=$idx")
                    if (idx != null) return idx == 0
                }
            }.onFailure { StealthLog.w("[HideMainUI] getCurrentTab reflect fail", it) }
        }
        // —— 第②层：底部 Tab 选中态文本 ——
        val root = currentRootView
        if (root != null) {
            val screenH = runCatching { root.context.resources.displayMetrics.heightPixels }.getOrDefault(0)
            val bottomMin = (screenH * 0.80f).toInt().coerceAtLeast(screenH - 200)
            val tabTexts = setOf("微信", "通讯录", "发现", "我")
            val selected = ChildDeepCheck().filter(root) { v ->
                v is TextView && v.visibility == View.VISIBLE &&
                v.text?.toString() in tabTexts && run {
                    val r = android.graphics.Rect()
                    v.getGlobalVisibleRect(r) && r.bottom > bottomMin
                }
            }.firstOrNull { tv ->
                var cur: View? = tv
                while (cur != null) {
                    if (cur.isSelected) return@firstOrNull true
                    cur = cur.parent as? View
                }
                false
            }?.let { (it as? TextView)?.text?.toString() }
            StealthLog.i("[HideMainUI] bottom selected tab text='$selected'")
            if (selected != null) return selected == "微信"
        }
        // —— 第③层：兜底顶部标题文本 ——
        val tv = titleViewRef?.get() as? TextView
        val topText = tv?.text?.toString()
        StealthLog.i("[HideMainUI] fallback top title text='$topText'")
        return topText == "微信"
    }

    /**
     * 在标题 TextView 上直接挂监听器（对齐 InkHide 的稳健方案）。
     * 不再拦截 Activity.dispatchTouchEvent，也不依赖文本匹配——标题 View 自身接收触摸，
     * 由系统正常派发，规避澎湃 OS 全面屏手势/导航栏吞掉顶部触摸事件的问题。
     *
     * 多策略定位标题 TextView（兼容不同微信版本的 ActionBar/Toolbar 实现）；
     * 定位不到则本次 onResume 不启用（下次 onResume 重试，布局就绪后通常能命中）。
     */
    private fun attachTitleListeners(rootView: View) {
        // ★ 关键：标题必须是"屏幕顶部"的 ActionBar/Toolbar 标题，而非底部导航栏的"微信"Tab
        //   文字。微信 LauncherUI 底部 Tab 的"微信"也是 text="微信" 的 TextView，深度遍历时
        //   若先命中它，会把 listener 挂到屏幕底部（实测 rect.top≈2269），导致在顶部标题栏
        //   多击/长按时根本不触发——这就是"多击/长按不生效"的真正根因。
        //   因此所有候选都约束在屏幕顶部 25% 区域（屏幕高约 2376，顶部≈0~594）。
        val screenH = runCatching { (rootView.context.resources.displayMetrics.heightPixels) }.getOrDefault(0)
        val topBandMax = (screenH * 0.25f).toInt().coerceAtLeast(120)

        // 候选判定：可见 TextView 且屏幕坐标顶部落在顶部区域
        fun isTopTitle(v: View): Boolean {
            if (v !is TextView || v.visibility != View.VISIBLE || v.height <= 0) return false
            val r = android.graphics.Rect()
            if (!v.getGlobalVisibleRect(r)) return false
            return r.top < topBandMax
        }

        // ★ 关键：只接受"当前文本就是『微信』"的标题 View 作为触发源。
        //   原因——微信 LauncherUI 的顶部标题栏（Toolbar/标题 TextView）是跨 Tab 共用的同一个
        //   实例，切到通讯录/发现页时标题栏不重建、只是 setText 改文字。若挂到一个"任意 Toolbar
        //   内 TextView"，则三个 Tab 共用该监听，点哪个页顶部都会触发临时解除（实测误触）。
        //   因此定位阶段就锁死"文本==微信"，并且回调里再实时校验一次当前文本，双保险。
        fun isWeChatTitle(v: View): Boolean {
            val tv = v as? TextView ?: return false
            return (tv.text == "微信" || tv.contentDescription == "微信")
        }

        // 策略①：ActionBar/Toolbar 容器内、文本为"微信"的标题 TextView（最精确）
        var titleView: View? = run {
            val toolbar = ChildDeepCheck.findFirst(rootView) { v ->
                v.javaClass.name.contains("ActionBar") ||
                v.javaClass.name.contains("Toolbar") ||
                v.id == rootView.context.resources.getIdentifier("action_bar", "id", rootView.context.packageName)
            }
            (toolbar as? ViewGroup)?.let { tg ->
                ChildDeepCheck.findFirst(tg) { v -> isTopTitle(v) && isWeChatTitle(v) }
            } ?: toolbar?.takeIf { isTopTitle(it) && isWeChatTitle(it) }
        }
        // 策略②：整树中顶部区域、文本为"微信"的 TextView
        if (titleView == null) {
            titleView = ChildDeepCheck.findFirst(rootView) { v ->
                isTopTitle(v) && isWeChatTitle(v)
            }
        }
        if (titleView == null) {
            StealthLog.i("[HideMainUI] Title view not found (text=='微信' top band=$topBandMax) this pass, will retry on next onResume")
            return
        }
        // ★ 关键修复：不再用"引用相等"去重。原因——微信在 onResume 后会重建/替换标题
        //   TextView（旧实例被移除但弱引用尚在 GC 前仍非空），若仍指向旧 View 就直接 return，
        //   会导致 500ms/1500ms 兜底也跳过，最终把 listener 挂到了已被移除的旧 View 上 →
        //   多击/长按永远不触发。setOnClickListener/setOnLongClickListener 是"替换"语义，
        //   重复 set 不会叠加，因此每次找到标题都直接重新挂载即可，安全且保证挂到最新 View。
        titleViewRef = WeakReference(titleView)
        StealthLog.i("[HideMainUI] Title view resolved: ${titleView.javaClass.name} text=\"${(titleView as? TextView)?.text}\"")

        val opt = ConfigUtil.getOptionData()
        val longPressMs = if (opt.longPressDuration > 0) opt.longPressDuration.toLong() else 800L
        val multiClickWindow = if (opt.multiClickInterval > 0) opt.multiClickInterval.toLong() else 500L

        // ★ 回调页面定位：触发前实时判断当前是否处于微信主页（底部"微信"Tab 选中）。
        //   用 isCurrentTabWeChat()（getCurrentTab 反射 / 底部选中 Tab 文本 / 顶部标题兜底），
        //   仅微信主页才允许触发临时解除，彻底避免通讯录/发现页顶部误触。

        // ★ 长按临时解除：setOnTouchListener 自计时（替代 setOnLongClickListener）
        //   原因：1) setOnLongClickListener 写死500ms忽略用户配置的长按时长
        //        2) 澎湃 OS3 ACTION_CANCEL 杀死 CheckForLongPress postDelayed 导致长按失效
        //   采用 setOnTouchListener + postDelayed 自计时 + 180ms CANCEL 容错
        if (opt.enableLongPressTempUnhide) {
            val slop = android.view.ViewConfiguration.get(titleView.context).scaledTouchSlop
            titleView.setOnTouchListener { _, event ->
                if (!ConfigUtil.isMasterEnabled() || !isCurrentTabWeChat()) {
                    cancelTitleLongPress()
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        titleConsumedByLongPress = false
                        titleCancelPending = false
                        titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                        titleLongPressPending = true
                        titlePendingX = event.x
                        titlePendingY = event.y
                        titleLongPressHandler.removeCallbacks(titleLongPressRunnable)
                        titleLongPressHandler.postDelayed(titleLongPressRunnable, longPressMs)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (titleLongPressPending) {
                            val dx = event.x - titlePendingX
                            val dy = event.y - titlePendingY
                            if (dx * dx + dy * dy > slop * slop) {
                                cancelTitleLongPress()
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (titleCancelPending) {
                            titleCancelPending = false
                            titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                        }
                        cancelTitleLongPress()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        // 澎湃 OS3 激进手势提前发送 CANCEL → 延迟 180ms 容错
                        titleCancelPending = true
                        titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                        titleLongPressHandler.postDelayed(titleCancelToleranceRunnable, CANCEL_TOLERANCE_MS)
                    }
                }
                false // ★ 不消费事件，保证 OnClickListener 多击仍能正常工作
            }
        }
        // ★ 多击临时解除：挂 OnClickListener + 自计数（不依赖 dispatchTouchEvent）
        if (opt.enableMultiClickTempUnhide) {
            titleView.setOnClickListener {
                if (!ConfigUtil.isMasterEnabled() || !opt.enableMultiClickTempUnhide) return@setOnClickListener
                // 仅在当前处于微信主页（底部"微信"Tab 选中）才计入多击
                if (!isCurrentTabWeChat()) {
                    titleClickCounter = 0
                    return@setOnClickListener
                }
                val now = System.currentTimeMillis()
                if (now - titleLastClickTime < multiClickWindow) {
                    titleClickCounter++
                } else {
                    titleClickCounter = 1
                }
                titleLastClickTime = now
                AppLog.i("HideMainUI: tap #$titleClickCounter (window=${multiClickWindow}ms, targetClicks=${opt.multiClickCount})")
                if (titleClickCounter >= opt.multiClickCount) {
                    titleClickCounter = 0
                    AppLog.i("HideMainUI: multi-click triggered (${opt.multiClickCount} taps)")
                    onTempUnhideTriggered()
                }
            }
        }
        hasRegisteredMultiClick = true
        StealthLog.i("[HideMainUI] Title long-press/multi-click listeners attached (view=${titleView.javaClass.simpleName})")
    }

    /** 取消标题长按自计时（清理 pending 状态 + 移除所有延时回调） */
    private fun cancelTitleLongPress() {
        titleLongPressPending = false
        titleCancelPending = false
        titleLongPressHandler.removeCallbacks(titleLongPressRunnable)
        titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
    }

    /** 临时解除/恢复时联动刷新通讯录，使通讯录隐藏跟随同一临时解除状态 */
    private fun syncContactListTempUnhide() {
        HideContactListPluginPart.refresh()
    }

    private fun onTempUnhideTriggered() {
        val opt = ConfigUtil.getOptionData()
        StealthLog.i("HideMainUI: onTempUnhideTriggered enableMultiClick=${opt.enableMultiClickTempUnhide}")
        if (!ConfigUtil.isMasterEnabled() || !opt.enableMultiClickTempUnhide) return
        tempUnhideMainConv = true
        Toast.makeText(top.mmjz.floatingclouds.util.AppContext.context, "刻舟求剑", Toast.LENGTH_SHORT).show()
        toggleMuteForAllMasked(false)
        toggleHideForAllMasked(false)
        cacheInvalid.clear()
        chatUserCache.clear()
        // ★ 不依赖缓存 ListView，直接从当前根视图查找并刷新（用 NoCache 版本）
        Handler(Looper.getMainLooper()).postDelayed({
            StealthLog.i("HideMainUI: onTempUnhide refresh rootView=${currentRootView?.javaClass?.simpleName}")
            currentRootView?.let { rv ->
                runCatching { findListViewAndNotifyNoCache(rv) }
            }
            syncContactListTempUnhide()
        }, 300)
    }

    /** 8.0.74: yf5.w0 由 hookListViewSetAdapter 通过 isTargetAdapterName 接管过滤。
     *  extractChatUserFromItem 已兼容嵌套 x.d(l4).field_username 提取。 */
    private fun hookConvDataSource(session: HookSession) {
        // 由 hookListViewSetAdapter 自动接管，无需额外操作
    }

    private fun shouldFilterConv(): Boolean {
        return ConfigUtil.isMasterEnabled()
                && ConfigUtil.getOptionData().hideMainConvList
                && !tempUnhideMainConv
                && ConfigUtil.getMaskList().isNotEmpty()
    }

    private fun getMaskedWxidSet(): HashSet<String> =
        HashSet(ConfigUtil.getMaskList().map { it.maskId })

    /** 从 yf5.x 数据对象中提取 wxid。尝试顺序:
     *  1) x.d 字段 (l4 存储对象) 内的 field_username
     *  2) 遍历所有 String 字段找 wxid 格式
     */
    private fun extractWxidFromYf5DataItem(item: Any?): String? {
        if (item == null) return null
        try {
            val l4Obj = AppReflect.getObjectField(item, "d")
            if (l4Obj != null) {
                val wxid = AppReflect.getObjectField(l4Obj, "field_username") as? String
                if (wxid != null && isWxidStr(wxid)) return wxid
            }
        } catch (_: Exception) {}
        var cls: Class<*>? = item.javaClass
        while (cls != null && cls != Any::class.java && cls != Object::class.java) {
            for (field in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                try {
                    val v = field.get(item)
                    if (v is String && isWxidStr(v)) return v
                } catch (_: Exception) {}
            }
            cls = cls.superclass
        }
        return null
    }

    private fun isWxidStr(s: String) = s.startsWith("wxid_", ignoreCase = true) || s.contains("@chatroom") || s == "qmessage"

    /** 保留旧方法签名但清空实现 */
    private fun processLrF1Adapter(rootView: View) {}
    private fun scanAndHideMaskedConvItems(rootView: View) {}

    private fun onLongPressUnhideTriggered() {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().enableLongPressTempUnhide) return
        tempUnhideMainConv = true
        Toast.makeText(top.mmjz.floatingclouds.util.AppContext.context, "刻舟求剑", Toast.LENGTH_SHORT).show()
        toggleMuteForAllMasked(false)
        toggleHideForAllMasked(false)
        cacheInvalid.clear()
        chatUserCache.clear()
        currentRootView?.let { rv ->
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { findListViewAndNotifyNoCache(rv) }
                syncContactListTempUnhide()
            }, 300)
        }
        StealthLog.i("longPressUnhideTriggered: tempUnhideMainConv activated")
    }
}
