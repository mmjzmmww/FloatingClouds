package top.mmjz.floatingclouds.util

import top.mmjz.floatingclouds.ObfuscatedClassResolver
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 运行时定位微信 VoIP 相关类/方法，降低对硬编码混淆类名的依赖。
 * 重构版：不再依赖 XposedHelpers2/XposedBridge，改用 AppReflect。
 */
object VoipClassResolver {

    private const val TAG = "VoipClassResolver"

    // 8.0.70 实测 NewVoipMgr 被混淆为 com.tencent.mm.plugin.voip.model.h2
    // 8.0.74 实测 NewVoipMgr 被混淆为 com.tencent.mm.plugin.voip.model.b2
    // 8.0.74 新增描述性类名 NewVoipMgr
    private val VOIP_MGR_CANDIDATES = arrayOf(
        "com.tencent.mm.plugin.voip.model.b2",          // 8.0.74
        "com.tencent.mm.plugin.voip.model.h2",          // 8.0.70
        "com.tencent.mm.plugin.voip.model.NewVoipMgr"   // 8.0.74 新描述性类名
    )

    // bindVoipForegroundIfNeed(String username, boolean isVideo, boolean isRecalled, long roomKey)
    private val BIND_FOREGROUND_SIGNATURE = arrayOf(
        String::class.java,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Long::class.javaPrimitiveType
    )

    // 8.0.70 实测 VoipIncomingCallManager 被混淆为 com.tencent.mm.plugin.voip.model.n
    // 8.0.74 新增 NewVoipIncomingCallManager, e0 等候选
    private val INCOMING_CALL_MGR_CANDIDATES = arrayOf(
        "com.tencent.mm.plugin.voip.model.NewVoipIncomingCallManager",  // 8.0.74 新描述性类名
        "com.tencent.mm.plugin.voip.model.VoipIncomingCallManager",     // 通用接口
        "com.tencent.mm.plugin.voip.model.n",                           // 8.0.70
        "com.tencent.mm.plugin.voip.model.e0",                          // 8.0.74 混淆名
        "com.tencent.mm.plugin.voip.model.c0"                           // 8.0.70 实现
    )

    // RoomInfo 常见字段名
    private val WXID_FIELD_CANDIDATES = arrayOf("i", "f309528i", "f", "f309528f")
    private val ROOM_ID_FIELD_CANDIDATES = arrayOf("d", "f309528d", "b", "f309528b")
    private val ROOM_KEY_FIELD_CANDIDATES = arrayOf("e", "f309528e", "c", "f309528c")

    private var cachedVoipMgrClass: Class<*>? = null
    private var cachedBindMethods: List<Method>? = null
    private var cachedIncomingCallMethod: Method? = null
    private var cachedIncomingCallHandlerMethod: Method? = null
    private var cachedIncomingCallUiMethod: Method? = null
    private var cachedBusyResponder: Pair<Class<*>, Method>? = null

