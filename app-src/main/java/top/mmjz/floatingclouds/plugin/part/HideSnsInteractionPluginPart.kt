package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 帖子详情页 — 多层数据拦截 + 深度子列表级联清洁（#5）。
 *
 * 微信 8.0.74：评论/点赞列表 adapter = SnsCommentDetailUI$CommentListAdapter（BaseAdapter），
 * 持有两条 LinkedList（评论、点赞），元素为 e86（protobuf，public 字段
 * f373101d = 评论/点赞者 username，f373102e = 回复对象 username）。
 *
 * 做法（数据层，无 GONE）：
 * ① CommentListAdapter 构造器：创建前过滤两条 LinkedList（评论兼判回复对象）；
 * ② SnsCommentDetailUI.initView：过滤已持有的 adapter 列表 + notify；
 * ③ notifyDataSetChanged 兜底：notify 时再过滤。
 *
 * 注：8.0.74 中 SnsCommentDetailUI 并不存在 w7(SnsInfo, PhotosContent, int) 方法（旧版假设失效），
 * 故改为对 CommentListAdapter 构造器 + initView + notify 三层拦截，覆盖评论与点赞两条 LinkedList，
 * 作用等价于原 w7 意图且更稳健。
 */
class HideSnsInteractionPluginPart : IPlugin {

    override fun handleHook(session: HookSession) {
        var ok = 0; var fail = 0
        if (hookCtor(session)) ok++ else fail++
        if (hookInitView(session)) ok++ else fail++
        if (hookNotifyChanged(session)) ok++ else fail++
        if (hookSetLikedHeader(session)) ok++ else fail++
        if (hookImproveTimelineInteraction(session)) ok++ else fail++
        if (hookSnsMsgList(session)) ok++ else fail++
        StealthLog.i("[HideSnsInteraction] registered $ok/6 OK")
    }

    // ⑥ 朋友圈"互动消息"通知页（SnsMsgUIWithAll/SnsMsgUI，adapter=bm extends com.tencent.mm.ui.z9，cursor 驱动）。
    // 通知项 = storage.v1(SnsComment)，发送者 wxid 在 DB 列 talker（v1.field_talker）；q2.r(v1) 得到的 e86 只有内容无 wxid。
    // z9.getCount()/getItem(i) 都经 j() 取 cursor，故 hook z9.j()（仅对 bm 实例生效），把返回 cursor 包一层
    // TalkerFilterCursor：预扫描剔除 talker∈密友 的行，做位置映射 → getCount/getItem/getView 全部一致、无空行。
    private fun hookSnsMsgList(session: HookSession): Boolean {
        return try {
            val handle = session.findAndHookByPredicate(
                "com.tencent.mm.ui.z9",
                { m -> m.name == "j" && m.parameterTypes.isEmpty() && android.database.Cursor::class.java.isAssignableFrom(m.returnType) }
            ) { chain ->
                val raw = chain.proceed()
                val cur = raw as? android.database.Cursor ?: return@findAndHookByPredicate raw
                // 仅作用于朋友圈互动消息 adapter bm，且开关开启
                if (!isActive() || chain.thisObject.javaClass.name != "com.tencent.mm.plugin.sns.ui.bm") return@findAndHookByPredicate cur
                if (cur is TalkerFilterCursor) return@findAndHookByPredicate cur
                wrapMsgCursor(cur, maskedSet())
            }
            if (handle.isNotEmpty()) { StealthLog.i("[HideSnsInteraction] OK snsMsg cursor hook"); true }
            else { StealthLog.w("[HideSnsInteraction] snsMsg: z9.j not found"); false }
        } catch (t: Throwable) { StealthLog.e("[HideSnsInteraction] snsMsg FAILED", t); false }
    }

    /** 缓存 base cursor -> 过滤包装（base 变更即重建）；避免 j() 高频调用反复扫描 */
    private val msgCursorCache = java.util.WeakHashMap<android.database.Cursor, TalkerFilterCursor>()

    private fun wrapMsgCursor(base: android.database.Cursor, masked: Set<String>): android.database.Cursor {
        return try {
            msgCursorCache[base]?.let { if (!it.isClosed) return it }
            val w = TalkerFilterCursor(base, masked)
            msgCursorCache[base] = w
            if (w.hiddenCount > 0) StealthLog.i("[HideSnsInteraction] snsMsg filter ${w.hiddenCount}")
            w
        } catch (t: Throwable) { StealthLog.w("[HideSnsInteraction] snsMsg wrap fail: ${t.message}"); base }
    }

