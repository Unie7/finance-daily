package com.finapp.daily

import com.finapp.daily.data.Digest
import com.finapp.daily.data.Indicator
import com.finapp.daily.data.Keywords
import com.finapp.daily.data.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实接口返回的报文片段做解析测试，跑在 JVM 上，不需要模拟器。
 */
class ParsersTest {

    // ---------------------------------------------------------- 云端指标

    private val cloudJson = """
    {
      "generated_at": "2026-11-10T04:57:51Z",
      "indicators": {
        "BRENT": {"as_of":"2026-11-10","change":-0.95,"title":"布伦特原油","unit":"美元/桶","value":91.62},
        "DXY":   {"as_of":"2026-11-09","change":null,"title":"美元指数","unit":"","value":101.535},
        "FED_TARGET": {"as_of":"2026-11-06","high":4.0,"low":3.75,"title":"美联储目标利率区间","unit":"%","value":"3.75-4.00"},
        "US10Y": {"as_of":"2026-11-09","change":0.02,"title":"美债10年期","unit":"%","value":4.88},
        "US2Y":  {"as_of":"2026-11-09","change":0.05,"title":"美债2年期","unit":"%","value":4.56}
      }
    }
    """.trimIndent()

    @Test
    fun `云端 JSON 解析出全部指标`() {
        val list = Sources.parseCloudIndicators(cloudJson)
        assertEquals(5, list.size)
        val byCode = list.associateBy { it.code }
        assertEquals("4.88", byCode.getValue("US10Y").value)
        assertEquals(0.02, byCode.getValue("US10Y").change)
        assertEquals("3.75-4.00", byCode.getValue("FED_TARGET").value)
        assertFalse(byCode.getValue("FED_TARGET").isNumeric)
        // null change 要保留为 null 而不是 0
        assertNull(byCode.getValue("DXY").change)
    }

    private fun assertNull(v: Any?) = assertTrue(v == null)

    // ---------------------------------------------------------- 资讯

    @Test
    fun `见闻快讯能解析并剥掉 HTML`() {
        val json = """
        {"data":{"items":[
          {"id":3197392,"title":"","content_text":"铃木股价跌幅一度扩大至5.2%。",
           "display_time":1794287189,"uri":"https://wallstreetcn.com/livenews/3197392"},
          {"id":3197393,"title":"","content":"<p>美债10年期收益率升至4.9%。</p>",
           "display_time":1794287200,"uri":""}
        ],"next_cursor":1794287200}}
        """.trimIndent()
        val list = Sources.parseWscn(json, "2026-01-01 00:00")
        assertEquals(2, list.size)
        assertFalse(list[1].summary.contains("<p>"))
        assertTrue(list[1].url.contains("3197393"))
        // 时间统一成 yyyy-MM-dd HH:mm，才能做字符串比较
        assertEquals(16, list[0].publishedAt.length)
    }

    @Test
    fun `时间窗外的快讯被丢弃`() {
        val json = """
        {"data":{"items":[
          {"id":1,"content_text":"美债收益率大涨","display_time":1794287189,"uri":"u1"}
        ],"next_cursor":1794287189}}
        """.trimIndent()
        assertTrue(Sources.parseWscn(json, "2100-01-01 00:00").isEmpty())
    }

    @Test
    fun `东财资讯能解析`() {
        val json = """
        {"data":{"list":[{"title":"标题A","showTime":"2026-11-10 12:43:00",
        "url":"http://a.com/1.html","summary":"摘要"}]}}
        """.trimIndent()
        val list = Sources.parseEastmoneyNews(json)
        assertEquals(1, list.size)
        assertEquals("2026-11-10 12:43", list[0].publishedAt)
    }

