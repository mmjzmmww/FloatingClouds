package top.mmjz.floatingclouds.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * DexKit 字节码扫描器 —— 运行时发现微信 DEX 中的实际类名/方法名。
 */
class DexKitScanner(private val context: Context, private val apkPath: String) {

    companion object {
        const val SP_NAME = "dexkit_scan_cache"
        const val KEY_CONV_MVVM = "conversation_mvvm_list_v3"
        const val KEY_QUICK_MENU = "quick_add_menu_v10"
        const val KEY_CONTACT = "contact_mvvm_address_v2"
        const val KEY_SEARCH = "search_command_v2"
        const val KEY_ANTI_REVOKE = "anti_revoke_v2"
        const val KEY_RECENT_FORWARD = "recent_forward_v2"
        // ══ T5: 混淆类名 DexKit 主路径候选键 ══
        const val KEY_CONV_STORAGE = "conv_storage_class_v1"
        const val KEY_CONV_STORAGE_HELPER = "conv_storage_helper_v1"
        const val KEY_FLUTTER_VOIP = "flutter_voip_class_v1"
        const val KEY_VOIP_MGR = "voip_mgr_class_v1"
        const val KEY_INCOMING_CALL_MGR = "incoming_call_mgr_v1"
        const val KEY_SCAN_STATUS = "scan_status"
        const val KEY_LAST_VERSION = "last_version_code"

        val WECHAT_PKGS = arrayOf("com.tencent.mm", "va5", "kc5", "sd5", "yf5")

        @Volatile var isScanning = false; private set
        @Volatile var lastError: String? = null; private set
    }

    private val sp: SharedPreferences = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    data class ScanTask(
        val name: String, val key: String, val weight: Int,
        val execute: () -> JSONObject?
    )

    val tasks: List<ScanTask> = listOf(
        ScanTask("会话MVVM列表", KEY_CONV_MVVM, 25) { scanConvMvvm() },
        ScanTask("快捷加入菜单", KEY_QUICK_MENU, 20) { scanQuickMenu() },
        ScanTask("通讯录列表", KEY_CONTACT, 15) { scanContact() },
        ScanTask("搜索框", KEY_SEARCH, 15) { scanSearch() },
        ScanTask("防撤回", KEY_ANTI_REVOKE, 10) { scanAntiRevoke() },
        ScanTask("转发列表", KEY_RECENT_FORWARD, 15) { scanRecentForward() },
        // ══ T5: 混淆类名主路径扫描（尾部追加，不改动现有 6 任务）══
        ScanTask("会话存储类", KEY_CONV_STORAGE, 8) { scanConvStorage() },
        ScanTask("存储辅助类", KEY_CONV_STORAGE_HELPER, 8) { scanConvStorageHelper() },
        ScanTask("Flutter通话类", KEY_FLUTTER_VOIP, 6) { scanFlutterVoip() },
        ScanTask("VoipMgr", KEY_VOIP_MGR, 6) { scanVoipMgr() },
        ScanTask("IncomingCallMgr", KEY_INCOMING_CALL_MGR, 6) { scanIncomingCallMgr() }
    )
    val totalWeight: Int = tasks.sumOf { it.weight }

    fun isCached(): Boolean = sp.contains(KEY_CONV_MVVM) && sp.contains(KEY_QUICK_MENU)
    fun getLastVersionCode(): Int = sp.getInt(KEY_LAST_VERSION, 0)
    fun getLastScanTime(): Long = sp.getLong(KEY_SCAN_STATUS, 0)
    fun clearCache() { sp.edit().clear().apply() }

    fun scanAll(onProgress: (percent: Int, taskName: String) -> Unit): Boolean {
        if (isScanning) { lastError = "扫描已在运行中"; return false }
        isScanning = true; lastError = null
        var completed = 0

        try {
            onProgress(5, "初始化 DexKit...")
            val bridgeOk = initDexKit()
            AppLog.i("DexKit: bridge=${if (bridgeOk) "OK" else "NULL (fallback to cache)"}")
            FeatureDiagnostics.reportDexKitScan("__bridge__", if (bridgeOk) "OK" else "SKIP")

            for (task in tasks) {
                onProgress(completed * 100 / totalWeight, task.name)
                try {
                    val result = task.execute()
                    if (result != null) {
                        saveResult(task.key, result)
                        FeatureDiagnostics.reportDexKitScan(task.name, "OK",
                            "keys=${result.keys().asSequence().joinToString(",")}")
                    } else {
                        FeatureDiagnostics.reportDexKitScan(task.name,
                            if (bridgeOk) "FAIL" else "SKIP", "no result")
                    }
                } catch (e: Exception) {
                    AppLog.w("DexKit: ${task.name} scan error", e)
                    FeatureDiagnostics.reportDexKitScan(task.name, "FAIL", e.message ?: "unknown")
                }
                completed += task.weight
                onProgress(completed * 100 / totalWeight, task.name + " 完成")
            }

            try {
                val vc = context.packageManager.getPackageInfo("com.tencent.mm", 0).versionCode
                sp.edit().putInt(KEY_LAST_VERSION, vc)
                    .putLong(KEY_SCAN_STATUS, System.currentTimeMillis()).apply()
            } catch (_: Exception) {}

            FeatureDiagnostics.reportDexKitScanDone(true, null)
            isScanning = false; return true
        } catch (e: Exception) {
            lastError = "扫描异常: ${e.message}"
            AppLog.e("DexKit: scanAll failed", e)
            FeatureDiagnostics.reportDexKitScanDone(false, e.message)
            isScanning = false; return false
        }
    }

