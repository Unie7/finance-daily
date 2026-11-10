package com.finapp.daily.data

import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.MessageDigest

/**
 * 财联社（cls.cn）资讯。
 *
 * 这个源比其它几个麻烦，踩过的坑都记在这里：
 *
 * 1. **必须带 cookie 会话**。直接请求 API 会被 WAF 回落成首页 HTML（200 状态、32KB 的 SPA 壳），
 *    看起来像"接口改版了"，其实是没会话。所以先用同一个 CookieManager 访问一次首页。
 * 2. **签名算法**：参数按键名排序、拼成 `k=v&k=v`、再过 SHA-1 然后 MD5。
 *    注意 **空值参数不参与签名**（JS 里 `filter(e => e)` 会把空串过滤掉），
 *    但 URL 里仍然要带上这些空参数。这一条卡了我很久。
 * 3. **版本参数 sv 要跟得上**，从站点 JS 里挖的当前值是 8.7.9。
 * 4. **要用桌面 UA**，移动 UA 会被同样处理成 HTML。
 * 5. `/nodeapi/telegraphList` 这类老接口已经 404，现在能用的是
 *    `/v3/depth/home/assembled/1000`（返回首页聚合数据，含 top_article / depth_list）。
 */
object Cailianpress {

    private const val BASE = "https://www.cls.cn"
    private const val SV = "8.7.9"
    private const val PATH = "/v3/depth/home/assembled/1000"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Volatile
    private var warmedUp = false

    // ---------------------------------------------------------------- 签名

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** sign = md5(sha1(排序后的非空参数串)) */
    fun sign(sortedQuery: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(sortedQuery.toByteArray(Charsets.UTF_8))
        val md5 = MessageDigest.getInstance("MD5").digest(hex(sha1).toByteArray(Charsets.UTF_8))
        return hex(md5)
    }

    fun buildUrl(path: String = PATH, extra: Map<String, String> = emptyMap()): String {
        val params = LinkedHashMap<String, String>()
        params["app"] = "CailianpressWeb"
        params["os"] = "web"
        params["sv"] = SV
        params.putAll(extra)

        val signed = params.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key.uppercase() }
            .joinToString("&") { "${it.key}=${it.value}" }

        val full = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "$BASE$path?$full&sign=${sign(signed)}"
    }

    // ---------------------------------------------------------------- 解析

    fun parse(json: String): List<Article> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        if (root.optInt("errno", 0) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()

        val out = mutableListOf<Article>()
        for (key in listOf("top_article", "depth_list")) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = stripTag(item.optString("title"))
                if (title.isEmpty()) continue
                val id = item.optLong("id", 0L)
                val ctime = item.optLong("ctime", 0L)
                val brief = stripTag(item.optString("brief"))
                out.add(
                    Article(
                        title = title,
                        summary = brief.ifBlank { title },
                        source = "财联社",
                        url = if (id > 0) "$BASE/detail/$id" else "",
                        publishedAt = if (ctime > 0) Sources.formatTs(ctime * 1000) else ""
                    )
                )
            }
        }
        return out.distinctBy { it.url }
    }

    private fun stripTag(s: String): String =
        s.replace(Regex("<[^>]+>"), "").trim()

    // ---------------------------------------------------------------- 抓取

    private fun ensureCookieHandler() {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    /** 先访问一次首页建立会话，否则 API 会返回 HTML 而不是 JSON。 */
    @Synchronized
    private fun warmUp() {
        if (warmedUp) return
        ensureCookieHandler()
        try {
            Http.get(
                "$BASE/",
                headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to BASE),
                timeoutMs = 8000
            )
            warmedUp = true
        } catch (e: Exception) {
            // 失败就下次再试，不阻塞其它数据源
        }
    }

    fun fetch(): List<Article> {
        warmUp()
        val text = Http.get(
            buildUrl(),
            headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$BASE/telegraph"
            ),
            timeoutMs = 10000
        )
        return parse(text)
    }
}