    @Test
    fun `新浪外盘期货字段位置正确`() {
        // 真实报文片段：现价/昨收 分别在 [0] 和 [7]
        val raw = """var hq_str_hf_CL="85.722,,85.740,85.760,86.930,85.520,13:19:05,86.850,86.430,0,8,3,2026-11-10,ŦԼԭ��,0";""" +
            "\n" + """var hq_str_hf_OIL="91.470,,91.470,91.490,92.840,91.350,13:19:05,92.570,92.450,0,3,1,2026-11-10,������ԭ��,15356";"""
        val list = Sources.parseSinaOil(raw)
        assertEquals(2, list.size)
        val wti = list.first { it.code == "WTI" }
        assertEquals("85.722", wti.value)
        assertEquals(-1.128, wti.change!!, 0.001)   // 85.722 - 86.850
        assertEquals("2026-11-10", wti.asOf)
    }

    // ---------------------------------------------------------- 公告

    @Test
    fun `东财公告能解析出事项分类和链接`() {
        val json = """
        {"data":{"list":[{
          "art_code":"AN202611101831573694",
          "codes":[{"stock_code":"002046","short_name":"国机精工"}],
          "columns":[{"column_code":"050003","column_name":"重大合同"}],
          "notice_date":"2026-11-10 00:00:00",
          "title":"关于签订重大合同的公告"
        }]}}
        """.trimIndent()
        val list = Sources.parseEastmoneyAnnouncements(json)
        assertEquals(1, list.size)
        assertEquals("002046", list[0].code)
        assertEquals("重大合同", list[0].category)
        assertTrue(list[0].url.contains("AN202611101831573694"))
        assertEquals("2026-11-10 00:00", list[0].publishedAt)
    }

    @Test
    fun `巨潮公告能解析且能拼出 PDF 链接`() {
        val json = """
        {"announcements":[{
          "secCode":"300450","secName":"先导智能","announcementId":"1225664657",
          "announcementTitle":"关于重大资产重组的进展公告",
          "announcementTime":1794268374000,
          "adjunctUrl":"finalpage/2026-11-10/1225664657.PDF"
        }]}
        """.trimIndent()
        val list = Sources.parseCninfo(json)
        assertEquals(1, list.size)
        assertTrue(list[0].url.startsWith("https://static.cninfo.com.cn/"))
    }

    @Test
    fun `空响应不崩`() {
        assertTrue(Sources.parseCninfo("""{"announcements":null}""").isEmpty())
        assertTrue(Sources.parseCloudIndicators("""{"indicators":null}""").isEmpty())
        assertTrue(Sources.parseEastmoneyNews("""{"data":null}""").isEmpty())
        assertTrue(Sources.parseEastmoneyAnnouncements("""{"data":{"list":[]}}""").isEmpty())
    }
}

class KeywordsTest {

    // ---------------------------------------------------------- 精确过滤

    @Test
    fun `标题命中才算相关`() {
        assertEquals(listOf("美债"), Keywords.classify("美债10年期收益率升至4.9%", ""))
        assertEquals(listOf("美元"), Keywords.classify("美元指数重新站上102", ""))
        assertEquals(listOf("加息"), Keywords.classify("美联储官员暗示可能加息", ""))
        assertEquals(listOf("原油"), Keywords.classify("WTI原油跌破85美元", ""))
    }

    @Test
    fun `只是顺带提一句的不收`() {
        // 一条公司新闻，正文里偶然出现「美元」两个字 —— 不该进列表
        val topics = Keywords.classify(
            "某公司发布新款手机",
            "该产品海外售价499美元，预计三季度上市，公司表示将加大研发投入。"
        )
        assertTrue(topics.isEmpty())
    }

    @Test
    fun `首段命中也算相关`() {
        val topics = Keywords.classify(
            "隔夜市场综述",
            "美元指数走强，美元兑人民币走高，分析师认为强势美元将延续。"
        )
        assertTrue(topics.contains("美元"))
    }

