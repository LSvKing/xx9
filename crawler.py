#!/usr/bin/env python3
"""
主播视频爬虫 - MySQL 版本

安装:
	uv sync

运行:
	uv run python crawler.py --backfill               # 全量回填（枚举 id 抓全站历史）
	uv run python crawler.py --backfill --shard 3/10  # 分片回填（多机并行）
	uv run python crawler.py --refresh                # 增量（只抓比库里更新的）
	uv run python crawler.py --detail                 # 独立补爬详情（从DB取未抓详情的）
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
from datetime import datetime, timezone


def _now_iso() -> str:
    """统一用 UTC 写时间戳，避免不同时区的机器写进 crawled_at/detail_at 的
    本地时间无法比较/排序（带 +00:00 偏移，明确是 UTC）。"""
    return datetime.now(timezone.utc).isoformat()

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
        "media_base": "https://rr.rxjhwl.com",        # m3u8 播放线路前缀
        "pic_base": "https://qv1tx.cloudworki.com",   # 图片前缀
        "mp4_base": "https://mp4.almusiclub.com",      # mp4 前缀
        "access_token": "",
        "jwt_token": "",
        "web_password": "xx9",                          # 前端登录密码
        "web_secret": "change-this-secret",             # cookie 签名密钥
        "proxy": {},                                    # 青果住宅代理池(可选)：{query_url, get_url, auth}

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
                    "SELECT api_base, frontend, media_base, pic_base, mp4_base, "
                    "access_token, jwt_token, captured_at "
                    "FROM credentials ORDER BY id DESC LIMIT 1"
                )
                return cur.fetchone()
        finally:
            conn.close()
    except Exception:
        return None


def _db_conn(mysql_cfg: dict):
    return pymysql.connect(
        host=mysql_cfg["host"], port=int(mysql_cfg.get("port", 3306)),
        user=mysql_cfg["user"], password=mysql_cfg.get("password", ""),
        database=mysql_cfg["database"], charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor, connect_timeout=5,
    )


def _load_settings_from_db(mysql_cfg: dict) -> dict:
    """从 MySQL settings 表读共享配置(k->v, v 为 JSON)。无表/连不上返回 {}。
    让 web_password / keys 等集中存库，多机共享，config.json 只留 mysql 连接。"""
    try:
        conn = _db_conn(mysql_cfg)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT k, v FROM settings")
                out = {}
                for r in cur.fetchall():
                    try:
                        out[r["k"]] = json.loads(r["v"])
                    except Exception:
                        out[r["k"]] = r["v"]
                return out
        finally:
            conn.close()
    except Exception:
        return {}


def _load_proxy_from_db(mysql_cfg: dict):
    """从 MySQL proxy 表读启用的代理配置（最新一条 enabled=1）。无则返回 None。"""
    try:
        conn = _db_conn(mysql_cfg)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT get_url, query_url, auth FROM proxy "
                            "WHERE enabled=1 ORDER BY id DESC LIMIT 1")
                return cur.fetchone()
        finally:
            conn.close()
    except Exception:
        return None


def get_setting(key: str, default=None, mysql_cfg: dict = None):
    """从 settings 表读单个键（v 反 JSON）。无表/连不上/无键返回 default。
    写入用 INSERT ... ON DUPLICATE KEY UPDATE，settings 表已持久化的字典
    （如固定不变的 theme_names 专题名）由此读取，首页据此渲染。"""
    try:
        conn = _db_conn(mysql_cfg or MYSQL)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT v FROM settings WHERE k=%s", (key,))
                row = cur.fetchone()
                if row:
                    try:
                        return json.loads(row["v"])
                    except Exception:
                        return row["v"]
        finally:
            conn.close()
    except Exception:
        pass
    return default


_cfg = _load_config()
MYSQL = _cfg["mysql"]
API_PATH = "/fast-endecode/main/request"

# 共享配置(keys/密码)优先从 MySQL settings 表读，覆盖 config.json
_settings = _load_settings_from_db(MYSQL)
for _k in ("keys", "web_password", "web_secret"):
    if _settings.get(_k):
        _cfg[_k] = _settings[_k]
# 代理配置优先从 MySQL proxy 表读，覆盖 config.json
_proxy_db = _load_proxy_from_db(MYSQL)
if _proxy_db and _proxy_db.get("query_url"):
    _cfg["proxy"] = _proxy_db

KEYS = _cfg["keys"]

# 域名/token 优先用 MySQL credentials 表里最新一条；取不到则回退 config.json
_creds = _load_creds_from_db(MYSQL)
if _creds and _creds.get("api_base"):
    API_BASE = _creds["api_base"] or _cfg["api_base"]
    FRONTEND = _creds["frontend"] or _cfg["frontend"]
    MEDIA_BASE = _creds.get("media_base") or _cfg["media_base"]
    PIC_BASE = _creds.get("pic_base") or _cfg["pic_base"]
    MP4_BASE = _creds.get("mp4_base") or _cfg["mp4_base"]
    ACCESS_TOKEN = _creds["access_token"] or _cfg["access_token"]
    JWT_TOKEN = _creds["jwt_token"] or _cfg["jwt_token"]
    CREDS_SOURCE = "MySQL credentials 表 @ " + str(_creds.get("captured_at", ""))[:19]
else:
    API_BASE = _cfg["api_base"]
    FRONTEND = _cfg["frontend"]
    MEDIA_BASE = _cfg["media_base"]
    PIC_BASE = _cfg["pic_base"]
    MP4_BASE = _cfg["mp4_base"]
    ACCESS_TOKEN = _cfg["access_token"]
    JWT_TOKEN = _cfg["jwt_token"]
    CREDS_SOURCE = "config.json（DB 无凭证，已回退）"


class _ProxyPool:
    """青果住宅代理池：query 拿当前在用 IP 并轮换；池空时尝试 get 提取（每天0点通道空出后自愈）。"""
    def __init__(self, cfg: dict):
        self.query_url = cfg.get("query_url")
        self.get_url = cfg.get("get_url")
        self.auth = cfg.get("auth", "")
        self.proxies = []
        self.t = 0.0
        self.i = 0
        self.lock = threading.Lock()

    def _servers(self):
        data = requests.get(self.query_url, timeout=10).json()
        return [d.get("server") for d in (data.get("data") or []) if d.get("server")]

    def _refresh(self):
        try:
            servers = self._servers()
            if not servers and self.get_url:        # 没在用 IP（如0点重置后），尝试提取
                for _ in range(3):
                    try:
                        requests.get(self.get_url, timeout=10)
                    except Exception:
                        pass
                servers = self._servers()
            self.proxies = [f"http://{self.auth}@{s}" for s in servers]
        except Exception:
            pass
        self.t = time.time()

    def get(self):
        with self.lock:
            if time.time() - self.t > 120 or not self.proxies:
                self._refresh()
            if not self.proxies:
                return None
            p = self.proxies[self.i % len(self.proxies)]
            self.i += 1
            return {"http": p, "https": p}


_PROXY = _ProxyPool(_cfg["proxy"]) if _cfg.get("proxy", {}).get("query_url") else None
USE_PROXY = False   # 是否走代理池；crawler 由 --proxy 控制，server 启动时按需打开


def aes_enc(key: str, plain: str) -> str:
    c = AES.new(key.encode(), AES.MODE_ECB)
    return base64.b64encode(c.encrypt(pad(plain.encode(), 16))).decode()


def aes_dec(key: str, b64: str) -> bytes:
    c = AES.new(key.encode(), AES.MODE_ECB)
    return unpad(c.decrypt(base64.b64decode(b64)), 16)


def aes_dec_auto(b64: str, hint: int = None) -> bytes:
    """解密响应：不信任服务端回传的 time（不同时区/时钟偏差会让 time%10 跟真正
    加密用的 key 对不上），直接在 10 把 key 里试，能 unpad 且像 JSON 的就是对的。
    hint 给个优先尝试的索引（一般是 data["time"]%10），命中则省去爆破。"""
    raw = base64.b64decode(b64)
    order = list(range(len(KEYS)))
    if hint is not None:
        order = [hint % len(KEYS)] + [i for i in order if i != hint % len(KEYS)]
    last = None
    for i in order:
        try:
            out = unpad(AES.new(KEYS[i].encode(), AES.MODE_ECB).decrypt(raw), 16)
            s = out.lstrip()[:1]
            if s in (b"{", b"["):          # 合法 JSON 起手，确认是对的 key
                return out
            last = out                      # 能 unpad 但不像 JSON，先留着兜底
        except Exception:
            continue
    if last is not None:
        return last
    raise ValueError("无法用任何 key 解密响应（疑似 WAF 拦截返回的乱码）")


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
    proxies = _PROXY.get() if (_PROXY and USE_PROXY) else None
    resp = requests.post(f"{API_BASE}{API_PATH}", headers=headers, json={"data": ed, "time": ts},
                         timeout=30, proxies=proxies)
    if resp.status_code != 200:
        raise Exception(f"HTTP {resp.status_code}")
    data = resp.json()
    if "data" not in data or "time" not in data:
        return data
    return json.loads(aes_dec_auto(data["data"], hint=int(data.get("time", 0))))


def _join(base: str, path: str) -> str:
    if not path or path.startswith("http"):
        return path or ""
    return base + (path if path.startswith("/") else "/" + path)


def image_url(path: str) -> str:
    """图片(封面/动图/预览/头像) 拼图片 CDN 前缀"""
    return _join(PIC_BASE, path)


# 兼容旧名：图片前缀（原 full_url 误用 FRONTEND，已修正为 PIC_BASE）
full_url = image_url


def video_url(path: str) -> str:
    """视频 m3u8 拼播放线路前缀"""
    return _join(MEDIA_BASE, path)


def mp4_url(path: str) -> str:
    """mp4 拼 mp4 CDN 前缀"""
    return _join(MP4_BASE, path)


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
        -- 详情字段（不存播放/下载地址，签名会过期，播放时实时取）
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
    """CREATE TABLE IF NOT EXISTS settings (
        k VARCHAR(64) PRIMARY KEY,
        v TEXT
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS proxy (
        id INT PRIMARY KEY AUTO_INCREMENT,
        name VARCHAR(64),
        get_url VARCHAR(255),
        query_url VARCHAR(255),
        auth VARCHAR(255),
        enabled INT DEFAULT 1,
        created_at VARCHAR(32)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci""",
]

# videos 表列定义（列表字段 + 详情字段，合成一张表）
_LIST_COLS = [
    "id", "vodId", "title", "duration", "quality", "vodPic", "gif", "preview",
    "author", "authorId", "authorAvatar", "fansNum", "readNumber", "likeNumber",
    "comments", "tags", "`groups`", "themes", "createTime", "vodTime", "crawled_at",
]
_DETAIL_COLS = ["groupNames", "statistics", "authorDetail", "detail_at"]
_ALL_COLS = _LIST_COLS + _DETAIL_COLS

# 写入：一次详情即一整行（列表+详情字段全有）。INSERT 全字段，已存在则更新
# 列表+详情字段，但 downloaded/download_path 不动，保留下载状态。
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

    def _new_conn(self):
        return pymysql.connect(
            host=self.cfg["host"], port=int(self.cfg.get("port", 3306)),
            user=self.cfg["user"], password=self.cfg.get("password", ""),
            database=self.cfg["database"], charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor, autocommit=True,
        )

    @property
    def conn(self):
        c = getattr(self._local, "conn", None)
        if c is None:
            c = self._local.conn = self._new_conn()
            return c
        try:
            c.ping(reconnect=False)          # 探活；连接死了就重建（避开已弃用的 reconnect=True）
        except Exception:
            try:
                c.close()
            except Exception:
                pass
            c = self._local.conn = self._new_conn()
        return c

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
            item.get("createTime"), item.get("vodTime"), _now_iso(),
        )

    @staticmethod
    def _detail_part(data: dict):
        """详情里的稳定元数据；无 vod.id 返回 None（已删除/空洞）。
        不存播放/下载地址（vodFullPlayUrl/mp4/newAddr）——它们带时间签名会过期，
        播放时实时取（见 server.py /api/video）。"""
        vod = (data.get("result") or {}).get("vod") or {}
        if not vod.get("id"):
            return None
        author = (data.get("result") or {}).get("author") or {}
        stats = (data.get("result") or {}).get("statistics") or {}
        group_names = vod.get("group_names", []) or []
        return (
            json.dumps([{"id": g["id"], "name": g.get("groupName"), "desc": g.get("description")}
                        for g in group_names], ensure_ascii=False),
            json.dumps(stats, ensure_ascii=False),
            json.dumps({
                "nickName": author.get("nickName"), "avatar": author.get("avatar"),
                "introduce": author.get("introduce"), "fansNum": author.get("fansNum"),
            }, ensure_ascii=False),
            _now_iso(),
        )

    @classmethod
    def _full_row(cls, data: dict):
        """详情响应 -> videos 全字段行（列表21 + 详情7）；无 vod.id 返回 None。"""
        det = cls._detail_part(data)
        if det is None:
            return None
        vod = (data.get("result") or {}).get("vod") or {}
        return cls._list_row(vod) + det

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
            SELECT id, title FROM videos
            WHERE downloaded = 0 AND detail_at IS NOT NULL
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
        """, (tag, page, total, collected, _now_iso()))

    def close(self):
        if hasattr(self._local, "conn") and self._local.conn is not None:
            self._local.conn.close()
            self._local.conn = None


def get_max_id() -> int:
    """当前站点最大视频 id（搜索第1页第1条，按时间倒序）。"""
    r = api_call("cms/vod/search", method=2, params={"wd": "", "page": 1, "pageSize": 1})
    return int(r["data"][0]["id"])


def preflight(workers: int = 5, samples: int = 20, min_rate: float = None) -> bool:
    """开抓前连通性自检：先取 maxId（测搜索接口+token），再并发抓一批样本详情，
    统计成功率。FAT20001（已删除）也算连通成功；只有网络错/超时/非200算失败。
    成功率低于 min_rate 返回 False。走代理时住宅IP本身抖动+爬取有重试，阈值放宽到 40%。"""
    if min_rate is None:
        min_rate = 0.4 if USE_PROXY else 0.7
    try:
        max_id = get_max_id()
    except Exception as e:
        print(f"[自检] ✗ 连搜索接口都失败: {e}")
        print("       → token 失效或代理/网络不通。先跑 fetch_creds 刷 token，或检查代理。")
        return False
    ids = [max_id - i for i in range(samples)]

    def one(v):
        try:
            api_call(f"cms/vod/detail/{v}", method=1)   # 拿到响应即连通（含 FAT20001）
            return True
        except Exception:
            return False

    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(one, ids))
    ok = sum(1 for r in results if r)
    rate = ok / len(ids)
    tip = "（代理模式，爬取有重试，阈值已放宽）" if USE_PROXY else ""
    print(f"[自检] maxId={max_id} | 并发{workers} 样本{len(ids)} 成功{ok} 成功率{rate*100:.0f}% {tip}")
    if rate < min_rate:
        print(f"[自检] ⚠️ 成功率过低（<{int(min_rate*100)}%）。" + ("代理/住宅IP不稳或被限。" if USE_PROXY else "本机出口IP可能被限速/封禁。"))
        print("       建议: ① 多买几个青果IP  ② 降并发 -w 2  ③ 等会儿恢复  ④ --no-check 跳过自检强行跑")
        return False
    return True


