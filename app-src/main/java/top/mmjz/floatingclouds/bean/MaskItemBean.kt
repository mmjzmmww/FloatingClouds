package top.mmjz.floatingclouds.bean

import androidx.annotation.Keep
import top.mmjz.floatingclouds.Constrant
import org.json.JSONObject

@Keep
class MaskItemBean(
    var maskId: String,
    var tagName: String = "",
    var tipMode: Int = Constrant.WX_MASK_TIP_MODE_SILENT,
    var tipData: JSONObject? = JSONObject(),
    //伪装映射id
    // 文件传输助手：filehelper（所有微信账号都内置，最安全）
    // 订阅号：officialaccounts
    // 微信支付商家助手：gh_e087bb5b95e6
    // 微信团队：weixin
    var mapId: String = "filehelper"
) {

    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("maskId", maskId)
            put("tagName", tagName)
            put("tipMode", tipMode)
            put("tipData", tipData?.toString() ?: "{}")
            put("mapId", mapId)
        }
    }

    fun toJson(): String = toJSONObject().toString()

    companion object {
        fun fromJson(jsonText: String): MaskItemBean {
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                JSONObject()
            }
            return MaskItemBean(
                maskId = json.optString("maskId", ""),
                tagName = json.optString("tagName", ""),
                tipMode = json.optInt("tipMode", Constrant.WX_MASK_TIP_MODE_SILENT),
                tipData = try { JSONObject(json.optString("tipData", "{}")) } catch (e: Exception) { JSONObject() },
                mapId = json.optString("mapId", "filehelper")
            )
        }
    }

    @Keep
    class TipData(var mess: String = Constrant.WX_MASK_TIP_ALERT_MESS_DEFAULT) {
        companion object {
            @JvmStatic
            fun from(wrapper: MaskItemBean): TipData {
                return try {
                    val tipData = wrapper.tipData ?: return TipData()
                    val mess = tipData.optString("mess", "").ifEmpty {
                        Constrant.WX_MASK_TIP_ALERT_MESS_DEFAULT
                    }
                    TipData(mess)
                } catch (e: Exception) {
                    TipData()
                }
            }
        }
    }

}