    @Test
    fun `首段之后的正文提及不算`() {
        // 关键词出现在首段之后（长文章后半段），不该被当成相关
        val body = "【探访AI短剧创作一线】".padEnd(120, ' ') +
            "该剧组表示，近期原油价格波动对拍摄成本没有影响。"
        assertTrue(Keywords.classify("探访AI短剧创作一线", body).isEmpty())
    }

    @Test
    fun `相关度打分：标题命中高于首段命中`() {
        val titleHit = Keywords.relevance("美债收益率上行", "")
        val paraHit = Keywords.relevance("某综述", "美债收益率上行和其他内容。")
        assertTrue(titleHit > paraHit)
        assertEquals(0, Keywords.relevance("无关话题", "无关内容"))
    }

    @Test
    fun `泛化的国债和利率不再误伤`() {
        // 国内储蓄国债、存贷款利率这类新闻不该被当成美债/加息
        assertTrue(Keywords.classify("财政部决定发行储蓄国债", "本期国债面向个人投资者").isEmpty())
        assertTrue(Keywords.classify("多家银行下调存款利率", "贷款利率同步调整").isEmpty())
    }

    @Test
    fun `一条消息可同时属于多个主题`() {
        val topics = Keywords.classify("美联储加息预期升温，美元指数走高，美债收益率上行", "")
        assertTrue(topics.contains("加息"))
        assertTrue(topics.contains("美元"))
        assertTrue(topics.contains("美债"))
    }

    // ---------------------------------------------------------- 公告筛选

    @Test
    fun `按事项分类筛重大事项`() {
        assertTrue(Keywords.isMajorAnnouncement("重大合同", "关于签订重大合同的公告"))
        assertTrue(Keywords.isMajorAnnouncement("重组进展公告", "关于重组进展的公告"))
        assertTrue(Keywords.isMajorAnnouncement("股权转让", "关于股权转让的公告"))
        assertTrue(Keywords.isMajorAnnouncement("实施退市风险警示", "关于退市风险警示的公告"))
        // 常规事项要排除
        assertFalse(Keywords.isMajorAnnouncement("调研活动", "投资者关系管理信息"))
        assertFalse(Keywords.isMajorAnnouncement("股东大会资料", "2026年第二次临时股东大会资料"))
        assertFalse(Keywords.isMajorAnnouncement("法律意见书", "关于本次交易的法律意见书"))
    }

    @Test
    fun `分类是其他时退回标题判断`() {
        assertTrue(Keywords.isMajorAnnouncement("其他", "关于重大资产重组的公告"))
        assertFalse(Keywords.isMajorAnnouncement("其他", "公司章程修订"))
    }

    // ---------------------------------------------------------- 数字提取

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
    }
}

class BloombergTest {

    @Test
    fun `识别彭博转载标记`() {
        assertTrue(Keywords.isBloomberg("日本半导体企业Rapidus据称难以按期实现量产。（彭博）"))
        assertTrue(Keywords.isBloomberg("据彭博社报道，美联储官员倾向于放缓降息"))
        assertTrue(Keywords.isBloomberg("Bloomberg reported the yield curve"))
        assertFalse(Keywords.isBloomberg("某公司发布新款手机"))
        assertFalse(Keywords.isBloomberg(""))
    }

    @Test
    fun `彭博快讯即使不命中四主题也应保留`() {
        val text = "日本半导体企业Rapidus据称难以按期实现量产。（彭博）"
        // 主题打不上标签（不是美债/美元/加息/原油），但彭博标记在
        assertTrue(Keywords.classify(text, text).isEmpty())
        assertTrue(Keywords.isBloomberg(text))
    }
}
class CailianpressTest {

    @Test
    fun `签名算法与站点一致`() {
        // 这个期望值是照着站点 JS 里的实现（md5(sha1(排序后的非空参数串))）算出来后，
        // 用真实请求验证通过的。改动签名逻辑会让它失败。
        assertEquals(
            "b02d8f7bc4c45eeb3e86904203597da2",
            com.finapp.daily.data.Cailianpress.sign("app=CailianpressWeb&os=web&sv=8.7.9")
        )
    }

