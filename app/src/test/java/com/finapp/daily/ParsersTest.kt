package com.finapp.daily

import com.finapp.daily.data.Digest
import com.finapp.daily.data.Indicator
import com.finapp.daily.data.Keywords
import com.finapp.daily.data.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实接口返回的报文片段做解析测试。
 * 这些跑在 JVM 上，不需要模拟器。
 */
class ParsersTest {

    private val cloudJson = """
    {
      "generated_at": "2026-11-10T04:57:51Z",
      "sources": {"treasury":"U.S. Treasury","nyfed":"Fed","quotes":"CNBC"},
      "errors": {},
      "indicators": {
        "BRENT": {"as_of":"2026-11-10","change":-0.95,"title":"布伦特原油","unit":"美元/桶","value":91.62},
        "DXY":   {"as_of":"2026-11-09","change":null,"title":"美元指数","unit":"","value":101.535},
        "EFFR":  {"as_of":"2026-11-06","change":null,"title":"有效联邦基金利率","unit":"%","value":3.88},
        "FED_TARGET": {"as_of":"2026-11-06","change":null,"high":4.0,"low":3.75,"title":"美联储目标利率区间","unit":"%","value":"3.75-4.00"},
        "US10Y": {"as_of":"2026-11-09","change":0.02,"title":"美债10年期","unit":"%","value":4.88},
        "US2Y":  {"as_of":"2026-11-09","change":0.05,"title":"美债2年期","unit":"%","value":4.56},
        "US30Y": {"as_of":"2026-11-09","change":0.0,"title":"美债30年期","unit":"%","value":5.26}
      }
    }
    """.trimIndent()

    @Test
    fun `云端 JSON 能解析出全部指标`() {
        val list = Sources.parseCloudIndicators(cloudJson)
        assertEquals(7, list.size)
        val byCode = list.associateBy { it.code }
        assertEquals("4.88", byCode.getValue("US10Y").value)
        assertEquals(0.02, byCode.getValue("US10Y").change)
        // 区间型指标不能被当成数字丢掉
        assertEquals("3.75-4.00", byCode.getValue("FED_TARGET").value)
        assertFalse(byCode.getValue("FED_TARGET").isNumeric)
    }

    @Test
    fun `华尔街见闻快讯能解析`() {
        val json = """
        {"code":20000,"message":"OK","data":{"items":[
          {"id":3197392,"title":"","content_text":"铃木股价跌幅一度扩大至5.2%。",
           "display_time":1794287189,"uri":"https://wallstreetcn.com/livenews/3197392"},
          {"id":3197393,"title":"","content":"<p>美债10年期收益率升至4.9%。</p>",
           "display_time":1794287200,"uri":""}
        ]}}
        """.trimIndent()
        val list = Sources.parseWscn(json)
        assertEquals(2, list.size)
        assertTrue(list[0].title.contains("铃木"))
        // HTML 标签要被剥掉
        assertFalse(list[1].summary.contains("<p>"))
        // uri 为空时用 id 兜底拼链接
        assertTrue(list[1].url.contains("3197393"))
    }

    @Test
    fun `东财资讯能解析`() {
        val json = """
        {"data":{"list":[{"title":"厂房变科创容器","showTime":"2026-11-10 12:43:00",
        "url":"http://bond.eastmoney.com/news/1332,1.html","summary":"摘要内容"}]}}
        """.trimIndent()
        val list = Sources.parseEastmoneyNews(json)
        assertEquals(1, list.size)
        assertEquals("东方财富", list[0].source)
        assertEquals("2026-11-10 12:43:00", list[0].publishedAt)
    }

    @Test
    fun `巨潮公告能解析且能拼出 PDF 链接`() {
        val json = """
        {"totalAnnouncement":240667,"announcements":[
          {"secCode":"300450","secName":"先导智能","announcementId":"1225664657",
           "announcementTitle":"关于重大资产重组的进展公告",
           "announcementTime":1794268374000,
           "adjunctUrl":"finalpage/2026-11-10/1225664657.PDF",
           "announcementType":"01010503"}
        ]}
        """.trimIndent()
        val list = Sources.parseCninfo(json)
        assertEquals(1, list.size)
        assertEquals("300450", list[0].code)
        assertTrue(list[0].url.startsWith("https://static.cninfo.com.cn/"))
    }

