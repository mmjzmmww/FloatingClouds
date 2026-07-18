package top.mmjz.floatingclouds.plugin.part

import android.app.AlertDialog
import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.reflect.Field
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.ClipboardUtil
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog
import io.github.libxposed.api.XposedInterface

/**
 * #4 隐藏自己朋友圈 — 8.0.74 终版（数据层过滤，album + timeline + profile 三端）
 *
 * ⚠️ 关键：jadx 反编译里看到的 `f426127a / f426128b / f426142d / f453343y0 / f453340l1` 全是
 *    jadx 的“renamed from”改名结果，**运行时 dex 里的真实字段名是 a / b / d / y0 / l1**。
 *    反射必须用真实名，否则 getField 永远 NoSuchField → 过滤静默失效。
 *
 * 一、朋友圈相册（SnsAlbumUI）网格
 *   适配器 ub4.i（SnsAlbumAdapter extends f2），数据在字段 d（List<section c>）。
 *   section c 真实字段：a = List<SnsInfo>（本分组图片），b = String（分组类型："my_timeline"/"loading"/日期串）。
 *   数据由异步 helper ub4.k（SnsAlbumAdapterHelper）在其 c(List) 方法里填充：
 *      d.clear(); add(my_timeline); addAll(list); [add(loading)]; notifyDataSetChanged();
 *   → 黄金 hook = ub4.k.c(List)，在 addAll 之前过滤 list（移除 hidden SnsInfo、移除空分组），
 *     数据进入 d 时已是干净的，后续 onBindViewHolder / getItemCount 全部自然正确，无留白、无卡加载。
 *
 * 二、朋友圈 timeline（ImproveSnsTimelineUI）
 *   适配器 h2（com.tencent.mm.plugin.sns.ui.improve.component.h2 extends xm3.t0=WxRecyclerAdapter），
 *   data 字段 = ArrayList<zc4.b>（WxRecyclerAdapter 的 "data"，真实名即 data，未被改名）。
 *   每次加载/刷新/loadMore 都走 h2.k(o0)（onUpdateAdapter）：先按 diff 增量 notify，再写 data。
 *   → hook h2.k(o0)：先 proceed()（增量 notify 跑在原始 data 上，不破坏 diff 位置），
 *     再过滤 data（移除 hidden zc4.b）、写回 data 字段、notifyDataSetChanged() 全量重排。
 *
 * 三、snsId 归一化与匹配
 *   隐藏列表存的 22 位 server snsId（带前导零，无前缀）。SnsInfo.field_snsId = "sns_table_<22位>"。
 *   zc4.b → n()→xc4.p；xc4.p 真实字段 l1（StateFlow）→getValue() = SnsInfo（field_snsId 同口径）；
 *   或 xc4.p 真实字段 y0（StateFlow）→getValue() = server snsId 长整/字符串。
 *   统一 normalizeId() 去前导零后比较；shouldHide() 收集 item 所有候选 id，命中隐藏集合任一即隐藏，
 *   不依赖任何单个方法名，抗 jadx 改名。
 *
 * 四、资料页朋友圈入口预览（SnsPreference 的 4 张缩略图）
 *   SnsPreference.t(View) 调 N(this.Z) 把 Z（最近动态 media jj4 列表）填进 4 个 ImageView。
 *   N 被异步 d0 反复调用、jj4 无 snsId，且预览数据来自服务端推送事件（RecentlySnsMediaObjEvent），
 *   本地 SnsInfo 库此刻往往尚未打开 → 无法按条识别隐藏动态（查库路径实测必失败）。
 *   故采用"自己的资料页 + 有隐藏动态 → 直接清空 4 张缩略图"策略：读微信自身
 *   com.tencent.mm_preferences 的 login_weixin_username 判定是否自己的资料页，是则清空预览，
 *   彻底杜绝隐藏图泄漏；非自己资料页（好友）则放行原生预览。零混淆反射、零本地库依赖。
 */

class HideOwnSnsPluginPart : IPlugin {

    companion object {
        private const val TAG = "HideOwnSns"
        // 8.0.74 运行时真实类名
        private const val ALBUM_HELPER = "ub4.k"      // SnsAlbumAdapterHelper（数据注入）
        private const val ALBUM_ADAPTER = "ub4.i"      // SnsAlbumAdapter
        private const val TIMELINE_ADAPTER = "com.tencent.mm.plugin.sns.ui.improve.component.h2"
        private const val WX_RECYCLER_VIEW = "com.tencent.mm.view.recyclerview.WxRecyclerView"
        // 「我的朋友圈」SnsUserUI → SnsSelfAdapter(so) 走 ListView，数据由 helper vo(SnsSelfAdapterHelper) 注入
        private const val SELF_HELPER = "com.tencent.mm.plugin.sns.ui.vo"  // SnsSelfAdapterHelper
        // 8.0.74 真实字段名（jadx 显示 f426127a/f426128b/f426142d 均为 renamed，真实 dex 名为 a/b/d）
        private const val ALBUM_SEC_LIST = "a"   // ub4.c.a : List<SnsInfo>
        private const val ALBUM_SEC_TYPE = "b"   // ub4.c.b : String 分组类型
        private const val ALBUM_DATA = "d"       // ub4.i.d : List<section>

        // 供 LongClickTracePluginPart 在「加入隐藏」后触发即时刷新
        @Volatile private var activeInstance: HideOwnSnsPluginPart? = null
        fun requestSelfRefresh() { activeInstance?.refreshSelfIfVisible() }
        fun requestProfileRefresh() { activeInstance?.refreshProfileIfVisible() }
        fun requestTimelineRefresh() { activeInstance?.refreshTimelineIfVisible() }
        fun requestAlbumRefresh() { activeInstance?.refreshAlbumIfVisible() }
    }

