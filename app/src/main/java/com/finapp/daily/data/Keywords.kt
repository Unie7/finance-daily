package com.finapp.daily.data

/**
 * 内容过滤规则。
 *
 * 用户的要求很明确：**不是「提到了美元」就收，而是这条资讯本身在讲美元/美元加息相关的事**。
 * 所以采用打分制，而不是「命中任一关键词就算」：
 *
 *   标题命中一次 = 3 分
 *   正文每命中一个不同关键词 = 1 分（最多计 3 个）
 *   总分 >= 3 才收
 *
 * 效果：标题里就有主题词的直接收；标题没有、但正文反复提到（>=3 处）也收；
 * 只是顺带提一句的（比如某公司财报里出现「美元」两个字）会被丢掉。
 */
object Keywords {

    val TOPICS = listOf("美债", "美元", "加息", "原油")

    /**
     * 关键词表经过两轮收敛：
     *  - 去掉过于宽泛的词（如裸的「国债」「利率」「汇率」），它们会把国内债市、
     *    存贷款利率、日元汇率之类的新闻全放进来
     *  - 保留真正指向宏观主题的组合词
     */
    private val RULES = linkedMapOf(
        "美债" to listOf(
            "美债", "美国国债", "国债收益率", "收益率曲线", "美债收益率",
            "十年期美债", "10年期美债", "两年期美债", "30年期美债",
            "期限溢价", "收益率倒挂", "美债拍卖"
        ),
        "美元" to listOf(
            "美元指数", "美元走强", "美元走弱", "强势美元", "弱势美元", "美元信用",
            "美元兑人民币", "人民币汇率", "离岸人民币", "汇率中间价",
            "美元资产", "去美元化", "美元流动性"
        ),
        "加息" to listOf(
            "美联储", "加息", "降息", "fomc", "鲍威尔", "联邦基金利率",
            "点阵图", "缩表", "议息", "货币政策", "政策利率", "鹰派", "鸽派",
            "美国通胀", "美国cpi", "非农数据", "降准", "lpr"
        ),
        "原油" to listOf(
            "原油", "油价", "wti", "布伦特", "brent", "opec", "欧佩克",
            "页岩油", "炼厂", "石油供应", "石油需求",
            "汽油价格", "柴油价格", "成品油",
            // 以下必须是组合词：裸的「减产」「增产」在中文里太通用，
            // 任何行业削减产能都会用（实例：某短剧团队"主动减产"被判成原油）
            "减产协议", "增产协议", "原油减产", "原油增产",
            "石油减产", "石油增产", "欧佩克减产", "opec减产"
            // 「能源价格」也移除了——它会把欧洲央行/电价/天然气的评论算成原油
        )
    )

    /** 首段长度上限。太长了就等于全文匹配，噪音会回来。 */
    private const val FIRST_PARA_MAX = 100

    /** 取正文首段：截到第一个换行，或前 FIRST_PARA_MAX 个字符，取更短的那个。 */
    fun firstParagraph(body: String): String {
        if (body.isBlank()) return ""
        val cut = body.indexOfFirst { it == '\n' || it == '。' }
        val head = if (cut in 1 until FIRST_PARA_MAX) body.substring(0, cut) else body.take(FIRST_PARA_MAX)
        return head.trim()
    }

    /**
     * 命中判定：**标题 或 首段**命中即算。
     *
     * 历史：最早是"命中任一关键词就算"（太松）→ 改成"标题必中"（漏得多）→
     * 现在折中为"标题或首段"。首段限长 100 字，避免长文章正文里的偶然提及混进来。
     */
    fun classify(title: String, body: String): List<String> {
        val t = title.lowercase()
        val p = firstParagraph(body).lowercase()
        if (t.isBlank() && p.isBlank()) return emptyList()
        return RULES.entries
            .filter { (_, words) -> words.any { t.contains(it) || p.contains(it) } }
            .map { it.key }
    }

