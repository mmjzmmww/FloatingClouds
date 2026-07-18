package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 隐藏通讯录标签主页预览中的密友备注。
 *
 * 数据路径：d53.b.j(labelId) → List<String>（成员 wxid 列表）
 */
class HideContactLabelPluginPart : IPlugin {

    override fun handleHook(session: HookSession) {
        val cl = session.classLoader

        // ★ 始终注册 Hook，开关检查放在回调内部
        runCatching {
            val labelMgrCls = AppReflect.findClassIfExists("d53.b", cl) ?: return@runCatching

            session.findAndHook(labelMgrCls.name, "j", String::class.java) { chain ->
                val result = chain.proceed() as? List<*> ?: return@findAndHook chain.proceed()

                // 开关关闭时不过滤
                if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideContactList) return@findAndHook result

                val maskedSet = ConfigUtil.getMaskList().map { it.maskId }.toHashSet()
                if (maskedSet.isEmpty()) return@findAndHook result

                val filtered = result.filterNotNull().filter { item ->
                    !maskedSet.contains(item.toString())
                }

                if (filtered.size != result.size) {
                    StealthLog.i("[HideContactLabel] j() filtered: ${result.size} → ${filtered.size}")
                }
                filtered
            }
            StealthLog.i("[HideContactLabel] hooked d53.b.j")
        }
    }
}
