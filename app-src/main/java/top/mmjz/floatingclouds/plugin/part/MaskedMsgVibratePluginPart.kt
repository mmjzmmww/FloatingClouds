package top.mmjz.floatingclouds.plugin.part

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppContext
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 隐藏密友消息震动反馈。
 *
 * 设计原则（与微信免打扰「互补、不冲突」）：
 * - 隐藏密友时，插件已通过 WXMaskPlugin.setConversationMute 将密友设为微信原生「消息免打扰」，
 *   微信自身不会响铃/震动/弹通知。本 Part 在「密友确实被隐藏」时补一次短震，弥补免打扰静默的缺点。
 * - 仅当密友处于隐藏/免打扰状态（微信不会自己震）才震；若密友未被隐藏（微信会自己震），则跳过，
 *   避免与微信原生震动「双重震动」。
 * - 只震「收到」的消息（isSend==0），自己发出的消息不震。
 *
 * Hook 点（微信 8.0.74 实证）：
 * - MsgInfoStorage = com.tencent.mm.storage.g9
 * - 真正落库方法 = M9(Lcom/tencent/mm/storage/f9;)J（内部 N9(f9,Z)J → WCDB 写入，返回 msgId）。
 *   微信 m4 插入消息时即调 g9.M9(f9)J（smali 11555），是收消息可靠触发点。
 *   （之前误用 Ba/B8，实测收消息均不触发 → 完全不震动。）
 * - B8(f9)J / Ba(f9)V 仅作兜底，统一走 onMessageInserted，msgId 去重防重复。
 * - 注意：微信用的是 WCDB（Lot0/z2 封装），不是标准 SQLiteDatabase。
 * - 震动方式参考同类插件 InkHide：直接用系统 Vibrator.vibrate，不弹通知、不响铃，
 *   与微信「消息免打扰」天然兼容（仅补一次震动，不破坏免打扰）。
 *
 * 与 IgnoreVoipCallPluginPart 的 Vibrator 抑制互不冲突：后者仅在 VoIP 拦截窗口
 * （VoipInterceptionState.shouldSuppressAudio()）内抑制 Vibrator.vibrate，平时放行。
 */
class MaskedMsgVibratePluginPart : IPlugin {