    private fun initDexKit(): Boolean {
        try { System.loadLibrary("dexkit") } catch (_: Throwable) {
            try { System.loadLibrary("dexkit") } catch (_: Throwable) { return false }
        }
        return DkBridge.init(apkPath)
    }

    // ═══════════════════════════════════════
    // 6 个扫描模块
    // ═══════════════════════════════════════

    private fun scanConvMvvm(): JSONObject? {
        if (!DkBridge.isReady) return null
        val names = HashMap<String, MutableList<String>>()
        fun add(key: String, value: String) { names.getOrPut(key) { mutableListOf() }.add(value) }

        val mvvmClasses = DkBridge.findClasses(WECHAT_PKGS, "Mvvm") +
            DkBridge.findClasses(WECHAT_PKGS, "ConversationList") +
            DkBridge.findClasses(arrayOf("com.tencent.mm.ui.conversation"), "Conversation")
        if (mvvmClasses.isEmpty()) { AppLog.w("DexKit: scanConvMvvm found 0"); return null }
        for (cls in mvvmClasses.distinct()) {
            val l = cls.lowercase()
            when {
                l.contains("adapter") || l.contains("convlist") || l.contains("mvvmconv") ->
                    add("adapterClassNames", cls)
                l.contains("datasource") || l.contains("repository") || l.contains("loader") ->
                    add("dataSourceClassNames", cls)
                l.contains("item") && !l.contains("adapter") -> add("itemClassNames", cls)
                l.contains("holder") -> add("holderClassNames", cls)
                l.contains("request") || l.contains("query") -> add("requestClassNames", cls)
                l.contains("storage") || l.contains("store") || l.contains("cache") ->
                    add("storageClassNames", cls)
            }
        }
        if (names.isEmpty()) return null
        val json = JSONObject()
        names.forEach { (k, v) -> json.put(k, JSONArray(v)) }
        AppLog.i("DexKit: scanConvMvvm → ${names.map { "${it.key}=${it.value.size}" }}")
        return json
    }

    private fun scanQuickMenu(): JSONObject? {
        if (!DkBridge.isReady) return null
        val names = HashMap<String, MutableList<String>>()
        fun add(key: String, value: String) { names.getOrPut(key) { mutableListOf() }.add(value) }

        for (c in DkBridge.findClasses(WECHAT_PKGS, "LongClick") +
            DkBridge.findClasses(WECHAT_PKGS, "ConversationMenu"))
            add("conversationMenuCallbackClassNames", c)
        for (c in DkBridge.findClasses(WECHAT_PKGS, "ConversationLongClick"))
            add("conversationLongClickClassNames", c)
        for (c in (DkBridge.findClasses(arrayOf("com.tencent.mm.ui.widget"), "Popup") +
            DkBridge.findClasses(WECHAT_PKGS, "MMMenu")).distinct())
            add("popupClassNames", c)
        for (c in DkBridge.findClasses(arrayOf("com.tencent.mm.ui.contact"), "Callback"))
            add("contactMenuCallbackClassNames", c)

        if (names.isEmpty()) return null
        val json = JSONObject()
        names.forEach { (k, v) -> json.put(k, JSONArray(v)) }
        AppLog.i("DexKit: scanQuickMenu → ${names.map { "${it.key}=${it.value.size}" }}")
        return json
    }

    private fun scanContact(): JSONObject? {
        if (!DkBridge.isReady) return null
        val candidates = listOf("MvvmContactListUI", "ContactList", "SelectContact",
            "AddressLiveList", "ContactInfoUI", "AddressUI")
        val pkgs = arrayOf("com.tencent.mm.ui.contact", "com.tencent.mm.ui.mvvm",
            "com.tencent.mm.ui", "com.tencent.mm.plugin.profile.ui")
        val found = LinkedHashSet<String>()
        for (c in candidates) found.addAll(DkBridge.findClasses(pkgs, c))
        found.addAll(DkBridge.findClasses(pkgs, "Address"))
        found.addAll(DkBridge.findClasses(pkgs, "ContactAdapter"))
        found.addAll(DkBridge.findClasses(pkgs, "ContactLabel"))
        if (found.isEmpty()) return null
        val json = JSONObject().put("classNames", JSONArray(found.toList()))
        AppLog.i("DexKit: scanContact → ${found.size} classes")
        return json
    }

