package top.mmjz.floatingclouds.plugin.part

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.part.HideOwnSnsPluginPart
import top.mmjz.floatingclouds.util.ClipboardUtil
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog
import java.lang.reflect.Field

/**
 * #4 长按注入 —— 方案 A：AdapterView.setOnItemLongClickListener 注入方案（对齐 InkHide 已验证路线）。
 *
 * 失败史根因（真机 + 反编译实证）：
 *   微信 Sns 相册 item 内部自带 TouchListener / GestureDetector，消费了触摸事件，导致
 *   View.onTouchEvent 永不被调用 → 标准长按检测（performLongClick / OnLongClickListener）全部
 *   永不触发；此前「dispatchTouchEvent 自计时」方案在澎湃 OS3 相册（顶部 item、贴近手势热区）
 *   上事件序列被系统手势 / 微信滑动返回截断 → 自计时长按无法完成 → 不弹框。
 *
 * 正解（方案 A，2026-07-15 改造）：
 *   hook android.widget.AdapterView.setOnItemLongClickListener，把微信相册 ListView 原有的
 *   OnItemLongClickListener **包装一层**。当微信自身的长按识别链路（View.performLongClick →
 *   AdapterView 长按计时 → OnItemLongClickListener）成功完成，我们才介入：
 *     1. 判断当前 Adapter 是否为目标相册适配器（对齐 InkHide：适配器内 Activity 是
 *        SnsUserUI「我的朋友圈」页，或动态判定为 sns 包 BaseAdapter）。
 *     2. 从 adapter.getItem(position) 直取数据项，extractSnsId 拿到 snsId。
 *     3. 弹「隐藏我的朋友圈」对话框；之后继续放行微信原长按（不影响原生行为）。
 *
 * 为什么稳：完全复用微信原生长按识别，不依赖自己计时的「完整、未被打断的 DOWN→(无大MOVE)→
 *   持续 500ms→UP」事件序列；澎湃手势 / 滑动返回吞事件只会影响我们「自己计时」那套，而微信
 *   内部识别在本机是正常的（InkHide 已证实），因此本方案在澎湃 OS3 上稳定弹框。
 *
 * 识别：相册适配器 com.tencent.mm.plugin.sns.ui.so（=SnsSelfAdapter，8.0.74 已确认）。
 *   职责：本 Part 只管「长按弹框」；物理隐藏（GONE / 数据层过滤）由 HideOwnSnsPluginPart 负责，
 *   互不干扰。
 *
 * 三路径（对齐 InkHide，确保稳定弹框，适配澎湃 OS3 等激进手势系统）：
 *   - 路径 1：AdapterView.setOnItemLongClickListener 包装（主路径，微信原生长按识别链路）。
 *   - 路径 2：SnsUserUI.onResume → 捕获 ListView → AbsListView.dispatchTouchEvent 自计时长按
 *     （兜底路径，含 180ms CANCEL 延迟容错防止澎湃 OS3 手势截断）。
 *   - 路径 3：Window.Callback 层级拦截自计时长按（更深层兜底，比 dispatchTouchEvent 更底层，
 *     对激进手势系统的触摸事件截断有更好兼容性）。含 180ms CANCEL 延迟容错。
 *   - 去重：路径 2/3 共用 500ms 时间戳窗口，防止双路径重复弹框。
 */
class LongClickTracePluginPart : IPlugin {

    companion object {
        private const val TAG = "LongClickInject"
        // InkHide 判定用「我的朋友圈」相册页 Activity 类名（非混淆，固定）
        private const val SNS_USER_UI = "com.tencent.mm.plugin.sns.ui.SnsUserUI"
        // 标记 View 已被注入 View.OnLongClickListener，避免重复注入（用私有 int key 避免与资源 id 冲突）
        private const val INJECT_TAG_KEY = 0x1FC00001
    }

    private fun isEnabled(): Boolean =
        ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideOwnSns

