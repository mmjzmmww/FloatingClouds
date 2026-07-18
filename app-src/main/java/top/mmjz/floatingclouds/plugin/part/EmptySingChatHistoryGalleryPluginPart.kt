package top.mmjz.floatingclouds.plugin.part

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.plugin.IPlugin
import top.mmjz.floatingclouds.util.StealthLog
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.Constrant
import top.mmjz.floatingclouds.plugin.WXMaskPlugin
import top.mmjz.floatingclouds.util.AppVersionUtil
import top.mmjz.floatingclouds.util.ConfigUtil
import java.lang.reflect.Method

/**
 * 置空单聊页面菜单的"查找聊天记录"搜索结果
 */
class EmptySingChatHistoryGalleryPluginPart : IPlugin {
    val MediaHistoryGalleryUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI"
    var mChattingArguments: Bundle? = null

    override fun handleHook(session: HookSession) {
        handleImageQueryMainUI(session)
        setEmptyDetailHistoryUI(session)
        setEmptyActionBarTabPageUI(session)
    }

    /**
     * 处理8.0.49之后出现的图片搜索页面
     */
    private fun handleImageQueryMainUI(session: HookSession) {
        val ImageQueryMainUI = "com.tencent.mm.view.activity.ImageQueryMainUI"
        val imageQueryClass = AppReflect.findClassIfExists(ImageQueryMainUI, session.classLoader) ?: return

        session.findAndHook(
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment",
            "onCreate",
            Bundle::class.java
        ) { chain ->
            val result = chain.proceed()
            mChattingArguments = AppReflect.callMethod(chain.thisObject, "getArguments") as? Bundle
            result
        }
        session.findAndHook(
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment",
            "onDestroy"
        ) { chain ->
            val result = chain.proceed()
            mChattingArguments = null
            result
        }
        session.findAndHook(
            ImageQueryMainUI,
            "onCreate",
            Bundle::class.java
        ) { chain ->
            val act: Activity = chain.thisObject as Activity
            if (mChattingArguments == null) return@findAndHook chain.proceed()
            val bundle: Bundle = mChattingArguments as Bundle
            val thizUser = bundle.getString("Chat_User")
            if (WXMaskPlugin.containChatUser(thizUser)) {
                act.finish()
            }
            val kSet = bundle.keySet()
            val sb = StringBuilder()
            for (key in kSet) {
                sb.append(key + ": " + bundle.get(key) + ", ")
            }
            StealthLog.d("ImageQueryMainUI onCreate", sb.toString())
            chain.proceed()
        }
    }

    private fun setEmptyDetailHistoryUI(session: HookSession) {
        setEmptyDetailHistoryUIForMedia(session)
        setEmptyDetailHistoryUIForGalleryCompat(session)
    }

    private fun setEmptyDetailHistoryUIForMedia(session: HookSession) {
        var mediaMethodName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_35 -> "k"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_43 -> "l"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44, Constrant.WX_CODE_PLAY_8_0_48 -> "z"
            in Constrant.WX_CODE_8_0_44..Constrant.WX_CODE_8_0_45 -> "A"
            Constrant.WX_CODE_8_0_47 -> "B"
            Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_56, Constrant.WX_CODE_8_0_58 -> "y"
            Constrant.WX_CODE_8_0_50 -> "K"
            Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_60 -> "z"
            else -> "l"
        }
        val MediaHistoryListUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryListUI"
        var mediaMethod: Method? = AppReflect.findMethodExact(
            MediaHistoryListUI, session.classLoader, mediaMethodName,
            java.lang.Boolean.TYPE, java.lang.Integer.TYPE
        )

