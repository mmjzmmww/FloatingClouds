package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 禁止进入联系人资料页（blockContactInfo）。
 *
 * 微信 8.0.70 联系人资料页：com.tencent.mm.plugin.profile.ui.ContactInfoUI
 * ContactInfoUI.onCreate(Bundle) 在早期读取 getIntent().getStringExtra("Contact_User")。
 *
 * 实现：hook ContactInfoUI.onCreate 的 afterHookedMethod（确保 super.onCreate 已执行），
 * 从 Intent 取 Contact_User，如果是密友且开关开启，直接 finish() 不进入资料页。
 * 同时 hook onResume beforeHookedMethod 作为兜底。
 *
 * 注意：不能在 beforeHookedMethod 中用 param.setResult(null) 跳过 onCreate，
 * 因为这会跳过 super.onCreate() 导致 SuperNotCalledException 崩溃。
 *
 * 参考：HideSnsEntryPluginPart 已 hook ContactInfoUI.initView 和 onResume（隐藏朋友圈入口），
 * 两个 hook 互不冲突，Xposed 支持同一方法多个 hook。
 */
class BlockContactInfoPluginPart : IPlugin {

    companion object {
        private const val TAG = "BlockContactInfo"
        private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
        private const val CONTACT_USER_EXTRA = "Contact_User"
    }

    override fun handleHook(session: HookSession) {
        StealthLog.i("=== $TAG handleHook START ===")

        val contactInfoClass = AppReflect.findClassIfExists(CONTACT_INFO_UI, session.classLoader)
        if (contactInfoClass == null) {
            StealthLog.i("$TAG: ERROR - $CONTACT_INFO_UI not found")
            return
        }
        StealthLog.i("$TAG: found $CONTACT_INFO_UI")

        // hook onCreate(Bundle): afterHookedMethod（super.onCreate 已执行，不会崩溃）
        runCatching {
            session.findAndHook(
                CONTACT_INFO_UI,
                "onCreate",
                Bundle::class.java
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@findAndHook result
                if (activity.isFinishing) return@findAndHook result
                val option = ConfigUtil.getOptionData()
                if (!option.blockContactInfo || !ConfigUtil.isMasterEnabled()) return@findAndHook result
                if (ConfigUtil.isConfigModeFlag()) return@findAndHook result
                if (HideMainUIListPluginPart.tempUnhideMainConv) {
                    StealthLog.i("$TAG: tempUnhideMainConv=true, SKIP block (allow contact info)")
                    return@findAndHook result
                }

                val chatUser = activity.intent?.getStringExtra(CONTACT_USER_EXTRA)
                if (chatUser.isNullOrBlank()) return@findAndHook result

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    StealthLog.i("$TAG: BLOCK ContactInfoUI.onCreate for $chatUser, finishing activity")
                    activity.finish()
                }
                result
            }
            StealthLog.i("$TAG: hooked onCreate (afterHookedMethod)")
        }.onFailure {
            StealthLog.i("$TAG: hook onCreate FAILED", it)
        }

        // hook onResume: beforeHookedMethod 兜底
        runCatching {
            session.findAndHook(
                CONTACT_INFO_UI,
                "onResume"
            ) { chain ->
                val activity = chain.thisObject as? Activity
                if (activity == null || activity.isFinishing) return@findAndHook chain.proceed()
                val option = ConfigUtil.getOptionData()
                if (!option.blockContactInfo || !ConfigUtil.isMasterEnabled()) return@findAndHook chain.proceed()
                if (ConfigUtil.isConfigModeFlag()) return@findAndHook chain.proceed()
                if (HideMainUIListPluginPart.tempUnhideMainConv) {
                    StealthLog.i("$TAG: tempUnhideMainConv=true, SKIP block (allow contact info)")
                    return@findAndHook chain.proceed()
                }

                val chatUser = activity.intent?.getStringExtra(CONTACT_USER_EXTRA)
                if (chatUser.isNullOrBlank()) return@findAndHook chain.proceed()

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    StealthLog.i("$TAG: BLOCK ContactInfoUI.onResume (fallback) for $chatUser, finishing")
                    activity.finish()
                    null
                } else {
                    chain.proceed()
                }
            }
            StealthLog.i("$TAG: hooked onResume (fallback)")
        }.onFailure {
            StealthLog.i("$TAG: hook onResume FAILED", it)
        }

        StealthLog.i("=== $TAG handleHook DONE ===")
    }
}
