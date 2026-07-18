package top.mmjz.floatingclouds.plugin

import top.mmjz.floatingclouds.hook.HookSession
import top.mmjz.floatingclouds.ClazzN
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.ConfigUtil
import top.mmjz.floatingclouds.util.StealthLog

class CommonPlugin : IPlugin {

    companion object {
        private const val SQL_SELECT_MESSAGE =
            "SELECT type, subtype, entity_id, aux_index, MAX(timestamp) as maxTime, count(aux_index) as msgCount, talker FROM FTS5MetaMessage"
        private const val SQL_SELECT_MESSAGES_BY_KEYWORD =
            "SELECT FTS5MetaMessage.docid, type, subtype, entity_id, aux_index, timestamp, talker FROM FTS5MetaMessage"
    }

    private val regex by lazy {
        Regex("^SELECT (FTS5MetaContact|FTS5MetaTopHits|FTS5MetaKefuContact|FTS5MetaFeature|FTS5MetaWeApp|FTS5MetaFinderFollow|FTS5MetaFavorite)\\.docid, type, subtype, entity_id, aux_index,.*")
    }

    override fun handleHook(session: HookSession) {
        val sqliteClass = ClazzN.from("com.tencent.wcdb.database.SQLiteDatabase", session.classLoader) ?: return

        AppReflect.findMethodsByExactPredicate(sqliteClass) { m ->
            if (m.name == "rawQueryWithFactory") {
                StealthLog.d("rawQueryWithFactory", m.parameterTypes.size)
                return@findMethodsByExactPredicate m.parameterTypes.size == 4
            }
            false
        }.forEach { method ->
            session.hook(method).intercept { chain ->
                val option = ConfigUtil.getOptionData()
                if (!ConfigUtil.isMasterEnabled() || (!option.hideMainSearch && !option.hideSingleSearch)) return@intercept chain.proceed()
                val sql = chain.args[1].toString()
                val maskIdList = ConfigUtil.getMaskList().map { it.maskId }
                if (maskIdList.isNotEmpty()) {
                    if (needReplaceChatSearchHistory(sql)) {
                        val modifiedArgs = chain.args.toMutableList()
                        modifiedArgs[1] = buildHideSearchSql(maskIdList, sql)
                        StealthLog.d("sql hide hit:", modifiedArgs[1])
                        return@intercept chain.proceed(modifiedArgs.toTypedArray())
                    }
                    // needReplacePicAndVideoSearch 逻辑暂保留为跳过
                }
                chain.proceed()
            }
        }
    }

    private fun needReplaceChatSearchHistory(sql: String): Boolean {
        return regex.containsMatchIn(sql) ||
                sql.startsWith(SQL_SELECT_MESSAGE) ||
                sql.startsWith(SQL_SELECT_MESSAGES_BY_KEYWORD)
    }

    private fun buildHideSearchSql(maskIdList: List<String?>, sql: String): String {
        val hideValueText = maskIdList.joinToString(",") { "\"$it\"" }
        val base = if (sql.endsWith(";")) sql.dropLast(1) else sql
        return "SELECT * FROM ($base) AS a WHERE aux_index NOT IN ($hideValueText);"
    }
}