    override fun handleHook(session: HookSession) {
        // 始终安装 hook（门控在拦截内部做），以支持运行时开关动态生效。
        StealthLog.i("=== $TAG handleHook START (setOnItemLongClickListener inject) ===")
        try {
            val handles = session.findAndHookByPredicate(
                "android.widget.AdapterView",
                { m -> m.name == "setOnItemLongClickListener" && m.parameterTypes.size == 1 }
            ) { chain -> onSetOnItemLongClickListener(chain) }

            if (handles.isNotEmpty()) {
                StealthLog.i("$TAG: AdapterView.setOnItemLongClickListener hooked (${handles.size})")
            } else {
                StealthLog.w("$TAG: AdapterView.setOnItemLongClickListener hook FAILED")
            }
        } catch (t: Throwable) {
            StealthLog.e("$TAG: hook setOnItemLongClickListener FAILED", t)
        }

        // ★ 路径 2：SnsUserUI.onResume 后捕获 ListView + hook dispatchTouchEvent 自计时长按。
        try {
            session.findAndHook(SNS_USER_UI, "onResume") { chain ->
                val activity = chain.thisObject as? android.app.Activity
                if (activity != null) {
                    activity.window?.decorView?.post {
                        runCatching { injectAlbumViewLongClick(activity, activity.window.decorView) }
                            .onFailure { StealthLog.e("$TAG: injectAlbumViewLongClick FAILED", it) }
                    }
                    StealthLog.i("$TAG: SnsUserUI.onResume -> inject posted")
                }
                return@findAndHook chain.proceed()
            }
            StealthLog.i("$TAG: SnsUserUI.onResume hooked (View long-click inject path2)")
        } catch (t: Throwable) {
            StealthLog.e("$TAG: hook SnsUserUI.onResume FAILED", t)
        }

        // 全局 hook：AbsListView.dispatchTouchEvent，按 targetListView 过滤后自计时长按
        try {
            session.findAndHook(
                "android.widget.AbsListView", "dispatchTouchEvent",
                android.view.MotionEvent::class.java
            ) { chain -> onListViewDispatchTouchEvent(chain) }
            StealthLog.i("$TAG: AbsListView.dispatchTouchEvent hooked (self-timing long press)")
        } catch (t: Throwable) {
            StealthLog.e("$TAG: hook AbsListView.dispatchTouchEvent FAILED", t)
        }

        StealthLog.i("=== $TAG handleHook DONE ===")
    }

    // ═══════════════════════════════════════════
    //  setOnItemLongClickListener 注入：包装微信原监听器
    // ═════════════════════════════════════════

    private fun onSetOnItemLongClickListener(chain: XposedInterface.Chain): Any? {
        // ★ 始终包装（对齐 InkHide：wrapper 常驻，门控下沉到 tryHandleAlbumLongClick 内部）。
        // 若在此层因 isEnabled() 提前 return，会导致本就脆弱的 OnItemLongClickListener 路径
        // 永远不触发我们的逻辑；且运行时开关动态生效也应靠内部判定而非包装层拦截。
        val orig = chain.args.getOrNull(0) as? AdapterView.OnItemLongClickListener
        // ── libxposed API 102：chain.args 是只读 UnmodifiableList，
        //    不能直接 args[0] = x，必须 toMutableList() 改后用 proceed(modifiedArgs) 放行 ──
        val wrapped = AdapterView.OnItemLongClickListener { parent, view, position, id ->
            // 先尝试我们的「隐藏我的朋友圈」逻辑
            val consumed = tryHandleAlbumLongClick(parent, view, position)
            if (consumed) {
                true // 我们已处理（弹框 + 刷新），吞掉微信原长按，避免误进详情
            } else {
                // 非相册 item：原样转交微信原生 OnItemLongClickListener（若有）
                orig?.onItemLongClick(parent, view, position, id) ?: false
            }
        }
        val modifiedArgs = chain.args.toMutableList()
        modifiedArgs[0] = wrapped
        return chain.proceed(modifiedArgs.toTypedArray())
    }

    // ═══════════════════════════════════════════
    //  路径 2：SnsUserUI.onResume 后捕获 ListView，
    //  hook AbsListView.dispatchTouchEvent + 自计时长按（含 CANCEL 延迟容错）
    // ═══════════════════════════════════════════

