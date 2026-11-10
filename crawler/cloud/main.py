# -*- coding: utf-8 -*-
"""云端爬虫入口（GitHub Actions 每天 21:00 UTC = 北京 05:00 调用）。

用法：
    python -m crawler.cloud.main            # 写入 data/latest.json 与 data/series.json
    python -m crawler.cloud.main --dry-run  # 只在终端打印，不写文件
"""

import argparse
import json
import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA_DIR = os.path.join(BASE_DIR, "data")

if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from crawler.cloud import build as builder   # noqa: E402


def _write_json(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description="抓取境外宏观指标并生成 JSON")
    parser.add_argument("--dry-run", action="store_true", help="只打印，不写文件")
    parser.add_argument("--data-dir", default=DATA_DIR, help="输出目录")
    parser.add_argument("--delay", type=float, default=0.3, help="请求间隔秒数")
    args = parser.parse_args(argv)

    latest, series, errors = builder.build(args.data_dir, delay=args.delay)

    print("生成时间: %s" % latest["generated_at"])
    print("数据源  : %s" % json.dumps(latest["sources"], ensure_ascii=False))
    if errors:
        print("失败项  : %s" % json.dumps(errors, ensure_ascii=False))
    print("-" * 60)
    for code, indicator in sorted(latest["indicators"].items()):
        change = indicator.get("change")
        tail = "" if change is None else "  (%+g)" % change
        print("  %-10s %-14s %s %s  @%s" % (
            code,
            indicator.get("title", ""),
            indicator.get("value"),
            indicator.get("unit", ""),
            indicator.get("as_of", ""),
        ) + tail)

    if not latest["indicators"]:
        print("\n[错误] 一个指标都没抓到")
        return 1

    if args.dry_run:
        print("\n--dry-run：未写文件")
        return 0

    latest_path = os.path.join(args.data_dir, "latest.json")
    series_path = os.path.join(args.data_dir, "series.json")
    _write_json(latest_path, latest)
    _write_json(series_path, series)
    print("\n已写入: %s" % latest_path)
    print("已写入: %s" % series_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
