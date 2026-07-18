package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin

/**
 * 长按添加密友 — 已拆分。
 *
 * 历史实现已拆分为两个独立的 Part：
 *   - ConvAddMaskPluginPart  → 会话列表长按（ContextMenu 注入）
 *   - ContactAddMaskPluginPart → 通讯录长按（PopupWindow 底部按钮注入）
 *
 * 本类保留为空实现，避免破坏 WXMaskPlugin 的 PluginPart 列表引用。
 */
class LongPressAddMaskPluginPart : IPlugin {
    override fun handleHook(session: HookSession) {}
}
