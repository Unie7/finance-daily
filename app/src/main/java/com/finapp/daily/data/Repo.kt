package com.finapp.daily.data

import android.content.Context

/**
 * 刷新编排层。
 *
 * 资讯口径（根据实测数据定）：
 *   1. 主 feed 按「美债/美元/加息/原油」四主题过滤，保证内容对题
 *   2. 彭博社转载的条目额外打「彭博」标签，即使不属于四主题也保留（可用筛选项单独看）
 *
 * 实测依据（3.5 天、800 条见闻快讯）：
 *   - 东财 400 条 / 新浪 50 条：提到彭博 0 条
 *   - 见闻 800 条：提到彭博 31 条，其中「引用形态」22 条、彭博指数类 9 条
 *   - 22 条真引用里命中四主题的只有 4 条（13%），内容是产业/企业新闻
 *   → 所以「彭博 + 宏观」在免费源里几乎没有交集，只能做混合口径
 */
class Repo(context: Context) {

    private val db = Db(context)

    /** 必须在后台线程调用。 */
    fun refresh(): RefreshResult {
        val problems = mutableListOf<String>()

        // ---------------- 1. 指标：云端多镜像 ----------------
        val indicators = mutableMapOf<String, Indicator>()
        try {
            Sources.fetchCloud().forEach { indicators[it.code] = it }
        } catch (e: Exception) {
            problems.add("云端指标不可用（${e.message}）")
        }

        if (listOf("DXY", "WTI", "BRENT").any { !indicators.containsKey(it) }) {
            runCatching { Sources.fetchDomesticQuotes() }.getOrNull()
                ?.forEach { indicators.putIfAbsent(it.code, it) }
        }

        // ---------------- 2. 资讯：四主题为主 + 彭博条目标注 ----------------
        val since = Sources.sinceLabel(Config.NEWS_DAYS)
        val raw = mutableListOf<Article>()

        runCatching { raw += Sources.fetchWscn(since) }
            .onFailure { problems.add("见闻快讯失败（${it.message}）") }
        runCatching { raw += Sources.fetchEastmoneyNews() }
            .onFailure { problems.add("东财资讯失败（${it.message}）") }
        // 财联社已移除：实测连续多轮 32 条抓取、0 条命中四主题
        // （它的首页聚合以 A 股个股和券商观点为主），接入只增加刷新耗时。
        // 实现保留在 Cailianpress.kt，日后若找到宏观频道再加回来。
        if (false) {
            runCatching { raw += Cailianpress.fetch() }
        }

        val pool = raw
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .filter { it.publishedAt.isEmpty() || it.publishedAt >= since }
            .distinctBy { it.url }

        val articles = pool.mapNotNull { article ->
            val topics = Keywords.classify(article.title, article.summary).toMutableList()
            if (Keywords.isBloomberg(article.title + " " + article.summary)) {
                topics.add(0, PSEUDO_BLOOMBERG)
            }
            if (topics.isEmpty()) null else article.copy(
                topics = topics.joinToString(","),
                score = Keywords.relevance(article.title, article.summary)
            )
        }
            .sortedByDescending { it.publishedAt }
            .take(MAX_ARTICLES)

        // ---------------- 3. 公告：东财分类筛，失败退回巨潮 ----------------
        var announcements: List<Announcement> = emptyList()
        try {
            announcements = Sources.fetchEastmoneyAnnouncements()
                .filter { Keywords.isMajorAnnouncement(it.category, it.title) }
        } catch (e: Exception) {
            problems.add("东财公告失败（${e.message}）")
        }
        if (announcements.isEmpty()) {
            try {
                announcements = Sources.fetchCninfo()
                    .filter { Keywords.isMajorAnnouncement(it.title) }
            } catch (e: Exception) {
                problems.add("巨潮公告失败（${e.message}）")
            }
        }

        // ---------------- 4. 指标仍缺 → 资讯提取 ----------------
        val missing = EXPECTED_CODES.filter { !indicators.containsKey(it) }
        if (missing.isNotEmpty() && articles.isNotEmpty()) {
            extractFromNews(articles, missing).forEach { indicators.putIfAbsent(it.code, it) }
        }

        // ---------------- 5. 落库 ----------------
        val indicatorList = indicators.values.toList()
        db.upsertIndicators(indicatorList)
        db.insertArticles(articles)
        // 全量重算：把库里所有已存条目按**当前规则**重新打标签，
        // 再删掉重算后不再命中任何主题的（收藏的除外）。
        reclassifyStoredArticles()
        db.insertAnnouncements(announcements)

        val counts = db.counts()
        db.saveDigest(
            Sources.today(),
            Digest.build(indicatorList, counts.second, counts.third, missing)
        )
        db.sweep()

        val bloombergCount = articles.count { it.topics.contains(PSEUDO_BLOOMBERG) }
        if (bloombergCount == 0) {
            problems.add("近 ${Config.NEWS_DAYS} 天没有彭博社转载内容")
        }

        // 按源统计：抓了多少、过滤后剩多少，让界面能说清"为什么没内容"
        val rawBySource = pool.groupingBy { it.source }.eachCount()
        val keptBySource = articles.groupingBy { it.source }.eachCount()
        val stats = rawBySource.entries
            .sortedByDescending { it.value }
            .map { SourceStat(it.key, it.value, keptBySource[it.key] ?: 0) }

        return RefreshResult(
            indicatorCount = indicatorList.size,
            articleCount = articles.size,
            announcementCount = announcements.size,
            error = problems.takeIf { it.isNotEmpty() }?.joinToString("；"),
            stats = stats
        )
    }

