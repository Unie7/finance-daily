package com.finapp.daily.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import android.content.ContentValues
import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地库。故意不用 Room —— 省掉 KSP 注解处理器，构建链更稳，
 * 而且这里只有 4 张表，手写 SQL 足够。
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "finance.db"
        private const val VERSION = 4
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
                score INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                fav_at INTEGER,
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
                favorite INTEGER NOT NULL DEFAULT 0,
                fav_at INTEGER,
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

    /**
     * 增量迁移：v4 只在 article / announcement 上加两列，不动原有数据，
     * 用户已经抓到的资讯和公告都保得住。
     * v3 之前结构变动大，直接重建。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            listOf("indicator", "indicator_history", "article", "announcement", "digest")
                .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
            return
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE article ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE article ADD COLUMN fav_at INTEGER")
            db.execSQL("ALTER TABLE announcement ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE announcement ADD COLUMN fav_at INTEGER")
        }
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

    /**
     * 逐条 upsert。
     *
     * 为什么不能再用 INSERT ... CONFLICT_IGNORE：那样已存在的行会被整体跳过，
     * topics / score 永远停在第一次入库时的值。改过几次过滤规则之后，
     * 库里就积了一堆「按旧规则打的标签」——比如「法国GDP」被标成原油。
     *
     * 现在改成：先 UPDATE 内容字段（**不碰 favorite / fav_at**），没有行才 INSERT。
     * 这样每次刷新都会用当前规则重算 topics/score，收藏状态又不会被冲掉。
     */
    fun insertArticles(items: List<Article>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var changed = 0
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("url", item.url)
                    put("title", item.title)
                    put("summary", item.summary)
                    put("source", item.source)
                    put("topics", item.topics)
                    put("score", item.score)
                    put("published_at", item.publishedAt)
                    put("fetched_at", now)
                }
                val updated = db.update("article", values, "url = ?", arrayOf(item.url))
                if (updated == 0) {
                    if (db.insert("article", null, values) > 0) changed++
                } else {
                    changed++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    /** 清掉历史残留：没收藏且当前规则下不命中任何主题的行。 */
    fun purgeNoTopicArticles(): Int =
        writableDatabase.delete(
            "article",
            "favorite = 0 AND (topics IS NULL OR topics = '')",
            null
        )

    /**
     * @param byRelevance true 则按相关度降序，否则按时间降序
     */
    fun loadArticles(
        topic: String? = null,
        limit: Int = 300,
        byRelevance: Boolean = false,
        minScore: Int = 0
    ): List<Article> {
        val conds = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!topic.isNullOrEmpty() && topic != "全部") {
            conds.add("topics LIKE ?"); args.add("%$topic%")
        }
        if (minScore > 0) {
            conds.add("score >= ?"); args.add(minScore.toString())
        }
        val where = if (conds.isEmpty()) "" else "WHERE " + conds.joinToString(" AND ")
        val order = if (byRelevance) "score DESC, published_at DESC" else "published_at DESC"
        val out = mutableListOf<Article>()
        readableDatabase.rawQuery(
            "SELECT id,title,summary,source,url,published_at,topics,score,favorite,fav_at " +
                "FROM article $where ORDER BY $order LIMIT ?",
            (args + limit.toString()).toTypedArray()
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
                        topics = c.getString(6) ?: "",
                        score = c.getInt(7),
                        favorite = c.getInt(8) == 1,
                        favoriteAt = if (c.isNull(9)) 0L else c.getLong(9)
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

    /**
     * @param category 按公告类型筛（模糊匹配，因为一条公告可能挂多个分类）
     * @param code     按公司代码筛
     */
    fun loadAnnouncements(
        limit: Int = 300,
        category: String? = null,
        code: String? = null
    ): List<Announcement> {
        val conds = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!category.isNullOrBlank()) { conds.add("category LIKE ?"); args.add("%$category%") }
        if (!code.isNullOrBlank()) { conds.add("code = ?"); args.add(code) }
        val where = if (conds.isEmpty()) "" else "WHERE " + conds.joinToString(" AND ")
        args.add(limit.toString())
        val out = mutableListOf<Announcement>()
        readableDatabase.rawQuery(
            "SELECT id,code,name,title,category,url,published_at,favorite,fav_at " +
                "FROM announcement $where ORDER BY published_at DESC LIMIT ?",
            args.toTypedArray()
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
                        publishedAt = c.getString(6) ?: "",
                        favorite = c.getInt(7) == 1,
                        favoriteAt = if (c.isNull(8)) 0L else c.getLong(8)
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

    /** 出现次数最多的公告分类，用于类型筛选栏 */
    fun announcementCategories(limit: Int = 12): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT category, COUNT(*) c FROM announcement WHERE category <> '' " +
                "GROUP BY category ORDER BY c DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** 公告涉及的公司（按最近时间排序），用于公司筛选 */
    fun announcementCompanies(limit: Int = 300): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT code, name, MAX(published_at) t FROM announcement WHERE code <> '' " +
                "GROUP BY code ORDER BY t DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to (c.getString(1) ?: "")) }
        return out
    }

    // ------------------------------------------------------------- 收藏

    fun setArticleFavorite(url: String, favorite: Boolean) {
        val v = ContentValues().apply {
            put("favorite", if (favorite) 1 else 0)
            if (favorite) put("fav_at", System.currentTimeMillis()) else putNull("fav_at")
        }
        writableDatabase.update("article", v, "url = ?", arrayOf(url))
    }

    fun setAnnouncementFavorite(url: String, favorite: Boolean) {
        val v = ContentValues().apply {
            put("favorite", if (favorite) 1 else 0)
            if (favorite) put("fav_at", System.currentTimeMillis()) else putNull("fav_at")
        }
        writableDatabase.update("announcement", v, "url = ?", arrayOf(url))
    }

    fun loadFavoriteArticles(limit: Int = 300): List<Article> {
        val out = mutableListOf<Article>()
        readableDatabase.rawQuery(
            "SELECT id,title,summary,source,url,published_at,topics,score,favorite,fav_at " +
                "FROM article WHERE favorite = 1 ORDER BY fav_at DESC LIMIT ?",
            arrayOf(limit.toString())
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
                        topics = c.getString(6) ?: "",
                        score = c.getInt(7),
                        favorite = true,
                        favoriteAt = if (c.isNull(9)) 0L else c.getLong(9)
                    )
                )
            }
        }
        return out
    }

    fun loadFavoriteAnnouncements(limit: Int = 300): List<Announcement> {
        val out = mutableListOf<Announcement>()
        readableDatabase.rawQuery(
            "SELECT id,code,name,title,category,url,published_at,favorite,fav_at " +
                "FROM announcement WHERE favorite = 1 ORDER BY fav_at DESC LIMIT ?",
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
                        publishedAt = c.getString(6) ?: "",
                        favorite = true,
                        favoriteAt = if (c.isNull(8)) 0L else c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    fun favoriteCounts(): Pair<Int, Int> {
        fun count(table: String): Int =
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $table WHERE favorite = 1", null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return count("article") to count("announcement")
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

    // ------------------------------------------------------------- 手动清理

    @SuppressLint("SimpleDateFormat")
    private fun labelDaysAgo(days: Int): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .format(Date(System.currentTimeMillis() - days * 24L * 3600L * 1000L))

    /** 清掉 N 天之前的资讯和公告，返回删除条数。 */
    /**
     * @param keepFavorites true 则保留已收藏的条目（默认行为，避免误删）
     */
    fun clearOlderThan(days: Int, keepFavorites: Boolean = true): Int {
        val label = labelDaysAgo(days)
        val day = label.take(10)
        val db = writableDatabase
        val keep = if (keepFavorites) " AND favorite = 0" else ""
        var n = db.delete("article", "published_at < ?$keep", arrayOf(label))
        n += db.delete("announcement", "published_at < ?$keep", arrayOf(label))
        db.delete("digest", "day < ?", arrayOf(day))
        db.delete("indicator_history", "as_of < ?", arrayOf(day))
        return n
    }

    /** 清空资讯和公告（指标保留）。keepFavorites 为 true 时只清未收藏的。 */
    fun clearAllContent(keepFavorites: Boolean = true): Int {
        val db = writableDatabase
        val where = if (keepFavorites) "favorite = 0" else null
        var n = db.delete("article", where, null)
        n += db.delete("announcement", where, null)
        db.delete("digest", null, null)
        db.delete("indicator_history", null, null)
        return n
    }
}
