package top.mmjz.floatingclouds

import top.mmjz.floatingclouds.ClazzN
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.DexKitCache
import top.mmjz.floatingclouds.util.StealthLog
import java.lang.reflect.Modifier

/**
 * 混淆类名解析门面：DexKit 主路径 → 硬编码 FALLBACK 兜底 → null。
 *
 * 设计目标：让会话存储/静音/隐藏/Flutter通话/VoIP 类优先走 DexKit 扫描结果，
 * 仅在缓存缺失或校验失败时回退到 ClazzN.FALLBACK 硬编码链。
 *
 * 关键：校验门（validate*）确保 DexKit 候选类在运行时反射链中确实可用，
 * 从而与当前 8.0.74 硬编码行为逐字节等价；任何偏差都会回退硬编码，无功能变化。
 * 因此本类在缓存为空（未扫描 / 首次启动）时，解析结果与原硬编码链完全一致。
 *
 * 红线：硬编码 FALLBACK 常量与候选链全部保留，仅调整"先用谁"的顺序。
 */
object ObfuscatedClassResolver {

    private const val TAG = "ObfResolv"

    /**
     * 通用解析：优先尝试 DexKit 候选（经校验门），失败回退硬编码。
     * dexCandidates 为空或全校验失败时，行为与 fallback() 完全一致。
     */
    private fun resolve(
        cl: ClassLoader,
        dexCandidates: List<String>,
        validate: (Class<*>) -> Boolean,
        fallback: () -> Class<*>?
    ): Class<*>? {
        for (cand in dexCandidates) {
            val cls = AppReflect.findClassIfExists(cand, cl) ?: continue
            if (validate(cls)) {
                StealthLog.i("$TAG: DexKit 命中 $cand")
                return cls
            }
        }
        return fallback()
    }

    /** 校验：类存在静态方法 [name] 且恰好 1 个参数（会话存储/辅助类的入口特征） */
    private fun hasStaticMethodWith1Param(cls: Class<*>, name: String): Boolean =
        cls.declaredMethods.any {
            it.name == name && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
        }

    // ═══════════════════════════════════════════
    // ① 会话静音 x3 系列
    // ═════════════════════════════════════════

    fun resolveMuteX3(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getConvStorageClassCandidates(),
        { hasStaticMethodWith1Param(it, "s") }
    ) {
        AppReflect.findClassIfExists(ClazzN.MUTE_X3_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_GE3_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_SC3_X3_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_SC3_X_FALLBACK, cl)
    }

    // ① 会话静音 yj0 系列
    fun resolveMuteYj0(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getConvStorageHelperClassCandidates(),
        { hasStaticMethodWith1Param(it, "s") }
    ) {
        AppReflect.findClassIfExists(ClazzN.MUTE_YJ0_J1_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_YJ0_H1_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_YJ0_I1_FALLBACK, cl)
    }

    // ═══════════════════════════════════════════
    // ④ 会话隐藏 x3（与 ① 同解析；fallback 含 storage.e4/l4 候选）
    // ═════════════════════════════════════════

    fun resolveConvStorage(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getConvStorageClassCandidates(),
        { hasStaticMethodWith1Param(it, "s") }
    ) {
        AppReflect.findClassIfExists("com.tencent.mm.storage.e4", cl)
            ?: AppReflect.findClassIfExists("com.tencent.mm.storage.l4", cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_X3_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_GE3_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_SC3_X3_FALLBACK, cl)
            ?: AppReflect.findClassIfExists(ClazzN.MUTE_SC3_X_FALLBACK, cl)
            ?: AppReflect.findClassIfExists("sc3.x0", cl)
    }

    // ④ 会话隐藏 yj0 helper
    fun resolveConvStorageHelper(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getConvStorageHelperClassCandidates(),
        { hasStaticMethodWith1Param(it, "s") }
    ) {
        listOf(
            "yj0.j1", "yj0.h1", "yj0.i1", "yj0.g1", "yj0.f1",
            "yj0.e1", "yj0.d1", "yj0.c1", "yj0.b1", "yj0.a1",
            "yk0.j1", "yk0.h1", "yk0.i1", "yj0.k1", "yj0.l1",
            "zh0.j1", "zg0.j1", "zf0.j1"
        ).firstNotNullOfOrNull { AppReflect.findClassIfExists(it, cl) }
    }

