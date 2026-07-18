package top.mmjz.floatingclouds

import top.mmjz.floatingclouds.util.AppReflect

/**
 * 微信类名常量 + 反射查找工具。
 * 重构版：不再依赖 com.lu.lposed.api2.XposedHelpers2，改用 AppReflect。
 */
interface ClazzN {
    companion object {
        const val ChattingUIProxy = "com.tencent.mm.ui.chatting.ChattingUIProxy"
        const val BaseChattingUIFragment = "com.tencent.mm.ui.chatting.BaseChattingUIFragment"
        const val LauncherUI = "com.tencent.mm.ui.LauncherUI"
        const val ChattingUI = "com.tencent.mm.ui.chatting.ChattingUI"
        const val MainUI = "com.tencent.mm.ui.conversation.MainUI"
        const val ConversationListView = "com.tencent.mm.ui.conversation.ConversationListView"
        const val BaseContact = "com.tencent.mm.autogen.table.BaseContact"
        const val BaseConversation = "com.tencent.mm.autogen.table.BaseConversation"

        // ───────────────────────────────────────────────
        // FALLBACK 混淆名（非主路径；主路径应为 DexKitCache 命中类名）
        // 跨微信版本会失效，需随微信更新维护。修改前务必确认对应 DexKit 任务已建立主路径，
        // 否则替换为不存在的类名会直接破坏相关功能。
        // ───────────────────────────────────────────────

        // 会话静音（8.0.74 InkHide 参考）。候选序: e3.x3 → ge3.x3 → sc3.x3 → sc3.x
        const val MUTE_X3_FALLBACK = "e3.x3" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        const val MUTE_GE3_FALLBACK = "ge3.x3" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        const val MUTE_SC3_X3_FALLBACK = "sc3.x3" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        const val MUTE_SC3_X_FALLBACK = "sc3.x" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        // yj0 候选序: yj0.j1 → yj0.h1 → yj0.i1
        const val MUTE_YJ0_J1_FALLBACK = "yj0.j1" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        const val MUTE_YJ0_H1_FALLBACK = "yj0.h1" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        const val MUTE_YJ0_I1_FALLBACK = "yj0.i1" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态
        // Flutter 通话入口（8.0.74）。次级: com.tencent.mm.plugin.voip_cs.flutter.d（描述性类名，相对稳定，保留为第二候选）
        const val FLUTTER_VOIP_FALLBACK = "iq0.d" // FALLBACK ONLY - 非主路径，主路径应为 DexKitCache；修改前确认 DexKit 任务状态

        // 默认 classLoader，在 WeChat 进程初始化时由 XposedEntry 设置。
        // 替代旧 AppUtil.getContext().classLoader 的隐式依赖。
        @Volatile
        var defaultClassLoader: ClassLoader? = null

        @JvmStatic
        @JvmOverloads
        fun from(clazz: String, classLoader: ClassLoader? = defaultClassLoader): Class<*>? {
            val loader = classLoader ?: return null
            return AppReflect.findClassIfExists(clazz, loader)
        }
    }
}
