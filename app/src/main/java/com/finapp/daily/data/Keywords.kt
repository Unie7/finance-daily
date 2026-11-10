package com.finapp.daily.data

/**
 * 主题分类。用户明确要求：资讯只留「美债 / 美元 / 加息 / 原油」四类，
 * 不命中的直接丢弃，避免消息流被无关内容淹没。
 */
object Keywords {

    val TOPICS = listOf("美债", "美元", "加息", "原油")

    private val RULES = linkedMapOf(
        "美债" to listOf(
            "美债", "国债", "收益率", "殖利率", "treasury", "10年期", "2年期", "30年期",
            "十年期", "两年期", "债市", "国债拍卖", "期限溢价", "倒挂"
        ),
        "美元" to listOf(
            "美元", "美元指数", "dxy", "汇率", "离岸人民币", "在岸人民币", "人民币中间价",
            "美元兑", "非美货币", "外汇储备", "美元走强", "美元走弱"
        ),
        "加息" to listOf(
            "美联储", "加息", "降息", "利率", "fomc", "鲍威尔", "联邦基金", "点阵图",
            "缩表", "议息", "货币政策", "鹰派", "鸽派", "降准", "lpr", "政策利率",
            "基准利率", "通胀", "cpi", "非农", "就业数据"
        ),
        "原油" to listOf(
            "原油", "油价", "wti", "布伦特", "brent", "opec", "欧佩克", "石油", "汽油",
            "页岩油", "炼厂", "减产", "增产", "能源价格", "天然气"
        )
    )

    /** 返回命中的主题列表，可能为空。 */
    fun classify(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        return RULES.entries.filter { (_, words) -> words.any { lower.contains(it) } }
            .map { it.key }
    }

    /**
     * 公告只留「重大事项」。巨潮的公告标题里通常带这些词，
     * 用它做过滤比按公告类型编码更稳（类型编码各板块不统一）。
     */
    private val MAJOR_WORDS = listOf(
        "重大资产重组", "重大合同", "重大事项", "重大诉讼", "重大仲裁", "重大风险",
        "业绩预告", "业绩快报", "年度报告", "半年度报告", "季度报告",
        "收购", "并购", "要约", "股权转让", "控制权", "实际控制人",
        "增持", "减持", "回购", "定增", "非公开发行", "可转债",
        "问询函", "关注函", "监管函", "立案", "处罚", "警示函",
        "退市", "暂停上市", "终止上市", "风险提示",
        "停牌", "复牌", "破产", "重整", "清算",
        "分红", "利润分配", "高送转", "股权激励", "员工持股",
        "关联交易", "对外担保", "募集资金", "中标"
    )

    fun isMajorAnnouncement(title: String): Boolean {
        if (title.isBlank()) return false
        return MAJOR_WORDS.any { title.contains(it) }
    }

    /** 从资讯正文里抠数字时的主题标识，供界面标注「资讯提取」。 */
    data class Extracted(val value: Double, val text: String)

    private val YIELD_RE = Regex("""(10年期|十年期|2年期|两年期|30年期)[^0-9%]{0,12}(\d+\.\d{1,3})\s*%""")
    private val DXY_RE = Regex("""美元指数[^0-9]{0,8}(\d{2,3}\.\d{1,3})""")
    private val OIL_RE = Regex("""(WTI|布伦特|原油)[^0-9]{0,12}(\d{1,3}\.\d{1,3})\s*美元""")

    fun extractNumbers(text: String): List<Extracted> {
        val out = mutableListOf<Extracted>()
        YIELD_RE.findAll(text).forEach {
            it.groupValues[2].toDoubleOrNull()?.let { v ->
                out.add(Extracted(v, it.value.trim()))
            }
        }
        DXY_RE.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            out.add(Extracted(it, "美元指数 $it"))
        }
        OIL_RE.find(text)?.let { m ->
            m.groupValues[2].toDoubleOrNull()?.let { out.add(Extracted(it, m.value.trim())) }
        }
        return out
    }
}
