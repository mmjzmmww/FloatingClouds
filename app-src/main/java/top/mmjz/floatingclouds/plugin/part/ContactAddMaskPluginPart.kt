package top.mmjz.floatingclouds.plugin.part

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import top.mmjz.floatingclouds.bean.MaskItemBean
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppContext
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import java.lang.reflect.Method

/**
 * 通讯录长按加入密友 —— 微信原生菜单注入方案（Route B，参考 InkHide / ConvAddMask 思路）。
 *
 * 微信 8.0.74 通讯录架构（已真机日志 + 反编译源码实证）：
 *   - 长按入口：in5.g1.onLongClick → tf5.b0.v → rl5.r.g(...) → 同时构造菜单与点击回调：
 *       new o(frag, data, pos)  // tf5.o  implements View.OnCreateContextMenuListener  → 构造菜单项
 *       new p(frag, data, pos)  // tf5.p  implements db5.t4(onMMMenuItemSelected)       → 处理菜单点击
 *   - tf5.o.onCreateContextMenu(db5.g4, View, ContextMenuInfo)
 *     · db5.g4 **implements android.view.ContextMenu**（可直接当 ContextMenu 用，menu.add 是接口方法）
 *     · tf5.o 持有 public final g f418981d（dataItem，类型 tf5.g）
 *     · tf5.g 持有 public final z3 f418953d（联系人对象）；wxid = z3.d1()
 *   - 点击交给 tf5.p.onMMMenuItemSelected(MenuItem, int)：微信只认 itemId ∈ {1,2,7,8}，
 *     其余 itemId 一律走默认（no-op）。因此拦截点击必须 hook onMMMenuItemSelected，
 *     **不能**依赖 MenuItem.setOnMenuItemClickListener（微信自定义 db5.g4 菜单不回调它）。
 *
 * ⚠️ 关键坑（真机验证得出）：tf5.o.onCreateContextMenu 第一个参数声明类型是 db5.g4，
 *    不是 android.view.ContextMenu。getDeclaredMethod 按精确类型匹配会找不到，
 *    必须用 null 通配按"方法名+参数数量(3)"匹配。
 *
 * 方案（只 hook 两个方法，最干净，与 ConvAddMaskPluginPart 完全一致）：
 *   1. Hook tf5.o.onCreateContextMenu（null 通配匹配签名）→ 追加「加入密友 / 取消密友」项，
 *      并在注入时把当前选中联系人 wxid 暂存到 pendingWxid（15s 过期）。
 *   2. Hook tf5.p.onMMMenuItemSelected(MenuItem, int) → 若 itemId == MENU_ITEM_ID（或标题命中），
 *      取 pendingWxid 执行 加/取消密友 + Toast + 触发列表刷新。
 */
class ContactAddMaskPluginPart : IPlugin {

    companion object {
        private const val TAG = "ContactAddMask"
        private const val MENU_ITEM_ID = 1835102721
        private const val MENU_TEXT_FALLBACK = "加入密友"
        private const val MENU_TEXT_CANCEL = "取消密友"

        // 8.0.74 通讯录长按菜单内部类混淆名候选（反编译确认在 tf5 包）
        private val INNER_O_CLASS_CANDIDATES = listOf(
            "tf5.o",
            "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment\$o"
        )
        private const val FRAGMENT_CLASS = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"

        // 通讯录长按菜单「点击」回调类（implements db5.t4.onMMMenuItemSelected）
        private val MENU_SELECT_CLASS_CANDIDATES = listOf(
            "tf5.p",
            "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment\$p"
        )
    }

    @Volatile private var pendingWxid: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun handleHook(session: HookSession) {
        Log.e(TAG, "handleHook START (Route B)")
        hookContactOnCreateContextMenu(session)
        hookContactMenuItemSelected(session)
        Log.e(TAG, "ready")
    }

    private fun isEnabled(): Boolean {
        return ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().enableContactLongPressAddMask
    }

