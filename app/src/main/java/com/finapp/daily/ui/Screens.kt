package com.finapp.daily.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.finapp.daily.data.Config
import com.finapp.daily.data.Indicator
import com.finapp.daily.data.Repo
import kotlin.math.roundToInt

private val Up = Color(0xFFD32F2F)
private val Down = Color(0xFF2E7D32)

@Composable
fun FinanceApp(state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("概览", "资讯", "公告", "收藏")
    var showClear by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            Header(state, onClear = { showClear = true })
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title, fontSize = 14.sp) }
                    )
                }
            }
            when (tab) {
                0 -> OverviewScreen(state)
                1 -> NewsScreen(state)
                2 -> AnnouncementScreen(state)
                else -> FavoritesScreen(state)
            }
        }
    }

    if (showClear) {
        ClearDialog(state) { showClear = false }
    }
}

@Composable
private fun Header(state: AppState, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("财经每日", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                (state.lastUpdated?.let { "更新于 $it" } ?: "尚未更新") + "  v" + Config.VERSION_NAME,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = { state.refresh() }) { Text("刷新") }
        }
        TextButton(onClick = onClear) { Text("清理") }
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

/** 概览顶部只放这三个行情方框；几年期的美债写在综述里 */
private val CARD_CODES = listOf("DXY", "WTI", "BRENT")

