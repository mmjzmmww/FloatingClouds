package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.ui.MaskManagerCenterUI
import top.mmjz.floatingclouds.util.StealthLog

class MaskUIManagerPluginPart : IPlugin {
    override fun handleHook(session: HookSession) {
        android.util.Log.e("FC_DEBUG", "MaskUIManager.handleHook START")

        session.findAndHook(
            "com.tencent.mm.plugin.setting.ui.setting.SettingsCareModeIntro",
            "initView"
        ) { chain ->
            android.util.Log.e("FC_DEBUG", "MaskUIManager: SettingsCareModeIntro.initView FIRED!")
            val result = chain.proceed()
            val act = chain.thisObject as? Activity ?: return@findAndHook result
            val cv = act.findViewById<ViewGroup>(android.R.id.content) ?: return@findAndHook result
            var n = 0
            findViews(cv) { v -> if (v.id > View.NO_ID) { n++; v.setOnLongClickListener {
                v.post { MaskManagerCenterUI(act).show() }; true
            }}}
            StealthLog.i("MaskUIManager: $n long-click views"); result
        }
    }

    private fun findViews(root: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i); action(c)
            if (c is ViewGroup) findViews(c, action)
        }
    }
}