    @Test
    fun `空值参数不参与签名`() {
        val url = com.finapp.daily.data.Cailianpress.buildUrl(
            extra = mapOf("category" to "", "rn" to "20")
        )
        // URL 里带空参数，但签名等价于只有非空参数
        assertTrue(url.contains("category="))
        assertTrue(url.contains("rn=20"))
        assertTrue(url.contains("sign="))
    }

    @Test
    fun `首页数据结构能解析出条目`() {
        val json = """
        {"errno":0,"data":{
          "top_article":[{"id":2528856,"title":"10月出口同比增长35.2%","brief":"摘要A","ctime":1794283350}],
          "depth_list":[{"id":2528000,"title":"<em>财联社</em>电报：美债收益率上行","brief":"","ctime":1794283456}]
        }}
        """.trimIndent()
        val list = com.finapp.daily.data.Cailianpress.parse(json)
        assertEquals(2, list.size)
        assertEquals("财联社", list[0].source)
        assertTrue(list[0].url.contains("detail/2528856"))
        // HTML 标签要剥掉
        assertFalse(list[1].title.contains("<em>"))
    }

    @Test
    fun `errno 非零时返回空列表`() {
        assertTrue(
            com.finapp.daily.data.Cailianpress.parse("""{"errno":"10012","msg":"签名错误"}""").isEmpty()
        )
        assertTrue(com.finapp.daily.data.Cailianpress.parse("<!DOCTYPE html>").isEmpty())
    }
}
class LeadLineTest {

    @Test
    fun `见闻标题只取首行，不把第二段混进来`() {
        // 真实案例：这条曾被判成「原油」，因为 take(60) 把第二段也切进了标题
        val json = """
        {"data":{"items":[{
          "id":1,
          "content_text":"欧洲央行管委兼法国央行行长Moulin：预计2026年法国GDP增长约0.3%。\n\n认为能源价格出现积极迹象。",
          "display_time":1794286440,
          "uri":"https://wallstreetcn.com/livenews/1"
        }],"next_cursor":1794286440}}
        """.trimIndent()
        val list = Sources.parseWscn(json, "2000-01-01 00:00")
        assertEquals(1, list.size)
        // 标题里不该出现第二段的「能源价格」
        assertFalse(list[0].title.contains("能源价格"))
        // 整条也不该被判成原油
        assertTrue(Keywords.classify(list[0].title, list[0].summary).isEmpty())
    }

    @Test
    fun `leadLine 取第一个非空行`() {
        assertEquals("标题在第二行", Sources.leadLine("\n\n标题在第二行\n\n正文"))
        assertEquals("只有一行", Sources.leadLine("只有一行"))
        assertEquals("", Sources.leadLine(""))
    }
}
class KeywordPrecisionTest {

    @Test
    fun `裸的减产不该算原油`() {
        // 真实案例：短剧团队"主动减产"曾被判成原油，相关度 18
        val title = "【探访AI短剧创作一线：《持械入宋》爆红后，团队为何主动减产？】"
        val body = "【探访AI短剧创作一线：《持械入宋》爆红后，团队为何主动减产？】" +
            "《持械入宋》创作团队共有30多名成员，但直接参与制作的主创只有6人。"
        assertTrue(Keywords.classify(title, body).isEmpty())
    }

    @Test
    fun `组合词减产仍能识别石油减产`() {
        assertTrue(Keywords.classify("OPEC达成减产协议，国际油价应声上涨", "").contains("原油"))
        assertTrue(Keywords.classify("原油减产预期升温", "").contains("原油"))
    }

    @Test
    fun `能源价格不该算原油`() {
        assertTrue(Keywords.classify("欧洲央行认为能源价格出现积极迹象", "").isEmpty())
    }
}