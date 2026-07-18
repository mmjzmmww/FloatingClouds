package top.mmjz.floatingclouds.util

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlin.math.max
import top.mmjz.floatingclouds.util.AppContext
import top.mmjz.floatingclouds.util.StealthLog

/**
 * VoIP 拦截状态机。
 * 在检测到需要拦截的通话后设置一个时间窗口，期间所有相关副作用（通知、前台服务、Activity）都会被抑制。
 *
 * 补充说明：来电铃声由微信在来电流程极早期通过 Java MediaPlayer/AudioTrack 播放，
 * 而拦截状态(startIntercept)通常由 VoIP 前台服务意图在铃声开始之后才触发，
 * 导致基于 isActive() 的 Java 音频 Hook 总是晚于铃声、漏拦。
 * 因此这里在激活拦截的同时直接静音铃声相关音频流，
 * 不依赖具体音频 API / 流类型 / 触发时机，已响起的铃声也会被立即掐断。
 */
object VoipInterceptionState {

    // 拦截窗口 60 秒，覆盖「来电 → 用户进微信 → 挂断」整段生命周期；微信来电邀请约 30-60s 自动超时
    private const val DEFAULT_TTL_MS = 60000L

    // 音频静音宽限期：窗口结束后额外保留静音/压制一段时间。
    // 挂断反馈音(「咚」)常在窗口结束后才播放（实测晚于最后一笔 voip 信号 1~60s），若窗口一结束就恢复静音，
    // 挂断声会漏出。宽限期内继续压制音频（同时 STREAM_RING / STREAM_VOICE_CALL 静音保留到宽限期）。
    private const val AUDIO_GRACE_MS = 60000L

    @Volatile
    private var activeUntil: Long = 0

    @Volatile
    private var interceptedUser: String? = null

    @Volatile
    private var muted: Boolean = false

    // STREAM_VOICE_CALL 音量兜底：setStreamMute 对语音通话流常无效（特权流），改用 setStreamVolume(0)
    @Volatile
    private var savedVoiceCallVolume: Int = -1

    // 音频静音宽限截止时刻；窗口(audioGraceUntil 之前)结束后仍保留静音直到此刻，覆盖挂断反馈音
    @Volatile
    private var audioGraceUntil: Long = 0

    // 需要静音的音频流：来电铃声(RING)、通知音(NOTIFICATION)、微信自定义铃声常用(MUSIC)
    // 注：挂断/拒接反馈音走 STREAM_RING/STREAM_VOICE_CALL 通道，其中 STREAM_RING 由本数组 setStreamMute 静音；
    // STREAM_VOICE_CALL 因特权流 setStreamMute 常无效，改用 setStreamVolume(0)（见 muteRingtoneStreams），
    // 二者均保留静音到 audioGraceUntil（窗口结束后的宽限期），覆盖「挂断咚」晚于窗口播放的场景。
    private val MUTED_STREAMS = intArrayOf(
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_MUSIC
    )

    @JvmStatic
    fun startIntercept(user: String, ttlMs: Long = DEFAULT_TTL_MS) {
        interceptedUser = user
        val now = SystemClock.uptimeMillis()
        activeUntil = now + ttlMs
        audioGraceUntil = activeUntil + AUDIO_GRACE_MS
        muteRingtoneStreams()
    }

    @JvmStatic
    fun refreshIntercept(ttlMs: Long = DEFAULT_TTL_MS) {
        interceptedUser?.let {
            val now = SystemClock.uptimeMillis()
            activeUntil = now + ttlMs
            audioGraceUntil = max(audioGraceUntil, activeUntil + AUDIO_GRACE_MS)
        }
    }

    @JvmStatic
    fun isActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now > activeUntil) {
            interceptedUser = null
            // 窗口结束，但音频静音保留到 audioGraceUntil（挂断反馈音常在窗口结束后才播放）
            maybeRestoreAudioMute()
            return false
        }
        return true
    }

    /**
     * 音频播放钩子调用：窗口内 或 窗口结束后宽限期内，均应抑制 voip 音频。
     * 宽限期让「挂断咚」这类晚于窗口播放的声音被压制（同时 STREAM_RING / STREAM_VOICE_CALL 静音也保留到宽限期）。
     */
    @JvmStatic
    fun shouldSuppressAudio(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now <= activeUntil) return true
        if (now <= audioGraceUntil) {
            return true
        }
        maybeRestoreAudioMute()
        return false
    }

    @JvmStatic
    fun getInterceptedUser(): String? = if (isActive()) interceptedUser else null

    @JvmStatic
    fun clear() {
        activeUntil = 0
        audioGraceUntil = 0
        interceptedUser = null
        restoreRingtoneStreams()
    }

    // 窗口与宽限期都结束后才恢复音频流静音；否则继续保留（覆盖挂断反馈音）
    private fun maybeRestoreAudioMute() {
        val now = SystemClock.uptimeMillis()
        if (muted && now > audioGraceUntil) {
            restoreRingtoneStreams()
        }
    }

    private fun muteRingtoneStreams() {
        if (muted) return
        val ctx = AppContext.context ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // 来电铃声(RING)/通知(NOTIFICATION)/音乐(MUSIC)：setStreamMute 即可，逐条 try 互不阻断
        for (stream in MUTED_STREAMS) {
            try {
                am.setStreamMute(stream, true)
            } catch (e: Throwable) {
                log("mute stream $stream failed: ${e.message}")
            }
        }
        // 挂断/拒接提示音带 usage=USAGE_VOICE_COMMUNICATION → 路由到 STREAM_VOICE_CALL 音量通道。
        // setStreamMute 对该特权流通常无效，改用 setStreamVolume(0) 更可靠（实测 AudioTrack usage=6 / stream=2）。
        try {
            savedVoiceCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (savedVoiceCallVolume != 0) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                log("muted VOICE_CALL volume ($savedVoiceCallVolume -> 0)")
            }
        } catch (e: Throwable) {
            log("mute VOICE_CALL volume failed: ${e.message}")
        }
        muted = true
    }

    private fun restoreRingtoneStreams() {
        if (!muted) return
        val ctx = AppContext.context ?: run { muted = false; return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run { muted = false; return }
        for (stream in MUTED_STREAMS) {
            try {
                am.setStreamMute(stream, false)
            } catch (e: Throwable) {
                log("unmute stream $stream failed: ${e.message}")
            }
        }
        try {
            if (savedVoiceCallVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0)
                log("restored VOICE_CALL volume -> $savedVoiceCallVolume")
                savedVoiceCallVolume = -1
            }
        } catch (e: Throwable) {
            log("restore VOICE_CALL volume failed: ${e.message}")
        }
        muted = false
    }

    private fun log(msg: String) {
        StealthLog.i("[VoipInterceptionState] $msg")
    }
}
