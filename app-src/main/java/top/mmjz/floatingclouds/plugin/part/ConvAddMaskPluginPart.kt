package top.mmjz.floatingclouds.plugin.part

import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ListView
import android.widget.Toast
import top.mmjz.floatingclouds.bean.MaskItemBean
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil

/**
 * 会话列表长按加入密友 — ContextMenu 注入方案（参考 InkHide）。
 *
 * 微信 8.0.74 会话列表长按走标准 ContextMenu：
 *   1. Hook Fragment r3/m3/n3 的 onCreateContextMenu → 注入"加入密友"菜单项
 *   2. Hook 回调类 p3/k3/l3 的 onMMMenuItemSelected → 拦截点击
 *   3. wxid 从 AdapterContextMenuInfo.position + ListView.getItemAtPosition 提取
 *
 * 与通讯录的 ContactAddMaskPluginPart 完全独立，各自有独立开关。
 */
class ConvAddMaskPluginPart : IPlugin {

    @Volatile private var pendingWxid: String? = null
    @Volatile private var pendingIsConv: Boolean = false
    @Volatile private var lastInjectTime: Long = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "CAM"
        private const val MENU_ITEM_ID_CONV = 1835102465
        private const val MENU_TEXT_FALLBACK = "加入密友"
    }

    override fun handleHook(session: HookSession) {
        val cl = session.classLoader
        Log.e(TAG, "handleHook START")

        hookConvOnCreateContextMenu(session)
        hookConvMenuItemSelected(session)
        Log.e(TAG, "ready")
    }

    /** Hook 会话列表 Fragment 的 onCreateContextMenu */
    private fun hookConvOnCreateContextMenu(session: HookSession) {
        val cl = session.classLoader
        val convFragClasses = arrayOf(
            "com.tencent.mm.ui.conversation.m3",
            "com.tencent.mm.ui.conversation.n3",
            "com.tencent.mm.ui.conversation.r3"
        )
        var hooked = false
        for (cls in convFragClasses) {
            if (hooked) break
            runCatching {
                val m = AppReflect.findMethodExact(cls, cl, "onCreateContextMenu",
                    ContextMenu::class.java, View::class.java, ContextMenu.ContextMenuInfo::class.java)
                if (m != null) {
                    session.hookMethod(m) { chain ->
                        val menu = chain.args[0] as? ContextMenu
                        val view = chain.args[1] as? View
                        val info = chain.args[2] as? ContextMenu.ContextMenuInfo
                        if (menu != null && view != null) {
                            val pos = (info as? AdapterView.AdapterContextMenuInfo)?.position ?: -1
                            injectConvMenuItem(menu, view, pos)
                        }
                        chain.proceed()
                    }
                    Log.e(TAG, "conv onCreateContextMenu hooked: $cls")
                    hooked = true
                }
            }.onFailure { Log.w(TAG, "conv $cls fail: ${it.message}") }
        }
        if (!hooked) Log.e(TAG, "!!! conv onCreateContextMenu NOT FOUND")
    }

    /** Hook 会话列表菜单回调 onMMMenuItemSelected */
    private fun hookConvMenuItemSelected(session: HookSession) {
        val cl = session.classLoader
        val menuCallbackClasses = arrayOf(
            "com.tencent.mm.ui.conversation.k3",
            "com.tencent.mm.ui.conversation.l3",
            "com.tencent.mm.ui.conversation.p3"
        )
        var hooked = false
        for (cls in menuCallbackClasses) {
            if (hooked) break
            runCatching {
                val m = AppReflect.findMethodExact(cls, cl, "onMMMenuItemSelected",
                    MenuItem::class.java, Int::class.javaPrimitiveType)
                if (m != null) {
                    session.hookMethod(m) { chain ->
                        val item = chain.args[0] as? MenuItem
                        if (item != null && isOurMenuItem(item)) {
                            consumeConvMenuItem()
                        }
                        chain.proceed()
                    }
                    Log.e(TAG, "onMMMenuItemSelected hooked: $cls")
                    hooked = true
                }
            }.onFailure { Log.w(TAG, "conv $cls fail: ${it.message}") }
        }
        if (!hooked) {
            findAndHookOnMMMenuItemSelectedBroad(session)
        }
    }

    private fun isEnabled(): Boolean {
        return ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().enableLongPressAddMask
    }

    private fun getMenuText(): String {
        val cfg = ConfigUtil.getOptionData().addMaskMenuText
        return cfg.ifBlank { MENU_TEXT_FALLBACK }
    }

    private fun isOurMenuItem(item: MenuItem): Boolean {
        if (item.itemId == MENU_ITEM_ID_CONV) return true
        val title = item.title?.toString()
        return title == getMenuText() || title == "取消密友"
    }

    private fun injectConvMenuItem(menu: ContextMenu, view: View, infoPos: Int) {
        if (!isEnabled()) return

        val now = System.currentTimeMillis()
        if (now - lastInjectTime < 500) return
        lastInjectTime = now

        val wxid = extractConvWxid(view, infoPos)
        if (wxid.isNullOrBlank()) {
            Log.w(TAG, "inject: no wxid from view=${view.javaClass.name}")
            return
        }

        pendingWxid = wxid
        pendingIsConv = true

        val isMasked = WXMaskPlugin.containChatUser(wxid)
        val text = if (isMasked) "取消密友" else getMenuText()
        try {
            menu.add(0, MENU_ITEM_ID_CONV, 0, text)
            Log.e(TAG, "inject OK: '$text' wxid=$wxid masked=$isMasked")
        } catch (e: Exception) {
            Log.e(TAG, "inject fail: ${e.message}", e)
        }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ pendingWxid = null }, 15000)
    }

    private fun consumeConvMenuItem() {
        val wxid = pendingWxid ?: return
        pendingWxid = null

        if (WXMaskPlugin.containChatUser(wxid)) {
            ConfigUtil.removeMaskList(wxid)
            Toast.makeText(top.mmjz.floatingclouds.util.AppContext.context, "已取消密友: $wxid", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "removeMask OK: $wxid")
        } else {
            ConfigUtil.addMaskList(MaskItemBean(wxid, wxid))
            Toast.makeText(top.mmjz.floatingclouds.util.AppContext.context, "刻舟求剑", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "addMask OK: $wxid")
        }
        HideMainUIListPluginPart.onMaskListChanged?.invoke()
        HideContactListPluginPart.onContactMaskChanged?.invoke()
    }

    private fun extractConvWxid(view: View, infoPos: Int): String? {
        var lv: ListView? = null
        var p: View? = view
        while (p != null) {
            if (p is ListView) { lv = p; break }
            p = p.parent as? View
        }
        if (lv == null) return null

        val adapter = lv.adapter ?: return null
        val headerCount = lv.headerViewsCount

        runCatching { lv.getItemAtPosition(infoPos) }?.let { item ->
            val wxid = item?.let { readWxidFromItem(it) }
            if (!wxid.isNullOrBlank()) return wxid
        }

        if (headerCount > 0 && infoPos >= headerCount) {
            runCatching { adapter.getItem(infoPos - headerCount) }?.let { item ->
                val wxid = item?.let { readWxidFromItem(it) }
                if (!wxid.isNullOrBlank()) return wxid
            }
        }

        runCatching { adapter.getItem(infoPos) }?.let { item ->
            val wxid = item?.let { readWxidFromItem(it) }
            if (!wxid.isNullOrBlank()) return wxid
        }

        return extractWxidFromViewTag(view)
    }

    private fun readWxidFromItem(item: Any): String? {
        val wxid = runCatching { AppReflect.getObjectField(item, "field_username") as? String }.getOrNull()
        if (!wxid.isNullOrBlank()) return wxid
        return extractWxidFromObject(item)
    }

    private fun extractWxidFromViewTag(view: View): String? {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 15) {
            val tag = v.tag
            if (tag != null) {
                val wxid = readWxidFromItem(tag)
                if (!wxid.isNullOrBlank()) return wxid
            }
            v = v.parent as? View
            depth++
        }
        return null
    }

    private fun extractWxidFromObject(obj: Any): String? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java) continue
                f.isAccessible = true
                val v = f.get(obj) as? String ?: continue
                if (v.startsWith("wxid_") || v.endsWith("@chatroom") || v == "filehelper" || v.startsWith("gh_")) {
                    return v
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun findAndHookOnMMMenuItemSelectedBroad(session: HookSession) {
        val cl = session.classLoader
        for (pkg in arrayOf("com.tencent.mm.ui.conversation")) {
            val candidates = arrayOf(
                "${pkg}.a", "${pkg}.b", "${pkg}.c", "${pkg}.d", "${pkg}.e",
                "${pkg}.f", "${pkg}.g", "${pkg}.h", "${pkg}.i", "${pkg}.j",
                "${pkg}.k3", "${pkg}.l3", "${pkg}.m3", "${pkg}.n3", "${pkg}.o3",
                "${pkg}.p3", "${pkg}.q3", "${pkg}.r3", "${pkg}.s3", "${pkg}.t3"
            )
            for (cls in candidates) {
                runCatching {
                    val m = AppReflect.findMethodExact(cls, cl, "onMMMenuItemSelected",
                        MenuItem::class.java, Int::class.javaPrimitiveType)
                    if (m != null) {
                        session.hookMethod(m) { chain ->
                            val item = chain.args[0] as? MenuItem
                            if (item != null && isOurMenuItem(item)) {
                                consumeConvMenuItem()
                            }
                            chain.proceed()
                        }
                        Log.e(TAG, "onMMMenuItemSelected hooked (broad): $cls")
                        return
                    }
                }
            }
        }
        Log.w(TAG, "onMMMenuItemSelected broad search failed")
    }
}
