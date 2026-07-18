
package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.ObfuscatedClassResolver
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.BuildConfig

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.StealthLog
import top.mmjz.floatingclouds.util.VoipClassResolver
import top.mmjz.floatingclouds.util.VoipInterceptionState
import java.util.regex.Pattern

/**
 * 忽略指定联系人的语音/视频通话（方案 2：全入口拦截）。
 */
class IgnoreVoipCallPluginPart : IPlugin {

    companion object {
        private const val TAG = "IgnoreVoipCall"
        // ★ 性能诊断计数器（仅 Debug 模式）
        private var fastPassCount = 0
        private var scanCount = 0
        private val FLUTTER_USER_PATTERN = Pattern.compile("username=(.*?),")

        private val VOIP_SERVICE_NAMES = arrayOf(
            "com.tencent.mm.plugin.voip.widget.VoipForegroundService",
            "com.tencent.mm.plugin.voip.widget.VoipNewForegroundService",
            "com.tencent.mm.plugin.voip.widget.VoipSmallService"
        )

        private val VOIP_ACTIVITY_NAMES = arrayOf(
            "com.tencent.mm.plugin.voip.ui.VideoActivity",
            "com.tencent.mm.plugin.appbrand.wmpfvoip.notify.ui.WmpfVoipCallInProxyActivity",
            "com.tencent.mm.plugin.voip.ui.MMSuperAlert",
            "com.tencent.mm.plugin.voip.widget.InviteRemindDialog",
            "com.tencent.mm.plugin.voip.floatcard.VoipFloatCardPermissionDialog",
            "com.tencent.mm.plugin.voip.ui.VoipViewFragment"
        )

        private val VOIP_NOTIFICATION_KEYWORDS = arrayOf(
            "voip", "call", "incoming", "语音", "视频", "通话", "呼叫", "来电", "邀请你", "等待接听", "接听"
        )

        private val VOIP_VIEW_CLASSES = arrayOf(
            "com.tencent.mm.plugin.voip.widget.NewVideoTalkingSmallView",
            "com.tencent.mm.plugin.voip.widget.BaseSmallView",
            "com.tencent.mm.plugin.voip.video.MovableVideoView",
            "com.tencent.mm.plugin.voip.video.NewMovableVideoView",
            "com.tencent.mm.plugin.voip.video.VoIPRenderTextureView",
            "com.tencent.mm.plugin.voip.widget.VoipBigIconButton"
        )

        private val OUTGOING_PATTERNS = listOf(
            Pattern.compile("(voip_)?(is_?)?out_?call[=:](true|1|yes)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("outgoing[=:](true|1|yes)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("is_?caller[=:](true|1|yes)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(is_?)?from_?me[=:](true|1|yes)", Pattern.CASE_INSENSITIVE)
        )

        private val INCOMING_PATTERNS = listOf(
            Pattern.compile("(is_?)?incoming[=:](true|1|yes)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(voip_)?in_?call[=:](true|1|yes)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("invite[=:](true|1|yes)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("caller[=:](false|0|no)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(is_?)?from_?me[=:](false|0|no)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("is_?ilink_?voip[=:](true|1|yes)", Pattern.CASE_INSENSITIVE)
        )
    }

    override fun handleHook(session: HookSession) {
        log("handleHook started")

        // 最上游入口
        hookActivityStarts(session)
        hookContextStarts(session)
        hookInstrumentation(session)
        hookActivityTaskManager(session)
        hookActivityManager(session)

        // 微信内部入口
        hookIncomingCallHandler(session)
        hookIncomingCallUi(session)
        hookIncomingCallManager(session)
        hookVoipMgrBind(session)

        // Service/通知/Activity/Flutter/WindowManager/Telecom
        hookServiceStartForeground(session)
        hookVoipServices(session)
        hookNotifications(session)
        hookTelecomManager(session)
        hookVoipActivities(session)
        hookFlutterGlobal(session)
        hookWindowManagerImpl(session)
        hookWindowManagerAddView(session)
        hookDialogAndPopup(session)

        // 音频振动兜底
        hookAudioAndVibration(session)
    }

    // region Layer -2: startActivity 全链路拦截

    private fun hookActivityStarts(session: HookSession) {
        val activityClass = Activity::class.java
        runCatching {
            session.findAndHook(activityClass.name, "startActivity", Intent::class.java) { chain ->
                handleStartActivityHook(chain)
            }
        }
        runCatching {
            session.findAndHook(activityClass.name, "startActivity", Intent::class.java, Bundle::class.java) { chain ->
                handleStartActivityHook(chain)
            }
        }
        runCatching {
            session.findAndHook(activityClass.name, "startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType!!) { chain ->
                handleStartActivityHook(chain)
            }
        }
        runCatching {
            session.findAndHook(activityClass.name, "startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType!!, Bundle::class.java) { chain ->
                handleStartActivityHook(chain)
            }
        }
        log("Activity start hooks installed")
    }

    private fun handleStartActivityHook(chain: XposedInterface.Chain): Any? {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return chain.proceed()
        val intent = chain.args.firstOrNull { it is Intent } as? Intent
        if (intent != null) {
            logVoipIntentCandidate("startActivity", intent)
            if (shouldInterceptIntent(intent)) {
                log("intercept startActivity intent=${intent.component} extras=${summarizeExtras(intent)}")
                return null
            }
        }
        return chain.proceed()
    }

    private fun hookContextStarts(session: HookSession) {
        // API 102 限制：不能 hook 抽象方法（Context/ContextWrapper 的 startActivity 等是 abstract）
        // 使用 runCatching 包裹，失败时静默跳过而非中断整个 handleHook
        runCatching {
            session.findAndHook(Context::class.java.name, "startActivity", Intent::class.java) { chain ->
                handleStartActivityHook(chain)
            }
        }
        runCatching {
            session.findAndHook(Context::class.java.name, "startActivity", Intent::class.java, Bundle::class.java) { chain ->
                handleStartActivityHook(chain)
            }
        }
        runCatching {
            session.findAndHook(Context::class.java.name, "startService", Intent::class.java) { chain ->
                handleStartServiceHook(chain)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                session.findAndHook(Context::class.java.name, "startForegroundService", Intent::class.java) { chain ->
                    handleStartServiceHook(chain)
                }
            }
        }
        runCatching {
            session.findAndHook(Context::class.java.name, "bindService", Intent::class.java, Service::class.java, Int::class.javaPrimitiveType!!) { chain ->
                handleStartServiceHook(chain)
            }
        }
        runCatching {
            session.findAndHook(ContextWrapper::class.java.name, "startActivity", Intent::class.java) { chain ->
                handleStartActivityHook(chain)
            }
        }
        runCatching {
            session.findAndHook(ContextWrapper::class.java.name, "startService", Intent::class.java) { chain ->
                handleStartServiceHook(chain)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                session.findAndHook(ContextWrapper::class.java.name, "startForegroundService", Intent::class.java) { chain ->
                    handleStartServiceHook(chain)
                }
            }
        }
        log("Context/ContextWrapper start hooks installed (abstract method failures suppressed via runCatching)")
    }

    private fun handleStartServiceHook(chain: XposedInterface.Chain): Any? {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return chain.proceed()
        val intent = chain.args.firstOrNull { it is Intent } as? Intent
        if (intent != null) {
            logVoipIntentCandidate("startService/startForegroundService/bindService", intent)
            if (shouldInterceptIntent(intent)) {
                log("intercept startService/startForegroundService/bindService intent=${intent.component} extras=${summarizeExtras(intent)}")
                return false
            }
        }
        return chain.proceed()
    }

    private fun hookInstrumentation(session: HookSession) {
        runCatching {
            session.findAndHook(
                Instrumentation::class.java.name, "execStartActivity",
                Context::class.java, IBinder::class.java, IBinder::class.java,
                Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType!!,
                Bundle::class.java
            ) { chain ->
                val intent = chain.args.getOrNull(4) as? Intent
                if (intent != null && shouldInterceptIntent(intent)) {
                    log("intercept Instrumentation.execStartActivity intent=${intent.component}")
                    null
                } else {
                    chain.proceed()
                }
            }
        }.onFailure { log("Instrumentation hook failed: ${it.message}") }
        log("Instrumentation hook installed")
    }

    private fun hookActivityTaskManager(session: HookSession) {
        val className = "android.app.IActivityTaskManager\$Stub\$Proxy"
        val proxyClass = AppReflect.findClassIfExists(className, session.classLoader)
        if (proxyClass == null) {
            log("IActivityTaskManager proxy not found")
            return
        }
        runCatching {
            // ★ T1 修复：原写法用 Any + 末尾裸 null 触发 findMethodExact 的「按名称+参数数量」退化匹配，
            // 掩盖了真实第 9 个参数 android.app.ProfilingInfo。这里显式给出真实类型并去掉裸 null，
            // 走精确签名匹配，避免退化路径的解析/阻塞风险。
            session.findAndHook(
                className, "startActivity",
                Class.forName("android.app.IApplicationThread"), String::class.java,
                Intent::class.java, String::class.java, IBinder::class.java,
                String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Class.forName("android.app.ProfilingInfo"), Bundle::class.java
            ) { chain ->
                val intent = chain.args.getOrNull(2) as? Intent
                if (intent != null && shouldInterceptIntent(intent)) {
                    log("intercept IActivityTaskManager.startActivity intent=${intent.component}")
                    0
                } else {
                    chain.proceed()
                }
            }
        }.onFailure { log("IActivityTaskManager hook failed: ${it.message}") }
        log("IActivityTaskManager proxy hook installed")
    }

    private fun hookActivityManager(session: HookSession) {
        val className = "android.app.IActivityManager\$Stub\$Proxy"
        val proxyClass = AppReflect.findClassIfExists(className, session.classLoader)
        if (proxyClass == null) {
            log("IActivityManager proxy not found")
            return
        }
        // startActivity
        runCatching {
            session.findAndHook(
                className, "startActivity",
                Class.forName("android.app.IApplicationThread"), String::class.java,
                Intent::class.java, String::class.java, IBinder::class.java,
                String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Class.forName("android.app.ProfilingInfo"), Bundle::class.java
            ) { chain ->
                val intent = chain.args.getOrNull(2) as? Intent
                if (intent != null && shouldInterceptIntent(intent)) {
                    log("intercept IActivityManager.startActivity intent=${intent.component}")
                    0
                } else {
                    chain.proceed()
                }
            }
        }
        // startService
        runCatching {
            session.findAndHook(
                className, "startService",
                Class.forName("android.app.IApplicationThread"), Intent::class.java,
                String::class.java, Boolean::class.javaPrimitiveType!!,
                String::class.java, Int::class.javaPrimitiveType!!
            ) { chain ->
                val intent = chain.args.getOrNull(1) as? Intent
                if (intent != null && shouldInterceptIntent(intent)) {
                    log("intercept IActivityManager.startService intent=${intent.component}")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        // bindService
        runCatching {
            session.findAndHook(
                className, "bindService",
                Class.forName("android.app.IApplicationThread"), IBinder::class.java,
                Intent::class.java, String::class.java,
                Class.forName("android.app.IServiceConnection"),
                Int::class.javaPrimitiveType!!, String::class.java,
                Int::class.javaPrimitiveType!!
            ) { chain ->
                val intent = chain.args.getOrNull(2) as? Intent
                if (intent != null && shouldInterceptIntent(intent)) {
                    log("intercept IActivityManager.bindService intent=${intent.component}")
                    0
                } else {
                    chain.proceed()
                }
            }
        }
        log("IActivityManager proxy hooks installed")
    }

    // endregion

    // region Intent 判断工具

    private fun shouldInterceptIntent(intent: Intent): Boolean {
        val component = intent.component?.className
        if (component != null && isVoipComponent(component)) {
            val isActivity = isVoipActivityComponent(component)
            return interceptForIntent(intent, component, isActivity)
        }
        val action = intent.action
        if (action != null && (action.contains("voip", ignoreCase = true) || action.contains("call", ignoreCase = true))) {
            return interceptForIntent(intent, action, false)
        }
        return false
    }

    private fun interceptForIntent(intent: Intent, source: String, isActivity: Boolean): Boolean {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return false
        val wxid = extractWxidFromIntent(intent)
        val isIncoming = isIncomingCallIntent(intent)
        log("VoIP intent detected source=$source user=$wxid incoming=$isIncoming isActivity=$isActivity")

        if (wxid != null && WXMaskPlugin.containChatUser(wxid)) {
            if (isActivity && !isIncoming && !VoipInterceptionState.isActive()) {
                log("allow established/outgoing VoIP Activity for user=$wxid source=$source")
                return false
            }
            log("intercept VoIP intent for user=$wxid source=$source")
            VoipInterceptionState.startIntercept(wxid)
            return true
        }

        if (VoipInterceptionState.isActive()) {
            val activeUser = VoipInterceptionState.getInterceptedUser()
            if (activeUser != null && WXMaskPlugin.containChatUser(activeUser)) {
                log("intercept VoIP intent during active intercept source=$source isActivity=$isActivity activeUser=$activeUser")
                VoipInterceptionState.refreshIntercept()
                return true
            }
            if (isIncoming) {
                log("intercept incoming VoIP intent during active intercept source=$source")
                return true
            }
        }

        log("allow VoIP intent source=$source user=$wxid incoming=$isIncoming")
        return false
    }

    private fun isVoipActivityComponent(name: String): Boolean {
        return VOIP_ACTIVITY_NAMES.any { name == it || name.startsWith("$it$") }
    }

    private fun isVoipComponent(name: String): Boolean {
        return VOIP_ACTIVITY_NAMES.any { name == it || name.startsWith("$it$") } ||
                VOIP_SERVICE_NAMES.any { name == it || name.startsWith("$it$") } ||
                name.contains("voip", ignoreCase = true) ||
                name.contains("wmpfvoip", ignoreCase = true)
    }

    private fun isIncomingCallIntent(intent: Intent): Boolean {
        val extras = intent.extras?.keySet() ?: return false
        return extras.any { key ->
            val value = intent.extras?.get(key)?.toString() ?: ""
            INCOMING_PATTERNS.any { it.matcher("$key=$value").find() }
        }
    }

    private fun summarizeExtras(intent: Intent): String {
        return runCatching {
            intent.extras?.keySet()?.joinToString(", ") { "$it=${intent.extras?.get(it)}" } ?: "null"
        }.getOrDefault("error")
    }

    // endregion

    // region Layer -1: 真正来电入口

    private fun hookIncomingCallHandler(session: HookSession) {
        try {
            val method = VoipClassResolver.resolveIncomingCallHandlerMethod(session.classLoader) ?: run {
                log("incoming call handler method not resolved")
                return
            }
            log("hookIncomingCallHandler hooking ${method.declaringClass.name}#${method.name}")
            session.hook(method).intercept { chain ->
                if (!hasActiveMaskedUser()) return@intercept chain.proceed()
                val wxid = VoipClassResolver.extractWxidFromRoomInfo(chain.args.getOrNull(0) ?: return@intercept chain.proceed())
                if (wxid != null && WXMaskPlugin.containChatUser(wxid)) {
                    log("intercept incoming call handler for wxid=$wxid")
                    VoipInterceptionState.startIntercept(wxid)
                    return@intercept null
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            log("hookIncomingCallHandler fail: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun hookIncomingCallUi(session: HookSession) {
        try {
            val method = VoipClassResolver.resolveIncomingCallUiMethod(session.classLoader) ?: run {
                log("incoming call UI method not resolved")
                return
            }
            log("hookIncomingCallUi hooking ${method.declaringClass.name}#${method.name}")
            session.hook(method).intercept { chain ->
                if (!hasActiveMaskedUser()) return@intercept chain.proceed()
                val wxid = chain.args.getOrNull(1) as? String
                if (wxid != null && WXMaskPlugin.containChatUser(wxid)) {
                    log("intercept incoming call UI for wxid=$wxid")
                    VoipInterceptionState.startIntercept(wxid)
                    return@intercept null
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            log("hookIncomingCallUi fail: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun hookIncomingCallManager(session: HookSession) {
        try {
            val method = VoipClassResolver.resolveIncomingCallManagerMethod(session.classLoader) ?: run {
                log("incoming call manager method not resolved")
                return
            }
            log("hookIncomingCallManager hooking ${method.declaringClass.name}#${method.name}")
            session.hook(method).intercept { chain ->
                if (!hasActiveMaskedUser()) return@intercept chain.proceed()
                val roomInfo = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                val wxid = VoipClassResolver.extractWxidFromRoomInfo(roomInfo)
                if (wxid != null && WXMaskPlugin.containChatUser(wxid)) {
                    log("intercept incoming call manager for wxid=$wxid")
                    VoipInterceptionState.startIntercept(wxid)
                    return@intercept false
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            log("hookIncomingCallManager fail: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun hookVoipMgrBind(session: HookSession) {
        try {
            val methods = VoipClassResolver.resolveBindVoipForegroundMethods(session.classLoader)
            if (methods.isEmpty()) {
                log("voip mgr bind methods not resolved")
                return
            }
            methods.forEach { method ->
                log("hookVoipMgrBind hooking ${method.declaringClass.name}#${method.name}")
                session.hook(method).intercept { chain ->
                    if (!hasActiveMaskedUser()) return@intercept chain.proceed()
                    val username = chain.args.getOrNull(0) as? String
                    if (username != null && WXMaskPlugin.containChatUser(username)) {
                        log("intercept voip bind for user=$username")
                        VoipInterceptionState.startIntercept(username)
                        return@intercept null
                    }
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            log("hookVoipMgrBind fail: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun hookVoipActivities(session: HookSession) {
        try {
            VOIP_ACTIVITY_NAMES.forEach { activityName ->
                val activityClass = AppReflect.findClassIfExists(activityName, session.classLoader) ?: return@forEach
                log("hookVoipActivities: hooking $activityName")
                // Activity.onCreate(Bundle) 带参且在父类声明，无参签名查不到 → 必须用带参查找
                runCatching {
                    session.findAndHook(activityName, "onCreate", Bundle::class.java) { chain ->
                        handleVoipActivityLifecycle(chain, activityName, "onCreate")
                    }
                }.onFailure { log("hookVoipActivities onCreate fail $activityName: ${it.message}") }
                // singleTask 已存在实例提前台只触发 onResume/onNewIntent，不触发 onCreate
                runCatching {
                    session.findAndHookNoArgs(activityName, "onResume") { chain ->
                        handleVoipActivityLifecycle(chain, activityName, "onResume")
                    }
                }.onFailure { log("hookVoipActivities onResume fail $activityName: ${it.message}") }
                runCatching {
                    session.findAndHook(activityName, "onNewIntent", Intent::class.java) { chain ->
                        handleVoipActivityLifecycle(chain, activityName, "onNewIntent")
                    }
                }.onFailure { log("hookVoipActivities onNewIntent fail $activityName: ${it.message}") }
            }
        } catch (e: Throwable) {
            log("hookVoipActivities fail: ${e.javaClass.name}: ${e.message}")
        }
    }

    // 仅这些是真正的「来电界面」，拦截时 finish() 隐藏；其余（MMSuperAlert 通话结束摘要、
    // InviteRemindDialog、VoipFloatCardPermissionDialog）为良性弹窗，finish 会误杀用户正常导航
    // （如从桌面点开微信被弹回桌面，且形成 finish→重弹→再 finish 死循环），故只放行，
    // 其 voip 文案由 WindowManager 钩子隐藏。
    private val VOIP_FINISH_ACTIVITIES = setOf(
        "com.tencent.mm.plugin.voip.ui.VideoActivity",
        "com.tencent.mm.plugin.appbrand.wmpfvoip.notify.ui.WmpfVoipCallInProxyActivity"
    )

    // 通话是否仍活跃：VoIP 前台服务 onStartCommand(密友) 置 true，onDestroy 置 false。
    // 用于决定 onResume/onNewIntent 是否 finish 来电 Activity——通话已结束时残留 Activity 被顶到前台
    // 不应再 finish（否则把用户从微信弹回桌面，第二次点击才正常）。
    @Volatile
    private var voipCallLive: Boolean = false

    private fun handleVoipActivityLifecycle(chain: XposedInterface.Chain, activityName: String, stage: String): Any? {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return chain.proceed()
        val activity = chain.thisObject as? android.app.Activity
        val intent = activity?.intent
        val wxid = intent?.let { extractWxidFromIntent(it) }
        val isIncoming = intent?.let { isIncomingCallIntent(it) } == true
        if (shouldBlockVoipActivity(wxid, isIncoming)) {
            val target = wxid ?: VoipInterceptionState.getInterceptedUser()
            log("block VoIP activity $activityName stage=$stage user=$target incoming=$isIncoming")
            if (wxid != null) VoipInterceptionState.startIntercept(wxid)
            return if (activityName in VOIP_FINISH_ACTIVITIES) {
                val result = chain.proceed()
                // 仅「初始创建(onCreate)且窗口活跃」或「通话仍活跃(voipCallLive)」时 finish 隐藏来电界面；
                // 通话已结束后残留 Activity 被顶到前台(onResume/onNewIntent)时不再 finish，
                // 否则会把用户从微信弹回桌面（第二次点击才正常进入的元凶）。
                val shouldFinish = (stage == "onCreate" && VoipInterceptionState.isActive()) || voipCallLive
                if (shouldFinish) activity?.finish()
                result
            } else {
                chain.proceed()
            }
        }
        return chain.proceed()
    }

    private fun shouldBlockVoipActivity(wxid: String?, isIncoming: Boolean): Boolean {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return false
        // 活跃拦截中（上游已识别为密友来电）→ 兜底拦截任何密友 VoIP Activity（含 singleTask 提前台场景）
        val activeUser = VoipInterceptionState.getInterceptedUser()
        if (activeUser != null && WXMaskPlugin.containChatUser(activeUser)) return true
        // intent 中取到密友 wxid，仅当为「来电」才拦截，避免误杀用户主动拨出
        if (wxid != null && WXMaskPlugin.containChatUser(wxid)) return isIncoming
        return false
    }

    private fun hookFlutterGlobal(session: HookSession) {
        try {
            // T5: 优先 DexKit 主路径（ObfuscatedClassResolver 内含硬编码兜底）
            val flutterClass = ObfuscatedClassResolver.resolveFlutterVoip(session.classLoader) ?: return
            val method = AppReflect.findMethodsByExactPredicate(flutterClass) { m ->
                m.name.let { it == "a" || it == "b" } && m.parameterTypes.size >= 1
            }.firstOrNull() ?: return
            log("hookFlutterGlobal hooking ${flutterClass.name}#${method.name}")
            session.hook(method).intercept { chain ->
                if (!hasActiveMaskedUser()) return@intercept chain.proceed()
                val intent = chain.args.firstOrNull { it is android.content.Intent } as? android.content.Intent
                if (intent != null && shouldInterceptIntent(intent)) {
                    log("intercept Flutter global for VOIP intent")
                    return@intercept null
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            log("hookFlutterGlobal fail: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun hasActiveMaskedUser(): Boolean {
        return ConfigUtil.isMasterEnabled() && (VoipInterceptionState.isActive() || ConfigUtil.getMaskList().isNotEmpty())
    }

    // endregion

    // region Layer 1: Service.startForeground + VoIP Service onStartCommand

    private fun hookServiceStartForeground(session: HookSession) {
        runCatching {
            session.findAndHook(
                Service::class.java.name, "startForeground",
                Int::class.javaPrimitiveType!!, Notification::class.java
            ) { chain ->
                val notification = chain.args.getOrNull(1) as? Notification
                if (notification != null && VoipInterceptionState.isActive() && isVoipNotification(notification)) {
                    log("intercept Service.startForeground")
                    null
                } else {
                    chain.proceed()
                }
            }
        }.onFailure { log("Service.startForeground hook failed: ${it.message}") }
        log("Service.startForeground hook installed")
    }

    private fun hookVoipServices(session: HookSession) {
        VOIP_SERVICE_NAMES.forEach { serviceName ->
            val serviceClass = AppReflect.findClassIfExists(serviceName, session.classLoader)
            if (serviceClass == null) {
                log("service class not found: $serviceName")
                return@forEach
            }
            runCatching {
                session.findAndHook(
                    serviceName, "onStartCommand",
                    Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
                ) { chain ->
                    val intent = chain.args.getOrNull(0) as? Intent
                    val userInIntent = intent?.getStringExtra("Voip_User")
                    log("VoIP service onStartCommand $serviceName user=$userInIntent active=${VoipInterceptionState.isActive()}")
                    voipCallLive = false

                    if (userInIntent != null && WXMaskPlugin.containChatUser(userInIntent)) {
                        voipCallLive = true
                        log("intercept user=$userInIntent at $serviceName onStartCommand")
                        VoipInterceptionState.startIntercept(userInIntent)
                        runCatching {
                            val svc = chain.thisObject as? Service
                            svc?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        }
                    }
                    chain.proceed()
                }
            }.onFailure { log("VoIP service onStartCommand hook failed: ${it.message}") }
            // 通话服务销毁 = 通话结束：标记非活跃，并续期拦截窗口以覆盖「挂断反馈音」(常晚于窗口播放)
            runCatching {
                // ★ T1 修复：onDestroy() 为无参方法，裸 null 会被解析成 [null]（长度1）误查「1 个参数的 onDestroy」，
                // 改用 findAndHookNoArgs 显式无参签名。
                session.findAndHookNoArgs(serviceName, "onDestroy") { chain ->
                    voipCallLive = false
                    VoipInterceptionState.refreshIntercept()
                    log("VoIP service onDestroy $serviceName")
                    chain.proceed()
                }
            }
        }
    }

    // endregion

    // region Layer 2: 通知兜底

    private fun hookNotifications(session: HookSession) {
        runCatching {
            session.findAndHook(
                "android.app.NotificationManager", "notify",
                String::class.java, Int::class.javaPrimitiveType!!, Notification::class.java
            ) { chain ->
                handleNotificationHook(chain)
            }
        }.onFailure { log("NotificationManager.notify(String) hook failed: ${it.message}") }
        runCatching {
            session.findAndHook(
                "android.app.NotificationManager", "notify",
                Int::class.javaPrimitiveType!!, Notification::class.java
            ) { chain ->
                handleNotificationHook(chain)
            }
        }.onFailure { log("NotificationManager.notify(int) hook failed: ${it.message}") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                session.findAndHook(
                    "android.app.NotificationManager", "notifyAsPackage",
                    String::class.java, String::class.java, Int::class.javaPrimitiveType!!, Notification::class.java
                ) { chain ->
                    handleNotificationHook(chain)
                }
            }.onFailure { log("NotificationManager.notifyAsPackage hook failed: ${it.message}") }
        }
    }

    private fun handleNotificationHook(chain: XposedInterface.Chain): Any? {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return chain.proceed()
        val notification = chain.args.lastOrNull() as? Notification ?: return chain.proceed()
        val isVoip = isVoipNotification(notification)
        val texts = extractNotificationTexts(notification)
        val wxidInText = texts.firstNotNullOfOrNull { extractWxid(it) }
        log("NotificationManager.notify active=${VoipInterceptionState.isActive()} isVoip=$isVoip texts=$texts wxidInText=$wxidInText")

        val shouldSuppress = when {
            VoipInterceptionState.isActive() && isVoip -> true
            wxidInText != null && WXMaskPlugin.containChatUser(wxidInText) -> {
                log("intercept notification containing blacklisted user=$wxidInText")
                VoipInterceptionState.startIntercept(wxidInText)
                true
            }
            else -> false
        }

        if (shouldSuppress) {
            log("suppress VoIP notification")
            VoipInterceptionState.refreshIntercept()
            return null
        }
        return chain.proceed()
    }

    private fun isVoipNotification(notification: Notification): Boolean {
        if (notification.channelId?.contains("voip", ignoreCase = true) == true) return true
        if (notification.category == Notification.CATEGORY_CALL) return true
        if (notification.fullScreenIntent != null) return true
        val texts = extractNotificationTexts(notification)
        return texts.any { text ->
            VOIP_NOTIFICATION_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
        }
    }

    private fun extractNotificationTexts(notification: Notification): List<String> {
        return listOfNotNull(
            notification.tickerText?.toString(),
            notification.extras?.getString(Notification.EXTRA_TITLE),
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        )
    }

    private fun hookTelecomManager(session: HookSession) {
        runCatching {
            session.findAndHook(
                TelecomManager::class.java.name, "addNewIncomingCall",
                PhoneAccountHandle::class.java, Bundle::class.java
            ) { chain ->
                val extras = chain.args.getOrNull(1) as? Bundle
                val extraSummary = extras?.keySet()?.joinToString(", ") { "$it=${extras.get(it)}" } ?: "null"
                log("TelecomManager.addNewIncomingCall extras={$extraSummary}")

                val wxid = extractWxidFromBundle(extras)
                    ?: VoipInterceptionState.getInterceptedUser()?.takeIf { WXMaskPlugin.containChatUser(it) }

                if (wxid != null && WXMaskPlugin.containChatUser(wxid)) {
                    log("intercept TelecomManager.addNewIncomingCall for blacklisted user=$wxid")
                    VoipInterceptionState.startIntercept(wxid)
                    null
                } else {
                    chain.proceed()
                }
            }
            log("TelecomManager hook installed")
        }.onFailure { log("TelecomManager hook failed: ${it.message}") }
    }

    // endregion

    // region Layer 4: Activity / Flutter / WindowManager 兜底

    private fun hookWindowManagerImpl(session: HookSession) {
        runCatching {
            session.findAndHook(
                "android.view.WindowManagerImpl", "addView",
                View::class.java, ViewGroup.LayoutParams::class.java
            ) { chain ->
                handleWindowAddView(chain)
            }
        }.onFailure { log("WindowManagerImpl.addView hook failed: ${it.message}") }
        log("WindowManagerImpl addView hook installed")
    }

    private fun hookWindowManagerAddView(session: HookSession) {
        runCatching {
            session.findAndHook(
                "android.view.WindowManagerGlobal", "addView",
                View::class.java, ViewGroup.LayoutParams::class.java, Display::class.java, Window::class.java
            ) { chain ->
                handleWindowAddView(chain)
            }
        }.onFailure { log("WindowManagerGlobal.addView hook failed: ${it.message}") }
        log("WindowManager addView hook installed")
    }

    private fun handleWindowAddView(chain: XposedInterface.Chain): Any? {
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().blockVoipCall) return chain.proceed()
        val view = chain.args.getOrNull(0) as? View ?: return chain.proceed()
        val lp = chain.args.getOrNull(1) as? WindowManager.LayoutParams ?: return chain.proceed()
        // ★ 快速放行：先检查轻量条件，避免无关窗口的文本扫描
        val isVoipByClass = isVoipViewClass(view)
        val isVoipByTitle = lp.title?.toString()?.contains("VoIP", ignoreCase = true) == true ||
                lp.title?.toString()?.contains("call", ignoreCase = true) == true
        val isVoipByType = lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY ||
                lp.type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT ||
                lp.type == WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        if (!isVoipByClass && !isVoipByTitle && !isVoipByType) {
            // 普通窗口，快速放行（优化：跳过文本扫描）
            if (BuildConfig.DEBUG) fastPassCount++
            if (BuildConfig.DEBUG && fastPassCount % 100 == 0) {
                log("[PERF] fastPass=$fastPassCount scan=$scanCount (${(fastPassCount*100.0/(fastPassCount+scanCount+1)).toInt()}%)")
            }
            return chain.proceed()
        }
        if (BuildConfig.DEBUG) scanCount++

        val text = extractViewTexts(view).joinToString(" ")
        val isVoipView = isVoipByClass || isVoipByTitle ||
                VOIP_NOTIFICATION_KEYWORDS.any { text.contains(it, ignoreCase = true) }

        if (VoipInterceptionState.isActive()) {
            log("WindowManager addView active title=${lp.title} type=${lp.type} viewClass=${view.javaClass.name} text=$text isVoipView=$isVoipView")
        }

        if (!isVoipView) return chain.proceed()

        log("WindowManager addView candidate title=${lp.title} type=${lp.type} viewClass=${view.javaClass.name} text=$text")

        val wxidInText = extractWxid(text)
        val activeUser = VoipInterceptionState.getInterceptedUser()
        val shouldSuppress = when {
            VoipInterceptionState.isActive() && activeUser != null -> {
                log("WindowManager addView suppressed for active intercept user=$activeUser text=$text")
                true
            }
            wxidInText != null && WXMaskPlugin.containChatUser(wxidInText) -> {
                log("WindowManager addView suppressed for blacklisted user=$wxidInText text=$text")
                VoipInterceptionState.startIntercept(wxidInText)
                true
            }
            else -> false
        }

        if (shouldSuppress) return null
        return chain.proceed()
    }

    private fun hookDialogAndPopup(session: HookSession) {
        runCatching {
            // ★ T1 修复（根因重点）：Dialog.show() 为无参方法，原裸 null 会被解析成 [null]（长度 1），
            // 误查「1 个参数的 show」，而 Dialog.show() 无参 → method not found；且裸 null 触发
            // AppReflect.findMethodExact 的退化匹配路径，存在底层解析/阻塞风险（真机 init 挂起疑似与此相关）。
            // 改用 findAndHookNoArgs 显式无参签名。
            session.findAndHookNoArgs(
                android.app.Dialog::class.java.name, "show"
            ) { chain ->
                val dialog = chain.thisObject as? android.app.Dialog ?: return@findAndHookNoArgs chain.proceed()
                val text = extractViewTexts(dialog.window?.decorView ?: return@findAndHookNoArgs chain.proceed()).joinToString(" ")
                val isVoip = VOIP_NOTIFICATION_KEYWORDS.any { text.contains(it, ignoreCase = true) }
                if (!isVoip) return@findAndHookNoArgs chain.proceed()
                val activeUser = VoipInterceptionState.getInterceptedUser()
                if (VoipInterceptionState.isActive() && activeUser != null) {
                    log("Dialog.show suppressed for active intercept user=$activeUser text=$text")
                    null
                } else {
                    chain.proceed()
                }
            }
        }.onFailure { log("Dialog.show hook failed: ${it.message}") }
        runCatching {
            // ★ T1 修复：PopupWindow.showAtLocation(View, int, int, int) 为 4 参，禁止末尾补裸 null。
            session.findAndHook(
                android.widget.PopupWindow::class.java.name, "showAtLocation",
                View::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            ) { chain ->
                val popup = chain.thisObject as? android.widget.PopupWindow ?: return@findAndHook chain.proceed()
                val content = popup.contentView ?: return@findAndHook chain.proceed()
                val text = extractViewTexts(content).joinToString(" ")
                val isVoip = VOIP_NOTIFICATION_KEYWORDS.any { text.contains(it, ignoreCase = true) }
                if (!isVoip) return@findAndHook chain.proceed()
                if (VoipInterceptionState.isActive()) {
                    log("PopupWindow.showAtLocation suppressed text=$text")
                    null
                } else {
                    chain.proceed()
                }
            }
        }.onFailure { log("PopupWindow.showAtLocation hook failed: ${it.message}") }
        log("Dialog/PopupWindow hooks installed")
    }

    private fun extractViewTexts(view: View): List<String> {
        val result = ArrayList<String>()
        runCatching { extractViewTextsRecursive(view, result) }
        return result
    }

    private fun extractViewTextsRecursive(view: View?, out: ArrayList<String>) {
        if (view == null) return
        when (view) {
            is android.widget.TextView -> out.add(view.text.toString())
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    extractViewTextsRecursive(view.getChildAt(i), out)
                }
            }
        }
    }

    // endregion

    // region Layer 5: 音频振动兜底

    private fun hookAudioAndVibration(session: HookSession) {
        // 注意：无参方法必须用 findAndHookNoArgs。AppReflect.findMethodExact 在形参含 null 通配时
        // 会退化成「按方法名+参数数量」匹配；若写 findAndHook(X, "start", null)，Kotlin 会把单个 null
        // 解析成 [null]（长度 1），于是去查「1 个参数的 start」而 start()/play() 是无参方法，永远 method not found，
        // 钩子静默装不上（前几轮挂断声漏拦的根因）。带参方法则传真实类型、不要末尾补 null。
        // MediaPlayer.start (no-arg)
        runCatching {
            session.findAndHookNoArgs(MediaPlayer::class.java.name, "start") { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress MediaPlayer.start")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        // SoundPool.play (int,float,float,int,int,float)
        runCatching {
            session.findAndHook(
                SoundPool::class.java.name, "play",
                Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!
            ) { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress SoundPool.play")
                    0
                } else {
                    chain.proceed()
                }
            }
        }
        // Ringtone.play (no-arg)
        runCatching {
            session.findAndHookNoArgs(Ringtone::class.java.name, "play") { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress Ringtone.play")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        // AudioTrack.play / start (no-arg)：挂断/拒接反馈音由微信原生音频层经 start() 播放
        // （ToneGenerator 内部亦是 new AudioTrack().start()）。两者在「拦截窗口内 或 宽限期内」压制。
        runCatching {
            session.findAndHookNoArgs(AudioTrack::class.java.name, "play") { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress AudioTrack.play")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        runCatching {
            session.findAndHookNoArgs(AudioTrack::class.java.name, "start") { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress AudioTrack.start")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        // ToneGenerator.startTone：拒接/挂断反馈 beep 常经 ToneGenerator 播放，走 VOICE_CALL/DTMF 通道，直接拦截最稳。
        runCatching {
            session.findAndHook(ToneGenerator::class.java.name, "startTone", Int::class.javaPrimitiveType!!) { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress ToneGenerator.startTone(int)")
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        runCatching {
            session.findAndHook(
                ToneGenerator::class.java.name, "startTone",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            ) { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress ToneGenerator.startTone(int,int)")
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        // Vibrator.vibrate
        val vibratorClass = Vibrator::class.java
        runCatching {
            session.findAndHook(vibratorClass.name, "vibrate", Long::class.javaPrimitiveType!!) { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress Vibrator.vibrate(long)")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        runCatching {
            session.findAndHook(vibratorClass.name, "vibrate", LongArray::class.java, Int::class.javaPrimitiveType!!) { chain ->
                if (VoipInterceptionState.shouldSuppressAudio()) {
                    log("suppress Vibrator.vibrate(long[], int)")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                session.findAndHook(vibratorClass.name, "vibrate", VibrationEffect::class.java) { chain ->
                    if (VoipInterceptionState.shouldSuppressAudio()) {
                        log("suppress Vibrator.vibrate(VibrationEffect)")
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
        log("Audio/vibration hooks installed")
    }

    // endregion

    // region 工具方法

    private fun logVoipIntentCandidate(source: String, intent: Intent) {
        val component = intent.component?.className ?: return
        if (isVoipComponent(component)) {
            log("VoIP intent candidate source=$source component=$component extras=${summarizeExtras(intent)}")
        }
    }

    private fun isVoipViewClass(view: View): Boolean {
        val name = view.javaClass.name
        return VOIP_VIEW_CLASSES.any { name == it || name.startsWith("$it$") } ||
                name.startsWith("com.tencent.mm.plugin.ball.view.") ||
                name.contains("FloatBall", ignoreCase = true) ||
                name.contains("Voip", ignoreCase = true) ||
                name.contains("voip", ignoreCase = true)
    }

    private fun extractWxidFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val extras = listOfNotNull(
            intent.getStringExtra("Voip_User"),
            intent.getStringExtra("username"),
            intent.getStringExtra("talker"),
            intent.getStringExtra("k_username")
        )
        return extras.firstOrNull { it.startsWith("wxid_") || it.isNotBlank() }
    }

    private fun extractWxidFromBundle(bundle: Bundle?): String? {
        if (bundle == null) return null
        return bundle.keySet().asSequence().mapNotNull { key ->
            when (val value = bundle.get(key)) {
                is String -> value
                is CharSequence -> value.toString()
                else -> null
            }
        }.firstOrNull { it.startsWith("wxid_") || it.isNotBlank() }
    }

    private fun extractWxid(text: String): String? {
        val matcher = Pattern.compile("(wxid_[a-zA-Z0-9_-]+)").matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun log(msg: String) {
        StealthLog.i("[$TAG] $msg")
    }

    // endregion
}
