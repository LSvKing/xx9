#!/usr/bin/env python3
"""
主播视频爬虫 - SQLite 版本

安装:
	uv sync

运行:
	uv run python crawler.py --all                    # 全站抓取列表
	uv run python crawler.py --all --detail           # 全站抓取列表+详情
	uv run python crawler.py --detail                 # 独立补爬详情（从DB取未解析的）
	uv run python crawler.py -t 动漫 -p 10            # 按标签爬列表
	uv run python crawler.py -t 动漫 -p 10 --detail   # 按标签爬列表+详情
	uv run python crawler.py --download               # 下载所有未下载视频
	uv run python crawler.py --download -w 3          # 下载（3并发）
	uv run python crawler.py -d 1664643               # 单个详情
"""
import json
import base64
import os
import re
import time
import sqlite3
import argparse
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

API_BASE = "https://api.1d1bzspqmi46l.xyz"
API_PATH = "/fast-endecode/main/request"
FRONTEND = "https://by2uvofj99p0gf.xyz"
MEDIA_BASE = "https://rr.rxjhwl.com"
DB_PATH = "output/crawler.db"

ACCESS_TOKEN = "FDY7OBOZ926VQGT3A189EZZKOWJ5GBHP"
JWT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhZHNDb2RlIjoiREZIIiwic2l0ZUlkIjoxLCJleHAiOjE3ODA5NDc2MTF9.NzdGaKF7GjYKpKPuonXAzmr7nmU9lijtFRIw7VnF85M"

KEYS = [
    "6eIZ4cxM5pqzUXcF", "84UZNK33cSVylz6Y", "jeSWRcTwHyAKwJDB",
    "i1hvJx9vuRt5zEBS", "1Yy1KOa75R7cnmkg", "4MVTQQAJlMpUIAiL",
    "T0RVp7KIPamrtQ33", "8HbPxhX6fjhhhwok", "ugvseZc5Kkj8ecmV",
    "G7i3OPcfNhBnAYpc",
]

PAGE_SIZE = 20


def aes_enc(key: str, plain: str) -> str:
    c = AES.new(key.encode(), AES.MODE_ECB)
    return base64.b64encode(c.encrypt(pad(plain.encode(), 16))).decode()


def aes_dec(key: str, b64: str) -> bytes:
    c = AES.new(key.encode(), AES.MODE_ECB)
    return unpad(c.decrypt(base64.b64decode(b64)), 16)


def api_call(uri: str, method: int = 1, params: dict = None) -> dict:
    ts = int(time.time() * 1000)
    key = KEYS[ts % 10]
    bp = json.dumps({"method": method, "params": params or {}, "uri": uri}, separators=(",", ":"))
    ed = aes_enc(key, bp)
    headers = {
        "accept": "application/json", "content-type": "application/json",
        "accesstoken": ACCESS_TOKEN, "jwttoken": JWT_TOKEN,
        "origin": FRONTEND,
        "user-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/147.0.0.0",
    }
    resp = requests.post(f"{API_BASE}{API_PATH}", headers=headers, json={"data": ed, "time": ts}, timeout=30)
    if resp.status_code != 200:
        raise Exception(f"HTTP {resp.status_code}")
    data = resp.json()
    if "data" not in data or "time" not in data:
        return data
    rk = KEYS[data["time"] % 10]
    return json.loads(aes_dec(rk, data["data"]))


def full_url(path: str) -> str:
    """相对路径拼域名，导出时使用"""
    if not path or path.startswith("http"):
        return path or ""
    return FRONTEND + (path if path.startswith("/") else "/" + path)


def video_url(path: str) -> str:
    """视频 m3u8 拼 CDN 域名"""
    if not path or path.startswith("http"):
        return path or ""
    return MEDIA_BASE + (path if path.startswith("/") else "/" + path)


