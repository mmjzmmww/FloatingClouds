package top.mmjz.floatingclouds.bean

import org.json.JSONObject


class OptionData
/**
 * @param hideMainSearch 主页搜索隐藏（默认开启）
 * @param hideSingleSearch 单聊搜索隐藏（默认开启）
 * @param hideMainSearchStrong 主页搜索，暴力隐藏（已废弃，默认关闭）
 * @param viewWxDbPw 查看微信数据库密码（默认开启）
 * @param hideStorageChatRecordEntry 隐藏存储空间聊天记录入口（默认开启）
 * @param hideSnsEntry 隐藏密友朋友圈入口及朋友圈时间线中密友内容（默认开启）
 * @param hideRecentForward 隐藏最近转发中的密友（默认开启）
 * @param hideOwnSns 隐藏我的朋友圈（默认开启）
 * @param showOwnSnsHideDialog 隐藏我的朋友圈时长按弹出的隐藏提示框（默认开启，关闭则长按不弹提示）
 * @param hideMainConvList 主页会话列表隐藏密友（默认开启）
 * @param hideContactList 通讯录隐藏密友（默认开启）
 * @param hideConversation 不显示该聊天（微信原生机制，独立于总开关，默认开启；开启后密友写入微信DB隐藏状态，关总开关/重启均兜底生效）
 * @param blockEnterChat 禁止进入密友聊天界面（默认开启）
 * @param blockContactInfo 禁止进入联系人资料页（默认开启）
 * @param enableMultiClickTempUnhide 多击微信标题临时解除主页会话隐藏（默认开启）
 * @param enableLongPressTempUnhide 长按微信标题临时解除（默认开启）
 * @param longPressDuration 长按触发时长（毫秒，默认800）
 * @param blockScanLogin 拦截扫码登录确认弹窗（默认开启）
 * @param blockHotUpdate 屏蔽微信热更新（Tinker补丁，默认开启）
 * @param vibrateIntensity 密友消息震动强度（0–100 百分比，默认 60；仅控制振幅，总开关 vibrateOnMaskedMessage 控制开关）
 */
