package top.mmjz.floatingclouds.util

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 纯反射工具层，替代 com.lu.lposed.api2.XposedHelpers2 中的反射部分。
 * Hook 操作交由 HookSession 处理（因为 libxposed API 102 要求 hook() 必须从 XposedModule 实例发起）。
 */
object AppReflect {

    // ── 类查找 ──

    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }
    }

    // ── 方法查找 ──

    @JvmStatic
    fun findMethodExact(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Any?
    ): Method? {
        val clazz = findClassIfExists(className, classLoader) ?: return null
        return findMethodExact(clazz, methodName, *parameterTypes)
    }

    @JvmStatic
    fun findMethodExact(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any?
    ): Method? {
        // 如果有 null 参数（通配符），回退为按名称+参数数量匹配，兼容旧 XposedHelpers2 行为
        val hasNullType = parameterTypes.any { it == null }
        if (hasNullType) {
            var current: Class<*>? = clazz
            while (current != null) {
                for (m in current.declaredMethods) {
                    if (m.name == methodName && m.parameterTypes.size == parameterTypes.size) {
                        m.isAccessible = true
                        return m
                    }
                }
                current = current.superclass
            }
            return null
        }
        val paramClasses = parameterTypes.map {
            when (it) {
                is Class<*> -> it
                is String -> findClassIfExists(it, clazz.classLoader)
                else -> it?.javaClass
            }
        }.toTypedArray()
        return try {
            clazz.getDeclaredMethod(methodName, *paramClasses).also { it.isAccessible = true }
        } catch (_: NoSuchMethodException) {
            // try super classes
            var current: Class<*>? = clazz.superclass
            while (current != null) {
                try {
                    return current.getDeclaredMethod(methodName, *paramClasses).also { it.isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    current = current.superclass
                }
            }
            null
        }
    }

    /**
     * 查找所有符合 predicate 的方法。
     * 等价于 XposedHelpers2.findMethodsByExactPredicate
     */
    @JvmStatic
    fun findMethodsByExactPredicate(
        className: String,
        classLoader: ClassLoader,
        predicate: (Method) -> Boolean
    ): Array<Method> {
        val clazz = findClassIfExists(className, classLoader) ?: return emptyArray()
        return findMethodsByExactPredicate(clazz, predicate)
    }

    @JvmStatic
    fun findMethodsByExactPredicate(
        clazz: Class<*>,
        predicate: (Method) -> Boolean
    ): Array<Method> {
        return clazz.declaredMethods.filter(predicate).toTypedArray()
    }

    // ── 字段操作 ──

    @JvmStatic
    fun findFieldsByExactPredicate(
        clazz: Class<*>,
        predicate: (Field) -> Boolean
    ): Array<Field> {
        return clazz.declaredFields.filter(predicate).toTypedArray()
    }

    /**
     * 在 clazz 及其父类中查找第一个类型匹配 fieldType 的字段。
     */
    @JvmStatic
    fun findFirstFieldByExactType(clazz: Class<*>, fieldType: Class<*>): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.type == fieldType) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    @JvmStatic
    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
    }

    // ── 方法调用 ──

    /**
     * 按方法名 + 参数数量反射调用（兼容原 XposedHelpers2.callMethod 的宽松匹配策略）。
     * 不检查参数类型，匹配时只看方法名和参数个数，解决 Kotlin 严格类型导致 "Inapplicable candidate" 编译错。
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (m in clazz.declaredMethods) {
                if (m.name == methodName && m.parameterTypes.size == args.size) {
                    m.isAccessible = true
                    try { return m.invoke(obj, *args) } catch (_: Exception) { }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    @JvmStatic
    fun callStaticMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg args: Any?
    ): Any? {
        val clazz = findClassIfExists(className, classLoader) ?: return null
        for (m in clazz.declaredMethods) {
            if (m.name == methodName && m.parameterTypes.size == args.size) {
                m.isAccessible = true
                try { return m.invoke(null, *args) } catch (_: Exception) { }
            }
        }
        return null
    }

    // ── 附加字段（替代 XposedHelpers.getAdditionalInstanceField / setAdditionalInstanceField）──

    private val additionalFields = java.util.WeakHashMap<Any, MutableMap<String, Any?>>()

    @JvmStatic
    @Synchronized
    fun getAdditionalInstanceField(obj: Any?, key: String): Any? {
        if (obj == null) return null
        return additionalFields[obj]?.get(key)
    }

    @JvmStatic
    @Synchronized
    fun setAdditionalInstanceField(obj: Any?, key: String, value: Any?) {
        if (obj == null) return
        additionalFields.getOrPut(obj) { mutableMapOf() }[key] = value
    }

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val paramClasses = args.map { it?.javaClass }.toTypedArray()
        return try {
            val method = clazz.getDeclaredMethod(methodName, *paramClasses)
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (_: NoSuchMethodException) {
            null
        }
    }
}
