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

    /**
     * 云端 JSON 的多个入口，按实测可达性排序（国内网络实测结果）：
     *   fastly / gcore / testingcf  ✅ 200
     *   ghproxy.net / gh-proxy.com  ✅ 200
     *   cdn.jsdelivr.net            ❌ 国内不通（放最后，避免每次先等它超时）
     * 每个只等 [CLOUD_TIMEOUT_MS]，6 个全失败也控制在 20 秒左右。
     */
    val CLOUD_MIRRORS = listOf(
        "https://fastly.jsdelivr.net/gh/$REPO/$PATH",
        "https://gcore.jsdelivr.net/gh/$REPO/$PATH",
        "https://testingcf.jsdelivr.net/gh/$REPO/$PATH",
        "https://ghproxy.net/https://raw.githubusercontent.com/$REPO@main/data/latest.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO@main/data/latest.json",
        "https://cdn.jsdelivr.net/gh/$REPO/$PATH"
    )

    /** 单个镜像的超时，短一点才能快速换下一个 */
    const val CLOUD_TIMEOUT_MS = 6000

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
    const val WSCN_PAGES = 8
    const val WSCN_PAGE_SIZE = 100

    /** 公告：抓几页、每页多少条 */
    const val ANNOUNCEMENT_PAGES = 3
    const val ANNOUNCEMENT_PAGE_SIZE = 100
}
