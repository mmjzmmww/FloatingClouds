package top.mmjz.floatingclouds.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 读取 InkHide 通过 DexKit 生成的 hook 点缓存 (hook_point_cache SP)。
 * 
 * 提供各功能模块所需的真实类名/方法名，替代硬编码候选列表。
 * 若缓存不可用则返回 null，调用方应 fallback 到硬编码候选。
 */
object HookCacheReader {

    private var cache: Map<String, String>? = null

    /** 在模块初始化时调用一次 */
    fun init(context: Context) {
        try {
            val sp = context.getSharedPreferences("hook_point_cache", Context.MODE_PRIVATE)
            cache = sp.all.mapValues { it.value.toString() }
            AppLog.i("HookCacheReader: loaded ${cache?.size ?: 0} keys")
            // 打印关键 key 是否存在
            val keys = cache?.keys?.joinToString(",") ?: "null"
            AppLog.i("HookCacheReader: keys = $keys")
        } catch (t: Throwable) {
            AppLog.w("HookCacheReader: init failed", t)
            cache = null
        }
    }

    // ═══════════════════════════════════════
    // 快捷加入菜单 (quick_add_menu_v9)
    // ═══════════════════════════════════════

    data class PopupCreateSpec(
        val popupClassName: String,
        val callbackClassName: String,
        val methodName: String
    )

    data class PopupBuildSpec(
        val popupClassName: String,
        val methodName: String
    )

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

    fun getQuickAddMenu(): QuickAddMenuCache? {
        val json = getJson("quick_add_menu_v9") ?: return null
        return try {
            QuickAddMenuCache(
                conversationLongClickClassNames = json.optJSONArray("conversationLongClickClassNames")?.toStringList() ?: emptyList(),
                conversationMenuCallbackClassNames = json.optJSONArray("conversationMenuCallbackClassNames")?.toStringList() ?: emptyList(),
                popupClassNames = json.optJSONArray("popupClassNames")?.toStringList() ?: emptyList(),
                popupCreateSpecs = json.optJSONArray("popupCreateSpecs")?.toCreateSpecList() ?: emptyList(),
                popupBuildSpecs = json.optJSONArray("popupBuildSpecs")?.toBuildSpecList() ?: emptyList(),
                popupClickHandlerClassNames = json.optJSONArray("popupClickHandlerClassNames")?.toStringList() ?: emptyList(),
                popupAdapterClassNames = json.optJSONArray("popupAdapterClassNames")?.toStringList() ?: emptyList(),
                contactMenuCallbackClassNames = json.optJSONArray("contactMenuCallbackClassNames")?.toStringList() ?: emptyList()
            )
        } catch (t: Throwable) {
            AppLog.w("HookCacheReader: parse quick_add_menu_v9 failed", t)
            null
        }
    }

    // ═══════════════════════════════════════
    // 会话 MVVM 列表 (conversation_mvvm_list_v2)
    // ═══════════════════════════════════════

    data class ConvMvvmListCache(
        val adapterClassNames: List<String>,
        val dataSourceClassNames: List<String>,
        val itemClassNames: List<String>,
        val holderClassNames: List<String>,
        val requestClassNames: List<String>,
        val storageClassNames: List<String>
    )

    fun getConvMvvmList(): ConvMvvmListCache? {
        val json = getJson("conversation_mvvm_list_v2") ?: return null
        return try {
            ConvMvvmListCache(
                adapterClassNames = json.optJSONArray("adapterClassNames")?.toStringList() ?: emptyList(),
                dataSourceClassNames = json.optJSONArray("dataSourceClassNames")?.toStringList() ?: emptyList(),
                itemClassNames = json.optJSONArray("itemClassNames")?.toStringList() ?: emptyList(),
                holderClassNames = json.optJSONArray("holderClassNames")?.toStringList() ?: emptyList(),
                requestClassNames = json.optJSONArray("requestClassNames")?.toStringList() ?: emptyList(),
                storageClassNames = json.optJSONArray("storageClassNames")?.toStringList() ?: emptyList()
            )
        } catch (t: Throwable) {
            AppLog.w("HookCacheReader: parse conversation_mvvm_list_v2 failed", t)
            null
        }
    }

    // ═══════════════════════════════════════
    // 私有
    // ═══════════════════════════════════════

    private fun getJson(key: String): JSONObject? {
        val raw = cache?.get(key) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun JSONArray.toStringList(): List<String> {
        val list = mutableListOf<String>()
        for (i in 0 until length()) {
            list.add(optString(i, ""))
        }
        return list
    }

    private fun JSONArray.toCreateSpecList(): List<PopupCreateSpec> {
        val list = mutableListOf<PopupCreateSpec>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            list.add(PopupCreateSpec(
                popupClassName = obj.optString("popupClassName", ""),
                callbackClassName = obj.optString("callbackClassName", ""),
                methodName = obj.optString("methodName", "")
            ))
        }
        return list
    }

    private fun JSONArray.toBuildSpecList(): List<PopupBuildSpec> {
        val list = mutableListOf<PopupBuildSpec>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            list.add(PopupBuildSpec(
                popupClassName = obj.optString("popupClassName", ""),
                methodName = obj.optString("methodName", "")
            ))
        }
        return list
    }
}
