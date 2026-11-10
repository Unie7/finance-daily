# -*- coding: utf-8 -*-
"""美元指数 / 原油报价。

主源 CNBC 免费行情接口（已实测可用），失败回退 Yahoo Finance。
两者都是公开接口、免 Key。
"""

import datetime

from ..http import Fetcher

CNBC = (
    "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
    "?symbols=%40DX.1%7C%40CL.1%7C%40LCO.1"
    "&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json&events=1"
)

YAHOO = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1y&interval=1d"

# 内部代码, Yahoo 符号, CNBC 符号, 名称, 单位
SYMBOLS = [
    ("DXY",   "DX-Y.NYB", "@DX.1",  "美元指数",    ""),
    ("WTI",   "CL=F",     "@CL.1",  "WTI原油",     "美元/桶"),
    ("BRENT", "BZ=F",     "@LCO.1", "布伦特原油",  "美元/桶"),
]


def _num(value):
    """CNBC 的数值带 '+' 号和千分位逗号，统一转成 float。"""
    try:
        return float(str(value).replace(",", "").strip())
    except (TypeError, ValueError):
        return None


# ------------------------------------------------------------------- CNBC

def parse_cnbc(payload):
    """纯解析 CNBC 响应，返回 indicators 字典。"""
    quotes = ((payload or {}).get("FormattedQuoteResult") or {}).get("FormattedQuote") or []
    by_symbol = {}
    for quote in quotes:
        if isinstance(quote, dict) and quote.get("symbol"):
            by_symbol[quote["symbol"]] = quote

    indicators = {}
    for code, _, cnbc_symbol, title, unit in SYMBOLS:
        quote = by_symbol.get(cnbc_symbol)
        if not quote:
            continue
        price = _num(quote.get("last"))
        if price is None:
            continue
        indicators[code] = {
            "value": round(price, 3),
            "change": _num(quote.get("change")),
            "as_of": (quote.get("last_time") or "")[:10],
            "title": title,
            "unit": unit,
        }
    if not indicators:
        raise RuntimeError("CNBC 行情返回空")
    return indicators


def fetch_cnbc(delay=0.2):
    payload = Fetcher(delay=delay).get_json(CNBC)
    return parse_cnbc(payload)


# ------------------------------------------------------------------ Yahoo

def parse_yahoo(payload):
    """纯解析 Yahoo chart 响应，返回 (indicators, series)。"""
    result = (((payload or {}).get("chart") or {}).get("result") or [None])[0]
    if not result:
        raise RuntimeError("Yahoo 返回空 result")

    meta = result.get("meta") or {}
    price = _num(meta.get("regularMarketPrice"))
    prev = _num(meta.get("chartPreviousClose")) or _num(meta.get("previousClose"))
    if price is None:
        raise RuntimeError("Yahoo 缺少 regularMarketPrice")

    stamps = result.get("timestamp") or []
    closes = (((result.get("indicators") or {}).get("quote") or [{}])[0].get("close")) or []
    points = []
    for stamp, close in zip(stamps, closes):
        if close is None:
            continue
        day = datetime.datetime.fromtimestamp(stamp, datetime.timezone.utc).date().isoformat()
        points.append({"date": day, "value": round(close, 3)})

    change = round(price - prev, 3) if prev else None
    return {
        "value": round(price, 3),
        "change": change,
        "as_of": points[-1]["date"] if points else "",
    }, points[-180:]


def fetch_yahoo(delay=0.2):
    fetcher = Fetcher(delay=delay)
    indicators, series = {}, {}
    for code, yahoo_symbol, _, title, unit in SYMBOLS:
        indicator, points = parse_yahoo(
            fetcher.get_json(YAHOO.format(symbol=yahoo_symbol))
        )
        indicator.update({"title": title, "unit": unit})
        indicators[code] = indicator
        series[code] = points
    return indicators, series


# ------------------------------------------------------------------ 入口

def fetch(delay=0.3):
    """返回 (indicators, series, source_name)。CNBC 优先，Yahoo 兜底。"""
    try:
        return fetch_cnbc(delay=delay), {}, "CNBC"
    except Exception as cnbc_error:
        try:
            indicators, series = fetch_yahoo(delay=delay)
            return indicators, series, "Yahoo Finance"
        except Exception as yahoo_error:
            raise RuntimeError("CNBC 与 Yahoo 均失败：%s / %s" % (cnbc_error, yahoo_error))
