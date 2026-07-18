package top.mmjz.floatingclouds.plugin.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Build
import android.os.Vibrator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import top.mmjz.floatingclouds.bean.MaskItemBean
import top.mmjz.floatingclouds.bean.OptionData
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.plugin.part.HideContactListPluginPart
import top.mmjz.floatingclouds.plugin.part.HideMainUIListPluginPart
import top.mmjz.floatingclouds.plugin.part.HideOwnSnsPluginPart
import top.mmjz.floatingclouds.plugin.part.MaskedMsgVibratePluginPart

class MaskManagerCenterUI(private val activity: Activity) {

    private val c = C.from(activity)
    private val maskList: List<MaskItemBean> get() = ConfigUtil.getMaskList()
    private var dialog: android.app.AlertDialog? = null

    fun show() {
        val root = ScrollView(activity).apply {
            background = GradientDrawable().apply {
                setColor(c.pageBg); cornerRadius = dp(16).toFloat()
            }
            setPadding(0, 0, 0, dp(12))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(36))
        }

        val opt = ConfigUtil.getOptionData()

        // ═══════════ 品牌标识 ═══════════
        container.addView(TextView(activity).apply {
            text = "@mmjz"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(c.accent)
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8))
        })

        // ═══════════ 群组 ═══════════
        container.addView(TextView(activity).apply {
            text = "https://t.me/tkwx123"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(c.accent); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12)); setOnClickListener {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://t.me/tkwx123"))
                activity.startActivity(i)
            }
        })

        // ═══════════ 总开关 ═══════════
        container.addView(sectionHeader("总开关"))
        container.addView(switchRow("启用模块", "关闭后所有功能均不生效，开启后下方配置才生效", opt.masterEnabled) {
            opt.masterEnabled = it; save(opt)
        })
        container.addView(arrowRow("配置名单") { openMaskBlacklist() })

        // ═══════════ 搜索 ═══════════
        container.addView(sectionHeader("搜索"))
        container.addView(switchRow("隐藏主页搜索", "主页搜索不显示密友结果", opt.hideMainSearch) {
            opt.hideMainSearch = it; save(opt)
        })
        container.addView(switchRow("隐藏单聊搜索", "单聊内搜索不显示密友结果", opt.hideSingleSearch) {
            opt.hideSingleSearch = it; save(opt)
        })

        // ═══════════ 列表隐藏 ═══════════
        container.addView(sectionHeader("列表隐藏"))
        container.addView(switchRow("不显示该聊天", "微信原生机制，独立于总开关，默认开启。开启后密友写入微信DB隐藏状态，关闭总开关/重启均兜底生效；仅临时解除才显示", opt.hideConversation) {
            opt.hideConversation = it; save(opt)
            // ★ 立即同步原生隐藏状态（独立于总开关）：开启即隐藏，关闭即解除
            HideMainUIListPluginPart.setNativeHideForAll(it)
        })
        container.addView(switchRow("主页会话隐藏", "需手动开启，隐藏主页会话中的密友", opt.hideMainConvList) {
            opt.hideMainConvList = it; save(opt)
        })
        container.addView(switchRow("通讯录隐藏", "隐藏通讯录列表及标签中的密友(含预览)", opt.hideContactList) {
            opt.hideContactList = it; save(opt)
            // ★ 立即按当前开关重算并刷新通讯录（开关独立可控，切到关闭立刻恢复显示）
            HideContactListPluginPart.refresh()
        })

        // ═══════════ 访问拦截 ═══════════
        container.addView(sectionHeader("访问拦截"))
        container.addView(switchRow("禁止进入聊天", "需手动开启，禁止进入密友聊天界面", opt.blockEnterChat) {
            opt.blockEnterChat = it; save(opt)
        })
        container.addView(switchRow("禁止查看资料", "需手动开启，禁止进入联系人资料页", opt.blockContactInfo) {
            opt.blockContactInfo = it; save(opt)
        })

        // ═══════════ 社交隐藏 ═══════════
        container.addView(sectionHeader("社交隐藏"))
        container.addView(switchRow("隐藏朋友圈入口", "隐藏密友朋友圈入口及时间线内容", opt.hideSnsEntry) {
            opt.hideSnsEntry = it; save(opt)
        })
        container.addView(switchRow("隐藏最近转发", "最近转发列表不显示密友", opt.hideRecentForward) {
            opt.hideRecentForward = it; save(opt)
        })
        container.addView(switchRow("隐藏我的朋友圈", "按需隐藏自己的朋友圈", opt.hideOwnSns) {
            opt.hideOwnSns = it; save(opt)
        })
        container.addView(switchRow("隐藏提示弹窗", "长按自己朋友圈时弹出「加入隐藏」提示框，关闭则长按不弹", opt.showOwnSnsHideDialog) {
            opt.showOwnSnsHideDialog = it; save(opt)
        })
        container.addView(switchRow("隐藏朋友圈互动", "隐藏密友的点赞/评论提示及内容", opt.hideSnsInteraction) {
            opt.hideSnsInteraction = it; save(opt)
        })
        container.addView(switchRow("隐藏朋友圈分组图标", "隐藏密友朋友圈的分组图标标记", opt.hideSnsGroupIcon) {
            opt.hideSnsGroupIcon = it; save(opt)
        })
        container.addView(arrowRow("管理已隐藏的朋友圈") { manageHiddenOwnSns() })
        container.addView(arrowRow("隐藏朋友圈教程") { showSnsIdHelpDialog() })

        // ═══════════ 拦截与通知 ═══════════
        container.addView(sectionHeader("拦截与通知"))
        container.addView(switchRow("阻止电话请求", "拒绝密友的电话联系", opt.blockVoipCall) {
            opt.blockVoipCall = it; save(opt)
        })
        container.addView(switchRow("密友消息震动", "隐藏状态下收到密友消息时手机震动提示（与微信免打扰互补，不冲突）", opt.vibrateOnMaskedMessage) {
            opt.vibrateOnMaskedMessage = it; save(opt)
        })
        // 震动强度（百分比控制振幅）：仅在总开关开启时有意义
        container.addView(seekBarRow("震动强度", "控制震动轻微程度（0=极轻/短促，100=较强/持久，默认 60）", opt.vibrateIntensity,
            onProgress = { pct -> opt.vibrateIntensity = pct; save(opt) },
            testAction = { MaskedMsgVibratePluginPart.previewVibrate(opt.vibrateIntensity) }))
        // 设备不支持振幅控制时给出提示（仍可按总开关震动，只是强度固定）
        val ampSupported = run {
            val v = activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            v != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || v.hasAmplitudeControl())
        }
        if (!ampSupported) {
            container.addView(TextView(activity).apply {
                text = "⚠ 本设备不支持振幅调节，强度滑块无效（将使用固定强度）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(c.danger); setPadding(dp(6), dp(2), dp(6), dp(6))
            })
        }
        container.addView(switchRow("拦截扫码登录", "拦截扫码登录确认弹窗", opt.blockScanLogin) {
            opt.blockScanLogin = it; save(opt)
        })
        container.addView(switchRow("屏蔽微信热更新", "阻止微信通过Tinker下载/应用热补丁，防止插件Hook失效", opt.blockHotUpdate) { enabled ->
            opt.blockHotUpdate = enabled
            save(opt)
            // 首次开启时弹出强提醒（代价/收益），确认后写 ack 标记（REQ-P1-3 / US-4）
            if (enabled && !ConfigUtil.isHotUpdateReminderAcked()) {
                showHotUpdateReminder()
            }
        })

        // ═══════════ 存储 ═══════════
        container.addView(sectionHeader("存储"))
        container.addView(switchRow("隐藏聊天记录入口", "隐藏存储空间中的密友聊天记录", opt.hideStorageChatRecordEntry) {
            opt.hideStorageChatRecordEntry = it; save(opt)
        })

        // ═══════════ 刻舟求剑 - 临时解除 ═══════════
        container.addView(sectionHeader("刻舟求剑 - 临时解除"))
        container.addView(switchRow("多击标题解除", "多击微信标题临时解除会话隐藏", opt.enableMultiClickTempUnhide) {
            opt.enableMultiClickTempUnhide = it; save(opt)
        })
        container.addView(inputRow("多击次数", opt.multiClickCount.toString(), InputType.TYPE_CLASS_NUMBER) { text ->
            opt.multiClickCount = text.toIntOrNull() ?: 3; save(opt)
        })
        container.addView(inputRow("多击间隔（毫秒）", opt.multiClickInterval.toString(), InputType.TYPE_CLASS_NUMBER) { text ->
            opt.multiClickInterval = text.toIntOrNull() ?: 500; save(opt)
        })
        container.addView(switchRow("长按标题解除", "长按微信标题临时解除隐藏", opt.enableLongPressTempUnhide) {
            opt.enableLongPressTempUnhide = it; save(opt)
        })
        container.addView(inputRow("长按触发时长（毫秒）", opt.longPressDuration.toString(), InputType.TYPE_CLASS_NUMBER) { text ->
            opt.longPressDuration = text.toIntOrNull() ?: 800; save(opt)
        })

        // ═══════════ 恢复隐藏 ═══════════
        container.addView(sectionHeader("恢复隐藏"))
        container.addView(switchRow("离开对话隐藏", "进入密友对话后返回主页，立即恢复隐藏", opt.rehideOnLeaveChat) {
            opt.rehideOnLeaveChat = it; save(opt)
        })
        container.addView(switchRow("离开微信隐藏", "按HOME离开微信后立即恢复隐藏", opt.rehideOnLeaveApp) {
            opt.rehideOnLeaveApp = it; save(opt)
        })

        // ═══════════ 添加密友 ═══════════
        container.addView(sectionHeader("添加密友"))
        container.addView(switchRow("会话列表长按添加", "主页会话列表长按弹出加入密友菜单", opt.enableLongPressAddMask) {
            opt.enableLongPressAddMask = it; save(opt)
        })
        container.addView(switchRow("通讯录长按添加", "通讯录列表长按弹出加入密友菜单", opt.enableContactLongPressAddMask) {
            opt.enableContactLongPressAddMask = it; save(opt)
        })
        container.addView(inputRow("菜单显示文字", opt.addMaskMenuText, InputType.TYPE_CLASS_TEXT) { text ->
            opt.addMaskMenuText = text; save(opt)
        })

        // ═══════════ 搜索指令 ═══════════
        container.addView(sectionHeader("搜索指令"))
        container.addView(inputRow("打开设置指令", opt.cmdOpenSettings, InputType.TYPE_CLASS_TEXT) { text ->
            opt.cmdOpenSettings = text; save(opt)
        })
        container.addView(inputRow("临时解除指令", opt.cmdTempUnhide, InputType.TYPE_CLASS_TEXT) { text ->
            opt.cmdTempUnhide = text; save(opt)
        })

        // ═══════════ 适配扫描 ═══════════
        container.addView(sectionHeader("适配扫描"))
        container.addView(arrowRow("⚡ DexKit 扫描微信类名") {
            startDexKitScan()
        })
        val cacheReady = top.mmjz.floatingclouds.util.DexKitCache.isReady()
        container.addView(TextView(activity).apply {
            text = if (cacheReady) "✅ 缓存已就绪，Hook点已自动适配" else "⚠ 缓存未就绪，部分功能可能失效"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (cacheReady) Color.parseColor("#4CAF50") else c.danger)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        })

        // ═══════════ 开发者 ═══════════
        container.addView(sectionHeader("开发者"))
        container.addView(switchRow("数据库", "开启后缓存微信数据库，可反查好友昵称/备注（开发者用）", opt.viewWxDbPw) {
            opt.viewWxDbPw = it; save(opt)
        })
        container.addView(dangerRow("清空所有配置") {
            android.app.AlertDialog.Builder(activity)
                .setTitle("确认清空").setMessage("清空后微信将自动重启，所有伪装和设置恢复默认。")
                .setPositiveButton("确认清空并重启") { _, _ ->
                    ConfigUtil.clearData()
                    dialog?.dismiss()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .setNegativeButton("取消", null)
                .create()?.also { dlg ->
                    dlg.window?.apply {
                        setBackgroundDrawable(GradientDrawable().apply {
                            setColor(c.pageBg); cornerRadius = dp(16).toFloat()
                        })
                    }
                    dlg.show()
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(c.danger)
                }
        })
        container.addView(arrowRow("🔄 重启微信") { confirmRestartWeChat() })


        root.addView(container)

        dialog = android.app.AlertDialog.Builder(activity)
            .setView(root).create()?.also { dlg ->
                dlg.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                }
                dlg.setCanceledOnTouchOutside(true)
                dlg.show()
                // Set dimensions AFTER show() — AlertDialog resets them during show
                dlg.window?.apply {
                    val dm = activity.resources.displayMetrics
                    val w = (dm.widthPixels * 0.94).toInt()
                    val h = (dm.heightPixels * 0.82).toInt()
                    val lp = attributes
                    lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    attributes = lp
                }
            }
    }

    private fun save(opt: OptionData) {
        android.util.Log.d("Floatingclouds_Config",
            "UI save BEFORE: masterEnabled=${opt.masterEnabled} hideMainConvList=${opt.hideMainConvList} " +
            "blockEnterChat=${opt.blockEnterChat} hideContactList=${opt.hideContactList}")
        ConfigUtil.setOptionData(opt)
        // ★ 强制刷新缓存，确保从磁盘读取已保存的值
        ConfigUtil.invalidateCache()
        // ★ 立即回读验证
        val verify = ConfigUtil.getOptionData()
        android.util.Log.d("Floatingclouds_Config",
            "UI save AFTER verify: masterEnabled=${verify.masterEnabled} hideMainConvList=${verify.hideMainConvList} " +
            "blockEnterChat=${verify.blockEnterChat}")
        ConfigUtil.dumpSpContent("Floatingclouds_Config")
    }

    /**
     * 首次开启「屏蔽微信热更新」时的强提醒弹窗（REQ-P1-3 / US-4）。
     * 说明代价（无法热修复/需手动更新微信）与收益（隐私 Hook 长期稳定），
     * 确认后写入 `blockHotUpdate_reminder_acked` 标记，避免重复打扰。
     * 若用户选择"关闭此功能"，则回退开关并重新渲染 UI。
     */
    private fun showHotUpdateReminder() {
        android.app.AlertDialog.Builder(activity)
            .setTitle("屏蔽微信热更新")
            .setMessage(
                "开启后，微信收到的热更新补丁将被拦截：\n\n" +
                "• 代价：微信无法通过热补丁自修复 bug、接收官方小更新，需手动更新微信版本；\n" +
                "• 收益：已适配的隐私 Hook 点（如密友隐藏）长期稳定，不会因微信热补丁悄然重现。"
            )
            .setPositiveButton("我已知晓") { _, _ ->
                ConfigUtil.setHotUpdateReminderAcked()
            }
            .setNegativeButton("关闭此功能") { _, _ ->
                ConfigUtil.setHotUpdateReminderAcked()
                val latest = ConfigUtil.getOptionData()
                latest.blockHotUpdate = false
                save(latest)
                dialog?.dismiss(); show()
            }
            .setCancelable(false)
            .create()?.also { dlg ->
                dlg.window?.apply {
                    setBackgroundDrawable(GradientDrawable().apply {
                        setColor(c.pageBg); cornerRadius = dp(16).toFloat()
                    })
                }
                dlg.show()
            }
    }

    // ═══════════ Views ═══════════

    private fun sectionHeader(title: String): TextView {
        return TextView(activity).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(c.textSecondary)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
    }

    private fun switchRow(label: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply {
                setColor(c.surface); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(c.textPrimary); setSingleLine(true)
        })
        textCol.addView(TextView(activity).apply {
            text = desc; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(c.textSecondary)
        })
        val sw = Switch(activity).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun inputRow(label: String, value: String, inputType: Int, onChange: (String) -> Unit): View {
        var saved = value
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(c.surface); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
        }
        // Title row: label + save button
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val saveBtn = TextView(activity).apply {
            text = "保存"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(c.surface); gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(c.accent); cornerRadius = dp(6).toFloat()
            }
        }
        titleRow.addView(saveBtn); row.addView(titleRow)

        val input = EditText(activity).apply {
            setText(value); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.textPrimary); setHintTextColor(c.textTertiary)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(c.pageBg); cornerRadius = dp(8).toFloat()
            }
            setSingleLine(true); this.inputType = inputType
        }
        row.addView(input)

        saveBtn.setOnClickListener {
            val text = input.text.toString()
            android.util.Log.d("MaskUI", "saveBtn: input.text='$text', label='$label'")
            saved = text
            onChange(saved)
            saveBtn.text = "已保存"
            saveBtn.postDelayed({ saveBtn.text = "保存" }, 1500)
            Toast.makeText(activity, "保存成功", Toast.LENGTH_SHORT).show()
        }
        return row
    }

    /**
     * 强度滑块行：标题 + 实时百分比 + 可选「测试」按钮 + 描述 + SeekBar(0–100)。
     * 拖动时通过 onProgress 回传百分比（UI 负责保存与预览）。
     */
    private fun seekBarRow(label: String, desc: String, initialPct: Int, onProgress: (Int) -> Unit, testAction: (() -> Unit)? = null): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(c.surface); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(2), 0, dp(2)) }
        }
        // 标题行：label + 百分比 + 可选测试按钮
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.textPrimary); layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val pctText = TextView(activity).apply {
            text = "$initialPct%"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(c.accent); gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(4), 0)
        }
        titleRow.addView(pctText)
        if (testAction != null) {
            val testBtn = TextView(activity).apply {
                text = "测试"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(c.surface); gravity = Gravity.CENTER
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = GradientDrawable().apply { setColor(c.accent); cornerRadius = dp(6).toFloat() }
                setOnClickListener { testAction() }
            }
            titleRow.addView(testBtn)
        }
        row.addView(titleRow)
        // 描述
        row.addView(TextView(activity).apply {
            text = desc; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(c.textSecondary); setPadding(0, dp(4), 0, dp(6))
        })
        // 滑块
        val sb = SeekBar(activity).apply { max = 100; progress = initialPct.coerceIn(0, 100) }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                pctText.text = "$p%"
                if (fromUser) onProgress(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        row.addView(sb)
        return row
    }

    private fun blacklistItem(item: MaskItemBean, onChange: () -> Unit = { dialog?.dismiss(); show() }): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(c.surface); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
        }
        // 标题行：昵称/备注 + 自定义标签
        val displayName = getContactDisplayName(item.maskId)
        val title = if (displayName != null) {
            if (item.tagName.isNotEmpty()) "${displayName}（${item.tagName}）" else displayName
        } else {
            item.tagName.ifEmpty { item.maskId }
        }
        row.addView(TextView(activity).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(c.textPrimary)
        })
        // 微信原始备注
        if (displayName != null) {
            row.addView(TextView(activity).apply {
                text = "备注: ${displayName}"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(c.accent); setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(TextView(activity).apply {
            text = "伪装ID: ${hide(item.maskId)}\n映射ID: ${item.mapId}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(c.textSecondary)
            setPadding(0, dp(2), 0, dp(6))
        })
        row.addView(TextView(activity).apply {
            text = "移除伪装"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(c.danger); gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setStroke(1, c.danger); cornerRadius = dp(6).toFloat()
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener {
                ConfigUtil.removeMaskItem(item.maskId)
                onChange()
            }
        })
        return row
    }

    private fun clearAllButton(onChange: () -> Unit = { dialog?.dismiss(); show() }): View {
        return TextView(activity).apply {
            text = "一键清空全部伪装（${maskList.size} 个）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.danger); gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply {
                setStroke(1, c.danger); cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener {
                android.app.AlertDialog.Builder(activity)
                    .setTitle("确认清空").setMessage("将删除全部 ${maskList.size} 个伪装，不可撤销。")
                    .setPositiveButton("确认清空") { _, _ ->
                        ConfigUtil.setMaskList(emptyList())
                        onChange()
                    }
                    .setNegativeButton("取消", null)
                    .create()?.also { dlg ->
                        dlg.window?.apply {
                            setBackgroundDrawable(GradientDrawable().apply {
                                setColor(c.pageBg); cornerRadius = dp(16).toFloat()
                            })
                        }
                        dlg.show()
                        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(c.danger)
                    }
            }
        }
    }

    // ─── 二级界面：配置名单管理 ───

    /**
     * 二级界面：以与主面板一致的风格展示全部配置名单（密友卡片 + 一键清空）。
     * 单项移除 / 清空后关闭本二级弹窗并重新打开，保证数据实时刷新。
     */
    private fun openMaskBlacklist() {
        val items = ConfigUtil.getMaskList()
        val root = ScrollView(activity).apply {
            background = GradientDrawable().apply {
                setColor(c.pageBg); cornerRadius = dp(16).toFloat()
            }
            setPadding(0, 0, 0, dp(12))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(36))
        }
        container.addView(sectionHeader("配置名单（${items.size} 个）"))
        if (items.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "暂无伪装"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(c.textSecondary); gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            })
        }
        root.addView(container)

        var subDialog: android.app.AlertDialog? = null
        // 重新打开本二级界面的刷新回调（单项移除 / 清空后调用）
        val refresh: () -> Unit = { subDialog?.dismiss(); openMaskBlacklist() }
        if (!items.isEmpty()) {
            items.forEach { item -> container.addView(blacklistItem(item, onChange = refresh)) }
            container.addView(space(dp(12)))
            container.addView(clearAllButton(onChange = refresh))
        }
        subDialog = android.app.AlertDialog.Builder(activity)
            .setView(root).create()?.also { dlg ->
                dlg.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                }
                dlg.setCanceledOnTouchOutside(true)
                dlg.show()
                dlg.window?.apply {
                    val dm = activity.resources.displayMetrics
                    val w = (dm.widthPixels * 0.94).toInt()
                    val h = (dm.heightPixels * 0.82).toInt()
                    val lp = attributes
                    lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    attributes = lp
                }
            }
    }

    private fun divider(h: Int, col: Int) = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, h)
        setBackgroundColor(col)
    }

    private fun space(h: Int) = View(activity).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH, h)
    }

    // ─── Arrow row (clickable item with right arrow) ───

    private fun arrowRow(label: String, onClick: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(c.surface); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(2), 0, dp(2)) }
            addView(TextView(activity).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(c.textPrimary); setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(TextView(activity).apply {
                text = ">"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(c.textSecondary); gravity = Gravity.CENTER
            })
            setOnClickListener { onClick() }
        }
    }

    // ─── Danger row ───

    private fun dangerRow(label: String, onClick: () -> Unit): View {
        return TextView(activity).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.danger); gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setStroke(1, c.danger); cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) }
            setOnClickListener { onClick() }
        }
    }

    // ─── Hidden SNS management ───

    private fun manageHiddenOwnSns() {
        val hiddenIds = ConfigUtil.getHiddenOwnSnsIds().toMutableList()
        val items = hiddenIds.map { it.toString() }.toTypedArray<CharSequence>()
        var mgmtDialog: android.app.AlertDialog? = null
        val builder = android.app.AlertDialog.Builder(activity)
            .setTitle("已隐藏的朋友圈 snsId（共 ${hiddenIds.size} 条）")
            .apply {
                if (items.isNotEmpty()) {
                    setItems(items) { _, which ->
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("移除 ${hiddenIds[which]}？")
                            .setPositiveButton("移除") { _, _ ->
                                ConfigUtil.removeHiddenOwnSnsId(hiddenIds[which])
                                Toast.makeText(activity, "已移除", Toast.LENGTH_SHORT).show()
                                mgmtDialog?.dismiss(); manageHiddenOwnSns()
                            }
                            .setNegativeButton("取消", null).show()
                    }
                    // 列表非空时提供「清空」入口（二次确认，不可撤销）
                    setPositiveButton("清空") { _, _ ->
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("确认清空")
                            .setMessage("将清空全部 ${hiddenIds.size} 条已隐藏的朋友圈，清空后这些朋友圈会重新显示，操作不可撤销。")
                            .setPositiveButton("确认清空") { _, _ ->
                                val n = ConfigUtil.clearHiddenOwnSnsIds()
                                Toast.makeText(activity, "已清空 $n 条，四端已即时刷新", Toast.LENGTH_SHORT).show()
                                HideOwnSnsPluginPart.requestSelfRefresh()
                                HideOwnSnsPluginPart.requestProfileRefresh()
                                HideOwnSnsPluginPart.requestTimelineRefresh()
                                HideOwnSnsPluginPart.requestAlbumRefresh()
                                mgmtDialog?.dismiss(); manageHiddenOwnSns()
                            }
                            .setNegativeButton("取消", null).show()
                    }
                } else setMessage("暂无已隐藏的朋友圈")
            }
            .setNeutralButton("添加") { _, _ ->
                val edit = EditText(activity).apply { hint = "输入朋友圈 snsId" }
                android.app.AlertDialog.Builder(activity)
                    .setTitle("添加要隐藏的朋友圈")
                    .setView(edit)
                    .setPositiveButton("添加") { _, _ ->
                        val id = edit.text.toString().trim()
                        if (id.isNotBlank()) {
                            ConfigUtil.addHiddenOwnSnsId(id)
                            Toast.makeText(activity, "刻舟求剑", Toast.LENGTH_SHORT).show()
                            HideOwnSnsPluginPart.requestSelfRefresh()
                            HideOwnSnsPluginPart.requestProfileRefresh()
                            HideOwnSnsPluginPart.requestTimelineRefresh()
                            HideOwnSnsPluginPart.requestAlbumRefresh()
                        }
                    }
                    .setNegativeButton("取消", null).show()
            }
            .setNegativeButton("关闭", null)
        mgmtDialog = builder.show()
    }

    private fun showSnsIdHelpDialog() {
        android.app.AlertDialog.Builder(activity)
            .setTitle("隐藏朋友圈教程")
            .setMessage(
                "1. 先开启上方「隐藏我的朋友圈」开关。\n" +
                "2. 进入微信「我」→「朋友圈」→ 打开自己的朋友圈相册。\n" +
                "3. 长按任意一条自己朋友圈顶部「详情」二字 2 秒，弹出 snsId 弹窗。\n" +
                "4. 在弹框中点击「加入隐藏」或「复制」snsId。\n\n" +
                "提示：添加隐藏后该条朋友圈对他人不可见，自己仍可长按查看/管理。\n" +
                "也可在「管理已隐藏的朋友圈」中手动输入 snsId 添加。"
            )
            .setPositiveButton("知道了", null).show()
    }

    private fun getNavBarHeight(): Int {
        val id = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    private fun hide(s: String) = if (s.length <= 10) s else "${s.take(8)}…${s.takeLast(4)}"

    /** 通过微信联系人数据库反查好友备注/昵称（委托给 WxSQLiteManager 缓存） */
    private fun getContactDisplayName(wxid: String): String? {
        return top.mmjz.floatingclouds.util.WxSQLiteManager.getDisplayName(wxid)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    // ═══════════ Colors ═══════════

    class C(
        val pageBg: Int, val surface: Int, val divider: Int,
        val textPrimary: Int, val textSecondary: Int, val textTertiary: Int,
        val accent: Int, val danger: Int
    ) {
        companion object {
            fun from(ctx: android.content.Context): C {
                val d = (ctx.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return if (d) C(
                    pageBg = Color.parseColor("#111111"), surface = Color.parseColor("#1E1E1E"),
                    divider = Color.parseColor("#2C2C2C"),
                    textPrimary = Color.parseColor("#E5E5E5"), textSecondary = Color.parseColor("#A0A0A0"),
                    textTertiary = Color.parseColor("#666666"),
                    accent = Color.parseColor("#7D9EC8"), danger = Color.parseColor("#FA5151")
                ) else C(
                    pageBg = Color.parseColor("#EDEDED"), surface = Color.parseColor("#FFFFFF"),
                    divider = Color.parseColor("#DDDDDD"),
                    textPrimary = Color.parseColor("#191919"), textSecondary = Color.parseColor("#888888"),
                    textTertiary = Color.parseColor("#B0B0B0"),
                    accent = Color.parseColor("#576B95"), danger = Color.parseColor("#FA5151")
                )
            }
        }
    }

    // ═══════════ DexKit Scan ═══════════

    private fun startDexKitScan() {
        try {
            val appInfo = activity.packageManager.getApplicationInfo("com.tencent.mm", 0)
            val apkPath = appInfo.sourceDir

            val scanner = top.mmjz.floatingclouds.util.DexKitScanner(activity, apkPath)

            // 显示进度对话框
            val progressLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(16))
            }
            val progressText = TextView(activity).apply {
                text = "准备扫描..."; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(c.textPrimary); setPadding(0, 0, 0, dp(8))
            }
            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = scanner.totalWeight; progress = 0
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(6))
            }
            val percentText = TextView(activity).apply {
                text = "0%"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(c.textSecondary); gravity = Gravity.END
            }
            progressLayout.addView(progressText)
            progressLayout.addView(progressBar)
            progressLayout.addView(percentText)

            val scanDialog = android.app.AlertDialog.Builder(activity)
                .setTitle("DexKit 扫描中")
                .setView(progressLayout)
                .setNegativeButton("取消") { _, _ -> }
                .setCancelable(false)
                .create()?.also { dlg ->
                    dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
                        setColor(c.pageBg); cornerRadius = dp(16).toFloat()
                    })
                    dlg.show()
                }

            // 在后台线程执行扫描
            Thread {
                val success = scanner.scanAll { percent, taskName ->
                    activity.runOnUiThread {
                        progressText.text = taskName
                        progressBar.progress = percent * scanner.totalWeight / 100
                        percentText.text = "${percent}%"
                    }
                }

                activity.runOnUiThread {
                    scanDialog?.dismiss()
                    if (success) {
                        Toast.makeText(activity, "扫描完成！请重启微信生效", Toast.LENGTH_LONG).show()
                    } else {
                        val err = top.mmjz.floatingclouds.util.DexKitScanner.lastError ?: "未知错误"
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("扫描失败")
                            .setMessage(err + "\n\n将使用已有缓存或硬编码候选类名。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                    dialog?.dismiss(); show() // 刷新 UI 显示缓存状态
                }
            }.start()

        } catch (e: Exception) {
            Toast.makeText(activity, "扫描启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRestartWeChat() {
        android.app.AlertDialog.Builder(activity)
            .setTitle("重启微信")
            .setMessage("将关闭并重新启动微信，重启后配置与 Hook 点立即生效。")
            .setPositiveButton("立即重启") { _, _ -> restartWeChat() }
            .setNegativeButton("取消", null)
            .create()?.also { dlg ->
                dlg.window?.apply {
                    setBackgroundDrawable(GradientDrawable().apply {
                        setColor(c.pageBg); cornerRadius = dp(16).toFloat()
                    })
                }
                dlg.show()
            }
    }

    /**
     * 重启微信：
     * ① 优先 root：用参数数组形式执行 `su -c "am force-stop ... && am start ..."`
     *    （数组形式避免 shell 引号解析问题，KernelSU 下更稳）。
     * ② 回退：当前进程（微信）内无法"先 start 再自杀"——自杀会连带作废待启动的 intent。
     *    改用 AlarmManager 在 1s 后由 system_server 拉起微信 LauncherUI（pendingIntent
     *    存活于 AMS，进程死后再触发），随后 kill 自身 PID 完成重启。
     * 全程异常兜底，失败给出 Toast。
     */
    private fun restartWeChat() {
        Toast.makeText(activity, "正在重启微信…", Toast.LENGTH_SHORT).show()
        dialog?.dismiss()
        try {
            val rooted = tryRootRestart()
            if (rooted) return
            // 回退：AlarmManager 定时拉起 + 自杀
            scheduleRestartViaAlarm()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 800)
        } catch (e: Exception) {
            Toast.makeText(activity, "重启失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleRestartViaAlarm() {
        val ctx = activity.applicationContext
        val launch = android.content.Intent().apply {
            setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, 0, launch,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val trigger = android.os.SystemClock.elapsedRealtime() + 1000L
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
    }

    private fun tryRootRestart(): Boolean {
        return try {
            val script = "am force-stop com.tencent.mm && am start -n com.tencent.mm/.ui.LauncherUI"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            // 读取错误流，避免子进程阻塞；不依赖输出内容
            val err = p.errorStream.bufferedReader().readText()
            val exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val ok = exited && p.exitValue() == 0
            if (!ok) {
                android.util.Log.d("MaskUI", "tryRootRestart exit=${if (exited) p.exitValue() else "timeout"} err=$err")
            }
            ok
        } catch (e: Exception) {
            android.util.Log.d("MaskUI", "tryRootRestart exception: ${e.message}")
            false
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
