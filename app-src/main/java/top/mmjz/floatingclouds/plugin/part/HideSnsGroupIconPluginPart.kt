package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 隐藏朋友圈分组图标（#3）。
 *
 * 目标图标：朋友圈"可见范围/分组"入口——一个人头小图标，位于「时间」右侧、「删除」左侧，
 * 点击进入"谁可以看"。真机视图树定位其 resource-id 名为 `pt`（WeImageView，contentDescription
 * 形如"部分朋友可见"），与"删除"图标(id=pi)同处一行容器(id=kq)。
 *
 * 旧实现（清 SnsInfo.ExtFlag）无效——ExtFlag 与该图标无关。改为**按 view id 定位并 GONE**：
 * - 详情页 SnsCommentDetailUI：initView 后多次延迟遍历 decorView，GONE 掉 id==pt 的图标（头部静态，一次性即可）。
 * - 主时间线/相册：WxRecyclerView.setAdapter 捕获后，挂 OnGlobalLayoutListener，随布局/滚动持续 GONE（应对 item 复用）。
 * 开关 hideSnsGroupIcon 关闭时完全不处理。
 */
class HideSnsGroupIconPluginPart : IPlugin {

    companion object {
        private const val TAG = "HideSnsGroupIcon"
        private const val WX_RECYCLER_VIEW = "com.tencent.mm.view.recyclerview.WxRecyclerView"
        private const val SNS_COMMENT_DETAIL_UI = "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI"
        // 目标图标的 resource-id 名（真机视图树确认）
        private const val ICON_ID_NAME = "pt"
    }

    @Volatile private var iconId: Int = -1
    private val hookedRoots = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun isEnabled() = ConfigUtil.isMasterEnabled() && ConfigUtil.getOptionData().hideSnsGroupIcon

    override fun handleHook(session: HookSession) {
        StealthLog.i("=== $TAG handleHook START ===")
        hookDetailPage(session)
        hookRecyclerViewSetAdapter(session)
        StealthLog.i("=== $TAG handleHook DONE ===")
    }

    private fun resolveIconId(ctx: android.content.Context): Int {
        if (iconId != -1) return iconId
        iconId = runCatching {
            ctx.resources.getIdentifier(ICON_ID_NAME, "id", ctx.packageName)
        }.getOrDefault(0)
        StealthLog.i("$TAG: resolved id '$ICON_ID_NAME' = $iconId")
        return iconId
    }

    // ── 详情页 ──
    private fun hookDetailPage(session: HookSession) {
        runCatching {
            val h = session.findAndHookNoArgs(SNS_COMMENT_DETAIL_UI, "initView") { chain ->
                chain.proceed()
                val act = chain.thisObject as? Activity
                val root = act?.window?.decorView
                if (root != null) {
                    // 头部静态：延迟多次 GONE，覆盖异步布局
                    for (delay in longArrayOf(200L, 600L, 1200L)) {
                        root.postDelayed({ if (isEnabled()) goneIconInTree(root) }, delay)
                    }
                    installLayoutHider(root)
                }
                null
            }
            if (h != null) StealthLog.i("$TAG: hooked SnsCommentDetailUI.initView")
            else StealthLog.w("$TAG: initView not found")
        }.onFailure { StealthLog.w("$TAG hook initView failed", it) }
    }

    // ── 主时间线 / 相册（WxRecyclerView）──
    private fun hookRecyclerViewSetAdapter(session: HookSession) {
        val cls = AppReflect.findClassIfExists(WX_RECYCLER_VIEW, session.classLoader) ?: run {
            StealthLog.w("$TAG: $WX_RECYCLER_VIEW not found"); return
        }
        val setAdapter = AppReflect.findMethodsByExactPredicate(cls) { m ->
            m.name == "setAdapter" && m.parameterTypes.size == 1
        }.firstOrNull() ?: run { StealthLog.w("$TAG: setAdapter not found"); return }
        runCatching {
            session.hook(setAdapter).intercept { chain ->
                chain.proceed()
                (chain.thisObject as? View)?.let { installLayoutHider(it) }
                null
            }
            StealthLog.i("$TAG: hooked $WX_RECYCLER_VIEW.setAdapter")
        }.onFailure { StealthLog.w("$TAG hook setAdapter failed", it) }
    }

    /** 给 root 挂一个全局布局监听：随布局/滚动持续 GONE 掉可见的分组图标（应对复用/异步刷新） */
    private fun installLayoutHider(root: View) {
        if (!hookedRoots.add(root)) return
        runCatching {
            root.viewTreeObserver.addOnGlobalLayoutListener(
                ViewTreeObserver.OnGlobalLayoutListener {
                    if (isEnabled()) goneIconInTree(root)
                }
            )
        }
    }

    /** 遍历 view 树，GONE 掉所有 id==iconId 且当前可见的图标 */
    private fun goneIconInTree(root: View) {
        val id = resolveIconId(root.context)
        if (id == 0) return
        var n = 0
        fun walk(v: View) {
            if (v.id == id && v.visibility != View.GONE) {
                v.visibility = View.GONE
                n++
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        runCatching { walk(root) }
        if (n > 0) StealthLog.i("$TAG: hid $n group-icon(s)")
    }
}
