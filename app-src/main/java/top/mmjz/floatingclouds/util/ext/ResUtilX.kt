
package top.mmjz.floatingclouds.util.ext
import top.mmjz.floatingclouds.util.AppContext

import android.content.Context
import top.mmjz.floatingclouds.App

/**
 * View ID 缓存查找工具（替代 com.lu.magic.util.ResUtil.getViewId 扩展）。
 */
private val resMap = HashMap<String, Int>()

@JvmOverloads
fun getViewId(
    idName: String,
    context: Context = AppContext.context!!
): Int {
    val packageName = context.packageName
    val k = "@id/$idName"
    var id = resMap[k]
    if (id == null) {
        id = context.resources.getIdentifier(idName, "id", packageName)
        resMap[k] = id
    }
    return id
}

/**
 * 兼容旧 ResUtilX.kt 的 ResUtil.getViewId 调用。
 * 旧代码：ResUtil.getViewId(idName, packageName? → 改为：getViewId(idName)
 */
@JvmOverloads
fun getViewIdLegacy(
    idName: String,
    packageName: String? = null,
    context: Context = AppContext.context!!
): Int {
    val pkg = packageName ?: context.packageName
    return context.resources.getIdentifier(idName, "id", pkg)
}
