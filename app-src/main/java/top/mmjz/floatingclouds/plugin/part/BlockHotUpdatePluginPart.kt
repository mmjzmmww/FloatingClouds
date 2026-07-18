package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.hotupdate.TinkerHotUpdateBlocker
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 屏蔽微信热更新 —— 诊断专用 Part（L0–L4 的 Hook 注册已迁移至 [TinkerHotUpdateBlocker]）。
 *
 * 该 Part 在 `WXMaskPlugin` 内被调用（主进程经 AppContext 就绪后的插件初始化；`:patch` 进程
 * 按 NEW-9 收敛后仅保留本诊断 Part）。其职责仅为：调用 [TinkerHotUpdateBlocker.diagnose]
 * 输出 L0–L4 的"类存在 / 已 Hook / 已拦截"三态诊断，消除原头部 "✅ 类存在" 的误导（LOW-8）。
 *
 * 拦截层实际注册时机：在 `XposedEntry.initWeChatHooks` 创建 `HookSession` **之后、
 * `Application.attach` 的 `chain.proceed()` 之前**由 [TinkerHotUpdateBlocker.install] 同步完成
 * （详见 docs/system_design.md）。本 Part 不再承担注册职责，避免注册晚于首次 `tryLoad`。
 *
 * 层级 | 目标 | 说明
 * ─────┼─────────────────────────────────────────┼──────────
 * L0   | File.mkdirs / mkdir / createNewFile      | 文件系统层
 * L1   | ShareSecurityCheck.verifyPatchMetaSignature | 签名层
 * L2   | TinkerLoader.tryLoad                     | 核心加载层（决定性）
 * L3   | TinkerApplication.onBaseContextAttached  | 入口层（放行）
 * L4   | LegacyTinkerCore$PatchService            | 合成层（:patch 进程）
 */
class BlockHotUpdatePluginPart : IPlugin {

    companion object {
        private const val TAG = "BlockHotUpdate"
    }

    override fun handleHook(session: HookSession) {
        // ★ 仅委托诊断：逐层输出"类存在 / 已 Hook / 已拦截"三态，由 TinkerHotUpdateBlocker 上报。
        runCatching {
            TinkerHotUpdateBlocker.diagnose(session.classLoader, session)
        }.onFailure {
            StealthLog.e("[$TAG] diagnose FAILED", it)
        }
        StealthLog.i("[$TAG] diagnostic Part DONE")
    }
}
