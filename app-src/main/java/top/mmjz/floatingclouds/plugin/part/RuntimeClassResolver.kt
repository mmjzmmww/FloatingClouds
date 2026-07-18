package top.mmjz.floatingclouds.plugin.part

import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.FeatureDiagnostics
import top.mmjz.floatingclouds.util.StealthLog

/**
 * 运行时类解析器：通过候选列表 + Class.forName + SP 缓存发现微信内部类。
 *
 * 微信版本变化 → 遍历候选类名 → Class.forName 尝试加载
 * 同版本 → SP 缓存命中，无需重复遍历
 *
 * 注意：本类不依赖任何第三方 DexKit 库，纯标准反射实现。
 */
object RuntimeClassResolver {

    @Volatile var isReady: Boolean = false; private set

    @Volatile var snsCommentDetailUICls: Class<*>? = null; private set
    @Volatile var commentListAdapterCls: Class<*>? = null; private set
    @Volatile var snsMsgAdapterCls: Class<*>? = null; private set
    @Volatile var snsMsgRelevanceAdapterCls: Class<*>? = null; private set
    @Volatile var snsTimeLineAdapterCls: Class<*>? = null; private set
    @Volatile var contactInfoUICls: Class<*>? = null; private set
    @Volatile var snsRecyclerAdapterCls: Class<*>? = null; private set
    @Volatile var conversationAdapterCls: Class<*>? = null; private set
    @Volatile var contactAdapterCls: Class<*>? = null; private set
    @Volatile var wxRecyclerAdapterCls: Class<*>? = null; private set
    @Volatile var mvvmListCls: Class<*>? = null; private set
    @Volatile var snsTimelineAdapterCls: Class<*>? = null; private set
    @Volatile var snsSelfAdapterCls: Class<*>? = null; private set
    @Volatile var snsAlbumAdapterCls: Class<*>? = null; private set

    @JvmStatic
    fun reset() {
        isReady = false
        snsCommentDetailUICls = null; commentListAdapterCls = null
        snsMsgAdapterCls = null; snsMsgRelevanceAdapterCls = null
        snsTimeLineAdapterCls = null; contactInfoUICls = null
        snsRecyclerAdapterCls = null; conversationAdapterCls = null
        contactAdapterCls = null; wxRecyclerAdapterCls = null; mvvmListCls = null
        snsTimelineAdapterCls = null; snsSelfAdapterCls = null; snsAlbumAdapterCls = null
        val editor = ConfigUtil.sp.edit().remove(CACHE_KEY_VERSION)
        for (e in entries) editor.remove(CACHE_PREFIX_CLS + e.key)
        editor.apply()
    }

    private const val CACHE_KEY_VERSION = "rcr_ver"
    private const val CACHE_PREFIX_CLS = "cls_cache_"

    private class Entry(
        val key: String,
        val candidates: List<String>,
        val assign: (Class<*>?) -> Unit,
        val getter: () -> Class<*>?
    )

