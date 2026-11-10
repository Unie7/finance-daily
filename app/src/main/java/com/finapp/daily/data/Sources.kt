package com.finapp.daily.data

import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据源抓取。全部是公开接口，不需要任何 key。
 *
 * 解析逻辑都抽成纯函数（parseXxx），只吃字符串、不碰网络，
 * 这样可以用真实报文在 JVM 上做单元测试，不需要模拟器。
 */
object Sources {

    // ------------------------------------------------------------ 云端指标

    fun parseCloudIndicators(json: String): List<Indicator> {
        val indicators = JSONObject(json).optJSONObject("indicators") ?: return emptyList()
        val out = mutableListOf<Indicator>()
        indicators.keys().forEach { code ->
            val item = indicators.optJSONObject(code) ?: return@forEach
            val rawValue = item.opt("value")
            val value = when (rawValue) {
                is Number -> trimNumber(rawValue.toDouble())
                is String -> rawValue
                else -> null
            } ?: return@forEach

            out.add(
                Indicator(
                    code = code,
                    title = item.optString("title", code),
                    value = value,
                    unit = item.optString("unit", ""),
                    change = if (item.has("change") && !item.isNull("change"))
                        item.optDouble("change") else null,
                    asOf = item.optString("as_of", ""),
                    precision = "official",
                    source = "云端"
                )
            )
        }
        return out
    }

    /**
     * 依次尝试多个 jsDelivr 镜像，全部失败时抛出带明细的异常，
     * 让界面能显示到底哪个地址出了什么问题。
     */
    fun fetchCloud(): List<Indicator> {
        val urls = Config.DEBUG_CLOUD_URL?.let { listOf(it) } ?: Config.CLOUD_MIRRORS
        val failures = mutableListOf<String>()
        for (url in urls) {
            val host = url.substringAfter("//").substringBefore("/")
            try {
                val list = parseCloudIndicators(Http.get(url, timeoutMs = 12000))
                if (list.isNotEmpty()) return list
                failures.add("$host 返回空")
            } catch (e: Exception) {
                failures.add("$host ${e.message?.take(50) ?: e.javaClass.simpleName}")
            }
        }
        throw RuntimeException(failures.joinToString(" / "))
    }