def _err_reason(e) -> str:
    """把异常归成可读的失败原因。"""
    n = type(e).__name__
    s = str(e)
    if "Padding" in s or "padding" in s:
        return "WAF拦截/解密失败"
    if "Timeout" in n or "timed out" in s.lower():
        return "超时"
    if "403" in s:
        return "403限速"
    if "ProxyError" in n:
        return "代理错误"
    if "SSL" in n or "SSL" in s:
        return "SSL错误"
    if "Connection" in n or "Connection" in s:
        return "连接失败"
    if s.startswith("HTTP "):
        return s[:12]
    return n


def _fetch_detail(vid, retries: int = 4):
    """抓单条详情，失败重试 + 退避。成功返回 dict；彻底失败返回原因字符串。"""
    reason = "未知"
    for att in range(retries):
        try:
            return api_call(f"cms/vod/detail/{vid}", method=1)   # dict = 成功
        except Exception as e:
            reason = _err_reason(e)
            if att < retries - 1:
                time.sleep(0.5 * (att + 1))   # 0.5/1/1.5s 退避
    return reason   # str = 失败原因


def _run_detail_jobs(db: DB, ids: list, workers: int, batch: int, label: str, verbose: bool = False):
    """并发抓 ids 的详情，批量写库（网络并发 / DB 写在主线程串行批量）。
    一条详情即一整行 videos（列表字段+详情字段），insert_details_batch 一次写全。
    单条失败会重试，整轮结束后对仍失败的 id 再整体重试一轮。
    verbose=True 时逐条打印失败 id+原因。
    返回 (ok 成功, gap 空洞即已删除id, err 最终仍失败)。"""
    total = len(ids)
    done = ok = gap = 0
    failed = []
    reasons = {}
    start = time.time()
    det_buf = []

    def flush():
        nonlocal det_buf
        if det_buf:
            db.insert_details_batch(det_buf); det_buf = []

    def consume(vid, data, show=True):
        nonlocal done, ok, gap
        done += 1
        if isinstance(data, str):                 # 失败，data 是原因字符串
            failed.append(vid)
            reasons[data] = reasons.get(data, 0) + 1
            if verbose:
                print(f"\n  ✗ id={vid} 失败: {data}", flush=True)
        elif (data.get("result") or {}).get("vod", {}).get("id"):
            ok += 1
            det_buf.append(data)
        else:
            gap += 1
        if len(det_buf) >= batch:
            flush()
        if show and (done % 100 == 0 or done == total):
            el = time.time() - start
            rate = done / el if el > 0 else 0
            eta = (total - done) / rate if rate > 0 else 0
            print(f"\r  [{label}] {done}/{total} {done*100//max(total,1)}% | "
                  f"ok{ok} 空洞{gap} 失败{len(failed)} | {rate:.1f}/s ETA{eta:.0f}s   ", end="", flush=True)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        for vid, data in zip(ids, pool.map(_fetch_detail, ids)):
            consume(vid, data)
    flush()

    # 收尾：对仍失败的 id 再整体重试一轮（多为限速/网络抖动，缓一下多半能成）
    if failed:
        retry_ids = failed[:]
        failed.clear(); reasons.clear()           # 重试后的结果才是最终失败
        print(f"\n  [{label}] {len(retry_ids)} 个失败，缓 5s 后重试一轮 ...")
        time.sleep(5)
        with ThreadPoolExecutor(max_workers=max(2, workers // 2)) as pool:
            for vid, data in zip(retry_ids, pool.map(_fetch_detail, retry_ids)):
                consume(vid, data, show=False)
        flush()

    print()
    if failed:                                    # 失败原因汇总（按数量降序）
        rb = ", ".join(f"{k}×{v}" for k, v in sorted(reasons.items(), key=lambda x: -x[1]))
        print(f"  [{label}] 仍失败 {len(failed)} 个 | 原因: {rb}")
    return ok, gap, len(failed)


def crawl_details(db: DB, tag: str = "__all__", workers: int = 5, batch: int = 200, verbose: bool = False):
    """补爬详情：从 items 里挑还没详情的 id（天然排重）。"""
    rows = db.query("SELECT id FROM videos WHERE detail_at IS NULL")
    ids = [r["id"] for r in rows]
    if not ids:
        print("详情已全部爬取")
        return
    print(f"待爬详情: {len(ids)} 条, 并发{workers} 批量{batch}")
    ok, gap, err = _run_detail_jobs(db, ids, workers, batch, label="详情", verbose=verbose)
    print(f"详情完成! 成功{ok} 空洞{gap} 失败{err}, 共 {db.count_details()} 条")


def crawl_backfill(db: DB, workers: int = 5, batch: int = 200, chunk: int = 2000,
                   shard=None, verbose: bool = False):
    """全量回填：从库里已有的最大 id 往下枚举到 1，逐个取详情，写入 videos。
    - 起点：首跑从 DB 已有的最大 id 起（更大的是新数据，归 --refresh，backfill 不碰；
      也省掉问站点 maxId）；库空才回退站点 maxId。续传则从 progress.page 继续。
    - 排重：每块先剔除已抓过(detail_at 非空)的 id，不重复请求
    - 断点续传：progress.page = 已回填到的最低 id
    - 分片(shard=(k,N))：只抓 id%N==k 的，多机各跑一个通道并行，互不重复。
      每个通道独立进度行 __backfill_k/N__，独立续传；起点取该通道在库里的最大 id。
    """
    if shard is None:
        tag, lbl = "__backfill__", "回填"
        def mine(i): return True
    else:
        k, n = shard
        tag, lbl = f"__backfill_{k}/{n}__", f"回填{k}/{n}"
        def mine(i): return i % n == k

    prog = db.get_progress(tag)
    if prog["page"]:
        next_id = prog["page"] - 1
        top = prog["total"] or next_id
    else:
        # 首跑：从库里已有的最大 id 起（更大的是新数据，交给 --refresh，backfill 不碰），
        # 顺带省掉一次问站点的 API；库里该通道还没数据才回退站点 maxId。
        if shard is None:
            dbmax = db.query("SELECT MAX(id) AS m FROM videos")[0]["m"]
        else:
            dbmax = db.query("SELECT MAX(id) AS m FROM videos WHERE MOD(id,%s)=%s",
                             (shard[1], shard[0]))[0]["m"]
        top = int(dbmax) if dbmax else get_max_id()
        next_id = top
    if next_id < 1:
        print(f"[{lbl}] 已完成（已到 id=1）")
        return
    print(f"[{lbl}] 起始 maxId={top} | 从 id={next_id} 往下到 1 | 并发{workers} 批量{batch} 块{chunk}"
          + (f" | 分片 id%{shard[1]}=={shard[0]}" if shard else ""))

    g_ok = g_gap = g_err = g_skip = 0
    cur = next_id
    while cur >= 1:
        lo = max(1, cur - chunk + 1)
        cand = [i for i in range(cur, lo - 1, -1) if mine(i)]   # 本通道在该块的候选
        # 排重：剔除已抓过详情的 id
        have = {r["id"] for r in db.query(
            "SELECT id FROM videos WHERE detail_at IS NOT NULL AND id BETWEEN %s AND %s", (lo, cur))}
        ids = [i for i in cand if i not in have]
        g_skip += len(cand) - len(ids)
        if ids:
            ok, gap, err = _run_detail_jobs(db, ids, workers, batch, label=f"{lbl} {lo}-{cur}", verbose=verbose)
            g_ok += ok; g_gap += gap; g_err += err
        db.set_progress(tag, lo, top, db.count_items())   # page=lo: 已回填到 lo
        print(f"[{lbl}] {lo}-{cur} 完成 | 累计 ok{g_ok} 空洞{g_gap} 跳过{g_skip} err{g_err} "
              f"| videos={db.count_items()}")
        cur = lo - 1
    print(f"[{lbl}] 全部完成! ok{g_ok} 空洞{g_gap} 跳过{g_skip} err{g_err}")


def crawl_refresh(db: DB, workers: int = 5, batch: int = 200, verbose: bool = False):
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
    ok, gap, err = _run_detail_jobs(db, ids, workers, batch, label="增量", verbose=verbose)
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

        out = os.path.join(dl_dir, f"{vid}.mp4")
        if os.path.exists(out):
            db.mark_downloaded(vid, out)
            with lock:
                done_count[0] += 1
            return

        # 播放地址带签名会过期，下载时实时取新鲜的
        try:
            d = api_call(f"cms/vod/detail/{vid}", method=1)
            play = (d.get("result") or {}).get("vod", {}).get("vodFullPlayUrl") or []
            addr = next((p.get("addr") for p in play if p.get("addr")), None)
        except Exception:
            addr = None
        url = video_url(addr) if addr else None
        if not url:
            with lock:
                fail_count[0] += 1
            return

        with lock:
            done = done_count[0] + fail_count[0]
            print(f"  [{done+1}/{total}] {title} ...", end=" ", flush=True)

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
    parser.add_argument("-d", "--detail-id", help="爬取单个详情")
    parser.add_argument("--detail", action="store_true", help="补爬详情（从DB取未抓详情的id）")
    parser.add_argument("--download", action="store_true", help="下载未下载的视频")
    parser.add_argument("--backfill", action="store_true", help="全量回填：枚举 id 从大到小抓全站历史")
    parser.add_argument("--refresh", action="store_true", help="增量：抓比库中最大 id 更新的新视频")
    parser.add_argument("-b", "--batch", type=int, default=200, help="批量写入条数")
    parser.add_argument("-w", "--workers", type=int, default=5, help="详情/下载并发数")
    parser.add_argument("--no-check", action="store_true", help="跳过开抓前连通性自检")
    parser.add_argument("-v", "--verbose", action="store_true", help="逐条打印失败 id 和原因")
    parser.add_argument("--shard", type=str, help="回填分片，格式 k/N，如 3/10 只抓 id%%10==3（多机并行用）")
    parser.add_argument("--proxy", action="store_true", help="走 MySQL proxy 表里的住宅代理（绕 WAF，国内服务器抓取用）")
    args = parser.parse_args()

    global USE_PROXY
    if args.proxy:
        if not _PROXY:
            print("⚠️ proxy 表里没有启用的代理，--proxy 无效")
        else:
            USE_PROXY = True
            print("[代理] 已启用 MySQL proxy 表里的住宅代理")

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

        if args.backfill or args.refresh or args.detail:
            if not args.no_check and not preflight(args.workers):
                return
            if args.backfill:
                shard = None
                if args.shard:
                    k, n = (int(x) for x in args.shard.split("/"))
                    if not (0 <= k < n):
                        print(f"--shard 非法: k 必须在 0~{n-1}")
                        return
                    shard = (k, n)
                crawl_backfill(db, args.workers, args.batch, shard=shard, verbose=args.verbose)
            elif args.refresh:
                crawl_refresh(db, args.workers, args.batch, verbose=args.verbose)
            else:
                crawl_details(db, "__all__", args.workers, args.batch, verbose=args.verbose)
            return

        # 无参数：打印状态和用法菜单
        print("=" * 50)
        print("主播视频爬虫 - MySQL 版本")
        print("=" * 50)
        print(f"数据库: MySQL {MYSQL['host']}:{MYSQL['port']}/{MYSQL['database']}")
        print(f"域名/token 来源: {CREDS_SOURCE}")
        print(f"  api_base: {API_BASE}")
        print(f"已收集: {db.count_items()} 条视频, {db.count_details()} 有详情, {db.count_downloaded()} 已下载")
        print()
        print("阶段1 - 爬取信息:")
        print("  python crawler.py --backfill                    # 全量回填(枚举id，抓全站114万历史)")
        print("  python crawler.py --backfill --shard 3/10       # 分片回填(多机并行用)")
        print("  python crawler.py --refresh                     # 增量(只抓比库里更新的新视频)")
        print("  python crawler.py --detail                      # 独立补爬详情(从DB取未抓详情的)")
        print()
        print("阶段2 - 下载视频:")
        print("  python crawler.py --download                     # 下载所有未下载的")
    finally:
        db.close()


if __name__ == "__main__":
    main()
