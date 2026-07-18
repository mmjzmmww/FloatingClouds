package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.ObfuscatedClassResolver
import top.mmjz.floatingclouds.ObfuscatedClassResolver.ConvStoragePair
import top.mmjz.floatingclouds.ObfuscatedClassResolver.invokeStorageLocator
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 利用微信原生 "不显示该聊天" API 隐藏/显示会话。
 * 状态持久化在微信 DB (rconversation.parentRef) 中，不受模块/进程重启影响。
 *
 * 微信 8.0.74 实证：
 * - "不显示该聊天" 改写 rconversation 表的 parentRef 列（隐藏=hidden_conv_parent，显示=""）
 * - 正确实现是调用会话存储 m4（h2.Di()）的原生批量方法 void P(String[], String)：
 *     隐藏 → P(arrayOf(wxid), "hidden_conv_parent")
 *     显示 → P(arrayOf(wxid), "")
 * - 之前误用联系人存储 k4（h2.Ai()/Bi()）+ 反射 field_parentRef + p0，因 k4 非 rconversation
 *   且 p0 不持久化 hidden_conv_parent，导致隐藏永久失效。现已全面切换到 m4.P 原生路径。
 */
object ConversationHideHelper {

    private const val TAG = "ConvHide"

    // ── parentRef 反射句柄缓存 ──
    /** Conversation 对象的 field_parentRef Field（String 类型，smali 明文字段名） */
    private var parentRefField: java.lang.reflect.Field? = null
    /** 发现的父引用字段名（用于日志诊断） */
    private var parentRefFieldName: String? = null

    /** 是否已成功发现 parentRef 字段 */
    @Volatile private var methodsReady: Boolean = false

    /** 微信 rconversation.parentRef 的隐藏标记值 */
    private const val HIDDEN_PARENT_REF = "hidden_conv_parent"

    // ══ 8.0.74 正确机制：会话存储 m4（h2.Di()），原生批量隐藏方法 void P(String[], String) ══
    //   之前误用联系人存储 k4（h2.Ai()/Bi()），且 k4 用 n(String,boolean)，导致隐藏永久失效。
    /** 会话存储实例（com.tencent.mm.storage.m4，通过 h2 的某个 getter 获取） */
    @Volatile private var convHideStore: Any? = null
    /** 原生隐藏方法：void P(String[] usernames, String parentRef)；隐藏=hidden_conv_parent，显示="" */
    @Volatile private var hideMethodP: java.lang.reflect.Method? = null
    /** 按 wxid 取会话模型(l4)的 getter（用于 isHidden 回读校验） */
    @Volatile private var convModelGetter: java.lang.reflect.Method? = null
    /** 会话模型上的 parentRef String 字段（用于 isHidden 回读校验） */
    @Volatile private var modelParentRefField: java.lang.reflect.Field? = null

    // ═══════════════════════════════════════════════════════

