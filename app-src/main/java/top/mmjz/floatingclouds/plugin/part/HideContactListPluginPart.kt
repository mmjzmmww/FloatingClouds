package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.DexKitCache
import top.mmjz.floatingclouds.util.StealthLog
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.HashSet

/**
 * 通讯录隐藏密友（hideContactList）。
 *
 * 微信 8.0.74 通讯录架构：
 * - Fragment: com.tencent.mm.ui.contact.address.MvvmAddressUIFragment
 * - 数据源: com.tencent.mm.ui.contact.address.AddressLiveList extends MvvmList<tf5.g>
 * - 数据项: tf5.g（持有 z3 联系人对象，字段类型含 "storage.z3"）
 * - 显示列表: MvvmList.f152040o（= adapter(xm3.t0).data），由 AddressLiveList.e(List) 变换得到
 *
 * 【隐藏方案（8.0.74 实测生效，且零延迟、不错人、不占位）】
 * AddressLiveList.e(List) 把快照变换为送显列表；hook e() 返回值剔除密友即可。
 * e() 按【真实对象】过滤（无 adapter 的 a0() 头部偏移问题），所以不会错人。
 *
 * 加/取消密友后，WeChat 自己不会重跑 e()（密友名单是我们的 SP，非微信数据），故由本插件
 * 同步重跑 e()：取原始快照 f152041p -> 调 e()（经 hook 自动过滤密友）-> 写回 f152040o 与
 * adapter.data -> notifyDataSetChanged。整条链路在主线程同步完成，手指一松立刻消失，且不残留空白。
 *
 * 早期版本用 onBindViewHolder 对 itemView 置 GONE 兜底，但微信列表有 a0() 头部偏移导致按 data[pos]
 * 取到错位联系人（隐藏"前面那个"），且 GONE 在该列表里不折叠留白——已废弃，改为数据层真正移除。
 */
class HideContactListPluginPart : IPlugin {

    companion object {
        private const val TAG = "HideContactList"
        // 8.0.74 运行时真实类名（R8 保留 ui 包外层类名，只混淆内部类/数据项）
        private const val ADDRESS_LIVE_LIST = "com.tencent.mm.ui.contact.address.AddressLiveList"
        private const val WX_RECYCLER_VIEW = "com.tencent.mm.view.recyclerview.WxRecyclerView"
        private const val CONTACT_ADAPTER = "xm3.t0"
        private const val MIGRATION_FLAG = "hide_migration_v1"

        /** 加/取消密友后即时刷新通讯录（由 ContactAddMaskPluginPart 调用） */
        @Volatile var onContactMaskChanged: (() -> Unit)? = null

        /** 单例引用，供设置UI切开关时立即刷新 */
        @Volatile private var instance: HideContactListPluginPart? = null

        /** 设置UI切换"通讯录隐藏"开关时调用，按当前开关立即重算并刷新可见列表 */
        fun refresh() { instance?.refreshContactList() }
    }

    // 缓存通讯录 LiveList 实例，用于加/取消密友后即时刷新
    @Volatile private var liveListRef: Any? = null
    // 当前可见通讯录 adapter 实例，由 WxRecyclerView.setAdapter 钩子实时捕获（最可靠，不依赖 f152042q 反射）
    @Volatile private var currentContactAdapter: Any? = null
    // 当前通讯录可见的 WxRecyclerView（f206578p），refresh 时 getAdapter() 取最新可见 adapter
    @Volatile private var contactRecyclerViewRef: Any? = null
    // 最近一次 e() 输入的原始联系人快照（raw contacts，未经变换），供 refresh 同步重跑 e() 用（可靠，不依赖 f152041p 瞬时状态）
    @Volatile private var lastRawInput: List<*>? = null
    // AddressLiveList.e(List) 方法引用（hook 后调用它会自动过滤密友），用于同步重跑刷新
    @Volatile private var addressEMethod: Method? = null

    // hook 会话，供动态 hook adapter 使用
    private var hookSession: HookSession? = null

