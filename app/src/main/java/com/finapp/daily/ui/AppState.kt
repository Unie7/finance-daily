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
import com.finapp.daily.data.Repo
import com.finapp.daily.data.RefreshResult
import java.util.concurrent.Executors

/**
 * 简单的状态容器。不用 androidx.lifecycle.ViewModel —— 这个 App 只有一个界面状态，
 * 用 Compose 的 mutableStateOf 就够了，也少一个依赖。
 *
 * 刷新策略：打开 App 时，如果距上次刷新超过 [Config.AUTO_REFRESH_INTERVAL_MS]
 * 就自动抓一次；也可以手动下拉/点刷新。
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

    /** 资讯页的主题筛选，null / "全部" 表示不过滤 */
    var topicFilter by mutableStateOf<String?>(null)
        private set

    fun setTopic(topic: String?) {
        topicFilter = topic
        loadArticles()
    }

    fun loadFromDb() {
        executor.execute {
            val ind = repo.indicators()
            val art = repo.articles(topicFilter)
            val ann = repo.announcements()
            val dig = repo.digest()
            val upd = repo.lastUpdated()
            main.post {
                indicators = ind
                articles = art
                announcements = ann
                digest = dig
                lastUpdated = upd
            }
        }
    }

    private fun loadArticles() {
        executor.execute {
            val art = repo.articles(topicFilter)
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

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        statusText = "正在更新…"
        executor.execute {
            val result: RefreshResult = try {
                repo.refresh()
            } catch (e: Exception) {
                RefreshResult(error = e.message ?: "未知错误")
            }
            val ind = repo.indicators()
            val art = repo.articles(topicFilter)
            val ann = repo.announcements()
            val dig = repo.digest()
            val upd = repo.lastUpdated()
            main.post {
                indicators = ind
                articles = art
                announcements = ann
                digest = dig
                lastUpdated = upd
                refreshing = false
                statusText = when {
                    result.isEmpty -> result.error ?: "没有取到数据"
                    result.error != null -> "更新完成（部分失败：${result.error}）"
                    else -> "更新完成：指标 ${result.indicatorCount} · 资讯 ${result.articleCount} · 公告 ${result.announcementCount}"
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
