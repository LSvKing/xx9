#!/usr/bin/env python3
"""
独立的域名/token 抓取脚本 - 用 Playwright 跑真实 app，捕获当前可用的
API 域名 + accessToken + jwtToken，写入 MySQL 的 credentials 表。

token 是每次注册轮换的、域名也会变，所以这张表按时间追加历史，
爬虫读最新一条即可。

用法:
    uv run python fetch_creds.py                 # 抓一次并写库（默认开 xx9.com/enter）
    uv run python fetch_creds.py --url https://2fue20zkk5hbe5.xyz/recommend
    uv run python fetch_creds.py --show          # 打印库里最新一条
    uv run python fetch_creds.py --sync-config    # 抓取后顺便写回 config.json
    uv run python fetch_creds.py --headful        # 显示浏览器窗口（调试）

注意: 这些域名在国内需走代理才能连（直连会被 GFW 按 SNI 重置）。
"""
import asyncio
import json
import os
import re
import time
import base64
import argparse
from datetime import datetime
from urllib.parse import urlparse

import requests
import urllib3
import pymysql
import pymysql.cursors
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

urllib3.disable_warnings()

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
DEFAULT_URL = "https://xx9.com/enter"   # 永久发布页，最稳
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/147.0.0.0 Safari/537.36"


DEFAULT_KEYS = [
    "6eIZ4cxM5pqzUXcF", "84UZNK33cSVylz6Y", "jeSWRcTwHyAKwJDB",
    "i1hvJx9vuRt5zEBS", "1Yy1KOa75R7cnmkg", "4MVTQQAJlMpUIAiL",
    "T0RVp7KIPamrtQ33", "8HbPxhX6fjhhhwok", "ugvseZc5Kkj8ecmV",
    "G7i3OPcfNhBnAYpc",
]


def load_config() -> dict:
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            return json.load(f)
    return {}


def get_keys(cfg: dict) -> list:
    """keys 优先 config.json，其次 MySQL settings 表，最后内置默认（公开的）。"""
    if cfg.get("keys"):
        return cfg["keys"]
    try:
        conn = mysql_conn(cfg)
        try:
            cur = conn.cursor()
            cur.execute("SELECT v FROM settings WHERE k='keys'")
            row = cur.fetchone()
            if row:
                return json.loads(row["v"])
        finally:
            conn.close()
    except Exception:
        pass
    return DEFAULT_KEYS


# ============================================================
# MySQL
# ============================================================
def mysql_conn(cfg: dict, with_db=True):
    m = cfg.get("mysql", {})
    kw = dict(
        host=m.get("host", "localhost"), port=int(m.get("port", 3306)),
        user=m.get("user", "root"), password=m.get("password", ""),
        charset="utf8mb4", autocommit=True, cursorclass=pymysql.cursors.DictCursor,
    )
    if with_db:
        kw["database"] = m["database"]
    return pymysql.connect(**kw)


def ensure_table(cfg: dict):
    m = cfg.get("mysql", {})
    # 建库
    boot = mysql_conn(cfg, with_db=False)
    boot.cursor().execute(
        f"CREATE DATABASE IF NOT EXISTS `{m['database']}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
    )
    boot.close()
    # 建表
    conn = mysql_conn(cfg)
    conn.cursor().execute("""
        CREATE TABLE IF NOT EXISTS credentials (
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            api_base     VARCHAR(255),
            frontend     VARCHAR(255),
            media_base   VARCHAR(255),
            pic_base     VARCHAR(255),
            mp4_base     VARCHAR(255),
            access_token VARCHAR(255),
            jwt_token    TEXT,
            source_url   VARCHAR(255),
            captured_at  VARCHAR(32),
            INDEX idx_cred_captured (captured_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """)
    # 已有旧表则补列（列已存在会报错，忽略）
    for col in ("pic_base", "mp4_base"):
        try:
            conn.cursor().execute(f"ALTER TABLE credentials ADD COLUMN {col} VARCHAR(255)")
        except Exception:
            pass
    return conn


