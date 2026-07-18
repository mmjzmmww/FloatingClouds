# 修复记录：主页会话列表重复bug

**版本**：3.0.132 → 3.0.133  
**日期**：2026-07-14  
**影响范围**：HideMainUIListPluginPart.kt

---

## 问题描述

加入密友后，微信主页"微信"Tab会话列表出现**重复会话**——同一个好友/系统账号在列表中显示两次。

### 复现步骤
1. 清空配置
2. 长按任意会话 → "加入密友"
3. 返回主页 → 列表中多出一个重复的会话（如"微信团队"）
4. 再加入第二个密友 → 第二个密友的会话也出现重复

---

## 根因分析

### 调用链
```
ConfigUtil.addMaskList() 
  → onMaskListChanged
    → notifyAdapter(HeaderViewListAdapter)
      → notifyDataSetChanged
        → filterGetView (对每个位置)
          → rebuildFilterCache → 构建33个非隐藏位置
        → getCount() 返回 34 (未被过滤)
          → 第34个位置 (index=33) 越界回退到原始数据
            → 该位置会话被再次渲染 → 重复显示
```

### 根本原因

**`filterGetCount` 从未被调用的两个原因**：

1. **hookAdapterClassDynamic 找不到 getCount 方法**：由于微信 8.0.74 的会话 adapter 经过混淆 + HeaderViewListAdapter 包装，`findMethodExact(adapterClass, "getCount")` 在 adapter 的实际类层级中找不到该方法（方法在父类或接口中，而非直接声明）。

2. **getAdapterFilterType 无法识别包装类**：运行时 adapter 类型为 `HeaderViewListAdapter`/`Moostlt`（微信混淆类），这些类名不在 DexKitCache 的会话 adapter 列表中，导致 `getAdapterFilterType` 返回 0，filterGetCount 即使被 hook 也会被 early return 掉。

### 结果
- `getCount()` 始终返回原始数量 34
- `filterGetView` 的 filterCache 只有 33 个映射（34 - 1密友）
- 第 34 个位置无法映射 → 回退到原始索引 → 又渲染了一次已被映射到前面位置的会话 → 重复

---

## 修复方案

### 修复 1：filterGetView 中直接安装 getCount hook

**位置**：`HideMainUIListPluginPart.kt` → `filterGetView()` 方法

当 `filterGetView` 首次被 adapter 调用时，检测到 adapter 是 `ListAdapter` 且尚未被 hook，直接通过反射安装 `getCount` 拦截器：

```kotlin
if (!hookedClasses.contains(adapter.javaClass) && adapter is ListAdapter) {
    runCatching {
        val gcm = adapter.javaClass.getDeclaredMethod("getCount")
        session.hook(gcm).intercept { c -> filterGetCount(session, c) }
        hookedClasses.add(adapter.javaClass)
    }
}
```

这绕过了 `hookAdapterClassDynamic` 的类层级查找问题——因为此时 adapter 实例已经可用，`getDeclaredMethod` 覆盖了 Android 系统类（HeaderViewListAdapter），方法必定存在。

### 修复 2：getAdapterFilterType 增加 filterCache 判定

**位置**：`HideMainUIListPluginPart.kt` → `getAdapterFilterType()` 方法

在 DexKitCache 名称匹配之前，加入 filterCache 检查：

```kotlin
if (filterCache.containsKey(adapter)) return if (option.hideMainConvList) 1 else 0
```

已经进入过 `filterGetView` 的 adapter（filterCache 中有数据）必定是会话 adapter，无需依赖类名匹配。

---

## 变更清单

| 文件 | 改动 | 类型 |
|------|------|------|
| `app-src/build.gradle` | versionCode: 3132→3133, versionName: 3.0.132→3.0.133 | 版本提升 |
| `HideMainUIListPluginPart.kt` | filterGetView: 新增 getCount hook | 核心修复 |
| `HideMainUIListPluginPart.kt` | getAdapterFilterType: 新增 filterCache 检查 | 核心修复 |
| `HideMainUIListPluginPart.kt` | 清理诊断日志和无效修复代码 | 代码清理 |

### 移除的无效尝试
- `hookListViewSetAdapter` 的 try-catch 包装
- `diagLoggedAdapters` / `diagLoggedRvAdapters` 诊断字段
- `_filterGetCountDiagOnce` / `_filterGetViewDiagOnce` 诊断字段
- 各方法内的 `android.util.Log.i` 诊断输出
- `DIAG-STACK` / `DIAG-HOOK` / `DIAG-T3-*` 等日志

### 保留的架构
- `hookAdapterClassDynamic` 的类层级递归查找逻辑（不失配）
- `hookRecyclerView` 的 `RecyclerView.Adapter` 匹配逻辑（不失配）
- `filterGetView` 的内容清除和位置重映射（不失配）
- `rebuildFilterCache` 的 StealthLog.i 诊断日志（不失配）
