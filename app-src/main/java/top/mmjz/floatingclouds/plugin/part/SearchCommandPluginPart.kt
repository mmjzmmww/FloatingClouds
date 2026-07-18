package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Handler
import android.os.Looper
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.part.GlobalLifecycleHook
import top.mmjz.floatingclouds.plugin.ui.MaskManagerCenterUI
import top.mmjz.floatingclouds.util.ConfigUtil

/**
 * 搜索指令功能（仅主页顶部搜索框）：
 *  - 微信主页顶部搜索框（FTSBaseMainUI，点主页搜索打开的全屏搜索）输入指令：
 *      cmdOpenSettings（默认 #jz#）  → 打开插件设置
 *      cmdTempUnhide （默认 #mm#）   → 临时解除隐藏
 * 两条指令均可在插件设置「搜索指令」分区自定义。
 * （好友对话输入框的 #mmjz# 加密友指令已移除，见 3.0.117）
 */
class SearchCommandPluginPart : IPlugin {

    companion object {
        private const val TAG = "SCP"
    }

    override fun handleHook(session: HookSession) {
        hookHomeSearch(session)
    }

    /** 主页顶部搜索框：FTSBaseMainUI.P4(String) 即逐字搜索回调 */
    private fun hookHomeSearch(session: HookSession) {
        runCatching {
            session.findAndHook(
                "com.tencent.mm.plugin.fts.ui.FTSBaseMainUI",
                "P4",
                String::class.java
            ) { chain ->
                val act = chain.thisObject as? Activity
                val query = (chain.args[0] as? String) ?: ""
                chain.proceed()
                handleSearchCommand(act, query)
            }
            android.util.Log.i(TAG, "home search P4 hooked")
        }.onFailure {
            android.util.Log.w(TAG, "home search P4 hook failed: ${it.message}")
        }
    }

    private fun handleSearchCommand(act: Activity?, query: String) {
        if (!ConfigUtil.isMasterEnabled()) return
        val opt = ConfigUtil.getOptionData()
        when (query) {
            opt.cmdOpenSettings -> {
                act ?: return
                act.finish()
                Handler(Looper.getMainLooper()).postDelayed({
                    val home = GlobalLifecycleHook.instance?.currentActivity
                    home?.let { h ->
                        runCatching { MaskManagerCenterUI(h).show() }
                    }
                }, 250)
            }
            opt.cmdTempUnhide -> {
                HideMainUIListPluginPart.triggerTempUnhideViaCommand()
                act?.finish()
            }
        }
    }
}
