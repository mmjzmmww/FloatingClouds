package top.mmjz.floatingclouds.util

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher

/**
 * DexKit 扫描封装，使用原生 DexKit API。
 * build.gradle 中依赖 org.luckypray:dexkit:2.0.0。
 */
object DkBridge {

    private var bridge: DexKitBridge? = null

    fun init(apkPath: String): Boolean {
        return try {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(apkPath)
            android.util.Log.i("[MyPlugin-DexKit]", "DkBridge.init OK, apkPath=$apkPath")
            bridge != null
        } catch (e: Exception) {
            android.util.Log.w("[MyPlugin-DexKit]", "DkBridge.init FAILED: ${e.message}")
            false
        }
    }

    val isReady: Boolean get() = bridge != null

    /** 按类名模式搜索 DEX，返回类名列表 */
    fun findClasses(
        packages: Array<out String>,
        namePattern: String,
        exact: Boolean = false
    ): List<String> {
        val b = bridge ?: return emptyList()
        return try {
            val result = b.findClass(FindClass().apply {
                searchPackages(*packages.toList().toTypedArray())
                matcher(ClassMatcher().apply {
                    if (exact) {
                        className(namePattern)
                    } else {
                        addUsingString(namePattern)
                    }
                })
            })
            result.map { it.name }
        } catch (e: Exception) {
            AppLog.w("DkBridge.findClasses($namePattern) fail", e)
            emptyList()
        }
    }

    /** 查找包含目标签名字符串的方法，返回 Triple(className, methodName, signature) */
    fun findMethodsBySignature(signature: String): List<Triple<String, String, String>> {
        val b = bridge ?: return emptyList()
        return try {
            val result = b.findMethod(FindMethod().apply {
                matcher(MethodMatcher().apply {
                    addUsingString(signature)
                })
            })
            result.map {
                Triple(it.className, it.methodName, it.descriptor)
            }
        } catch (e: Exception) {
            AppLog.w("DkBridge.findMethodsBySignature fail", e)
            emptyList()
        }
    }

    /**
     * 按类名 + 方法名模式查找方法描述符。
     * 用于在运行时确认混淆后方法签名。
     */
    fun findMethodDescriptors(className: String, methodNamePattern: String): List<String> {
        val b = bridge ?: return emptyList()
        return try {
            val result = b.findMethod(FindMethod().apply {
                searchPackages("com.tencent.mm", "va5", "kc5", "sd5", "yf5", "ri5")
                matcher(MethodMatcher().apply {
                    addUsingString(methodNamePattern)
                })
            })
            result.map { "${it.className}.${it.methodName}${it.descriptor}" }
        } catch (e: Exception) {
            android.util.Log.w("[MyPlugin-DexKit]", "findMethodDescriptors fail: ${e.message}")
            emptyList()
        }
    }
}