    private fun isActive() =
        ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideSnsInteraction && ConfigUtil.getMaskList().isNotEmpty()

    private fun maskedSet() = ConfigUtil.getMaskList().map { it.maskId }.toHashSet()

    // ① 构造器
    private fun hookCtor(session: HookSession): Boolean {
        val innerCls = RuntimeClassResolver.commentListAdapterCls ?: run {
            StealthLog.w("[HideSnsInteraction] ctor: CommentListAdapter cls NULL"); return false
        }
        val ctors = innerCls.declaredConstructors.filter { c ->
            c.parameterTypes.count { p -> java.util.List::class.java.isAssignableFrom(p) } >= 1
        }
        if (ctors.isEmpty()) {
            StealthLog.w("[HideSnsInteraction] ctor: no List-param ctor"); return false
        }
        var ok = 0
        for (c in ctors) {
            try {
                session.hook(c).intercept { chain ->
                    if (!isActive()) return@intercept chain.proceed()
                    val masked = maskedSet()
                    for (i in chain.args.indices) {
                        (chain.args[i] as? MutableList<*>)?.let { filterList(it, masked) }
                    }
                    chain.proceed()
                }
                ok++
            } catch (t: Throwable) { StealthLog.w("[HideSnsInteraction] ctor hook fail", t) }
        }
        StealthLog.i("[HideSnsInteraction] OK ctor hooks=$ok")
        return ok > 0
    }

    // ② initView
    private fun hookInitView(session: HookSession): Boolean {
        val uiCls = RuntimeClassResolver.snsCommentDetailUICls ?: run {
            StealthLog.w("[HideSnsInteraction] initView: snsCommentDetailUICls NULL"); return false
        }
        return try {
            val handle = session.findAndHookNoArgs(uiCls.name, "initView") { chain ->
                chain.proceed()
                if (!isActive()) return@findAndHookNoArgs null
                val masked = maskedSet()
                val adapter = findCommentAdapter(chain.thisObject) ?: return@findAndHookNoArgs null
                val total = filterAdapterLists(adapter, masked)
                if (total > 0) {
                    StealthLog.i("[HideSnsInteraction] initView filter $total")
                    notify(adapter)
                }
                null
            }
            if (handle != null) { StealthLog.i("[HideSnsInteraction] OK initView"); true }
            else { StealthLog.w("[HideSnsInteraction] initView NOT FOUND"); false }
        } catch (t: Throwable) { StealthLog.e("[HideSnsInteraction] initView FAILED", t); false }
    }

    // ③ notifyDataSetChanged 兜底
    private fun hookNotifyChanged(session: HookSession): Boolean {
        val cla = RuntimeClassResolver.commentListAdapterCls ?: run {
            StealthLog.w("[HideSnsInteraction] ndc: CommentListAdapter cls NULL"); return false
        }
        return try {
            val ndc = findMethodUpward(cla, "notifyDataSetChanged")
                ?: android.widget.BaseAdapter::class.java.getDeclaredMethod("notifyDataSetChanged")
            session.hook(ndc).intercept { chain ->
                val obj = chain.thisObject
                if (!isActive() || !cla.isInstance(obj)) return@intercept chain.proceed()
                filterAdapterLists(obj, maskedSet())
                chain.proceed()
            }
            StealthLog.i("[HideSnsInteraction] OK ndc")
            true
        } catch (t: Throwable) { StealthLog.e("[HideSnsInteraction] ndc FAILED", t); false }
    }