        if (mediaMethod == null) {
            val guessMethods = AppReflect.findMethodsByExactPredicate(
                MediaHistoryListUI, session.classLoader
            ) { m ->
                m.returnType == Void.TYPE &&
                    m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE, Integer.TYPE))
            }
            if (guessMethods.isNotEmpty()) {
                mediaMethod = guessMethods[0]
            }
            StealthLog.w(AppVersionUtil.getSmartVersionName(), "guess MediaHistoryListUI empty method is ", mediaMethod)
        }
        if (mediaMethod == null) return

        session.hook(mediaMethod).intercept { chain ->
            if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideSingleSearch) return@intercept chain.proceed()
            val activity: Activity = chain.thisObject as Activity
            val intent = activity.intent
            val userName = intent.getStringExtra("kintent_talker")
            if (userName.isNullOrBlank()) {
                StealthLog.w("MediaHistoryListUI's user is empty", userName)
                return@intercept chain.proceed()
            }
            if (WXMaskPlugin.containChatUser(userName)) {
                val newArgs = arrayOf(chain.args[0], 0)
                StealthLog.i("empty MediaHistoryListUI data")
                return@intercept chain.proceed(newArgs)
            }
        }
    }

    private fun setEmptyDetailHistoryUIForGalleryCompat(session: HookSession) {
        if (AppVersionUtil.getVersionCode() > Constrant.WX_CODE_8_0_43) {
            setEmptyDetailHistoryUIForGallery8044(session)
        } else {
            setEmptyDetailHistoryUIForGallery(session)
        }
    }

    private fun setEmptyDetailHistoryUIForGallery(session: HookSession) {
        val methodName = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_35 -> "k"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_43 -> "l"
            else -> null
        }
        var galleryMethod: Method? = null
        if (methodName != null) {
            galleryMethod = AppReflect.findMethodExact(
                MediaHistoryGalleryUI, session.classLoader, methodName,
                java.lang.Boolean.TYPE, java.lang.Integer.TYPE
            )
        }
        if (galleryMethod == null) {
            val guessMethods = AppReflect.findMethodsByExactPredicate(
                MediaHistoryGalleryUI, session.classLoader
            ) { m ->
                m.returnType == Void.TYPE &&
                    m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE, java.lang.Integer.TYPE))
            }
            if (guessMethods.isNotEmpty()) {
                galleryMethod = guessMethods[0]
            }
            StealthLog.w(AppVersionUtil.getSmartVersionName(), "guess MediaHistoryGalleryUI empty method is ", galleryMethod)
        }
        if (galleryMethod == null) return
        session.hook(galleryMethod).intercept { chain ->
            if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideSingleSearch) return@intercept chain.proceed()
            val activity: Activity = chain.thisObject as Activity
            val intent = activity.intent
            val userName = intent.getStringExtra("kintent_talker")
            if (userName.isNullOrBlank()) {
                StealthLog.w("MediaHistoryListUI's user is empty", userName)
                return@intercept chain.proceed()
            }
            if (WXMaskPlugin.containChatUser(userName)) {
                val newArgs = arrayOf(chain.args[0], 0)
                StealthLog.i("empty MediaHistoryGalleryUI data")
                return@intercept chain.proceed(newArgs)
            }
        }
    }

    private fun setEmptyDetailHistoryUIForGallery8044(session: HookSession) {
        val presenterClazz = when (AppVersionUtil.getVersionCode()) {
            in Constrant.WX_CODE_8_0_44..Constrant.WX_CODE_8_0_53 -> "com.tencent.mm.ui.chatting.presenter.k1"
            else -> "com.tencent.mm.ui.chatting.presenter.j1"
        }
        val methods = AppReflect.findMethodsByExactPredicate(
            presenterClazz, session.classLoader
        ) { m ->
            m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE, Integer.TYPE))
        }
        if (methods.isNotEmpty()) {
            session.hook(methods[0]).intercept { chain ->
                if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideSingleSearch) return@intercept chain.proceed()

                val fields = AppReflect.findFieldsByExactPredicate(chain.thisObject.javaClass) {
                    val v = it.get(chain.thisObject)
                    if (v != null && v.javaClass.name == MediaHistoryGalleryUI) return@findFieldsByExactPredicate true
                    false
                }
                var activity: Activity? = null
                if (fields.isNotEmpty()) {
                    activity = fields[0].get(chain.thisObject) as Activity
                }
                if (activity == null) {
                    StealthLog.w("can not find DetailHistoryUIForGallery8044")
                    return@intercept chain.proceed()
                }
                val intent = activity.intent
                val userName = intent.getStringExtra("kintent_talker")
                if (userName.isNullOrBlank()) {
                    StealthLog.w("MediaHistoryListUI's user is empty", userName)
                    return@intercept chain.proceed()
                }
                if (WXMaskPlugin.containChatUser(userName)) {
                    val newArgs = arrayOf(false, 0)
                    StealthLog.i("empty MediaHistoryGalleryUI data (8044)")
                    return@intercept chain.proceed(newArgs)
                }
            }
        } else {
            StealthLog.w("can not find presenter for setEmptyDetailHistoryUIForGallery8044")
        }
    }

    /**
     * 处理通过顶部ActionBar搜索框进行的结果
     */
    private fun setEmptyActionBarTabPageUI(session: HookSession) {
        val Clazz_FTSMultiAllResultFragment = "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiAllResultFragment"
        var commonHookMethodName: String? = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_32 -> "N"
            Constrant.WX_CODE_8_0_33 -> "O"
            Constrant.WX_CODE_8_0_34 -> {
                if (AppVersionUtil.getVersionName() == "8.0.35") "P" else "R"
            }
            Constrant.WX_CODE_8_0_35, Constrant.WX_CODE_PLAY_8_0_42 -> "P"
            Constrant.WX_CODE_8_0_37 -> "Q"
            Constrant.WX_CODE_8_0_38 -> "R"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_41, Constrant.WX_CODE_8_0_43 -> "Q"
            in Constrant.WX_CODE_8_0_41..Constrant.WX_CODE_8_0_42 -> "R"
            in Constrant.WX_CODE_8_0_44..Constrant.WX_CODE_8_0_47 -> "D"
            Constrant.WX_CODE_PLAY_8_0_48 -> "G"
            Constrant.WX_CODE_8_0_49 -> "F"
            Constrant.WX_CODE_8_0_50 -> "D"
            in Constrant.WX_CODE_8_0_51..Constrant.WX_CODE_8_0_56 -> "I"
            Constrant.WX_CODE_8_0_58 -> "R"
            Constrant.WX_CODE_8_0_60 -> "V"
            else -> null
        }
        StealthLog.d("setEmptyActionBarTabPageUI method is :", commonHookMethodName)
        var preHookMethod: Method? = null
        if (commonHookMethodName != null) {
            preHookMethod = AppReflect.findMethodExact(
                Clazz_FTSMultiAllResultFragment, session.classLoader, commonHookMethodName,
                java.util.ArrayList::class.java
            )
        }
        if (preHookMethod == null) {
            val methods = AppReflect.findMethodsByExactPredicate(
                Clazz_FTSMultiAllResultFragment, session.classLoader
            ) { m ->
                m.returnType == Void.TYPE &&
                    m.parameterTypes.contentEquals(arrayOf(java.util.ArrayList::class.java))
            }
            if (methods.isNotEmpty()) {
                preHookMethod = methods[0]
                commonHookMethodName = methods[0].name
            }
            StealthLog.w(AppVersionUtil.getSmartVersionName(), "guess setEmptyActionBarTabPageUI method:", preHookMethod)
        }

        if (preHookMethod == null) {
            StealthLog.w(AppVersionUtil.getSmartVersionName(), "setEmptyActionBarTabPageUI is method null")
            return
        }

        //tab==全部，搜索结果置空
        session.hook(preHookMethod).intercept { chain ->
            if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideSingleSearch) return@intercept chain.proceed()
            debugLog(chain)
            if (isHitMaskId(chain.thisObject)) {
                val arrayList: java.util.ArrayList<*> = chain.args[0] as java.util.ArrayList<*>
                arrayList.clear()
            }
            chain.proceed()
        }
        if (commonHookMethodName == null) {
            StealthLog.i("setEmptyActionBarTabPageUI is null")
            return
        }
        //其他的/普通的/一般的tab，搜索结果置空
        session.findAndHook(
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiNormalResultFragment",
            commonHookMethodName,
            java.util.ArrayList::class.java
        ) { chain ->
            if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideSingleSearch) return@findAndHook chain.proceed()
            debugLog(chain)
            if (isHitMaskId(chain.thisObject)) {
                val arrayList: java.util.ArrayList<*> = chain.args[0] as java.util.ArrayList<*>
                arrayList.clear()
            }
            chain.proceed()
        }

        //tab==图片，全体视图替换置空
        session.findAndHook(
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiImageResultFragment",
            "onCreateView",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Bundle::class.java
        ) { chain ->
            if (!ConfigUtil.isMasterEnabled() || !ConfigUtil.getOptionData().hideSingleSearch) return@findAndHook chain.proceed()
            debugLog(chain)
            if (isHitMaskId(chain.thisObject)) {
                val inflater = chain.args[0] as LayoutInflater
                val viewGroup: ViewGroup = chain.args[1] as ViewGroup
                val layoutId = AppReflect.callMethod(chain.thisObject, "getLayoutId") as? Int ?: return@findAndHook chain.proceed()
                inflater.inflate(layoutId, viewGroup, false)
            } else {
                chain.proceed()
            }
        }
    }

    private fun debugLog(chain: XposedInterface.Chain) {
        StealthLog.d(
            "set empty for ${chain.thisObject}",
            "hook method args:",
            chain.args,
            "fragment arguments:",
            AppReflect.callMethod(chain.thisObject, "getArguments"),
        )
    }

    private fun isHitMaskId(fragmentObj: Any?): Boolean {
        val activity = AppReflect.callMethod(fragmentObj, "getActivity") as Activity?
        if (activity == null) {
            StealthLog.w("Not attach Activity for ", fragmentObj)
            return false
        }
        val intent = activity.intent
        StealthLog.d(activity, activity.intent.extras)

        val username = intent.getStringExtra("detail_username")
        return WXMaskPlugin.containChatUser(username)
    }
}