class DB:
    def __init__(self, path=DB_PATH):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.path = path
        self._local = threading.local()
        self._lock = threading.Lock()
        self._init_schema()

    @property
    def conn(self):
        if not hasattr(self._local, "conn") or self._local.conn is None:
            # 使用 check_same_thread=False 允许跨线程
            conn = sqlite3.connect(self.path, check_same_thread=False)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            self._local.conn = conn
        return self._local.conn

    def _init_schema(self):
        self.conn.executescript("""
            CREATE TABLE IF NOT EXISTS items (
                id INTEGER PRIMARY KEY,
                vodId TEXT,
                title TEXT,
                duration INTEGER,
                quality TEXT,
                vodPic TEXT,
                gif TEXT,
                preview TEXT,
                author TEXT,
                authorId INTEGER,
                authorAvatar TEXT,
                fansNum INTEGER,
                readNumber INTEGER,
                likeNumber INTEGER,
                comments INTEGER,
                tags TEXT,
                groups TEXT,
                themes TEXT,
                createTime INTEGER,
                vodTime INTEGER,
                crawled_at TEXT
            );
            CREATE TABLE IF NOT EXISTS details (
                id INTEGER PRIMARY KEY,
                newAddr TEXT,
                mp4 TEXT,
                playUrls TEXT,
                groupNames TEXT,
                statistics TEXT,
                authorDetail TEXT,
                downloaded INTEGER DEFAULT 0,
                download_path TEXT,
                detail_at TEXT
            );
            CREATE TABLE IF NOT EXISTS progress (
                tag TEXT PRIMARY KEY,
                page INTEGER,
                total INTEGER,
                collected INTEGER,
                updated_at TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_items_tags ON items(tags);
            CREATE INDEX IF NOT EXISTS idx_items_authorId ON items(authorId);
            CREATE INDEX IF NOT EXISTS idx_items_crawled ON items(crawled_at);
            CREATE INDEX IF NOT EXISTS idx_details_downloaded ON details(downloaded);
        """)
        self.conn.commit()

    # --- items ---
    def item_exists(self, iid: int) -> bool:
        return self.conn.execute("SELECT 1 FROM items WHERE id=?", (iid,)).fetchone() is not None

    def insert_item(self, item: dict):
        a = item.get("authors", {}) or {}
        with self._lock:
            self.conn.execute("""
                INSERT OR IGNORE INTO items (id, vodId, title, duration, quality, vodPic, gif, preview,
                    author, authorId, authorAvatar, fansNum, readNumber, likeNumber, comments,
                    tags, groups, themes, createTime, vodTime, crawled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                item.get("id"),
                item.get("vodId"),
                item.get("title"),
                item.get("vodDuration"),
                item.get("vodVersion"),
                item.get("vodPic"),
                item.get("gif"),
                item.get("preview"),
                a.get("nickName"),
                a.get("id"),
                a.get("avatar"),
                a.get("fansNum"),
                item.get("readNumber"),
                item.get("likeNumber"),
                item.get("comments"),
                json.dumps(item.get("tags"), ensure_ascii=False),
                json.dumps(item.get("groups")),
                json.dumps(item.get("themes")),
                item.get("createTime"),
                item.get("vodTime"),
                datetime.now().isoformat(),
            ))
            self.conn.commit()

    def count_items(self) -> int:
        return self.conn.execute("SELECT COUNT(*) FROM items").fetchone()[0]

    # --- details ---
    def detail_exists(self, iid: int) -> bool:
        return self.conn.execute("SELECT 1 FROM details WHERE id=?", (iid,)).fetchone() is not None

    def insert_detail(self, data: dict):
        vod = data.get("result", {}).get("vod", {})
        author = data.get("result", {}).get("author", {})
        stats = data.get("result", {}).get("statistics", {})
        group_names = vod.get("group_names", [])

        play_urls = vod.get("vodFullPlayUrl", [])
        if isinstance(play_urls, str):
            play_urls = [play_urls]
        elif play_urls is None:
            play_urls = []
        play_urls_full = [{"addr": u.get("addr"), "type": u.get("type"),
                           "duration": u.get("duration"), "size": u.get("size")} for u in play_urls]

        with self._lock:
            self.conn.execute("""
                INSERT OR REPLACE INTO details (id, newAddr, mp4, playUrls, groupNames,
                    statistics, authorDetail, detail_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                vod.get("id"),
                vod.get("newAddr"),
                vod.get("mp4"),
                json.dumps(play_urls_full, ensure_ascii=False),
                json.dumps([{"id": g["id"], "name": g.get("groupName"), "desc": g.get("description")}
                            for g in group_names], ensure_ascii=False),
                json.dumps(stats, ensure_ascii=False),
                json.dumps({
                    "nickName": author.get("nickName"),
                    "avatar": author.get("avatar"),
                    "introduce": author.get("introduce"),
                    "fansNum": author.get("fansNum"),
                }, ensure_ascii=False),
                datetime.now().isoformat(),
            ))
            self.conn.commit()

    def count_details(self) -> int:
        return self.conn.execute("SELECT COUNT(*) FROM details").fetchone()[0]

    def count_downloaded(self) -> int:
        return self.conn.execute("SELECT COUNT(*) FROM details WHERE downloaded=1").fetchone()[0]

    def mark_downloaded(self, iid: int, path: str):
        with self._lock:
            self.conn.execute("UPDATE details SET downloaded=1, download_path=? WHERE id=?", (path, iid))
            self.conn.commit()

    def get_undownloaded(self, limit=None) -> list:
        sql = """
            SELECT d.*, i.title FROM details d
            LEFT JOIN items i ON d.id = i.id
            WHERE d.downloaded = 0 AND d.playUrls IS NOT NULL
        """
        if limit:
            sql += f" LIMIT {limit}"
        return self.conn.execute(sql).fetchall()

    # --- progress ---
    def get_progress(self, tag: str) -> dict:
        row = self.conn.execute("SELECT * FROM progress WHERE tag=?", (tag,)).fetchone()
        return dict(row) if row else {"tag": tag, "page": 0, "total": 0, "collected": 0}

    def set_progress(self, tag: str, page: int, total: int, collected: int):
        with self._lock:
            self.conn.execute("""
                INSERT OR REPLACE INTO progress (tag, page, total, collected, updated_at)
                VALUES (?, ?, ?, ?, ?)
            """, (tag, page, total, collected, datetime.now().isoformat()))
            self.conn.commit()

    def close(self):
        self.conn.close()


