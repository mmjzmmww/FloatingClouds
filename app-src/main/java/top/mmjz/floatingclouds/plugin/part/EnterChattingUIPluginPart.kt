package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Bundle
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.ClazzN
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 聊天页拦截：
 * 仅保留【禁止进入密友聊天界面】——开关开启且是密友则 block，开关关闭直接放行。
 */
class EnterChattingUIPluginPart() : IPlugin {

    override fun handleHook(session: HookSession) {
        hookChattingUIOnCreate(session)
        hookChattingUIOnEnterBegin(session)
    }

    private fun hookChattingUIOnCreate(session: HookSession) {
        runCatching {
            session.findAndHook(
                "android.app.Activity",
                "onCreate",
                Bundle::class.java
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@findAndHook result
                if (!activity.javaClass.name.contains("ChattingUI")) return@findAndHook result

                val option = ConfigUtil.getOptionData()
                if (!option.blockEnterChat || !ConfigUtil.isMasterEnabled()) return@findAndHook result
                if (ConfigUtil.isConfigModeFlag()) return@findAndHook result
                if (ConfigUtil.getMaskList().isEmpty()) return@findAndHook result
                if (HideMainUIListPluginPart.tempUnhideMainConv) return@findAndHook result
                if (HideMainUIListPluginPart.hasEnteredMaskChatting) return@findAndHook result

                val intent = activity.intent ?: return@findAndHook result
                val chatUser = intent.getStringExtra("Chat_User")
                if (chatUser.isNullOrBlank()) return@findAndHook result

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    StealthLog.i("blockEnterChat(onCreate): finishing ${activity.javaClass.simpleName} for $chatUser")
                    activity.finish()
                }
                result
            }
        }.onFailure {
            StealthLog.i("hook Activity.onCreate for blockEnterChat fail", it)
        }
    }

    private fun hookChattingUIOnEnterBegin(session: HookSession) {
        session.findAndHook(
            ClazzN.ChattingUIProxy,
            "onEnterBegin"
        ) { chain ->
            val option = ConfigUtil.getOptionData()
            if (!option.blockEnterChat || !ConfigUtil.isMasterEnabled()) return@findAndHook chain.proceed()
            if (ConfigUtil.isConfigModeFlag()) return@findAndHook chain.proceed()
            if (ConfigUtil.getMaskList().isEmpty()) return@findAndHook chain.proceed()
            if (HideMainUIListPluginPart.tempUnhideMainConv) return@findAndHook chain.proceed()
            if (HideMainUIListPluginPart.hasEnteredMaskChatting) return@findAndHook chain.proceed()

            runCatching {
                val proxyClass = AppReflect.findClassIfExists(ClazzN.ChattingUIProxy, session.classLoader) ?: return@runCatching
                val fragmentClass = AppReflect.findClassIfExists(ClazzN.BaseChattingUIFragment, session.classLoader) ?: return@runCatching
                val fragmentField = AppReflect.findFirstFieldByExactType(proxyClass, fragmentClass) ?: return@runCatching
                fragmentField.isAccessible = true
                val fragment = fragmentField.get(chain.thisObject) ?: return@runCatching

                val arguments = AppReflect.callMethod(fragment, "getArguments") as? Bundle ?: return@runCatching
                val chatUser = arguments.getString("Chat_User")
                if (chatUser.isNullOrBlank()) return@runCatching

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    val act = AppReflect.callMethod(fragment, "getActivity") as? Activity
                    act?.finish()
                    return@findAndHook null
                }
            }.onFailure {
                StealthLog.i("blockEnterChat: onEnterBegin error", it)
            }
            chain.proceed()
        }
    }
}
