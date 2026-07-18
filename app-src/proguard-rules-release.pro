# ════════════════════════════════════════════════════════════════
# 正式发布版专属规则（仅 release buildType 叠加）
#
# 正式版剥离 AppLog 日志调用（安全，无功能影响），减小体积、去除调试开销。
# 注意：测试版（debug buildType，minifyEnabled=false）不引用本文件，因此日志调用被保留并落文件。
# ════════════════════════════════════════════════════════════════

-assumenosideeffects class top.mmjz.floatingclouds.util.AppLog {
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
}
# android.util.Log 保留用于 logcat 调试
# -assumenosideeffects class android.util.Log { ... }