def crawl_list(db: DB, tag: str, max_pages: int):
    """按标签爬取"""
    prog = db.get_progress(tag)
    page = prog["page"] + 1
    total = prog["total"]
    while page <= max_pages:
        try:
            result = api_call("cms/vod/search", method=2, params={
                "wd": tag, "page": page, "pageSize": PAGE_SIZE,
            })
        except Exception as e:
            print(f"[{tag}] 第{page}页 请求失败: {e}")
            time.sleep(5)
            continue
        if result.get("code") != "0000":
            print(f"[{tag}] 第{page}页 错误: {result.get('message')}")
            break
        items = result.get("data", [])
        total = result.get("total", 0)
        if not items:
            break
        new_count = sum(1 for it in items if not db.item_exists(it.get("id")))
        for item in items:
            if not db.item_exists(item.get("id")):
                db.insert_item(item)
        collected = db.count_items()
        db.set_progress(tag, page, total, collected)
        print(f"[{tag}] 第{page}/{max_pages}页 新增{new_count}条 累计{collected}")
        if len(items) < PAGE_SIZE:
            break
        page += 1
        time.sleep(0.5)
    print(f"[{tag}] 完成! 共 {db.count_items()} 条")


def crawl_all(db: DB):
    """全站抓取 - 无关键词搜索，跑到无数据为止"""
    tag = "__all__"
    prog = db.get_progress(tag)
    page = prog["page"] + 1
    while True:
        try:
            result = api_call("cms/vod/search", method=2, params={
                "wd": "", "page": page, "pageSize": PAGE_SIZE,
            })
        except Exception as e:
            print(f"[全站] 第{page}页 请求失败: {e}")
            time.sleep(5)
            continue
        if result.get("code") != "0000":
            print(f"[全站] 第{page}页 错误: {result.get('message')}")
            break
        items = result.get("data", [])
        total = result.get("total", 0)
        if not items:
            break
        new_count = sum(1 for it in items if not db.item_exists(it.get("id")))
        for item in items:
            if not db.item_exists(item.get("id")):
                db.insert_item(item)
        collected = db.count_items()
        db.set_progress(tag, page, total, collected)
        print(f"[全站] 第{page}页 新增{new_count}条 累计{collected}/{total} 进度{collected*100//max(total,1)}%")
        if len(items) < PAGE_SIZE:
            break
        page += 1
        time.sleep(0.5)
    print(f"[全站] 完成! 共 {db.count_items()} 条")


def crawl_details(db: DB, tag: str, workers: int = 5):
    rows = db.conn.execute("""
        SELECT id, title FROM items
        WHERE id NOT IN (SELECT id FROM details)
    """).fetchall()

    todo = [dict(r) for r in rows]
    if not todo:
        print(f"[{tag}] 详情已全部爬取")
        return

    print(f"[{tag}] 待爬详情: {len(todo)} 条, 并发: {workers}")

    done_count = [0]  # mutable counter for thread safety
    error_count = [0]
    lock = threading.Lock()

    def fetch_one(row):
        vid = row["id"]
        try:
            data = api_call(f"cms/vod/detail/{vid}", method=1)
            db.insert_detail(data)
            with lock:
                done_count[0] += 1
                if done_count[0] % 50 == 0:
                    print(f"  已爬 {done_count[0]}/{len(todo)} ({error_count[0]} err)")
            return True
        except Exception:
            with lock:
                error_count[0] += 1
            time.sleep(2)
            return False

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_one, r): r for r in todo}
        for _ in as_completed(futures):
            pass

    print(f"[{tag}] 详情完成! 成功 {done_count[0]}, 失败 {error_count[0]}, 共 {db.count_details()} 条")


