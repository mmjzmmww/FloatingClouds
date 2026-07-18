package top.mmjz.floatingclouds.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.mmjz.floatingclouds.util.DexKitScanner

/**
 * 统一缓存层：优先读自己 DexKit 扫描缓存，fallback InkHide 缓存
 */
object DexKitCache {

    // 继承自 HookCacheReader 的数据结构
    data class PopupCreateSpec(val popupClassName: String, val callbackClassName: String, val methodName: String)
    data class PopupBuildSpec(val popupClassName: String, val methodName: String)

    data class QuickAddMenuCache(
        val conversationLongClickClassNames: List<String>,
        val conversationMenuCallbackClassNames: List<String>,
        val popupClassNames: List<String>,
        val popupCreateSpecs: List<PopupCreateSpec>,
        val popupBuildSpecs: List<PopupBuildSpec>,
        val popupClickHandlerClassNames: List<String>,
        val popupAdapterClassNames: List<String>,
        val contactMenuCallbackClassNames: List<String>
    )

    data class ConvMvvmListCache(
        val adapterClassNames: List<String>,
        val dataSourceClassNames: List<String>,
        val itemClassNames: List<String>,
        val holderClassNames: List<String>,
        val requestClassNames: List<String>,
        val storageClassNames: List<String>
    )

    data class ContactCache(val classNames: List<String>)
    data class AntiRevokeCache(val className: String, val methodName: String, val methodSign: String)
    data class SearchCache(val searchViewClassNames: List<String>)
    data class RecentForwardCache(val activityClassNames: List<String>)

    private var selfCache: Map<String, String>? = null
    private var inkCache: Map<String, String>? = null

    fun init(context: Context) {
        selfCache = try {
            context.getSharedPreferences("dexkit_scan_cache", Context.MODE_PRIVATE)
                .all.mapValues { it.value.toString() }
        } catch (_: Exception) { emptyMap() }

        inkCache = try {
            context.getSharedPreferences("hook_point_cache", Context.MODE_PRIVATE)
                .all.mapValues { it.value.toString() }
        } catch (_: Exception) { emptyMap() }

        AppLog.i("DexKitCache: self=${selfCache?.size ?: 0} keys, ink=${inkCache?.size ?: 0} keys")
    }

    // ═══════════════════════
    // Public API
    // ═══════════════════════

    fun getQuickAddMenu(): QuickAddMenuCache? {
        // 自己的 v10 > InkHide v9
        val json = getJson("quick_add_menu_v10") ?: getJson("quick_add_menu_v9") ?: return null
        return try {
            QuickAddMenuCache(
                conversationLongClickClassNames = json.jsonList("conversationLongClickClassNames"),
                conversationMenuCallbackClassNames = json.jsonList("conversationMenuCallbackClassNames"),
                popupClassNames = json.jsonList("popupClassNames"),
                popupCreateSpecs = json.jsonArray("popupCreateSpecs")?.toCreateSpecList() ?: emptyList(),
                popupBuildSpecs = json.jsonArray("popupBuildSpecs")?.toBuildSpecList() ?: emptyList(),
                popupClickHandlerClassNames = json.jsonList("popupClickHandlerClassNames"),
                popupAdapterClassNames = json.jsonList("popupAdapterClassNames"),
                contactMenuCallbackClassNames = json.jsonList("contactMenuCallbackClassNames")
            )
        } catch (t: Throwable) {
            AppLog.w("DexKitCache: parse QuickAddMenu failed", t)
            null
        }
    }

    fun getConvMvvmList(): ConvMvvmListCache? {
        val json = getJson("conversation_mvvm_list_v3") ?: getJson("conversation_mvvm_list_v2") ?: return null
        return try {
            ConvMvvmListCache(
                adapterClassNames = json.jsonList("adapterClassNames"),
                dataSourceClassNames = json.jsonList("dataSourceClassNames"),
                itemClassNames = json.jsonList("itemClassNames"),
                holderClassNames = json.jsonList("holderClassNames"),
                requestClassNames = json.jsonList("requestClassNames"),
                storageClassNames = json.jsonList("storageClassNames")
            )
        } catch (t: Throwable) {
            AppLog.w("DexKitCache: parse ConvMvvm failed", t)
            null
        }
    }

