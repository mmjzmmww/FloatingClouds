package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 隐藏密友朋友圈 — onBindViewHolder 视图级 GONE (#2)。
 * hook h2 adapter 的混后 bind 方法(o0/B/E)，bind 时检查 username 是否密友，
 * 若是则将 holder.itemView 设为 GONE+height=0。不修改 data 列表，零延迟零闪现。
 */
class HideSnsEntryPluginPart : IPlugin {

    companion object {
        private const val TAG = "HideSnsEntry"
        private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
        private const val CONTACT_USER_EXTRA = "Contact_User"
        private const val SNS_PREFERENCE_KEY = "contact_profile_sns"
        const val SNS_TIMELINE_ADAPTER = "com.tencent.mm.plugin.sns.ui.improve.component.h2"
        private const val WX_RECYCLER_VIEW = "com.tencent.mm.view.recyclerview.WxRecyclerView"
    }

    private var hookSession: HookSession? = null
    @Volatile private var adapterRef: Any? = null

    private fun isEnabled() = ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideSnsEntry
    private fun targetName() = RuntimeClassResolver.snsTimelineAdapterCls?.name ?: SNS_TIMELINE_ADAPTER

    override fun handleHook(session: HookSession) {
        hookSession = session
        hookContactProfileEntry(session)
        hookSetAdapterAndBind(session)
    }

    private fun hookContactProfileEntry(session: HookSession) {
        val cls = AppReflect.findClassIfExists(CONTACT_INFO_UI, session.classLoader) ?: return
        session.findAndHookNoArgs(CONTACT_INFO_UI, "initView") { chain ->
            if (!isEnabled()) return@findAndHookNoArgs chain.proceed()
            chain.proceed()
            (chain.thisObject as? Activity)?.let {
                val chatUser = it.intent?.getStringExtra(CONTACT_USER_EXTRA) ?: return@findAndHookNoArgs null
                if (WXMaskPlugin.containChatUser(chatUser))
                    AppReflect.callMethod(it, "getPreferenceScreen")?.let { s ->
                        AppReflect.callMethod(s, "m", SNS_PREFERENCE_KEY, true)
                    }
            }
        }
    }

    private fun hookSetAdapterAndBind(session: HookSession) {
        // 捕获 adapter
        val cls = AppReflect.findClassIfExists(WX_RECYCLER_VIEW, session.classLoader) ?: return
        val setAdapter = AppReflect.findMethodsByExactPredicate(cls) { m ->
            m.name == "setAdapter" && m.parameterTypes.size == 1
        }.firstOrNull() ?: return
        session.hook(setAdapter).intercept { chain ->
            val adapter = chain.args[0]
            chain.proceed()
            if (adapter != null && adapter.javaClass.name == targetName() && isEnabled()) {
                adapterRef = adapter
                hookBindOnAdapter(session, adapter)
            }
            null
        }
    }

    /** 寻找 onBindViewHolder(VH,int) 混淆变体并 hook，bind 时直接 GONE 密友条目 */
    private var bindHooked = false
    private fun hookBindOnAdapter(session: HookSession, adapter: Any) {
        if (bindHooked) return
        bindHooked = true
        // 混淆后 o0/VH,int B/VH,int E/VH,int 参数2=Int
        val bindMethod = adapter.javaClass.methods.firstOrNull { m ->
            m.parameterTypes.size == 2 && m.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: run { StealthLog.w("$TAG: no bind(VH,int) found on ${adapter.javaClass.name}"); return }
        bindMethod.isAccessible = true
        session.hook(bindMethod).intercept { chain ->
            val pos = chain.args[1] as? Int ?: return@intercept chain.proceed()
            val holder = chain.args[0]  // ViewHolder (k3)
            chain.proceed()
            if (isEnabled() && holder != null) hideIfMasked(pos, holder)
            null
        }
        StealthLog.i("$TAG: hooked bind ${bindMethod.name} on ${adapter.javaClass.simpleName}")
    }

    private fun hideIfMasked(pos: Int, holder: Any) {
        val adapter = adapterRef ?: return
        val data = runCatching { AppReflect.getObjectField(adapter, "data") as? List<*> }.getOrNull() ?: return
        if (pos >= data.size) return
        val item = data[pos] ?: return
        val name = extractUserName(item) ?: return
        if (!WXMaskPlugin.containChatUser(name)) return
        // 通过 ViewHolder.itemView 直接 GONE
        try {
            val itemView = AppReflect.getObjectField(holder, "itemView") as? View ?: return
            itemView.visibility = View.GONE
            itemView.layoutParams?.let { it.height = 0; itemView.layoutParams = it }
        } catch (_: Exception) {}
    }

    private fun extractUserName(item: Any?): String? {
        if (item == null) return null
        (AppReflect.callMethod(item, "getUserName") as? String)?.let { return it }
        val n = AppReflect.callMethod(item, "n")
        if (n != null) (AppReflect.callMethod(n, "getUserName") as? String)?.let { return it }
        return null
    }
}