    @JvmStatic
    fun resolveBindVoipForegroundMethods(classLoader: ClassLoader): List<Method> {
        cachedBindMethods?.let { return it }

        val mgrClass = resolveVoipMgrClass(classLoader)
        if (mgrClass == null) {
            log("resolveVoipMgrClass returned null")
            return emptyList()
        }

        val methods = mgrClass.declaredMethods.filter { method ->
            method.returnType == Void.TYPE && method.parameterTypes.contentEquals(BIND_FOREGROUND_SIGNATURE)
        }.onEach { it.isAccessible = true }

        if (methods.isNotEmpty()) {
            cachedBindMethods = methods
            log("resolved bind methods: ${methods.joinToString { "${it.declaringClass.name}#${it.name}" }}")
        } else {
            log("cannot find bindVoipForegroundIfNeed method in ${mgrClass.name}")
        }
        return methods
    }

    @JvmStatic
    fun resolveIncomingCallManagerMethod(classLoader: ClassLoader): Method? {
        cachedIncomingCallMethod?.let { return it }

        log("resolveIncomingCallManagerMethod start")
        val mgrClass = try {
            resolveIncomingCallMgrClass(classLoader)
        } catch (e: Throwable) {
            log("resolveIncomingCallMgrClass threw: ${e.javaClass.name}: ${e.message}")
            null
        }

        if (mgrClass == null) {
            log("resolveIncomingCallManagerMethod: mgrClass is null")
            return null
        }

        log("resolveIncomingCallManagerMethod: scanning ${mgrClass.name}, methods=${mgrClass.declaredMethods.size}")
        val candidates = mgrClass.declaredMethods.filter { m ->
            m.name == "a" &&
                    m.returnType == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes.size == 1
        }
        candidates.forEach { m ->
            log("  candidate: ${m.name}(${m.parameterTypes.joinToString { it.name ?: "?" }}) return=${m.returnType.name}")
        }

        val method = candidates.firstOrNull()
        if (method != null) {
            method.isAccessible = true
            cachedIncomingCallMethod = method
            log("resolved incoming call method: ${method.declaringClass.name}#${method.name} param=${method.parameterTypes[0].name}")
        } else {
            log("cannot find incoming call method in ${mgrClass.name}")
        }
        return method
    }

    @JvmStatic
    fun resolveIncomingCallHandlerMethod(classLoader: ClassLoader): Method? {
        cachedIncomingCallHandlerMethod?.let { return it }

        val mgrClass = resolveVoipMgrClass(classLoader) ?: return null
        val candidates = mgrClass.declaredMethods.filter { m ->
            m.name == "h" &&
                    m.returnType == Void.TYPE &&
                    m.parameterTypes.size == 1 &&
                    isRoomInfoClass(m.parameterTypes[0])
        }

        val method = candidates.firstOrNull()
        if (method != null) {
            method.isAccessible = true
            cachedIncomingCallHandlerMethod = method
            log("resolved incoming call handler method: ${method.declaringClass.name}#${method.name} param=${method.parameterTypes[0].name}")
        } else {
            log("cannot find incoming call handler method in ${mgrClass.name}")
            val fallback = mgrClass.declaredMethods.filter { m ->
                m.returnType == Void.TYPE &&
                        m.parameterTypes.size == 1 &&
                        isRoomInfoClass(m.parameterTypes[0])
            }.firstOrNull()
            if (fallback != null) {
                fallback.isAccessible = true
                cachedIncomingCallHandlerMethod = fallback
                log("fallback resolved incoming call handler method: ${fallback.declaringClass.name}#${fallback.name}")
                return fallback
            }
        }
        return method
    }

    @JvmStatic
    fun resolveIncomingCallUiMethod(classLoader: ClassLoader): Method? {
        cachedIncomingCallUiMethod?.let { return it }

        val mgrClass = resolveVoipMgrClass(classLoader) ?: return null
        val method = mgrClass.declaredMethods.find { m ->
            m.name == "M" &&
                    m.returnType == Void.TYPE &&
                    m.parameterTypes.size == 6 &&
                    m.parameterTypes[0] == android.content.Context::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == Long::class.javaPrimitiveType &&
                    m.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[4] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[5] == Boolean::class.javaPrimitiveType
        }

        if (method != null) {
            method.isAccessible = true
            cachedIncomingCallUiMethod = method
            log("resolved incoming call UI method: ${method.declaringClass.name}#${method.name}")
        } else {
            log("cannot find incoming call UI method in ${mgrClass.name}")
            val fallback = mgrClass.declaredMethods.find { m ->
                m.returnType == Void.TYPE &&
                        m.parameterTypes.size == 6 &&
                        m.parameterTypes[0] == android.content.Context::class.java &&
                        m.parameterTypes[1] == String::class.java &&
                        (m.parameterTypes[2] == Long::class.javaPrimitiveType || m.parameterTypes[2] == java.lang.Long::class.java) &&
                        m.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                        m.parameterTypes[4] == Boolean::class.javaPrimitiveType &&
                        m.parameterTypes[5] == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            if (fallback != null) {
                cachedIncomingCallUiMethod = fallback
                log("fallback resolved incoming call UI method: ${fallback.declaringClass.name}#${fallback.name}")
                return fallback
            }
        }
        return method
    }

    @JvmStatic
    fun resolveBusyResponder(voipMgr: Any, roomInfo: Any): Pair<Any?, Method>? {
        cachedBusyResponder?.let { (helperClass, method) ->
            val instance = findBusyResponderInstance(voipMgr, helperClass)
            return if (instance != null) Pair(instance, method) else null
        }

        val busySig: Array<Class<*>> = arrayOf(
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            ByteArray::class.java,
            ByteArray::class.java,
            String::class.java
        )

        var helperClass: Class<*>? = null
        var helperMethod: Method? = null
        run findLoop@{
            for (field in getAllFields(voipMgr.javaClass)) {
                runCatching {
                    field.isAccessible = true
                    val obj = field.get(voipMgr) ?: return@runCatching
                    val methods = obj.javaClass.declaredMethods.filter { m ->
                        m.returnType == Void.TYPE && m.parameterTypes.contentEquals(busySig)
                    }
                    if (methods.isNotEmpty()) {
                        helperClass = obj.javaClass
                        helperMethod = methods.first().apply { isAccessible = true }
                        log("found busy responder in field ${field.name} type=${obj.javaClass.name} method=${helperMethod!!.name}")
                        return@findLoop
                    }
                }
            }
        }

        if (helperClass == null) {
            helperMethod = findBusyResponderMethodInClass(roomInfo.javaClass, busySig)
            helperClass = if (helperMethod != null) roomInfo.javaClass else null
            if (helperClass != null) {
                log("found busy responder on roomInfo class ${roomInfo.javaClass.name}#${helperMethod!!.name}")
            }
        }

        if (helperClass == null || helperMethod == null) {
            log("resolveBusyResponder: no busy responder found")
            return null
        }

        cachedBusyResponder = Pair(helperClass, helperMethod)
        val instance = findBusyResponderInstance(voipMgr, helperClass)
        return if (instance != null) Pair(instance, helperMethod) else null
    }

    private fun findBusyResponderInstance(voipMgr: Any, helperClass: Class<*>): Any? {
        for (field in getAllFields(voipMgr.javaClass)) {
            val obj = runCatching { field.apply { isAccessible = true }.get(voipMgr) }.getOrNull()
            if (obj != null && helperClass.isAssignableFrom(obj.javaClass)) return obj
        }
        if (helperClass.isAssignableFrom(voipMgr.javaClass)) return voipMgr
        return null
    }

    private fun findBusyResponderMethodInClass(cls: Class<*>, sig: Array<Class<*>>): Method? {
        return cls.declaredMethods.find { m ->
            m.returnType == Void.TYPE && m.parameterTypes.contentEquals(sig)
        }?.apply { isAccessible = true }
    }

    @JvmStatic
    fun extractWxidFromRoomInfo(roomInfo: Any): String? {
        for (name in WXID_FIELD_CANDIDATES) {
            val value = runCatching {
                roomInfo.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(roomInfo) as? String
            }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return getAllFields(roomInfo.javaClass).firstNotNullOfOrNull { field ->
            runCatching {
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    val value = field.get(roomInfo) as? String
                    if (value?.startsWith("wxid_") == true) value else null
                } else null
            }.getOrNull()
        }
    }

    @JvmStatic
    fun extractRoomIdKey(roomInfo: Any): Pair<Int, Long> {
        val roomId = ROOM_ID_FIELD_CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching { roomInfo.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(roomInfo) as? Int }.getOrNull()
        } ?: 0
        val roomKey = ROOM_KEY_FIELD_CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching { roomInfo.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(roomInfo) as? Long }.getOrNull()
        } ?: 0L
        return roomId to roomKey
    }

