package com.finapp.daily.data

/**
 * 全局配置。
 *
 * ⚠️ 云端地址拼接顺序很关键：`@分支` 必须紧跟在仓库名后面，
 *    .../gh/Unie7/finance-daily@main/data/latest.json   ✅
 *    .../gh/Unie7/finance-daily/data/latest.json@main   ❌ 会返回 404
 * 之前就是因为这个拼错，导致所有镜像全部 404。
 */
object Config {

    const val VERSION_NAME = "0.8.1"

    private const val REPO = "Unie7/finance-daily"
    private const val BRANCH = "main"
    private const val DATA_FILE = "data/latest.json"

    /**
     * 多个入口按实测可达性排序（国内网络 curl 实测）：
     *   fastly / gcore / testingcf.jsdelivr  ✅ 200
     *   ghproxy.net / gh-proxy.com           ✅ 200
     *   cdn.jsdelivr.net                     ❌ 国内不通，放最后
     */
    val CLOUD_MIRRORS = listOf(
        "https://fastly.jsdelivr.net/gh/$REPO@$BRANCH/$DATA_FILE",
        "https://gcore.jsdelivr.net/gh/$REPO@$BRANCH/$DATA_FILE",
        "https://testingcf.jsdelivr.net/gh/$REPO@$BRANCH/$DATA_FILE",
        "https://ghproxy.net/https://raw.githubusercontent.com/$REPO/$BRANCH/$DATA_FILE",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/$BRANCH/$DATA_FILE",
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/$DATA_FILE"
    )

    /** 单个镜像的超时，短一点才能快速换下一个 */
    const val CLOUD_TIMEOUT_MS = 6000

    /** 调试用：非 null 时只打这个地址 */
    val DEBUG_CLOUD_URL: String? = null

    /** 打开 App 后，距上次刷新超过这个时间才自动抓 */
    const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    /** 资讯回溯天数 */
    const val NEWS_DAYS = 7

    /** 东方财富资讯：翻几页、每页多少条 */
    const val EASTMONEY_NEWS_PAGES = 14
    const val EASTMONEY_NEWS_PAGE_SIZE = 50

    /** 华尔街见闻 7x24：翻几页、每页多少条 */
    const val WSCN_PAGES = 16
    const val WSCN_PAGE_SIZE = 100

    /** 公告：抓几页、每页多少条 */
    const val ANNOUNCEMENT_PAGES = 3
    const val ANNOUNCEMENT_PAGE_SIZE = 100

    /** 本地默认保留天数（超过自动清理，也可以手动清） */
    const val RETENTION_DAYS = 90
}
