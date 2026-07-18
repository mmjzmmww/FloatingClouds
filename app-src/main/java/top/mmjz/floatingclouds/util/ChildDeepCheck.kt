package top.mmjz.floatingclouds.util

import android.view.View
import android.view.ViewGroup

/**
 * View 树深度遍历工具，替代 com.lu.magic.util.view.ChildDeepCheck。
 */
class ChildDeepCheck {
    fun filter(root: View, predicate: (View) -> Boolean): List<View> {
        val result = mutableListOf<View>()
        filterInternal(root, predicate, result)
        return result
    }

    private fun filterInternal(view: View, predicate: (View) -> Boolean, result: MutableList<View>) {
        if (predicate(view)) {
            result.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                filterInternal(view.getChildAt(i), predicate, result)
            }
        }
    }

    companion object {
        /**
         * 深度搜索匹配第一个符合条件的 View。
         */
        fun findFirst(root: View, predicate: (View) -> Boolean): View? {
            if (predicate(root)) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val result = findFirst(root.getChildAt(i), predicate)
                    if (result != null) return result
                }
            }
            return null
        }
    }
}
