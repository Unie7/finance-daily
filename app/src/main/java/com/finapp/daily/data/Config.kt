package com.finapp.daily.data

/**
 * 全局配置。
 *
 * 云端指标通过 jsDelivr 从 GitHub 仓库读取。jsDelivr 在国内不同网络下可达性不一样，
 * 所以配了多个镜像依次尝试；全失败就降级到境内直连源。
 */
object Config {

    private const val REPO = "Unie7/finance-daily"
    private const val PATH = "data/latest.json@main"

    /** 云端 JSON 的多个入口，按顺序尝试，谁先通用谁 */
    val CLOUD_MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO/$PATH",
        "https://fastly.jsdelivr.net/gh/$REPO/$PATH",
        "https://gcore.jsdelivr.net/gh/$REPO/$PATH",
        "https://testingcf.jsdelivr.net/gh/$REPO/$PATH"
    )

    /** 调试用：非 null 时只打这个地址（模拟器访问宿主机用 10.0.2.2） */
    val DEBUG_CLOUD_URL: String? = null

    /** 打开 App 后，距上次刷新超过这个时间才自动抓 */
    const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    /** 资讯回溯天数 */
    const val NEWS_DAYS = 3

    /** 东方财富资讯：翻几页、每页多少条（页数越多覆盖天数越长） */
    const val EASTMONEY_NEWS_PAGES = 8
    const val EASTMONEY_NEWS_PAGE_SIZE = 50

    /** 华尔街见闻 7x24：翻几页、每页多少条 */
    const val WSCN_PAGES = 4
    const val WSCN_PAGE_SIZE = 100

    /** 公告：抓几页、每页多少条 */
    const val ANNOUNCEMENT_PAGES = 3
    const val ANNOUNCEMENT_PAGE_SIZE = 100
}
