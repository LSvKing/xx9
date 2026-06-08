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
import argparse
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

import requests
import pymysql
import pymysql.cursors
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

# ============================================================
# 配置 - 从 config.json 读取，不存在则用默认值
# ============================================================
def _load_config():
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
    defaults = {
        "mysql": {
            "host": "localhost", "port": 3306,
            "user": "root", "password": "", "database": "crawler",
        },
        "api_base": "https://api.1d1bzspqmi46l.xyz",
        "frontend": "https://rw345o29u6ivj.xyz",
        "media_base": "https://rr.rxjhwl.com",
        "access_token": "",
        "jwt_token": "",
        "keys": [
            "6eIZ4cxM5pqzUXcF", "84UZNK33cSVylz6Y", "jeSWRcTwHyAKwJDB",
            "i1hvJx9vuRt5zEBS", "1Yy1KOa75R7cnmkg", "4MVTQQAJlMpUIAiL",
            "T0RVp7KIPamrtQ33", "8HbPxhX6fjhhhwok", "ugvseZc5Kkj8ecmV",
            "G7i3OPcfNhBnAYpc",
        ],
    }
    if os.path.exists(config_path):
        with open(config_path) as f:
            cfg = json.load(f)
        for k in defaults:
            if k in cfg and cfg[k]:
                defaults[k] = cfg[k]
    return defaults

def _load_creds_from_db(mysql_cfg: dict):
    """从 MySQL credentials 表取最新一条域名/token（fetch_creds.py 写入的）。
    取不到（库/表不存在、连不上、表为空）返回 None，调用方回退 config.json。"""
    try:
        conn = pymysql.connect(
            host=mysql_cfg["host"], port=int(mysql_cfg.get("port", 3306)),
            user=mysql_cfg["user"], password=mysql_cfg.get("password", ""),
            database=mysql_cfg["database"], charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor, connect_timeout=5,
        )
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT api_base, frontend, media_base, access_token, jwt_token, captured_at "
                    "FROM credentials ORDER BY id DESC LIMIT 1"
                )
                return cur.fetchone()
        finally:
            conn.close()
    except Exception:
        return None


_cfg = _load_config()
MYSQL = _cfg["mysql"]
API_PATH = "/fast-endecode/main/request"
KEYS = _cfg["keys"]

# 域名/token 优先用 MySQL credentials 表里最新一条；取不到则回退 config.json
_creds = _load_creds_from_db(MYSQL)
if _creds and _creds.get("api_base"):
    API_BASE = _creds["api_base"] or _cfg["api_base"]
    FRONTEND = _creds["frontend"] or _cfg["frontend"]
    MEDIA_BASE = _creds.get("media_base") or _cfg["media_base"]
    ACCESS_TOKEN = _creds["access_token"] or _cfg["access_token"]
    JWT_TOKEN = _creds["jwt_token"] or _cfg["jwt_token"]
    CREDS_SOURCE = "MySQL credentials 表 @ " + str(_creds.get("captured_at", ""))[:19]
else:
    API_BASE = _cfg["api_base"]
    FRONTEND = _cfg["frontend"]
    MEDIA_BASE = _cfg["media_base"]
    ACCESS_TOKEN = _cfg["access_token"]
    JWT_TOKEN = _cfg["jwt_token"]
    CREDS_SOURCE = "config.json（DB 无凭证，已回退）"

PAGE_SIZE = 500   # 接口实测单页上限 500


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