def download_videos(db: DB, workers: int = 3):
    rows = db.get_undownloaded()
    if not rows:
        print("没有待下载的视频")
        return

    print(f"待下载: {len(rows)} 个, 并发: {workers}")
    dl_dir = os.path.join("output", "videos")
    os.makedirs(dl_dir, exist_ok=True)

    done_count = [0]
    fail_count = [0]
    lock = threading.Lock()
    total = len(rows)

    def download_one(row):
        vid = row["id"]
        title = re.sub(r'[\\/*?:"<>|]', "_", row["title"] or str(vid))[:40]
        play_urls = json.loads(row["playUrls"] or "[]")
        url = video_url(play_urls[0]["addr"]) if play_urls else None

        if not url:
            return

        out = os.path.join(dl_dir, f"{vid}.mp4")
        if os.path.exists(out):
            db.mark_downloaded(vid, out)
            with lock:
                done_count[0] += 1
            return

        size = play_urls[0].get("size", 0) / 1024 / 1024 if play_urls else 0
        with lock:
            done = done_count[0] + fail_count[0]
            print(f"  [{done+1}/{total}] {title} ({size:.0f}MB) ...", end=" ", flush=True)

        try:
            subprocess.run([
                "ffmpeg", "-y", "-loglevel", "error",
                "-headers", f"Referer: {FRONTEND}/\r\nOrigin: {FRONTEND}",
                "-i", url, "-c", "copy", "-bsf:a", "aac_adtstoasc", out,
            ], check=True, timeout=300)
            db.mark_downloaded(vid, out)
            actual = os.path.getsize(out) / 1024 / 1024
            with lock:
                done_count[0] += 1
                print(f"完成 ({actual:.0f}MB)")
        except subprocess.TimeoutExpired:
            with lock:
                fail_count[0] += 1
                print("超时")
        except Exception as e:
            with lock:
                fail_count[0] += 1
                print(f"失败: {e}")

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(download_one, r) for r in rows]
        for _ in as_completed(futures):
            pass

    print(f"下载完成! 成功 {done_count[0]}, 失败 {fail_count[0]}, 共 {db.count_downloaded()} 已下载")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("-t", "--tag", help="标签名")
    parser.add_argument("-p", "--pages", type=int, default=50, help="最大页数")
    parser.add_argument("-d", "--detail-id", help="爬取单个详情")
    parser.add_argument("--detail", action="store_true", help="爬完列表后继续爬详情")
    parser.add_argument("--download", action="store_true", help="下载未下载的视频")
    parser.add_argument("--all", action="store_true", help="全站抓取")
    parser.add_argument("-w", "--workers", type=int, default=5, help="详情/下载并发数")
    args = parser.parse_args()

    db = DB()

    try:
        if args.detail_id:
            data = api_call(f"cms/vod/detail/{args.detail_id}", method=1)
            db.insert_detail(data)
            print(f"详情已保存: {args.detail_id}")
            return

        if args.download:
            download_videos(db, args.workers)
            return

        if args.detail and not args.tag and not args.all:
            crawl_details(db, "__all__", args.workers)
            return

        if args.all:
            crawl_all(db)
            if args.detail:
                crawl_details(db, "__all__", args.workers)
            return

        if args.tag:
            crawl_list(db, args.tag, args.pages)
            if args.detail:
                crawl_details(db, args.tag, args.workers)
        else:
            print("=" * 50)
            print("主播视频爬虫 - 两阶段模式")
            print("=" * 50)
            print(f"数据库: {DB_PATH}")
            print(f"已收集: {db.count_items()} 列表项, {db.count_details()} 详情, {db.count_downloaded()} 已下载")
            print()
            print("阶段1 - 爬取信息:")
            print("  python crawler.py --all                         # 全站抓取列表")
            print("  python crawler.py --all --detail                # 全站抓取列表+详情")
            print("  python crawler.py --detail                      # 独立补爬详情(从DB取未解析的)")
            print("  python crawler.py -t <标签> -p <页数>            # 按标签爬列表")
            print("  python crawler.py -t <标签> -p <页数> --detail   # 爬列表+详情")
            print()
            print("阶段2 - 下载视频:")
            print("  python crawler.py --download                     # 下载所有未下载的")
    finally:
        db.close()


if __name__ == "__main__":
    main()
