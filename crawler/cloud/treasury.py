# -*- coding: utf-8 -*-
"""美国财政部：每日国债收益率曲线。

官方 CSV，免 Key：
  https://home.treasury.gov/resource-center/data-chart-center/
  interest-rates/daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve

数据按日期倒序返回，字段如 "10 Yr"、"2 Yr"、"30 Yr"、"3 Mo"。
"""

import csv
import datetime
import io

from ..http import Fetcher

BASE = (
    "https://home.treasury.gov/resource-center/data-chart-center/"
    "interest-rates/daily-treasury-rates.csv/{year}/all"
    "?type=daily_treasury_yield_curve&field_tdr_date_value={year}&page&_format=csv"
)

# CSV 表头 -> 内部指标代码
TENORS = [
    ("3 Mo", "US3M"),
    ("2 Yr", "US2Y"),
    ("5 Yr", "US5Y"),
    ("10 Yr", "US10Y"),
    ("30 Yr", "US30Y"),
]

TITLES = {
    "US3M": "美债3月期",
    "US2Y": "美债2年期",
    "US5Y": "美债5年期",
    "US10Y": "美债10年期",
    "US30Y": "美债30年期",
}

HISTORY_DAYS = 180


def _to_iso(text):
    """MM/DD/YYYY -> YYYY-MM-DD，失败返回 None。"""
    try:
        return datetime.datetime.strptime((text or "").strip(), "%m/%d/%Y").date().isoformat()
    except ValueError:
        return None


def _rows(text):
    """把 CSV 解析成按日期升序的行列表。"""
    rows = []
    reader = csv.DictReader(io.StringIO((text or "").lstrip("\ufeff")))
    for raw in reader:
        date = _to_iso(raw.get("Date"))
        if not date:
            continue
        item = {"date": date}
        for tenor, code in TENORS:
            cell = (raw.get(tenor) or "").strip()
            try:
                item[code] = float(cell)
            except ValueError:
                item[code] = None
        rows.append(item)
    rows.sort(key=lambda r: r["date"])
    return rows


def parse(text):
    """纯解析，返回 (indicators, series)。方便离线用真实报文做测试。"""
    rows = _rows(text)
    if not rows:
        raise RuntimeError("财政部收益率曲线返回空数据")

    latest = rows[-1]
    prev = rows[-2] if len(rows) > 1 else None

    indicators = {}
    for _, code in TENORS:
        value = latest.get(code)
        if value is None:
            continue
        change = None
        if prev and prev.get(code) is not None:
            change = round(value - prev[code], 3)
        indicators[code] = {
            "value": value,
            "change": change,
            "as_of": latest["date"],
            "title": TITLES.get(code, code),
            "unit": "%",
        }

    series = {}
    window = rows[-HISTORY_DAYS:]
    for _, code in TENORS:
        points = [{"date": r["date"], "value": r[code]}
                  for r in window if r.get(code) is not None]
        if points:
            series[code] = points

    return indicators, series


def fetch(delay=0.2):
    fetcher = Fetcher(delay=delay)
    last_error = None
    year = datetime.date.today().year
    for candidate in (year, year - 1):
        try:
            text = fetcher.get_text(BASE.format(year=candidate))
        except Exception as exc:
            last_error = exc
            continue
        try:
            return parse(text)
        except Exception as exc:
            last_error = exc
            continue
    raise RuntimeError("财政部收益率曲线抓取失败：%s" % last_error)