SCHEMA = [
    """CREATE TABLE IF NOT EXISTS videos (
        id BIGINT PRIMARY KEY,
        -- 列表字段
        vodId VARCHAR(64),
        title TEXT,
        duration INT,
        quality VARCHAR(32),
        vodPic TEXT,
        gif TEXT,
        preview TEXT,
        author VARCHAR(255),
        authorId BIGINT,
        authorAvatar TEXT,
        fansNum INT,
        readNumber INT,
        likeNumber INT,
        comments INT,
        tags TEXT,
        `groups` TEXT,
        themes TEXT,
        createTime BIGINT,
        vodTime BIGINT,
        crawled_at VARCHAR(32),
        -- 详情字段
        newAddr TEXT,
        mp4 TEXT,
        playUrls TEXT,
        groupNames TEXT,
        statistics TEXT,
        authorDetail TEXT,
        downloaded INT DEFAULT 0,
        download_path TEXT,
        detail_at VARCHAR(32),
        INDEX idx_videos_tags (tags(100)),
        INDEX idx_videos_authorId (authorId),
        INDEX idx_videos_crawled (crawled_at),
        INDEX idx_videos_downloaded (downloaded),
        INDEX idx_videos_detail (detail_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS progress (
        tag VARCHAR(64) PRIMARY KEY,
        page INT,
        total INT,
        collected INT,
        updated_at VARCHAR(32)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
]

# videos 表列定义（列表字段 + 详情字段，合成一张表）
_LIST_COLS = [
    "id", "vodId", "title", "duration", "quality", "vodPic", "gif", "preview",
    "author", "authorId", "authorAvatar", "fansNum", "readNumber", "likeNumber",
    "comments", "tags", "`groups`", "themes", "createTime", "vodTime", "crawled_at",
]
_DETAIL_COLS = ["newAddr", "mp4", "playUrls", "groupNames", "statistics", "authorDetail", "detail_at"]
_ALL_COLS = _LIST_COLS + _DETAIL_COLS

# 列表写入：只填列表字段，已存在则跳过（不覆盖已抓到的详情/下载状态）
_LIST_SQL = (
    f"INSERT IGNORE INTO videos ({', '.join(_LIST_COLS)}) "
    f"VALUES ({', '.join(['%s'] * len(_LIST_COLS))})"
)
# 详情写入：填全部字段，已存在则更新列表+详情字段（downloaded/download_path 不动，保留下载状态）
_DETAIL_SQL = (
    f"INSERT INTO videos ({', '.join(_ALL_COLS)}) "
    f"VALUES ({', '.join(['%s'] * len(_ALL_COLS))}) "
    "ON DUPLICATE KEY UPDATE " + ", ".join(f"{c}=VALUES({c})" for c in (_LIST_COLS[1:] + _DETAIL_COLS))
)


class DB:
    def __init__(self, cfg=MYSQL):
        self.cfg = cfg
        self._local = threading.local()
        self._lock = threading.Lock()
        self._ensure_database()
        self._init_schema()

    def _ensure_database(self):
        """连接时不指定 database，先建库（不存在则创建）"""
        boot = pymysql.connect(
            host=self.cfg["host"], port=int(self.cfg.get("port", 3306)),
            user=self.cfg["user"], password=self.cfg.get("password", ""),
            charset="utf8mb4", autocommit=True,
        )
        try:
            boot.cursor().execute(
                f"CREATE DATABASE IF NOT EXISTS `{self.cfg['database']}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        finally:
            boot.close()

    @property
    def conn(self):
        if not hasattr(self._local, "conn") or self._local.conn is None:
            self._local.conn = pymysql.connect(
                host=self.cfg["host"], port=int(self.cfg.get("port", 3306)),
                user=self.cfg["user"], password=self.cfg.get("password", ""),
                database=self.cfg["database"], charset="utf8mb4",
                cursorclass=pymysql.cursors.DictCursor, autocommit=True,
            )
        # 长时间运行防止 "MySQL server has gone away"
        self._local.conn.ping(reconnect=True)
        return self._local.conn

    def query(self, sql: str, params=()) -> list:
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()

    def execute(self, sql: str, params=()):
        with self._lock:
            with self.conn.cursor() as cur:
                cur.execute(sql, params)

    def executemany(self, sql: str, rows: list) -> int:
        """批量写入，一次往返 + 一次提交，返回受影响行数。"""
        if not rows:
            return 0
        with self._lock:
            with self.conn.cursor() as cur:
                cur.executemany(sql, rows)
                return cur.rowcount

    def _init_schema(self):
        for ddl in SCHEMA:
            self.execute(ddl)

    # --- 列表字段（来自搜索 item 或详情 vod，结构一致）---
    @staticmethod
    def _list_row(item: dict) -> tuple:
        a = item.get("authors", {}) or {}
        return (
            item.get("id"), item.get("vodId"), item.get("title"),
            item.get("vodDuration"), item.get("vodVersion"), item.get("vodPic"),
            item.get("gif"), item.get("preview"),
            a.get("nickName"), a.get("id"), a.get("avatar"), a.get("fansNum"),
            item.get("readNumber"), item.get("likeNumber"), item.get("comments"),
            json.dumps(item.get("tags"), ensure_ascii=False),
            json.dumps(item.get("groups")), json.dumps(item.get("themes")),
            item.get("createTime"), item.get("vodTime"), datetime.now().isoformat(),
        )

    @staticmethod
    def _detail_part(data: dict):
        """详情响应里的详情字段（7 列）；无 vod.id 返回 None（已删除/空洞）。"""
        vod = (data.get("result") or {}).get("vod") or {}
        if not vod.get("id"):
            return None
        author = (data.get("result") or {}).get("author") or {}
        stats = (data.get("result") or {}).get("statistics") or {}
        group_names = vod.get("group_names", []) or []
        play_urls = vod.get("vodFullPlayUrl", [])
        if isinstance(play_urls, str):
            play_urls = [play_urls]
        elif play_urls is None:
            play_urls = []
        play_urls_full = [{"addr": u.get("addr"), "type": u.get("type"),
                           "duration": u.get("duration"), "size": u.get("size")} for u in play_urls]
        return (
            vod.get("newAddr"), vod.get("mp4"),
            json.dumps(play_urls_full, ensure_ascii=False),
            json.dumps([{"id": g["id"], "name": g.get("groupName"), "desc": g.get("description")}
                        for g in group_names], ensure_ascii=False),
            json.dumps(stats, ensure_ascii=False),
            json.dumps({
                "nickName": author.get("nickName"), "avatar": author.get("avatar"),
                "introduce": author.get("introduce"), "fansNum": author.get("fansNum"),
            }, ensure_ascii=False),
            datetime.now().isoformat(),
        )

    @classmethod
    def _full_row(cls, data: dict):
        """详情响应 -> videos 全字段行（列表21 + 详情7）；无 vod.id 返回 None。"""
        det = cls._detail_part(data)
        if det is None:
            return None
        vod = (data.get("result") or {}).get("vod") or {}
        return cls._list_row(vod) + det

    # --- 列表写入（INSERT IGNORE，不覆盖已有详情）---
    def item_exists(self, iid: int) -> bool:
        return bool(self.query("SELECT 1 FROM videos WHERE id=%s", (iid,)))

    def insert_item(self, item: dict):
        self.execute(_LIST_SQL, self._list_row(item))

    def insert_items_batch(self, items: list) -> int:
        """批量写列表字段，返回实际新增行数（INSERT IGNORE 去重）。"""
        rows = [self._list_row(it) for it in items if it.get("id") is not None]
        return self.executemany(_LIST_SQL, rows)

    def count_items(self) -> int:
        return self.query("SELECT COUNT(*) AS n FROM videos")[0]["n"]

    # --- 详情写入（INSERT ... ON DUPLICATE KEY UPDATE，补全详情字段）---
    def detail_exists(self, iid: int) -> bool:
        return bool(self.query("SELECT 1 FROM videos WHERE id=%s AND detail_at IS NOT NULL", (iid,)))

    def insert_detail(self, data: dict):
        row = self._full_row(data)
        if row:
            self.execute(_DETAIL_SQL, row)

    def insert_details_batch(self, data_list: list) -> int:
        rows = [r for r in (self._full_row(d) for d in data_list) if r]
        return self.executemany(_DETAIL_SQL, rows)

    def count_details(self) -> int:
        return self.query("SELECT COUNT(*) AS n FROM videos WHERE detail_at IS NOT NULL")[0]["n"]

    def count_downloaded(self) -> int:
        return self.query("SELECT COUNT(*) AS n FROM videos WHERE downloaded=1")[0]["n"]

    def mark_downloaded(self, iid: int, path: str):
        self.execute("UPDATE videos SET downloaded=1, download_path=%s WHERE id=%s", (path, iid))

    def get_undownloaded(self, limit=None) -> list:
        sql = """
            SELECT id, title, playUrls FROM videos
            WHERE downloaded = 0 AND playUrls IS NOT NULL
        """
        if limit:
            sql += f" LIMIT {int(limit)}"
        return self.query(sql)

    # --- progress ---
    def get_progress(self, tag: str) -> dict:
        rows = self.query("SELECT * FROM progress WHERE tag=%s", (tag,))
        return dict(rows[0]) if rows else {"tag": tag, "page": 0, "total": 0, "collected": 0}

    def set_progress(self, tag: str, page: int, total: int, collected: int):
        self.execute("""
            REPLACE INTO progress (tag, page, total, collected, updated_at)
            VALUES (%s, %s, %s, %s, %s)
        """, (tag, page, total, collected, datetime.now().isoformat()))

    def close(self):
        if hasattr(self._local, "conn") and self._local.conn is not None:
            self._local.conn.close()
            self._local.conn = None


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
        new_count = db.insert_items_batch(items)   # 整页一次写入，返回实际新增
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
        new_count = db.insert_items_batch(items)   # 整页一次写入，返回实际新增
        collected = db.count_items()
        db.set_progress(tag, page, total, collected)
        print(f"[全站] 第{page}页 新增{new_count}条 累计{collected}/{total} 进度{collected*100//max(total,1)}%")
        if len(items) < PAGE_SIZE:
            break
        page += 1
        time.sleep(0.5)
    print(f"[全站] 完成! 共 {db.count_items()} 条")


def get_max_id() -> int:
    """当前站点最大视频 id（搜索第1页第1条，按时间倒序）。"""
    r = api_call("cms/vod/search", method=2, params={"wd": "", "page": 1, "pageSize": 1})
    return int(r["data"][0]["id"])


def _run_detail_jobs(db: DB, ids: list, workers: int, batch: int,
                     write_items: bool, label: str):
    """并发抓 ids 的详情，批量写库（网络并发 / DB 写在主线程串行批量）。
    write_items=True 时同时写 items 表（回填用）。
    返回 (ok 成功, gap 空洞即已删除id, err 请求失败)。"""
    total = len(ids)
    done = ok = gap = err = 0
    start = time.time()
    det_buf, item_buf = [], []

    def fetch(vid):
        try:
            return api_call(f"cms/vod/detail/{vid}", method=1)
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for data in pool.map(fetch, ids):     # 顺序产出，但已并发调度
            done += 1
            if data is None:
                err += 1
            else:
                vod = (data.get("result") or {}).get("vod") or {}
                if vod.get("id"):
                    ok += 1
                    det_buf.append(data)
                    if write_items:
                        item_buf.append(vod)
                else:
                    gap += 1
            if len(det_buf) >= batch:
                if write_items:
                    db.insert_items_batch(item_buf); item_buf = []
                db.insert_details_batch(det_buf); det_buf = []
            if done % 100 == 0 or done == total:
                el = time.time() - start
                rate = done / el if el > 0 else 0
                eta = (total - done) / rate if rate > 0 else 0
                print(f"\r  [{label}] {done}/{total} {done*100//max(total,1)}% | "
                      f"ok{ok} 空洞{gap} err{err} | {rate:.1f}/s ETA{eta:.0f}s   ", end="", flush=True)
    # 写余量
    if write_items and item_buf:
        db.insert_items_batch(item_buf)
    if det_buf:
        db.insert_details_batch(det_buf)
    print()
    return ok, gap, err


def crawl_details(db: DB, tag: str = "__all__", workers: int = 5, batch: int = 200):
    """补爬详情：从 items 里挑还没详情的 id（天然排重）。"""
    rows = db.query("SELECT id FROM videos WHERE detail_at IS NULL")
    ids = [r["id"] for r in rows]
    if not ids:
        print("详情已全部爬取")
        return
    print(f"待爬详情: {len(ids)} 条, 并发{workers} 批量{batch}")
    ok, gap, err = _run_detail_jobs(db, ids, workers, batch, write_items=False, label="详情")
    print(f"详情完成! 成功{ok} 空洞{gap} 失败{err}, 共 {db.count_details()} 条")


def crawl_backfill(db: DB, workers: int = 5, batch: int = 200, chunk: int = 2000):
    """全量回填：从当前最大 id 往下枚举到 1，逐个取详情，同时写 items+details。
    - 排重：每块先剔除已抓过(已有 details)的 id，不重复请求
    - 断点续传：progress(__backfill__).page = 已回填到的最低 id
    """
    tag = "__backfill__"
    prog = db.get_progress(tag)
    if prog["page"]:
        next_id = prog["page"] - 1
        top = prog["total"] or next_id
    else:
        top = get_max_id()
        next_id = top
    if next_id < 1:
        print("[回填] 已完成（已到 id=1）")
        return
    print(f"[回填] 起始 maxId={top} | 从 id={next_id} 往下到 1 | 并发{workers} 批量{batch} 块{chunk}")

    g_ok = g_gap = g_err = g_skip = 0
    cur = next_id
    while cur >= 1:
        lo = max(1, cur - chunk + 1)
        # 排重：剔除该区间内已有详情的 id
        have = {r["id"] for r in db.query(
            "SELECT id FROM videos WHERE detail_at IS NOT NULL AND id BETWEEN %s AND %s", (lo, cur))}
        ids = [i for i in range(cur, lo - 1, -1) if i not in have]
        g_skip += (cur - lo + 1) - len(ids)
        if ids:
            ok, gap, err = _run_detail_jobs(db, ids, workers, batch, write_items=True,
                                            label=f"回填{lo}-{cur}")
            g_ok += ok; g_gap += gap; g_err += err
        db.set_progress(tag, lo, top, db.count_items())   # page=lo: 已回填到 lo
        print(f"[回填] {lo}-{cur} 完成 | 累计 ok{g_ok} 空洞{g_gap} 跳过{g_skip} err{g_err} "
              f"| items={db.count_items()}")
        cur = lo - 1
    print(f"[回填] 全部完成! ok{g_ok} 空洞{g_gap} 跳过{g_skip} err{g_err}")


def crawl_refresh(db: DB, workers: int = 5, batch: int = 200):
    """增量：抓比库中最大 id 更新的部分（新发布的视频，id 更大）。"""
    max_id = get_max_id()
    have = int(db.query("SELECT MAX(id) AS m FROM videos")[0]["m"] or 0)
    if have == 0:
        print("[增量] 库为空，请先跑 --backfill 做全量，再用 --refresh 增量")
        return
    if max_id <= have:
        print(f"[增量] 无新数据（库最大 id={have}, 站点 maxId={max_id}）")
        return
    gap = max_id - have
    if gap > 50000:
        print(f"[增量] 与站点差距过大（{gap} 个 id），这不是增量场景，请用 --backfill")
        return
    ids = list(range(have + 1, max_id + 1))
    print(f"[增量] 库最大 id={have} -> 站点 maxId={max_id} | 新增 {len(ids)} 个待抓")
    ok, gap, err = _run_detail_jobs(db, ids, workers, batch, write_items=True, label="增量")
    print(f"[增量] 完成! ok{ok} 空洞{gap} err{err} | items={db.count_items()}")


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


# 备用 API 域名列表（从 app.js 中提取，自动尝试）
FALLBACK_DOMAINS = [
    "https://api.1d1bzspqmi46l.xyz",
    "https://0ncp0kjmhys5dzj.xyz",
    "https://5z3ffttoao467bc.xyz",
    "https://6qdy0xioq0d39hm.xyz",
    "https://c6sukj22g0f6zg7.xyz",
    "https://co2rwy7os281b0e.xyz",
    "https://ep3wmwhk45uhmx9.xyz",
    "https://fezpo48v7eod18x.xyz",
    "https://gdkm8yb00u3rxdm.xyz",
    "https://k4byju9ljsh8aza.xyz",
    "https://k4irke3n6xcwdpc.xyz",
    "https://1cjvr80fr3f7.xyz",
    "https://93l9cunfmoc1.xyz",
    "https://6rl8a5qg0y7m.xyz",
    "https://p9nwmo3jdquv.xyz",
    "https://5idytkrilig6.xyz",
    "https://y9ao7140l9ui.xyz",
    "https://k5p0vw2fgfvc.xyz",
    "https://mr5qvcc0k438.xyz",
    "https://agnuprlu6fhz.xyz",
    "https://ak8ib5fyxtww.xyz",
]


def update_config():
    """更新 token/域名 到 config.json - 自动从 app.js 提取所有域名"""
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
    cfg = json.load(open(config_path)) if os.path.exists(config_path) else {}
    frontend = cfg.get("frontend", "https://rw345o29u6ivj.xyz")

    # 1. 从前端页面下载 app.js 提取所有域名
    print(f"从 {frontend} 提取域名列表...")
    domains = set()
    try:
        html = requests.get(f"{frontend}/enter", timeout=10,
                            headers={"user-agent": "Mozilla/5.0"}).text
        js_url = re.search(r'src="([^"]*app[^"]*\.js)"', html)
        if js_url:
            js_full = js_url.group(1)
            if not js_full.startswith("http"):
                js_full = frontend + "/" + js_full.lstrip("./")
            print(f"  下载 app.js: {js_full}")
            js_code = requests.get(js_full, timeout=15).text
            domains.update(re.findall(r'https?://[a-zA-Z0-9.-]+\.(?:xyz|top)', js_code))
    except Exception as e:
        print(f"  提取失败: {e}")

    # 2. 加上 api.子域名变体
    api_variants = set()
    for d in list(domains):
        host = re.sub(r'https?://', '', d)
        api_variants.add(f"https://api.{host}")
        # 也去掉前缀子域名试
        if not host.startswith('api.'):
            api_variants.add(f"https://api.{host}")
    domains.update(api_variants)

    print(f"  共 {len(domains)} 个待测域名")

    # 3. 逐个测试
    found = False
    for domain in sorted(domains):
        print(f"  检测: {domain} ...", end=" ", flush=True)
        try:
            ts = int(time.time() * 1000)
            key = KEYS[ts % 10]
            bp = json.dumps({"method": 1, "params": {}, "uri": "app/jwt-token"}, separators=(",", ":"))
            c = AES.new(key.encode(), AES.MODE_ECB)
            ed = base64.b64encode(c.encrypt(pad(bp.encode(), 16))).decode()
            resp = requests.post(
                f"{domain}/fast-endecode/main/request",
                json={"data": ed, "time": ts}, timeout=10,
                headers={"accept": "application/json", "origin": frontend,
                         "user-agent": "Mozilla/5.0"})
            if resp.status_code == 200 and resp.json().get("data"):
                d = resp.json()
                rk = KEYS[d["time"] % 10]
                c2 = AES.new(rk.encode(), AES.MODE_ECB)
                inner = json.loads(unpad(c2.decrypt(base64.b64decode(d["data"])), 16))
                token = inner.get("accessToken") or inner.get("jwtToken")
                if token:
                    print("✅")
                    cfg["api_base"] = domain
                    cfg["jwt_token"] = token
                    cfg["access_token"] = inner.get("accessToken", "")
                    found = True
                    break
            print(f"HTTP {resp.status_code}")
        except requests.Timeout:
            print("超时")
        except Exception as e:
            print(f"失败 ({str(e)[:50]})")

    if found:
        with open(config_path, "w") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        print(f"\n已更新 config.json:")
        print(f"  api_base:  {cfg['api_base']}")
        print(f"  jwt_token: {cfg['jwt_token'][:50]}...")
    else:
        print("\n所有域名均不可用，请检查网络或手动更新 config.json")


def parse_curl_and_update(curl_cmd: str):
    """从浏览器复制的 curl 命令中提取 token/域名，更新 config.json"""
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
    cfg = json.load(open(config_path)) if os.path.exists(config_path) else {}

    # 提取 URL 中的域名
    url_match = re.search(r"(https?://[a-zA-Z0-9.-]+\.[a-z]+)/", curl_cmd.replace("\\", ""))
    if url_match:
        domain = url_match.group(1)
        # 如果不是 api. 开头，只当 frontend 用
        if "api." not in domain:
            cfg["frontend"] = domain
            print(f"frontend: {domain}")
        else:
            cfg["api_base"] = domain
            print(f"api_base: {domain}")

    # 提取 header 值
    for field, key in [("accesstoken", "access_token"), ("jwttoken", "jwt_token"), ("origin", "frontend")]:
        m = re.search(rf"-H\s+'?{field}:\s*['\"]?(\S+?)['\"]?\s*$", curl_cmd, re.I | re.M) or \
           re.search(rf"{field}:\s*['\"]?(\S+?)['\"]?", curl_cmd, re.I)
        if m:
            val = m.group(1).rstrip("'\"")
            if key == "frontend":
                val = val.rstrip("/")
            cfg[key] = val
            print(f"{key}: {val[:50]}...")

    # 尝试提取 origin 对应的前端域名（去掉路径）
    if cfg.get("frontend"):
        cfg["frontend"] = re.sub(r'/.*', '', cfg["frontend"])

    with open(config_path, "w") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f"\nconfig.json 已更新")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("-t", "--tag", help="标签名")
    parser.add_argument("-p", "--pages", type=int, default=50, help="最大页数")
    parser.add_argument("-d", "--detail-id", help="爬取单个详情")
    parser.add_argument("--detail", action="store_true", help="爬完列表后继续爬详情")
    parser.add_argument("--download", action="store_true", help="下载未下载的视频")
    parser.add_argument("--all", action="store_true", help="全站抓取（列表，仅最新1万条）")
    parser.add_argument("--backfill", action="store_true", help="全量回填：枚举 id 从大到小抓全站历史")
    parser.add_argument("--refresh", action="store_true", help="增量：抓比库中最大 id 更新的新视频")
    parser.add_argument("-b", "--batch", type=int, default=200, help="批量写入条数")
    parser.add_argument("-w", "--workers", type=int, default=5, help="详情/下载并发数")
    parser.add_argument("--update", action="store_true", help="更新 token/域名 到 config.json")
    parser.add_argument("--from-curl", type=str, help="从浏览器 curl 命令提取 token 并更新 config")
    args = parser.parse_args()

    db = DB()

    try:
        if args.from_curl:
            parse_curl_and_update(args.from_curl)
            return

        if args.update:
            update_config()
            return

        if args.detail_id:
            data = api_call(f"cms/vod/detail/{args.detail_id}", method=1)
            db.insert_detail(data)
            print(f"详情已保存: {args.detail_id}")
            return

        if args.download:
            download_videos(db, args.workers)
            return

        if args.backfill:
            crawl_backfill(db, args.workers, args.batch)
            return

        if args.refresh:
            crawl_refresh(db, args.workers, args.batch)
            return

        if args.detail and not args.tag and not args.all:
            crawl_details(db, "__all__", args.workers, args.batch)
            return

        if args.all:
            crawl_all(db)
            if args.detail:
                crawl_details(db, "__all__", args.workers, args.batch)
            return

        if args.tag:
            crawl_list(db, args.tag, args.pages)
            if args.detail:
                crawl_details(db, args.tag, args.workers, args.batch)
        else:
            print("=" * 50)
            print("主播视频爬虫 - 两阶段模式")
            print("=" * 50)
            print(f"数据库: MySQL {MYSQL['host']}:{MYSQL['port']}/{MYSQL['database']}")
            print(f"域名/token 来源: {CREDS_SOURCE}")
            print(f"  api_base: {API_BASE}")
            print(f"已收集: {db.count_items()} 列表项, {db.count_details()} 详情, {db.count_downloaded()} 已下载")
            print()
            print("阶段1 - 爬取信息:")
            print("  python crawler.py --backfill                    # 全量回填(枚举id，抓全站114万历史)")
            print("  python crawler.py --refresh                     # 增量(只抓比库里更新的新视频)")
            print("  python crawler.py --all                         # 全站列表(仅最新1万条)")
            print("  python crawler.py --all --detail                # 全站列表+详情")
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
