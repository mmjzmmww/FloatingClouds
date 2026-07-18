package top.mmjz.floatingclouds.util

import android.os.Environment
import java.io.File

/**
 * 微信侧路径集中解析工具。
 *
 * 所有路径均从 [AppContext.context]（微信进程内由 XposedEntry 设置的微信 Application Context）派生，
 * 而非硬编码 `/data/data/com.tencent.mm`。这样可自动适配多用户（/data/user/10/...）、工作资料、
 * 非默认 data 分区等 ROM，避免 TinkerFix / 诊断 / 热更新屏蔽在异常环境下静默失效。
 *
 * 约定：仅在 Application.attach 之后调用（doTinkerFix 在其后；插件初始化阶段 AppContext 已就绪）。
 * 若 AppContext 在极端注入顺序下为 null，退化为系统默认 data 分区下的微信目录
 * （Environment.getDataDirectory()/data/com.tencent.mm，即 /data/data/com.tencent.mm），避免 NPE。
 */
object WechatPaths {

    /** tinker 相关目录名（与微信 Tinker 部署一致，含 wx 8.0.74 实测的 wc_tinker_dir 变体）。 */
    private val TINKER_DIR_NAMES = listOf("tinker", "tinker_temp", "tinker_server", "wc_tinker_dir")

    /**
     * Tinker 关键路径段（用于 [isTinkerPath] 的"包含即命中"匹配，防 `tinker_odex` / `app_tinker` 等绕过）。
     * 集中管理，可按微信版本扩展（见 [getTinkerKeySegments]）。
     */
    private val TINKER_KEY_SEGMENTS = listOf(
        "tinker",        // tinker / tinker_temp / tinker_server / tinker_odex ...
        "wc_tinker_dir", // 微信 8.0.74 实测补丁目录变体（DefaultTinkerResultService 清理逻辑引用）
        "app_tinker",    // 部分版本补丁目录前缀
        "patch-",        // 补丁目录/文件前缀（patch-<uuid>）
        "hotpatch",      // LegacyTinkerCore 相关目录
        "tinker_patch"
    )

    /**
     * 按微信版本返回 Tinker 关键路径段。
     * 当前各版本统一使用 [TINKER_KEY_SEGMENTS]；如后续版本改目录命名，可在此按 versionCode 覆盖。
     *
     * @param versionCode 微信 versionCode（<=0 表示未知/默认）
     */
    @JvmStatic
    fun getTinkerKeySegments(versionCode: Int = -1): List<String> {
        // 预留：TINKER_KEY_SEGMENTS_BY_VERSION[versionCode] ?: TINKER_KEY_SEGMENTS
        return TINKER_KEY_SEGMENTS
    }

    /**
     * 判断路径是否为 Tinker 相关路径：路径（忽略大小写）**包含**任一关键段即命中。
     * 相比原"精确前缀匹配 tinker/tinker_temp/tinker_server"，放宽到关键段包含，
     * 防 `tinker_odex` / `app_tinker` 等变体绕过（REQ-P2-1 / MEDIUM-5）。
     */
    @JvmStatic
    fun isTinkerPath(path: String): Boolean {
        if (path.isEmpty()) return false
        val lower = path.lowercase()
        return getTinkerKeySegments().any { seg -> lower.contains(seg) }
    }

    /**
     * 微信 data 目录。
     * 正常 = AppContext.context.dataDir（如 /data/data/com.tencent.mm）；
     * 退化 = /data/data/com.tencent.mm（系统默认 data 分区下的微信目录）。
     */
    @JvmStatic
    fun getWechatDataDir(): File {
        val ctx = AppContext.context
        if (ctx != null) {
            return ctx.dataDir
        }
        AppLog.w("WechatPaths: AppContext 未就绪，回退默认 data 分区")
        return File(Environment.getDataDirectory(), "data/com.tencent.mm")
    }

    /** 微信 files 目录。 */
    @JvmStatic
    fun getWechatFilesDir(): File {
        val ctx = AppContext.context
        if (ctx != null) {
            return ctx.filesDir
        }
        AppLog.w("WechatPaths: AppContext 未就绪，回退默认 data 分区")
        return File(Environment.getDataDirectory(), "data/com.tencent.mm/files")
    }

    /** 微信 cache 目录。 */
    @JvmStatic
    fun getWechatCacheDir(): File {
        val ctx = AppContext.context
        if (ctx != null) {
            return ctx.cacheDir
        }
        AppLog.w("WechatPaths: AppContext 未就绪，回退默认 data 分区")
        return File(Environment.getDataDirectory(), "data/com.tencent.mm/cache")
    }

    /**
     * 全部 Tinker 相关目录（tinker / tinker_temp / tinker_server），位于微信 data 目录下。
     * 顺序与微信 Tinker 部署一致，供删除 / 前缀匹配 / 诊断遍历使用。
     */
    @JvmStatic
    fun getTinkerDirs(): List<File> {
        val base = getWechatDataDir()
        return TINKER_DIR_NAMES.map { File(base, it) }
    }
}
