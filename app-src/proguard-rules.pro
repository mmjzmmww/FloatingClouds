# ── Xposed 框架入口保持 ──
-keep,allowobfuscation @interface io.github.libxposed.api.XposedInterface
-keep,allowobfuscation @interface io.github.libxposed.api.XposedModuleInterface

# XposedModule 子类 — 框架通过反射实例化
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Hooker 实现 — Lambda 可能被内联
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker {
    <methods>;
}

# ── 必须保留原始类名的入口点 ──
# xposed_init 字符串引用：top.mmjz.floatingclouds.XposedEntry
-keep public class top.mmjz.floatingclouds.XposedEntry {
    public <init>();
}

# AndroidManifest 组件（Application / Activity）
-keep class top.mmjz.floatingclouds.App { *; }
-keep class top.mmjz.floatingclouds.ui.MainActivity { *; }

# 远程停运开关（Kotlin object，从 ConfigUtil 调用）
-keep class top.mmjz.floatingclouds.plugin.RemoteKillSwitch { *; }

# IPlugin 接口 — 所有 Plugin/Part 通过此接口 dispatch，混淆不得改变 vtable
-keep interface top.mmjz.floatingclouds.plugin.IPlugin { *; }

# ViewBinding 生成的类 — 保持 findViewById / layout 关联
-keep class top.mmjz.floatingclouds.databinding.** { *; }

# Bean 类保留（反射/JSON 序列化可能依赖）
-keep class top.mmjz.floatingclouds.bean.** { *; }

# 资源
-keep class **.R { *; }
-keep class **.R$* { *; }

# ── R8 混淆策略：开启混淆（类名/方法名/字段名），保留核心包结构 ──
# ★ 安全原则：保留 plugin/hook/util 等核心类的包名和类名，
#   仅混淆方法内部实现和非关键内部类，避免破坏 lambda dispatch / 类加载

# 核心功能包 — 保留类名
-keep class top.mmjz.floatingclouds.plugin.** { *; }
-keep class top.mmjz.floatingclouds.hook.** { *; }
-keep class top.mmjz.floatingclouds.util.ConfigUtil { *; }
-keep class top.mmjz.floatingclouds.util.LocalKVUtil { *; }
-keep class top.mmjz.floatingclouds.util.AppContext { *; }
-keep class top.mmjz.floatingclouds.util.AppVersionUtil { *; }
-keep class top.mmjz.floatingclouds.util.AppReflect { *; }
-keep class top.mmjz.floatingclouds.util.StealthLog { *; }
-keep class top.mmjz.floatingclouds.util.AppLog { *; }
-keep class top.mmjz.floatingclouds.util.WechatPaths { *; }
-keep class top.mmjz.floatingclouds.util.DexKitScanner { *; }
-keep class top.mmjz.floatingclouds.util.DexKitCache { *; }
-keep class top.mmjz.floatingclouds.util.ColorUtilX { *; }
-keep class top.mmjz.floatingclouds.util.VoipClassResolver { *; }
-keep class top.mmjz.floatingclouds.util.VoipInterceptionState { *; }
-keep class top.mmjz.floatingclouds.util.FeatureDiagnostics { *; }
-keep class top.mmjz.floatingclouds.util.FileConfigStore { *; }
-keep class top.mmjz.floatingclouds.util.HookCacheReader { *; }
-keep class top.mmjz.floatingclouds.util.ext.** { *; }
-keep class top.mmjz.floatingclouds.Constrant { *; }
-keep class top.mmjz.floatingclouds.ClazzN { *; }
-keep class top.mmjz.floatingclouds.BuildConfig { *; }
-keep class top.mmjz.floatingclouds.GlobalLifecycleHook { *; }

# ── AppLog 剥离规则已移至 proguard-rules-release.pro（仅正式 release 生效） ──
# 测试版（debug buildType，minifyEnabled=false）不引用任何剥离规则，AppLog 天然保留并落文件日志。

