package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.StealthLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局 Activity 生命周期 Hook。
 */
class GlobalLifecycleHook : IPlugin {

    companion object {
        @Volatile
        var instance: GlobalLifecycleHook? = null
            private set
    }

    @Volatile
    var currentActivity: Activity? = null
        private set

    private val listeners = ConcurrentHashMap<String, MutableList<(Activity) -> Unit>>()

    override fun handleHook(session: HookSession) {
        instance = this

        // ★★★ 在 GlobalLifecycleHook 中直接注入设置入口（绕过 maskUIManager 进程匹配问题） ★★★
        session.findAndHook("android.app.Activity", "onCreate", Bundle::class.java) { chain ->
            val r = chain.proceed()
            val act = chain.thisObject as? Activity
            if (act != null) {
                currentActivity = act
                notifyListeners(act)
            }
            r
        }

        // ★ onResume 时注入设置入口（此时布局已加载，不为空）
        session.findAndHook("android.app.Activity", "onResume") { chain ->
            val r = chain.proceed()
            val act = chain.thisObject as? Activity
            if (act != null) {
                currentActivity = act
                notifyListeners(act)
                if (act.javaClass.name.endsWith("SettingsCareModeIntro")) {
                    try {
                        val cv = act.findViewById<ViewGroup>(android.R.id.content)
                        if (cv != null) {
                            var n = 0
                            injectLongClick(cv) { v -> if (v.id > View.NO_ID) { n++; v.setOnLongClickListener {
                                v.post {
                                    try {
                                        top.mmjz.floatingclouds.plugin.ui.MaskManagerCenterUI(act).show()
                                    } catch (e: Exception) {
                                        android.util.Log.e("FC_DEBUG", "MaskUI show failed", e)
                                    }
                                }; true
                            }}}
                            android.util.Log.e("FC_DEBUG", "SettingsCare onResume: injected $n long-click views")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FC_DEBUG", "SettingsCare injection err", e)
                    }
                }
            }
            r
        }

        session.findAndHook("android.app.Activity", "onDestroy") { chain ->
            val r = chain.proceed()
            val act = chain.thisObject as? Activity
            if (act != null && currentActivity === act) {
                currentActivity = null
            }
            r
        }

        StealthLog.i("[GlobalLifecycle] hooked Activity lifecycle")
    }

    fun onActivity(activityClassName: String, callback: (Activity) -> Unit) {
        listeners.getOrPut(activityClassName) { mutableListOf() }.add(callback)
    }

    private fun notifyListeners(act: Activity) {
        val name = act.javaClass.name
        if (name.contains("Sns")) StealthLog.i("[GlobalLifecycle] Activity: $name")
        val cbs = listeners[name] ?: return
        for (cb in cbs) {
            runCatching { cb(act) }
        }
    }

    private fun injectLongClick(root: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            action(c)
            if (c is ViewGroup) injectLongClick(c, action)
        }
    }
}
