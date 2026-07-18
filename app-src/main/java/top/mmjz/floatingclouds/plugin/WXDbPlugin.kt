package top.mmjz.floatingclouds.plugin

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.ClazzN
import top.mmjz.floatingclouds.bean.DBItem
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog
import top.mmjz.floatingclouds.util.WxSQLiteManager

class WXDbPlugin : IPlugin {

    override fun handleHook(session: HookSession) {
        // ★ 始终注册数据库 Hook，回调中实时检查 viewWxDbPw，无需重启
        hookDatabase(session)
    }

    private fun hookDatabase(session: HookSession) {
        val sqliteClass = ClazzN.from("com.tencent.wcdb.database.SQLiteDatabase", session.classLoader) ?: return

        val method = AppReflect.findMethodExact(
            sqliteClass,
            "openDatabase",
            String::class.java,
            ByteArray::class.java,
            ClazzN.from("com.tencent.wcdb.database.SQLiteCipherSpec", session.classLoader),
            ClazzN.from("com.tencent.wcdb.database.SQLiteDatabase\$CursorFactory", session.classLoader),
            Int::class.java,
            ClazzN.from("com.tencent.wcdb.DatabaseErrorHandler", session.classLoader),
            Int::class.java
        ) ?: run {
            StealthLog.w("WXDbPlugin: openDatabase method not found")
            return
        }

        session.hook(method).intercept { chain ->
            // 先执行原始方法
            val result = chain.proceed()

            // ★ 运行时检查开关，无需重启
            if (!ConfigUtil.getOptionData().viewWxDbPw) return@intercept result

            // --- after 逻辑 ---
            val password = (chain.args[1] as? ByteArray)?.let { String(it) }
            val dbName = (chain.args[0] as? String).orEmpty()
            // 注意：绝不将 password 打印到日志（避免明文泄露数据库密码）
            StealthLog.d("hook db", dbName, "opened=${result != null}")
            if (dbName.isNotEmpty()) {
                WxSQLiteManager.Store[dbName] = DBItem(dbName, password, result)
                // EnMicroMsg 捕获后同步全量加载联系人缓存，供 UI 反查昵称/备注
                if (dbName.contains("EnMicroMsg")) {
                    WxSQLiteManager.loadAllContacts(dbName)
                }
            }
            result
        }
    }
}
