---
name: fix-conversation-hide-fallback
overview: 修复 ConversationHideHelper.init() 方法发现逻辑，排除免打扰方法 m2/D2，正确识别"不显示该聊天"的真正 getter/setter；强化冷启动保护和 onMaskListChanged 即时兜底写入。
todos:
  - id: fix-init-method-discovery
    content: 使用 [skill:lsposed-dev] 修复 ConversationHideHelper.init() 方法发现逻辑：收集所有候选 getter/setter，排除已知免打扰方法 m2/D2，通过值对比找到真正的隐藏标志方法，并记录 setter 参数类型
    status: pending
  - id: fix-setHidden-param-type
    content: 修复 ConversationHideHelper.setHidden() 中 setter 调用参数类型适配，根据 init() 记录的参数类型动态传 Boolean 或 Int
    status: pending
    dependencies:
      - fix-init-method-discovery
  - id: fix-onMaskListChanged-fallback
    content: 修复 HideMainUIListPluginPart.onMaskListChanged 回调，新增密友时立即触发 setConversationMute + setConversationHidden 写入 DB 兜底
    status: pending
  - id: fix-ensureAllMaskedHidden-mute
    content: 修复 HideMainUIListPluginPart.ensureAllMaskedHidden()，冷启动时同时调用 mute 和 hide 确保双重兜底完整生效
    status: pending
  - id: build-and-verify
    content: 构建调试版 APK，验证修复后 Conversations flags probe 日志确认 hide getter/setter 正确，冷启动场景密友不暴露
    status: pending
    dependencies:
      - fix-init-method-discovery
      - fix-setHidden-param-type
      - fix-onMaskListChanged-fallback
      - fix-ensureAllMaskedHidden-mute
---

## 用户需求

修复"不显示该聊天"兜底机制失效问题。设计意图是免打扰（mute）+ 不显示该聊天（hide）双重兜底，但当前 hide 机制因 `ConversationHideHelper.init()` 错误发现方法签名（命中了免打扰的 `m2`/`D2`），导致真正的隐藏标志从未写入微信 DB。冷启动时 Hook 未就绪，微信原生从 DB 读取到隐藏标志=false，密友会话直接暴露。

## 核心修复点

1. **`ConversationHideHelper.init()` 方法发现**：排除已知免打扰方法 `m2`/`D2`，通过对比样本会话的多个 boolean/int getter 返回值，找到与免打扰值不同的候选作为真正的隐藏标志 getter/setter
2. **`setHidden()` setter 参数类型匹配**：根据 setter 实际参数类型（Boolean 或 Int）传递正确的值
3. **`onMaskListChanged` 回调**：新增密友时立即写入隐藏标志到 DB，消除"新加密友→模块崩溃→重启暴露"的窗口期
4. **`ensureAllMaskedHidden()`**：冷启动时同时确保 mute 和 hide 都设置，双重兜底全部生效

## 技术栈

- 语言：Kotlin
- 框架：LSPosed/Xposed Hook（基于项目现有 HookSession）
- 核心依赖：AppReflect（反射工具）、ObfuscatedClassResolver（混淆类名解析）

## 实现方案

### 根因复述

`ConversationHideHelper.init()` 第65-80行遍历 `convClass.declaredMethods`，取**最后一个**符合签名的 getter 和 setter。由于 `declaredMethods` 只返回当前类声明的方法，若 Conversation 类直接声明了 `m2()`（boolean 无参）和 `D2(int)`（单参 int），且它们恰好是唯一匹配或排在可匹配位置，则 `isHiddenGetter="m2"`，`hideSetter="D2"`——与 `WXMaskPlugin.setConversationMute()` 硬编码的完全一致。`setHidden()` 调用 `m2()` 发现已免打扰=true，判定 `currentHidden == hidden` → 直接 return，真正的隐藏标志从未写入。

### 修复策略

#### 1. init() 方法发现升级

采用**值对比排除法**：

