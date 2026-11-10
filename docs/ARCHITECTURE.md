# 架构说明

「财经每日」的组成：**云端定时抓境外指标 + 手机端抓境内资讯公告 + 本地聚合展示**。

---

## 为什么拆成云端和手机两半

数据源按地理位置分成两拨，从哪儿抓成功率完全不同：

| 数据 | 从美国机房抓 | 从国内手机抓 |
|---|---|---|
| 美国财政部 / 纽约联储 / CNBC | ✅ 快且稳 | ⚠️ 慢，可能超时 |
| 华尔街见闻 / 东方财富 / 巨潮 | ⚠️ 可能被限流或返回空 | ✅ 快 |

所以：

- **云端（GitHub Actions）**：只抓境外指标，写成 JSON
- **手机端**：只抓境内资讯和公告，同时读云端 JSON

两边各干最擅长的部分，而不是两边都做全量。

---

## 数据流

```
GitHub Actions  每天 21:00 UTC（北京时间 05:00）
    │
    ├─ 美国财政部 CSV        → 美债 3M/2Y/5Y/10Y/30Y
    ├─ 纽约联储 API          → 美联储目标区间 / EFFR
    └─ CNBC 行情（Yahoo 备） → 美元指数 / WTI / 布伦特
    │
    └─ 写 data/latest.json + data/series.json
       └─ 提交到仓库 → jsDelivr 刷新缓存

手机 App 打开
    │
    ├─ 读 jsDelivr 上的 latest.json       → 指标
    ├─ 境内直连：东财 / 新浪 / 见闻 / 巨潮 → 兜底指标 + 资讯 + 公告
    └─ 三级降级：云端 → 境内直连 → 资讯正文提取数字
```

---

## 三级降级策略

指标取不到时不许白屏，按顺序降级，并且**必须把降级状态显示给用户**：

| 级别 | 来源 | 界面标记 |
|---|---|---|
| 1 | 云端 JSON（官方接口） | 无标记 |
| 2 | 手机直连东财 / 新浪 | 「境内源」角标 |
| 3 | 从资讯正文里用正则抠数字 | 「资讯提取」角标 + 综述里明确警告 |

第三级精度最低，所以绝不能悄悄用。

---

## 目录

```
crawler/cloud/        云端爬虫（Python，跑在 GitHub Actions）
  treasury.py         美国财政部收益率曲线
  nyfed.py            纽约联储目标利率
  quotes.py           美元指数 / 原油（CNBC 主，Yahoo 备）
  build.py            组装 latest.json / series.json
  main.py             命令行入口
  selftest.py         离线解析自测（需要 fixtures）

app/src/main/java/com/finapp/daily/
  data/
    Model.kt          数据模型
    Config.kt         云端 URL、节流间隔
    Db.kt             本地 SQLite（手写，不用 Room）
    Http.kt           HttpURLConnection 封装
    Keywords.kt       四主题关键词 + 重大事项词表 + 数字提取
    Sources.kt        各数据源解析
    Repo.kt           刷新编排与降级
    Digest.kt         每日综述（规则模板）
  ui/
    AppState.kt       状态容器
    Screens.kt        概览 / 资讯 / 公告
  MainActivity.kt
```

---

## 技术选型上的取舍

App 刻意只用 **Compose + activity-compose + core-ktx** 三个依赖，其余全部用平台自带能力：

| 常见选择 | 本项目 | 原因 |
|---|---|---|
| Room | 手写 `SQLiteOpenHelper` | 免掉 KSP 注解处理器，构建链更稳；只有 5 张表 |
| kotlinx.serialization / Retrofit | 内置 `org.json` + `HttpURLConnection` | 请求量小，接口都是 JSON 或纯文本 |
| Jsoup | 不需要 | 四个源全是结构化 JSON，无需解析 HTML |

这样做的直接好处：依赖越少，版本冲突越少，构建越不容易被上游改动打断。

> 举例：Compose BOM 从某版本起要求 `compileSdk 37` + `AGP 9.1`，
> 锁定 `compileSdk 36` 的项目必须选更早的 BOM。依赖多一层，这种连锁就多一次。

---

## 内容过滤

**资讯**：只保留命中「美债 / 美元 / 加息 / 原油」四组关键词的内容，其余丢弃。

**公告**：只保留重大事项（重组、业绩预告、重大合同、股权变动、监管问询、退市风险、停复牌等），
按标题关键词匹配而非公告类型编码——各板块的类型编码并不统一。

**每日综述**：用规则模板拼接，不接 AI，避免生成不可核实的表述。

---

## 已知问题

- GitHub Actions 的 `schedule` 触发不精确，常有几分钟到几十分钟延迟。
- jsDelivr 对分支引用有 12 小时缓存，因此工作流在提交后会主动调用 `purge.jsdelivr.net`。
- 美元指数用的是 CNBC 的指数期货报价，与现货指数（ICE DXY）有细微差别，已在字段说明中注明。
- 在部分 Windows 环境下，Android 模拟器会因为 WHPX（Windows Hypervisor Platform）问题无法启动，
  此时可用 JVM 单元测试验证解析逻辑。

---

## 免责声明

数据仅用于个人学习和信息聚合，不构成任何投资建议。内容版权归原始来源方所有。