    // ═══════════════════════════════════════════
    // ④ 会话隐藏：storage + helper 配对解析（原子，杜绝错配）
    // ═════════════════════════════════════════

    /**
     * 会话存储/隐藏解析结果。
     * @param storageInterface 微信 storage 定位器 s() 接受的「存储接口」(如 vg3.x3)，
     *                          8.0.74 起 s() 按接口（而非实现类）定位，传实现类 g9 会返回 null。
     * @param helper           storage 定位器宿主类（8.0.74 = gm0.j1，s(Class):lm0.a）。
     * @param convGetter       从 h2(messenger 基础服务) 取「会话存储」的 getter 方法名
     *                          （8.0.74 = Ai/Bi，返回 k4；旧版为 Tg()）。
     */
    data class ConvStoragePair(
        val storageInterface: Class<*>,
        val helper: Class<*>,
        val convGetter: String
    )

    /**
     * hook 捕获到的真实 storage 接口集合（微信启动期 s() 实际调用过的接口，最可靠）。
     * 由 WXMaskPlugin 在 hook gm0.j1.s(Class) 时写入；resolveConvStoragePair 第 1 步优先使用。
     */
    @Volatile
    private var capturedStorageInterfaces: MutableSet<Class<*>> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap(8)
    )

    /** hook 回调：记录微信真实调用的 storage 接口，供解析优先尝试 */
    fun captureStorageClass(c: Class<*>) {
        capturedStorageInterfaces.add(c)
    }

    /**
     * 8.0.74 实测确认：gm0.j1.s(interface) 返回 messenger.foundation.h2，
     * 其 getter(Ai/Bi) 返回会话存储 k4，k4.n(wxid,true) 返回会话模型 z3。
     * 旧版本（e4/yj0.j1）沿用 Tg() 这一 getter 名，靠运行期探测自动适配。
     *
     * 注意：DexKit 对该家族扫描串过宽（返回 80+ 噪声类），主路径不可靠，
     * 故以「接口 + 运行期探测」为主路径，DexKit 候选仅作附加尝试。
     */
    private val CONV_STORAGE_IFACES: List<String> = listOf(
        "vg3.x3", "c25.e", "rv1.f", "i35.g", "zq1.a0", "pz2.a", "e42.k0",
        // 旧版本兜底（极低版本兼容，接口形态不同，运行期探测会回退 Tg）
        "yj0.j1", "tk0.j1", "dl0.k1"
    )

    /**
     * 解析 (storageInterface, helper, convGetter) 三元组：
     * 1) 优先 hook 捕获到的真实接口（微信启动期 s() 实际调用过的接口，最可靠）；
     * 2) 再尝试硬编码接口候选列表（含旧版本）；
     * 3) 最后 DexKit 候选（附加尝试）。
     * 每个候选都运行期探测：s(iface) 非空 → 其 getter 返回带 n(String,boolean) 的会话存储。
     */
    fun resolveConvStoragePair(cl: ClassLoader): ConvStoragePair? {
        // 1) hook 捕获到的真实接口（优先）
        for (iface in capturedStorageInterfaces) {
            resolveViaInterface(cl, iface)?.let { return it }
        }
        // 2) 硬编码接口候选列表（含旧版本兜底）
        for (iname in CONV_STORAGE_IFACES) {
            val iCls = AppReflect.findClassIfExists(iname, cl) ?: continue
            resolveViaInterface(cl, iCls)?.let {
                StealthLog.i("$TAG: 接口候选命中 $iname")
                return it
            }
        }
        // 3) DexKit 候选（附加尝试）
        val storageCands = DexKitCache.getConvStorageClassCandidates()
        val helperCands = DexKitCache.getConvStorageHelperClassCandidates()
        for (sc in storageCands) {
            val iCls = AppReflect.findClassIfExists(sc, cl) ?: continue
            for (hc in helperCands) {
                val hCls = AppReflect.findClassIfExists(hc, cl) ?: continue
                resolveViaHelperOnly(cl, hCls, iCls)?.let { return it }
            }
        }
        return null
    }

    /**
     * 对给定 storage 接口，找 helper(gm0.j1 或该接口对应定位器) 并探测会话存储 getter。
     * 返回首个探测成功的三元组。
     */
    private fun resolveViaInterface(cl: ClassLoader, iface: Class<*>): ConvStoragePair? {
        // 优先 gm0.j1 作为定位器；若不存在则回退到该接口所在包的同名 j1（尽力兼容）
        val helperNames = listOf("gm0.j1")
        for (hName in helperNames) {
            val hCls = AppReflect.findClassIfExists(hName, cl) ?: continue
            resolveViaHelperOnly(cl, hCls, iface)?.let { return it }
        }
        return null
    }

    /**
     * 用指定 helper + storage 接口，运行期探测：
     *   h2 = helper.s(iface) 非空
     *   在 h2 上找 getter 返回带 n(String,boolean) 的子存储（会话存储）
     * 命中则返回三元组，否则 null。
     */
    private fun resolveViaHelperOnly(cl: ClassLoader, helperCls: Class<*>, iface: Class<*>): ConvStoragePair? = runCatching {
        val sMeths = helperCls.declaredMethods.filter {
            it.name == "s" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
        }
        val h2 = sMeths.firstNotNullOfOrNull { m ->
            m.isAccessible = true
            runCatching { m.invoke(null, iface) }.getOrNull()
        } ?: return@runCatching null
        // 探测 h2 上所有「返回带 n(String,boolean) 子存储」的 getter。
        // ★ 关键修复：联系人存储(rcontact)与会话存储(rconversation)都带 n(String,boolean)，
        //   若只取第一个会误选联系人存储 → parentRef 隐藏永远失效。
        //   因此改为：先收集全部候选，再用样本对象「表名/字段」判据优先选真正的会话存储。
        val candidates = mutableListOf<Pair<java.lang.reflect.Method, Any>>()
        for (gm in h2.javaClass.declaredMethods.filter {
            it.parameterTypes.isEmpty() && it.returnType != Void.TYPE && !it.returnType.isPrimitive
        }) {
            gm.isAccessible = true
            val sub = runCatching { gm.invoke(h2) }.getOrNull() ?: continue
            val hasN = sub.javaClass.declaredMethods.any {
                it.name == "n" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (hasN) candidates.add(gm to sub)
        }
        // 第一轮：优先返回样本对象为「会话对象」(rconversation / 含 field_parentRef) 的 getter
        for ((gm, sub) in candidates) {
            val tn = sampleTableName(sub)
            StealthLog.i("$TAG: 候选 getter=${gm.name} convStore=${sub.javaClass.name} sampleTable=$tn")
            if (isConversationStore(sub)) {
                StealthLog.i("$TAG: 解析命中(已验证rconversation) helper=${helperCls.name} iface=${iface.name} getter=${gm.name}")
                return@runCatching ConvStoragePair(iface, helperCls, gm.name)
            }
        }
        // 第二轮兜底：没有验证通过的会话存储 → 回退第一个 hasN（旧行为），但告警提示可能误选联系人存储
        candidates.firstOrNull()?.let { (gm, sub) ->
            StealthLog.w("$TAG: WARN 未验证到 rconversation 存储，回退首个 getter=${gm.name} table=${sampleTableName(sub)} (可能误选联系人存储)")
            return@runCatching ConvStoragePair(iface, helperCls, gm.name)
        }
        // 兼容旧版：h2 自身即会话存储（带 n(String,boolean)），getter 名为 Tg
        val selfHasN = h2.javaClass.declaredMethods.any {
            it.name == "n" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (selfHasN) {
            StealthLog.i("$TAG: 解析命中(旧链) helper=${helperCls.name} iface=${iface.name} getter=Tg")
            return@runCatching ConvStoragePair(iface, helperCls, "Tg")
        }
        null
    }.getOrNull()

    /** 用 filehelper 样本判定子存储是否为「会话存储」(rconversation / 含 field_parentRef)。 */
    private fun isConversationStore(sub: Any): Boolean = runCatching {
        val conv = sampleConvObject(sub) ?: return false
        // 判据1：getTableName()=='rconversation'
        val tn = tableNameOf(conv)
        if (tn != null) return tn.equals("rconversation", ignoreCase = true)
        // 判据2：字段层级含 field_parentRef（明文会话字段）
        var c: Class<*>? = conv.javaClass
        while (c != null) {
            if (c.declaredFields.any { it.name == "field_parentRef" }) return true
            c = c.superclass
        }
        false
    }.getOrDefault(false)

    /** 取子存储 n("filehelper",true) 返回对象的表名（诊断用）。 */
    private fun sampleTableName(sub: Any): String? = runCatching {
        sampleConvObject(sub)?.let { tableNameOf(it) }
    }.getOrNull()

    private fun sampleConvObject(sub: Any): Any? = runCatching {
        val nm = sub.javaClass.declaredMethods.firstOrNull {
            it.name == "n" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return null
        nm.isAccessible = true
        nm.invoke(sub, "filehelper", true)
    }.getOrNull()

    private fun tableNameOf(obj: Any): String? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val gm = c.declaredMethods.firstOrNull {
                it.name == "getTableName" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }
            if (gm != null) { gm.isAccessible = true; return (gm.invoke(obj) as? String) }
            c = c.superclass
        }
        null
    }.getOrNull()

    /**
     * 调用 storage 定位器 helper.s(storageInterface)，返回 h2（messenger 基础服务）。
     * 8.0.74 起 s() 接受「存储接口」(vg3.x3)，签名 s(java.lang.Class):lm0.a。
     * 兼容多种 s 重载：先试接口 Class，再试接口实例。
     */
    fun invokeStorageLocator(helperCls: Class<*>, storageInterface: Class<*>): Any? {
        val sMethods = helperCls.declaredMethods.filter {
            it.name == "s" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
        }
        for (m in sMethods) {
            m.isAccessible = true
            runCatching { m.invoke(null, storageInterface) }.getOrNull()?.let { return it }
            runCatching { storageInterface.getDeclaredConstructor().newInstance() }.getOrNull()?.let { inst ->
                runCatching { m.invoke(null, inst) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * 从 h2 取会话存储对象：调用 convGetter 对应的 getter。
     * 8.0.74 = Ai/Bi；旧版 = Tg。
     */
    fun resolveConvStorage(h2: Any, convGetter: String): Any? =
        AppReflect.callMethod(h2, convGetter)

    // ═══════════════════════════════════════════
    // ② Flutter 通话入口
    // ═════════════════════════════════════════

    fun resolveFlutterVoip(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getFlutterVoipClassCandidates(),
        { cls -> cls.declaredMethods.any { m -> (m.name == "a" || m.name == "b") && m.parameterTypes.size >= 1 } }
    ) {
        AppReflect.findClassIfExists(ClazzN.FLUTTER_VOIP_FALLBACK, cl)
            ?: AppReflect.findClassIfExists("com.tencent.mm.plugin.voip_cs.flutter.d", cl)
    }

    // ═══════════════════════════════════════════
    // ③ VoipMgr
    // ═════════════════════════════════════════

    fun resolveVoipMgrClass(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getVoipMgrClassCandidates(),
        { cls ->
            cls.declaredMethods.any { m ->
                m.returnType == Void.TYPE && m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[3] == Long::class.javaPrimitiveType
            }
        }
    ) {
        listOf(
            "com.tencent.mm.plugin.voip.model.b2",
            "com.tencent.mm.plugin.voip.model.h2",
            "com.tencent.mm.plugin.voip.model.NewVoipMgr"
        ).firstNotNullOfOrNull { AppReflect.findClassIfExists(it, cl) }
    }

    // ═══════════════════════════════════════════
    // ③ IncomingCallMgr
    // ═════════════════════════════════════════

    fun resolveIncomingCallMgrClass(cl: ClassLoader): Class<*>? = resolve(
        cl,
        DexKitCache.getIncomingCallMgrClassCandidates(),
        { cls ->
            cls.declaredMethods.any { m ->
                m.name == "a" && m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.size == 1
            }
        }
    ) {
        listOf(
            "com.tencent.mm.plugin.voip.model.NewVoipIncomingCallManager",
            "com.tencent.mm.plugin.voip.model.VoipIncomingCallManager",
            "com.tencent.mm.plugin.voip.model.n",
            "com.tencent.mm.plugin.voip.model.e0",
            "com.tencent.mm.plugin.voip.model.c0"
        ).firstNotNullOfOrNull { AppReflect.findClassIfExists(it, cl) }
    }
}