    /**
     * 相关度打分，用于「按相关度」排序。规则：
     *   标题命中每个词 +10（最可信）
     *   首段命中每个词 +3
     *   命中主题数 +5/个
     *   彭博转载 +8
     */
    fun relevance(title: String, body: String): Int {
        val t = title.lowercase()
        val p = firstParagraph(body).lowercase()
        var score = 0
        var topics = 0
        for ((_, words) in RULES) {
            val titleHits = words.count { t.contains(it) }
            val paraHits = words.count { p.contains(it) }
            if (titleHits > 0 || paraHits > 0) topics++
            score += titleHits * 10
            score += paraHits * 3
        }
        score += topics * 5
        if (isBloomberg(title + " " + body)) score += 8
        return score
    }

// ------------------------------------------------------------------ 彭博

    /**
     * 只保留彭博社相关的内容。中文财经站转载彭博时，通常在正文末尾标注「（彭博）」
     * 或在正文里写「据彭博社报道」。实测东财和见闻 7x24 里只有见闻会带这个标记。
     */
    private val BLOOMBERG_WORDS = listOf("彭博", "bloomberg", "布隆伯格")

    fun isBloomberg(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return BLOOMBERG_WORDS.any { lower.contains(it) }
    }

    // ------------------------------------------------------------------ 公告

    /**
     * 重大事项分类词表。东方财富的公告接口直接返回「事项分类」（如「重大合同」
     * 「重组进展公告」「股权转让」），比拿关键词猜标题准得多。
     */
    private val MAJOR_CATEGORY_WORDS = listOf(
        "重大", "重组", "收购", "出售", "股权转让", "控制权", "实际控制人",
        "增持", "减持", "回购", "增发", "定增", "退市", "风险警示",
        "停牌", "复牌", "处罚", "诉讼", "仲裁", "担保", "关联交易",
        "质押", "冻结", "破产", "重整", "清算", "业绩", "激励",
        "权益变动", "增资", "分红", "分配方案", "对外投资", "对外项目投资",
        "募集资金", "中标", "合同", "要约", "异常波动"
    )

    /** 分类信息缺失或为「其他」时，退回标题关键词判断 */
    private val FALLBACK_TITLE_WORDS = listOf(
        "重大", "重组", "收购", "并购", "要约", "股权转让", "控制权",
        "增持", "减持", "回购", "定增", "非公开发行", "可转债",
        "问询函", "关注函", "监管函", "立案", "处罚", "警示函",
        "退市", "风险提示", "停牌", "复牌", "破产", "重整", "清算",
        "业绩预告", "业绩快报", "分红", "利润分配", "股权激励",
        "关联交易", "对外担保", "中标", "重大合同"
    )

    fun isMajorAnnouncement(category: String, title: String): Boolean {
        // 分类命中直接收；分类不准（如「签订协议」配「控制权拟变更」的标题）时靠标题兜住，
        // 宁可多收一条，也别把真正的重大事项漏掉。
        if (MAJOR_CATEGORY_WORDS.any { category.contains(it) }) return true
        return FALLBACK_TITLE_WORDS.any { title.contains(it) }
    }

    /** 巨潮兜底源没有分类字段，只能看标题 */
    fun isMajorAnnouncement(title: String): Boolean =
        FALLBACK_TITLE_WORDS.any { title.contains(it) }

    // ------------------------------------------------------------------ 正文提数

    data class Extracted(val value: Double, val text: String)

    private val YIELD_RE = Regex("""(10年期|十年期|2年期|两年期|30年期)[^0-9%]{0,12}(\d+\.\d{1,3})\s*%""")
    private val DXY_RE = Regex("""美元指数[^0-9]{0,8}(\d{2,3}\.\d{1,3})""")
    private val OIL_RE = Regex("""(WTI|布伦特|原油)[^0-9]{0,12}(\d{1,3}\.\d{1,3})\s*美元""")

    fun extractNumbers(text: String): List<Extracted> {
        val out = mutableListOf<Extracted>()
        YIELD_RE.findAll(text).forEach {
            it.groupValues[2].toDoubleOrNull()?.let { v -> out.add(Extracted(v, it.value.trim())) }
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
