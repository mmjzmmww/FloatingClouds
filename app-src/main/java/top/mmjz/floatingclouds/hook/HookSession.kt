package top.mmjz.floatingclouds.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import top.mmjz.floatingclouds.util.AppLog
import top.mmjz.floatingclouds.util.AppReflect
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Hook 会话封装，传递给每个 IPlugin.handleHook() 使用。
 * 本质是对 XposedModule.hook() 的委托 + 常用便捷方法。
 *
 * libxposed API 102 要求 hook() 必须从 XposedModule 实例发起，
 * 因此 HookSession 不直接调用 module.hook()，而是通过 XposedEntry 暴露的委托函数。
 */
class HookSession(
    val classLoader: ClassLoader,
    val processName: String,
    private val hookDelegate: (Executable) -> XposedInterface.HookBuilder
) {
    /**
     * 最底层的 hook 入口。
     * 等价于 XposedModule.hook(executable)，返回 HookBuilder 供链式配置。
     */
    fun hook(executable: Executable): XposedInterface.HookBuilder = hookDelegate(executable)

    // ── 便捷方法 ──

    /**
     * 按类名 + 方法名 + 参数类型查找方法并 hook。
     * 返回 HookHandle，可后续调用 .unhook() 或 .replaceHook()。
     */
    fun findAndHook(
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>?,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
        interceptor: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle? {
        val method = AppReflect.findMethodExact(className, classLoader, methodName, *paramTypes)
        if (method == null) {
            AppLog.w("findAndHook: method not found: $className.$methodName")
            return null
        }
        return hook(method)
            .setPriority(priority)
            .setExceptionMode(exceptionMode)
            .intercept { chain -> interceptor(chain) }
    }

    /**
     * 按类名 + 方法名（无参）查找并 hook。
     */
    fun findAndHookNoArgs(
        className: String,
        methodName: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
        interceptor: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle? {
        val method = AppReflect.findMethodExact(className, classLoader, methodName)
        if (method == null) {
            AppLog.w("findAndHookNoArgs: method not found: $className.$methodName")
            return null
        }
        return hook(method)
            .setPriority(priority)
            .setExceptionMode(exceptionMode)
            .intercept { chain -> interceptor(chain) }
    }

    /**
     * hook 一个已经找到的 Method 对象。
     */
    fun hookMethod(
        method: Method,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
        interceptor: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle {
        return hook(method)
            .setPriority(priority)
            .setExceptionMode(exceptionMode)
            .intercept { chain -> interceptor(chain) }
    }

    /**
     * 按 predicate 匹配查找方法，并对匹配的每个方法分别 hook。
     */
    fun findAndHookByPredicate(
        className: String,
        predicate: (Method) -> Boolean,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
        interceptor: (XposedInterface.Chain) -> Any?
    ): List<XposedInterface.HookHandle> {
        val methods = AppReflect.findMethodsByExactPredicate(className, classLoader, predicate)
        return methods.map { method ->
            hook(method)
                .setPriority(priority)
                .setExceptionMode(exceptionMode)
                .intercept { chain -> interceptor(chain) }
        }
    }
}