    /**
     * 在主 hook 初始化时调用一次。
     * 搜索 Conversation 对象的 parentRef String 字段。
     * 微信 8.0.74: field_parentRef 在 smali 中是明文字段名（未混淆）。
     */
    fun init(classLoader: ClassLoader, convStorageClass: Class<*>, convObj: Any?) {
        if (methodsReady) return
        runCatching {
            val convClass = convObj?.javaClass ?: return@runCatching

            // 策略1: 精确名 field_parentRef（smali 明文字段名，最可靠）
            var clz: Class<*>? = convClass
            while (clz != null) {
                try {
                    val f = clz.getDeclaredField("field_parentRef")
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        parentRefField = f
                        parentRefFieldName = "field_parentRef"
                        methodsReady = true
                        steplog("init success: field_parentRef found in ${clz.simpleName}")
                        return@runCatching
                    }
                } catch (_: NoSuchFieldException) {}
                clz = clz.superclass
            }

            // 策略2: 关键词扫描 — 名称含 parentref / parent_ref（兼容混淆后的字段名）
            clz = convClass
            while (clz != null) {
                for (f in clz.declaredFields) {
                    if (f.type == String::class.java && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                        val name = f.name.lowercase()
                        if (name.contains("parentref") || name.contains("parent_ref")) {
                            f.isAccessible = true
                            parentRefField = f
                            parentRefFieldName = f.name
                            methodsReady = true
                            steplog("init success: keyword match '${f.name}' in ${clz.simpleName}")
                            return@runCatching
                        }
                    }
                }
                clz = clz.superclass
            }

            // 策略3（兜底，最稳健）: parentRef 是「会话专属」字段，声明在 Conversation 叶子类自身，
            //   而非其父类（联系人字段基类，含 field_username/field_nickname 等）。
            //   本版本(8.0.74 build 3120)叶子类 z3 唯一 String 字段即 K2（即 parentRef）。
            val leafFields = convClass.declaredFields.filter {
                it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            steplog("init: leaf-class String fields(${leafFields.size}): ${leafFields.joinToString { it.name }}")
            if (leafFields.size == 1) {
                val f = leafFields[0]
                f.isAccessible = true
                parentRefField = f
                parentRefFieldName = f.name
                methodsReady = true
                steplog("init success: leaf-class unique String field ${f.name} (parentRef heuristic)")
                return@runCatching
            }

            // 策略4: 若叶子类有多个 String 字段，用哨兵探针在「叶子字段」范围内定位（getter 回读比对）
            if (leafFields.isNotEmpty()) {
                steplog("init: multiple leaf String fields, running sentinel probe within leaf fields...")
                val sentinel = "__WXSENT__${System.currentTimeMillis()}__"
                for (g in convClass.declaredMethods) {
                    if (g.parameterTypes.isEmpty() && g.returnType == String::class.java) {
                        g.isAccessible = true
                        try {
                            for (sf in leafFields) {
                                val before = sf.get(convObj) as? String ?: ""
                                sf.set(convObj, sentinel)
                                val after = g.invoke(convObj) as? String
                                sf.set(convObj, before)
                                if (after == sentinel) {
                                    sf.isAccessible = true
                                    parentRefField = sf
                                    parentRefFieldName = sf.name
                                    methodsReady = true
                                    steplog("init success: sentinel probe found ${sf.name} via ${g.name}()")
                                    return@runCatching
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            steplog("init failed: no parentRef field found in class hierarchy")
        }.onFailure {
            steplog("init failed: ${it.message}")
        }
    }

    // ═══════════════════════════════════════════════════════

    fun isEnabled(): Boolean = hideMethodP != null && convHideStore != null

    // ═══════════════════════════════════════════════════════

    /**
     * 懒初始化：第一次调用时自动用微信内部已加载的 ConversationStorage
     * 获取一个样本 Conversation 对象来扫描所有方法签名。
     */
    /** 诊断标记：确保方法列表只打印一次 */
    @Volatile private var methodsDumped: Boolean = false

    // ══ 会话存储/隐藏：storage 与 helper 必须成对解析，避免错配 ══
    // 8.0.74 实测：原 e4/yj0.j1 组合下 yj0.j1.s(e4) 返回 null，隐藏静默失效。
    // 正确配对来自 InkHide 验证过的元组（见 ObfuscatedClassResolver.CONV_STORAGE_PAIRS）。
    @Volatile private var convPair: ConvStoragePair? = null

    private fun getConvPair(classLoader: ClassLoader): ConvStoragePair? {
        if (convPair != null) return convPair
        convPair = ObfuscatedClassResolver.resolveConvStoragePair(classLoader) ?: discoverConvPairByPattern(classLoader)
        return convPair
    }

    /** 返回 (storageInterface, helper, convGetter) 三元组；解析失败返回 null */
    private fun getConvTriple(classLoader: ClassLoader): Triple<Class<*>, Class<*>, String>? {
        val p = getConvPair(classLoader) ?: return null
        return Triple(p.storageInterface, p.helper, p.convGetter)
    }

    private fun findConvStorage(classLoader: ClassLoader): Class<*>? = getConvPair(classLoader)?.storageInterface
    private fun findConvStorageHelper(classLoader: ClassLoader): Class<*>? = getConvPair(classLoader)?.helper
    private fun findConvGetter(classLoader: ClassLoader): String? = getConvPair(classLoader)?.convGetter

    // ══ 8.0.74 正确机制：会话存储 m4（h2.Di()），原生批量隐藏方法 void P(String[], String) ══
    //   resolveNativeHide 一次性解析 m4 实例 + void P(String[],String) 方法 + 模型 getter，
    //   用于 isHidden 回读校验。替代旧的「反射 parentRef 字段 + p0」方案（该方案误用联系人存储 k4，
    //   且 p0 不持久化 hidden_conv_parent，已证实失效）。

    /** 在类层级上查找首个满足谓词的方法（含父类，兼容 P/模型 getter 被继承的情况）。 */
    private fun findMethodDeep(clz: Class<*>, pred: (java.lang.reflect.Method) -> Boolean): java.lang.reflect.Method? {
        var c: Class<*>? = clz
        while (c != null) {
            c.declaredMethods.firstOrNull(pred)?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** 取 h2（messenger.foundation 基础服务），m4/k4/g9 都从它派生。 */
    private fun getH2Instance(classLoader: ClassLoader): Any? {
        val triple = getConvTriple(classLoader) ?: return null
        return invokeStorageLocator(triple.second, triple.first)
    }

    /**
     * 解析会话存储 m4（com.tencent.mm.storage.m4）及其原生隐藏方法 void P(String[], String)。
     * 隐藏 = P(arrayOf(wxid), "hidden_conv_parent")；显示 = P(arrayOf(wxid), "")。
     * 同时定位 l4 模型 getter（x/p）与 field_parentRef 字段，供 isHidden 回读校验。
     */
    private fun resolveNativeHide(classLoader: ClassLoader): Boolean {
        if (hideMethodP != null && convHideStore != null) {
            steplog("resolveNativeHide: already resolved")
            return true
        }
        val h2 = getH2Instance(classLoader) ?: run { steplog("resolveNativeHide: h2 null"); return false }
        val m4Cls = AppReflect.findClassIfExists("com.tencent.mm.storage.m4", classLoader)
            ?: run { steplog("resolveNativeHide: m4 class not found"); return false }
        // 在 h2 上找返回 m4 实例的 getter（8.0.74 = Di()）
        val storeGetter = h2.javaClass.declaredMethods.firstOrNull { gm ->
            gm.parameterTypes.isEmpty() && !gm.returnType.isPrimitive && m4Cls.isAssignableFrom(gm.returnType)
        }?.also { it.isAccessible = true }
            ?: run { steplog("resolveNativeHide: no m4 getter on h2"); return false }
        val store = storeGetter.invoke(h2) ?: run { steplog("resolveNativeHide: m4 instance null"); return false }
        // 找 void P(String[], String)（可能在父类，跨层级查找）
        val pM = findMethodDeep(store.javaClass) { m ->
            m.name == "P" && m.parameterTypes.size == 2 &&
            m.parameterTypes[0].isArray && m.parameterTypes[0].componentType == String::class.java &&
            m.parameterTypes[1] == String::class.java
        }?.also { it.isAccessible = true } ?: run {
            steplog("resolveNativeHide: P(String[],String) not found on ${store.javaClass.name}"); return false
        }
        // 找模型 getter x(String)/p(String) 用于 isHidden 回读
        val modelG = findMethodDeep(store.javaClass) { m ->
            (m.name == "x" || m.name == "p") && m.parameterTypes.size == 1 &&
            m.parameterTypes[0] == String::class.java && !m.returnType.isPrimitive && m.returnType != Void.TYPE
        }?.also { it.isAccessible = true }
        // 从 l4 模型反射 field_parentRef 字段（明文 smali 字段名）
        var modelParentRef: java.lang.reflect.Field? = null
        if (modelG != null) {
            val sample = runCatching { modelG.invoke(store, "filehelper") }.getOrNull()
            if (sample != null) {
                var c: Class<*>? = sample.javaClass
                while (c != null) {
                    try {
                        val f = c.getDeclaredField("field_parentRef")
                        f.isAccessible = true
                        modelParentRef = f
                        break
                    } catch (_: NoSuchFieldException) { c = c.superclass }
                }
            }
        }
        convHideStore = store
        hideMethodP = pM
        convModelGetter = modelG
        modelParentRefField = modelParentRef
        steplog("resolveNativeHide OK: store=${store.javaClass.name} getter=${storeGetter.name} P=${pM.name} modelG=${modelG?.name} parentRef=${modelParentRef?.name}")
        return true
    }

    /**
     * 暴力扫描：(storageClass, helperClass) 配对，验证 helper.s(storageClass) 返回非空，
     * 且其 getter 返回带 n(String,boolean) 的会话存储。
     * 仅当 ObfuscatedClassResolver 全部配对失败时作为末位保障。
     */
    private fun discoverConvPairByPattern(classLoader: ClassLoader): ConvStoragePair? {
        steplog("discoverConvPair: scanning for working (storage, helper) pair...")
        val storages = ('a'..'z').flatMap { c -> (0..9).map { "$c$it.x3" } }
        val helpers = ('a'..'z').flatMap { c1 -> ('a'..'z').flatMap { c2 ->
            (0..2).flatMap { n -> listOf("j0", "j1", "h0", "h1", "k0", "l0").map { "$c1$c2$n.$it" } }
        } }
        for (sName in storages) {
            val sCls = AppReflect.findClassIfExists(sName, classLoader) ?: continue
            for (hName in helpers) {
                val hCls = AppReflect.findClassIfExists(hName, classLoader) ?: continue
                try {
                    val h2 = ObfuscatedClassResolver.invokeStorageLocator(hCls, sCls) ?: continue
                    // 探测 h2 上的 getter：返回带 n(String,boolean) 的子存储。
                    // ★ 关键修复：联系人存储(rcontact)与会话存储(rconversation)都带 n(String,boolean)，
                    //   必须用样本对象表名判据优先选 rconversation，否则误选联系人存储导致 parentRef 隐藏失效。
                    val hasNGetters = h2.javaClass.declaredMethods.filter {
                        it.parameterTypes.isEmpty() && it.returnType != Void.TYPE && !it.returnType.isPrimitive
                    }.mapNotNull { gm ->
                        gm.isAccessible = true
                        val sub = runCatching { gm.invoke(h2) }.getOrNull() ?: return@mapNotNull null
                        val hasN = sub.javaClass.declaredMethods.any {
                            it.name == "n" && it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                        }
                        if (hasN) gm.name to sub else null
                    }
                    // 优先选样本表名为 rconversation 的 getter
                    val getter = hasNGetters.firstOrNull { (_, sub) ->
                        sampleReturnsConversation(sub)
                    }?.first ?: hasNGetters.firstOrNull()?.first ?: run {
                        // 兼容旧版：h2 自身带 n(String,boolean)
                        val selfHasN = h2.javaClass.declaredMethods.any {
                            it.name == "n" && it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                        }
                        if (selfHasN) "Tg" else null
                    }
                    if (getter != null) {
                        steplog("discoverConvPair: FOUND $sName + $hName getter=$getter")
                        return ConvStoragePair(sCls, hCls, getter)
                    }
                } catch (_: Exception) {}
            }
        }
        steplog("discoverConvPair: no working pair found")
        return null
    }

    /** 用 filehelper 样本判定子存储是否为会话存储：getTableName()=='rconversation' 或含 field_parentRef 字段。 */
    private fun sampleReturnsConversation(sub: Any): Boolean = runCatching {
        val nm = sub.javaClass.declaredMethods.firstOrNull {
            it.name == "n" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return false
        nm.isAccessible = true
        val conv = nm.invoke(sub, "filehelper", true) ?: return false
        // 判据1：表名
        var c: Class<*>? = conv.javaClass
        while (c != null) {
            val gm = c.declaredMethods.firstOrNull {
                it.name == "getTableName" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }
            if (gm != null) {
                gm.isAccessible = true
                val tn = gm.invoke(conv) as? String
                if (tn != null) return tn.equals("rconversation", ignoreCase = true)
            }
            c = c.superclass
        }
        // 判据2：字段层级含 field_parentRef
        c = conv.javaClass
        while (c != null) {
            if (c.declaredFields.any { it.name == "field_parentRef" }) return true
            c = c.superclass
        }
        false
    }.getOrDefault(false)

    // 已被 discoverConvPairByPattern（配对扫描）替代；保留为空壳避免误用。
    @Suppress("unused")
    private fun discoverHelperBySignature(classLoader: ClassLoader): Class<*>? = null

    /** 诊断标记：确保结构列表只打印一次 */
    @Volatile private var structureDumped: Boolean = false

    /**
     * 结构诊断：dump Conversation 对象的所有 String 字段（名+值）与无参 String getter（名+值），
     * 用于在混淆版本上定位真实的 parentRef 字段及其 getter。结果写入文件避免被 logcat 冲掉。
     */
    private fun dumpConversationStructure(classLoader: ClassLoader) {
        structureDumped = true
        val sb = StringBuilder()
        sb.append("=== ConversationHideHelper structure dump @ ${System.currentTimeMillis()}\n")
        runCatching {
            val x3 = findConvStorage(classLoader) ?: run { sb.append("STRUCT: ConvStorage null\n"); return@runCatching }
            val j1 = findConvStorageHelper(classLoader) ?: run { sb.append("STRUCT: Helper null\n"); return@runCatching }
            val convGetter = findConvGetter(classLoader) ?: run { sb.append("STRUCT: convGetter null\n"); return@runCatching }
            val h2 = invokeStorageLocator(j1, x3) ?: run { sb.append("STRUCT: h2 null\n"); return@runCatching }
            val dh = AppReflect.callMethod(h2, convGetter) ?: run { sb.append("STRUCT: dh null\n"); return@runCatching }
            val conv = AppReflect.callMethod(dh, "n", "filehelper", true) ?: run { sb.append("STRUCT: conv null\n"); return@runCatching }
            sb.append("=== Conversation class: ${conv.javaClass.name}\n")
            // 1) 所有 String 字段（名+值）
            var clz: Class<*>? = conv.javaClass
            val fields = mutableListOf<java.lang.reflect.Field>()
            while (clz != null) {
                for (f in clz.declaredFields) {
                    if (f.type == String::class.java && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                        f.isAccessible = true
                        fields.add(f)
                        val v = runCatching { f.get(conv) as? String }.getOrNull()
                        sb.append("  FIELD ${clz.simpleName}.${f.name} = '${v?.take(48)}'\n")
                    }
                }
                clz = clz.superclass
            }
            sb.append("=== total ${fields.size} String fields\n")
            // 2) 所有无参 String getter（名+值）
            clz = conv.javaClass
            val getters = mutableListOf<java.lang.reflect.Method>()
            while (clz != null) {
                for (m in clz.declaredMethods) {
                    if (m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                        m.isAccessible = true
                        getters.add(m)
                        val v = runCatching { m.invoke(conv) as? String }.getOrNull()
                        sb.append("  GETTER ${clz.simpleName}.${m.name}() = '${v?.take(48)}'\n")
                    }
                }
                clz = clz.superclass
            }
            sb.append("=== total ${getters.size} String getters\n")
            // 3) 哨兵探针：对每个无参 String getter，逐字段写哨兵值，找出 getter 对应的字段
            sb.append("=== sentinel probe (getter -> field):\n")
            val sentinel = "__WXSENT__${System.currentTimeMillis()}__"
            for (g in getters) {
                try {
                    val orig = g.invoke(conv) as? String
                    for (f in fields) {
                        val before = f.get(conv) as? String ?: ""
                        f.set(conv, sentinel)
                        val after = g.invoke(conv) as? String
                        f.set(conv, before)
                        if (after == sentinel) {
                            sb.append("  GETTER ${g.name}() <-> FIELD ${f.name} (orig='${orig?.take(24)}')\n")
                        }
                    }
                } catch (_: Exception) {}
            }
        }.onFailure { sb.append("dumpStructure failed: ${it.message}\n${it.stackTraceToString()}\n") }
        writeStructFile(sb.toString())
        steplog("STRUCT dump written, len=${sb.length}")
    }

    private fun writeStructFile(content: String) {
        runCatching {
            val dir = java.io.File("/data/data/com.tencent.mm/files")
            dir.mkdirs()
            java.io.File(dir, "conv_struct.txt").writeText(content)
        }.onFailure { steplog("writeStructFile failed: ${it.message}") }
    }

    private fun writeProbeFile(content: String) {
        runCatching {
            val dir = java.io.File("/data/data/com.tencent.mm/files")
            dir.mkdirs()
            java.io.File(dir, "conv_parentref_probe.txt").writeText(content)
        }.onFailure { steplog("writeProbeFile failed: ${it.message}") }
    }

    private fun dumpMethods(classLoader: ClassLoader) {
        methodsDumped = true
        runCatching {
            val x3 = findConvStorage(classLoader) ?: return
            val j1 = findConvStorageHelper(classLoader) ?: return
            val convGetter = findConvGetter(classLoader) ?: return
            val h2 = invokeStorageLocator(j1, x3) ?: return
            val dh = AppReflect.callMethod(h2, convGetter) ?: return
            val conv = AppReflect.callMethod(dh, "n", "filehelper", true) ?: return
            var clz: Class<*>? = conv.javaClass
            while (clz != null) {
                val methods = clz.declaredMethods.map { m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    "${m.returnType.simpleName} ${m.name}($params)"
                }.sorted().joinToString("\n  ")
                steplog("Conversation class ${clz.simpleName} (${clz.declaredMethods.size} methods):\n  $methods")
                clz = clz.superclass
            }
        }.onFailure {
            steplog("dumpMethods failed: ${it.message}")
        }
    }

    @Volatile private var convDiscDumped: Boolean = false

    /**
     * 一次性探测：DexKit 指出真正会话存储是 com.tencent.mm.storage.g9 / m4（引用 "rconversation"），
     * 而当前解析拿到的是联系人存储 k4。此函数 dump g9/m4 的方法签名，并尝试用多种方式定位其实例：
     * ① h2 上返回 instanceof g9/m4 的 getter；② g9/m4 的静态访问器；③ 从实例上找会话 getter 并验证表名。
     * 结果写文件 conv_storage_discovery.txt，供确定最终实现方案。
     */
    private fun dumpConvStorageDiscovery(classLoader: ClassLoader) {
        if (convDiscDumped) return
        convDiscDumped = true
        val sb = StringBuilder()
        sb.append("=== conv storage discovery @ ${System.currentTimeMillis()}\n")
        runCatching {
            val candNames = listOf("com.tencent.mm.storage.g9", "com.tencent.mm.storage.m4")
            val candClasses = candNames.mapNotNull { n ->
                AppReflect.findClassIfExists(n, classLoader)?.also { sb.append("LOADED $n\n") }
                    ?: run { sb.append("MISS $n\n"); null }
            }
            // 1) dump 方法签名
            for (cc in candClasses) {
                sb.append("--- methods of ${cc.name} (super=${cc.superclass?.name}) ---\n")
                for (m in cc.declaredMethods.sortedBy { it.name }) {
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    val stat = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else ""
                    sb.append("  $stat${m.returnType.simpleName} ${m.name}($params)\n")
                }
            }
            // 2) 尝试从 h2 定位会话存储实例
            val triple = getConvTriple(classLoader)
            if (triple == null) { sb.append("NO convTriple\n") }
            else {
                val (x3, j1, _) = triple
                val h2 = invokeStorageLocator(j1, x3)
                sb.append("h2=${h2?.javaClass?.name}\n")
                if (h2 != null) {
                    sb.append("--- h2 ALL no-arg object getters ---\n")
                    for (gm in h2.javaClass.declaredMethods.filter { it.parameterTypes.isEmpty() && !it.returnType.isPrimitive && it.returnType != Void.TYPE }) {
                        gm.isAccessible = true
                        val r = runCatching { gm.invoke(h2) }.getOrNull()
                        val isCand = candClasses.any { it.isInstance(r) }
                        sb.append("  ${gm.name}() -> ${r?.javaClass?.name}${if (isCand) "  <== CONV STORAGE!" else ""}\n")
                    }
                }
            }
            // 3) 尝试静态访问器拿实例
            for (cc in candClasses) {
                for (m in cc.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && !it.returnType.isPrimitive }) {
                    m.isAccessible = true
                    val r = runCatching { m.invoke(null) }.getOrNull()
                    if (r != null) sb.append("STATIC ${cc.simpleName}.${m.name}() -> ${r.javaClass.name}\n")
                }
            }
        }.onFailure { sb.append("discovery failed: ${it.message}\n${it.stackTraceToString()}\n") }
        runCatching {
            java.io.File("/data/data/com.tencent.mm/files", "conv_storage_discovery.txt").writeText(sb.toString())
        }
        steplog("CONV-DISCOVERY written len=${sb.length}")
    }

    fun ensureInitialized(classLoader: ClassLoader) {
        steplog("DIAG ensureInitialized ENTER methodsReady=$methodsReady structureDumped=$structureDumped")
        if (!convDiscDumped) dumpConvStorageDiscovery(classLoader)
        if (methodsReady) {
            if (!methodsDumped) dumpMethods(classLoader)
            return
        }
        // 主路径：用会话存储 m4 的原生 void P(String[],String) 实现隐藏/显示
        if (resolveNativeHide(classLoader)) {
            methodsReady = true
            steplog("ensureInitialized: NATIVE path ready, methodsReady=true")
            if (!methodsDumped) dumpMethods(classLoader)
            return
        }
        steplog("ensureInitialized: native path failed, hide will not work this session")
        return

            // ★ 运行时确定性探针：找出 p0 真正序列化、且写入 hidden_conv_parent 会触发隐藏过滤的字段

    }

    /** 探针诊断标记 */
    @Volatile private var probed: Boolean = false

    /**
     * 运行时探针：对每个 String 字段，写入 hidden_conv_parent 后 p0 持久化，再用「可见会话列表」方法检查
     * filehelper 是否仍在列表中。若从可见列表消失，说明该字段正是 parentRef（只有 parentRef 列写入该值
     * 才会触发微信的隐藏过滤）。探针会逐个字段测试并把 filehelper 还原，最后选定正确字段覆盖 parentRefField。
     */
    private fun probeParentRefField(classLoader: ClassLoader) {
        if (probed) return
        probed = true
        val sb = StringBuilder()
        sb.append("=== parentRef probe @ ${System.currentTimeMillis()}\n")
        runCatching {
            val x3 = findConvStorage(classLoader) ?: run { sb.append("PROBE: ConvStorage null\n"); return@runCatching }
            val j1 = findConvStorageHelper(classLoader) ?: run { sb.append("PROBE: Helper null\n"); return@runCatching }
            val convGetter = findConvGetter(classLoader) ?: run { sb.append("PROBE: convGetter null\n"); return@runCatching }
            val h2 = invokeStorageLocator(j1, x3) ?: run { sb.append("PROBE: h2 null\n"); return@runCatching }
            val dh = AppReflect.callMethod(h2, convGetter) ?: run { sb.append("PROBE: dh null\n"); return@runCatching }
            val conv = AppReflect.callMethod(dh, "n", "filehelper", true) ?: run { sb.append("PROBE: conv null\n"); return@runCatching }

            // 判据：n(wxid, false) 返回「仅可见」会话；若写入 parentRef 后 n(wxid,false) 返回 null，
            // 说明该会话从可见集合消失，被探测的字段正是 parentRef。
            // 先做 baseline：filehelper 未隐藏时 n(filehelper,false) 应非空，否则该判据不可用。
            val baseVisible = AppReflect.callMethod(dh, "n", "filehelper", false)
            val useVisibleProbe = baseVisible != null
            sb.append("PROBE baseline n(filehelper,false)!=null ? $useVisibleProbe\n")

            val fields = mutableListOf<java.lang.reflect.Field>()
            var clz: Class<*>? = conv.javaClass
            while (clz != null) {
                for (f in clz.declaredFields) {
                    if (f.type == String::class.java && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                        f.isAccessible = true
                        fields.add(f)
                    }
                }
                clz = clz.superclass
            }
            val hideCandidates = mutableListOf<String>()
            val roundTripCandidates = mutableListOf<String>()
            for (f in fields) {
                val before = f.get(conv) as? String ?: ""
                // 写入 hidden_conv_parent 并持久化
                f.set(conv, HIDDEN_PARENT_REF)
                AppReflect.callMethod(dh, "p0", "filehelper", conv)
                // 可见性判据
                var hiddenNow = false
                if (useVisibleProbe) {
                    val vis = AppReflect.callMethod(dh, "n", "filehelper", false)
                    hiddenNow = (vis == null)
                }
                // 回读确认 p0 真的序列化了该字段
                var reF: String? = null
                val re1 = AppReflect.callMethod(dh, "n", "filehelper", true)
                if (re1 != null) {
                    reF = runCatching { f.get(re1) as? String }.getOrNull()
                    if (reF == HIDDEN_PARENT_REF) roundTripCandidates.add(f.name)
                }
                if (hiddenNow) hideCandidates.add(f.name)
                sb.append("  FIELD ${f.name}: hiddenNow=$hiddenNow roundTrip=${reF == HIDDEN_PARENT_REF}\n")
                // 还原
                f.set(conv, before)
                AppReflect.callMethod(dh, "p0", "filehelper", conv)
            }
            sb.append("PROBE hideCandidates=${hideCandidates.joinToString()}\n")
            sb.append("PROBE roundTripCandidates=${roundTripCandidates.joinToString()}\n")
            val chosen = if (hideCandidates.isNotEmpty()) hideCandidates.first()
                else if (roundTripCandidates.isNotEmpty()) {
                    val leaf = conv.javaClass.declaredFields.firstOrNull {
                        it.name in roundTripCandidates && it.type == String::class.java
                    }
                    leaf?.name ?: roundTripCandidates.first()
                } else null
            if (chosen != null) {
                val cf = fields.firstOrNull { it.name == chosen }
                if (cf != null) {
                    parentRefField = cf
                    parentRefFieldName = chosen
                    methodsReady = true
                    sb.append("PROBE CHOSEN parentRefField=$chosen\n")
                }
            } else {
                sb.append("PROBE: no candidate, keep ${parentRefFieldName}\n")
            }
        }.onFailure { sb.append("PROBE failed: ${it.message}\n${it.stackTraceToString()}\n") }
        writeProbeFile(sb.toString())
        steplog("PROBE done, written to file")
    }

    private var probeLogged: Boolean = false

    private fun probeConversationFlags(conv: Any) {
        if (probeLogged) return
        probeLogged = true
        runCatching {
            val results = mutableListOf<String>()

            // ★ 打印 parentRef 值（微信 8.0.74 "不显示该聊天" 的真实字段）
            val ref = parentRefField?.get(conv) as? String
            results.add("  parentRef = '$ref'${if (ref == HIDDEN_PARENT_REF) " (HIDDEN)" else ""}")

            var c: Class<*>? = conv.javaClass
            while (c != null) {
                for (m in c.declaredMethods) {
                    if (m.parameterTypes.isEmpty()) {
                        m.isAccessible = true
                        val ret = m.returnType
                        if (ret == Boolean::class.java || ret == java.lang.Boolean.TYPE ||
                            ret == Int::class.java || ret == java.lang.Integer.TYPE) {
                            try {
                                val v = m.invoke(conv)
                                results.add("  ${c.simpleName}.${m.name}() = $v")
                            } catch (_: Exception) {}
                        }
                    }
                }
                c = c.superclass
            }
            steplog("Conversation flags probe (${results.size} values):\n${results.joinToString("\n")}")
        }.onFailure {
            steplog("probeConversationFlags failed: ${it.message}")
        }
    }

    fun setHidden(wxid: String, hidden: Boolean, classLoader: ClassLoader): Boolean {
        if (hideMethodP == null || convHideStore == null) {
            // 兜底懒解析（正常情况下 ensureInitialized 已解析）
            if (!resolveNativeHide(classLoader)) return false
        }
        return runCatching {
            val targetRef = if (hidden) HIDDEN_PARENT_REF else ""
            // 调用会话存储 m4 的原生批量隐藏方法：隐藏=hidden_conv_parent，显示=""
            hideMethodP!!.invoke(convHideStore, arrayOf(wxid), targetRef)
            // 回读校验：确认 DB parentRef 已被写入目标值（无回读句柄时视为成功，P 幂等）
            val ok = isHidden(wxid, classLoader)?.let { it == hidden } ?: true
            steplog("setHidden: $wxid hidden=$hidden targetRef='$targetRef' verify=$ok")
            ok
        }.getOrDefault(false)
    }

    fun isHidden(wxid: String, classLoader: ClassLoader): Boolean? {
        if (convModelGetter == null || modelParentRefField == null) {
            // 无回读句柄：返回 null 让调用方回退到「总是尝试设置」（P 幂等，无副作用）
            return null
        }
        return runCatching {
            val model = convModelGetter!!.invoke(convHideStore, wxid) ?: return null
            val ref = modelParentRefField!!.get(model) as? String
            ref == HIDDEN_PARENT_REF
        }.getOrNull()
    }

    private fun steplog(msg: String) {
        StealthLog.i("$TAG: $msg")
    }
}
