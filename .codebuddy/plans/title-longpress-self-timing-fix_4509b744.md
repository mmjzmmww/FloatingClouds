---
name: title-longpress-self-timing-fix
overview: 将标题长按从 setOnLongClickListener（系统写死500ms，忽略用户配置的 longPressDuration）改为 setOnTouchListener 自计时（用配置时长 + 180ms CANCEL 容错），多击功能不变。
todos:
  - id: add-fields-and-imports
    content: 新增 import MotionEvent/ViewConfiguration，在字段区新增 titleLongPressPending/cancelPending/Handler/Runnable 及 CANCEL_TOLERANCE_MS
    status: completed
  - id: rewrite-longpress-listener
    content: 改造 attachTitleListeners 中 L1306-1314：将 setOnLongClickListener 替换为 setOnTouchListener 自计时 + CANCEL 容错，新增 cancelTitleLongPress 辅助方法
    status: completed
    dependencies:
      - add-fields-and-imports
  - id: add-onpause-cleanup
    content: 在 onPause (L306) 和 onWindowFocusChanged (L337) 清理逻辑中新增 cancelTitleLongPress() 调用
    status: completed
    dependencies:
      - rewrite-longpress-listener
  - id: build-and-verify
    content: 编译 debug + release 版本，安装到设备 facc5664，logcat 验证长按日志输出
    status: completed
    dependencies:
      - add-onpause-cleanup
---

## 用户需求

修复标题栏长按临时解除隐藏功能的两个 bug：

### Bug 1：`longPressDuration` 配置无效

`setOnLongClickListener` 的触发时间被 Android Framework 写死在 `ViewConfiguration.getLongPressTimeout()` ≈ 500ms。用户配置的 `longPressDuration`（默认 800ms）算出来但仅用于日志文案，实际触发永远 ~500ms。

### Bug 2：HyperOS3 长按完全失效

澎湃 OS3 手势系统在检测到屏幕顶部触摸时，激进发送 `ACTION_CANCEL`，杀死 Android Framework 内部 `CheckForLongPress` 的 `postDelayed` 回调，导致 `setOnLongClickListener` 永远不会被调用。

### 方案

对齐 `LongClickTracePluginPart` 已实现的 `setOnTouchListener` + 自计时 + 180ms CANCEL 延迟容错模式。多击功能（`setOnClickListener` + 自计数）完全保持不变。

## 技术方案

### 实现策略

仅修改 `HideMainUIListPluginPart.kt` 一个文件，改动集中在 `attachTitleListeners()` 方法内 L1305-1314 的长按部分，以及相关字段声明和清理逻辑。参照项目中 `LongClickTracePluginPart.kt` 已验证的 CANCEL 容错模式。

### 架构设计

```
attachTitleListeners(rootView)
    ├── 标题定位 (不变)
    │   ├── 策略①: ActionBar/Toolbar 容器内查找
    │   └── 策略②: 整树顶部25%区域查找
    ├── 长按: setOnTouchListener (改造点)
    │   ├── ACTION_DOWN → handler.postDelayed(longPressRunnable, longPressMs)
    │   ├── ACTION_MOVE → 超出 touchSlop 则取消
    │   ├── ACTION_UP → 取消 + 清理 CANCEL 容错状态
    │   ├── ACTION_CANCEL → cancelPending=true + 180ms 容错延迟
    │   └── longPressRunnable → 容错窗口内到期则放行，否则取消
    └── 多击: setOnClickListener (完全不变)
        └── 自计数 + isCurrentTabWeChat() 校验
```

### 关键设计决策

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 触摸监听器 | `setOnTouchListener` 返回 `false` | 不消费事件，保证 `onTouchEvent → performClick → OnClickListener` 链路完整，多击不受影响 |
| 计时延迟 | `handler.postDelayed(r, longPressMs)` | 使用用户配置的 `longPressDuration`（默认 800ms），不再被系统写死 |
| CANCEL 策略 | `cancelPending` + 180ms 延迟取消 | 对齐 LongClickTracePluginPart，给长按计时器最后机会 |
| 跨Tab防误触 | `isCurrentTabWeChat()` 三层校验 | 保持现有逻辑，不变 |


