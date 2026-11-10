package com.finapp.daily.data

import android.content.Context

/**
 * 刷新编排层。指标三级降级，资讯走精确过滤 + 时间窗，公告按事项分类筛。
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

        // ---------------- 2. 境内直连补缺 ----------------
        if (listOf("DXY", "WTI", "BRENT").any { !indicators.containsKey(it) }) {
            runCatching { Sources.fetchDomesticQuotes() }.getOrNull()
                ?.forEach { indicators.putIfAbsent(it.code, it) }
        }

        // ---------------- 3. 资讯：多页 + 时间窗 + 精确过滤 ----------------
        val since = Sources.sinceLabel(Config.NEWS_DAYS)
        val raw = mutableListOf<Article>()

        runCatching { raw += Sources.fetchEastmoneyNews() }
            .onFailure { problems.add("东财资讯失败（${it.message}）") }
        runCatching { raw += Sources.fetchWscn(since) }
            .onFailure { problems.add("见闻快讯失败（${it.message}）") }

        val filtered = raw
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .filter { it.publishedAt.isEmpty() || it.publishedAt >= since }
            .distinctBy { it.url }
            .mapNotNull { article ->
                val topics = Keywords.classify(article.title, article.summary)
                if (topics.isEmpty()) null
                else article.copy(topics = topics.joinToString(","))
            }
            .sortedByDescending { it.publishedAt }

        // ---------------- 4. 公告：东财分类筛，失败退回巨潮 ----------------
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

        // ---------------- 5. 指标仍缺 → 资讯提取 ----------------
        val missing = EXPECTED_CODES.filter { !indicators.containsKey(it) }
        if (missing.isNotEmpty() && filtered.isNotEmpty()) {
            extractFromNews(filtered, missing).forEach { indicators.putIfAbsent(it.code, it) }
        }

        // ---------------- 6. 落库 ----------------
        val indicatorList = indicators.values.toList()
        db.upsertIndicators(indicatorList)
        db.insertArticles(filtered)
        db.insertAnnouncements(announcements)

        // ---------------- 7. 综述 ----------------
        val counts = db.counts()
        db.saveDigest(
            Sources.today(),
            Digest.build(indicatorList, counts.second, counts.third, missing)
        )
        db.sweep()

        return RefreshResult(
            indicatorCount = indicatorList.size,
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

    fun lastUpdatedMillis(): Long = db.lastUpdatedAt()

    fun lastUpdated(): String? {
        val ts = db.lastUpdatedAt()
        if (ts <= 0) return null
        return Sources.formatTs(ts)
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