    /** Hook 通讯录长按菜单构造方法，注入「加入密友」项并暂存 wxid */
    private fun hookContactOnCreateContextMenu(session: HookSession) {
        val cl = session.classLoader

        // 1) 直接尝试已知混淆名候选（null 通配：按方法名+参数数量匹配，避开 db5.g4 精确类型问题）
        var m: Method? = null
        var resolvedClass: String? = null
        for (candidate in INNER_O_CLASS_CANDIDATES) {
            val found = AppReflect.findMethodExact(
                candidate, cl, "onCreateContextMenu",
                null, null, null
            )
            if (found != null) {
                m = found
                resolvedClass = candidate
                break
            }
        }

        // 2) 回退：反射枚举 MvvmAddressUIFragment 的内部类，找实现 OnCreateContextMenuListener 的
        if (m == null) {
            val fragClass = AppReflect.findClassIfExists(FRAGMENT_CLASS, cl)
            if (fragClass != null) {
                for (inner in fragClass.declaredClasses) {
                    if (View.OnCreateContextMenuListener::class.java.isAssignableFrom(inner)) {
                        val found = AppReflect.findMethodExact(inner, "onCreateContextMenu", null, null, null)
                        if (found != null) {
                            m = found
                            resolvedClass = inner.name
                            break
                        }
                    }
                }
            }
        }

        if (m == null) {
            Log.e(TAG, "!!! onCreateContextMenu NOT FOUND (candidates=${INNER_O_CLASS_CANDIDATES.joinToString()})")
            return
        }
        Log.e(TAG, "onCreateContextMenu hooked: $resolvedClass")
        session.hookMethod(m as Method) { chain ->
            try {
                // args[0] 是 db5.g4 实例（implements ContextMenu），直接当 ContextMenu 用
                val menu = chain.args[0] as? ContextMenu
                injectContactMenuItem(chain.thisObject, menu)
            } catch (e: Exception) {
                Log.e(TAG, "inject exception: ${e.message}", e)
            }
            chain.proceed()
        }
    }

    /**
     * Hook 通讯录长按菜单「点击」回调 tf5.p.onMMMenuItemSelected(MenuItem, int)。
     * 微信只认 itemId ∈ {1,2,7,8}，我们的 MENU_ITEM_ID 不会被处理，故在此拦截。
     */
    private fun hookContactMenuItemSelected(session: HookSession) {
        val cl = session.classLoader
        var hooked = false
        for (cls in MENU_SELECT_CLASS_CANDIDATES) {
            if (hooked) break
            runCatching {
                val m = AppReflect.findMethodExact(
                    cls, cl, "onMMMenuItemSelected",
                    MenuItem::class.java, Int::class.javaPrimitiveType
                )
                if (m != null) {
                    session.hookMethod(m) { chain ->
                        val item = chain.args[0] as? MenuItem
                        if (item != null && isOurMenuItem(item)) {
                            consumeContactMenuItem()
                        }
                        chain.proceed()
                    }
                    Log.e(TAG, "onMMMenuItemSelected hooked: $cls")
                    hooked = true
                }
            }.onFailure { Log.w(TAG, "onMMMenuItemSelected $cls fail: ${it.message}") }
        }
        if (!hooked) {
            Log.e(TAG, "!!! onMMMenuItemSelected NOT FOUND (candidates=${MENU_SELECT_CLASS_CANDIDATES.joinToString()})")
            findAndHookContactMenuItemSelectedBroad(session)
        }
    }

