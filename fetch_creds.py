#!/usr/bin/env python3
"""
协议凭证刷新。用 credentials 表里最近一条 api/frontend 做基线，
protocol_auth.bootstrap 注册访客会话，校验搜索和详情后追加写入 credentials，
并写本地 .protocol-credentials.json（crawler 优先读这个文件）。

用法:
    uv run python fetch_creds.py              # 刷新并写库
    uv run python fetch_creds.py --show       # 只打印库里最新一条的时间
    uv run python fetch_creds.py --sync-config
"""
import asyncio
import json
import os
import re
import time
import base64
import argparse
from datetime import datetime, timezone
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



# Protocol bootstrap uses the last discovered API/frontend and validates a detail response.
def main():
    from protocol_auth import bootstrap, save_local
    ap=argparse.ArgumentParser()
    ap.add_argument("--show",action="store_true")
    ap.add_argument("--sync-config",action="store_true")
    args=ap.parse_args();cfg=load_config();conn=mysql_conn(cfg)
    try:previous=latest_creds(conn)
    finally:conn.close()
    if not previous:raise RuntimeError("No API/frontend baseline in credentials table")
    if args.show:
        print("latest credential timestamp:",previous.get("captured_at"));return
    row=bootstrap(previous,get_keys(cfg))
    row["captured_at"]=datetime.now(timezone.utc).isoformat()
    conn=mysql_conn(cfg)
    try:insert_creds(conn,row)
    finally:conn.close()
    save_local(row)
    if args.sync_config:
        for key in ("access_token","jwt_token","api_base","frontend"):cfg[key]=row[key]
        with open(CONFIG_PATH,"w") as f:json.dump(cfg,f,ensure_ascii=False,indent=2)
    print("Protocol session validated against search and detail; credentials committed at",row["captured_at"])

if __name__=="__main__":
    main()