    private val entries: List<Entry> = run {
        val m = mutableListOf<Entry>()
        m.add(Entry("sns_cdui", listOf("com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI"), { snsCommentDetailUICls = it }, { snsCommentDetailUICls }))
        m.add(Entry("sns_cla", listOf("com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI\$CommentListAdapter"), { commentListAdapterCls = it }, { commentListAdapterCls }))
        // 8.0.74: obfuscated adapter names changed; add new candidates
        m.add(Entry("sns_sma", listOf("com.tencent.mm.plugin.sns.ui.sl"), { snsMsgAdapterCls = it }, { snsMsgAdapterCls }))
        m.add(Entry("sns_smr", listOf("com.tencent.mm.plugin.sns.ui.im"), { snsMsgRelevanceAdapterCls = it }, { snsMsgRelevanceAdapterCls }))
        m.add(Entry("sns_stl", listOf("com.tencent.mm.plugin.sns.ui.jo", "com.tencent.mm.plugin.sns.ui.jn"), { snsTimeLineAdapterCls = it }, { snsTimeLineAdapterCls }))
        m.add(Entry("sns_cui", listOf("com.tencent.mm.plugin.profile.ui.ContactInfoUI"), { contactInfoUICls = it }, { contactInfoUICls }))
        m.add(Entry("sns_sra", listOf(
            // 8.0.74: a0, a1 confirmed by DEX scan
            "com.tencent.mm.plugin.sns.ui.improve.component.a0",
            "com.tencent.mm.plugin.sns.ui.improve.component.a1",
            // legacy
            "com.tencent.mm.plugin.sns.ui.improve.component.g2",
            "com.tencent.mm.plugin.sns.ui.improve.component.t2"
        ), { snsRecyclerAdapterCls = it }, { snsRecyclerAdapterCls }))
        m.add(Entry("conv_ad", listOf(
            // 8.0.74: MVVM architecture, MvvmConvList as adapter
            "com.tencent.mm.ui.conversation.adapter.MvvmConvList",
            // legacy obfuscated names
            "com.tencent.mm.ui.conversation.p3","com.tencent.mm.ui.conversation.x","com.tencent.mm.ui.conversation.r"
        ), { conversationAdapterCls = it }, { conversationAdapterCls }))
        m.add(Entry("ct_ad", listOf(
            // 8.0.74: ui3.t0 -> ui3.a or ui3.b
            "ui3.a", "ui3.b",
            // legacy
            "com.tencent.mm.ui.contact.e",
            "com.tencent.mm.ui.contact.ui3.t0"
        ), { contactAdapterCls = it }, { contactAdapterCls }))
        m.add(Entry("wx_rv", listOf("com.tencent.mm.view.recyclerview.WxRecyclerAdapter"), { wxRecyclerAdapterCls = it }, { wxRecyclerAdapterCls }))
        // 8.0.74 朋友圈相关 adapter（SNS 隐藏功能共用，禁止在 Part 内硬编码候选）
        m.add(Entry("sns_tla", listOf("com.tencent.mm.plugin.sns.ui.improve.component.h2"), { snsTimelineAdapterCls = it }, { snsTimelineAdapterCls }))
        m.add(Entry("sns_sa", listOf("com.tencent.mm.plugin.sns.ui.SnsSelfAdapter"), { snsSelfAdapterCls = it }, { snsSelfAdapterCls }))
        m.add(Entry("sns_aa", listOf("com.tencent.mm.plugin.sns.ui.album.SnsAlbumAdapter"), { snsAlbumAdapterCls = it }, { snsAlbumAdapterCls }))
        m.add(Entry("mvvml", listOf("com.tencent.mm.plugin.mvvmlist.MvvmList"), { mvvmListCls = it }, { mvvmListCls }))
        m
    }

    /**
     * 诊断模式：扫描微信所有DEX中的类名，打印匹配关键模式的类。
     * 用于新版本适配时快速发现混淆类名变化。
     */
    fun diagnoseDexClasses(apkPath: String?) {
        var path = apkPath
        if (path == null) {
            try {
                val ctx = top.mmjz.floatingclouds.util.AppContext.context
                val ai = ctx?.packageManager?.getApplicationInfo("com.tencent.mm", 0)
                path = ai?.sourceDir
            } catch (_: Exception) {}
        }
        if (path == null) {
            StealthLog.i("[RCR-DIAG] apkPath is null, skip")
            return
        }
        StealthLog.i("[RCR-DIAG] === START scanning: $path ===")
        try {
            // Try multiple ways to create DexFile (API varies by Android version)
            var dexFile: Any? = null
            try {
                // Way 1: DexFile(File)
                val dfClass = Class.forName("dalvik.system.DexFile")
                val ctor = dfClass.getDeclaredConstructor(java.io.File::class.java)
                ctor.isAccessible = true
                dexFile = ctor.newInstance(java.io.File(path))
            } catch (_: Exception) {}
            if (dexFile == null) {
                try {
                    // Way 2: DexFile(String)
                    val dfClass = Class.forName("dalvik.system.DexFile")
                    val ctor = dfClass.getDeclaredConstructor(String::class.java)
                    ctor.isAccessible = true
                    dexFile = ctor.newInstance(path)
                } catch (_: Exception) {}
            }
            if (dexFile == null) {
                // Way 3: DexFile.loadDex
                try {
                    val dfClass = Class.forName("dalvik.system.DexFile")
                    val loadMethod = dfClass.getDeclaredMethod("loadDex", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                    loadMethod.isAccessible = true
                    dexFile = loadMethod.invoke(null, path, null, 0)
                } catch (_: Exception) {}
            }
            if (dexFile == null) {
                StealthLog.i("[RCR-DIAG] All DexFile constructors failed")
                return
            }
            
            val dfClass = dexFile.javaClass
            val entriesMethod = dfClass.getDeclaredMethod("entries")
            entriesMethod.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val entries = entriesMethod.invoke(dexFile) as java.util.Enumeration<String>
            
            // 关注的包名模式
            val patterns = listOf(
                "ui3." to "CONTACT/地址UI包",
                "address" to "通讯录地址",
                "sns.ui.improve" to "朋友圈改进组件",
                "sns.ui." to "朋友圈UI",
                "contact" to "联系人",
                "Mvvm" to "MVVM",
                "conversation" to "会话列表",
                "o74." to "旧timeline适配器",
                "v84." to "SNS Info类",
                "vh5." to "ViewHolder",
                "x84." to "列表项",
                "k3" to "菜单分发",
                "sc3." to "会话隐藏",
                "va5." to "适配器",
                "qa5." to "数据模型",
            )
            
            val matched = mutableMapOf<String, MutableList<String>>()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                for ((pattern, desc) in patterns) {
                    if (className.contains(pattern, ignoreCase = true)) {
                        matched.getOrPut("$desc ($pattern)") { mutableListOf() }.add(className)
                    }
                }
            }
            
            StealthLog.i("[RCR-DIAG] === Found ${matched.values.flatten().size} matching classes ===")
            for ((key, classes) in matched) {
                StealthLog.i("[RCR-DIAG] [$key]: ${classes.joinToString(", ")}")
            }
            StealthLog.i("[RCR-DIAG] === END ===")
        } catch (e: Exception) {
            StealthLog.e("[RCR-DIAG] FAILED", e)
        }
    }