    private fun scanSearch(): JSONObject? {
        if (!DkBridge.isReady) return null
        val pkgs = arrayOf("com.tencent.mm.ui.tools", "com.tencent.mm.ui", "com.tencent.mm.plugin.fts.ui")
        val all = (DkBridge.findClasses(pkgs, "ActionBarSearchView") +
            DkBridge.findClasses(pkgs, "SearchView") +
            DkBridge.findClasses(pkgs, "FTSSearchView")).distinct()
        if (all.isEmpty()) return null
        val json = JSONObject().put("searchViewClassNames", JSONArray(all))
        AppLog.i("DexKit: scanSearch → ${all.size} views")
        return json
    }

    private fun scanAntiRevoke(): JSONObject? {
        if (!DkBridge.isReady) return null
        val sigs = listOf(
            "(Ljava/lang/String;J" +
                "Lcom/tencent/mm/modelbase/p0;" +
                "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            "(Ljava/lang/String;J" +
                "Lcom/tencent/mm/modelbase/p0;" +
                "Ljava/lang/String;Ljava/lang/String;)V"
        )
        for (sig in sigs) {
            for ((cls, mtd, msig) in DkBridge.findMethodsBySignature(sig)) {
                if ((cls + mtd).lowercase().contains("revoke")) {
                    val json = JSONObject().put("className", cls).put("methodName", mtd).put("methodSign", msig)
                    AppLog.i("DexKit: scanAntiRevoke → $cls.$mtd")
                    return json
                }
            }
        }
        return null
    }

    private fun scanRecentForward(): JSONObject? {
        if (!DkBridge.isReady) return null
        val pkgs = arrayOf("com.tencent.mm.ui.transmit", "com.tencent.mm.ui.contact",
            "com.tencent.mm.pluginsdk.forward", "com.tencent.mm.ui")
        val results = DkBridge.findClasses(pkgs, "Forward") +
            DkBridge.findClasses(pkgs, "Transmit") +
            DkBridge.findClasses(pkgs, "RecentForward") +
            DkBridge.findClasses(pkgs, "SelectContact")
        val filtered = results.distinct().filter {
            val l = it.lowercase(); l.contains("forward") || l.contains("transmit") || l.contains("recent")
        }
        if (filtered.isEmpty()) return null
        val json = JSONObject().put("activityClassNames", JSONArray(filtered))
        AppLog.i("DexKit: scanRecentForward → ${filtered.size} classes")
        return json
    }

    // ═══════════════════════════════════════
    // T5: 5 个混淆类名主路径扫描（仅广撒网缩小候选，校验由 ObfuscatedClassResolver 负责）
    // ═══════════════════════════════════════

    private fun scanConvStorage(): JSONObject? {
        if (!DkBridge.isReady) return null
        val candidates = DkBridge.findClasses(
            arrayOf("com.tencent.mm.storage", "com.tencent.mm.plugin.messenger.foundation", "com.tencent.mm"),
            "rconversation"
        )
        if (candidates.isEmpty()) return null
        val json = JSONObject().put("classNames", JSONArray(candidates.distinct()))
        AppLog.i("DexKit: scanConvStorage → ${candidates.size} classes")
        return json
    }

    private fun scanConvStorageHelper(): JSONObject? {
        if (!DkBridge.isReady) return null
        val candidates = DkBridge.findClasses(
            arrayOf("com.tencent.mm.storage", "com.tencent.mm"),
            "conversation"
        )
        if (candidates.isEmpty()) return null
        val json = JSONObject().put("classNames", JSONArray(candidates.distinct()))
        AppLog.i("DexKit: scanConvStorageHelper → ${candidates.size} classes")
        return json
    }

    private fun scanFlutterVoip(): JSONObject? {
        if (!DkBridge.isReady) return null
        val candidates = DkBridge.findClasses(
            arrayOf("com.tencent.mm.plugin.voip_cs.flutter", "com.tencent.mm"),
            "voip"
        )
        if (candidates.isEmpty()) return null
        val json = JSONObject().put("classNames", JSONArray(candidates.distinct()))
        AppLog.i("DexKit: scanFlutterVoip → ${candidates.size} classes")
        return json
    }

    private fun scanVoipMgr(): JSONObject? {
        if (!DkBridge.isReady) return null
        val candidates = DkBridge.findClasses(
            arrayOf("com.tencent.mm.plugin.voip.model"),
            "voip"
        )
        if (candidates.isEmpty()) return null
        val json = JSONObject().put("classNames", JSONArray(candidates.distinct()))
        AppLog.i("DexKit: scanVoipMgr → ${candidates.size} classes")
        return json
    }

    private fun scanIncomingCallMgr(): JSONObject? {
        if (!DkBridge.isReady) return null
        val candidates = DkBridge.findClasses(
            arrayOf("com.tencent.mm.plugin.voip.model"),
            "IncomingCall"
        )
        if (candidates.isEmpty()) return null
        val json = JSONObject().put("classNames", JSONArray(candidates.distinct()))
        AppLog.i("DexKit: scanIncomingCallMgr → ${candidates.size} classes")
        return json
    }

    private fun saveResult(key: String, result: JSONObject) {
        sp.edit().putString(key, result.toString()).apply()
        AppLog.i("DexKit: saved $key (${result.toString().take(100)}...)")
    }
}