private constructor(
    var hideMainSearch: Boolean,
    var hideSingleSearch: Boolean,
    var hideMainSearchStrong: Boolean,
    var viewWxDbPw: Boolean,
    var hideStorageChatRecordEntry: Boolean,
    var hideSnsEntry: Boolean,
    var hideRecentForward: Boolean,
    var hideOwnSns: Boolean,
    var showOwnSnsHideDialog: Boolean,
    var hideSnsInteraction: Boolean,
    var hideSnsGroupIcon: Boolean,
    var hideMainConvList: Boolean,
    var hideContactList: Boolean,
    var hideConversation: Boolean,
    var blockEnterChat: Boolean,
    var blockContactInfo: Boolean,
    var enableMultiClickTempUnhide: Boolean,
    var multiClickCount: Int,
    var multiClickInterval: Int,
    var enableLongPressTempUnhide: Boolean,
    var longPressDuration: Int,
    var blockScanLogin: Boolean,
    var enableLongPressAddMask: Boolean,
    var enableContactLongPressAddMask: Boolean,
    var addMaskMenuText: String,
            var blockVoipCall: Boolean,
            var vibrateOnMaskedMessage: Boolean,
            var vibrateIntensity: Int,
            var masterEnabled: Boolean,
    var blockHotUpdate: Boolean,
    var rehideOnLeaveChat: Boolean,
    var rehideOnLeaveApp: Boolean,
    var cmdOpenSettings: String,
    var cmdTempUnhide: String
) {


    companion object {
        fun fromJson(jsonText: String): OptionData {
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                JSONObject()
            }
            return OptionData(
                hideMainSearch = json.optBoolean("hideMainSearch", true),
                hideSingleSearch = json.optBoolean("hideSingleSearch", true),
                hideMainSearchStrong = json.optBoolean("hideMainSearchStrong", false),
                viewWxDbPw = json.optBoolean("viewWxDbPw", true),
                hideStorageChatRecordEntry = json.optBoolean("hideStorageChatRecordEntry", true),
                hideSnsEntry = json.optBoolean("hideSnsEntry", true),
                hideRecentForward = json.optBoolean("hideRecentForward", true),
                hideOwnSns = json.optBoolean("hideOwnSns", true),
                showOwnSnsHideDialog = json.optBoolean("showOwnSnsHideDialog", true),
                hideSnsInteraction = json.optBoolean("hideSnsInteraction", true),
                hideSnsGroupIcon = json.optBoolean("hideSnsGroupIcon", true),
                hideMainConvList = json.optBoolean("hideMainConvList", true),
                hideContactList = json.optBoolean("hideContactList", true),
                hideConversation = json.optBoolean("hideConversation", true),
                blockEnterChat = json.optBoolean("blockEnterChat", true),
                blockContactInfo = json.optBoolean("blockContactInfo", true),
                enableMultiClickTempUnhide = json.optBoolean("enableMultiClickTempUnhide", true),
                multiClickCount = json.optInt("multiClickCount", 3),
                multiClickInterval = json.optInt("multiClickInterval", 500),
                enableLongPressTempUnhide = json.optBoolean("enableLongPressTempUnhide", true),
                longPressDuration = json.optInt("longPressDuration", 800),
                blockScanLogin = json.optBoolean("blockScanLogin", true),
                enableLongPressAddMask = json.optBoolean("enableLongPressAddMask", true),
                enableContactLongPressAddMask = json.optBoolean("enableContactLongPressAddMask", true),
                addMaskMenuText = json.optString("addMaskMenuText", "加入密友"),
                blockVoipCall = json.optBoolean("blockVoipCall", true),
                vibrateOnMaskedMessage = json.optBoolean("vibrateOnMaskedMessage", true),
                vibrateIntensity = json.optInt("vibrateIntensity", 60),
                masterEnabled = json.optBoolean("masterEnabled", true),
                blockHotUpdate = json.optBoolean("blockHotUpdate", true),
                rehideOnLeaveChat = json.optBoolean("rehideOnLeaveChat", true),
                rehideOnLeaveApp = json.optBoolean("rehideOnLeaveApp", true),
                cmdOpenSettings = json.optString("cmdOpenSettings", "#jz#"),
                cmdTempUnhide = json.optString("cmdTempUnhide", "#mm#")
            )
        }
        fun toJson(data: OptionData): String {
            return JSONObject().apply {
                put("hideMainSearch", data.hideMainSearch)
                put("hideSingleSearch", data.hideSingleSearch)
                put("hideMainSearchStrong", data.hideMainSearchStrong)
                put("viewWxDbPw", data.viewWxDbPw)
                put("hideStorageChatRecordEntry", data.hideStorageChatRecordEntry)
                put("hideSnsEntry", data.hideSnsEntry)
                put("hideRecentForward", data.hideRecentForward)
                put("hideOwnSns", data.hideOwnSns)
                put("showOwnSnsHideDialog", data.showOwnSnsHideDialog)
                put("hideSnsInteraction", data.hideSnsInteraction)
                put("hideSnsGroupIcon", data.hideSnsGroupIcon)
                put("hideMainConvList", data.hideMainConvList)
                put("hideContactList", data.hideContactList)
                put("hideConversation", data.hideConversation)
                put("blockEnterChat", data.blockEnterChat)
                put("blockContactInfo", data.blockContactInfo)
                put("enableMultiClickTempUnhide", data.enableMultiClickTempUnhide)
                put("multiClickCount", data.multiClickCount)
                put("multiClickInterval", data.multiClickInterval)
                put("enableLongPressTempUnhide", data.enableLongPressTempUnhide)
                put("longPressDuration", data.longPressDuration)
                put("blockScanLogin", data.blockScanLogin)
                put("enableLongPressAddMask", data.enableLongPressAddMask)
                put("enableContactLongPressAddMask", data.enableContactLongPressAddMask)
                put("addMaskMenuText", data.addMaskMenuText)
                put("blockVoipCall", data.blockVoipCall)
                put("vibrateOnMaskedMessage", data.vibrateOnMaskedMessage)
                put("vibrateIntensity", data.vibrateIntensity)
                put("masterEnabled", data.masterEnabled)
                put("blockHotUpdate", data.blockHotUpdate)
                put("rehideOnLeaveChat", data.rehideOnLeaveChat)
                put("rehideOnLeaveApp", data.rehideOnLeaveApp)
                put("cmdOpenSettings", data.cmdOpenSettings)
put("cmdTempUnhide", data.cmdTempUnhide)
            }.toString()
        }

    }
}
