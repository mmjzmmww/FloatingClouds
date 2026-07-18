package top.mmjz.floatingclouds.util

import android.database.Cursor
import top.mmjz.floatingclouds.bean.DBItem
import java.lang.reflect.Array
import java.util.concurrent.ConcurrentHashMap

/**
 * 微信 SQLite 数据库与联系人昵称缓存管理。
 *
 * - [Store] 保存各数据库名到活数据库实例的映射（Hook 线程与 UI 线程并发访问，故用 ConcurrentHashMap）。
 * - [contactCache] 缓存 rcontact 表中 wxid -> 显示名（昵称/备注）的映射，供 UI 现查反查使用。
 */
class WxSQLiteManager {
    companion object {
        /** dbName -> 活数据库条目（Hook 线程与 UI 线程共享，使用线程安全 Map） */
        val Store = ConcurrentHashMap<String, DBItem>()

        /** wxid -> displayName（conRemark 优先，其次 nickname，再次 alias） */
        val contactCache = ConcurrentHashMap<String, String>()

        /**
         * 全量加载 EnMicroMsg 的 rcontact 表到 [contactCache]。
         * 仅对 EnMicroMsg 数据库执行；通过反射调用活实例的 rawQuery。
         * 整段包 try/catch，单条异常不影响整体扫描。
         */
        fun loadAllContacts(dbName: String) {
            if (!dbName.contains("EnMicroMsg")) return
            val db = Store[dbName]?.sqliteDatabase
            if (db == null) {
                android.util.Log.e("FCloud_DB", "Store has no DB instance for $dbName")
                return
            }
            try {
                val rawQuery = db.javaClass.getMethod(
                    "rawQuery",
                    String::class.java,
                    Array.newInstance(Any::class.java, 0)::class.java
                )
                val cursor = rawQuery.invoke(
                    db,
                    "SELECT username, conRemark, nickname, alias FROM rcontact",
                    null
                ) as? Cursor
                if (cursor == null) {
                    android.util.Log.e("FCloud_DB", "rawQuery returned null cursor")
                    return
                }
                var count = 0
                cursor.use { c ->
                    val iUser = c.getColumnIndex("username")
                    val iRemark = c.getColumnIndex("conRemark")
                    val iNick = c.getColumnIndex("nickname")
                    val iAlias = c.getColumnIndex("alias")
                    android.util.Log.e("FCloud_DB", "columns user=$iUser remark=$iRemark nick=$iNick alias=$iAlias")
                    while (c.moveToNext()) {
                        try {
                            val username = if (iUser >= 0) c.getString(iUser) else null
                            if (username.isNullOrBlank()) continue
                            val remark = if (iRemark >= 0) c.getString(iRemark) else null
                            val nick = if (iNick >= 0) c.getString(iNick) else null
                            val alias = if (iAlias >= 0) c.getString(iAlias) else null
                            val display = remark?.takeIf { it.isNotBlank() }
                                ?: nick?.takeIf { it.isNotBlank() }
                                ?: alias?.takeIf { it.isNotBlank() }
                            if (!display.isNullOrBlank()) {
                                contactCache[username] = display
                                count++
                            }
                        } catch (_: Throwable) {
                            // 单条解析异常，跳过该行，不影响整体扫描
                        }
                    }
                }
                android.util.Log.e("FCloud_DB", "loaded $count contacts into cache")
            } catch (t: Throwable) {
                android.util.Log.e("FCloud_DB", "loadAllContacts failed: ${t.message}")
            }
        }

        /**
         * 反查 wxid 对应的显示名（备注/昵称）。
         * 优先级：缓存命中 > 缓存尚未加载时回退到逐条 DB 查询。
         * 过滤公众号(gh_)/群(@)等无昵称意义来源。
         *
         * @return 显示名；无结果返回 null
         */
        fun getDisplayName(wxid: String?): String? {
            if (wxid.isNullOrBlank() || wxid.startsWith("gh_") || wxid.contains("@")) return null

            // 1) 缓存命中直接返回
            val cached = contactCache[wxid]
            if (cached != null) return cached

            // 2) 缓存已全量加载过但确实无此人
            if (contactCache.isNotEmpty()) {
                android.util.Log.e("FCloud_DB", "cache miss for $wxid (cache=${contactCache.size} entries, no fallback)")
                return null
            }

            // 3) 缓存尚未加载：回退到旧逻辑，对 EnMicroMsg 实例逐条查询
            val enItem = Store.entries.firstOrNull { it.key.contains("EnMicroMsg") }
            if (enItem == null) {
                android.util.Log.e("FCloud_DB", "no EnMicroMsg in Store (Store size=${Store.size})")
            }
            val result = queryRcontactSingle(enItem?.value?.sqliteDatabase, wxid)
            android.util.Log.e("FCloud_DB", "single query for $wxid -> ${result ?: "null"}")
            return result
        }

        /**
         * 回退：对单个 wxid 跑 rcontact 查询。
         * 显示名优先级：conRemark > nickname > alias。
         */
        private fun queryRcontactSingle(db: Any?, wxid: String): String? {
            if (db == null) {
                android.util.Log.e("FCloud_DB", "db null for $wxid")
                return null
            }
            return try {
                val rawQuery = db.javaClass.getMethod(
                    "rawQuery",
                    String::class.java,
                    Array.newInstance(Any::class.java, 0)::class.java
                )
                val cursor = rawQuery.invoke(
                    db,
                    "select nickname, alias, conRemark from rcontact where username=?",
                    arrayOf(wxid)
                ) as? Cursor
                if (cursor == null) {
                    android.util.Log.e("FCloud_DB", "cursor null for $wxid")
                    return null
                }
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val result = c.getString(2)?.takeIf { it.isNotBlank() }   // conRemark
                            ?: c.getString(0)?.takeIf { it.isNotBlank() } // nickname
                            ?: c.getString(1)?.takeIf { it.isNotBlank() } // alias
                        if (result == null) android.util.Log.e("FCloud_DB", "row found but blank for $wxid")
                        result
                    } else {
                        android.util.Log.e("FCloud_DB", "no row for $wxid")
                        null
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("FCloud_DB", "query failed for $wxid: ${t.message}")
                null
            }
        }
    }
}
