# -*- coding: utf-8 -*-
"""组装云端产出：latest.json + series.json。

latest.json  —— 手机端只需要这一个文件就能渲染全部指标卡
series.json  —— 最近 180 天序列，用于画趋势
"""

import datetime
import json
import os

from . import nyfed, quotes, treasury

HISTORY_DAYS = 180

CODE_TITLES = {
    "US3M": "美债3月期",
    "US2Y": "美债2年期",
    "US5Y": "美债5年期",
    "US10Y": "美债10年期",
    "US30Y": "美债30年期",
    "FED_TARGET": "美联储目标利率区间",
    "EFFR": "有效联邦基金利率",
    "DXY": "美元指数",
    "WTI": "WTI原油",
    "BRENT": "布伦特原油",
}


def _merge_history(existing, fresh, indicators):
    """把今天的新值并进已有序列，去重、排序、截断到 HISTORY_DAYS。"""
    merged = {}
    for code, points in (existing or {}).items():
        merged[code] = list(points or [])

    for code, points in (fresh or {}).items():
        merged[code] = list(points)

    today = datetime.date.today().isoformat()
    for code, indicator in (indicators or {}).items():
        value = indicator.get("value")
        if not isinstance(value, (int, float)):
            continue                      # 目标区间这类字符串指标不画趋势
        points = merged.setdefault(code, [])
        day = indicator.get("as_of") or today
        points = [p for p in points if p.get("date") != day]
        points.append({"date": day, "value": value})
        merged[code] = points

    for code, points in list(merged.items()):
        points.sort(key=lambda p: p.get("date", ""))
        merged[code] = points[-HISTORY_DAYS:]
    return merged


def build(data_dir, delay=0.3):
    """抓齐所有境外指标，返回 (latest, series, errors)。"""
    indicators = {}
    series = {}
    errors = {}
    sources = {}

    try:
        ind, ser = treasury.fetch(delay=delay)
        indicators.update(ind)
        series.update(ser)
        sources["treasury"] = "U.S. Treasury"
    except Exception as exc:
        errors["treasury"] = str(exc)

    try:
        indicators.update(nyfed.fetch(delay=delay))
        sources["nyfed"] = "Federal Reserve Bank of New York"
    except Exception as exc:
        errors["nyfed"] = str(exc)

    try:
        ind, ser, name = quotes.fetch(delay=delay)
        indicators.update(ind)
        series.update(ser)
        sources["quotes"] = name
    except Exception as exc:
        errors["quotes"] = str(exc)

    series_path = os.path.join(data_dir, "series.json")
    existing = {}
    if os.path.isfile(series_path):
        try:
            with open(series_path, "r", encoding="utf-8") as fh:
                existing = (json.load(fh) or {}).get("series") or {}
        except Exception:
            existing = {}

    series = _merge_history(existing, series, indicators)

    for code, indicator in indicators.items():
        indicator.setdefault("title", CODE_TITLES.get(code, code))

    latest = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sources": sources,
        "errors": errors,
        "indicators": indicators,
    }
    series_doc = {
        "generated_at": latest["generated_at"],
        "series": series,
    }
    return latest, series_doc, errors
