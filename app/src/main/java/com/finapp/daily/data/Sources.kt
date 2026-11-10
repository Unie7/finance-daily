package com.finapp.daily.data

import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 四个数据源的抓取实现。全部是公开接口，不需要任何 key。
 *
 * 分工：
 *   - 云端（GitHub Actions）抓境外指标 → [CloudIndicators]
 *   - 手机直连抓境内源 → [DomesticQuotes] / [WscnNews] / [EastmoneyNews] / [CninfoAnnouncements]
 */
object Sources {

    // ------------------------------------------------------------ 云端指标

    /**
     * 解析云端 latest.json。
     * 结构见仓库 README：{ generated_at, indicators: { CODE: {value,change,as_of,...} } }
     */
    fun parseCloudIndicators(json: String): List<Indicator> {
        val root = JSONObject(json)
        val indicators = root.optJSONObject("indicators") ?: return emptyList()
        val out = mutableListOf<Indicator>()
        indicators.keys().forEach { code ->
            val item = indicators.optJSONObject(code) ?: return@forEach
            val rawValue = item.opt("value")
            val value = when (rawValue) {
                is Number -> trimNumber(rawValue.toDouble())
                is String -> rawValue
                else -> null
            } ?: return@forEach

            // 云端只有官方/接口数据，没有资讯提取
            val precision = "official"
            out.add(
                Indicator(
                    code = code,
                    title = item.optString("title", code),
                    value = value,
                    unit = item.optString("unit", ""),
                    change = if (item.has("change") && !item.isNull("change"))
                        item.optDouble("change") else null,
                    asOf = item.optString("as_of", ""),
                    precision = precision,
                    source = "云端"
                )
            )
        }
        return out
    }

    fun fetchCloud(): List<Indicator> = parseCloudIndicators(Http.get(Config.cloudUrl))

    // ------------------------------------------------------------ 境内行情

    /**
     * 境内兜底行情：
     *   美元指数 ← 东方财富 100.UDI
     *   WTI / 布伦特 ← 新浪外盘期货 hf_CL / hf_OIL
     * 这两个接口都实测可用，字段位置已核对。
     */
    fun fetchDomesticQuotes(): List<Indicator> {
        val out = mutableListOf<Indicator>()
        out += fetchEastmoneyDxy()
        out += fetchSinaOil()
        return out
    }

