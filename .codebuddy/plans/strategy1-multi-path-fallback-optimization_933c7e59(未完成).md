---
name: strategy1-multi-path-fallback-optimization
overview: 为 LongClickTracePluginPart 实施策略一多路径回退优化：增加 ACTION_CANCEL 延迟容错（防澎湃 OS3 手势截断）和 Window.Callback 第三路径兜底，提升跨 OEM 长按兼容性。
todos:
  - id: add-cancel-tolerance
    content: 路径2增加 CANCEL 延迟容错：新增 cancelPending 标志、cancelRunnable（180ms延迟），改造 ACTION_CANCEL/ACTION_DOWN/longPressRunnable 三处逻辑
    status: pending
  - id: add-path3-window-callback
    content: 新增路径3 Window.Callback 拦截：在 injectAlbumViewLongClick 中包裹 activity.window.callback，实现独立的 self-timing 长按检测，使用 rawX/rawY + getLocationOnScreen 做 ListView 区域判定
    status: pending
    dependencies:
      - add-cancel-tolerance
  - id: add-dedup-timestamp
    content: 路径2与路径3去重：在 onListViewLongPress 和 wcLongPressRunnable 中各设 lastLongPressHandledTime 时间戳，500ms 窗口内去重
    status: pending
    dependencies:
      - add-cancel-tolerance
      - add-path3-window-callback
  - id: update-doc-comments
    content: 更新文件头注释：将"双路径"改为"三路径"，补充 CANCEL 容错和 Window.Callback 兜底说明
    status: pending
    dependencies:
      - add-dedup-timestamp
  - id: build-and-verify
    content: 使用 [skill:lsposed-dev] 编译 debug 版本并验证：确保编译通过无错误，检查 logcat 中 PATH3 相关日志输出正常
    status: pending
    dependencies:
      - update-doc-comments
---

## 用户需求

按照"策略一"优化长按注入功能，提升在小米澎湃 OS3 等激进手势系统上的兼容性。

## 核心改动

### 1. CANCEL 延迟容错（路径2增强）

澎湃 OS3 在检测到可能的手势操作时激进发送 ACTION_CANCEL，导致路径2（AbsListView.dispatchTouchEvent 自计时长按）的长按计时器被提前取消。改造后在 CANCEL 到达时不立即取消，而是设 180ms 容错窗口——若长按计时器在窗口内到期则正常触发弹框。

### 2. 新增路径3 Window.Callback 拦截

在 Window.Callback 层级（事件流最上游）做兜底拦截。当路径1（setOnItemLongClickListener 包装）和路径2均因 OEM 手势截断失效时，Window.Callback 作为最后防线。在 SnsUserUI.onResume 中包裹 activity.window.callback，拦截 dispatchTouchEvent，判断触摸是否落在已捕获的 ListView 区域内，若是则独立自计时长按。

### 3. 三路径去重

路径2和路径3独立触发，通过共享时间戳（lastLongPressHandledTime）去重，避免重复弹框。

## 技术方案

### 实现策略

仅修改 `LongClickTracePluginPart.kt` 一个文件，不引入新依赖，复用现有的 `isEnabled()`、`isAlbumAdapter()`、`extractSnsId()`、`tryHandleAlbumLongClick()`、`showHideDialog()` 等共用逻辑。

### 改动一：CANCEL 延迟容错

**现状问题**：`onListViewDispatchTouchEvent` 中 ACTION_CANCEL 直接调用 `cancelLongPress()` 立即取消计时器。

**改造方案**：

- 新增字段 `cancelPending: Boolean` 和 `cancelRunnable: Runnable`，延迟 180ms
- CANCEL 到达且 `longPressPending == true` 时，不立即取消，改为 `postDelayed(cancelRunnable, 180L)`
- 若长按 runnable 在 180ms 内到期，清除 `cancelRunnable` 正常触发
- 若 180ms 后长按仍未到期，才真正取消
- ACTION_DOWN 到达时也清除可能存在的 `cancelRunnable`
- 路径2的 `cancelLongPress()` 保持原语义（UP/MOVE超slop时立即取消，这些场景不需要容错）

**新增字段**（在路径2区块内）：

```
private var cancelPending = false
private val cancelToleranceMs = 180L
private val cancelRunnable = Runnable {
    if (cancelPending) {
        cancelPending = false
        longPressPending = false
        longPressHandler.removeCallbacks(longPressRunnable)
    }
}
```

**ACTION_CANCEL 改造**：

```
android.view.MotionEvent.ACTION_CANCEL -> {
    if (longPressPending) {
        // 澎湃OS3激进CANCEL容错：延迟180ms再取消，给长按计时器最后机会
        cancelPending = true
        longPressHandler.postDelayed(cancelRunnable, cancelToleranceMs)
    } else {
        cancelLongPress()
    }
}
```

**longPressRunnable 改造**：到期时清除延迟取消：

```
private val longPressRunnable = Runnable {
    if (longPressPending) {
        // 清除可能存在的延迟取消（长按已确认触发）
        if (cancelPending) {
            cancelPending = false
            longPressHandler.removeCallbacks(cancelRunnable)
        }
        longPressPending = false
        consumedByLongPress = true
        onListViewLongPress()
    }
}
```

**ACTION_DOWN 改造**：清除延迟取消再开始新计时：