    // ④ b8(List, boolean) = setLikedHeader：点赞头部的唯一数据源。
    // 8.0.74 点赞头像由 SnsCommentDetailUI.b8(LikeUserList, commentEmpty) 直接从 SnsObject.LikeUserList
    // 构建，不走 adapter.getView（f167040e 仅作 getCount 的存在标志）。故必须在此 before 过滤点赞列表，
    // 否则点赞永远漏（历史顽疾）。args[0] 与 adapter.f167040e / SnsObject.LikeUserList 同引用，
    // 就地移除密友项可使"点赞头部 + getCount 标志"一致；全部命中时 b8 自身走 size<=0 分支隐藏整条点赞头。
    private fun hookSetLikedHeader(session: HookSession): Boolean {
        val uiCls = RuntimeClassResolver.snsCommentDetailUICls ?: run {
            StealthLog.w("[HideSnsInteraction] b8: snsCommentDetailUICls NULL"); return false
        }
        return try {
            val handles = session.findAndHookByPredicate(
                uiCls.name,
                { m ->
                    m.name == "b8" && m.parameterTypes.size == 2 &&
                        java.util.List::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        m.parameterTypes[1] == java.lang.Boolean.TYPE
                }
            ) { chain ->
                if (isActive()) {
                    (chain.args.getOrNull(0) as? MutableList<*>)?.let { list ->
                        val n = filterList(list, maskedSet())
                        if (n > 0) StealthLog.i("[HideSnsInteraction] b8 filter $n")
                    }
                }
                chain.proceed()
            }
            if (handles.isNotEmpty()) { StealthLog.i("[HideSnsInteraction] OK b8 hooks=${handles.size}"); true }
            else { StealthLog.w("[HideSnsInteraction] b8 NOT FOUND"); false }
        } catch (t: Throwable) { StealthLog.e("[HideSnsInteraction] b8 FAILED", t); false }
    }

    // ⑤ 朋友圈主时间线（improve 架构）内联点赞/评论 —— 从模型 xc4.p 源头过滤（容器无关）。
    // 时间线可能用 ImproveInteractionLayout 或 TimelineCommentView 两种容器渲染，但都从同一模型
    // xc4.p(ImproveSnsInfo) 取数据：评论 = getCommentList()(LinkedList<e86>)，点赞 = T0()(hm5.c 包装 List)。
    // 故直接 hook 模型两个 getter 的返回值就地过滤，任何容器读到的都是已过滤数据 → 点赞/评论/分割线全一致。
    private fun hookImproveTimelineInteraction(session: HookSession): Boolean {
        val modelClsName = "xc4.p"
        return try {
            var ok = 0
            val h1 = session.findAndHookByPredicate(
                modelClsName,
                { m -> m.name == "getCommentList" && m.parameterTypes.isEmpty() }
            ) { chain ->
                val r = chain.proceed()
                if (isActive() && r is MutableList<*>) {
                    val n = filterList(r, maskedSet())
                    if (n > 0) StealthLog.i("[HideSnsInteraction] improve comment filter $n")
                }
                r
            }
            if (h1.isNotEmpty()) ok++ else StealthLog.w("[HideSnsInteraction] improve: getCommentList not found")
            val h2 = session.findAndHookByPredicate(
                modelClsName,
                { m -> m.name == "T0" && m.parameterTypes.isEmpty() }
            ) { chain ->
                val r = chain.proceed()
                if (isActive() && r != null) {
                    var c: Class<*>? = r.javaClass
                    while (c != null && c != Any::class.java) {
                        for (f in c.declaredFields) {
                            if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
                            f.isAccessible = true
                            (f.get(r) as? MutableList<*>)?.let { val n = filterList(it, maskedSet()); if (n > 0) StealthLog.i("[HideSnsInteraction] improve like filter $n") }
                        }
                        c = c.superclass
                    }
                }
                r
            }
            if (h2.isNotEmpty()) ok++ else StealthLog.w("[HideSnsInteraction] improve: T0 not found")
            StealthLog.i("[HideSnsInteraction] OK improve model hooks ok=$ok/2")
            ok > 0
        } catch (t: Throwable) { StealthLog.e("[HideSnsInteraction] improve FAILED", t); false }
    }

    // ── 工具 ──