    companion object {
        private const val TAG = "MaskedMsgVibrate"

        // ══ 微信 8.0.74 混淆名（下个微信版本需重新适配 / 走 DexKit 定位）══
        private const val MSG_STORAGE_CLASS = "com.tencent.mm.storage.g9"
        private const val MSG_STORAGE_INSERT_METHOD = "M9"
        private const val MSG_INFO_CLASS = "com.tencent.mm.storage.f9"

        /** 单次短震时长范围（毫秒）：强度越低时长越短，营造「轻微」观感 */
        private const val VIBRATE_MIN_DURATION_MS = 30L
        private const val VIBRATE_MAX_DURATION_MS = 300L
        /** 同一消息去重窗口（毫秒），避免事务内重复 insert 导致多次震动 */
        private const val DEDUPE_WINDOW_MS = 1500L
        /** 默认震动强度（百分比） */
        private const val DEFAULT_INTENSITY = 60
        /**
         * 闹钟语义的震动属性：用 USAGE_ALARM + CONTENT_TYPE_SONIFICATION。
         * 一加/ColorOS 等 ROM 会拦截「后台应用」对普通 usage 的震动调用（前台能震、后台被静默丢弃）；
         * ALARM usage 被系统视作高优先级提示，后台/锁屏/静音下仍允许触发，
         * 从而让密友消息在微信退到后台时也能震动（参考同类插件 InkHide 实现）。
         * 副作用：手机处于静音模式时也会震动（类似闹钟），属预期行为。
         */
        private val ALARM_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /**
         * 按百分比强度执行一次震动（供收消息补震与 UI 预览共用）。
         * @param pct 强度百分比 0–100；0 映射为最轻组合（最短时长 30ms + 最小振幅 1），
         *            100 映射为最强组合（最长时长 300ms + 最大振幅 255）。
         *            - 振幅走平方曲线，让低端档位更细腻、更轻（低端不会一下子跳到很重）。
         *            - 时长随强度线性缩放，低端极短促 → 整体「轻微」观感。
         *            注：Android 振幅为整数 1–255，1 已是物理下限，不能再低；更轻只能靠缩短时长。
         *            不支持振幅控制的设备（hasAmplitudeControl==false）忽略振幅，但仍按强度缩放时长。
         */
        private fun vibrate(vibrator: Vibrator, pct: Int) {
            val ampPct = pct.coerceIn(0, 100)
            // 时长随强度缩放：低端 30ms（轻微）→ 高端 300ms
            val duration = if (ampPct <= 0) VIBRATE_MIN_DURATION_MS
            else VIBRATE_MIN_DURATION_MS + (VIBRATE_MAX_DURATION_MS - VIBRATE_MIN_DURATION_MS) * ampPct / 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    // 平方曲线：amp ≈ 1 + 254 * (pct/100)^2，低端更轻更细腻
                    val amp = if (ampPct <= 0) 1 else (1 + (254 * ampPct * ampPct / 10000)).coerceIn(1, 255)
                    VibrationEffect.createOneShot(duration, amp)
                } else {
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                // ★ 带 ALARM attributes：绕开厂商对后台应用震动的拦截（保留强度滑块控制）
                vibrator.vibrate(effect, ALARM_ATTRIBUTES)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, duration), -1, ALARM_ATTRIBUTES)
            }
        }

        /** UI 预览：用指定百分比强度震一次（不依赖收到消息），便于用户确认设备振幅是否生效 */
        fun previewVibrate(pct: Int) {
            runCatching {
                val ctx = AppContext.context ?: return
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                vibrate(vibrator, pct)
            }.onFailure { StealthLog.e("[$TAG] preview vibrate fail", it) }
        }
    }

    @Volatile private var talkerField: java.lang.reflect.Field? = null
    @Volatile private var isSendField: java.lang.reflect.Field? = null
    @Volatile private var msgIdField: java.lang.reflect.Field? = null
    private var fieldsResolved = false

    // 去重：同一 msgId 在窗口内只震一次
    private var lastMsgId: Long = -1
    private var lastVibrateTs: Long = 0

    override fun handleHook(session: HookSession) {
        log("handleHook started")
        val cl = session.classLoader

        // 解析 f9 的字段（talker / isSend / msgId），沿类继承链查找，与具体混淆字段名解耦
        resolveFields(cl)

        val msgInfoCls = AppReflect.findClassIfExists(MSG_INFO_CLASS, cl)
        if (msgInfoCls == null) {
            log("MSG_INFO_CLASS ($MSG_INFO_CLASS) not found, skip vibrate hook")
            return
        }
        // 收消息真正落库点是 g9.M9(f9)J（m4 插入即调，内部 N9→WCDB 写库，返回 msgId）。
        // B8/Ba 仅作兜底。三者统一走 onMessageInserted；msgId 去重避免同一消息重复震动。
        for (m in arrayOf(MSG_STORAGE_INSERT_METHOD, "B8", "Ba")) {
            runCatching {
                session.findAndHook(MSG_STORAGE_CLASS, m, msgInfoCls) { chain ->
                    try {
                        onMessageInserted(chain.args.firstOrNull(), cl)
                    } catch (t: Throwable) {
                        StealthLog.e("[$TAG] onMessageInserted fail", t)
                    }
                    chain.proceed()
                }
            }.onFailure {
                log("hook $MSG_STORAGE_CLASS.$m fail: ${it.message}")
            }
        }
        log("hook installed (M9+B8+Ba, talker=${talkerField?.name} isSend=${isSendField?.name} msgId=${msgIdField?.name})")
    }

    /**
     * 沿 f9 的类继承链解析 field_talker / field_isSend / field_msgId。
     * 这三个字段在微信不同版本/基类中声明位置不同，故走继承链扫描而非硬编码单一类。
     */
    private fun resolveFields(cl: ClassLoader) {
        synchronized(this) {
            if (fieldsResolved) return
            val cls = AppReflect.findClassIfExists(MSG_INFO_CLASS, cl) ?: run {
                fieldsResolved = true
                return
            }
            var c: Class<*>? = cls
            while (c != null) {
                for (f in c.declaredFields) {
                    when (f.name) {
                        "field_talker" -> if (talkerField == null) { f.isAccessible = true; talkerField = f }
                        "field_isSend" -> if (isSendField == null) { f.isAccessible = true; isSendField = f }
                        "field_msgId" -> if (msgIdField == null) { f.isAccessible = true; msgIdField = f }
                    }
                }
                c = c.superclass
            }
            fieldsResolved = true
            log("resolveFields done: talker=${talkerField?.name} isSend=${isSendField?.name} msgId=${msgIdField?.name}")
        }
    }

    private fun onMessageInserted(msgObj: Any?, cl: ClassLoader) {
        if (msgObj == null) { log("onInsert skip: msgObj null"); return }
        // 总开关门控（受远程停运开关约束）
        if (!ConfigUtil.isMasterEnabled()) { log("onInsert skip: master disabled"); return }
        val opt = ConfigUtil.getOptionData()
        if (!opt.vibrateOnMaskedMessage) { log("onInsert skip: switch off"); return }

        val talker = (AppReflect.getObjectField(msgObj, "field_talker") as? String)?.takeIf { it.isNotBlank() }
            ?: run { log("onInsert skip: talker empty"); return }
        // 仅对密友消息生效
        if (!WXMaskPlugin.containChatUser(talker)) { log("onInsert skip: not masked ($talker)"); return }
        // 只震「收到」的消息，跳过自己发出的
        if (isSent(msgObj)) { log("onInsert skip: self-sent ($talker)"); return }

        // 软跳过：仅当能可靠判定「微信会自己对该密友震动」(未隐藏 且 未免打扰) 时才跳过，避免双震。
        // isConversationHidden / isConversationMuted 在 helper 未就绪时返回 null，此时视为「未知」→ 补震。
        // 旧逻辑把震动硬绑在 isConversationHidden==true 上，而隐藏回读句柄(hidden)常因 helper 未初始化
        // 返回 null，导致 never 震动；现改为「密友收到消息默认补震，仅明确由微信自身震动才跳过」。
        val hidden = WXMaskPlugin.isConversationHidden(talker, cl)
        val muted = WXMaskPlugin.isConversationMuted(talker, cl)
        val wechatWillVibrate = (hidden == false) && (muted == false)
        if (wechatWillVibrate) { log("onInsert skip: wechat will vibrate itself ($talker)"); return }

        // 去重：同一 msgId 在窗口内只震一次
        val msgId = (AppReflect.getObjectField(msgObj, "field_msgId") as? Long) ?: -1
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (msgId != -1L && msgId == lastMsgId && now - lastVibrateTs < DEDUPE_WINDOW_MS) {
                log("onInsert skip: dedup ($talker msgId=$msgId)")
                return
            }
            lastMsgId = msgId
            lastVibrateTs = now
        }

        log("onInsert TRIGGER vibrate: talker=$talker msgId=$msgId hidden=$hidden muted=$muted")
        doVibrate()
    }

    /** 判断消息是否为「我发出」：field_isSend 可能是 int(0=收到,1=发出) 或 boolean */
    private fun isSent(msgObj: Any?): Boolean {
        val f = isSendField ?: return false
        return runCatching {
            when (val v = f.get(msgObj)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun doVibrate() {
        val ctx = AppContext.context ?: run {
            log("doVibrate skip: AppContext null")
            return
        }
        val pct = ConfigUtil.getOptionData().vibrateIntensity
        runCatching {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrate(vibrator, pct)
        }.onFailure { StealthLog.e("[$TAG] vibrate fail", it) }
    }

    private fun log(msg: String) {
        StealthLog.i("[$TAG] $msg")
    }
}
