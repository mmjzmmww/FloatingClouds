package top.mmjz.floatingclouds.util.ext
import top.mmjz.floatingclouds.util.AppContext

import android.content.res.Configuration
import android.util.TypedValue
import android.widget.TextView
import top.mmjz.floatingclouds.ClazzN
import top.mmjz.floatingclouds.util.ColorUtilX
import org.json.JSONArray
import org.json.JSONObject

val sizeIntCache = HashMap<String, Int>()
val sizeFloatCache = HashMap<String, Float>()

fun Any?.toJson(): String {
    if (this == null) return "null"
    return when (this) {
        is Iterable<*> -> {
            JSONArray().also { arr ->
                forEach { item ->
                    when (item) {
                        null -> arr.put(JSONObject.NULL)
                        is String -> arr.put(item)
                        is Number -> arr.put(item)
                        is Boolean -> arr.put(item)
                        is JSONObject -> arr.put(item)
                        is JSONArray -> arr.put(item)
                        else -> arr.put(item.toString())
                    }
                }
            }.toString()
        }
        is Map<*, *> -> {
            JSONObject().also { obj ->
                forEach { (k, v) ->
                    k?.let { key ->
                        when (v) {
                            null -> obj.put(key.toString(), JSONObject.NULL)
                            is String -> obj.put(key.toString(), v)
                            is Number -> obj.put(key.toString(), v)
                            is Boolean -> obj.put(key.toString(), v)
                            is JSONObject -> obj.put(key.toString(), v)
                            is JSONArray -> obj.put(key.toString(), v)
                            else -> obj.put(key.toString(), v.toString())
                        }
                    }
                }
            }.toString()
        }
        else -> toString()
    }
}

fun dp2px(dp: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp,
        AppContext.context!!.resources.displayMetrics
    )
}

fun isAppNightMode(): Boolean {
    return (AppContext.context!!.resources.configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

val Int.dp: Int
    get() = sizeIntCache[this.toString()].let {
        if (it == null) {
            val v = dp2px(this.toFloat()).toInt()
            sizeIntCache[this.toString()] = v
            return@let v
        }
        return it
    }

val Float.dp: Float
    get() = sizeFloatCache[this.toString()].let {
        if (it == null) {
            val v = dp2px(this.toFloat())
            sizeFloatCache[this.toString()] = v
            return@let v
        }
        return it
    }

val Double.dp: Float
    get() = this.toFloat().dp

fun CharSequence?.toIntElse(fallback: Int): Int = try {
    if (this == null) fallback
    else Integer.parseInt(this.toString())
} catch (e: Exception) {
    fallback
}

fun CharSequence?.toLongElse(fallback: Long): Long = try {
    if (this == null) fallback
    else this.toString().toLong()
} catch (e: Exception) {
    fallback
}

fun TextView.setTextColorTheme(color: Int) {
    if (isAppNightMode()) {
        setTextColor(ColorUtilX.invertColor(color))
    } else {
        setTextColor(color)
    }
}

fun Class<*>?.createEmptyOrNullObject(): Any? {
    if (this == null) return null
    return runCatching {
        this.getDeclaredConstructor().newInstance()
    }.getOrElse {
        runCatching {
            // Fallback: try to construct with default no-arg constructor
            null
        }.getOrDefault(null)
    }
}

fun Number.day2Mills(): Long {
    if (this is Double) return (this.toDouble() * 24L * 60L * 60L * 1000L).toLong()
    if (this is Float) return (this.toFloat() * 24L * 60L * 60L * 1000L).toLong()
    return (this.toLong() * 24L * 60L * 60L * 1000L)
}

fun Long.mills2Day(): Int {
    return ((this / 24L / 60L / 60L / 1000L).toInt())
}

fun TextView?.dayText2Mills(fallback: Long = 0): Long {
    try {
        return this?.text?.toString()?.toLongOrNull()?.day2Mills() ?: fallback
    } catch (e: Exception) {
    }
    return fallback
}

val String.OfClass: Class<*>? get() = ClazzN.from(this)