- 收集所有 getter 候选（无参、返回 Boolean/Int，排除名为 `m2` 的已知免打扰 getter）
- 收集所有 setter 候选（单参 Int/Boolean，排除名为 `D2` 的已知免打扰 setter）
- 调用 `m2()` 获取当前免打扰值作为参考基准
- 遍历所有非 `m2` 的 getter 候选，调用后在样本会话上取值
- 选择**第一个返回值与免打扰值不同**的 getter 作为隐藏 getter（真正的隐藏标志几乎必然与免打扰值不同）
- 根据 getter 返回类型匹配对应参数类型的 setter
- 若所有候选返回值都与免打扰相同（极小概率），降级为取第一个名称非 `m2` 的 getter/setter 对
- 详细日志输出所有候选及最终选择，方便版本适配诊断

#### 2. setHidden() setter 参数类型适配

当前硬编码传 `if (hidden) 1 else 0`（Int），若真实 setter 接受 Boolean 参数会失败。修复为根据 `init()` 记录的 setter 参数类型动态传参。

#### 3. onMaskListChanged 即时兜底

`ConvAddMaskPluginPart.consumeConvMenuItem()` 和 `LongPressAddMaskPluginPart` 新增密友后调用 `onMaskListChanged`，当前仅清缓存 + notifyAdapter。修复为额外触发 hide+mute 立即写入 DB。

#### 4. ensureAllMaskedHidden 补全 mute

当前 `ensureAllMaskedHidden()` 只调用 `WXMaskPlugin.setConversationHidden()` 写隐藏标志。冷启动场景需同时确保免打扰也设置，调用 `WXMaskPlugin.setConversationMute()` 形成完整双重兜底。

### 关键设计决策

- **不改变 mute 机制**：`setConversationMute()` 硬编码 `m2`/`D2` 保持不变，已验证正确
- **值对比法而非纯名称过滤**：比仅排除 `m2`/`D2` 更健壮，在微信版本升级导致方法重命名时仍能自动适配
- **最小侵入**：所有修改集中在 2 个文件（ConversationHideHelper.kt、HideMainUIListPluginPart.kt），不改动 Part 调度架构

## 目录结构

```
app-src/main/java/top/mmjz/floatingclouds/plugin/part/
├── ConversationHideHelper.kt    # [MODIFY] 修复 init() 方法发现 + setHidden() setter 类型适配
└── HideMainUIListPluginPart.kt  # [MODIFY] 修复 onMaskListChanged + ensureAllMaskedHidden
```

## 关键代码结构

### ConversationHideHelper.init() 新方法发现逻辑

```
1. 扫描 convClass.declaredMethods
2. 分类收集：
   - getterCandidates: List<Pair<name, returnType>>  (无参, 返回Boolean/Boolean.TYPE/Int/Integer.TYPE, name != "m2")
   - setterCandidates: List<Pair<name, paramType>>   (单参 Int/Integer.TYPE/Boolean/Boolean.TYPE, name != "D2")
   - setterParamType: Map<name, Class<*>>            (记录setter的参数类型)
3. 调用 m2() 获取 muteValue
4. 遍历 getterCandidates，调用每个 getter
5. 第一个返回值 != muteValue 的 → 确定为 isHiddenGetter
6. 根据 isHiddenGetter 的返回类型，从 setterCandidates 找参数类型匹配的 → hideSetter
7. 记录 hideSetterParamType 供 setHidden() 使用
8. 若所有候选值都 == muteValue，降级取第一个候选对
```

### setHidden() setter 调用

```
// 根据 hideSetterParamType 决定传参：
when (hideSetterParamType) {
    Boolean::class.java, java.lang.Boolean.TYPE -> AppReflect.callMethod(conv, setterName, hidden)
    Int::class.java, Integer.TYPE -> AppReflect.callMethod(conv, setterName, if (hidden) 1 else 0)
}
```

## Agent Extensions

### Skill

- **lsposed-dev**
- 用途：LSPosed/Xposed 模块开发专用技能，用于 WeChat Hook 场景下的反射调用、混淆类名处理、Hook 点注册等代码生成
- 预期结果：生成符合项目现有 HookSession 模型、AppReflect 调用规范、StealthLog 日志惯例的修复代码