@Composable
private fun OverviewScreen(state: AppState) {
    var detail by remember { mutableStateOf<Article?>(null) }

    val cards = CARD_CODES.mapNotNull { code ->
        state.indicators.firstOrNull { it.code == code }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (cards.isEmpty()) {
            item { EmptyHint("还没有行情数据，点右上角「刷新」试一下") }
        } else {
            item {
                Row(
                    Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    cards.forEach { ind ->
                        IndicatorCard(ind, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }

        state.digest?.let { item { DigestCard(it) } }

        val top = state.articles.take(5)
        if (top.isNotEmpty()) {
            item { SectionTitle("今日要闻") }
            items(top) { article ->
                ArticleRow(
                    article,
                    onClick = { detail = article },
                    onFav = { state.toggleArticleFavorite(article) }
                )
            }
        }
    }

    detail?.let { article ->
        ArticleDetailDialog(article) { detail = null }
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
            // 单位放在数字下面：三个方框等宽，竖排单位会被挤变形
            Text(ind.value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (ind.unit.isNotEmpty()) {
                Text(
                    ind.unit,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
private fun Badge(text: String) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
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
    val filters = listOf("全部", Repo.PSEUDO_BLOOMBERG, "美债", "美元", "加息", "原油")
    var detail by remember { mutableStateOf<Article?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { topic ->
                val selected = (state.topicFilter ?: "全部") == topic
                FilterChip(
                    selected = selected,
                    onClick = { state.setTopic(if (topic == "全部") null else topic) },
                    label = { Text(topic, fontSize = 12.sp) }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("排序", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(
                selected = !state.sortByRelevance,
                onClick = { state.setSort(false) },
                label = { Text("最新", fontSize = 12.sp) }
            )
            FilterChip(
                selected = state.sortByRelevance,
                onClick = { state.setSort(true) },
                label = { Text("相关度", fontSize = 12.sp) }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "共 ${state.articles.size} 条",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 相关度门槛：低于这个分数的资讯不显示，用户自己调
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "相关度≥",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var slider by remember(state.minRelevance) {
                mutableStateOf(state.minRelevance.toFloat())
            }
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { state.applyMinRelevance(slider.roundToInt()) },
                valueRange = 0f..50f,
                steps = 24,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                if (state.minRelevance == 0) "不限" else "${state.minRelevance}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (state.articles.isEmpty()) {
            EmptyHint(
                "近 ${Config.NEWS_DAYS} 天没有符合条件的资讯。\n" +
                    "可以试试调低上面的相关度门槛，或者换一个主题。"
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.articles) { article ->
                    ArticleRow(
                        article,
                        onClick = { detail = article },
                        onFav = { state.toggleArticleFavorite(article) }
                    )
                }
            }
        }
    }

    detail?.let { article ->
        ArticleDetailDialog(article) { detail = null }
    }
}

@Composable
private fun ArticleRow(
    article: Article,
    onClick: () -> Unit = {},
    onFav: () -> Unit = {}
) {
    val isBloomberg = article.topics.split(",").contains(Repo.PSEUDO_BLOOMBERG)
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBloomberg) {
                    Badge(Repo.PSEUDO_BLOOMBERG)
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
                Spacer(Modifier.width(6.dp))
                FavStar(article.favorite, onFav)
            }
        }
    }
}

/**
 * 站内详情。见闻快讯正文我们本来就抓到了，直接在这里读，不跳转、不用登录。
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
                    "${article.source} · ${article.publishedAt} · 相关度 ${article.score}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(article.summary, fontSize = 13.sp, lineHeight = 21.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
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
    var showPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 类型筛选
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val types = listOf("全部") + state.annCategories
            types.forEach { type ->
                val selected = (state.annCategory ?: "全部") == type
                FilterChip(
                    selected = selected,
                    onClick = { state.filterByCategory(if (type == "全部") null else type) },
                    label = { Text(type, fontSize = 12.sp) }
                )
            }
        }

        // 公司筛选
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.annCompany != null,
                onClick = { showPicker = true },
                label = {
                    Text(
                        state.annCompany?.let { it.second.ifBlank { it.first } } ?: "按公司筛选",
                        fontSize = 12.sp
                    )
                }
            )
            if (state.annCompany != null) {
                TextButton(onClick = { state.filterByCompany(null) }) {
                    Text("清除", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "共 ${state.announcements.size} 条",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.announcements.isEmpty()) {
            EmptyHint("没有符合条件的重大事项公告。\n换个类型或清除公司筛选试试。")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.announcements) { ann ->
                    AnnouncementRow(ann, onFav = { state.toggleAnnouncementFavorite(ann) })
                }
            }
        }
    }

    if (showPicker) {
        CompanyPickerDialog(
            companies = state.annCompanies,
            onDismiss = { showPicker = false },
            onPick = { picked ->
                state.filterByCompany(picked)
                showPicker = false
            }
        )
    }
}

@Composable
private fun CompanyPickerDialog(
    companies: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (Pair<String, String>?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) companies
    else companies.filter { it.first.contains(query) || it.second.contains(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按公司筛选", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("输入代码或名称", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "共 ${filtered.size} 家公司",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        TextButton(
                            onClick = { onPick(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("不限公司", fontSize = 13.sp) }
                    }
                    items(filtered) { company ->
                        TextButton(
                            onClick = { onPick(company) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                listOf(company.first, company.second)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" "),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun AnnouncementRow(ann: Announcement, onFav: () -> Unit = {}) {
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
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ann.category.isNotBlank()) {
                    Text(
                        ann.category,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                FavStar(ann.favorite, onFav)
            }
        }
    }
}

@Composable
private fun ClearDialog(state: AppState, onDismiss: () -> Unit) {
    var keepFav by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理本地数据", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "资讯和公告都存在手机本地，默认保留 ${Config.RETENTION_DAYS} 天，超期会自动清理。" +
                        "也可以现在手动清理，腾出空间。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { keepFav = !keepFav }
                ) {
                    Checkbox(checked = keepFav, onCheckedChange = { keepFav = it })
                    Text("保留已收藏的内容", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { state.clearOlderThan(7, keepFav); onDismiss() }) {
                    Text("清理7天前", fontSize = 13.sp)
                }
                TextButton(onClick = { state.clearOlderThan(30, keepFav); onDismiss() }) {
                    Text("清理30天前", fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { state.clearAll(keepFav); onDismiss() }) {
                    Text("全部清空", fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 13.sp) }
            }
        }
    )
}

// ------------------------------------------------------------------ 收藏

@Composable
private fun FavStar(favorite: Boolean, onToggle: () -> Unit) {
    Text(
        text = if (favorite) "★" else "☆",
        fontSize = 18.sp,
        color = if (favorite) Color(0xFFFFB300)
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun FavoritesScreen(state: AppState) {
    var detail by remember { mutableStateOf<Article?>(null) }
    val arts = state.favoriteArticles
    val anns = state.favoriteAnnouncements

    if (arts.isEmpty() && anns.isEmpty()) {
        EmptyHint("还没有收藏。\n在「资讯」或「公告」里点条目右侧的 ☆ 就能收藏。")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (anns.isNotEmpty()) {
                item { SectionTitle("收藏的公告 · ${anns.size}") }
                items(anns) { ann ->
                    AnnouncementRow(ann, onFav = { state.toggleAnnouncementFavorite(ann) })
                }
            }
            if (arts.isNotEmpty()) {
                item { SectionTitle("收藏的资讯 · ${arts.size}") }
                items(arts) { article ->
                    ArticleRow(
                        article,
                        onClick = { detail = article },
                        onFav = { state.toggleArticleFavorite(article) }
                    )
                }
            }
        }
    }

    detail?.let { article ->
        ArticleDetailDialog(article) { detail = null }
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
