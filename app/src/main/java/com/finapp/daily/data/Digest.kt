package com.finapp.daily.data

/**
 * 每日综述生成器。
 *
 * 用户选了「规则模板」方案（不接 AI）：免费、稳定、不会瞎编。
 * 输出一段话，把当天最关键的几个数字和条数串起来。
 */
object Digest {

    fun build(
        indicators: List<Indicator>,
        articleCount: Int,
        announcementCount: Int,
        missing: List<String>
    ): String {
        val byCode = indicators.associateBy { it.code }
        val parts = mutableListOf<String>()

        // ---- 美债 ----
        val yields = listOf("US10Y", "US2Y", "US30Y").mapNotNull { byCode[it] }
        if (yields.isNotEmpty()) {
            val text = yields.joinToString("，") { ind ->
                val bp = ind.change?.let { "（${formatBp(it)}）" } ?: ""
                "${ind.title} ${ind.value}%$bp"
            }
            parts.add("美债：$text")
        }

        // 10Y-2Y 利差
        val y10 = byCode["US10Y"]?.value?.toDoubleOrNull()
        val y2 = byCode["US2Y"]?.value?.toDoubleOrNull()
        if (y10 != null && y2 != null) {
            val spread = (y10 - y2) * 100
            val desc = if (spread < 0) "倒挂" else "正常"
            parts.add("10Y-2Y 利差 ${formatSigned(spread, 0)}bp（$desc）")
        }

        // ---- 美联储 ----
        val fed = byCode["FED_TARGET"]
        val effr = byCode["EFFR"]
        if (fed != null || effr != null) {
            val seg = mutableListOf<String>()
            fed?.let { seg.add("目标区间 ${it.value}%") }
            effr?.let { seg.add("EFFR ${it.value}%") }
            parts.add("美联储：${seg.joinToString("，")}")
        }

        // ---- 美元 ----
        byCode["DXY"]?.let { ind ->
            val pct = ind.change?.let { c ->
                val base = ind.value.toDoubleOrNull()?.minus(c)
                if (base != null && base != 0.0) c / base * 100.0 else null
            }
            val chg = if (pct != null) "（${formatSigned(pct, 2)}%）" else ""
            parts.add("美元指数 ${ind.value}$chg")
        }

        // ---- 原油 ----
        val oils = listOf("WTI", "BRENT").mapNotNull { byCode[it] }
        if (oils.isNotEmpty()) {
            parts.add(oils.joinToString("，") { "${it.title} ${it.value} 美元/桶" })
        }

        // ---- 数据质量说明 ----
        val fromNews = indicators.filter { it.precision == "news" }
        if (fromNews.isNotEmpty()) {
            parts.add(
                "⚠️ ${fromNews.joinToString("、") { it.title }} 为资讯正文提取，非接口数据，仅供参考"
            )
        }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString("、") { Repo.TITLES[it] ?: it }
            parts.add("本期未取到：$names")
        }

        // ---- 资讯与公告 ----
        parts.add("本期收录相关资讯 $articleCount 条，筛出重大公告 $announcementCount 条。")

        return parts.joinToString("\n")
    }

    /** 0.02 -> "+2bp" */
    private fun formatBp(change: Double): String {
        val bp = change * 100
        val sign = if (bp > 0) "+" else ""
        return "$sign${format1(bp)}bp"
    }

    private fun formatSigned(v: Double, digits: Int): String {
        val sign = if (v > 0) "+" else ""
        return sign + if (digits == 0) "${Math.round(v)}" else String.format("%.${digits}f", v)
    }

    private fun format1(v: Double): String {
        val rounded = Math.round(v * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