```
android.view.MotionEvent.ACTION_DOWN -> {
    // 清除上一个手势可能残留的延迟取消
    if (cancelPending) {
        cancelPending = false
        longPressHandler.removeCallbacks(cancelRunnable)
    }
    consumedByLongPress = false
    longPressPending = true
    pendingX = event.x
    pendingY = event.y
    longPressHandler.removeCallbacks(longPressRunnable)
    longPressHandler.postDelayed(longPressRunnable, longPressTimeout.toLong())
}
```

### 改动二：新增路径3 Window.Callback 拦截

**原理**：Window.Callback.dispatchTouchEvent 是事件流入口（ViewRootImpl 之后第一个接收点），在 DecorView 分发之前。OEM 手势系统可能在 DecorView/ViewGroup 层级截断事件，但 Window.Callback 始终能收到原始事件序列。

**实现方式**：

- 在已有的 `SnsUserUI.onResume` hook 中，捕获 ListView 之后额外包裹 `activity.window.callback`
- 用 Kotlin `by` 委托实现代理模式，只覆写 `dispatchTouchEvent`，其余方法透传
- `dispatchTouchEvent` 中判断触摸是否在已捕获的 `targetListView` 区域内（用 `getLocationOnScreen` + `rawX/rawY` 转换坐标）
- 区域内则自计时长按，逻辑与路径2独立但共享去重时间戳

**新增字段**（路径3区块）：

```
// 路径3：Window.Callback 自计时长按
@Volatile private var lastLongPressHandledTime = 0L
private var windowCallbackWrapped = false
private var wcLongPressPending = false
private var wcPendingX = 0f
private var wcPendingY = 0f
private val wcHandler = android.os.Handler(android.os.Looper.getMainLooper())
private val wcLongPressRunnable = Runnable {
    if (wcLongPressPending) {
        wcLongPressPending = false
        val lv = targetListView ?: return@Runnable
        // 去重：路径2已处理则跳过
        if (System.currentTimeMillis() - lastLongPressHandledTime < 500) return@Runnable
        val position = lv.pointToPosition(wcPendingX.toInt(), wcPendingY.toInt())
        if (position >= 0) {
            Log.d("LC_TRACE", ">>> [PATH3_HIT] Window.Callback long-press, position=$position")
            if (tryHandleAlbumLongClick(lv, lv, position)) {
                lastLongPressHandledTime = System.currentTimeMillis()
            }
        }
    }
}
```

**Window.Callback 包裹逻辑**（在 `injectAlbumViewLongClick` 末尾调用）：

```
private fun wrapWindowCallbackIfNeeded(activity: android.app.Activity) {
    if (windowCallbackWrapped) return
    val window = activity.window ?: return
    val orig = window.callback ?: return
    window.callback = object : android.view.Window.Callback by orig {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            handleWindowTouchForLongPress(event)
            return orig.dispatchTouchEvent(event)
        }
    }
    windowCallbackWrapped = true
    StealthLog.i("$TAG: Window.Callback wrapped (path3)")
}

private fun handleWindowTouchForLongPress(event: android.view.MotionEvent) {
    val lv = targetListView ?: return
    if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideOwnSns) return
    if (!ConfigUtil.getOptionData().showOwnSnsHideDialog) return

    val lvLoc = IntArray(2)
    lv.getLocationOnScreen(lvLoc)
    val localX = event.rawX - lvLoc[0]
    val localY = event.rawY - lvLoc[1]
    if (localX < 0 || localX > lv.width || localY < 0 || localY > lv.height) return

    when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
            wcLongPressPending = true
            wcPendingX = localX
            wcPendingY = localY
            wcHandler.removeCallbacks(wcLongPressRunnable)
            wcHandler.postDelayed(wcLongPressRunnable, longPressTimeout.toLong())
        }
        android.view.MotionEvent.ACTION_MOVE -> {
            if (wcLongPressPending) {
                val dx = event.rawX - (lvLoc[0] + wcPendingX)
                val dy = event.rawY - (lvLoc[1] + wcPendingY)
                val slop = android.view.ViewConfiguration.get(lv.context).scaledTouchSlop
                if (dx * dx + dy * dy > slop * slop) {
                    wcLongPressPending = false
                    wcHandler.removeCallbacks(wcLongPressRunnable)
                }
            }
        }
        else -> {
            wcLongPressPending = false
            wcHandler.removeCallbacks(wcLongPressRunnable)
        }
    }
}
```

### 改动三：路径2与路径3去重

路径2的 `onListViewLongPress()` 中，触发成功后记录时间戳：

```
private fun onListViewLongPress() {
    // ... 现有逻辑 ...
    if (tryHandleAlbumLongClick(lv, lv, position)) {
        lastLongPressHandledTime = System.currentTimeMillis()
    }
}
```

路径3的 runnable 中检查 `System.currentTimeMillis() - lastLongPressHandledTime < 500` 来去重。

### 性能分析

- CANCEL 容错：仅增加一个 180ms 延迟 Runnable，无额外遍历，开销可忽略
- Window.Callback：每次触摸事件多做一次 ListView 边界判断（IntArray 分配 + getLocationOnScreen），频率为触摸事件频率（~60-120Hz），开销极低
- 去重：仅一次时间戳比较，无锁（@Volatile 保证可见性，单线程场景安全）

## 使用的 Agent 扩展

### Skill

- **lsposed-dev**
- 用途：在实施过程中验证 libxposed API 102 的 hook 语法正确性，确保 MotionEvent 参数类型、chain.proceed() 返回值处理等符合框架规范
- 预期结果：代码编译通过，hook 注册成功无运行时异常