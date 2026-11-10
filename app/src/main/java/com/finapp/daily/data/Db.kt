package com.finapp.daily.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import android.content.ContentValues

/**
 * 本地库。故意不用 Room —— 省掉 KSP 注解处理器，构建链更稳，
 * 而且这里只有 4 张表，手写 SQL 足够。
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "finance.db"
        private const val VERSION = 1
        const val RETENTION_DAYS = 90
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE indicator (
                code TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                value TEXT NOT NULL,
                unit TEXT,
                change REAL,
                as_of TEXT,
                precision TEXT,
                source TEXT,
                updated_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE indicator_history (
                code TEXT NOT NULL,
                as_of TEXT NOT NULL,
                value REAL NOT NULL,
                PRIMARY KEY (code, as_of)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE article (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                summary TEXT,
                source TEXT,
                topics TEXT,
                published_at TEXT,
                fetched_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_article_time ON article(published_at DESC)")
        db.execSQL(
            """
            CREATE TABLE announcement (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                code TEXT,
                name TEXT,
                title TEXT NOT NULL,
                category TEXT,
                published_at TEXT,
                fetched_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ann_time ON announcement(published_at DESC)")
        db.execSQL(
            """
            CREATE TABLE digest (
                day TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                created_at INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("indicator", "indicator_history", "article", "announcement", "digest")
            .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    // ------------------------------------------------------------- 指标

    fun upsertIndicators(items: List<Indicator>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("code", item.code)
                    put("title", item.title)
                    put("value", item.value)
                    put("unit", item.unit)
                    if (item.change == null) putNull("change") else put("change", item.change)
                    put("as_of", item.asOf)
                    put("precision", item.precision)
                    put("source", item.source)
                    put("updated_at", now)
                }
                db.insertWithOnConflict("indicator", null, values, SQLiteDatabase.CONFLICT_REPLACE)

                // 数值型指标顺手记一条历史，供画趋势
                val numeric = item.value.toDoubleOrNull()
                if (numeric != null && item.asOf.isNotEmpty()) {
                    val hist = ContentValues().apply {
                        put("code", item.code)
                        put("as_of", item.asOf)
                        put("value", numeric)
                    }
                    db.insertWithOnConflict("indicator_history", null, hist, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadIndicators(): List<Indicator> {
        val order = "US10Y,US2Y,US30Y,US5Y,US3M,FED_TARGET,EFFR,DXY,WTI,BRENT"
        val result = mutableListOf<Indicator>()
        readableDatabase.rawQuery("SELECT * FROM indicator", null).use { c ->
            while (c.moveToNext()) {
                result.add(
                    Indicator(
                        code = c.getString(0),
                        title = c.getString(1),
                        value = c.getString(2),
                        unit = c.getString(3) ?: "",
                        change = if (c.isNull(4)) null else c.getDouble(4),
                        asOf = c.getString(5) ?: "",
                        precision = c.getString(6) ?: "official",
                        source = c.getString(7) ?: ""
                    )
                )
            }
        }
        val rank = order.split(",").withIndex().associate { it.value to it.index }
        return result.sortedBy { rank[it.code] ?: 99 }
    }

    fun history(code: String, limit: Int = 180): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        readableDatabase.rawQuery(
            "SELECT as_of, value FROM indicator_history WHERE code=? ORDER BY as_of DESC LIMIT ?",
            arrayOf(code, limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getDouble(1))
        }
        return out.reversed()
    }

    // ------------------------------------------------------------- 资讯

    /** 返回真正新增的条数（UNIQUE url 冲突时被忽略）。 */
    fun insertArticles(items: List<Article>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var inserted = 0
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("url", item.url)
                    put("title", item.title)
                    put("summary", item.summary)
                    put("source", item.source)
                    put("topics", item.topics)
                    put("published_at", item.publishedAt)
                    put("fetched_at", now)
                }
                val id = db.insertWithOnConflict("article", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (id > 0) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun loadArticles(topic: String? = null, limit: Int = 200): List<Article> {
        val where = if (topic.isNullOrEmpty() || topic == "全部") "" else "WHERE topics LIKE ?"
        val args = if (where.isEmpty()) null else arrayOf("%$topic%")
        val out = mutableListOf<Article>()
        readableDatabase.rawQuery(
            "SELECT id,title,summary,source,url,published_at,topics FROM article $where ORDER BY published_at DESC LIMIT ?",
            (args ?: emptyArray()) + limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Article(
                        id = c.getLong(0),
                        title = c.getString(1),
                        summary = c.getString(2) ?: "",
                        source = c.getString(3) ?: "",
                        url = c.getString(4),
                        publishedAt = c.getString(5) ?: "",
                        topics = c.getString(6) ?: ""
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------- 公告

    fun insertAnnouncements(items: List<Announcement>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var inserted = 0
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("url", item.url)
                    put("code", item.code)
                    put("name", item.name)
                    put("title", item.title)
                    put("category", item.category)
                    put("published_at", item.publishedAt)
                    put("fetched_at", now)
                }
                val id = db.insertWithOnConflict("announcement", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (id > 0) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun loadAnnouncements(limit: Int = 200): List<Announcement> {
        val out = mutableListOf<Announcement>()
        readableDatabase.rawQuery(
            "SELECT id,code,name,title,category,url,published_at FROM announcement ORDER BY published_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Announcement(
                        id = c.getLong(0),
                        code = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        title = c.getString(3),
                        category = c.getString(4) ?: "",
                        url = c.getString(5),
                        publishedAt = c.getString(6) ?: ""
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------- 综述

    fun saveDigest(day: String, content: String) {
        val values = ContentValues().apply {
            put("day", day)
            put("content", content)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("digest", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadDigest(day: String): String? {
        readableDatabase.rawQuery("SELECT content FROM digest WHERE day=?", arrayOf(day)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    // ------------------------------------------------------------- 清理

    /** 删除 90 天前的数据。每次刷新后调用。 */
    fun sweep(): Int {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600L * 1000L
        val db = writableDatabase
        val a = db.delete("article", "fetched_at < ?", arrayOf(cutoff.toString()))
        val b = db.delete("announcement", "fetched_at < ?", arrayOf(cutoff.toString()))
        db.delete("digest", "created_at < ?", arrayOf(cutoff.toString()))
        db.delete("indicator_history", "as_of < date('now', '-90 day')", null)
        return a + b
    }

    fun counts(): Triple<Int, Int, Int> {
        fun count(table: String): Int =
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        return Triple(count("indicator"), count("article"), count("announcement"))
    }

    /** 最近一次指标更新时间（毫秒），用于判断数据是否过期。 */
    fun lastUpdatedAt(): Long =
        readableDatabase.rawQuery("SELECT MAX(updated_at) FROM indicator", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
}