    // ------------------------------------------------------------ 读取

    fun indicators(): List<Indicator> = db.loadIndicators()

    fun articles(
        topic: String? = null,
        byRelevance: Boolean = false,
        minScore: Int = 0
    ): List<Article> = db.loadArticles(topic, byRelevance = byRelevance, minScore = minScore)

    fun announcements(category: String? = null, code: String? = null): List<Announcement> =
        db.loadAnnouncements(category = category, code = code)

    fun announcementCategories(): List<String> = db.announcementCategories()

    fun announcementCompanies(): List<Pair<String, String>> = db.announcementCompanies()

    fun digest(): String? = db.loadDigest(Sources.today())

    fun counts(): Triple<Int, Int, Int> = db.counts()

    fun lastUpdatedMillis(): Long = db.lastUpdatedAt()

    fun clearOlderThan(days: Int, keepFavorites: Boolean = true): Int =
        db.clearOlderThan(days, keepFavorites)

    fun clearAllContent(keepFavorites: Boolean = true): Int =
        db.clearAllContent(keepFavorites)

    // ---------------- 收藏 ----------------
    fun setArticleFavorite(url: String, favorite: Boolean) = db.setArticleFavorite(url, favorite)

    fun setAnnouncementFavorite(url: String, favorite: Boolean) =
        db.setAnnouncementFavorite(url, favorite)

    fun favoriteArticles(): List<Article> = db.loadFavoriteArticles()

    fun favoriteAnnouncements(): List<Announcement> = db.loadFavoriteAnnouncements()

    fun favoriteCounts(): Pair<Int, Int> = db.favoriteCounts()

    fun lastUpdated(): String? {
        val ts = db.lastUpdatedAt()
        if (ts <= 0) return null
        return Sources.formatTs(ts)
    }

    // ------------------------------------------------------------ 全量重算

    /**
     * 按当前过滤规则重算库里所有资讯的 topics / score，并清理不再命中的。
     * 每次刷新都跑一遍，保证标签规则升级后老数据能跟上。
     */
    private fun reclassifyStoredArticles() {
        for (a in db.loadAllArticlesForReclassify()) {
            // 不信任库里的 title（老版本可能存的是截断正文），统一从正文首行重建
            val lead = Sources.leadLine(a.summary.ifBlank { a.title })
            val topics = Keywords.classify(lead, a.summary).toMutableList()
            if (Keywords.isBloomberg(lead + " " + a.summary)) {
                topics.add(0, PSEUDO_BLOOMBERG)
            }
            val newTopics = topics.joinToString(",")
            val newScore = Keywords.relevance(lead, a.summary)
            if (newTopics != a.topics || newScore != a.score || lead != a.title) {
                db.updateArticleDerived(a.url, lead, newTopics, newScore)
            }
        }
        db.purgeNoTopicArticles()
    }

    // ------------------------------------------------------------ 资讯提取

    private fun extractFromNews(
        articles: List<Article>,
        missing: List<String>
    ): List<Indicator> {
        if (missing.isEmpty()) return emptyList()
        val found = mutableMapOf<String, Indicator>()

        for (article in articles) {
            val text = article.title + " " + article.summary
            for (n in Keywords.extractNumbers(text)) {
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
                    unit = when (code) {
                        "DXY" -> ""
                        "WTI" -> "美元/桶"
                        else -> "%"
                    },
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
        /** 伪主题：用它当筛选标签，复用现有的 topics LIKE 查询 */
        const val PSEUDO_BLOOMBERG = "彭博"

        const val MAX_ARTICLES = 300

        val EXPECTED_CODES =
            listOf("US10Y", "US2Y", "US30Y", "FED_TARGET", "DXY", "WTI", "BRENT")

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
