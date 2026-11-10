package com.finapp.daily.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finapp.daily.data.Announcement
import com.finapp.daily.data.Article
import com.finapp.daily.data.Config
import com.finapp.daily.data.Indicator
import com.finapp.daily.data.RefreshResult
import com.finapp.daily.data.Repo
import java.util.concurrent.Executors

/**
 * 界面状态容器。刷新在单线程池里跑，结果用 Handler 回投主线程。
 */
class AppState(private val context: Context) {

    private val repo = Repo(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var indicators by mutableStateOf<List<Indicator>>(emptyList())
        private set
    var articles by mutableStateOf<List<Article>>(emptyList())
        private set
    var announcements by mutableStateOf<List<Announcement>>(emptyList())
        private set
    var digest by mutableStateOf<String?>(null)
        private set
    var lastUpdated by mutableStateOf<String?>(null)
        private set

    var refreshing by mutableStateOf(false)
        private set
    var statusText by mutableStateOf<String?>(null)
        private set

    /** 失败明细，直接展示给用户，方便定位是哪个源不通 */
    var errorDetail by mutableStateOf<String?>(null)
        private set

    /** 每个资讯源本轮抓取/保留条数，用于解释"为什么没有某家的内容" */
    var sourceStats by mutableStateOf<List<com.finapp.daily.data.SourceStat>>(emptyList())
        private set

    var topicFilter by mutableStateOf<String?>(null)
        private set

    fun setTopic(topic: String?) {
        topicFilter = topic
        loadArticles()
    }

    /** 资讯排序方式：false=最新，true=按相关度 */
    private val prefs = context.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE)

    var sortByRelevance by mutableStateOf(prefs.getBoolean("sort_relevance", false))
        private set

    fun setSort(relevance: Boolean) {
        sortByRelevance = relevance
        prefs.edit().putBoolean("sort_relevance", relevance).apply()
        loadArticles()
    }

    /** 相关度门槛：低于这个分数的资讯不显示。用户可以自己调。 */
    var minRelevance by mutableStateOf(prefs.getInt("min_relevance", 0))
        private set

    fun applyMinRelevance(value: Int) {
        val v = value.coerceIn(0, 50)
        minRelevance = v
        prefs.edit().putInt("min_relevance", v).apply()
        loadArticles()
    }

    // ---------------- 公告筛选 ----------------
    var annCategory by mutableStateOf<String?>(null)
        private set
    var annCompany by mutableStateOf<Pair<String, String>?>(null)
        private set
    var annCategories by mutableStateOf<List<String>>(emptyList())
        private set
    var annCompanies by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    fun filterByCategory(category: String?) {
        annCategory = category
        loadAnnouncements()
    }

    fun filterByCompany(company: Pair<String, String>?) {
        annCompany = company
        loadAnnouncements()
    }

    private fun loadAnnouncements() {
        executor.execute {
            val ann = repo.announcements(annCategory, annCompany?.first)
            main.post { announcements = ann }
        }
    }

    fun loadFromDb() {
        executor.execute {
            val ind = repo.indicators()
            val art = repo.articles(topicFilter, sortByRelevance, minRelevance)
            val ann = repo.announcements(annCategory, annCompany?.first)
            val dig = repo.digest()
            val upd = repo.lastUpdated()
            val cats = repo.announcementCategories()
            val comps = repo.announcementCompanies()
            val fa = repo.favoriteArticles()
            val fn = repo.favoriteAnnouncements()
            main.post {
                indicators = ind
                articles = art
                announcements = ann
                digest = dig
                lastUpdated = upd
                annCategories = cats
                annCompanies = comps
                favoriteArticles = fa
                favoriteAnnouncements = fn
            }
        }
    }

    private fun loadArticles() {
        executor.execute {
            val art = repo.articles(topicFilter, sortByRelevance, minRelevance)
            main.post { articles = art }
        }
    }

