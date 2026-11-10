# -*- coding: utf-8 -*-
"""纽约联储：美联储目标利率与 EFFR（有效联邦基金利率）。

免 Key 接口：
  https://markets.newyorkfed.org/api/rates/unsecured/effr/last/1.json

返回含 targetRateFrom / targetRateTo，即 FOMC 目标区间上下限。
"""

from ..http import Fetcher

API = "https://markets.newyorkfed.org/api/rates/unsecured/effr/last/1.json"


def _fmt(value):
    """3.75 -> '3.75'，4.0 -> '4'，避免出现 4.00 这种不齐整。"""
    text = ("%g" % float(value))
    if "." not in text:
        text += ".00"
    return text


def parse(payload):
    """纯解析，返回 indicators 字典。"""
    rates = (payload or {}).get("refRates") or []
    if not rates:
        raise RuntimeError("纽约联储接口返回空")

    row = rates[0]
    as_of = row.get("effectiveDate") or ""
    low = row.get("targetRateFrom")
    high = row.get("targetRateTo")
    effr = row.get("percentRate")

    indicators = {}
    if low is not None and high is not None:
        indicators["FED_TARGET"] = {
            "value": "%s-%.2f" % (_fmt(low), float(high)),
            "low": low,
            "high": high,
            "change": None,
            "as_of": as_of,
            "title": "美联储目标利率区间",
            "unit": "%",
        }
    if effr is not None:
        indicators["EFFR"] = {
            "value": effr,
            "change": None,
            "as_of": as_of,
            "title": "有效联邦基金利率",
            "unit": "%",
        }
    if not indicators:
        raise RuntimeError("纽约联储接口没有目标利率字段")
    return indicators


def fetch(delay=0.2):
    return parse(Fetcher(delay=delay).get_json(API))
