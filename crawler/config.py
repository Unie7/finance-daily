# -*- coding: utf-8 -*-
"""全局配置：路径、超时、限速、请求头。"""

import os

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")

# 抓取参数
DEFAULT_TIMEOUT = 15      # 单次请求超时（秒）
DEFAULT_RETRIES = 3       # 失败重试次数
DEFAULT_DELAY = 0.15      # 相邻请求最小间隔（秒），别调太小，容易被封

# 随机 UA，降低被识别为爬虫的概率
UA_POOL = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
]

HEADERS = {
    "User-Agent": UA_POOL[0],
    "Accept": "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Connection": "keep-alive",
}


def ensure_data_dir():
    """确保 data 目录存在并返回其路径。"""
    if not os.path.isdir(DATA_DIR):
        os.makedirs(DATA_DIR)
    return DATA_DIR