    fun getContactClasses(): ContactCache? {
        val json = getJson("contact_mvvm_address_v2") ?: getJson("contact_mvvm_address") ?: return null
        return try {
            ContactCache(classNames = json.jsonList("classNames").ifEmpty { json.jsonList("dataClassNames") })
        } catch (_: Exception) { null }
    }

    fun getAntiRevoke(): AntiRevokeCache? {
        val json = getJson("anti_revoke_v2") ?: getJson("anti_revoke_revoke_method") ?: return null
        return try {
            AntiRevokeCache(
                className = json.optString("className", ""),
                methodName = json.optString("methodName", ""),
                methodSign = json.optString("methodSign", "")
            )
        } catch (_: Exception) { null }
    }

    fun getSearchCommand(): SearchCache? {
        val json = getJson("search_command_v2") ?: getJson("search_command_v1") ?: return null
        return try {
            SearchCache(searchViewClassNames = json.jsonList("searchViewClassNames"))
        } catch (_: Exception) { null }
    }

    fun getRecentForward(): RecentForwardCache? {
        val json = getJson("recent_forward_v2") ?: getJson("recent_forward_v1") ?: return null
        return try {
            RecentForwardCache(activityClassNames = json.jsonList("activityClassNames"))
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════
    // T5: 混淆类名 DexKit 主路径候选读取（键与 DexKitScanner 一致）
    // ═══════════════════════════════════════

    /** 会话存储类（x3 系列）候选类名列表 */
    fun getConvStorageClassCandidates(): List<String> =
        getJson(DexKitScanner.KEY_CONV_STORAGE)?.jsonList("classNames") ?: emptyList()

    /** 存储辅助类（yj0 系列）候选类名列表 */
    fun getConvStorageHelperClassCandidates(): List<String> =
        getJson(DexKitScanner.KEY_CONV_STORAGE_HELPER)?.jsonList("classNames") ?: emptyList()

    /** Flutter 通话入口类候选类名列表 */
    fun getFlutterVoipClassCandidates(): List<String> =
        getJson(DexKitScanner.KEY_FLUTTER_VOIP)?.jsonList("classNames") ?: emptyList()

    /** VoipMgr 类候选类名列表 */
    fun getVoipMgrClassCandidates(): List<String> =
        getJson(DexKitScanner.KEY_VOIP_MGR)?.jsonList("classNames") ?: emptyList()

    /** IncomingCallMgr 类候选类名列表 */
    fun getIncomingCallMgrClassCandidates(): List<String> =
        getJson(DexKitScanner.KEY_INCOMING_CALL_MGR)?.jsonList("classNames") ?: emptyList()

    fun isReady(): Boolean = getQuickAddMenu() != null && getConvMvvmList() != null

    // ═══════════════════════
    // Private
    // ═══════════════════════

    private fun getJson(key: String): JSONObject? {
        val raw = selfCache?.get(key) ?: inkCache?.get(key) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun JSONObject.jsonList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }
    }

    private fun JSONObject.jsonArray(key: String): JSONArray? = optJSONArray(key)

    private fun JSONArray.toCreateSpecList(): List<PopupCreateSpec> = 
        (0 until length()).mapNotNull { i ->
            val obj = optJSONObject(i) ?: return@mapNotNull null
            PopupCreateSpec(
                popupClassName = obj.optString("popupClassName", ""),
                callbackClassName = obj.optString("callbackClassName", ""),
                methodName = obj.optString("methodName", "")
            )
        }

    private fun JSONArray.toBuildSpecList(): List<PopupBuildSpec> =
        (0 until length()).mapNotNull { i ->
            val obj = optJSONObject(i) ?: return@mapNotNull null
            PopupBuildSpec(popupClassName = obj.optString("popupClassName", ""), methodName = obj.optString("methodName", ""))
        }
}