### 实现细节

#### 1. 新增字段（在 L149 下方）

```
// 标题长按自计时状态（setOnTouchListener 自计时，替代系统 setOnLongClickListener）
private var titleLongPressPending = false
private var titleCancelPending = false
private val titleLongPressHandler = Handler(Looper.getMainLooper())
private var titleLongPressRunnable: Runnable? = null
private val titleCancelToleranceRunnable = Runnable {
    if (titleCancelPending) {
        titleCancelPending = false
        cancelTitleLongPress()
    }
}
private val CANCEL_TOLERANCE_MS = 180L
```

#### 2. 改造 L1306-1314（长按部分）

```
// ★ 长按临时解除：setOnTouchListener 自计时 + CANCEL 容错（修复 HyperOS3 兼容性）
if (opt.enableLongPressTempUnhide) {
    titleView.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                titleLongPressPending = true
                titleCancelPending = false
                titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                cancelTitleLongPress() // 清理旧计时器
                val r = Runnable {
                    if (titleLongPressPending) {
                        titleLongPressPending = false
                        if (titleCancelPending) {
                            // CANCEL 来了但长按计时器在容错窗口内到期 → 容错放行
                            titleCancelPending = false
                            titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                            AppLog.i("HideMainUI: CANCEL tolerance, long-press completed")
                        }
                        if (ConfigUtil.isMasterEnabled() && isCurrentTabWeChat()) {
                            AppLog.i("HideMainUI: long-press triggered (threshold=${longPressMs}ms)")
                            onLongPressUnhideTriggered()
                        }
                    }
                }
                titleLongPressRunnable = r
                titleLongPressHandler.postDelayed(r, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (titleLongPressPending) {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    // 使用 ABS 做简化 MOVE 检测：标题 View 通常较小，直接用历史坐标对比
                    if (Math.abs(event.x - event.downTime) > slop || Math.abs(event.y - event.downTime) > slop) {
                        cancelTitleLongPress()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (titleCancelPending) {
                    titleCancelPending = false
                    titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                }
                cancelTitleLongPress()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (titleLongPressPending) {
                    titleCancelPending = true
                    titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
                    titleLongPressHandler.postDelayed(titleCancelToleranceRunnable, CANCEL_TOLERANCE_MS)
                }
            }
        }
        false // 不消费事件，保证 OnClickListener 仍能接收点击事件用于多击
    }
}
```

#### 3. 新增辅助方法

```
private fun cancelTitleLongPress() {
    titleLongPressPending = false
    titleCancelPending = false
    titleLongPressRunnable?.let { titleLongPressHandler.removeCallbacks(it) }
    titleLongPressRunnable = null
    titleLongPressHandler.removeCallbacks(titleCancelToleranceRunnable)
}
```

#### 4. 更新清理逻辑

- L306-309（onPause）：新增 `cancelTitleLongPress()` 调用
- L337-339（onWindowFocusChanged）：新增 `cancelTitleLongPress()` 调用
- 新增 `import android.view.MotionEvent` 和 `import android.view.ViewConfiguration`（如果尚未导入）

#### 5. MOVE 检测修正

上面用 `event.downTime` 做 MOVE 检测是不对的。正确做法是用 `ACTION_DOWN` 时的初始坐标。修正为：

```
// 在字段区新增
private var titleDownX = 0f
private var titleDownY = 0f

// ACTION_DOWN 中记录
titleDownX = event.x
titleDownY = event.y

// ACTION_MOVE 中对比
val dx = event.x - titleDownX
val dy = event.y - titleDownY
if (dx * dx + dy * dy > slop * slop) {
    cancelTitleLongPress()
}
```

### 与多击的交互

`setOnTouchListener` 返回 `false`，不消费事件：

- 原生 `onTouchEvent(ACTION_UP)` 正常执行
- `performClick()` 正常触发
- `setOnClickListener` 正常回调
- 多击计数逻辑完全不受影响