def insert_creds(conn, row: dict):
    conn.cursor().execute("""
        INSERT INTO credentials (api_base, frontend, media_base, pic_base, mp4_base,
            access_token, jwt_token, source_url, captured_at)
        VALUES (%(api_base)s, %(frontend)s, %(media_base)s, %(pic_base)s, %(mp4_base)s,
            %(access_token)s, %(jwt_token)s, %(source_url)s, %(captured_at)s)
    """, row)


def latest_creds(conn) -> dict:
    cur = conn.cursor()
    cur.execute("SELECT * FROM credentials ORDER BY id DESC LIMIT 1")
    return cur.fetchone()


# ============================================================
# 用刚抓到的凭证调 config/query 拿 CDN 前缀
# ============================================================
def fetch_cdn_prefixes(api_base, access_token, jwt_token, origin, keys) -> dict:
    """调 config/query 取图片/视频/mp4 前缀。失败返回空 dict。"""
    def enc(k, s):
        return base64.b64encode(AES.new(k.encode(), AES.MODE_ECB).encrypt(pad(s.encode(), 16))).decode()

    def dec(k, b):
        return unpad(AES.new(k.encode(), AES.MODE_ECB).decrypt(base64.b64decode(b)), 16)

    try:
        ts = int(time.time() * 1000)
        body = json.dumps({"method": 1, "uri": "config/query",
                           "params": {"groupKey": "APP",
                                      "key": "picBaseUrl,newPicBaseUrl,playLines,h5_play_line,mp4Domain"}},
                          separators=(",", ":"))
        r = requests.post(f"{api_base}/fast-endecode/main/request",
                          json={"data": enc(keys[ts % 10], body), "time": ts}, timeout=15, verify=False,
                          headers={"accesstoken": access_token, "jwttoken": jwt_token, "origin": origin,
                                   "content-type": "application/json", "user-agent": UA})
        d = r.json()
        conf = json.loads(dec(keys[d["time"] % 10], d["data"]))
        conf = conf.get("data") or conf.get("result") or conf
        def first_line(*keys):
            for kk in keys:
                try:
                    arr = json.loads(conf.get(kk) or "[]")
                    if arr:
                        return arr[0].get("line")
                except Exception:
                    pass
            return None
        return {
            # 图片用 playLines[0]（快线，原站图片函数取这条），回退 picBaseUrl
            "pic_base": first_line("playLines") or conf.get("picBaseUrl") or conf.get("newPicBaseUrl"),
            # 视频 m3u8 用 h5_play_line[0]（国线）
            "media_base": first_line("h5_play_line", "pc_play_line", "playLines"),
            "mp4_base": conf.get("mp4Domain"),
        }
    except Exception as e:
        print(f"  [config/query 拉 CDN 前缀失败: {str(e)[:60]}]")
        return {}