    /** 打开 App 时调用：数据过期（或从来没有）才刷新。 */
    fun refreshIfStale() {
        if (refreshing) return
        executor.execute {
            val lastMs = repo.lastUpdatedMillis()
            val stale = lastMs == 0L ||
                System.currentTimeMillis() - lastMs > Config.AUTO_REFRESH_INTERVAL_MS
            val upd = repo.lastUpdated()
            main.post {
                if (stale) refresh() else statusText = upd?.let { "上次更新 $it" }
            }
        }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        statusText = "正在更新…"
        errorDetail = null
        executor.execute {
            val result: RefreshResult = try {
                repo.refresh()
            } catch (e: Exception) {
                RefreshResult(error = e.message ?: e.javaClass.simpleName)
            }
            val ind = repo.indicators()
            val art = repo.articles(topicFilter, sortByRelevance, minRelevance)
            val ann = repo.announcements(annCategory, annCompany?.first)
            val dig = repo.digest()
            val upd = repo.lastUpdated()
            val cats = repo.announcementCategories()
            val comps = repo.announcementCompanies()
            val fa = repo.favoriteArticles()
            val fn = repo.favoriteAnnouncements()
            main.post {
                indicators = ind
                articles = art
                announcements = ann
                digest = dig
                lastUpdated = upd
                annCategories = cats
                annCompanies = comps
                favoriteArticles = fa
                favoriteAnnouncements = fn
                refreshing = false
                errorDetail = result.error
                sourceStats = result.stats
                statusText = when {
                    result.isEmpty -> "没有取到任何数据"
                    result.error != null ->
                        "更新完成：指标 ${result.indicatorCount} · 资讯 ${result.articleCount} · 公告 ${result.announcementCount}（有失败项）"
                    else ->
                        "更新完成：指标 ${result.indicatorCount} · 资讯 ${result.articleCount} · 公告 ${result.announcementCount}"
                }
            }
        }
    }

    // ---------------- 收藏 ----------------
    var favoriteArticles by mutableStateOf<List<Article>>(emptyList())
        private set
    var favoriteAnnouncements by mutableStateOf<List<Announcement>>(emptyList())
        private set

    fun toggleArticleFavorite(article: Article) {
        val target = !article.favorite
        executor.execute {
            repo.setArticleFavorite(article.url, target)
            val art = repo.articles(topicFilter, sortByRelevance, minRelevance)
            val favs = repo.favoriteArticles()
            main.post {
                articles = art
                favoriteArticles = favs
                statusText = if (target) "已收藏" else "已取消收藏"
            }
        }
    }

    fun toggleAnnouncementFavorite(ann: Announcement) {
        val target = !ann.favorite
        executor.execute {
            repo.setAnnouncementFavorite(ann.url, target)
            val list = repo.announcements(annCategory, annCompany?.first)
            val favs = repo.favoriteAnnouncements()
            main.post {
                announcements = list
                favoriteAnnouncements = favs
                statusText = if (target) "已收藏" else "已取消收藏"
            }
        }
    }

    private fun loadFavorites() {
        executor.execute {
            val fa = repo.favoriteArticles()
            val fn = repo.favoriteAnnouncements()
            main.post {
                favoriteArticles = fa
                favoriteAnnouncements = fn
            }
        }
    }

    // ---------------- 清理 ----------------

    /** 清理 N 天之前的内容；keepFavorites 为 true 时保留已收藏的 */
    fun clearOlderThan(days: Int, keepFavorites: Boolean = true) {
        executor.execute {
            val removed = repo.clearOlderThan(days, keepFavorites)
            val art = repo.articles(topicFilter, sortByRelevance, minRelevance)
            val ann = repo.announcements(annCategory, annCompany?.first)
            val fa = repo.favoriteArticles()
            val fn = repo.favoriteAnnouncements()
            main.post {
                articles = art
                announcements = ann
                favoriteArticles = fa
                favoriteAnnouncements = fn
                statusText = "已清理 $days 天前的内容，共 $removed 条" +
                    if (keepFavorites) "（收藏已保留）" else ""
            }
        }
    }

    /** 清空全部内容；keepFavorites 为 true 时只清未收藏的 */
    fun clearAll(keepFavorites: Boolean = true) {
        executor.execute {
            val removed = repo.clearAllContent(keepFavorites)
            val art = repo.articles(topicFilter, sortByRelevance, minRelevance)
            val ann = repo.announcements(annCategory, annCompany?.first)
            val fa = repo.favoriteArticles()
            val fn = repo.favoriteAnnouncements()
            main.post {
                articles = art
                announcements = ann
                favoriteArticles = fa
                favoriteAnnouncements = fn
                statusText = "已清空 $removed 条" + if (keepFavorites) "（收藏已保留）" else ""
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