    @JvmStatic
    fun invalidateCache() {
        cachedVoipMgrClass = null
        cachedBindMethods = null
        cachedIncomingCallMethod = null
        cachedIncomingCallHandlerMethod = null
        cachedIncomingCallUiMethod = null
        cachedBusyResponder = null
    }

    private fun isRoomInfoClass(cls: Class<*>): Boolean {
        if (cls == Object::class.java) return false
        val fields = getAllFields(cls)
        val hasString = fields.any { it.type == String::class.java }
        val hasInt = fields.any { it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java }
        val hasLong = fields.any { it.type == Long::class.javaPrimitiveType || it.type == java.lang.Long::class.java }
        return hasString && hasInt && hasLong
    }

    private fun getAllFields(cls: Class<*>?): List<Field> {
        val result = ArrayList<Field>()
        var current = cls
        while (current != null && current != Object::class.java) {
            result.addAll(current.declaredFields)
            current = current.superclass
        }
        return result
    }

    private fun resolveIncomingCallMgrClass(classLoader: ClassLoader): Class<*>? {
        // T5: DexKit 主路径 + 硬编码兜底（ObfuscatedClassResolver 已含候选链，
        // 原 no-op 的签名扫描兜底已替换为 DexKit 真查询）
        val cls = ObfuscatedClassResolver.resolveIncomingCallMgrClass(classLoader)
        if (cls != null) {
            log("use incoming call mgr (DexKit/Fallback): ${cls.name}")
        } else {
            log("incoming call mgr not found (DexKit + fallback 均失败)")
        }
        return cls
    }

    private fun resolveVoipMgrClass(classLoader: ClassLoader): Class<*>? {
        cachedVoipMgrClass?.let { return it }

        // T5: DexKit 主路径 + 硬编码兜底（ObfuscatedClassResolver 已含 b2/h2/NewVoipMgr 候选链）
        val cls = ObfuscatedClassResolver.resolveVoipMgrClass(classLoader) ?: return null
        cachedVoipMgrClass = cls
        log("use VoIP mgr (DexKit/Fallback): ${cls.name}")
        return cls
    }

    private fun log(msg: String) {
        StealthLog.i("[$TAG] $msg")
    }
}
