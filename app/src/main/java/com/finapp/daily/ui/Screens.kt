package com.finapp.daily.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finapp.daily.data.Announcement
import com.finapp.daily.data.Article
import com.finapp.daily.data.Indicator
import com.finapp.daily.data.Keywords

private val Up = Color(0xFFD32F2F)
private val Down = Color(0xFF2E7D32)

@Composable
fun FinanceApp(state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("概览", "彭博资讯", "公告")

    Scaffold { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            Header(state)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title, fontSize = 15.sp) }
                    )
                }
            }
            when (tab) {
                0 -> OverviewScreen(state)
                1 -> NewsScreen(state)
                else -> AnnouncementScreen(state)
            }
        }
    }
}

@Composable
private fun Header(state: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("财经每日", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                state.lastUpdated?.let { "更新于 $it" } ?: "尚未更新",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = { state.refresh() }) { Text("刷新") }
        }
    }
    state.statusText?.let {
        Text(
            it,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }
    state.errorDetail?.let {
        Text(
            "⚠ $it",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }
}

// ------------------------------------------------------------------ 概览

@Composable
private fun OverviewScreen(state: AppState) {
    val indicators = state.indicators
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (indicators.isEmpty()) {
            item { EmptyHint("还没有指标数据，点右上角「刷新」试一下") }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    indicators.take(6).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { ind -> IndicatorCard(ind, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        state.digest?.let { item { DigestCard(it) } }

        val top = state.articles.take(5)
        if (top.isNotEmpty()) {
            item { SectionTitle("今日彭博") }
            items(top) { article -> ArticleRow(article) }
        }
    }
}

@Composable
private fun IndicatorCard(ind: Indicator, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ind.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                when (ind.precision) {
                    "news" -> Badge("资讯提取")
                    "local" -> Badge("境内源")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ind.value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (ind.unit.isNotEmpty()) {
                    Text(
                        ind.unit,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                    )
                }
            }
            ind.change?.let { c ->
                val color = when {
                    c > 0 -> Up
                    c < 0 -> Down
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    (if (c > 0) "+" else "") + formatChange(c),
                    fontSize = 12.sp,
                    color = color
                )
            }
            Text(ind.asOf, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Badge(text: String, container: Color = Color.Unspecified) {
    val bg = if (container == Color.Unspecified)
        MaterialTheme.colorScheme.tertiaryContainer else container
    Box(
        Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 9.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun DigestCard(text: String) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text("每日综述", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

// ------------------------------------------------------------------ 资讯

@Composable
private fun NewsScreen(state: AppState) {
    val topics = listOf("全部", "美债", "美元", "加息", "原油")
    var detail by remember { mutableStateOf<Article?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            topics.forEach { topic ->
                val selected = (state.topicFilter ?: "全部") == topic
                FilterChip(
                    selected = selected,
                    onClick = { state.setTopic(if (topic == "全部") null else topic) },
                    label = { Text(topic, fontSize = 12.sp) }
                )
            }
        }
        if (state.articles.isEmpty()) {
            EmptyHint(
                "近 3 天没有抓到彭博社相关内容。\n" +
                    "彭博原文有付费墙抓不到，这里收的是中文站转载并标注「（彭博）」的部分，" +
                    "某几天没有属正常。"
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.articles) { article ->
                    ArticleRow(article) { detail = article }
                }
            }
        }
    }

    detail?.let { article ->
        ArticleDetailDialog(article) { detail = null }
    }
}

@Composable
private fun ArticleRow(article: Article, onClick: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (Keywords.isBloomberg(article.title + " " + article.summary)) {
                    Badge("彭博")
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    article.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3
                )
            }
            if (article.summary.isNotEmpty() && article.summary != article.title) {
                Spacer(Modifier.height(4.dp))
                Text(
                    article.summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(article.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    article.publishedAt,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    article.topics.replace(",", " · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * 站内详情。见闻的快讯正文我们已经抓到了，直接在这里读，不用跳转、不用登录。
 */
@Composable
private fun ArticleDetailDialog(article: Article, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(article.title, fontSize = 15.sp, lineHeight = 21.sp) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "${article.source} · ${article.publishedAt}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(article.summary, fontSize = 13.sp, lineHeight = 21.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            if (article.url.isNotBlank()) {
                TextButton(onClick = {
                    openUrl(context, article.url)
                    onDismiss()
                }) { Text("浏览器打开") }
            }
        }
    )
}

// ------------------------------------------------------------------ 公告

@Composable
private fun AnnouncementScreen(state: AppState) {
    if (state.announcements.isEmpty()) {
        EmptyHint(
            "暂无重大事项公告。\n" +
                "只保留重组 / 业绩 / 合同 / 股权变动 / 问询 / 退市等分类。"
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.announcements) { ann -> AnnouncementRow(ann) }
        }
    }
}

@Composable
private fun AnnouncementRow(ann: Announcement) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { openUrl(context, ann.url) }) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(
                    listOf(ann.code, ann.name).filter { it.isNotBlank() }.joinToString(" "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    ann.publishedAt,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(ann.title, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (ann.category.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(ann.category, fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

// ------------------------------------------------------------------ 通用

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        // 没有浏览器就静默忽略
    }
}

private fun formatChange(v: Double): String {
    val rounded = Math.round(v * 1000.0) / 1000.0
    return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
        Math.abs(rounded).toLong().toString()
    } else {
        rounded.toString()
    }
}
