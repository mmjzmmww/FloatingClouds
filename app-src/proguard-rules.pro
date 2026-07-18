# ── Xposed 框架入口保持 ──
-keep,allowobfuscation @interface io.github.libxposed.api.XposedInterface
-keep,allowobfuscation @interface io.github.libxposed.api.XposedModuleInterface

# XposedModule 子类 — 框架按 xposed_init 字符串加载并实例化。
# 必须保留全部成员原名（尤其 onModuleLoaded/onPackageLoaded/onPackageReady/
# onHotReloading/onHotReloaded 生命周期回调）。
# 实测：若只保 <init>()，R8 会把这四个回调改名成 a；libxposed 目前按签名/注解
# dispatch 仍能命中，但属脆弱巧合——一旦框架改为按名回调或热重载按名调用，
# 模块会瞬间零 hook 失效。故保留该入口类全部成员原名。
# 注意：ProGuard 中 "public protected *" 是 AND 语义（永不匹配），必须用 "*"。
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    *;
}

# Hooker 实现 — Lambda 可能被内联，保留其方法
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker {
    <methods>;
}

# ── 必须保留原始类名的入口点 ──
# xposed_init 字符串引用：top.mmjz.floatingclouds.XposedEntry
-keep public class top.mmjz.floatingclouds.XposedEntry {
    public <init>();
}

# AndroidManifest 组件（Application / Activity）— 框架按 Manifest 名加载
-keep class top.mmjz.floatingclouds.App { *; }
-keep class top.mmjz.floatingclouds.ui.MainActivity { *; }

# IPlugin 接口 — 所有 Plugin/Part 通过此接口 dispatch，混淆不得改变 vtable
-keep interface top.mmjz.floatingclouds.plugin.IPlugin { *; }

# ViewBinding 生成的类 — 保持 findViewById / layout 关联
-keep class top.mmjz.floatingclouds.databinding.** { *; }

# 资源类
-keep class **.R { *; }
-keep class **.R$* { *; }

# ═══════════════════════════════════════════════════════════════════════════
# 常规 R8 混淆策略：
# 除上述"必须按原名保留"的入口外，其余模块类（plugin/hook/util/bean 等）
# 一律放开，由 R8 重命名类名 / 方法名 / 字段名。
#
# 安全性说明（不影响功能）：
#  - 模块内部类均为代码直接构造（如 WXMaskPlugin()），无按模块类名反射加载，
#    故重命名后引用由 R8 自动重写下仍一致；
#  - 反射加载的均为微信/系统类（com.tencent.mm、dalvik.system.DexFile 等），
#    与模块自身类名无关；
#  - 配置序列化（OptionData / MaskItemBean 等）使用字面量 key 字符串，
#    R8 不改进字符串，故旧版本配置读写不受影响；
#  - BuildConfig 由 AGP 直接内联，无需保留。
# ═══════════════════════════════════════════════════════════════════════════

# 重新打包：将混淆后的类移入单一包，去除原始包结构痕迹
# （-repackageclasses 需配合 -allowaccessmodification 以放宽访问修饰符）
-allowaccessmodification
-repackageclasses 'a'

# 混淆类名时保留「包名 + 类名首字母」风格的映射可读性（可选，注释掉即完全扁平）
# -obfuscationdictionary proguard-dictionary.txt

# 移除无用的注解（保留 @Keep 等运行时注解由下方规则覆盖）
-keepattributes *Annotation*

# 保留源码行号信息（mapping 可还原堆栈，且不影响混淆强度）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留内部类 / 匿名类 / 泛型 / 签名（避免某些反射/序列化边界问题）
-keepattributes InnerClasses,Signature,EnclosingMethod

# Kotlin $WhenMappings — 枚举 when 分支映射表（R8 通常自动保留，但跨版本行为不一，显式 keep 为最佳实践）
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Kotlin 元数据 — 保留 @kotlin.Metadata 注解（R8 默认保留，显式声明防未来变更）
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# 枚举保持 valueOf/values（android.util.Log 等无影响，保险）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── AppLog 剥离规则已移至 proguard-rules-release.pro（仅正式 release 生效） ──
# 测试版（debug buildType，minifyEnabled=false）不引用任何剥离规则，AppLog 天然保留并落文件日志。