    /** 过滤单个 LinkedList（评论 + 点赞）：评论者或回复对象命中密友即剔除 */
    private fun filterList(list: MutableList<*>, masked: Set<String>): Int {
        var r = 0
        val it = list.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (item != null && maskedUser(item, masked)) { it.remove(); r++ }
        }
        return r
    }

    /** 过滤 adapter 中所有 List 类型字段（评论 LinkedList + 点赞 LinkedList） */
    private fun filterAdapterLists(adapter: Any, masked: Set<String>): Int {
        var t = 0
        var c: Class<*>? = adapter.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val list = f.get(adapter) as? MutableList<*> ?: continue
                t += filterList(list, masked)
            }
            c = c.superclass
        }
        return t
    }

    /**
     * 判断一条互动项是否命中密友（递归深搜 wxid）。
     * ⚠️ 两个关键坑：
     *  1) jadx 把字段显示为 f373101d/f373102e，运行时真实名是 d/e（`/* renamed from: d */`）。
     *  2) 时间线点赞项是 nm5.d（继承链 d→c→b→a→j），wxid 不是直接 String 字段，而嵌在
     *     基类 j 的 `Object[] f338488a` 里（内含 e86 等）。评论项 e86 的 wxid 则是直接字段 d。
     * 为同时覆盖"直接字段"与"嵌套数组/对象"，这里不硬编码字段名，改为**深度受限、带环保护的递归**：
     * 遍历对象所有非基本类型字段/数组元素/集合元素，任一 String 等于密友 wxid 即命中。
     * （密友 id 是具体 wxid，普通字符串字段不会恰好相等，安全。）
     */
    private fun maskedUser(item: Any?, masked: Set<String>): Boolean =
        maskedDeep(item, masked, 0, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))

    private fun maskedDeep(obj: Any?, masked: Set<String>, depth: Int, seen: MutableSet<Any>): Boolean {
        if (obj == null || depth > 3 || !seen.add(obj)) return false
        when (obj) {
            is String -> return masked.contains(obj)
            is CharSequence -> return masked.contains(obj.toString())
            is Array<*> -> { for (e in obj) if (maskedDeep(e, masked, depth + 1, seen)) return true; return false }
            is Iterable<*> -> { for (e in obj) if (maskedDeep(e, masked, depth + 1, seen)) return true; return false }
        }
        val pkg = obj.javaClass.name
        // 跳过系统类（除上面已处理的 String/CharSequence/集合），避免递归爆炸
        if (pkg.startsWith("java.") || pkg.startsWith("android.") || pkg.startsWith("kotlin.") || pkg.startsWith("androidx.")) return false
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (maskedDeep(v, masked, depth + 1, seen)) return true
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return false
    }

    private fun findCommentAdapter(ui: Any): Any? {
        var c: Class<*>? = ui.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type.name.endsWith("CommentListAdapter")) {
                    f.isAccessible = true
                    return f.get(ui)
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun findMethodUpward(cls: Class<*>, name: String, vararg pts: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            try { return c.getDeclaredMethod(name, *pts).also { it.isAccessible = true } } catch (_: NoSuchMethodException) {}
            c = c.superclass
        }
        return null
    }

    private fun notify(adapter: Any) {
        runCatching {
            val m = adapter.javaClass.methods.firstOrNull {
                it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty()
            }
            m?.isAccessible = true
            m?.invoke(adapter)
        }
    }
}

/**
 * 朋友圈互动消息列表过滤游标：包装底层 cursor，预扫描剔除 talker(发送者wxid)∈密友 的行，
 * 通过位置映射对上层（bm/z9 的 getCount/getItem/getView）呈现"已过滤"视图，无空行。
 */
private class TalkerFilterCursor(
    base: android.database.Cursor,
    masked: Set<String>
) : android.database.CursorWrapper(base) {
    private val map: IntArray
    var hiddenCount: Int = 0; private set
    private var pos: Int = -1

    init {
        val vis = ArrayList<Int>()
        val col = runCatching { base.getColumnIndex("talker") }.getOrDefault(-1)
        val saved = base.position
        if (col >= 0) {
            val n = base.count
            for (i in 0 until n) {
                if (!base.moveToPosition(i)) continue
                val talker = runCatching { base.getString(col) }.getOrNull()
                if (talker != null && masked.contains(talker)) hiddenCount++ else vis.add(i)
            }
        } else {
            for (i in 0 until base.count) vis.add(i)
        }
        base.moveToPosition(saved)
        map = vis.toIntArray()
    }

    override fun getCount(): Int = map.size
    override fun getPosition(): Int = pos

    override fun moveToPosition(position: Int): Boolean {
        if (position < 0) { pos = -1; super.moveToPosition(-1); return false }
        if (position >= map.size) { pos = map.size; super.moveToPosition(super.getCount()); return false }
        pos = position
        return super.moveToPosition(map[position])
    }

    override fun move(offset: Int): Boolean = moveToPosition(pos + offset)
    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToLast(): Boolean = moveToPosition(map.size - 1)
    override fun moveToNext(): Boolean = moveToPosition(pos + 1)
    override fun moveToPrevious(): Boolean = moveToPosition(pos - 1)
    override fun isBeforeFirst(): Boolean = map.isEmpty() || pos < 0
    override fun isAfterLast(): Boolean = map.isEmpty() || pos >= map.size
    override fun isFirst(): Boolean = map.isNotEmpty() && pos == 0
    override fun isLast(): Boolean = map.isNotEmpty() && pos == map.size - 1
}
