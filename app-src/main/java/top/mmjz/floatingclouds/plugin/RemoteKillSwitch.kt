package top.mmjz.floatingclouds.plugin

import android.util.Log
import top.mmjz.floatingclouds.BuildConfig
import top.mmjz.floatingclouds.util.LocalKVUtil
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程停运开关 — 全局 + 版本级精确控制。
 *
 * 两层叠加：
 *   优先级 1（最高）：顶层 disabled=true → 所有版本立即停运（紧急刹车）
 *   优先级 2：versions[本机版本].disabled=true → 仅该版本停运
 *   兜底：不禁用
 *
 * JSON 示例：
 *   { "disabled": false, "message": "",
 *     "versions": { "3.0.30.1": { "disabled": true, "message": "该版本存在严重闪退，请更新" } } }
 *
 * ★ 逻辑（每次重启验证一次）：
 *   启动 → 默认正常运行 → 后台联网验证
 *     · 联网成功 + 远程 disabled=true  → 停运 + 写入 SP
 *     · 联网成功 + 远程 disabled=false → 正常运行 + 清除 SP
 *     · 联网失败 + SP 有 disabled=true  → 保持停运（上次被停过）
 *     · 联网失败 + SP 无记录或 false   → 正常运行（fail-open）
 *   停运后每次重启也联网验证，收到恢复指示立即恢复。
 *
 * 集成点：ConfigUtil.isMasterEnabled() = masterEnabled && !isRemoteDisabled()
 */
object RemoteKillSwitch {

    private const val TAG = "fc-remote-killswitch"

    private val CONFIG_URLS = arrayOf(
        "https://raw.githubusercontent.com/mmjzmmww/FloatingClouds/main/kill_switch.json"
    )
    private const val POLL_INTERVAL_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 8_000

    private const val SP_TABLE = "fc_remote_killswitch"
    private const val KEY_DISABLED = "remote_disabled"
    private const val KEY_MESSAGE = "remote_message"
    private const val KEY_LAST_FETCH = "last_fetch_ts"

    /** SP 的 disabled=true 必须在此天数内有成功联网记录才可信，否则视为过期忽略 */
    private const val MAX_STALE_DAYS = 30L

    private val LOCAL_VERSION = BuildConfig.VERSION_NAME

    /** ★ 默认 false → 正常运行（fail-open） */
    @Volatile var remoteDisabled: Boolean = false
        private set
    @Volatile var remoteMessage: String = ""
        private set

    private val started = AtomicBoolean(false)
    @Volatile private var sp: android.content.SharedPreferences? = null

    fun isRemoteDisabled(): Boolean = remoteDisabled

    /**
     * 每次微信重启调用一次。
     * ★ 默认正常运行 → 后台联网验证 → 按远程结果决定。
     * 联网失败时，若 SP 有 disabled=true 则保持停运，否则正常运行。
     */
    fun startMonitoring() {
        if (!started.compareAndSet(false, true)) return

        val ctx = top.mmjz.floatingclouds.util.AppContext.context
        if (ctx != null) {
            sp = LocalKVUtil.getTable(SP_TABLE)
        }

        val thread = Thread({
            // 启动后立即联网验证（重试最多 3 次）
            var succeeded = false
            for (i in 0 until 3) {
                if (fetchOnce()) { succeeded = true; break }
                if (i < 2) try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }

            if (!succeeded) {
                // ★ 联网失败 → 看 SP，但需要时间戳验证新鲜度
                val persistedDisabled = sp?.getBoolean(KEY_DISABLED, false) ?: false
                val lastFetch = sp?.getLong(KEY_LAST_FETCH, 0L) ?: 0L
                val now = System.currentTimeMillis()
                val isStale = lastFetch == 0L || now - lastFetch > MAX_STALE_DAYS * 24 * 3600 * 1000L

                if (persistedDisabled && !isStale) {
                    remoteDisabled = true
                    remoteMessage = sp?.getString(KEY_MESSAGE, "") ?: ""
                    Log.w(TAG, "network failed, SP disabled=true (fetched ${(now - lastFetch) / 86400000}d ago) → keep disabled")
                } else if (persistedDisabled && isStale) {
                    Log.w(TAG, "network failed, SP disabled=true but stale (${(now - lastFetch) / 86400000}d) → ignore, stay running")
                } else {
                    Log.i(TAG, "network failed, SP not disabled → stay running (fail-open)")
                }
            }
            // else: fetchOnce 已根据远程配置更新 remoteDisabled

            // 定时轮询
            while (true) {
                try { Thread.sleep(POLL_INTERVAL_MS) }
                catch (_: InterruptedException) { break }
                fetchOnce()
            }
        }, TAG)
        thread.isDaemon = true
        thread.start()
    }

    private fun persist(disabled: Boolean, message: String) {
        val s = sp ?: return
        try {
            s.edit()
                .putBoolean(KEY_DISABLED, disabled)
                .putString(KEY_MESSAGE, message)
                .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "persisted disabled=$disabled msg=$message")
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    private fun fetchOnce(): Boolean {
        for (url in CONFIG_URLS) {
            if (fetchFrom(url)) return true
        }
        Log.w(TAG, "all ${CONFIG_URLS.size} mirrors failed (keep $remoteDisabled)")
        return false
    }

    private fun fetchFrom(url: String): Boolean {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "Floatingclouds-KillSwitch")
                conn.useCaches = false
                conn.instanceFollowRedirects = true

                if (conn.responseCode != 200) {
                    Log.w(TAG, "HTTP ${conn.responseCode} from $url")
                    return false
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)

                val globalDisabled = json.optBoolean("disabled", false)
                val globalMessage = json.optString("message", "")

                val (newDisabled, newMessage) = resolveState(json, globalDisabled, globalMessage)

                if (newDisabled != remoteDisabled || newMessage != remoteMessage) {
                    remoteDisabled = newDisabled
                    remoteMessage = newMessage
                    persist(newDisabled, newMessage)
                }
                Log.i(TAG, "OK from $url: disabled=$newDisabled (global=$globalDisabled v=$LOCAL_VERSION) msg=$newMessage")
                return true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed from $url: ${e.message}")
            return false
        }
    }

    private fun resolveState(json: org.json.JSONObject, globalDisabled: Boolean, globalMsg: String): Pair<Boolean, String> {
        if (globalDisabled) return true to globalMsg

        val versions = json.optJSONObject("versions")
        if (versions != null) {
            val ver = versions.optJSONObject(LOCAL_VERSION)
            if (ver != null && ver.optBoolean("disabled", false)) {
                val msg = ver.optString("message", "").ifEmpty { globalMsg }
                return true to msg
            }
        }

        return false to ""
    }
}
