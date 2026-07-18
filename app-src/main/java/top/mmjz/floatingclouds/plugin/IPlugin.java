package top.mmjz.floatingclouds.plugin;

import top.mmjz.floatingclouds.hook.HookSession;

/**
 * Floatingclouds 插件统一接口（API 102 重构版）。
 *
 * 新签名：handleHook(HookSession) 替代 handleHook(Context, XC_LoadPackage.LoadPackageParam)
 * - HookSession 封装 classLoader、hook() 委托、便捷方法
 * - 不再暴露 XC_LoadPackage（属于旧 API，API 102 不可用）
 */
public interface IPlugin {
    void handleHook(HookSession session);
    default void onCreate() {}
    default void onConfigChange() {}
}
