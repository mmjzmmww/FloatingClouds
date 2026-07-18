package top.mmjz.floatingclouds.util

import android.graphics.Color

/**
 * 颜色工具替代 com.lu.magic.util.ColorUtil。
 */
object ColorUtil

class ColorUtilX {
    companion object {
        @JvmStatic
        fun invertColor(color: Int): Int {
            val a = Color.alpha(color)
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val invR = 255 - r
            val invG = 255 - g
            val invB = 255 - b
            return Color.argb(a, invR, invG, invB)
        }
    }
}

fun ColorUtil.invertColor(color: Int) = ColorUtilX.invertColor(color)