    // ------------------------------------------------------------ 境内行情

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
            val diff = JSONObject(Http.get(url)).optJSONObject("data")
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
     */
    private fun fetchSinaOil(): List<Indicator> {
        return try {
            val text = Http.get(
                "https://hq.sinajs.cn/list=hf_CL,hf_OIL",
                headers = mapOf("Referer" to "https://finance.sina.com.cn"),
                charset = "GBK"
            )
            parseSinaOil(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseSinaOil(text: String): List<Indicator> {
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
            val code: String
            val title: String
            when (symbol) {
                "hf_CL" -> { code = "WTI"; title = "WTI原油" }
                "hf_OIL" -> { code = "BRENT"; title = "布伦特原油" }
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
        return out
    }

    // ------------------------------------------------------------ 资讯

    /**
     * 解析一页见闻快讯。sinceLabel 之前的条目直接丢弃。
     */
    fun parseWscn(json: String, sinceLabel: String): List<Article> {
        val data = JSONObject(json).optJSONObject("data") ?: return emptyList()
        val items = data.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Article>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val text = item.optString("content_text").ifBlank {
                item.optString("content")
            }.replace(Regex("<[^>]+>"), "").trim()
            if (text.isEmpty()) continue
            val ts = item.optLong("display_time", 0L)
            val label = if (ts > 0) formatTs(ts * 1000) else ""
            if (label.isNotEmpty() && label < sinceLabel) continue
            val id = item.optString("id")
            out.add(
                Article(
                    title = text.take(60),
                    summary = text,
                    source = "华尔街见闻",
                    url = item.optString("uri").ifBlank {
                        "https://wallstreetcn.com/livenews/$id"
                    },
                    publishedAt = label
                )
            )
        }
        return out
    }

    /** 用 cursor 向前翻页，直到翻够页数或整页都超出时间窗。 */
    fun fetchWscn(sinceLabel: String): List<Article> {
        val out = mutableListOf<Article>()
        var cursor: String? = null
        for (page in 0 until Config.WSCN_PAGES) {
            val url = buildString {
                append("https://api-one.wallstcn.com/apiv1/content/lives")
                append("?channel=global-channel&client=pc&limit=")
                append(Config.WSCN_PAGE_SIZE)
                if (cursor != null) append("&cursor=").append(cursor)
            }
            val json = try {
                Http.get(url)
            } catch (e: Exception) {
                break
            }
            val pageArticles = try {
                parseWscn(json, sinceLabel)
            } catch (e: Exception) {
                break
            }
            out += pageArticles

            // 整页都没命中时间窗就说明翻过头了
            val rawItems = try {
                JSONObject(json).optJSONObject("data")?.optJSONArray("items")
            } catch (e: Exception) {
                null
            }
            if (rawItems == null || rawItems.length() == 0) break
            var oldestTs = Long.MAX_VALUE
            for (i in 0 until rawItems.length()) {
                val ts = rawItems.optJSONObject(i)?.optLong("display_time", 0L) ?: 0L
                if (ts in 1 until oldestTs) oldestTs = ts
            }
            if (oldestTs != Long.MAX_VALUE && formatTs(oldestTs * 1000) < sinceLabel) break

            val next = try {
                JSONObject(json).optJSONObject("data")?.optString("next_cursor")
            } catch (e: Exception) {
                null
            }
            if (next.isNullOrBlank()) break
            cursor = next
        }
        return out
    }

    fun parseEastmoneyNews(json: String): List<Article> {
        val list = JSONObject(json).optJSONObject("data")?.optJSONArray("list")
            ?: return emptyList()
        val out = mutableListOf<Article>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            val code = item.optString("code")
            out.add(
                Article(
                    title = title,
                    summary = item.optString("summary").trim(),
                    source = "东方财富",
                    url = item.optString("url").ifBlank {
                        "https://finance.eastmoney.com/a/$code.html"
                    },
                    publishedAt = item.optString("showTime").take(16)
                )
            )
        }
        return out
    }

    fun fetchEastmoneyNews(pages: Int = Config.EASTMONEY_NEWS_PAGES): List<Article> {
        val out = mutableListOf<Article>()
        for (page in 1..pages) {
            val url = "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns" +
                "?client=web&biz=web_news_col&column=350&order=1&needInteractData=0" +
                "&page_index=$page&page_size=${Config.EASTMONEY_NEWS_PAGE_SIZE}" +
                "&req_trace=${System.currentTimeMillis()}" +
                "&fields=code,showtime,title,url,summary&types=1,20"
            val json = try {
                Http.get(url)
            } catch (e: Exception) {
                break
            }
            val batch = try {
                parseEastmoneyNews(json)
            } catch (e: Exception) {
                break
            }
            if (batch.isEmpty()) break
            out += batch
        }
        return out
    }

    // ------------------------------------------------------------ 公告

    /**
     * 东方财富公告。相比巨潮的优势：直接返回「事项分类」，可精确筛重大事项。
     * 字段：art_code / codes[].stock_code / codes[].short_name /
     *       columns[].column_name / notice_date / title
     */
    fun parseEastmoneyAnnouncements(json: String): List<Announcement> {
        val list = JSONObject(json).optJSONObject("data")?.optJSONArray("list")
            ?: return emptyList()
        val out = mutableListOf<Announcement>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            val codeObj = item.optJSONArray("codes")?.optJSONObject(0)
            val code = codeObj?.optString("stock_code") ?: ""
            val name = codeObj?.optString("short_name") ?: ""
            val artCode = item.optString("art_code")
            val cols = item.optJSONArray("columns")
            val names = mutableListOf<String>()
            if (cols != null) {
                for (c in 0 until cols.length()) {
                    val n = cols.optJSONObject(c)?.optString("column_name") ?: continue
                    if (n.isNotEmpty()) names.add(n)
                }
            }
            out.add(
                Announcement(
                    code = code,
                    name = name,
                    title = title,
                    category = names.joinToString("/"),
                    url = if (code.isNotEmpty() && artCode.isNotEmpty())
                        "https://data.eastmoney.com/notices/detail/$code/$artCode.html"
                    else "",
                    publishedAt = item.optString("notice_date").take(16)
                )
            )
        }
        return out
    }

    fun fetchEastmoneyAnnouncements(pages: Int = Config.ANNOUNCEMENT_PAGES): List<Announcement> {
        val out = mutableListOf<Announcement>()
        for (page in 1..pages) {
            val url = "https://np-anotice-stock.eastmoney.com/api/security/ann" +
                "?sr=-1&page_size=${Config.ANNOUNCEMENT_PAGE_SIZE}&page_index=$page" +
                "&ann_type=A&client_source=web&f_node=0&s_node=0"
            val json = try {
                Http.get(url)
            } catch (e: Exception) {
                break
            }
            val batch = try {
                parseEastmoneyAnnouncements(json)
            } catch (e: Exception) {
                break
            }
            if (batch.isEmpty()) break
            out += batch
        }
        return out
    }

    /** 巨潮兜底源（东财失败时用）。没有分类字段，只能按标题筛。 */
    fun fetchCninfo(pages: Int = 2, pageSize: Int = 30): List<Announcement> {
        val out = mutableListOf<Announcement>()
        for (page in 1..pages) {
            val text = try {
                Http.postForm(
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
            } catch (e: Exception) {
                break
            }
            val batch = try {
                parseCninfo(text)
            } catch (e: Exception) {
                break
            }
            if (batch.isEmpty()) break
            out += batch
        }
        return out
    }

    fun parseCninfo(json: String): List<Announcement> {
        val arr = JSONObject(json).opt("announcements") ?: return emptyList()
        if (arr !is JSONArray) return emptyList()
        val out = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("announcementTitle")
                .replace("<em>", "").replace("</em>", "").trim()
            if (title.isEmpty()) continue
            val adjunct = item.optString("adjunctUrl")
            val ts = item.optLong("announcementTime", 0L)
            out.add(
                Announcement(
                    code = item.optString("secCode"),
                    name = item.optString("secName"),
                    title = title,
                    category = "",
                    url = if (adjunct.isEmpty()) "" else "https://static.cninfo.com.cn/$adjunct",
                    publishedAt = if (ts > 0) formatTs(ts) else ""
                )
            )
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

    /** 统一时间格式 yyyy-MM-dd HH:mm，这样字符串比较等价于时间比较。 */
    @SuppressLint("SimpleDateFormat")
    fun formatTs(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    @SuppressLint("SimpleDateFormat")
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** N 天前的时间标签，用于时间窗过滤 */
    fun sinceLabel(days: Int): String =
        formatTs(System.currentTimeMillis() - days * 24L * 3600L * 1000L)
}