    override fun handleHook(session: HookSession) {
        StealthLog.i("=== $TAG handleHook START ===")
        hookSession = session
        instance = this
        // ★ 注意：不再在启动时强制打开主开关/隐藏开关。
        // 旧逻辑 ensureHideEnabledOnce() 会在每次启动、只要配置名单非空就把
        // masterEnabled / hideContactList / hideMainConvList 强制改回 true 并写盘，
        // 导致用户在设置里关闭的开关重启后被恢复成默认开启（叠加 maskList/CACHE 提交时
        // 把陈旧 options 刷回磁盘）。用户显式关闭的开关必须被尊重；新用户默认即为开启，
        // 无需强制。
        // 注册密友变更回调，加/取消密友后即时刷新通讯录
        onContactMaskChanged = { refreshContactList() }
        // hook AddressLiveList.e(List) 做数据层过滤（权威），并保存方法引用供同步刷新
        hookAddressLiveListE(session)
        // hook WxRecyclerView.setAdapter 捕获通讯录可见 adapter（最可靠路径，与会话列表同构）
        hookRecyclerViewSetAdapter(session)
        StealthLog.i("=== $TAG handleHook DONE ===")
    }

    /** Hook AddressLiveList.e(List):List，过滤掉密友；并保存方法引用供同步刷新 */
    private fun hookAddressLiveListE(session: HookSession) {
        val cl = session.classLoader
        val candidates = LinkedHashSet<String>().apply {
            add(ADDRESS_LIVE_LIST)
            DexKitCache.getContactClasses()?.classNames?.let { addAll(it) }
        }
        var hooked = false
        for (className in candidates) {
            val clazz = AppReflect.findClassIfExists(className, cl) ?: continue
            val m = AppReflect.findMethodsByExactPredicate(clazz) { mtd ->
                mtd.name == "e" && mtd.parameterTypes.size == 1 &&
                        mtd.parameterTypes[0] == List::class.java && mtd.returnType == List::class.java
            }.firstOrNull { it.declaringClass.name == className }
                ?: AppReflect.findMethodsByExactPredicate(clazz) { mtd ->
                    mtd.name == "e" && mtd.parameterTypes.size == 1 &&
                            mtd.parameterTypes[0] == List::class.java && mtd.returnType == List::class.java
                }.firstOrNull()
            if (m == null) {
                StealthLog.w("$TAG: $className has no e(List):List")
                continue
            }
            addressEMethod = m
            session.hook(m).intercept { chain ->
                liveListRef = chain.thisObject  // 缓存实例，供即时刷新
                val input = chain.args[0]
                if (input is List<*>) lastRawInput = ArrayList(input)  // 缓存原始快照，供同步刷新（可靠）
                val result = chain.proceed() as? MutableList<*> ?: return@intercept chain.proceed()
                if (!shouldFilter()) return@intercept result
                val filtered = filterMaskedContacts(result)
                StealthLog.i("$TAG: e() total=${result.size} hidden=${result.size - filtered.size}")
                filtered
            }
            StealthLog.i("$TAG: hooked $className.e(List) (method cached for refresh)")
            hooked = true
            break
        }
        if (!hooked) StealthLog.w("$TAG: AddressLiveList.e NOT hooked (candidates=${candidates.joinToString()})")
    }

    /**
     * Hook WxRecyclerView.setAdapter(RecyclerView.Adapter)：微信在 MvvmAddressUIFragment 建立通讯录时
     * 调用 wxRecyclerView.setAdapter(y0())，y0() 返回 xm3.t0（通讯录 adapter）。借此捕获可见 adapter 实例，
     * 并缓存 WxRecyclerView 引用，refresh 时直接 getAdapter() 取当前屏幕正在显示的 adapter（与主页会话列表同构，
     * 最可靠，不依赖 E0 异步时机 / f152042q 反射）。
     */
    private fun hookRecyclerViewSetAdapter(session: HookSession?) {
        val cl = session?.classLoader ?: run {
            StealthLog.w("$TAG: hookRecyclerViewSetAdapter: classLoader null"); return
        }
        val cls = AppReflect.findClassIfExists(WX_RECYCLER_VIEW, cl) ?: run {
            StealthLog.w("$TAG: $WX_RECYCLER_VIEW not found"); return
        }
        // setAdapter 在 RecyclerView 中方法名唯一（单参数），不靠类型匹配（微信把 RecyclerView.Adapter 混淆为 f2，
        // 按 canonical name 解析会失败）；拦截时再按 adapter 类名 xm3.t0 过滤即可。
        val setAdapter = AppReflect.findMethodsByExactPredicate(cls) { mtd ->
            mtd.name == "setAdapter" && mtd.parameterTypes.size == 1
        }.firstOrNull() ?: run {
            StealthLog.w("$TAG: $WX_RECYCLER_VIEW.setAdapter not found"); return
        }
        try {
            session.hook(setAdapter).intercept { chain ->
                val adapter = chain.args[0]
                if (adapter != null && adapter.javaClass.name == CONTACT_ADAPTER) {
                    contactRecyclerViewRef = chain.thisObject  // WxRecyclerView 实例
                    currentContactAdapter = adapter
                    StealthLog.i("$TAG: captured contact adapter via setAdapter -> ${adapter.javaClass.name}")
                }
                chain.proceed()
            }
            StealthLog.i("$TAG: hooked $WX_RECYCLER_VIEW.setAdapter (capture contact adapter)")
        } catch (e: Throwable) { StealthLog.w("$TAG: hook setAdapter failed", e) }
    }

