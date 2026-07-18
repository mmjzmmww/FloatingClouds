package top.mmjz.floatingclouds.plugin.part

import android.text.TextUtils
import android.util.LruCache
import android.util.SparseArray
import android.view.View
import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.plugin.PluginProviders
import top.mmjz.floatingclouds.util.StealthLog
import top.mmjz.floatingclouds.BuildConfig
import top.mmjz.floatingclouds.Constrant
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.AppVersionUtil
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.DexKitCache
import top.mmjz.floatingclouds.util.FeatureDiagnostics
import top.mmjz.floatingclouds.util.FieldClassUtil
import top.mmjz.floatingclouds.util.TextKit
import java.lang.reflect.Field

/**
 * 隐藏搜索列表
 */
class HideSearchListUIPluginPart : IPlugin {
    private val hideFieldInfoCache = HashMap<String, HashSet<Field>>()
    private val jsonResultLruCache = LruCache<String, CharSequence>(16)

    override fun handleHook(session: HookSession) {
        if (AppVersionUtil.getVersionCode() > Constrant.WX_CODE_8_0_47) {
            // 8.0.70+ 使用新版 FTS/MVVM 搜索，通过 RecyclerView 兜底隐藏
            StealthLog.d("HideSearchListUIPluginPart: handle high version search via RecyclerView")
            handleHighVersionSearch(session)
            return
        }
        if (AppVersionUtil.getVersionCode() < Constrant.WX_CODE_8_0_44 || AppVersionUtil.getVersionCode() == Constrant.WX_CODE_PLAY_8_0_48) { // WX_CODE_PLAY_8_0_42 matches
            handleGlobalSearch(session)
            handleDetailSearch(session)
            return
        }

        val getItemMethod = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_44 -> "h"
            Constrant.WX_CODE_8_0_49 -> "g"
            else -> "i"
        }
        //hook getItem --> rename to h
        //hook adapter 's getItem method, this method provider data for every itemView
        session.findAndHook("com.tencent.mm.plugin.fts.ui.a0",
            getItemMethod,
            java.lang.Integer.TYPE
        ) { chain ->
            val result = chain.proceed()
            if (needHideUserName2(result, chain.args[0] as? Int)) {
                StealthLog.d("search hide", result)
                val f: SparseArray<*> = AppReflect.getObjectField(chain.thisObject, "f") as SparseArray<*>
                f.get(0)
            } else {
                result
            }
        }
    }

    private fun handleDetailSearch(session: HookSession) {
        var hookClazzName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_38..Constrant.WX_CODE_8_0_41 -> "com.tencent.mm.plugin.fts.ui.x"
            else -> "com.tencent.mm.plugin.fts.ui.y"
        }
        //全局搜索详情置空
        session.findAndHook(
            hookClazzName,
            "d",
            Integer.TYPE
        ) { chain ->
            val result = chain.proceed()
            if (needHideUserName2(result, chain.args.getOrNull(0) as? Int)) {
                StealthLog.d(result)
                try {
                    result?.javaClass?.getDeclaredConstructor()?.newInstance()
                } catch (e: Throwable) {
                    StealthLog.d("error new Instance, return null")
                    null
                }
            } else {
                result
            }
        }
    }

    private fun handleGlobalSearch(session: HookSession) {
        var hookClazzName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_38..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.plugin.fts.ui.y"
            else -> "com.tencent.mm.plugin.fts.ui.z"
        }
        //全局搜索首页
        session.findAndHook(
            hookClazzName,
            "d",
            Integer.TYPE
        ) { chain ->
            val result = chain.proceed()
            if (needHideUserName(result, Integer.TYPE)) {
                StealthLog.d(result)
                runCatching {
                    result?.javaClass?.getDeclaredConstructor()?.newInstance()
                }.getOrElse {
                    StealthLog.w("error new Instance, return null")
                    null
                }
            } else {
                result
            }
        }
    }

    private fun needHideUserName(_unused: Any?, itemData: Any?): Boolean {
        if (itemData == null) {
            return false
        }
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideMainSearch) {
            return false
        }

        if (BuildConfig.DEBUG) {
        }

        var chatUser: String? = try {
            val fieldName = when (AppVersionUtil.getVersionCode()) {
                Constrant.WX_CODE_8_0_40 -> "q1"
                else -> "q"
            }
            AppReflect.getObjectField(itemData, fieldName) as? String
        } catch (e: Throwable) {
            null
        }
        if (chatUser == null) {
            when (AppVersionUtil.getVersionCode()) {
                in Constrant.WX_CODE_8_0_33..Constrant.WX_CODE_8_0_41 -> {
                    val fieldValue: Any = AppReflect.getObjectField(itemData, "p") ?: return false
                    chatUser = AppReflect.getObjectField(fieldValue, "e") as? String
                }

                Constrant.WX_CODE_8_0_32 -> {
                    val fieldValue: Any = AppReflect.getObjectField(itemData, "o") ?: return false
                    chatUser = AppReflect.getObjectField(fieldValue, "e") as? String
                }
            }
        }
        if (chatUser == null) {
            return false
        }

        return (WXMaskPlugin.containChatUser(chatUser)).also {
            if (it) {
                StealthLog.d("need hide user from search result list after", chatUser)
            }
        }
    }


    fun needHideUserName2(result: Any?, position: Int?): Boolean {
        if (result == null) {
            return false
        }
        if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideMainSearch) {
            return false
        }
        var clazz: Class<*>? = result.javaClass ?: return false
        if (hideFieldInfoCache[clazz!!.name] != null) {
            for (field in hideFieldInfoCache[clazz.name]!!) {
                if (checkFieldNeedHide(result, field)) {
                    StealthLog.d("hide field from cache: ", field.type.name, field.name, field.get(result))
                    return true
                }
            }
            return false
        }
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                try {
                    if (checkFieldNeedHide(result, field)) {
                        StealthLog.d("hide field: ", field.type.name, field.name, field.get(result))
                        return true
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            clazz = try {
                clazz.superclass
            } catch (e: Exception) {
                break
            }
        }
        return false
    }

    private fun checkFieldNeedHide(itemData: Any, field: Field): Boolean {
        var fieldValue: Any? = field.get(itemData) ?: return false
        var clazzName = field.type.name
        if (field.type.isAssignableFrom(Number::class.java)
            || field.type.isAssignableFrom(Byte::class.java)
            || clazzName.startsWith("android")
            || TextKit.isContain(FieldClassUtil.getAllClassName(fieldValue), "android")
        ) {
            return false
        }
        var jsonKey = fieldValue.toString().hashCode().toString()

        var compareText = if (fieldValue is CharSequence) {
            fieldValue
        } else {
            ""
        }
        if (compareText.isBlank()) {
            return false
        }
        jsonResultLruCache.put(jsonKey, compareText)

        for (wxid in PluginProviders.from(WXMaskPlugin::class.java)!!.maskIdList) {
            if (TextUtils.isEmpty(wxid) || TextUtils.isEmpty(wxid?.trim())) {
                continue
            }
            if (compareText.contains(wxid!!)) {
                putField2Cache(itemData::class.java.name, field)
                StealthLog.d("hit wxid compareText: ", compareText, field)
                return true
            }
        }
        return false

    }

    private fun putField2Cache(itemClassName: String, field: Field) {
        var pool = hideFieldInfoCache[itemClassName]
        if (pool == null) {
            pool = hashSetOf(field)
            hideFieldInfoCache[itemClassName] = pool
        } else {
            pool.add(field)
        }
    }

    /**
     * 8.0.70+ 全局搜索兜底：Hook WxRecyclerAdapter.o0，对搜索结果数据项进行字段扫描，
     * 若包含密友/终极隐藏 wxid 则隐藏对应 itemView。
     * 优先使用 DexKitCache 中缓存的搜索类名。
     */
    private fun handleHighVersionSearch(session: HookSession) {
        runCatching {
            val wxRecyclerAdapterClass = AppReflect.findClassIfExists(
                "com.tencent.mm.view.recyclerview.WxRecyclerAdapter", session.classLoader
            ) ?: return

            // 从缓存获取搜索View类名，用于识别搜索结果数据
            val searchCache = DexKitCache.getSearchCommand()
            val knownSearchViews = searchCache?.searchViewClassNames ?: emptyList()
            if (knownSearchViews.isNotEmpty()) {
                FeatureDiagnostics.reportDexKitScan("search_views", "CACHED", "count=${knownSearchViews.size}")
            }

            val s0HolderClass = AppReflect.findClassIfExists("vh5.s0", session.classLoader) ?: return
            session.findAndHook(
                wxRecyclerAdapterClass.name,
                "o0",
                s0HolderClass,
                Int::class.java
            ) { chain ->
                if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideMainSearch) {
                    return@findAndHook chain.proceed()
                }
                val dataList = AppReflect.getObjectField(chain.thisObject, "data") as? ArrayList<*> ?: return@findAndHook chain.proceed()
                val position = chain.args[1] as? Int ?: return@findAndHook chain.proceed()
                if (position < 0 || position >= dataList.size) return@findAndHook chain.proceed()
                val item = dataList[position] ?: return@findAndHook chain.proceed()

                val shouldHide = needHideUserName2(item, position)
                if (shouldHide) {
                    val holder = chain.args[0] ?: return@findAndHook chain.proceed()
                    val itemView = AppReflect.getObjectField(holder, "itemView") as? View ?: return@findAndHook chain.proceed()
                    itemView.visibility = View.GONE
                    itemView.layoutParams?.let { lp ->
                        lp.height = 0; lp.width = 0
                        itemView.layoutParams = lp
                    }
                    StealthLog.d("HideSearchListUIPluginPart: hide search item at $position")
                }
                chain.proceed()
            }
        }.onFailure {
            StealthLog.w("HideSearchListUIPluginPart: handle high version search fail", it)
        }
    }
}
