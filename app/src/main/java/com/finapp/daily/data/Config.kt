package com.finapp.daily.data

/**
 * 全局配置。
 *
 * 注意 [CLOUD_URL]：生产用 jsDelivr 从 GitHub 仓库取云端聚合好的指标。
 * 但 jsDelivr 在国内某些网络环境下不可达，所以：
 *   1. 取不到时降级到境内直连源（[DomesticQuotes]）
 *   2. 再取不到就用资讯正文提取的数字（精度最低，界面必须标注）
 *
 * 在本机模拟器里调试时 jsDelivr 不通，可用 [DEBUG_CLOUD_URL] 指向本地假服务器
 * （10.0.2.2 是模拟器访问宿主机 localhost 的特殊地址）。
 */
object Config {

    const val CLOUD_URL =
        "https://cdn.jsdelivr.net/gh/Unie7/finance-daily@main/data/latest.json"

    /** 置为非 null 则优先用它（本机调试用），正式包保持 null */
    val DEBUG_CLOUD_URL: String? = null

    val cloudUrl: String
        get() = DEBUG_CLOUD_URL ?: CLOUD_URL

    /** 打开 App 后，距上次刷新超过这个时间才自动抓 */
    const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    /** 资讯抓多少条 */
    const val NEWS_LIMIT = 60

    /** 公告抓几页 */
    const val ANNOUNCEMENT_PAGES = 2
    const val ANNOUNCEMENT_PAGE_SIZE = 30
}