    private fun shouldFilter(): Boolean {
        // 设计原则：总开关是总闸（关=所有功能停，除"不显示该聊天"），
        // 但功能自身开关 hideContactList 必须保持独立可控，不能被总开关覆盖失效。
        // 同时，临时解除（主会话列表临时解除）需同步作用于通讯录：
        // tempUnhideMainConv 为真时通讯录也显示密友，恢复隐藏后密友消失。
        // 故四条件同时成立才过滤：总开关开 && 通讯录隐藏开关开 && 名单非空 && 非临时解除态。
        // （与 HideMainUIListPluginPart 对 hideMainConvList 的双门控写法对齐，保证每个功能独立可维护）
        return ConfigUtil.isMasterEnabled()
                && ConfigUtil.getOptionData().hideContactList
                && !ConfigUtil.isMaskListEmpty()
                && !HideMainUIListPluginPart.tempUnhideMainConv
    }

    /** 从列表中移除密友项（tf5.g），返回过滤后的新列表 */
    private fun filterMaskedContacts(list: MutableList<*>): MutableList<Any?> {
        val maskSet = HashSet(ConfigUtil.getMaskList().map { it.maskId })
        val out = ArrayList<Any?>()
        for (item in list) {
            if (item == null) continue
            val wxid = extractWxidFromG(item)
            if (wxid != null && wxid in maskSet) {
                StealthLog.i("$TAG: hide contact wxid=$wxid")
                continue
            }
            out.add(item)
        }
        return out
    }