    // ── 门控 ──

    private fun isEnabled(): Boolean =
        ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideOwnSns

    /** 归一化后的隐藏 id 集合（去前导零，统一口径） */
    private fun hiddenSnsIds(): Set<String> =
        ConfigUtil.getHiddenOwnSnsIds().map { normalizeId(it) }.toSet()

    // 捕获的适配器实例，供"加入隐藏后立即刷新"使用
    @Volatile private var albumAdapterRef: Any? = null
    @Volatile private var timelineAdapterRef: Any? = null
    @Volatile private var albumRefreshing = false
    @Volatile private var timelineRefreshing = false
    // SnsPreference 实例 → 目标 username（M() 捕获，供 N() 过滤用）
    private val prefUserNames = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())
    // 「我的朋友圈」即时刷新：捕获 vo 实例 + 最近一次完整（未过滤）list
    @Volatile private var selfHelperRef: Any? = null
    @Volatile private var selfRawListRef: MutableList<Any?>? = null
    // 自己的 wxid 缓存（读微信 com.tencent.mm_preferences.login_weixin_username，仅首次解析）
    @Volatile private var cachedSelfWxId: String? = null
    @Volatile private var selfWxIdResolved = false

    // ═══════════════════════════════════════
    //  handleHook 入口
    // ═════════════════════════════════════

    override fun handleHook(session: HookSession) {
        activeInstance = this
        StealthLog.i("$TAG: handleHook START (album+timeline+profile data-layer)")
        hookTimelineAdapter(session)   // h2.k(o0) 过滤 + 捕获
        hookAlbumDataLoad(session)     // ub4.k.c(List) 注入前过滤
        hookSelfSnsDataLoad(session)   // vo.c(List) 注入前过滤（我的朋友圈 ListView）
        hookAlbumAdapter(session)      // WxRecyclerView.setAdapter 捕获 ub4.i / h2（即时刷新）
        hookProfileSnsPreview(session) // SnsPreference.N 兜底过滤资料页朋友圈入口 4 图预览
        hookSnsCommentDetail(session)  // SnsCommentDetailUI.onCreate/B7 命中即 finish（详情页隐藏）
        hookSnsMsgList(session)        // ③ 朋友圈消息中心(SnsMsgUI) 通知列表游标过滤，堵住隐藏泄漏
        StealthLog.i("$TAG: handleHook DONE")
    }

    // ═══════════════════════════════════════
    //  四、资料页朋友圈入口预览（SnsPreference 的 4 张缩略图）
    //  SnsPreference.t(View) 调 N(this.Z) 把 Z（最近动态 media jj4 列表）填进 4 个 ImageView。
    //  N 被异步 d0 反复调用、jj4 无 snsId，且预览来自服务端推送、本地库此刻未打开，无法按条过滤。
    //  策略：自己资料页(uname==login_weixin_username) + 有隐藏动态 → 直接清空 4 图；其余放行原生。
    // ═════════════════════════════════════

    private fun hookProfileSnsPreview(session: HookSession) {
        // 兜底过滤点：SnsPreference.M(username) 捕获 username；N(List) 直接过滤显示列表
        session.findAndHookByPredicate(
            "com.tencent.mm.pluginsdk.ui.preference.SnsPreference",
            { m -> m.name == "M" && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java }
        ) { chain ->
            runCatching {
                val uname = chain.args.getOrNull(0) as? String
                val pref = chain.thisObject
                if (!uname.isNullOrBlank() && pref != null) prefUserNames[pref] = uname
            }
            chain.proceed()
        }
        session.findAndHookByPredicate(
            "com.tencent.mm.pluginsdk.ui.preference.SnsPreference",
            { m -> m.name == "N" && m.parameterTypes.size == 1 }
        ) { chain -> snsPreviewNInterceptor(chain) }
    }

    // ═══════════════════════════════════════
    //  五、单条详情页隐藏（SnsCommentDetailUI）
    //  用户从通知/搜索/分享打开"已隐藏朋友圈"详情页时直接 finish。零闪烁。
    //  方案 A：onCreate(Bundle) after 读字段 G(server snsId) 命中即 finish —— 渲染前关闭。
    //  方案 B：B7(boolean) after 取返回 SnsInfo.getSnsId() 命中即 finish —— 兜底分享/通知解析场景。
    //  G/H/B7 均为真实名（jadx 无 renamed-from 注释），与 SnsInfo.getSnsId() 同口径。
    // ═══════════════════════════════════════

    private fun hookSnsCommentDetail(session: HookSession) {
        val cls = "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI"
        // 方案 A：onCreate after 读 G（主路径，零闪烁）
        session.findAndHookByPredicate(
            cls,
            { m -> m.name == "onCreate" && m.parameterTypes.size == 1 && m.parameterTypes[0] == android.os.Bundle::class.java }
        ) { chain ->
            chain.proceed()
            runCatching { finishIfHiddenSnsDetail(chain.thisObject) }
        }
        // 方案 B：B7(boolean) after 兜底（覆盖分享链接/通知本地无缓存时的实时解析）
        session.findAndHookByPredicate(
            cls,
            { m -> m.name == "B7" && m.parameterTypes.size == 1 && m.parameterTypes[0] == java.lang.Boolean.TYPE }
        ) { chain ->
            val ret = chain.proceed()
            runCatching {
                val snsInfo = ret as? Any ?: return@runCatching
                val rawSnsId = callMethod(snsInfo, "getSnsId") as? String ?: return@runCatching
                if (matchesHiddenSnsId(rawSnsId)) {
                    (chain.thisObject as? android.app.Activity)?.finish()
                }
            }
            ret
        }
    }

    /** SnsCommentDetailUI.G 是 server snsId；命中隐藏即 finish */
    private fun finishIfHiddenSnsDetail(target: Any?) {
        if (!isEnabled()) return
        if (target !is android.app.Activity) return
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return
        val rawSnsId = getFieldValue(target, "G") as? String ?: return
        if (matchesHiddenSnsId(rawSnsId)) {
            target.finish()
        }
    }

    /**
     * 把"可能的 snsId 字符串"（"sns_table_<22位>" / "sns_table_<localid>" / 裸 22 位）归一化
     * （去前缀 + 去前导零），与 ConfigUtil.getHiddenOwnSnsIds() 口径完全一致后比对。
     */
    private fun matchesHiddenSnsId(raw: String): Boolean {
        if (raw.isBlank()) return false
        val normalized = normalizeId(
            raw.removePrefix("sns_table_").removePrefix("ad_table_")
        )
        if (normalized.isEmpty()) return false
        return hiddenSnsIds().contains(normalized)
    }

    /**
     * 兜底：SnsPreference.N(List<jj4>) 入口。
     * 预览图来自服务端推送（RecentlySnsMediaObjEvent），jj4 无 snsId、本地库此刻未打开，
     * 无法按条识别隐藏动态。故策略：当资料页属于"自己"且存在隐藏动态时，直接清空
     * 该 4 张缩略图列表，彻底杜绝隐藏图泄漏；非自己资料页（好友）则放行原生预览。
     * username 仅来自 M(String) 捕获（e0 中 snsPreference.M(contact.d1())）。
     */
    private fun snsPreviewNInterceptor(chain: XposedInterface.Chain): Any? {
        if (!isEnabled()) return chain.proceed()
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return chain.proceed()
        try {
            val list = chain.args.getOrNull(0) as? MutableList<Any?> ?: return chain.proceed()
            val pref = chain.thisObject
            val uname = prefUserNames[pref]
            if (uname.isNullOrBlank()) {
                StealthLog.w("$TAG: N intercept: username not captured, skip (native)")
                return chain.proceed()
            }
            val selfWxId = getSelfWxId()
            if (selfWxId != null && uname == selfWxId) {
                // 自己资料页 + 有隐藏动态 → 清空预览，隐藏图不再出现
                list.clear()
                StealthLog.i("$TAG: N intercept: SELF profile ($uname) has hidden moments -> blank 4 previews")
            } else {
                StealthLog.i("$TAG: N intercept: profile $uname != self, native preview kept")
            }
        } catch (e: Throwable) {
            StealthLog.e("$TAG: N intercept error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return chain.proceed()
    }

    /**
     * 读微信自身 SharedPreferences 的 login_weixin_username，得到当前登录 wxid（即"自己"）。
     * 该键稳定、持久、无混淆反射；用于判定资料页是否属于自己。
     */
    private fun getSelfWxId(): String? {
        if (selfWxIdResolved) return cachedSelfWxId
        val id = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.content.Context
            val prefs = app?.getSharedPreferences("com.tencent.mm_preferences", 0)
            prefs?.getString("login_weixin_username", null)
        }.getOrNull()
        cachedSelfWxId = id
        selfWxIdResolved = true
        return id
    }

    // ═══════════════════════════════════════
    //  一、Timeline：hook h2.k(o0)
    // ═════════════════════════════════════

    private fun hookTimelineAdapter(session: HookSession) {
        val handles = session.findAndHookByPredicate(
            TIMELINE_ADAPTER,
            { m -> m.name == "k" && m.parameterTypes.size == 1 }
        ) { chain -> timelineKInterceptor(chain) }

        if (handles.isNotEmpty()) {
            StealthLog.i("$TAG: hooked $TIMELINE_ADAPTER.k(o0)")
        } else {
            StealthLog.w("$TAG: $TIMELINE_ADAPTER.k(o0) NOT hooked")
        }
    }

    private fun timelineKInterceptor(chain: XposedInterface.Chain): Any? {
        // 先让微信按 diff 增量刷新（在原始 data 上，diff 位置一致，不会错位）
        val result = chain.proceed()
        if (isEnabled() && !timelineRefreshing) {
            val hidden = hiddenSnsIds()
            if (hidden.isNotEmpty()) {
                timelineRefreshing = true
                try {
                    val adapter = chain.thisObject
                    val data = getDataList(adapter)
                    if (data != null) {
                        val filtered = ArrayList<Any?>()
                        for (item in data) {
                            if (!shouldHide(item, hidden)) filtered.add(item)
                        }
                        if (filtered.size != data.size) {
                            setDataField(adapter, filtered)
                            notifyAdapter(adapter)
                            StealthLog.i("$TAG: timeline hide removed=${data.size - filtered.size}")
                        }
                    }
                } catch (e: Throwable) {
                    // 静默失败
                } finally {
                    timelineRefreshing = false
                }
            }
        }
        return result
    }

    // ═══════════════════════════════════════
    //  二、Album：hook ub4.k.c(List)
    // ═════════════════════════════════════

    private fun hookAlbumDataLoad(session: HookSession) {
        val handles = session.findAndHookByPredicate(
            ALBUM_HELPER,
            { m -> m.name == "c" && m.parameterTypes.size == 1 && m.parameterTypes[0] == List::class.java }
        ) { chain -> albumDataLoadInterceptor(chain) }

        if (handles.isNotEmpty()) {
            StealthLog.i("$TAG: hooked $ALBUM_HELPER.c(List)")
        } else {
            StealthLog.w("$TAG: $ALBUM_HELPER.c(List) NOT hooked")
        }
    }

    private fun albumDataLoadInterceptor(chain: XposedInterface.Chain): Any? {
        if (isEnabled()) {
            val list = chain.args.getOrNull(0) as? MutableList<*>
            val hidden = hiddenSnsIds()
            if (list != null && hidden.isNotEmpty()) {
                filterSectionsList(list, hidden)
            }
        }
        return chain.proceed()
    }

    /**
     * 过滤日期分组 sections：从每个 section.a（真实字段名）移除 hidden SnsInfo，
     * 移除被清空的非 loading / 非 my_timeline 分组（防 onBind 取 get(0) 越界崩）。
     */
    private fun filterSectionsList(sections: MutableList<*>, hidden: Set<String>) {
        val iter = sections.listIterator()
        while (iter.hasNext()) {
            val section = iter.next() ?: continue
            val type = getFieldValue(section, ALBUM_SEC_TYPE) as? String
            val snsList = (getFieldValue(section, ALBUM_SEC_LIST) as? MutableList<Any?>) ?: continue
            val it = snsList.iterator()
            while (it.hasNext()) {
                val sns = it.next()
                if (shouldHide(sns, hidden)) it.remove()
            }
            if (snsList.isEmpty() && type != "loading" && type != "my_timeline") {
                iter.remove()
            }
        }
    }

    /** 统计 sections 内 SnsInfo 总数（即时刷新前/后比较用） */
    private fun countSns(sections: MutableList<*>): Int {
        var n = 0
        for (section in sections) {
            val snsList = getFieldValue(section, ALBUM_SEC_LIST) as? List<*> ?: continue
            n += snsList.size
        }
        return n
    }

    // ═══════════════════════════════════════
    //  二·B、我的朋友圈（SnsUserUI）：hook vo.c(List)
    //  vo = SnsSelfAdapterHelper，c(List) → d(true,list) 构建 maps/count 装配到 so 适配器。
    //  在 c(List) 入口过滤 list（移除 hidden SnsInfo），maps/count 自然基于过滤后列表重建，零错位。
    // ═════════════════════════════════════

    private fun hookSelfSnsDataLoad(session: HookSession) {
        val handles = session.findAndHookByPredicate(
            SELF_HELPER,
            { m -> m.name == "c" && m.parameterTypes.size == 1 && m.parameterTypes[0] == java.util.List::class.java }
        ) { chain -> selfSnsDataLoadInterceptor(chain) }

        if (handles.isNotEmpty()) {
            StealthLog.i("$TAG: hooked $SELF_HELPER.c(List)")
        } else {
            StealthLog.w("$TAG: $SELF_HELPER.c(List) NOT hooked")
        }
    }

    private fun selfSnsDataLoadInterceptor(chain: XposedInterface.Chain): Any? {
        if (isEnabled()) {
            val list = chain.args.getOrNull(0) as? MutableList<Any?>
            val hidden = hiddenSnsIds()
            if (list != null && hidden.isNotEmpty()) {
                // 缓存完整（未过滤）list + vo 实例，供「加入隐藏」即时刷新复用
                selfHelperRef = chain.thisObject
                selfRawListRef = ArrayList(list)
                val it = list.iterator()
                while (it.hasNext()) {
                    val sns = it.next()
                    if (shouldHide(sns, hidden)) it.remove()
                }
            }
        }
        return chain.proceed()
    }

    // ═══════════════════════════════════════
    //  三、捕获适配器（WxRecyclerView.setAdapter）+ 即时刷新
    // ═════════════════════════════════════

    private fun hookAlbumAdapter(session: HookSession) {
        val triggerClasses = listOf(
            WX_RECYCLER_VIEW,                          // 微信列表标准 RecyclerView 子类
            "androidx.recyclerview.widget.RecyclerView" // 兜底
        )
        for (cls in triggerClasses) {
            val handles = session.findAndHookByPredicate(
                cls,
                { m -> m.name == "setAdapter" && m.parameterTypes.size == 1 }
            ) { chain ->
                val adapter = chain.args.getOrNull(0)
                chain.proceed() // 永远先放行，保证微信原生 setAdapter 正常执行
                val adapterName = adapter?.javaClass?.name ?: ""
                if (adapterName == ALBUM_ADAPTER) {
                    albumAdapterRef = adapter
                    refreshAlbumIfVisible()
                } else if (adapterName == TIMELINE_ADAPTER || isTimelineAdapter(adapter)) {
                    timelineAdapterRef = adapter
                    refreshTimelineIfVisible()
                }
                null
            }
            if (handles.isNotEmpty()) {
                StealthLog.i("$TAG: $cls.setAdapter hooked (${handles.size})")
            } else {
                StealthLog.w("$TAG: $cls.setAdapter hook FAILED")
            }
        }
    }

    /** 相册已打开且数据已加载时，立即过滤并刷新（覆盖"加入隐藏"即时生效场景） */
    private fun refreshAlbumIfVisible() {
        val ad = albumAdapterRef ?: return
        if (!isEnabled()) return
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val sections = getFieldValue(ad, ALBUM_DATA) as? MutableList<Any?> ?: return@runCatching
                val before = countSns(sections)
                filterSectionsList(sections, hidden)
                val after = countSns(sections)
                if (before != after) {
                    notifyAlbumGuarded(ad)
                }
            }
        }
    }

    /** timeline 已打开时，立即过滤并刷新 */
    private fun refreshTimelineIfVisible() {
        val ad = timelineAdapterRef ?: return
        if (!isEnabled()) return
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val data = getDataList(ad) ?: return@runCatching
                val filtered = ArrayList<Any?>()
                for (item in data) {
                    if (!shouldHide(item, hidden)) filtered.add(item)
                }
                if (filtered.size != data.size) {
                    setDataField(ad, filtered)
                    notifyAdapter(ad)
                }
            }
        }
    }

    /** 「我的朋友圈」已打开时，立即过滤并刷新（重调 vo.c 让微信重建 maps/count + 通知 adapter） */
    private fun refreshSelfIfVisible() {
        val helper = selfHelperRef ?: return
        val raw = selfRawListRef ?: return
        if (!isEnabled()) return
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val filtered = ArrayList<Any?>()
                for (item in raw) {
                    if (!shouldHide(item, hidden)) filtered.add(item)
                }
                // 重调 vo.c(filtered) → d(true,filtered) 重建 + uoVar.c(true) 刷新 ListView；
                // 会再次触发 selfSnsDataLoadInterceptor（二次过滤幂等），最终显示过滤后列表。
                callMethodListArg(helper, "c", filtered)
            }
        }
    }

    /** 资料页预览已打开时，立即用空列表重填 4 图（自己资料页 + 隐藏非空时清空） */
    private fun refreshProfileIfVisible() {
        if (!isEnabled()) return
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return
        val selfWxId = getSelfWxId() ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                for ((pref, uname) in prefUserNames) {
                    if (uname == selfWxId) {
                        // 重调 N(empty) 立即重填；会再次触发 snsPreviewNInterceptor（二次清空幂等）
                        callMethodListArg(pref, "N", ArrayList<Any?>())
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  snsId 提取 + 归一化 + 匹配
    // ═════════════════════════════════════

    /** 去前导零，统一 id 口径（"00000123" -> "123"，"" -> ""） */
    private fun normalizeId(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.trimStart('0').ifEmpty { "0" }
    }

    /** 是否应隐藏：收集该 item 的所有候选 id，命中隐藏集合任一即隐藏 */
    private fun shouldHide(item: Any?, hidden: Set<String>): Boolean {
        if (item == null) return false
        val candidates = candidateIds(item)
        return candidates.any { hidden.contains(it) }
    }

    /** 收集 item 的全部候选 id（归一化），用于与隐藏集合匹配 */
    private fun candidateIds(item: Any?): List<String> {
        if (item == null) return emptyList()
        val cn = item.javaClass.name
        return if (cn.endsWith("zc4.b") || cn.endsWith("zc4\$b")) {
            candidateIdsZc4b(item)
        } else {
            candidateIdsSnsInfo(item)
        }
    }

    /** album 的 SnsInfo：优先 server snsId（field_snsId），其次 stringSeq / localid */
    private fun candidateIdsSnsInfo(item: Any?): List<String> {
        if (item == null) return emptyList()
        val out = mutableListOf<String>()
        // 1) field_snsId "sns_table_<id>" —— 与隐藏列表同口径（server snsId）
        addNormalized(out, readField(item, "field_snsId")) { it.removePrefix("sns_table_").removePrefix("ad_table_") }
        // 2) field_stringSeq（局部序号，作为候选，命中即隐藏，不会误伤 server 集合）
        addNormalized(out, readField(item, "field_stringSeq"))
        // 3) localid / field_localId
        val localName = when {
            hasField(item, "localid") -> "localid"
            hasField(item, "field_localId") -> "field_localId"
            else -> null
        }
        if (localName != null) addNormalized(out, readField(item, localName))
        return out.distinct()
    }

    /**
     * timeline 的 zc4.b：n()→xc4.p（ImproveSnsInfo）。
     * 真实字段名（非 jadx 改名）：l1（StateFlow）→getValue()=SnsInfo（含 field_snsId）；
     * 或 y0（StateFlow）→getValue()=server snsId。均取 server 口径匹配隐藏列表。
     */
    private fun candidateIdsZc4b(item: Any?): List<String> {
        if (item == null) return emptyList()
        val out = mutableListOf<String>()
        val improve = callMethod(item, "n") ?: return emptyList()

        // 路径 A：l1 字段（StateFlow）→ getValue() = SnsInfo
        val l1 = getFieldValue(improve, "l1")
        val snsInfo = l1?.let { callMethod(it, "getValue") }
        if (snsInfo != null) {
            addNormalized(out, readField(snsInfo, "field_snsId")) { it.removePrefix("sns_table_").removePrefix("ad_table_") }
            addNormalized(out, readField(snsInfo, "field_stringSeq"))
        }

        // 路径 B：y0 字段（StateFlow）→ getValue() = server snsId（长整/字符串）
        val y0 = getFieldValue(improve, "y0")
        val y0v = y0?.let { callMethod(it, "getValue") }
        if (y0v != null) addNormalized(out, y0v.toString())

        // 兜底：v()="sns_table_<localId>"、U0()=long localId（本地号，命中概率低但无害）
        addNormalized(out, callMethod(improve, "v")?.toString()) { it.removePrefix("sns_table_").removePrefix("ad_table_") }
        val u0 = callMethod(improve, "U0")
        if (u0 != null) addNormalized(out, u0.toString())

        return out.distinct()
    }

    private fun addNormalized(out: MutableList<String>, raw: String?, transform: (String) -> String = { it }) {
        if (raw.isNullOrBlank()) return
        val t = transform(raw)
        if (t.isNotBlank()) {
            val n = normalizeId(t)
            if (n.isNotEmpty()) out.add(n)
        }
    }

    private fun readField(obj: Any?, name: String): String? {
        if (obj == null) return null
        return getFieldInHierarchy(obj.javaClass, name)?.let { f ->
            runCatching { f.isAccessible = true; f.get(obj)?.toString() }.getOrNull()
        }
    }

    private fun hasField(obj: Any?, name: String): Boolean {
        if (obj == null) return false
        return getFieldInHierarchy(obj.javaClass, name) != null
    }

    private fun callMethod(obj: Any?, name: String): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            try {
                val m = c.getDeclaredMethod(name)
                m.isAccessible = true
                return m.invoke(obj)
            } catch (_: NoSuchMethodException) {
            }
            c = c.superclass
        }
        return null
    }

    /** 带单个参数的方法调用（沿继承链查找，不依赖精确参数类型匹配） */
    private fun callMethod1(obj: Any?, name: String, arg: Any?): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == 1) {
                    m.isAccessible = true
                    return runCatching { m.invoke(obj, arg) }.getOrNull()
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 带单个 List 参数的方法调用（精确匹配 List 形参，避免误命中同名其它 arity-1 方法） */
    private fun callMethodListArg(obj: Any?, name: String, arg: Any?): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == java.util.List::class.java
                ) {
                    m.isAccessible = true
                    return runCatching { m.invoke(obj, arg) }.getOrNull()
                }
            }
            c = c.superclass
        }
        return null
    }

    // ═══════════════════════════════════════
    //  反射工具
    // ═════════════════════════════════════

    /** 沿类继承链读字段值（用真实字段名） */
    private fun getFieldValue(obj: Any?, name: String): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            try {
                val f = clazz.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun getFieldInHierarchy(clazz: Class<*>, name: String): Field? {
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != Any::class.java) {
            try {
                return currentClass.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    /** 该 adapter 是否为 timeline 适配器：其 data 列表首个非空元素为 zc4.b */
    private fun isTimelineAdapter(adapter: Any?): Boolean {
        if (adapter == null) return false
        val data = getDataList(adapter) ?: return false
        for (item in data) {
            if (item != null) {
                val cn = item.javaClass.name
                return cn.endsWith("zc4.b") || cn.endsWith("zc4\$b")
            }
        }
        return false
    }

    /** 读 WxRecyclerAdapter.data（ArrayList<zc4.b>，真实字段名即 data） */
    @Suppress("UNCHECKED_CAST")
    private fun getDataList(adapter: Any?): MutableList<Any?>? {
        var c: Class<*>? = adapter?.javaClass
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField("data")
                f.isAccessible = true
                return f.get(adapter) as? MutableList<Any?>
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        return null
    }

    /** 写 WxRecyclerAdapter.data 字段（与 E0(ArrayList) setter 等价） */
    private fun setDataField(adapter: Any?, list: Any?) {
        var c: Class<*>? = adapter?.javaClass
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField("data")
                f.isAccessible = true
                f.set(adapter, list)
                return
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
    }

    /** 在 adapter 类继承链上找 notifyDataSetChanged()（0 参 void） */
    private fun findNotifyDataSetChanged(adapter: Any?): java.lang.reflect.Method? {
        if (adapter == null) return null
        var c: Class<*>? = adapter.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.parameterTypes.isEmpty() && m.returnType == Void.TYPE &&
                    m.name == "notifyDataSetChanged"
                ) return m
            }
            c = c.superclass
        }
        return null
    }

    /** 触发 adapter.notifyDataSetChanged */
    private fun notifyAdapter(adapter: Any?) {
        if (adapter == null) return
        val ndc = findNotifyDataSetChanged(adapter) ?: return
        runCatching {
            ndc.isAccessible = true
            ndc.invoke(adapter)
        }
    }

    /** 防重入触发 ub4.i.notifyDataSetChanged（album 即时刷新用） */
    private fun notifyAlbumGuarded(adapter: Any?) {
        if (adapter == null) return
        val ndc = findNotifyDataSetChanged(adapter) ?: return
        albumRefreshing = true
        try {
            ndc.isAccessible = true
            ndc.invoke(adapter)
        } catch (_: Throwable) {
        } finally {
            albumRefreshing = false
        }
    }

    // ═══════════════════════════════════════
    //  弹框（保留，供后续 RecyclerView 长按接线使用）
    // ═════════════════════════════════════

    private fun showHideDialog(context: Context?, snsId: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("朋友圈隐藏")
            .setMessage("SnsId: $snsId")
            .setNeutralButton("复制") { _, _ ->
                ClipboardUtil.copy(snsId)
                Toast.makeText(ctx, "已复制 SnsId", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("加入隐藏") { _, _ ->
                ConfigUtil.addHiddenOwnSnsId(snsId)
                Toast.makeText(ctx, "刻舟求剑", Toast.LENGTH_SHORT).show()
                // 加入后立即刷新所有可见界面（album / timeline / 我的朋友圈 / 资料页预览）
                refreshTimelineIfVisible()
                refreshAlbumIfVisible()
                refreshSelfIfVisible()
                refreshProfileIfVisible()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ═══════════════════════════
    //  ③ 朋友圈通知泄漏拦截（SnsMsgUI 消息中心）
    //  SnsMsgUI 的 adapter = bm（extends z9 游标适配器），数据由 q() 内
    //  `select *, rowid from SnsComment where isSend = 0 order by createTime desc LIMIT n`
    //  查询得到，存入 z9.f212584f（Cursor）。我们在 q() 之后把该 Cursor 包成
    //  过滤游标：跳过「所属 moment 已被隐藏」的赞/评通知行，从数据层消除泄漏。
    //  匹配方式：每行 snsID(long) → l4.Fj().W0(long) 取回 SnsInfo → 读 field_stringSeq
    //  （22 位 server id，与隐藏集合同口径）→ normalize 后比对。这条路径与微信
    //  SnsMsgUI.java:242 自身解析通知所属 moment 完全一致，抗 jadx 改名、无 long 溢出问题。
    // ═══════════════════════════

    private fun hookSnsMsgList(session: HookSession) {
        val cls = "com.tencent.mm.plugin.sns.ui.bm"
        val handles = session.findAndHookByPredicate(
            cls,
            { m -> m.name == "q" && m.parameterTypes.isEmpty() }
        ) { chain ->
            chain.proceed()            // 先让微信完成游标查询 + 原生 notify
            runCatching { hookSnsMsgListAfter(chain) }
            null
        }
        if (handles.isNotEmpty()) StealthLog.i("$TAG: hooked $cls.q() (notify list filter)")
        else StealthLog.w("$TAG: $cls.q() NOT hooked")
    }

    private fun hookSnsMsgListAfter(chain: XposedInterface.Chain) {
        if (!isEnabled()) return
        val hidden = hiddenSnsIds()
        if (hidden.isEmpty()) return
        val adapter = chain.thisObject ?: return
        val cursor = getFieldValue(adapter, "f212584f") as? Cursor ?: return
        if (cursor is SnsMsgFilterCursor) return  // 已包装，幂等（防 onNotifyChange 重入重复包装）
        val wrapper = SnsMsgFilterCursor(cursor, hidden)
        // 替换游标 + 重置缓存计数 + 清空按位置缓存，触发干净重排（无留白、无错位）
        setFieldValue(adapter, "f212584f", wrapper)
        setFieldValue(adapter, "f212587i", -1)
        setFieldValue(adapter, "f212585g", null)
        notifyAdapter(adapter)
        // 诊断：每次 bm.q() 触发都打一行全量候选 + 命中/丢弃明细，方便用户排查"为啥没隐藏"
        StealthLog.i(
            "$TAG: SnsMsgUI notify scan: total=${wrapper.totalCount} kept=${wrapper.validPositions.size} " +
            "removed=${wrapper.removedCount} hiddenSet=${hidden.size} candidates=${wrapper.diagCandidates()}"
        )
    }

    /**
     * 把 SnsComment 里的溢出负 long 反推为 22 位 server snsId 字符串。
     * 微信算法（ca4.z0.t0 / w0.a）：
     *   long 二进制补码 → BigInteger → toString → 不足 22 位则前导补 0。
     * 不依赖任何 SnsInfo / DB 查询，纯计算，O(1)。
     */
    private fun longToSnsIdString(j17: Long): String {
        if (j17 == 0L) return ""
        val s = java.math.BigInteger(java.lang.Long.toBinaryString(j17), 2).toString(10)
        if (s.length >= 22) return s
        return "0".repeat(22 - s.length) + s
    }

    /** 写字段值（沿继承链），用于替换 z9 的游标/计数/缓存字段 */
    private fun setFieldValue(obj: Any?, name: String, value: Any?) {
        if (obj == null) return
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            try {
                val f = clazz.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
    }

    /**
     * 过滤游标：跳过所属 moment 已被隐藏的 SnsComment 行（朋友圈消息中心防泄漏）。
     * 构造时一次性算出"可见行在底层游标中的真实位置"，重写 getCount/moveToPosition，
     * 其余 getX 全部委托底层游标（已 moveTo 到对应行），对 adapter 完全透明。
     */
    private inner class SnsMsgFilterCursor(
        private val wrapped: Cursor,
        private val hidden: Set<String>
    ) : CursorWrapper(wrapped) {

        internal val validPositions: IntArray
        internal val candidates: Array<String>

        /** 被过滤掉的行数（调试/确认用） */
        val removedCount: Int
            get() = totalCount - validPositions.size

        internal val totalCount: Int

        init {
            val colIdx = wrapped.getColumnIndex("snsID")
            val list = ArrayList<Int>()
            val candList = ArrayList<String>()
            val savedPos = wrapped.position
            var total = 0
            try {
                if (colIdx >= 0 && wrapped.moveToFirst()) {
                    do {
                        total++
                        val snsId = wrapped.getLong(colIdx)
                        val seq = longToSnsIdString(snsId)
                        val normalized = if (seq.isEmpty()) "?" else normalizeId(seq)
                        val hit = hidden.contains(normalized)
                        candList += "0x${java.lang.Long.toHexString(snsId)}->$normalized${if (hit) "[HIDDEN]" else ""}"
                        if (!hit) {
                            list.add(wrapped.position)
                        }
                    } while (wrapped.moveToNext())
                }
            } catch (_: Throwable) {
                // 解析失败则保留全部行（安全回退，不丢通知）
            } finally {
                try { wrapped.moveToPosition(savedPos) } catch (_: Throwable) {}
            }
            validPositions = list.toIntArray()
            totalCount = total
            candidates = candList.toTypedArray()
        }

        /** 诊断：返回全部候选（候选snsId 归一化 + 是否命中），便于日志排查"为啥没隐藏" */
        fun diagCandidates(): String = candidates.joinToString(" | ")

        override fun getCount(): Int = validPositions.size

        override fun moveToPosition(position: Int): Boolean {
            if (position < -1 || position >= validPositions.size) return false
            return super.moveToPosition(if (position < 0) position else validPositions[position])
        }

        override fun move(offset: Int): Boolean = moveToPosition(this.position + offset)
        override fun moveToFirst(): Boolean = moveToPosition(0)
        override fun moveToLast(): Boolean = moveToPosition(validPositions.size - 1)
        override fun moveToNext(): Boolean = moveToPosition(this.position + 1)
        override fun moveToPrevious(): Boolean = moveToPosition(this.position - 1)
    }
}