    // 捕获目标 ListView（SnsUserUI 中的相册列表），供 dispatchTouchEvent hook 过滤
    @Volatile private var targetListView: android.widget.ListView? = null
    // 自计时：DOWN 时记录触摸信息；长按定时器到期时判定
    private var longPressPending = false
    private var consumedByLongPress = false // 长按已触发，后续 UP 需吞掉防止误进详情
    private var pendingX = 0f
    private var pendingY = 0f
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (longPressPending) {
            longPressPending = false
            consumedByLongPress = true
            if (cancelPending) {
                // CANCEL 来了但长按计时器在容错窗口内到期 → 容错放行
                cancelPending = false
                longPressHandler.removeCallbacks(cancelToleranceRunnable)
                Log.d("LC_TRACE", ">>> [PATH2_CANCEL_TOLERANCE] long-press completed within tolerance window")
            }
            onListViewLongPress()
        }
    }
    private val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout()
    // CANCEL 延迟容错（澎湃 OS3 激进手势截断长按计时）
    private var cancelPending = false
    private val cancelToleranceRunnable = Runnable {
        if (cancelPending) {
            cancelPending = false
            cancelLongPress()
        }
    }
    private val CANCEL_TOLERANCE_MS = 180L
    // 多路径去重（路径 2/3 共用，500ms 内重复触发视为同一次长按）
    private var lastLongPressTime = 0L
    private val LONG_PRESS_DEDUP_MS = 500L
    // 路径 3：Window.Callback 层自计时长按状态
    private var windowCallbackWrapped = false
    private var winLongPressPending = false
    private var winCancelPending = false
    private var winConsumedByLongPress = false
    private var winPendingX = 0f
    private var winPendingY = 0f
    private val winLongPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val winLongPressRunnable = Runnable {
        if (winLongPressPending) {
            winLongPressPending = false
            winConsumedByLongPress = true
            if (winCancelPending) {
                winCancelPending = false
                winLongPressHandler.removeCallbacks(winCancelToleranceRunnable)
                Log.d("LC_TRACE", ">>> [PATH3_CANCEL_TOLERANCE] long-press completed within tolerance window")
            }
            onWindowLongPress()
        }
    }
    private val winCancelToleranceRunnable = Runnable {
        if (winCancelPending) {
            winCancelPending = false
            winCancelLongPress()
        }
    }

    /**
     * 对齐 InkHide 路径 2：找到 SnsUserUI 中的 ListView，记录引用供 dispatchTouchEvent hook 用。
     * 同时安装路径 3 的 Window.Callback 包裹。
     * 不再在内部 View 上设 OnLongClickListener（避免 longClickable=true 消费事件导致点击失效）。
     */
    private fun injectAlbumViewLongClick(activity: android.app.Activity, root: View) {
        if (activity.javaClass.name != SNS_USER_UI) return
        Log.d("LC_TRACE", ">>> [PATH2_INJECT] start find ListView, root=${root.javaClass.name}")
        findAndCaptureListView(root)
        // ★ 路径 3：Window.Callback 层级拦截（比 dispatchTouchEvent 更底层，对澎湃 OS3 兜底）
        injectWindowCallback(activity)
    }

    /** 递归找到第一个 AdapterView（ListView），存储引用 */
    private fun findAndCaptureListView(view: View): Boolean {
        if (view.getTag(INJECT_TAG_KEY) == true) return false
        if (view is AdapterView<*>) {
            targetListView = view as? android.widget.ListView
            Log.d("LC_TRACE", ">>> [PATH2_LV_CAPTURED] ${view.javaClass.name} childCount=${(view as android.view.ViewGroup).childCount}")
            return true
        }
        if (view is android.view.ViewGroup) {
            view.setTag(INJECT_TAG_KEY, true)
            for (i in 0 until view.childCount) {
                runCatching { if (findAndCaptureListView(view.getChildAt(i))) return true }
            }
        }
        return false
    }

    // ═══════════════════════════════════════════
    //  路径 3：Window.Callback 层自计时长按（比 dispatchTouchEvent 更底层）
    // ═══════════════════════════════════════════

    private fun injectWindowCallback(activity: android.app.Activity) {
        if (windowCallbackWrapped) return
        val win = activity.window ?: return
        val orig = win.callback ?: return
        windowCallbackWrapped = true
        win.callback = object : android.view.Window.Callback by orig {
            override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
                event?.let { onWindowTouchEvent(it) }
                return orig.dispatchTouchEvent(event)
            }
        }
        Log.d("LC_TRACE", ">>> [PATH3_WINDOW_CALLBACK] Window.Callback wrapped for SnsUserUI")
    }

    private fun onWindowTouchEvent(event: android.view.MotionEvent) {
        val lv = targetListView ?: return
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideOwnSns) return
        if (!ConfigUtil.getOptionData().showOwnSnsHideDialog) return
        val loc = IntArray(2)
        if (!runCatching { lv.getLocationOnScreen(loc) }.isSuccess) return
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        val inBounds = rawX in loc[0]..(loc[0] + lv.width) && rawY in loc[1]..(loc[1] + lv.height)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (!inBounds) return
                winConsumedByLongPress = false
                winCancelPending = false
                winLongPressHandler.removeCallbacks(winCancelToleranceRunnable)
                winLongPressPending = true
                winPendingX = event.rawX
                winPendingY = event.rawY
                winLongPressHandler.removeCallbacks(winLongPressRunnable)
                winLongPressHandler.postDelayed(winLongPressRunnable, longPressTimeout.toLong())
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!winLongPressPending) return
                val dx = event.rawX - winPendingX
                val dy = event.rawY - winPendingY
                val slop = android.view.ViewConfiguration.get(lv.context).scaledTouchSlop
                if (dx * dx + dy * dy > slop * slop) {
                    winCancelLongPress()
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (winCancelPending) {
                    winCancelPending = false
                    winLongPressHandler.removeCallbacks(winCancelToleranceRunnable)
                }
                winCancelLongPress()
                if (winConsumedByLongPress) {
                    winConsumedByLongPress = false
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                winCancelPending = true
                winLongPressHandler.removeCallbacks(winCancelToleranceRunnable)
                winLongPressHandler.postDelayed(winCancelToleranceRunnable, CANCEL_TOLERANCE_MS)
            }
        }
    }

    private fun winCancelLongPress() {
        winLongPressPending = false
        winLongPressHandler.removeCallbacks(winLongPressRunnable)
    }

    private fun onWindowLongPress() {
        if (!checkDedup()) return
        val lv = targetListView ?: return
        val loc = IntArray(2)
        if (!runCatching { lv.getLocationOnScreen(loc) }.isSuccess) return
        val listX = winPendingX - loc[0]
        val listY = winPendingY - loc[1]
        val position = lv.pointToPosition(listX.toInt(), listY.toInt())
        if (position < 0) {
            Log.d("LC_TRACE", ">>> [PATH3_SKIP] pointToPosition=$position")
            return
        }
        Log.d("LC_TRACE", ">>> [PATH3_HIT] Window.Callback long-press, position=$position")
        tryHandleAlbumLongClick(lv, lv, position)
    }

    /** 多路径去重：500ms 内重复触发视为同一次长按 */
    private fun checkDedup(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLongPressTime < LONG_PRESS_DEDUP_MS) {
            Log.d("LC_TRACE", ">>> [DEDUP] long-press suppressed (within ${now - lastLongPressTime}ms)")
            return false
        }
        lastLongPressTime = now
        return true
    }

    // dispatchTouchEvent hook 在 handleHook 中注册（全局 AbsListView hook，按 targetListView 过滤）



    /**
     * AbsListView.dispatchTouchEvent 拦截：DOWN 时启动长按计时，MOVE 超出阈值/UP 则取消。
     * 不消费事件（始终 call through），不影响系统正常触摸/点击流程。
     */
    private fun onListViewDispatchTouchEvent(chain: XposedInterface.Chain): Any? {
        val lv = chain.thisObject as? android.widget.ListView ?: return chain.proceed()
        if (lv !== targetListView) return chain.proceed()
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideOwnSns) {
            return chain.proceed()
        }
        if (!ConfigUtil.getOptionData().showOwnSnsHideDialog) return chain.proceed()

        val event = chain.args.getOrNull(0) as? android.view.MotionEvent ?: return chain.proceed()
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                consumedByLongPress = false
                cancelPending = false
                longPressHandler.removeCallbacks(cancelToleranceRunnable)
                longPressPending = true
                pendingX = event.x
                pendingY = event.y
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(longPressRunnable, longPressTimeout.toLong())
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (longPressPending) {
                    val dx = event.x - pendingX
                    val dy = event.y - pendingY
                    val slop = android.view.ViewConfiguration.get(lv.context).scaledTouchSlop
                    if (dx * dx + dy * dy > slop * slop) {
                        cancelLongPress()
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (cancelPending) {
                    cancelPending = false
                    longPressHandler.removeCallbacks(cancelToleranceRunnable)
                }
                cancelLongPress()
                // ★ 长按已触发弹框 → 吞掉此 UP 事件，阻止 ListView 将松手识别为 item click 跳进详情
                if (consumedByLongPress) {
                    consumedByLongPress = false
                    return true
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                // 澎湃 OS3 激进手势可能提前发送 CANCEL → 延迟 180ms 容错
                cancelPending = true
                longPressHandler.removeCallbacks(cancelToleranceRunnable)
                longPressHandler.postDelayed(cancelToleranceRunnable, CANCEL_TOLERANCE_MS)
            }
        }
        return chain.proceed()
    }

    private fun cancelLongPress() {
        longPressPending = false
        cancelPending = false
        longPressHandler.removeCallbacks(longPressRunnable)
        longPressHandler.removeCallbacks(cancelToleranceRunnable)
    }

    /** 长按计时器到期：获取 item position → 走 tryHandleAlbumLongClick 弹框 */
    private fun onListViewLongPress() {
        if (!checkDedup()) return
        val lv = targetListView ?: return
        val position = lv.pointToPosition(pendingX.toInt(), pendingY.toInt())
        if (position < 0) {
            Log.d("LC_TRACE", ">>> [PATH2_SKIP] pointToPosition=$position")
            return
        }
        Log.d("LC_TRACE", ">>> [PATH2_HIT] dispatchTouchEvent long-press, position=$position adapter=${lv.adapter?.javaClass?.name}")
        tryHandleAlbumLongClick(lv, lv, position)
    }

    /**
     * 判断是否相册 item 长按：是则弹框并返回 true；否则返回 false（交由微信原生处理）。
     * 直接复用微信已识别长按后的 (parent, position) —— 从 adapter.getItem(position) 直取数据项，
     * 无需回溯父链、无需自计时、无事件序列依赖。
     *
     * 判定对齐 InkHide（已验证可用，跨系统稳定）：
     *   1) 从 adapter 反射取字段 `d`（Activity），若为「我的朋友圈」SnsUserUI 页 → 命中；
     *   2) 否则走动态 `v()`：BaseAdapter 子类 + 包名前缀 com.tencent.mm.plugin.sns. + 有
     *      getView/getCount 方法 → 命中（不再写死混淆类名 `so`，避免版本差异漏判）。
     */
    private fun tryHandleAlbumLongClick(
        parent: AdapterView<*>,
        view: View,
        position: Int
    ): Boolean {
        if (!isEnabled()) return false
        // 门控：隐藏提示框开关（showOwnSnsHideDialog，默认开启）；关闭则不弹提示、不消费
        if (!ConfigUtil.getOptionData().showOwnSnsHideDialog) {
            return false
        }
        if (position < 0) return false
        // 微信 ListView 可能使用 HeaderViewListAdapter 包装真实 adapter（addHeaderView/addFooterView），
        // 需要解包取出内部 sns adapter 再做判定。
        val rawAdapter = parent.adapter ?: return false
        val adapter = if (rawAdapter is android.widget.HeaderViewListAdapter) {
            runCatching { rawAdapter.wrappedAdapter }.getOrNull() ?: rawAdapter
        } else rawAdapter
        if (!isAlbumAdapter(adapter)) {
            Log.d("LC_TRACE", ">>> [ADAPTER_SKIP] ${rawAdapter.javaClass.name} -> ${adapter.javaClass.name} (not album)")
            return false
        }
        // ★ 关键：getItem 必须用 rawAdapter（HeaderViewListAdapter），因为 position 已含 header 偏移量。
        // 若用解包后的内层 adapter，position 会错位导致取到错误 item → 隐藏错误的动态。
        val item = runCatching { rawAdapter.getItem(position) }.getOrNull() ?: return false
        val snsId = extractSnsId(item) ?: return false
        Log.d("LC_TRACE", ">>> [ALBUM_LONG_CLICK] position=$position snsId=$snsId")
        val ctx = parent.context ?: view.context
        showHideDialog(ctx, snsId, parent)
        return true
    }

    /**
     * 对齐 InkHide D(adapter)：命中条件 = 适配器内 Activity 是 SnsUserUI，或动态 v(adapter)。
     */
    private fun isAlbumAdapter(adapter: Any): Boolean {
        // 1) 反射取字段 `d`（Activity），判定是否「我的朋友圈」相册页
        val activity = getFieldValue(adapter, "d") as? android.app.Activity
        if (activity != null && activity.javaClass.name == SNS_USER_UI) {
            Log.d("LC_TRACE", ">>> [ALBUM_HIT] SnsUserUI activity detected")
            return true
        }
        // 2) 动态 v(adapter)：BaseAdapter 子类 + sns 包名 + 有 getView/getCount
        val clazz = adapter.javaClass
        if (android.widget.BaseAdapter::class.java.isAssignableFrom(clazz) &&
            clazz.name.startsWith("com.tencent.mm.plugin.sns.")
        ) {
            var hasGetView = false
            var hasGetCount = false
            clazz.declaredMethods.forEach { m ->
                if (m.name == "getView" && m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
                ) hasGetView = true
                if (m.name == "getCount" && m.parameterTypes.isEmpty()) hasGetCount = true
            }
            if (hasGetView && hasGetCount) {
                Log.d("LC_TRACE", ">>> [ALBUM_HIT] dynamic sns BaseAdapter: ${clazz.name}")
                return true
            }
        }
        return false
    }

    // ═══════════════════════════════════════════
    //  snsId 提取（继承链穿透，8.0.74 实测有效）
    // ═════════════════════════════════════════

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

    /** 继承链穿透取值（字段不存在/异常时返回 null）。 */
    private fun getFieldValue(obj: Any, name: String): Any? {
        val field = getFieldInHierarchy(obj.javaClass, name) ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(obj)
        }.getOrNull()
    }

    private fun extractSnsId(item: Any?): String? {
        if (item == null) return null
        val clazz = item.javaClass

        // 策略 1：继承链穿透，field_stringSeq（8.0.74 真实唯一序列号）
        getFieldInHierarchy(clazz, "field_stringSeq")?.let { field ->
            try {
                field.isAccessible = true
                val res = field.get(item)?.toString()
                if (!res.isNullOrEmpty()) {
                    Log.d("LC_TRACE", ">>> [SUCCESS] field_stringSeq: $res")
                    return res
                }
            } catch (_: Exception) {
            }
        }

        // 策略 2：field_snsId，剔除分表前缀
        getFieldInHierarchy(clazz, "field_snsId")?.let { field ->
            try {
                field.isAccessible = true
                val res = field.get(item)?.toString()
                if (!res.isNullOrEmpty() && !res.startsWith("sns_table_")) {
                    Log.d("LC_TRACE", ">>> [BACKUP] field_snsId: $res")
                    return res
                }
            } catch (_: Exception) {
            }
        }

        // 策略 3：localId 兜底
        val localIdName = when {
            getFieldInHierarchy(clazz, "localid") != null -> "localid"
            getFieldInHierarchy(clazz, "field_localId") != null -> "field_localId"
            else -> null
        }
        if (localIdName != null) {
            getFieldInHierarchy(clazz, localIdName)?.let { field ->
                try {
                    field.isAccessible = true
                    val res = field.get(item)?.toString()
                    if (!res.isNullOrEmpty() && res != "-1" && res != "0") {
                        Log.w("LC_TRACE", ">>> [FALLBACK] localId: local_$res")
                        return "local_$res"
                    }
                } catch (_: Exception) {
                }
            }
        }

        Log.e("LC_TRACE", ">>> [CRITICAL] All snsId strategies failed for ${clazz.name}")
        return null
    }

    // ═══════════════════════════════════════════
    //  弹框
    // ═════════════════════════════════════════

    private fun showHideDialog(context: Context?, snsId: String, albumList: AdapterView<*>) {
        val ctx = context ?: return
        Log.d("LC_TRACE", ">>> [DIALOG_SHOW] building hide dialog, snsId=$snsId context=${ctx.javaClass.name}")
        Toast.makeText(ctx, "已识别朋友圈，弹出隐藏菜单", Toast.LENGTH_SHORT).show()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("朋友圈隐藏")
            .setMessage("SnsId: $snsId")
            .setNeutralButton("复制") { _, _ ->
                ClipboardUtil.copy(snsId)
                Toast.makeText(ctx, "已复制 SnsId", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("加入隐藏") { _, _ ->
                ConfigUtil.addHiddenOwnSnsId(snsId)
                Toast.makeText(ctx, "刻舟求剑", Toast.LENGTH_SHORT).show()
                // 🟢 加入后立刻做数据层即时刷新（album/timeline/我的朋友圈/资料页预览全覆盖）
                HideOwnSnsPluginPart.requestSelfRefresh()
                HideOwnSnsPluginPart.requestProfileRefresh()
                HideOwnSnsPluginPart.requestTimelineRefresh()
                HideOwnSnsPluginPart.requestAlbumRefresh()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        Log.d("LC_TRACE", ">>> [DIALOG_SHOWN] dialog.show() returned, isShowing=${dialog.isShowing}")
    }
}
