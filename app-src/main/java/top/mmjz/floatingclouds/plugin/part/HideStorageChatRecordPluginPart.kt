package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Bundle
import android.view.View
import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 隐藏微信设置 -> 通用 -> 存储空间 页面中的「聊天记录」入口卡片。
 */
class HideStorageChatRecordPluginPart : IPlugin {

    private val CLEAN_NEW_UI_CLASS = "com.tencent.mm.plugin.clean.ui.fileindexui.CleanNewUI"
    private val CHAT_RECORD_CARD_ID_NAME = "ju2"
    // 8.0.70 反编译产物中 R.id.ju2 的十六进制值，作为回退。
    private val HARDCODED_CHAT_RECORD_CARD_ID = 0x7f0947a2

    /** 运行时检查开关，支持即时生效无需重启 */
    private fun isEnabled() = ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideStorageChatRecordEntry

    override fun handleHook(session: HookSession) {
        // ★ 始终注册 Hook，isEnabled() 在回调中实时检查
        runCatching {
            val cleanUiClass = AppReflect.findClassIfExists(CLEAN_NEW_UI_CLASS, session.classLoader)
            if (cleanUiClass == null) {
                StealthLog.w("HideStorageChatRecordPluginPart: $CLEAN_NEW_UI_CLASS not found, skip")
                return
            }
            hookCleanNewUI(session)
            StealthLog.i("HideStorageChatRecordPluginPart hooked")
        }.onFailure {
            StealthLog.w("HideStorageChatRecordPluginPart hook fail", it)
        }
    }

    private fun hookCleanNewUI(session: HookSession) {
        session.findAndHook(
            CLEAN_NEW_UI_CLASS,
            "onCreate",
            Bundle::class.java
        ) { chain ->
            val result = chain.proceed()
            if (!isEnabled()) return@findAndHook result
            val activity = chain.thisObject as? Activity ?: return@findAndHook result
            hideChatRecordCard(activity)
            result
        }

        // 页面在数据加载完成后会通过 x7(long) 刷新卡片
        runCatching {
            session.findAndHook(
                CLEAN_NEW_UI_CLASS,
                "x7",
                Long::class.java
            ) { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@findAndHook result
                val activity = chain.thisObject as? Activity ?: return@findAndHook result
                hideChatRecordCard(activity)
                result
            }
        }.onFailure {
            StealthLog.w("HideStorageChatRecordPluginPart hook x7 fail", it)
        }
    }

    private fun hideChatRecordCard(activity: Activity) {
        var cardId = activity.resources.getIdentifier(CHAT_RECORD_CARD_ID_NAME, "id", activity.packageName)
        if (cardId == 0) {
            cardId = HARDCODED_CHAT_RECORD_CARD_ID
        }
        if (cardId == 0) {
            return
        }
        val card = activity.findViewById<View>(cardId)
        if (card?.visibility != View.GONE) {
            card?.visibility = View.GONE
            StealthLog.d("Storage chat record card hidden")
        }
    }
}