    fun init(apkPath: String?, classLoader: ClassLoader, hostVersion: String?) {
        if (isReady) return
        val sp = ConfigUtil.sp
        val cachedVersion = sp.getString(CACHE_KEY_VERSION, null)

        // 分支 1: SP 缓存命中
        if (cachedVersion != null && cachedVersion == hostVersion) {
            var allOk = true; var cachedHit = 0; var cachedMiss = 0
            for (e in entries) {
                val name = sp.getString(CACHE_PREFIX_CLS + e.key, null)
                if (name != null) {
                    try {
                        e.assign(Class.forName(name, false, classLoader))
                        cachedHit++
                        FeatureDiagnostics.reportClassResolved(e.key, true, name)
                    } catch (_: Exception) {
                        allOk = false; cachedMiss++
                        FeatureDiagnostics.reportClassResolved(e.key, false, name)
                        break
                    }
                } else { allOk = false; break }
            }
            if (allOk) {
                checkReady()
                FeatureDiagnostics.reportRCR(isReady = true, cachedCount = cachedHit, fallbackCount = 0, totalCount = entries.size, diagInfo = "SP hit $hostVersion")
                StealthLog.i("[RuntimeClassResolver] cached (SP, $hostVersion)")
                return
            }
        }

        // 分支 2: 候选列表 Class.forName + 写入 SP
        val editor = sp.edit()
        editor.putString(CACHE_KEY_VERSION, hostVersion)
        var fallbackCount = 0; var foundCount = 0
        for (e in entries) {
            if (e.getter() != null) continue
            var found = false
            for (name in e.candidates) {
                try {
                    e.assign(Class.forName(name, false, classLoader))
                    found = true; foundCount++
                    FeatureDiagnostics.reportClassResolved(e.key, true, name)
                    break
                } catch (_: Exception) {}
            }
            if (!found) {
                fallbackCount++
                FeatureDiagnostics.reportClassResolved(e.key, false, e.candidates.firstOrNull() ?: "unknown")
            }
            val c = e.getter()
            if (c != null) editor.putString(CACHE_PREFIX_CLS + e.key, c.name)
        }
        editor.apply()
        checkReady()
        FeatureDiagnostics.reportRCR(isReady = isReady, cachedCount = foundCount, fallbackCount = fallbackCount, totalCount = entries.size, diagInfo = hostVersion)
        StealthLog.i("[RuntimeClassResolver] ready ($hostVersion) found=$foundCount miss=$fallbackCount")
    }

    private fun checkReady() {
        isReady = snsCommentDetailUICls != null && commentListAdapterCls != null
            && snsMsgAdapterCls != null && conversationAdapterCls != null
            && wxRecyclerAdapterCls != null
    }
}