    /** 从 tf5.g（通讯录数据项）提取 wxid：找 z3 类型字段 -> d1()（沿继承链查找） */
    private fun extractWxidFromG(item: Any): String? {
        var c: Class<*>? = item.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type.name.contains("storage.z3")) {
                    runCatching {
                        f.isAccessible = true
                        val z3 = f.get(item) ?: return@runCatching
                        return callZ3Wxid(z3)
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 从 z3 联系人对象取 wxid：沿类继承链找 d1()/d()/getUsername()，兜底读 username 字段 */
    private fun callZ3Wxid(z3: Any): String? {
        for (mn in listOf("d1", "d", "getUsername", "getWxid")) {
            var c: Class<*>? = z3.javaClass
            while (c != null && c != Any::class.java) {
                runCatching {
                    val mtd = c.getDeclaredMethod(mn)
                    mtd.isAccessible = true
                    val v = mtd.invoke(z3) as? String
                    if (!v.isNullOrBlank()) return v.lowercase()
                }
                c = c.superclass
            }
        }
        for (fn in listOf("username", "wxid", "field_username", "field_wxid")) {
            var c: Class<*>? = z3.javaClass
            while (c != null && c != Any::class.java) {
                runCatching {
                    val fld = c.getDeclaredField(fn)
                    fld.isAccessible = true
                    val v = fld.get(z3) as? String
                    if (!v.isNullOrBlank()) return v.lowercase()
                }
                c = c.superclass
            }
        }
        return null
    }

    /**
     * 加/取消密友后即时刷新通讯录列表（与主页会话列表同构：立即生效、零延迟）。
     *
     * 做法：同步重跑 AddressLiveList.e()（取原始快照 f152041p，经 hook 自动过滤密友），得到过滤后的
     * 显示列表，写回 MvvmList.f152040o 与 adapter.data，再 notifyDataSetChanged。密友真正从数据层移除，
     * 不依赖 GONE（避免错人 + 留白），手指一松立刻干净消失。
     */
    private fun refreshContactList() {
        val ll = liveListRef ?: run {
            StealthLog.w("$TAG: refreshContactList skipped, no liveList instance yet")
            return
        }
        val adapter = currentContactAdapter ?: findContactAdapter() ?: run {
            StealthLog.w("$TAG: refreshContactList skipped, no visible adapter yet")
            return
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                // 当前显示列表（f152040o，屏幕正在显示，含分组头）；以此为基准过滤，绝对不会清空
                val current = (getFieldValue(ll, "f152040o") as? MutableList<*>) ?: ArrayList<Any?>()
                // 原始快照：优先用 e() 钩子缓存的 lastRawInput（可靠，不受 f152041p 瞬时为空影响），
                // 用它同步重跑 e() 可同时支持“隐藏”与“取消隐藏”；若为空则退化为过滤当前显示列表（仅隐藏场景安全）
                val raw = lastRawInput
                val newDisplay: List<*> = if (raw != null && raw.isNotEmpty() && addressEMethod != null) {
                    runCatching {
                        addressEMethod!!.apply { isAccessible = true }
                        (addressEMethod!!.invoke(ll, ArrayList(raw)) as? List<*>)?.let { ArrayList(it) }
                    }.getOrNull() ?: filterMaskedContacts(ArrayList(current))
                } else {
                    filterMaskedContacts(ArrayList(current))
                }
                // ★ 关键修复：参照 HideMainUIListPluginPart 的做法，对 adapter.data
                //    就地修改（clear + addAll），不创建新 ArrayList 对象。
                //    创建新对象会导致 adapter 内部其他引用指向旧对象 → getItemCount
                //    不一致 → RecyclerView 布局出空白间隙。
                val adData = readAdapterData(adapter)
                val llData = getFieldValue(ll, "f152040o") as? MutableList<Any?>
                if (adData != null) {
                    adData.clear()
                    adData.addAll(newDisplay)
                } else {
                    // 兜底：读不到现有列表时退化为新建（罕见，如首次初始化）
                    setAdapterData(adapter, ArrayList(newDisplay))
                }
                if (llData != null) {
                    llData.clear()
                    llData.addAll(newDisplay)
                }
                // 仅 notifyDataSetChanged，不需要 requestLayout
                //（就地修改使 adapter 内部状态一致，RecyclerView 自动正确重排）
                val notify = adapter.javaClass.methods.firstOrNull {
                    it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty()
                }
                notify?.isAccessible = true
                notify?.invoke(adapter)
                StealthLog.i("$TAG: refreshContactList -> e() re-run + notifyDataSetChanged (in-place), newSize=${newDisplay.size}")
            }.onFailure { StealthLog.w("$TAG: refreshContactList failed", it) }
        }
    }




    /**
     * 取当前可见通讯录 adapter，优先级：
     * 1) contactRecyclerViewRef.getAdapter() —— 屏幕正在显示的 adapter（最可靠，与会话列表同构）
     * 2) currentContactAdapter —— setAdapter 钩子捕获的实例
     * 3) MvvmList.f152042q 兜底
     */
    private fun findContactAdapter(): Any? {
        // 路径 1：从可见 WxRecyclerView 实时 getAdapter()
        contactRecyclerViewRef?.let { rv ->
            runCatching {
                val getAdapter = rv.javaClass.methods.firstOrNull {
                    it.name == "getAdapter" && it.parameterTypes.isEmpty()
                }
                getAdapter?.isAccessible = true
                val ad = getAdapter?.invoke(rv)
                if (ad != null) {
                    StealthLog.i("$TAG: findContactAdapter via WxRecyclerView.getAdapter -> ${ad.javaClass.name}")
                    return ad
                }
            }.onFailure { StealthLog.w("$TAG: findContactAdapter via getAdapter failed", it) }
        }
        // 路径 2：setAdapter 钩子捕获的实例
        currentContactAdapter?.let {
            StealthLog.i("$TAG: findContactAdapter via currentContactAdapter -> ${it.javaClass.name}")
            return it
        }
        // 路径 3：f152042q 兜底
        val ll = liveListRef
        if (ll != null) {
            var c: Class<*>? = ll.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.name == "f152042q") {
                        f.isAccessible = true
                        val ad = f.get(ll)
                        if (ad != null) {
                            StealthLog.i("$TAG: findContactAdapter via f152042q -> ${ad.javaClass.name}")
                            return ad
                        }
                    }
                }
                c = c.superclass
            }
        }
        StealthLog.w("$TAG: findContactAdapter: no adapter (rv=${contactRecyclerViewRef != null}, cur=${currentContactAdapter != null}, ll=${liveListRef != null})")
        return null
    }

    /** 沿类继承链读字段值 */
    private fun getFieldValue(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name == name) {
                    f.isAccessible = true
                    return f.get(obj)
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 沿类继承链写字段值 */
    private fun setFieldValue(obj: Any, name: String, value: Any?) {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name == name) {
                    f.isAccessible = true
                    f.set(obj, value)
                    return
                }
            }
            c = c.superclass
        }
    }

    /** 一次性 dump adapter 全部 List/Array 和 int 字段，定位 getItemCount 实际依赖的内部字段 */
    private fun dumpAdapterFields(adapter: Any) {
        try {
            val itemCount = runCatching {
                val m = adapter.javaClass.methods.firstOrNull {
                    it.name == "getItemCount" && it.parameterTypes.isEmpty()
                }?.also { it.isAccessible = true }
                m?.invoke(adapter) as? Int
            }.getOrNull() ?: -1
            StealthLog.i("$TAG: DUMP-ADAPTER class=${adapter.javaClass.name} getItemCount()=$itemCount")
            var c: Class<*>? = adapter.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(adapter) ?: return@runCatching
                        val tn = f.type.simpleName
                        val cn = c!!.simpleName
                        when {
                            v is List<*> -> StealthLog.i("$TAG: DUMP-ADAPTER $cn.${f.name} List size=${v.size}")
                            v is Map<*, *> -> StealthLog.i("$TAG: DUMP-ADAPTER $cn.${f.name} Map size=${v.size}")
                            v is Array<*> -> StealthLog.i("$TAG: DUMP-ADAPTER $cn.${f.name} Array[${v.size}]")
                            tn == "int" || tn == "Integer" -> {
                                if (v is Int && v > 0 && v < 10000) // 只报可疑的计数器
                                    StealthLog.i("$TAG: DUMP-ADAPTER $cn.${f.name} int=$v")
                            }
                        }
                    }
                }
                c = c.superclass
            }
        } catch (t: Throwable) { StealthLog.w("$TAG: DUMP-ADAPTER failed", t) }
    }

    /** 读取 adapter 的 data 字段引用（ArrayList，不创建新对象，供就地 clear+addAll 使用） */
    private fun readAdapterData(adapter: Any): ArrayList<Any?>? {
        var c: Class<*>? = adapter.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name == "data") {
                    f.isAccessible = true
                    val v = f.get(adapter)
                    return if (v is ArrayList<*>) @Suppress("UNCHECKED_CAST") (v as ArrayList<Any?>)
                    else null
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 设置 adapter(xm3.t0) 的 data 字段（WxRecyclerAdapter.data，定义在父类），使显示列表与 f152040o 一致 */
    private fun setAdapterData(adapter: Any, data: ArrayList<*>) {
        var c: Class<*>? = adapter.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name == "data") {
                    f.isAccessible = true
                    f.set(adapter, data)
                    StealthLog.i("$TAG: setAdapterData -> ${data.size} items (field in ${c.simpleName})")
                    return
                }
            }
            c = c.superclass
        }
        // 诊断：字段没找到时，列出适配器层级的所有字段名
        val allFields = ArrayList<String>()
        c = adapter.javaClass
        while (c != null) {
            allFields.addAll(c.declaredFields.map { "${c!!.simpleName}.${it.name}" })
            c = c.superclass
        }
        StealthLog.w("$TAG: setAdapterData: no 'data' field in adapter hierarchy (${adapter.javaClass.name}). Fields: ${allFields.joinToString(", ")}")
    }

    /** 读回 adapter 的 data 字段大小，用于诊断 setAdapterData 是否生效 */
    private fun readAdapterDataFieldSize(adapter: Any): Int {
        var c: Class<*>? = adapter.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name == "data") {
                    f.isAccessible = true
                    val v = f.get(adapter)
                    return if (v is List<*>) v.size else if (v != null) -2 else -1
                }
            }
            c = c.superclass
        }
        return -3 // not found
    }
}
