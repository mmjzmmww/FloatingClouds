package top.mmjz.floatingclouds.plugin.part

import android.view.View
import android.view.ViewGroup
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.StealthLog

/**
 * ★ 诊断用 PluginPart（精简版）：
 *   通讯录标签 mb adapter 的 item class 和字段打印（暂无正式实现替换）
 *   SNS 互动相关 Hook 已全部移至 HideSnsInteractionPluginPart
 */
class DiagSnsLabelPluginPart : IPlugin {

    override fun handleHook(session: HookSession) {
        StealthLog.i("[DIAG-SNS] handleHook START")
        val classLoader = session.classLoader ?: return

        // ★ sl / im / CommentListAdapter Hook 已移至 HideSnsInteractionPluginPart
        // 此处不再注册冲突的 Hook

        // ▸▸▸ 通讯录标签成员列表（诊断保留）
        runCatching {
            val mbClass = AppReflect.findClassIfExists("com.tencent.mm.ui.contact.mb", classLoader) ?: return@runCatching
            session.findAndHook(mbClass.name, "getView",
                Int::class.java, View::class.java, ViewGroup::class.java
            ) { chain ->
                val r = chain.proceed()
                val p = chain.args[0] as? Int ?: return@findAndHook r
                diagItem("[ContactLabel-mb getView:$p]", chain.thisObject, p)
                r
            }
            session.findAndHook(mbClass.name, "getItem", Int::class.java) { chain ->
                val item = chain.proceed()
                val p = chain.args[0] as? Int ?: return@findAndHook item
                val itemCls = item?.javaClass?.name ?: "null"
                StealthLog.i("[DIAG-LABEL] mb getItem[$p] class=$itemCls")
                dumpFields(item, "ContactLabel item[$p]")
                item
            }
            session.findAndHook(mbClass.name, "getCount") { chain ->
                val c = chain.proceed() as? Int ?: 0
                StealthLog.i("[DIAG-LABEL] mb getCount=$c")
                c
            }
            StealthLog.i("[DIAG-LABEL] hooked mb adapter")
        }

        StealthLog.i("[DIAG-SNS] handleHook DONE")
    }

    private fun diagItem(label: String, adapter: Any?, position: Int) {
        runCatching {
            val getItem = adapter?.javaClass?.getMethod("getItem", Int::class.java)
            val item = getItem?.invoke(adapter, position) ?: return
            val clsName = item.javaClass.name
            StealthLog.i("[DIAG-SNS] $label class=$clsName")
            dumpFields(item, label)
        }.onFailure {
            StealthLog.i("[DIAG-SNS] $label FAILED: ${it.message}")
        }
    }

    private fun dumpFields(obj: Any?, label: String) {
        if (obj == null) return
        runCatching {
            val fields = obj.javaClass.declaredFields.sortedBy { it.name }
            val parts = mutableListOf<String>()
            for (f in fields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v == null) return@runCatching
                    val vStr = when (v) {
                        is String -> "\"${if (v.length > 30) v.take(30) + "…" else v}\""
                        is Number -> v.toString()
                        is Boolean -> v.toString()
                        is Collection<*> -> "[size=${v.size}]"
                        else -> v.javaClass.simpleName
                    }
                    parts.add("${f.name}=$vStr")
                }
            }
            StealthLog.i("[DIAG-SNS] $label fields: ${parts.joinToString("\n  ")}")
        }
    }
}
