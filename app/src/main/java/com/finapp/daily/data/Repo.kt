package com.finapp.daily.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 刷新编排层。核心是三级降级：
 *
 *   指标：云端 JSON  →  境内直连（东财/新浪）  →  从资讯正文提取数字
 *   资讯：华尔街见闻 + 东财，按四主题过滤，不命中的丢弃
 *   公告：巨潮，按「重大事项」关键词过滤
 *
 * 每次刷新结束都会生成当日综述并清理 90 天前数据。
 */
class Repo(context: Context) {

    private val db = Db(context)

    /** 必须在后台线程调用。 */
    fun refresh(): RefreshResult {
        val problems = mutableListOf<String>()

        // ---------------- 1. 指标：云端优先 ----------------
        val indicators = mutableMapOf<String, Indicator>()
        try {
            Sources.fetchCloud().forEach { indicators[it.code] = it }
        } catch (e: Exception) {
            problems.add("云端数据不可用")
        }

        // ---------------- 2. 境内直连补缺 ----------------
        if (!indicators.containsKey("DXY") ||
            !indicators.containsKey("WTI") ||
            !indicators.containsKey("BRENT")
        ) {
            runCatching { Sources.fetchDomesticQuotes() }.getOrElse { emptyList() }
                .forEach { indicators.putIfAbsent(it.code, it) }
        }

        // ---------------- 3. 资讯 ----------------
        val rawArticles = mutableListOf<Article>()
        runCatching { rawArticles += Sources.fetchWscn() }
            .onFailure { problems.add("华尔街见闻失败") }
        runCatching { rawArticles += Sources.fetchEastmoneyNews() }
            .onFailure { problems.add("东财资讯失败") }

        val filtered = rawArticles
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
            .mapNotNull { article ->
                val topics = Keywords.classify(article.title + " " + article.summary)
                if (topics.isEmpty()) null else article.copy(topics = topics.joinToString(","))
            }

        // ---------------- 4. 公告（只留重大事项）----------------
        val announcements = runCatching { Sources.fetchCninfo() }
            .getOrElse {
                problems.add("巨潮公告失败")
                emptyList()
            }
            .filter { Keywords.isMajorAnnouncement(it.title) }

        // ---------------- 5. 指标仍缺 → 资讯提取 ----------------
        val missing = EXPECTED_CODES.filter { !indicators.containsKey(it) }
        if (missing.isNotEmpty() && filtered.isNotEmpty()) {
            extractFromNews(filtered, missing).forEach { indicators.putIfAbsent(it.code, it) }
        }

        // ---------------- 6. 落库 ----------------
        val sortedIndicators = indicators.values.toList()
        db.upsertIndicators(sortedIndicators)
        db.insertArticles(filtered)
        db.insertAnnouncements(announcements)

        // ---------------- 7. 当日综述 ----------------
        val counts = db.counts()
        db.saveDigest(
            Sources.today(),
            Digest.build(sortedIndicators, counts.second, counts.third, missing)
        )
        db.sweep()

        return RefreshResult(
            indicatorCount = sortedIndicators.size,
            articleCount = filtered.size,
            announcementCount = announcements.size,
            error = problems.takeIf { it.isNotEmpty() }?.joinToString("；")
        )
    }

    // ------------------------------------------------------------ 读取

    fun indicators(): List<Indicator> = db.loadIndicators()

    fun articles(topic: String? = null): List<Article> = db.loadArticles(topic)

    fun announcements(): List<Announcement> = db.loadAnnouncements()

    fun digest(): String? = db.loadDigest(Sources.today())

    fun counts(): Triple<Int, Int, Int> = db.counts()

    /** 上次更新时间（毫秒）。0 表示从没更新过。 */
    fun lastUpdatedMillis(): Long = db.lastUpdatedAt()

    @Suppress("SimpleDateFormat")
    fun lastUpdated(): String? {
        val ts = db.lastUpdatedAt()
        if (ts <= 0) return null
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))
    }

    // ------------------------------------------------------------ 资讯提取

    /**
     * 从资讯正文里抠数字填指标。这是最低档的兜底，precision 标记为 "news"，
     * 界面必须显示「资讯提取」角标（用户明确要求）。
     */
    private fun extractFromNews(
        articles: List<Article>,
        missing: List<String>
    ): List<Indicator> {
        if (missing.isEmpty()) return emptyList()
        val found = mutableMapOf<String, Indicator>()

        for (article in articles) {
            val text = article.title + " " + article.summary
            val numbers = Keywords.extractNumbers(text)
            for (n in numbers) {
                val code = when {
                    n.text.contains("10年") || n.text.contains("十年") -> "US10Y"
                    n.text.contains("2年") || n.text.contains("两年") -> "US2Y"
                    n.text.contains("30年") -> "US30Y"
                    n.text.contains("美元指数") -> "DXY"
                    n.text.contains("WTI") || n.text.contains("原油") -> "WTI"
                    else -> null
                } ?: continue
                if (code !in missing || found.containsKey(code)) continue
                found[code] = Indicator(
                    code = code,
                    title = TITLES[code] ?: code,
                    value = Sources.trimNumber(n.value),
                    unit = if (code == "DXY") "" else if (code == "WTI") "美元/桶" else "%",
                    change = null,
                    asOf = article.publishedAt.take(10),
                    precision = "news",
                    source = article.source
                )
            }
        }
        return found.values.toList()
    }

    companion object {
        val EXPECTED_CODES = listOf("US10Y", "US2Y", "US30Y", "FED_TARGET", "DXY", "WTI", "BRENT")

        val TITLES = mapOf(
            "US10Y" to "美债10年期",
            "US2Y" to "美债2年期",
            "US30Y" to "美债30年期",
            "US5Y" to "美债5年期",
            "US3M" to "美债3月期",
            "FED_TARGET" to "美联储目标利率区间",
            "EFFR" to "有效联邦基金利率",
            "DXY" to "美元指数",
            "WTI" to "WTI原油",
            "BRENT" to "布伦特原油"
        )
    }
}