    /** 兜底：在 tf5 包内扫描 onMMMenuItemSelected(MenuItem, int) 并 hook 第一个命中的（针对混淆漂移） */
    private fun findAndHookContactMenuItemSelectedBroad(session: HookSession) {
        val cl = session.classLoader
        val prefix = "tf5."
        for (c in 'a'..'z') {
            for (len in 1..2) {
                val cls = prefix + c.toString().repeat(len)
                runCatching {
                    val m = AppReflect.findMethodExact(
                        cls, cl, "onMMMenuItemSelected",
                        MenuItem::class.java, Int::class.javaPrimitiveType
                    )
                    if (m != null) {
                        session.hookMethod(m) { chain ->
                            val item = chain.args[0] as? MenuItem
                            if (item != null && isOurMenuItem(item)) consumeContactMenuItem()
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

    private fun getMenuText(): String {
        val cfg = ConfigUtil.getOptionData().addMaskMenuText
        return cfg.ifBlank { MENU_TEXT_FALLBACK }
    }

    private fun isOurMenuItem(item: MenuItem): Boolean {
        if (item.itemId == MENU_ITEM_ID) return true
        val title = item.title?.toString()
        return title == getMenuText() || title == MENU_TEXT_CANCEL
    }

    /**
     * 在原生菜单里追加我们的项，并暂存选中联系人 wxid（点击时由 onMMMenuItemSelected 消费）。
     * @param innerO tf5.o 实例（thisObject）
     * @param menu   原生 ContextMenu（实际类型 db5.g4，implements ContextMenu）
     */
    private fun injectContactMenuItem(innerO: Any?, menu: ContextMenu?) {
        if (menu == null || innerO == null) {
            Log.w(TAG, "inject skip: menu=${menu?.javaClass?.name} innerO=${innerO?.javaClass?.name}")
            return
        }
        if (!isEnabled()) {
            Log.w(TAG, "inject skip: disabled (master=${ConfigUtil.isMasterEnabled()}, opt=${ConfigUtil.getOptionData().enableContactLongPressAddMask})")
            return
        }

        // 从 tf5.o.f418981d（dataItem g）-> g.f418953d（z3）-> z3.d1() 精准提取 wxid
        val wxid = extractWxidFromInnerO(innerO)
        if (wxid.isNullOrBlank()) {
            Log.w(TAG, "inject: wxid null from innerO=${innerO.javaClass.name}")
            return
        }

        val isMasked = WXMaskPlugin.containChatUser(wxid)
        val text = if (isMasked) MENU_TEXT_CANCEL else getMenuText()

        // 暂存 wxid，供点击回调消费（15s 过期，避免陈旧值串号）
        pendingWxid = wxid
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ pendingWxid = null }, 15000)

        runCatching {
            menu.add(0, MENU_ITEM_ID, 0, text)
            Log.e(TAG, "inject OK: '$text' wxid=$wxid masked=$isMasked")
        }.onFailure { e ->
            Log.e(TAG, "inject fail: ${e.message}", e)
        }
    }

    /** 处理点击：加/取消密友 + Toast + 触发列表刷新（wxid 来自注入时暂存的 pendingWxid） */
    private fun consumeContactMenuItem() {
        val wxid = pendingWxid ?: run {
            Log.w(TAG, "consume: pendingWxid is null (menu opened but wxid lost?)")
            return
        }
        pendingWxid = null

        if (WXMaskPlugin.containChatUser(wxid)) {
            ConfigUtil.removeMaskList(wxid)
            Toast.makeText(AppContext.context, "已取消密友: $wxid", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "removeMask OK: $wxid")
        } else {
            ConfigUtil.addMaskList(MaskItemBean(wxid, wxid))
            Toast.makeText(AppContext.context, "刻舟求剑", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "addMask OK: $wxid")
        }
        // 会话列表即时刷新（加/取消密友后让其从会话列表消失/重现）
        HideMainUIListPluginPart.onMaskListChanged?.invoke()
        // 通讯录列表即时刷新（加/取消密友后让其从通讯录消失/重现）
        HideContactListPluginPart.onContactMaskChanged?.invoke()
    }

    /**
     * 从 tf5.o 实例精准提取选中好友 wxid。
     * 不依赖混淆字段名：按「类型」在对象图里定位 dataItem(tf5.g) -> z3 -> d1()。
     * 8.0.74 运行时实测：tf5.o 字段为 [g d, int e, MvvmAddressUIFragment f]
     */
    private fun extractWxidFromInnerO(innerO: Any): String? {
        // 1) 先找 dataItem：tf5.o 中类型简单名为 "g" 的字段（即 tf5.g）
        val dataItem = findFieldByType(innerO, simpleName = "g")
        if (dataItem != null) {
            Log.d(TAG, "extract: dataItem=${dataItem.javaClass.name}")
            // 2) 在 dataItem 中找 z3（类型名含 storage.z3）
            val contact = findFieldByType(dataItem, nameContains = "storage.z3")
            if (contact != null) {
                val wxid = callWxidGetter(contact)
                if (!wxid.isNullOrBlank()) {
                    Log.d(TAG, "extract: via dataItem->z3 OK wxid=$wxid")
                    return wxid
                }
                Log.w(TAG, "extract: z3 found but d1() blank (class=${contact.javaClass.name})")
            } else {
                Log.w(TAG, "extract: no z3 field in dataItem=${dataItem.javaClass.name}")
            }
        } else {
            Log.w(TAG, "extract: no tf5.g dataItem field in ${innerO.javaClass.name}")
        }

        // 3) 终极兜底：整图扫描任意 z3 实例
        val all = findAllByType(innerO, nameContains = "storage.z3")
        Log.d(TAG, "extract: fallback z3 candidates=${all.size}")
        for (z in all) {
            val wxid = callWxidGetter(z)
            if (!wxid.isNullOrBlank()) return wxid
        }
        val fields = innerO.javaClass.declaredFields.joinToString { "${it.type.simpleName} ${it.name}" }
        Log.w(TAG, "extractWxidFromInnerO FAILED in ${innerO.javaClass.name}; fields=[$fields]")
        return null
    }

    /** 在 obj 的字段（含父类）里找第一个类型简单名==simpleName 的字段值 */
    private fun findFieldByType(obj: Any, simpleName: String? = null, nameContains: String? = null): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val tn = f.type.name
                val hit = (simpleName != null && f.type.simpleName == simpleName) ||
                        (nameContains != null && tn.contains(nameContains))
                if (hit) {
                    runCatching {
                        f.isAccessible = true
                        return f.get(obj)
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 递归收集 obj 对象图中所有类型名含 nameContains 的实例（深度/visited 受限） */
    private fun findAllByType(obj: Any?, nameContains: String, depth: Int = 0, visited: MutableSet<Int> = mutableSetOf()): List<Any> {
        val out = mutableListOf<Any>()
        if (obj == null || depth > 6) return out
        if (!visited.add(System.identityHashCode(obj))) return out
        val clazz = obj.javaClass
        if (clazz.isPrimitive || clazz == String::class.java) return out
        if (clazz.name.contains(nameContains)) out.add(obj)
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val t = f.type
                if (t.isPrimitive || t == String::class.java) continue
                if (t.name.contains("Fragment") || t.name.contains("Activity") ||
                    t.name.contains("Adapter") || t.name.contains("RecyclerView") ||
                    t.name.contains("Manager") || t.name.contains("Handler") ||
                    t.name.contains("Context") || t.name.contains("View") ||
                    t.name.endsWith("ArrayList") || t.name.endsWith("HashMap") ||
                    t.name.endsWith("List") || t.name.endsWith("Map") ||
                    t.name.endsWith("Set") || t.name.endsWith("Array") ||
                    t.name.endsWith("Sparse")) continue
                runCatching {
                    f.isAccessible = true
                    val v = f.get(obj) ?: return@runCatching
                    out.addAll(findAllByType(v, nameContains, depth + 1, visited))
                }
            }
            c = c.superclass
        }
        return out
    }

    /** 从 z3 联系人对象取 wxid：沿类继承链找 d1()/d()/getUsername()，兜底读 username/wxid 字段 */
    private fun callWxidGetter(contact: Any): String? {
        for (methodName in listOf("d1", "d", "getUsername", "getWxid")) {
            var c: Class<*>? = contact.javaClass
            while (c != null && c != Any::class.java) {
                runCatching {
                    val mtd = c.getDeclaredMethod(methodName)
                    mtd.isAccessible = true
                    val wxid = mtd.invoke(contact) as? String
                    if (!wxid.isNullOrBlank()) return wxid.lowercase()
                }
                c = c.superclass
            }
        }
        // 兜底：读字段 username / wxid
        for (fieldName in listOf("username", "wxid", "field_username", "field_wxid")) {
            var c: Class<*>? = contact.javaClass
            while (c != null && c != Any::class.java) {
                runCatching {
                    val fld = c.getDeclaredField(fieldName)
                    fld.isAccessible = true
                    val v = fld.get(contact) as? String
                    if (!v.isNullOrBlank()) return v.lowercase()
                }
                c = c.superclass
            }
        }
        return null
    }
}