# ============================================================
# Playwright 抓取
# ============================================================
async def capture(url: str, headful: bool) -> dict:
    from playwright.async_api import async_playwright

    captured = {"api_base": None, "access_token": None, "jwt_token": None,
                "origin": None, "final_url": None}

    pw = await async_playwright().start()
    browser = await pw.chromium.launch(headless=not headful, args=["--ignore-certificate-errors"])
    ctx = await browser.new_context(ignore_https_errors=True, user_agent=UA, locale="zh-CN",
                                    viewport={"width": 1440, "height": 900})
    page = await ctx.new_page()

    def on_request(req):
        if "fast-endecode" in req.url:
            if not captured["api_base"]:
                m = re.match(r"(https?://[^/]+)", req.url)
                if m:
                    captured["api_base"] = m.group(1)
            for h, k in [("accesstoken", "access_token"), ("jwttoken", "jwt_token"), ("origin", "origin")]:
                v = req.headers.get(h)
                if v and not captured[k if k != "origin" else "origin"]:
                    captured[k if k != "origin" else "origin"] = v

    page.on("request", on_request)

    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=30000)
    except Exception:
        pass  # SPA 常超时，忽略，靠下面的等待收集请求
    # 给 app 时间发起 jwt-token / config 等请求
    for _ in range(12):
        await asyncio.sleep(1)
        if captured["api_base"] and captured["jwt_token"]:   # access_token 现已可选
            break
    captured["final_url"] = page.url

    await browser.close()
    await pw.stop()
    return captured


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_URL, help="入口页（默认 xx9.com/enter 永久发布页）")
    ap.add_argument("--headful", action="store_true", help="显示浏览器窗口")
    ap.add_argument("--sync-config", action="store_true", help="抓取后写回 config.json")
    ap.add_argument("--show", action="store_true", help="只打印库里最新一条，不抓取")
    args = ap.parse_args()

    cfg = load_config()

    if args.show:
        conn = ensure_table(cfg)
        row = latest_creds(conn)
        conn.close()
        if not row:
            print("credentials 表为空")
        else:
            print("最新凭证:")
            for k in ["captured_at", "api_base", "frontend", "media_base", "pic_base", "mp4_base", "access_token", "jwt_token", "source_url"]:
                v = row.get(k)
                print(f"  {k:<13}: {v}")
        return

    print(f"打开 {args.url} 抓取域名/token ...")
    cap = asyncio.run(capture(args.url, args.headful))

    if not cap["api_base"] or not cap["jwt_token"]:   # access_token 现已可选，只要这俩
        print("未捕获到 API 域名/jwt_token，可能原因:")
        print("  1. 代理未生效（这些域名直连会被重置，需走代理）")
        print("  2. 入口域名已失效，换一个 --url（或不带 --url 用默认 xx9.com/enter）")
        print(f"  当前捕获: {cap}")
        raise SystemExit(1)
    if not cap["access_token"]:
        print("  (注: access_token 没抓到，但接口只认 jwt_token，不影响)")

    frontend = (cap["origin"] or "").rstrip("/")
    if not frontend and cap["final_url"]:
        p = urlparse(cap["final_url"])
        frontend = f"{p.scheme}://{p.netloc}"

    # 用刚抓到的凭证拉 CDN 前缀（图片/视频/mp4），拉不到则沿用 config
    cdn = fetch_cdn_prefixes(cap["api_base"], cap["access_token"], cap["jwt_token"],
                             frontend or cap["api_base"], get_keys(cfg))

    row = {
        "api_base": cap["api_base"],
        "frontend": frontend or None,
        "media_base": cdn.get("media_base") or cfg.get("media_base"),
        "pic_base": cdn.get("pic_base") or cfg.get("pic_base"),
        "mp4_base": cdn.get("mp4_base") or cfg.get("mp4_base"),
        "access_token": cap["access_token"],
        "jwt_token": cap["jwt_token"],
        "source_url": args.url,
        "captured_at": datetime.now().isoformat(),
    }

    conn = ensure_table(cfg)
    insert_creds(conn, row)
    conn.close()

    print("\n已写入 MySQL credentials 表:")
    print(f"  api_base    : {row['api_base']}")
    print(f"  frontend    : {row['frontend']}")
    print(f"  media_base  : {row['media_base']}   (m3u8)")
    print(f"  pic_base    : {row['pic_base']}   (图片)")
    print(f"  mp4_base    : {row['mp4_base']}   (mp4)")
    print(f"  access_token: {row['access_token']}")
    print(f"  jwt_token   : {row['jwt_token'][:50]}...")

    if args.sync_config:
        cfg["api_base"] = row["api_base"]
        if row["frontend"]:
            cfg["frontend"] = row["frontend"]
        for k in ("media_base", "pic_base", "mp4_base"):
            if row[k]:
                cfg[k] = row[k]
        cfg["access_token"] = row["access_token"]
        cfg["jwt_token"] = row["jwt_token"]
        with open(CONFIG_PATH, "w") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        print("  config.json 已同步")


if __name__ == "__main__":
    main()
