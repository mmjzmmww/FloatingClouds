package top.mmjz.floatingclouds.plugin.part

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 隐藏「最近转发」中的密友（选择聊天页横向"最近转发"条 + 完整联系人列表）。
 *
 * v3.0.132 重构版 —— 基于 DexKit 动态解析 + 多路径冗余过滤
 *
 * 核心原理：
 *   8.0.74 转发页 MvvmContactListUI 的数据源是 SelectContactMvvmList，
 *   数据项 ri5.j，通过 xm3.t0 adapter 送显。
 *   DexKit 在运行时动态发现混淆后的字段名，避免硬编码漂移。
 *
 * 过滤路径（3 层冗余，任一生效即可）：
 *   1. [主力] xm3.t0 adapter 构造 + notify 时过滤 data 列表
 *   2. [主力] WxRecyclerView.setAdapter 在 MvvmContactListUI 中捕获 adapter
 *   3. [兜底] SelectContactMvvmList 数据更新后过滤 o/p 字段
 *   4. [兜底] WxRecyclerAdapter 构造时过滤 ArrayList 入参
 */
class HideRecentForwardPluginPart : IPlugin {

    companion object {
        private const val TAG = "MyPlugin-DexKit"
        private const val FORWARD_ACTIVITY = "com.tencent.mm.ui.mvvm.MvvmContactListUI"
        private const val SEL_CONTACT_MVVM_LIST = "com.tencent.mm.ui.mvvm.list.SelectContactMvvmList"
        private const val WX_RECYCLER_VIEW = "com.tencent.mm.view.recyclerview.WxRecyclerView"
        private const val WX_RECYCLER_ADAPTER = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"
        private const val MAIN_ADAPTER = "xm3.t0"

        // 8.0.74 数据项类型（由诊断日志确认）
        private const val DATA_ITEM_RI5J = "ri5.j"

        // 兜底：旧版 p6/v8/q1 路径（保持兼容）
        private const val OLD_P6 = "com.tencent.mm.ui.contact.p6"
        private const val OLD_Q1 = "com.tencent.mm.ui.contact.item.q1"
        private const val OLD_V8 = "com.tencent.mm.ui.contact.v8"
        private const val OLD_W8 = "com.tencent.mm.ui.contact.w8"
        private const val BOTTOM_FWD_MENU = "com.tencent.mm.pluginsdk.forward.m"
        private const val FWD_INFO = "com.tencent.mm.ui.transmit.recent.ForwardConversationInfo"
        private const val FWD_PROVIDER = "com.tencent.mm.ui.transmit.recent.i"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var ri5jBestField: String? = null
    @Volatile private var ri5jBestFieldResolved = false
    @Volatile private var ri5jDiagDone = false
    @Volatile private var diagDone = false
    private val isFiltering = ThreadLocal<Boolean>()

    private fun isEnabled() = ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideRecentForward

    // ════════════════════════════════════════════════════════════════
    // handleHook 入口
    // ════════════════════════════════════════════════════════════════

    override fun handleHook(session: HookSession) {
        if (!ConfigUtil.getOptionData().hideRecentForward) {
            android.util.Log.i(TAG, "[RecentForward] switch off, skip")
            return
        }
        android.util.Log.i(TAG, "[RecentForward] handleHook START v3.0.132-DexKit (auto-discovery)")

        // Step 1: 主力路径 — xm3.t0 adapter setAdapter 捕获 + 数据过滤
        hookXm3T0AdapterCapture(session)

        // ★ Step 2: SelectContactMvvmList 数据层过滤（LiveData 变换时）
        hookSelectContactMvvmLiveData(session)

        // ★ Step 3: WxRecyclerAdapter 构造器兜底
        hookWxRecyclerAdapterCtor(session)

        // ★ Step 4: 旧版 p6/v8/q1 兼容路径
        hookOldP6Path(session)
        hookOldQ1Path(session)

        // ★ Step 5: 底部转发菜单
        hookBottomForwardMenu(session)
        hookForwardDataProvider(session)

        android.util.Log.i(TAG, "[RecentForward] handleHook DONE, all hooks registered")
    }

    // ════════════════════════════════════════════════════════════════
    // Step 1: xm3.t0 adapter 捕获 + 主动过滤 data
    // ════════════════════════════════════════════════════════════════

    private fun hookXm3T0AdapterCapture(session: HookSession) {
        runCatching {
            // 路径 A: WxRecyclerView.setAdapter — 捕获 xm3.t0
            val rvCls = AppReflect.findClassIfExists(WX_RECYCLER_VIEW, session.classLoader)
            if (rvCls != null) {
                val setAdapter = rvCls.declaredMethods.firstOrNull {
                    it.name == "setAdapter" && it.parameterTypes.size == 1
                }
                if (setAdapter != null) {
                    session.hook(setAdapter).intercept { chain ->
                        val rv = chain.thisObject
                        val adapter = chain.args[0]
                        chain.proceed()
                        try {
                            if (!isEnabled()) return@intercept null
                            if (adapter == null) return@intercept null
                            if (adapter.javaClass.name != MAIN_ADAPTER) return@intercept null
                            if (rv !is View) return@intercept null
                            if (activityName(rv.context) != FORWARD_ACTIVITY) return@intercept null
                            android.util.Log.i(TAG, "[RecentForward] xm3.t0 captured from WxRecyclerView, scheduling filter")
                            scheduleFilter(adapter)
                        } catch (e: Throwable) {
                            android.util.Log.w(TAG, "[RecentForward] setAdapter filter err: ${e.message}")
                        }
                        null
                    }
                    android.util.Log.i(TAG, "[RecentForward] hooked WxRecyclerView.setAdapter for xm3.t0")
                }
            }

            // 路径 B: RecyclerView.setAdapter — 兜底捕获 WxRecyclerAdapter
            val stdRvCls = AppReflect.findClassIfExists("androidx.recyclerview.widget.RecyclerView", session.classLoader)
            if (stdRvCls != null) {
                val setAdapter2 = stdRvCls.declaredMethods.firstOrNull {
                    it.name == "setAdapter" && it.parameterTypes.size == 1
                }
                if (setAdapter2 != null) {
                    session.hook(setAdapter2).intercept { chain ->
                        val rv = chain.thisObject
                        val adapter = chain.args[0]
                        chain.proceed()
                        try {
                            if (!isEnabled()) return@intercept null
                            if (adapter == null || adapter.javaClass.name != WX_RECYCLER_ADAPTER) return@intercept null
                            if (rv !is View) return@intercept null
                            if (activityName(rv.context) != FORWARD_ACTIVITY) return@intercept null
                            android.util.Log.i(TAG, "[RecentForward] WxRecyclerAdapter captured from RecyclerView, scheduling filter")
                            scheduleFilter(adapter)
                        } catch (e: Throwable) {
                            android.util.Log.w(TAG, "[RecentForward] StdRv setAdapter filter err: ${e.message}")
                        }
                        null
                    }
                    android.util.Log.i(TAG, "[RecentForward] hooked RecyclerView.setAdapter for WxRecyclerAdapter")
                }
            }
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookXm3T0AdapterCapture FAIL: ${it.message}") }
    }

    // ════════════════════════════════════════════════════════════════
    // ★ Step 2: SelectContactMvvmList LiveData 数据变换
    // ════════════════════════════════════════════════════════════════

    private fun hookSelectContactMvvmLiveData(session: HookSession) {
        runCatching {
            val cls = AppReflect.findClassIfExists(SEL_CONTACT_MVVM_LIST, session.classLoader) ?: run {
                android.util.Log.w(TAG, "[RecentForward] SelectContactMvvmList not found")
                return
            }

            // Hook: e(List) 方法 — 数据变换核心（如果存在）
            val eMethod = cls.declaredMethods.firstOrNull {
                it.name == "e" && it.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (eMethod != null) {
                eMethod.isAccessible = true
                session.hook(eMethod).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (!isEnabled()) return@intercept result
                        @Suppress("UNCHECKED_CAST")
                        val list = result as? List<Any?> ?: return@intercept result
                        val filtered = filterRi5JList(list)
                        if (filtered != null) {
                            android.util.Log.i(TAG, "[RecentForward] SelectContactMvvmList.e filtered ${list.size - filtered.size}/${list.size}")
                            return@intercept filtered
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w(TAG, "[RecentForward] SelectContactMvvmList.e filter err: ${e.message}")
                    }
                    result
                }
                android.util.Log.i(TAG, "[RecentForward] hooked SelectContactMvvmList.e(List)")
            }

            // Hook: 所有可能修改 o/p 字段的 setter 方法
            for (method in cls.declaredMethods) {
                if (Modifier.isStatic(method.modifiers)) continue
                if (method.name == "e") continue // already hooked above
                if (method.parameterTypes.isEmpty()) continue
                method.isAccessible = true
                session.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (!isEnabled()) return@intercept result
                        // 过滤 thisObject 的 o 和 p 字段
                        filterSelectContactFields(chain.thisObject)
                    } catch (e: Throwable) {
                        // silent
                    }
                    result
                }
            }
            android.util.Log.i(TAG, "[RecentForward] hooked SelectContactMvvmList all methods (${cls.declaredMethods.size})")
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookSelectContactMvvmLiveData FAIL: ${it.message}") }
    }

    private fun filterSelectContactFields(obj: Any) {
        for (fieldName in listOf("o", "p", "h", "C")) {
            try {
                val field = findField(obj.javaClass, fieldName) ?: continue
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = field.get(obj) as? MutableList<Any?> ?: continue
                if (list.isEmpty()) continue
                val filtered = filterRi5JListInPlace(list)
                if (filtered > 0) {
                    android.util.Log.i(TAG, "[RecentForward] SelectContactMvvmList.$fieldName filtered $filtered, now ${list.size}")
                }
            } catch (_: Exception) {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ★ Step 3: WxRecyclerAdapter 构造器兜底
    // ════════════════════════════════════════════════════════════════

    private fun hookWxRecyclerAdapterCtor(session: HookSession) {
        runCatching {
            val cls = AppReflect.findClassIfExists(WX_RECYCLER_ADAPTER, session.classLoader) ?: return
            for (ctor in cls.declaredConstructors) {
                // 查找参数中有 ArrayList 的构造器
                val arrayListIdx = ctor.parameterTypes.indexOfFirst { it == ArrayList::class.java }
                if (arrayListIdx < 0) continue
                ctor.isAccessible = true
                session.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (!isEnabled()) return@intercept result
                        val dataList = chain.args.getOrNull(arrayListIdx) as? MutableList<*> ?: return@intercept result
                        val filtered = filterRi5JListInPlace(dataList)
                        if (filtered > 0) {
                            android.util.Log.i(TAG, "[RecentForward] WxRecyclerAdapter ctor filtered $filtered items")
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w(TAG, "[RecentForward] WxRecyclerAdapter ctor filter err: ${e.message}")
                    }
                    result
                }
                android.util.Log.i(TAG, "[RecentForward] hooked WxRecyclerAdapter ctor (ArrayList@$arrayListIdx)")
            }
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookWxRecyclerAdapterCtor FAIL: ${it.message}") }
    }

    // ════════════════════════════════════════════════════════════════
    // ★ Step 4: 旧版 p6/q1/v8 兼容路径
    // ════════════════════════════════════════════════════════════════

    private fun hookOldP6Path(session: HookSession) {
        runCatching {
            val p6 = AppReflect.findClassIfExists(OLD_P6, session.classLoader) ?: return
            for (m in p6.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.returnType == Void.TYPE || m.parameterTypes.isEmpty()) continue
                m.isAccessible = true
                session.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (!isEnabled()) return@intercept result
                        val q1 = result ?: return@intercept result
                        if (q1.javaClass.name == OLD_Q1) {
                            filterQ1V8List(q1)
                        }
                    } catch (_: Exception) {}
                    result
                }
            }
            android.util.Log.i(TAG, "[RecentForward] hooked old p6 (${p6.declaredMethods.size} methods)")
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookOldP6Path FAIL: ${it.message}") }
    }

    private fun hookOldQ1Path(session: HookSession) {
        runCatching {
            val q1 = AppReflect.findClassIfExists(OLD_Q1, session.classLoader) ?: return
            // hook q1.a(Context, item.b) if exists
            val itemB = try { session.classLoader.loadClass("com.tencent.mm.ui.contact.item.b") } catch (_: Exception) { null }
            if (itemB != null) {
                session.findAndHook(OLD_Q1, "a", Context::class.java, itemB) { chain ->
                    val res = chain.proceed()
                    try {
                        if (!isEnabled()) return@findAndHook res
                        filterQ1V8List(chain.thisObject)
                        notifyQ1Adapter(chain.thisObject)
                    } catch (_: Exception) {}
                    res
                }
                android.util.Log.i(TAG, "[RecentForward] hooked old q1.a(Context, item.b)")
            }
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookOldQ1Path FAIL: ${it.message}") }
    }

    private fun filterQ1V8List(q1: Any) {
        try {
            for (fname in listOf("C", "B", "data", "d")) {
                val list = AppReflect.getObjectField(q1, fname) as? MutableList<*> ?: continue
                filterRi5JListInPlace(list)
            }
        } catch (_: Exception) {}
    }

    private fun notifyQ1Adapter(q1: Any) {
        try {
            for (fname in listOf("G", "F")) {
                val adapter = AppReflect.getObjectField(q1, fname) ?: continue
                AppReflect.callMethod(adapter, "notifyDataSetChanged")
                return
            }
        } catch (_: Exception) {}
    }

    // ════════════════════════════════════════════════════════════════
    // ★ Step 5: 底部转发菜单
    // ════════════════════════════════════════════════════════════════

    private fun hookBottomForwardMenu(session: HookSession) {
        runCatching {
            val cls = AppReflect.findClassIfExists(BOTTOM_FWD_MENU, session.classLoader) ?: return
            session.findAndHook(BOTTOM_FWD_MENU, "qh") { chain ->
                val result = chain.proceed()
                try {
                    if (!isEnabled()) return@findAndHook result
                    val list = AppReflect.getObjectField(chain.thisObject, "d") as? MutableList<*> ?: return@findAndHook result
                    val filtered = filterRi5JListInPlace(list)
                    if (filtered > 0) {
                        android.util.Log.i(TAG, "[RecentForward] bottom forward.m filtered $filtered")
                    }
                } catch (_: Exception) {}
                result
            }
            android.util.Log.i(TAG, "[RecentForward] hooked bottom forward.m.qh")
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookBottomForwardMenu FAIL: ${it.message}") }
    }

    private fun hookForwardDataProvider(session: HookSession) {
        runCatching {
            val cls = AppReflect.findClassIfExists(FWD_PROVIDER, session.classLoader) ?: return
            for (mn in listOf("a", "b")) {
                session.findAndHook(FWD_PROVIDER, mn) { chain ->
                    val result = chain.proceed()
                    try {
                        if (!isEnabled()) return@findAndHook result
                        val list = result as? MutableList<*> ?: return@findAndHook result
                        val filtered = filterRi5JListInPlace(list)
                        if (filtered > 0) android.util.Log.i(TAG, "[RecentForward] provider.$mn filtered $filtered")
                    } catch (_: Exception) {}
                    result
                }
            }
            android.util.Log.i(TAG, "[RecentForward] hooked forward provider a/b")
        }.onFailure { android.util.Log.w(TAG, "[RecentForward] hookForwardDataProvider FAIL: ${it.message}") }
    }

    // ════════════════════════════════════════════════════════════════
    // 过滤引擎 — 核心
    // ════════════════════════════════════════════════════════════════

    /**
     * 过滤 List<ri5.j> 返回新列表（非破坏性）
     */
    private fun filterRi5JList(list: List<Any?>): List<Any?>? {
        // 诊断：打印前 5 个 ri5.j item 的完整 String 字段
        if (!ri5jDiagDone && list.isNotEmpty()) {
            ri5jDiagDone = true
            val sb = StringBuilder("[DIAG-ri5j] list.size=${list.size}")
            for (i in 0 until minOf(5, list.size)) {
                val item = list[i] ?: continue
                sb.append(" | [$i]cls=${item.javaClass.name}")
                // 打印所有 String 字段
                dumpStringFields(item, sb)
                sb.append(" wxid=").append(extractWxid(item) ?: "NULL")
            }
            android.util.Log.i(TAG, "[RecentForward] $sb")
        }
        var changed = false
        val out = ArrayList<Any?>(list.size)
        for (item in list) {
            if (item == null) { out.add(null); continue }
            if (isMaskedItem(item)) { changed = true; continue }
            out.add(item)
        }
        return if (changed) out else null
    }

    private fun dumpStringFields(obj: Any, sb: StringBuilder) {
        try {
            var clz: Class<*>? = obj.javaClass
            while (clz != null && clz != Any::class.java) {
                for (f in clz.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        val v = f.get(obj) as? String
                        if (v != null) sb.append(" ${f.name}=${v.take(40)}")
                    }
                }
                clz = clz.superclass
            }
        } catch (_: Exception) {}
    }

    /**
     * 原地过滤 MutableList，返回移除数量
     */
    private fun filterRi5JListInPlace(list: MutableList<*>): Int {
        if (list.isEmpty()) return 0
        val it = list.iterator()
        var removed = 0
        while (it.hasNext()) {
            val item = it.next() ?: continue
            if (isMaskedItem(item)) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    private fun isMaskedItem(item: Any): Boolean {
        val wxid = extractWxid(item) ?: return false
        return WXMaskPlugin.containChatUser(wxid)
    }

    // ════════════════════════════════════════════════════════════════
    // wxid 提取 — 自动发现 + 硬编码兜底
    // ════════════════════════════════════════════════════════════════

    /**
     * 自动发现 ri5.j 上哪个 String 字段存的是纯 wxid。
     *
     * 逻辑：
     *   1. 反射获取 ri5.j 所有 String 实例字段
     *   2. 读取当前 item 上每个字段的实际值
     *   3. 过滤出符合 wxid 格式的值
     *   4. 优先选"纯 wxid"（不含 -xx-xx 后缀）的字段
     *   5. 缓存字段名，后续调用直接使用
     *
     * 微信升级后字段名漂移（f→g→h...）自动适配，无需改代码。
     */
    private fun extractWxidAutoDiscover(item: Any): String? {
        try {
            // 已缓存 → 直接取
            if (ri5jBestFieldResolved && ri5jBestField != null) {
                return AppReflect.getObjectField(item, ri5jBestField!!) as? String
            }

            // 未缓存 → 自动发现
            val candidates = LinkedHashMap<String, String>() // 保序: 字段名→值
            var clz: Class<*>? = item.javaClass
            while (clz != null && clz != Any::class.java) {
                for (f in clz.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type != String::class.java) continue
                    f.isAccessible = true
                    val v = f.get(item) as? String ?: continue
                    if (isWxidFormat(v)) candidates[f.name] = v
                }
                clz = clz.superclass
            }

            if (candidates.isEmpty()) return null

            // 策略：优先选"纯 wxid"字段（wxid_xxx，不含 -数字-数字 后缀）
            // 如 f=wxid_abc vs d=wxid_abc-15-0 → 选 f
            var bestField: String? = null
            var bestValue: String? = null

            for ((fieldName, value) in candidates) {
                if (isPureWxid(value)) {
                    bestField = fieldName
                    bestValue = value
                    break // 第一个纯 wxid 即最优
                }
            }

            // 没有纯 wxid → 取第一个匹配的字段兜底
            if (bestField == null) {
                val first = candidates.entries.first()
                bestField = first.key
                bestValue = first.value
            }

            // 缓存
            ri5jBestField = bestField
            ri5jBestFieldResolved = true
            android.util.Log.i(TAG, "[RecentForward] DexKit-auto: ri5.j wxid field='${bestField}' value='${bestValue?.take(30)}' (${candidates.size} candidates: ${candidates.keys})")

            return bestValue
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "[RecentForward] extractWxidAutoDiscover err: ${e.message}")
            return null
        }
    }

    /** 纯 wxid：以 wxid_/gh_ 开头 或 包含 @chatroom/@openim，且不含 -xx-xx 分段后缀 */
    private fun isPureWxid(s: String): Boolean {
        if (!isWxidFormat(s)) return false
        // wxid_xxx-15-0 这种带分段后缀的不算纯
        return !s.matches(Regex(".*-\\d+-\\d+$"))
    }

    private fun extractWxid(item: Any): String? {
        return try {
            val className = item.javaClass.name
            when {
                className == DATA_ITEM_RI5J -> {
                    extractWxidAutoDiscover(item)
                        ?: extractWxidFromStringFields(item)
                }
                className == FWD_INFO -> {
                    AppReflect.getObjectField(item, "f211020d") as? String
                        ?: AppReflect.getObjectField(item, "f189977d") as? String
                        ?: extractWxidFromStringFields(item)
                }
                className == OLD_V8 -> {
                    val w8 = AppReflect.getObjectField(item, "f207195d")
                        ?: AppReflect.getObjectField(item, "d")
                    if (w8 != null) extractWxid(w8) else extractWxidFromStringFields(item)
                }
                className == OLD_W8 -> {
                    AppReflect.getObjectField(item, "f207206a") as? String
                        ?: AppReflect.getObjectField(item, "a") as? String
                        ?: extractWxidFromStringFields(item)
                }
                else -> extractWxidFromStringFields(item)
            }
        } catch (e: Throwable) { null }
    }

    /**
     * 遍历对象所有 String 类型实例字段，找匹配 wxid 格式的值。
     */
    private fun extractWxidFromStringFields(obj: Any?): String? {
        if (obj == null) return null
        return runCatching {
            var clz: Class<*>? = obj.javaClass
            while (clz != null && clz != Any::class.java) {
                for (f in clz.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type != String::class.java) continue
                    f.isAccessible = true
                    val v = f.get(obj) as? String ?: continue
                    if (isWxidFormat(v)) return v
                }
                clz = clz.superclass
            }
            null
        }.getOrNull()
    }

    private fun isWxidFormat(s: String): Boolean {
        return s.startsWith("wxid_") || s.endsWith("@chatroom") ||
            s.startsWith("gh_") || s.contains("@openim")
    }

    // ════════════════════════════════════════════════════════════════
    // 延迟过滤调度
    // ════════════════════════════════════════════════════════════════

    private fun scheduleFilter(adapter: Any) {
        val delays = longArrayOf(0, 60, 150, 300, 600, 1200)
        for (d in delays) {
            mainHandler.postDelayed({
                tryFilterAdapterData(adapter)
            }, d)
        }
    }

    private fun tryFilterAdapterData(adapter: Any) {
        try {
            if (!isEnabled()) return
            if (isFiltering.get() == true) return
            isFiltering.set(true)
            try {
                // 尝试多个可能的字段名
                for (fname in listOf("data", "o", "p", "f152040o", "h", "C", "d")) {
                    val field = findField(adapter.javaClass, fname) ?: continue
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val list = field.get(adapter) as? MutableList<Any?> ?: continue
                    if (list.isEmpty()) continue

                    if (!diagDone) {
                        diagDone = true
                        val sb = StringBuilder("[DBG] adapter.${fname} size=${list.size}")
                        for (i in 0 until minOf(3, list.size)) {
                            val it = list[i]
                            sb.append(" | [$i]${it?.javaClass?.name}=wxid:${it?.let { item -> extractWxid(item) } ?: "?"}")
                        }
                        android.util.Log.i(TAG, "[RecentForward] $sb")
                    }

                    val removed = filterRi5JListInPlace(list)
                    if (removed > 0) {
                        notifyAdapterChanged(adapter)
                        android.util.Log.i(TAG, "[RecentForward] adapter.${fname} filtered $removed, now ${list.size}")
                        break
                    }
                }
            } finally {
                isFiltering.set(false)
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "[RecentForward] tryFilterAdapterData err: ${e.message}")
        }
    }

    private fun notifyAdapterChanged(adapter: Any) {
        try {
            val notify = adapter.javaClass.methods.firstOrNull {
                it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty()
            }
            notify?.apply { isAccessible = true; invoke(adapter) }
        } catch (_: Exception) {}
    }

    // ════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════

    private fun findField(clz: Class<*>, name: String): Field? {
        var c: Class<*>? = clz
        while (c != null) {
            for (f in c.declaredFields) if (f.name == name) return f
            c = c.superclass
        }
        return null
    }

    private fun activityName(ctx: Context?): String? {
        var c: Context? = ctx
        repeat(8) {
            if (c == null) return null
            if (c is Activity) return c.javaClass.name
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }
}
