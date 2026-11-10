package com.finapp.daily.data

/** 一条指标（美债收益率、美元指数、油价、Fed 利率） */
data class Indicator(
    val code: String,
    val title: String,
    val value: String,          // 统一用字符串，"3.75-4.00" 这种区间也能装
    val unit: String,
    val change: Double?,
    val asOf: String,
    /** official = 官方/接口数据；news = 从资讯正文提取；local = 手机直连境内源 */
    val precision: String,
    val source: String
) {
    val isNumeric: Boolean get() = value.toDoubleOrNull() != null
}

/** 一条资讯 */
data class Article(
    val id: Long = 0,
    val title: String,
    val summary: String,
    val source: String,
    val url: String,
    val publishedAt: String,
    /** 命中的主题：美债 / 美元 / 加息 / 原油，逗号分隔 */
    val topics: String = ""
)

/** 一条上市公司公告 */
data class Announcement(
    val id: Long = 0,
    val code: String,
    val name: String,
    val title: String,
    val category: String,
    val url: String,
    val publishedAt: String
)

/** 一次抓取的结果 */
data class RefreshResult(
    val indicatorCount: Int = 0,
    val articleCount: Int = 0,
    val announcementCount: Int = 0,
    val error: String? = null
) {
    val isEmpty: Boolean get() = indicatorCount == 0 && articleCount == 0 && announcementCount == 0
}
