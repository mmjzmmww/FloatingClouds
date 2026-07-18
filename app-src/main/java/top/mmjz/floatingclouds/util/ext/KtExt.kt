package top.mmjz.floatingclouds.util.ext

/**
 * Kotlin 扩展函数替代 com.lu.magic.util.kxt.*
 */

/** 替代 toElseString */
fun Any?.toElseString(default: String = ""): String = this?.toString() ?: default

/** 替代 toElseEmptyString */
fun String?.toElseEmptyString(): String = this ?: ""

/** 替代 optInt */
fun org.json.JSONObject?.optInt(key: String, fallback: Int = 0): Int = this?.optInt(key, fallback) ?: fallback
