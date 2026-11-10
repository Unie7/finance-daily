# 财经每日 · 云端指标抓取

给「财经每日」Android App 用的**境外宏观数据**定时抓取器。

App 装在用户手机上，负责抓境内资讯（华尔街见闻 / 东财）和上市公司公告（巨潮资讯）；
这个仓库负责从**美国机房**抓境外指标，因为财政部、纽约联储、CNBC 这些源在国内访问不稳定。

## 抓什么

| 指标 | 来源 | 说明 |
|---|---|---|
| 美债 3月 / 2年 / 5年 / 10年 / 30年 | 美国财政部官方 CSV | 免 Key，权威 |
| 美联储目标利率区间 / EFFR | 纽约联储 markets API | 免 Key |
| 美元指数 | CNBC 免费行情 | 主源，Yahoo 备用 |
| WTI / 布伦特原油 | CNBC 免费行情 | 主源，Yahoo 备用 |

全部是公开接口，不需要任何 API Key 或 Secret。

## 怎么跑

GitHub Actions 每天 **21:00 UTC（北京时间次日 05:00）** 自动跑一次：

```
python -m crawler.cloud.main
```

也可以手动触发（Actions 页面 → Run workflow），或本地跑：

```bash
pip install requests
python -m crawler.cloud.main --dry-run   # 只在终端看
python -m crawler.cloud.main             # 写入 data/latest.json 和 data/series.json
```

## 产出

`data/latest.json` —— App 只需要这一个文件就能渲染全部指标卡：

```json
{
  "generated_at": "2026-11-10T04:45:17Z",
  "sources": { "treasury": "U.S. Treasury", "nyfed": "Federal Reserve Bank of New York", "quotes": "CNBC" },
  "errors": {},
  "indicators": {
    "US10Y": { "value": 4.88, "change": 0.02, "as_of": "2026-11-09", "title": "美债10年期", "unit": "%" },
    "FED_TARGET": { "value": "3.75-4.00", "low": 3.75, "high": 4.0, "as_of": "2026-11-06" }
  }
}
```

`data/series.json` —— 最近 180 天序列，用于画趋势。

## App 怎么取

```
https://cdn.jsdelivr.net/gh/Unie7/finance-daily@main/data/latest.json
```

jsDelivr 对分支引用有 12 小时缓存，所以工作流在提交后会调用 `purge.jsdelivr.net` 主动刷新。

## 目录

```
crawler/
  http.py           通用抓取层（重试 / 限速 / UA 轮换）
  config.py         超时、间隔、UA 池
  cloud/
    treasury.py     美国财政部收益率曲线
    nyfed.py        纽约联储目标利率
    quotes.py       美元指数 / 原油（CNBC 主，Yahoo 备）
    build.py        组装 latest.json / series.json
    main.py         命令行入口
    selftest.py     离线解析自测（需 toolchain/fixtures 真实报文）
.github/workflows/daily.yml
```

## 数据说明

- 美债收益率是**前一日**收盘值（财政部每个工作日下午发布）。
- 美元指数用的是 CNBC 的**美元指数期货**报价，与现货指数（ICE DXY）有细微差别。
- 抓不到某项时不会让整个任务失败，会在 `errors` 字段里记录，App 端据此降级。

## 免责

数据仅用于个人学习和信息聚合，不构成任何投资建议。所有内容版权归原始来源方所有。
