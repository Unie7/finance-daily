# -*- coding: utf-8 -*-
"""通用抓取层：统一处理 UA 轮换、限速、重试、超时、编码。"""

import json
import random
import time

import requests

from . import config


def strip_jsonp(text):
    """把 callback({...}) 之类的 JSONP 包装剥成纯 JSON。"""
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        raise ValueError("响应中没有 JSON 对象: %r" % text[:120])
    return text[start:end + 1]


class Fetcher(object):
    """带限速与重试的 HTTP 抓取器。所有数据源共用一个实例即可。"""

    def __init__(self, delay=None, retries=None, timeout=None, session=None):
        self.delay = config.DEFAULT_DELAY if delay is None else delay
        self.retries = config.DEFAULT_RETRIES if retries is None else retries
        self.timeout = config.DEFAULT_TIMEOUT if timeout is None else timeout
        self.session = session or requests.Session()
        self._last = 0.0

    def _throttle(self):
        gap = time.time() - self._last
        if gap < self.delay:
            time.sleep(self.delay - gap)
        self._last = time.time()

    def request(self, method, url, **kwargs):
        headers = dict(config.HEADERS)
        headers["User-Agent"] = random.choice(config.UA_POOL)
        headers.update(kwargs.pop("headers", None) or {})

        last_error = None
        for attempt in range(1, self.retries + 1):
            self._throttle()
            try:
                resp = self.session.request(
                    method, url, headers=headers, timeout=self.timeout, **kwargs
                )
                if resp.status_code == 200:
                    return resp
                last_error = "HTTP %s" % resp.status_code
            except requests.RequestException as exc:
                last_error = repr(exc)
            # 指数退避：0.5s, 1s, 2s ...
            time.sleep(min(0.5 * (2 ** (attempt - 1)), 5))

        raise RuntimeError("请求失败(%s) %s" % (last_error, url))

    def get_text(self, url, params=None, headers=None, encoding=None):
        resp = self.request("GET", url, params=params, headers=headers)
        resp.encoding = encoding or "utf-8"
        return resp.text

    def get_json(self, url, params=None, headers=None, jsonp=False, encoding=None):
        text = self.get_text(url, params=params, headers=headers, encoding=encoding)
        if jsonp:
            text = strip_jsonp(text)
        return json.loads(text)

    def post_form(self, url, data=None, headers=None, encoding=None):
        hdrs = {"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}
        hdrs.update(headers or {})
        resp = self.request("POST", url, data=data, headers=hdrs)
        resp.encoding = encoding or "utf-8"
        return resp.text
