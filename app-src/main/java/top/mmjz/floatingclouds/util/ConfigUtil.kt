package top.mmjz.floatingclouds.util

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.text.TextUtils
import top.mmjz.floatingclouds.bean.MaskItemBean
import top.mmjz.floatingclouds.bean.OptionData
import org.json.JSONArray
import org.json.JSONObject

class ConfigUtil {
    companion object {
        val KEY_MASK_LIST = "maskList"
        val KEY_OPTIONS = "options"
        val KEY_CONFIG_MODE = "config_mode_flag"
        val KEY_HIDDEN_OWN_SNS = "hiddenOwnSnsIds"
        val KEY_HOTUPDATE_REMINDER_ACK = "blockHotUpdate_reminder_acked"

        val sp by lazy {
            LocalKVUtil.getTable("mask_wechat_config")
        }

        // ★★★ options 独立存储文件（与 maskList/缓存键隔离）★★★
        // 旧实现中 options 与 maskList 共用 mask_wechat_config.xml，
        // 任何 putString(maskList/缓存键) 的 commit 都会重写整个文件，
        // 把内存里可能陈旧的 options 值一起刷回磁盘 → 重启后配置被重置为默认。
        // 现把 options 单独存放，从根本上杜绝跨键 clobber。
        val optSp by lazy {
            LocalKVUtil.getTable("mask_wechat_options")
        }

        /** 定位某个 SP 文件在磁盘上的真实路径（多目录兼容） */
        private fun findSpFile(name: String): java.io.File? {
            val ctx = AppContext.context ?: return null
            val dirs = listOf(
                ctx.applicationInfo.dataDir,
                ctx.dataDir?.absolutePath,
                ctx.filesDir?.parentFile?.absolutePath
            ).filterNotNull().map { java.io.File(it, "shared_prefs") }
            return dirs.map { java.io.File(it, "$name.xml") }.firstOrNull { it.exists() }
        }

        // ═══ 内存级配置缓存（切断主线程磁盘 I/O） ═══
        @Volatile private var cachedOptionData: OptionData? = null
        @Volatile private var cachedMaskList: ArrayList<MaskItemBean>? = null
        @Volatile private var cachedMaskListIsEmpty: Boolean? = null

        fun invalidateCache() {
            cachedOptionData = null
            cachedMaskList = null
            cachedMaskListIsEmpty = null
        }

        @JvmStatic
        private val dataSetObserverList = arrayListOf<ConfigSetObserver>()

        fun getMaskList(): ArrayList<MaskItemBean> {
            cachedMaskList?.let { return it }
            val result = try {
                getMaskListInternal()
            } catch (e: Throwable) {
                StealthLog.e(e)
                arrayListOf()
            }
            cachedMaskList = result
            cachedMaskListIsEmpty = result.isEmpty()
            return result
        }

        fun isMaskListEmpty(): Boolean {
            cachedMaskListIsEmpty?.let { return it }
            val empty = getMaskList().isEmpty()
            cachedMaskListIsEmpty = empty
            return empty
        }

        private fun getMaskListInternal(): ArrayList<MaskItemBean> {
            val result = ArrayList<MaskItemBean>()
            var needPersist = false
            try {
                val jsonText = sp.getString(KEY_MASK_LIST, "[]")
                val jsonArr = JSONArray(jsonText)
                for (i in 0 until jsonArr.length()) {
                    val json = jsonArr.optString(i)
                    if (json.isNullOrBlank()) {
                        continue
                    }
                    var bean = MaskItemBean.fromJson(json)
                    if (TextUtils.isEmpty(bean.maskId)) {
                        continue
                    }
                    // 防止旧版配置中伪装 ID 为空导致微信加载空联系人崩溃
                    if (bean.mapId.isNullOrBlank()) {
                        bean.mapId = "filehelper"
                        needPersist = true
                    }
                    // 防止 maskId 为空字符串（误操作删除后保存）
                    if (bean.maskId.isBlank()) {
                        continue
                    }
                    result.add(bean)
                }
                if (needPersist) {
                    StealthLog.w("ConfigUtil: sanitized empty mapId, persisting default")
                    setMaskList(result)
                }
            } catch (e: Exception) {
                StealthLog.w("getMaskList fail", e)
            }
            return result
        }

        fun setMaskList(data: List<MaskItemBean>) {
            JSONArray().also { arr ->
                data.forEach { arr.put(it.toJSONObject()) }
            }.toString().let {
                sp.edit().putString(KEY_MASK_LIST, it).commit()
            }
            cachedMaskList = ArrayList(data)
            cachedMaskListIsEmpty = data.isEmpty()
            notifyConfigSetObserverChanged()
        }

        fun addMaskList(item: MaskItemBean) {
            val maskList = try {
                getMaskListInternal()
            } catch (e: Exception) {
                StealthLog.e(item.toJson(), e)
                null
            } ?: return
            maskList.add(item)
            JSONArray().also { arr ->
                maskList.forEach { arr.put(it.toJSONObject()) }
            }.toString().let {
                sp.edit().putString(KEY_MASK_LIST, it).commit()  // ★ commit() 同步写盘
                cachedMaskList = ArrayList(maskList)
                cachedMaskListIsEmpty = false
            }
            notifyConfigSetObserverChanged()
        }

        fun removeMaskList(wxid: String) {
            val maskList = try { getMaskListInternal() } catch (e: Exception) { null } ?: return
            maskList.removeAll { it.maskId == wxid }
            JSONArray().also { arr -> maskList.forEach { arr.put(it.toJSONObject()) } }
                .toString().let {
                    sp.edit().putString(KEY_MASK_LIST, it).commit()
                    cachedMaskList = ArrayList(maskList)
                    cachedMaskListIsEmpty = maskList.isEmpty()
                }
            notifyConfigSetObserverChanged()
        }

        @JvmStatic
        fun initFileStore(ctx: android.content.Context) {
            FileConfigStore.init(ctx)
        }

        fun getOptionData(): OptionData {
            cachedOptionData?.let { return it }

            // ★ 优先从独立的 options 文件读取（绕过 SP 的多进程缓存失效 + 跨键 clobber）
            reloadConfigFromDisk()
            cachedOptionData?.let { return it }

            // 兜底1：旧文件 mask_wechat_config.xml 中的 options（首次迁移场景）
            val legacyJson = sp.getString(KEY_OPTIONS, null)
            if (!legacyJson.isNullOrBlank() && legacyJson != "{}") {
                val data = OptionData.fromJson(legacyJson)
                optSp.edit().putString(KEY_OPTIONS, legacyJson).commit()  // 迁移到独立文件
                cachedOptionData = data
                AppLog.logRaw("Floatingclouds_Config", android.util.Log.INFO,
                    "getOptionData migrated from legacy file: masterEnabled=${data.masterEnabled} pid=${android.os.Process.myPid()}")
                return data
            }

            // 兜底2：默认值
            val data = OptionData.fromJson("{}")
            cachedOptionData = data
            return data
        }

        /**
         * ★★★ T0：绕过 SharedPreferences 内存缓存，直接从 XML 文件重载配置 ★★★
         * Android 14+ 上 MODE_MULTI_PROCESS 已完全失效，SP 在进程启动时 load 后永不刷新。
         * UI 进程写入新配置后，其他微信进程的 sp.getString() 仍返回旧值（甚至 invalidateCache
         * 后再读也会被 SP 底层的内存 Map 覆盖）。
         * 此方法直接读 mask_wechat_config.xml 解析，确保拿到磁盘最新值。
         */
        @JvmStatic
        fun reloadConfigFromDisk() {
            try {
                // 优先读独立的 options 文件
                var spFile = findSpFile("mask_wechat_options")
                var fromNew = true
                if (spFile == null) {
                    // 迁移：从旧文件 mask_wechat_config.xml 读 options
                    spFile = findSpFile("mask_wechat_config")
                    fromNew = false
                }
                if (spFile == null) {
                    AppLog.logRaw("Floatingclouds_Config", android.util.Log.DEBUG,
                        "reloadConfigFromDisk: no SP file found, pid=${android.os.Process.myPid()}")
                    return
                }
                val xml = spFile.readText()
                val regex = Regex("""<string name="options">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(xml) ?: run {
                    AppLog.logRaw("Floatingclouds_Config", android.util.Log.DEBUG,
                        "reloadConfigFromDisk: <string name=\"options\"> not found in XML (new=$fromNew)")
                    return
                }
                val rawJson = match.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                val data = OptionData.fromJson(rawJson)
                cachedOptionData = data
                // 迁移：把旧文件的 options 写入独立文件，避免下次再走旧文件
                if (!fromNew) {
                    optSp.edit().putString(KEY_OPTIONS, rawJson).commit()
                }
                AppLog.logRaw("Floatingclouds_Config", android.util.Log.INFO,
                    "reloadConfigFromDisk OK (new=$fromNew): len=${rawJson.length} masterEnabled=${data.masterEnabled} hideMainConvList=${data.hideMainConvList} pid=${android.os.Process.myPid()}")
            } catch (e: Exception) {
                AppLog.logRaw("Floatingclouds_Config", android.util.Log.WARN, "reloadConfigFromDisk failed", e)
            }
        }

        /**
         * Dump 所有 SP 键值内容到 logcat，用于诊断配置丢失问题。
         */
        @JvmStatic
        fun dumpSpContent(tag: String) {
            try {
                val all = sp.all
                AppLog.logRaw(tag, android.util.Log.INFO, "=== SP dump (${all.size} keys) pid=${android.os.Process.myPid()} ===")
                for ((key, value) in all) {
                    val preview = value.toString().take(120)
                    AppLog.logRaw(tag, android.util.Log.INFO, "  [$key] = $preview")
                }
                AppLog.logRaw(tag, android.util.Log.INFO, "=== SP dump end ===")
            } catch (e: Exception) {
                AppLog.logRaw(tag, android.util.Log.WARN, "dumpSpContent failed", e)
            }
        }

        fun setOptionData(data: OptionData) {
            try {
                cachedOptionData = data  // ★ 先更新缓存，确保当前进程立即可读
                val json = OptionData.toJson(data)
                // ★ 写入独立 options 文件（杜绝被 maskList/缓存键提交覆盖）
                val ok = optSp.edit().putString(KEY_OPTIONS, json).commit()
                // 同时写旧文件保持兼容（旧文件 options 即便被 clobber 也不影响读取，因优先读新文件）
                sp.edit().putString(KEY_OPTIONS, json).commit()
                if (!ok) {
                    StealthLog.w("setOptionData: optSp commit returned false — disk may be full or write failed")
                }
                android.util.Log.d("Floatingclouds_Config", "setOptionData committed ok=$ok len=${json.length}")
            } catch (e: Exception) { StealthLog.w("save option fail", e) }
        }

        @JvmStatic
        fun isConfigModeFlag(): Boolean {
            val result = sp.getBoolean(KEY_CONFIG_MODE, false)
            StealthLog.d("ConfigModeFlag read: $result")
            return result
        }

        @JvmStatic
        fun setConfigModeFlag(enabled: Boolean) {
            StealthLog.i("ConfigModeFlag set: $enabled")
            sp.edit().putBoolean(KEY_CONFIG_MODE, enabled).commit()
        }

        fun getHiddenOwnSnsIds(): MutableSet<String> {
            return try {
                val text = sp.getString(KEY_HIDDEN_OWN_SNS, "[]") ?: "[]"
                val arr = JSONArray(text)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
                }
                set
            } catch (e: Exception) {
                StealthLog.w("getHiddenOwnSnsIds fail", e)
                mutableSetOf()
            }
        }

        fun setHiddenOwnSnsIds(ids: Set<String>) {
            try {
                sp.edit().putString(KEY_HIDDEN_OWN_SNS, JSONArray(ids.toList()).toString()).commit()
            } catch (e: Exception) {
                StealthLog.w("setHiddenOwnSnsIds fail", e)
            }
        }

        fun addHiddenOwnSnsId(id: String) {
            val set = getHiddenOwnSnsIds()
            if (set.add(id)) {
                setHiddenOwnSnsIds(set)
            }
        }

        fun removeHiddenOwnSnsId(id: String) {
            val set = getHiddenOwnSnsIds()
            if (set.remove(id)) {
                setHiddenOwnSnsIds(set)
            }
        }

        /** 清空全部已隐藏的朋友圈 snsId，返回被清空的数量 */
        fun clearHiddenOwnSnsIds(): Int {
            val count = getHiddenOwnSnsIds().size
            if (count > 0) setHiddenOwnSnsIds(emptySet())
            return count
        }

        fun clearData() {
            try {
                val result = sp.edit().clear().commit()
                if (!result) {
                    StealthLog.w("clear sp data fail$result")
                }
//                notifyConfigSetObserverChanged()
            } catch (e: Exception) {
                StealthLog.w("clear sp data fail", e)
            }
        }

        /**
         * 从旧包名的 SharedPreferences 迁移数据到当前包。
         * 解决真机上同时安装多个版本 APK 导致配置被旧模块覆盖的问题。
         *
         * 旧包名列表（已知的历史版本）：
         * - com.lu.wxmask（原始版）
         * - com.lu.wxmask272（二改版）
         * - com.lu.floatingclouds（早期重命名版）
         *
         * @return true 表示从某个旧版本迁移了数据
         */
        @JvmStatic
        fun tryMigrateFromLegacyPackages(): Boolean {
            val legacyPackages = listOf(
                "com.lu.wxmask",
                "com.lu.wxmask272",
                "com.lu.floatingclouds"
            )
            var migrated = false
            val tag = "Floatingclouds_Migrate"

            // 先检查当前 SP 是否已有数据（如果有，跳过迁移避免覆盖）
            val currentOptions = optSp.getString(KEY_OPTIONS, null) ?: sp.getString(KEY_OPTIONS, null)
            val currentMaskList = sp.getString(KEY_MASK_LIST, null)
            val currentHasData = !currentOptions.isNullOrBlank() && currentOptions != "{}"
                    && !currentMaskList.isNullOrBlank() && currentMaskList != "[]"

            if (currentHasData) {
                android.util.Log.i(tag, "Current SP already has data, skipping migration")
                return false
            }

            for (legacyPkg in legacyPackages) {
                try {
                    val ctx = AppContext.context ?: continue
                    val legacyCtx = ctx.createPackageContext(
                        legacyPkg,
                        android.content.Context.CONTEXT_IGNORE_SECURITY
                    )
                    val legacySp = legacyCtx.getSharedPreferences("mask_wechat_config",
                        android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS)

                    val options = legacySp.getString(KEY_OPTIONS, null)
                    val maskList = legacySp.getString(KEY_MASK_LIST, null)
                    val hasData = !options.isNullOrBlank() && options != "{}"
                            && !maskList.isNullOrBlank() && maskList != "[]"

                    if (!hasData) {
                        android.util.Log.d(tag, "Legacy package $legacyPkg has no data, skip")
                        continue
                    }

                    // 迁移数据
                    sp.edit()
                        .putString(KEY_OPTIONS, options)
                        .putString(KEY_MASK_LIST, maskList)
                        .apply {  // 先异步写入，下方 commit 确保落盘
                            android.util.Log.i(tag,
                                "Migrated options(${options!!.length}B) + maskList(${maskList!!.length}B) from $legacyPkg → top.mmjz.floatingclouds")
                        }
                    // options 同时写入独立文件（主读取源）
                    optSp.edit().putString(KEY_OPTIONS, options).commit()

                    // 复制隐藏朋友圈ID
                    val snsIds = legacySp.getString(KEY_HIDDEN_OWN_SNS, null)
                    if (!snsIds.isNullOrBlank() && snsIds != "[]") {
                        sp.edit().putString(KEY_HIDDEN_OWN_SNS, snsIds).apply()
                    }

                    // 复制配置模式标志
                    val configMode = legacySp.getBoolean(KEY_CONFIG_MODE, false)
                    if (configMode) {
                        sp.edit().putBoolean(KEY_CONFIG_MODE, true).apply()
                    }

                    migrated = true
                    invalidateCache()
                    android.util.Log.i(tag,
                        "Migration from $legacyPkg complete. Invalidate cache and reload.")

                    // 只迁移第一个找到的有数据的旧版本
                    break

                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    android.util.Log.d(tag, "Legacy package $legacyPkg not installed, skip")
                } catch (e: Exception) {
                    android.util.Log.w(tag, "Migration from $legacyPkg failed", e)
                }
            }

            if (!migrated) {
                android.util.Log.i(tag, "No legacy data found to migrate")
            }
            return migrated
        }

        fun registerConfigSetObserver(observer: ConfigSetObserver) {
            dataSetObserverList.add(observer)
        }

        fun unregisterConfigSetObserver(observer: ConfigSetObserver) {
            dataSetObserverList.remove(observer)
        }

        private fun notifyConfigSetObserverChanged() {
            dataSetObserverList.forEach {
                it.onConfigChange()
            }
        }

        fun removeMaskItem(chatUser: String) {
            val maskList = getMaskList()
            val it = maskList.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (chatUser == item.maskId) {
                    it.remove()
                }
            }
            setMaskList(maskList)
            notifyConfigSetObserverChanged()
        }

        /**
         * 通用布尔配置读取接口。
         * @param key 配置键名（与 OptionData JSON 字段对应）
         * @return 配置值（true/false），读取失败默认返回 false
         */
        @JvmStatic
        fun isConfigEnabled(key: String): Boolean {
            return try {
                val json = optSp.getString(KEY_OPTIONS, null) ?: sp.getString(KEY_OPTIONS, "{}") ?: "{}"
                JSONObject(json).optBoolean(key, false)
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 检查总开关是否启用。
         * 所有 PluginPart 应在回调中使用此方法，实现即时生效无需重启。
         * 受远程停运开关约束：远程 disabled=true 时强制返回 false。
         */
        @JvmStatic
        fun isMasterEnabled(): Boolean =
            getOptionData().masterEnabled && !top.mmjz.floatingclouds.plugin.RemoteKillSwitch.isRemoteDisabled()

        /**
         * 屏蔽微信热更新（Tinker 补丁）是否启用。
         *
         * ★ REQ-P1-4 方案 a：脱离 `masterEnabled` 门控，仅依赖 `blockHotUpdate` 配置（默认 true）。
         * 仅开本项、主开关关时，拦截仍按预期生效（LOW-7 修复）。
         *
         * ★ 预 attach 读盘回退：本方法可能在 `Application.attach` 的 `chain.proceed()` **之前**
         * （[TinkerHotUpdateBlocker.install] / [cleanupPatchFiles]）被调用，此时 `AppContext` 尚未就绪、
         * 正常 SP 通道 [sp] 不可用。此时直接从微信 data 目录下的
         * `shared_prefs/mask_wechat_config.xml` 读盘（REQ-P0-2 / Open Q5 取舍：多用户回退默认分区）。
         *
         * @return 默认 true（REQ-P1-3：默认开启拦截）
         */
        @JvmStatic
        fun isHotUpdateBlockEnabled(): Boolean {
            val ctx = AppContext.context
            return if (ctx != null) {
                // AppContext 已就绪（attach 之后 / 模块自身进程）：走正常缓存通道
                getOptionData().blockHotUpdate
            } else {
                // AppContext 未就绪（onPackageReady 早于 attach）：直接读盘，默认 true
                readBooleanOptionFromDisk("blockHotUpdate", true)
            }
        }

        /**
         * 从微信 data 目录的 SP 文件直接读取布尔配置（用于 AppContext 未就绪场景）。
         * 解析 `shared_prefs/mask_wechat_config.xml` 中 `<boolean name="key" value="..."/>`。
         */
        @JvmStatic
        private fun readBooleanOptionFromDisk(key: String, default: Boolean): Boolean {
            return try {
                val base = WechatPaths.getWechatDataDir()
                val spFile = java.io.File(java.io.File(base, "shared_prefs"), "mask_wechat_config.xml")
                if (!spFile.isFile) return default
                val text = spFile.readText()
                val regex = Regex("""<boolean\s+name="$key"\s+value="(true|false)"\s*/>""")
                val match = regex.find(text)
                if (match != null) match.groupValues[1] == "true" else default
            } catch (e: Throwable) {
                AppLog.w("ConfigUtil: readBooleanOptionFromDisk fail for $key", e)
                default
            }
        }

        /**
         * 首启强提醒是否已确认（写入 `blockHotUpdate_reminder_acked` 标记）。
         * 仅模块自身进程（UI）使用，AppContext 须已就绪。
         */
        @JvmStatic
        fun isHotUpdateReminderAcked(): Boolean = try {
            sp.getBoolean(KEY_HOTUPDATE_REMINDER_ACK, false)
        } catch (_: Throwable) { false }

        /**
         * 标记首启强提醒已确认。
         */
        @JvmStatic
        fun setHotUpdateReminderAcked() {
            try {
                sp.edit().putBoolean(KEY_HOTUPDATE_REMINDER_ACK, true).apply()
            } catch (_: Throwable) { }
        }
    }

    fun interface ConfigSetObserver {
        fun onConfigChange()
    }
}