    private fun fetchEastmoneyDxy(): List<Indicator> {
        return try {
            val url = "https://push2.eastmoney.com/api/qt/ulist.np/get" +
                "?fltt=2&invt=2&secids=100.UDI&fields=f2,f3,f4,f12,f14,f18"
            val text = Http.get(url)
            val diff = JSONObject(text).optJSONObject("data")
                ?.optJSONArray("diff") ?: return emptyList()
            if (diff.length() == 0) return emptyList()
            val row = diff.getJSONObject(0)
            val price = row.optDouble("f2", Double.NaN)
            if (price.isNaN()) return emptyList()
            listOf(
                Indicator(
                    code = "DXY",
                    title = "美元指数",
                    value = trimNumber(price),
                    unit = "",
                    change = row.optDouble("f4", Double.NaN).takeIf { !it.isNaN() },
                    asOf = today(),
                    precision = "local",
                    source = "东方财富"
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 新浪外盘期货字段（已核对）：
     *   [0] 现价  [2] 买价  [3] 卖价  [4] 最高  [5] 最低
     *   [6] 时间  [7] 昨收  [8] 开盘  [12] 日期  [13] 名称
     * 返回 GBK 编码，必须带 Referer。
     */
    @SuppressLint("SimpleDateFormat")
    private fun fetchSinaOil(): List<Indicator> {
        return try {
            val text = Http.get(
                "https://hq.sinajs.cn/list=hf_CL,hf_OIL",
                headers = mapOf("Referer" to "https://finance.sina.com.cn"),
                charset = "GBK"
            )
            val out = mutableListOf<Indicator>()
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("var hq_str_hf_")) return@forEach
                val symbol = trimmed.substringAfter("hq_str_").substringBefore("=")
                val body = trimmed.substringAfter("\"").substringBeforeLast("\"")
                val f = body.split(",")
                if (f.size < 14) return@forEach
                val price = f[0].toDoubleOrNull() ?: return@forEach
                val prev = f[7].toDoubleOrNull()
                val (code, title) = when (symbol) {
                    "hf_CL" -> "WTI" to "WTI原油"
                    "hf_OIL" -> "BRENT" to "布伦特原油"
                    else -> return@forEach
                }
                out.add(
                    Indicator(
                        code = code,
                        title = title,
                        value = trimNumber(price),
                        unit = "美元/桶",
                        change = prev?.let { round3(price - it) },
                        asOf = f[12],
                        precision = "local",
                        source = "新浪财经"
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ 资讯

    /** 华尔街见闻 7x24 快讯。字段：id / content_text / display_time(unix 秒) / uri */
    fun parseWscn(json: String): List<Article> {
        val items = JSONObject(json).optJSONObject("data")
            ?.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Article>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val text = item.optString("content_text").ifBlank {
                item.optString("content")
            }.replace(Regex("<[^>]+>"), "").trim()
            if (text.isEmpty()) continue
            val id = item.optString("id")
            val url = item.optString("uri").ifBlank { "https://wallstreetcn.com/livenews/$id" }
            val ts = item.optLong("display_time", 0L)
            out.add(
                Article(
                    title = text.take(60),
                    summary = text,
                    source = "华尔街见闻",
                    url = url,
                    publishedAt = if (ts > 0) formatTime(ts * 1000) else ""
                )
            )
        }
        return out
    }

    fun fetchWscn(limit: Int = Config.NEWS_LIMIT): List<Article> = parseWscn(
        Http.get(
            "https://api-one.wallstcn.com/apiv1/content/lives" +
                "?channel=global-channel&client=pc&limit=$limit"
        )
    )

    /** 东方财富资讯。字段：code / showTime / title / url / summary */
    fun parseEastmoneyNews(json: String): List<Article> {
        val list = JSONObject(json).optJSONObject("data")
            ?.optJSONArray("list") ?: return emptyList()
        val out = mutableListOf<Article>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            val code = item.optString("code")
            val url = item.optString("url").ifBlank {
                "https://finance.eastmoney.com/a/$code.html"
            }
            out.add(
                Article(
                    title = title,
                    summary = item.optString("summary").trim(),
                    source = "东方财富",
                    url = url,
                    publishedAt = item.optString("showTime")
                )
            )
        }
        return out
    }

    fun fetchEastmoneyNews(pageSize: Int = 30): List<Article> = parseEastmoneyNews(
        Http.get(
            "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns" +
                "?client=web&biz=web_news_col&column=350&order=1&needInteractData=0" +
                "&page_index=1&page_size=$pageSize&req_trace=${System.currentTimeMillis()}" +
                "&fields=code,showtime,title,url,summary&types=1,20"
        )
    )

    // ------------------------------------------------------------ 公告

    /**
     * 巨潮资讯公告。POST 表单，需要 Referer。
     * 返回 announcements[]，字段 secCode / secName / announcementTitle /
     * announcementTime(毫秒) / adjunctUrl / announcementType。
     */
    fun parseCninfo(json: String): List<Announcement> {
        val root = JSONObject(json)
        val arr = root.opt("announcements") ?: return emptyList()
        if (arr !is JSONArray) return emptyList()
        val out = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("announcementTitle")
                .replace("<em>", "").replace("</em>", "").trim()
            if (title.isEmpty()) continue
            val adjunct = item.optString("adjunctUrl")
            val url = if (adjunct.isEmpty()) "" else "https://static.cninfo.com.cn/$adjunct"
            val ts = item.optLong("announcementTime", 0L)
            out.add(
                Announcement(
                    code = item.optString("secCode"),
                    name = item.optString("secName"),
                    title = title,
                    category = item.optString("announcementType"),
                    url = url,
                    publishedAt = if (ts > 0) formatTime(ts) else ""
                )
            )
        }
        return out
    }

    fun fetchCninfo(
        pages: Int = Config.ANNOUNCEMENT_PAGES,
        pageSize: Int = Config.ANNOUNCEMENT_PAGE_SIZE
    ): List<Announcement> {
        val out = mutableListOf<Announcement>()
        for (page in 1..pages) {
            val text = Http.postForm(
                "https://www.cninfo.com.cn/new/hisAnnouncement/query",
                form = mapOf(
                    "pageNum" to page.toString(),
                    "pageSize" to pageSize.toString(),
                    "column" to "szse",
                    "tabName" to "fulltext",
                    "plate" to "",
                    "stock" to "",
                    "searchkey" to "",
                    "secDate" to "",
                    "tdate" to "",
                    "type" to "",
                    "isHLtitle" to "true"
                ),
                headers = mapOf(
                    "Referer" to "https://www.cninfo.com.cn/new/commonUrl?url=disclosure/list/notice",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            val batch = parseCninfo(text)
            if (batch.isEmpty()) break
            out += batch
        }
        return out
    }

    // ------------------------------------------------------------ 工具

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    fun trimNumber(v: Double): String {
        val rounded = round3(v)
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    @SuppressLint("SimpleDateFormat")
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