    @Test
    fun `公告列表为空或 null 时不崩`() {
        assertTrue(Sources.parseCninfo("""{"announcements":null}""").isEmpty())
        assertTrue(Sources.parseCninfo("""{"totalAnnouncement":0}""").isEmpty())
        assertTrue(Sources.parseCloudIndicators("""{"indicators":null}""").isEmpty())
    }
}

class KeywordsTest {

    @Test
    fun `四个主题都能命中`() {
        assertTrue(Keywords.classify("美债10年期收益率升至4.9%").contains("美债"))
        assertTrue(Keywords.classify("美元指数走强").contains("美元"))
        assertTrue(Keywords.classify("美联储官员暗示可能加息").contains("加息"))
        assertTrue(Keywords.classify("WTI原油价格下跌").contains("原油"))
    }

    @Test
    fun `无关内容不命中`() {
        assertTrue(Keywords.classify("某公司发布新款手机").isEmpty())
        assertTrue(Keywords.classify("").isEmpty())
    }

    @Test
    fun `一条消息可以同时属于多个主题`() {
        val topics = Keywords.classify("美联储加息预期升温，美元指数走高，美债收益率上行")
        assertTrue(topics.contains("加息"))
        assertTrue(topics.contains("美元"))
        assertTrue(topics.contains("美债"))
    }

    @Test
    fun `重大事项公告能识别`() {
        assertTrue(Keywords.isMajorAnnouncement("关于重大资产重组的进展公告"))
        assertTrue(Keywords.isMajorAnnouncement("2026年第三季度业绩预告"))
        assertTrue(Keywords.isMajorAnnouncement("关于收到深圳证券交易所问询函的公告"))
        assertFalse(Keywords.isMajorAnnouncement("公司章程修订"))
        assertFalse(Keywords.isMajorAnnouncement(""))
    }

    @Test
    fun `能从资讯正文抠出数字`() {
        val nums = Keywords.extractNumbers("美债10年期收益率升至4.88%，2年期报4.56%")
        assertTrue(nums.any { it.value == 4.88 })
        assertTrue(nums.any { it.value == 4.56 })

        val dxy = Keywords.extractNumbers("美元指数现报101.65")
        assertTrue(dxy.any { it.value == 101.65 })
    }
}

class DigestTest {

    private fun ind(code: String, title: String, value: String, change: Double?) = Indicator(
        code = code, title = title, value = value, unit = "%",
        change = change, asOf = "2026-11-09", precision = "official", source = "云端"
    )

    @Test
    fun `综述包含关键数字和利差`() {
        val text = Digest.build(
            listOf(
                ind("US10Y", "美债10年期", "4.88", 0.02),
                ind("US2Y", "美债2年期", "4.56", 0.05),
                ind("FED_TARGET", "美联储目标利率区间", "3.75-4.00", null),
                ind("DXY", "美元指数", "101.5", 0.2),
                ind("WTI", "WTI原油", "85.8", -1.04)
            ),
            articleCount = 12,
            announcementCount = 5,
            missing = emptyList()
        )
        assertTrue(text.contains("4.88"))
        // 0.02 个百分点 = 2bp
        assertTrue(text.contains("+2bp"))
        assertTrue(text.contains("3.75-4.00"))
        assertTrue(text.contains("利差"))
        assertTrue(text.contains("12 条"))
    }

    @Test
    fun `资讯提取的指标必须被标注`() {
        val newsInd = Indicator(
            code = "US10Y", title = "美债10年期", value = "4.9", unit = "%",
            change = null, asOf = "2026-11-10", precision = "news", source = "华尔街见闻"
        )
        val text = Digest.build(listOf(newsInd), 1, 0, emptyList())
        assertTrue(text.contains("资讯正文提取"))
    }

    @Test
    fun `缺失指标会在综述里列出`() {
        val text = Digest.build(listOf(ind("US10Y", "美债10年期", "4.88", null)), 0, 0, listOf("WTI"))
        assertTrue(text.contains("本期未取到"))
        assertNotNull(text)
    }
}
