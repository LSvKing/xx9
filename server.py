#!/usr/bin/env python3
"""
视频站后端 - FastAPI

一体化：JSON API（查 MySQL）+ 密码登录 + ffmpeg 下载 + 托管前端静态页。
视频不经过后端：浏览器 hls.js 直连 CDN 播放（CDN 开放 CORS、不需 Referer）。

运行:
    uv run uvicorn server:app --host 0.0.0.0 --port 8000
"""
import os
import json
import time
import threading
from datetime import datetime, timezone

from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

import crawler as c

if c._PROXY:                 # 有配置代理就让服务器 API 调用走代理（绕 WAF）
    c.USE_PROXY = True

WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
PASSWORD = c._cfg.get("web_password", "xx9")
SECRET = c._cfg.get("web_secret", "change-this-secret")

db = c.DB()
# 收藏 / 历史表（单用户，无需 user_id）
db.execute("""CREATE TABLE IF NOT EXISTS favorites (
    video_id BIGINT PRIMARY KEY, created_at VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
db.execute("""CREATE TABLE IF NOT EXISTS history (
    video_id BIGINT PRIMARY KEY, watched_at VARCHAR(32), position INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")

app = FastAPI(title="xx9")
app.add_middleware(SessionMiddleware, secret_key=SECRET, max_age=30 * 24 * 3600)


# ---- CDN 前缀（每 2 分钟实时从 config/query 拉，跟随域名轮换）----
# 图片优先 picBaseUrl（更稳）；playLines 偶尔会轮换出对浏览器不可用的死线（如 qv1tx 解析到 fake-ip 超时），故仅作兜底。视频 m3u8 用 h5_play_line（国线/海线，可切换）。
_pref = {"t": 0.0, "media": c.MEDIA_BASE, "pic": c.PIC_BASE, "lines": [], "cands": []}


def _first_line(s):
    try:
        arr = json.loads(s or "[]")
        return arr[0]["line"] if arr else None
    except Exception:
        return None


_pic_sample = {"p": None}     # 探测用的样本图 path（库里取一次）


def _probe_pic(cands):
    """真实公网(绕开 server 代理)逐条探测候选图片域名，选第一条能正常返回封面的；
    自动筛掉 playLines 偶发的死线（如 qv1tx 解析到 fake-ip、对浏览器超时）。"""
    if _pic_sample["p"] is None:
        try:
            r = db.query("SELECT vodPic FROM videos WHERE vodPic IS NOT NULL AND vodPic<>'' "
                         "ORDER BY createTime DESC LIMIT 1")
            _pic_sample["p"] = (r[0]["vodPic"] if r else "") or ""
        except Exception:
            _pic_sample["p"] = ""
    for base in cands:
        try:
            resp = c.requests.get(c._join(base, _pic_sample["p"]), timeout=3,
                                  proxies={"http": None, "https": None})   # 强制不走代理 = 公网视角
            if resp.status_code == 200 and resp.content:
                return base
        except Exception:
            continue
    return None     # 候选全死：返回 None，调用方保留原值，不硬切到死 base


def prefixes():
    if time.time() - _pref["t"] > 120:
        cands = None
        try:
            r = c.api_call("config/query", method=1,
                           params={"groupKey": "APP", "key": "picBaseUrl,playLines,h5_play_line"})
            conf = r.get("data") or r.get("result") or {}
            # 图片基址候选：picBaseUrl + playLines 全部
            cands = [conf["picBaseUrl"]] if conf.get("picBaseUrl") else []
            for x in json.loads(conf.get("playLines") or "[]"):
                ln = x.get("line")
                if ln and ln not in cands:
                    cands.append(ln)
            _pref["cands"] = cands        # 记下本次候选，接口挂时离线复探用
            # 视频线路（国线/海线，h5_play_line），按 line 去重
            hl = json.loads(conf.get("h5_play_line") or "[]")
            seen, lines = set(), []
            for x in hl:
                ln = x.get("line")
                if ln and ln not in seen:
                    seen.add(ln); lines.append({"name": x.get("name") or ln, "line": ln})
            if lines:
                _pref["lines"] = lines
                _pref["media"] = lines[0]["line"]
        except Exception:
            # 接口挂了：用上次成功记下的候选列表离线复探，别硬用一个可能已死的旧 base
            cands = list(_pref.get("cands") or [])
        # 无论接口通不通，都对手上的候选做公网验活、选第一条活的；
        # 把当前值和 config 兜底也并进去做候选，全死时 _probe_pic 返回 None → 保留原值
        for extra in (_pref["pic"], c.PIC_BASE):
            if extra and extra not in cands:
                cands.append(extra)
        _pref["pic"] = _probe_pic(cands) or _pref["pic"]
        _pref["t"] = time.time()
    return _pref


# 播放签名地址缓存：vid -> (addr, t)。签名约 1 小时有效。
#   <TTL(30min)：直接复用，不打接口；重开/秒切更快、也少打抽风的实时接口。
#   <STALE(55min)：实时取址失败时，旧地址还没过签名期 → 拿来兜底，别让用户吃"暂时无法播放"。
_play_cache = {}
_PLAY_TTL = 1800
_PLAY_STALE = 3300
_play_lock = threading.Lock()        # 保护 cache + inflight
_play_inflight = {}                  # vid -> Event：同一 vid 并发只打一次接口，其余等结果


def _detail_addr(vid: int, use_proxy):
    """打一次 detail 接口，取第一条有 addr 的播放线路。失败抛异常。"""
    d = c.api_call(f"cms/vod/detail/{vid}", method=1, timeout=12, use_proxy=use_proxy)
    play = (d.get("result") or {}).get("vod", {}).get("vodFullPlayUrl") or []
    if isinstance(play, str):
        play = [{"addr": play}]
    return next((p.get("addr") for p in play if p.get("addr")), None)


def _fetch_addr(vid: int):
    """实时取址，最多 3 次：第 1 次走本机真实 IP（快、独享）；失败后第 2/3 次换住宅
    代理 IP（绕开被 WAF 限速的本机 IP）。每次之间小退避，给瞬时网络抖动恢复时间。"""
    for attempt in range(3):
        try:
            addr = _detail_addr(vid, use_proxy=(attempt >= 1))
            if addr:
                return addr
        except Exception:
            pass
        if attempt < 2:
            time.sleep(0.4)
    return None


def _fresh_addr(vid: int):
    now_t = time.time()
    hit = _play_cache.get(vid)
    if hit and now_t - hit[1] < _PLAY_TTL:
        return hit[0]                                    # 新鲜缓存，直接用

    # single-flight：同一 vid 并发只让一个去打接口，其余等它的结果
    with _play_lock:
        ev = _play_inflight.get(vid)
        leader = ev is None
        if leader:
            ev = threading.Event()
            _play_inflight[vid] = ev
    if not leader:
        ev.wait(timeout=15)
        h = _play_cache.get(vid)
        return h[0] if h else None

    try:
        addr = _fetch_addr(vid)
        if addr:
            _play_cache[vid] = (addr, time.time())
            return addr
        if hit and now_t - hit[1] < _PLAY_STALE:         # 取址失败 → 旧地址没过签名期就兜底
            return hit[0]
        return None
    finally:
        with _play_lock:
            _play_inflight.pop(vid, None)
        ev.set()


def pic_url(path):
    return c._join(prefixes()["pic"], path or "")


def m3u8_url(addr):
    return c._join(prefixes()["media"], addr or "")


def now():
    return datetime.now(timezone.utc).isoformat()   # 统一 UTC，跨时区机器可比较


# 图片是否加密（newH5ImageSecret，缓存 10 分钟；取不到默认按加密处理）
_imgsec = {"t": 0.0, "v": "1"}


def img_secret():
    if time.time() - _imgsec["t"] > 600:
        try:
            r = c.api_call("config/query", method=1,
                           params={"groupKey": "APP", "key": "newH5ImageSecret"})
            d = r.get("data") or r.get("result") or {}
            _imgsec["v"] = str(d.get("newH5ImageSecret", "1"))
        except Exception:
            pass
        _imgsec["t"] = time.time()
    return _imgsec["v"]


def require_auth(request: Request):
    if not request.session.get("auth"):
        raise HTTPException(401, "需要登录")


# ============================================================
# 认证
# ============================================================
@app.get("/api/me")
async def me(request: Request):
    return {"auth": bool(request.session.get("auth")), "img_secret": img_secret()}


@app.post("/api/login")
async def login(request: Request):
    body = await request.json()
    if str(body.get("password", "")) == str(PASSWORD):
        request.session["auth"] = True
        return {"ok": True}
    raise HTTPException(403, "密码错误")


@app.post("/api/logout")
async def logout(request: Request):
    request.session.clear()
    return {"ok": True}


# ============================================================
# 列表 / 搜索 / 筛选 / 排序 / 翻页
# ============================================================
_SORT = {"new": "v.createTime DESC", "hot": "v.readNumber DESC", "long": "v.duration DESC"}


@app.get("/api/videos")
def list_videos(q: str = "", tag: str = "", group: int = 0, theme: int = 0, sort: str = "new",
                source: str = "all", page: int = 1, page_size: int = 30,
                _=Depends(require_auth)):
    page = max(1, page)
    page_size = min(max(page_size, 1), 100)
    where = []
    params = []
    if q:
        where.append("v.title LIKE %s"); params.append(f"%{q}%")
    if tag:
        where.append("JSON_CONTAINS(v.tags, %s)"); params.append(json.dumps(tag, ensure_ascii=False))
    if group:
        where.append("JSON_CONTAINS(v.`groups`, %s)"); params.append(str(group))
    if theme:
        where.append("JSON_CONTAINS(v.themes, %s)"); params.append(str(theme))

    if source == "fav":
        frm = "favorites s JOIN videos v ON v.id = s.video_id"
        order = "s.created_at DESC"
    elif source == "hist":
        frm = "history s JOIN videos v ON v.id = s.video_id"
        order = "s.watched_at DESC"
    else:
        frm = "videos v"
        order = _SORT.get(sort, _SORT["new"])

    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    sql = (f"SELECT v.id, v.title, v.vodPic, v.duration, v.author, v.authorAvatar, "
           f"v.readNumber, v.likeNumber, v.createTime "
           f"FROM {frm}{where_sql} ORDER BY {order} "
           f"LIMIT %s OFFSET %s")
    rows = db.query(sql, (*params, page_size, (page - 1) * page_size))
    items = [{
        "id": r["id"], "title": r["title"],
        "cover": pic_url(r["vodPic"]),
        "duration": r["duration"], "author": r["author"],
        "readNumber": r["readNumber"], "likeNumber": r["likeNumber"],
        "createTime": r["createTime"],
    } for r in rows]
    return {"page": page, "page_size": page_size, "items": items, "has_more": len(rows) == page_size}


@app.get("/api/video/{vid}")
def video_detail(vid: int, _=Depends(require_auth)):
    rows = db.query("SELECT * FROM videos WHERE id=%s", (vid,))
    if not rows:
        raise HTTPException(404, "不存在")
    v = rows[0]
    # 播放地址带时间签名会过期，必须实时从 API 取新鲜的（库里存的早过期 → 403）
    addr = _fresh_addr(vid)

    # 同一签名地址套到各条线路（国线/海线），前端可切换
    pr = prefixes()
    lines = [{"name": ln["name"], "url": c._join(ln["line"], addr)} for ln in pr.get("lines", [])] if addr else []
    if not lines and addr:
        lines = [{"name": "默认", "url": m3u8_url(addr)}]

    fav = bool(db.query("SELECT 1 FROM favorites WHERE video_id=%s", (vid,)))
    return {
        "id": v["id"], "title": v["title"],
        "tags": json.loads(v.get("tags") or "[]"),
        "cover": pic_url(v.get("vodPic")),
        "duration": v["duration"], "author": v["author"],
        "readNumber": v["readNumber"], "likeNumber": v["likeNumber"],
        "createTime": v["createTime"],
        "lines": lines,
        "sources": [lines[0]["url"]] if lines else [],   # 兼容旧前端
        "fav": fav,
    }


# ============================================================
# 收藏 / 历史
# ============================================================
@app.post("/api/favorite/{vid}")
def toggle_fav(vid: int, _=Depends(require_auth)):
    if db.query("SELECT 1 FROM favorites WHERE video_id=%s", (vid,)):
        db.execute("DELETE FROM favorites WHERE video_id=%s", (vid,))
        return {"fav": False}
    db.execute("INSERT IGNORE INTO favorites (video_id, created_at) VALUES (%s, %s)", (vid, now()))
    return {"fav": True}


@app.post("/api/history/{vid}")
def record_history(vid: int, position: int = 0, _=Depends(require_auth)):
    db.execute("REPLACE INTO history (video_id, watched_at, position) VALUES (%s, %s, %s)",
               (vid, now(), position))
    return {"ok": True}


# ============================================================
# 分类列表（给筛选用，缓存 1 小时）
# ============================================================
_groups = {"t": 0.0, "data": []}


@app.get("/api/groups")
def groups(_=Depends(require_auth)):
    if time.time() - _groups["t"] > 3600 or not _groups["data"]:
        try:
            r = c.api_call("cms/group", method=1)
            _groups["data"] = [{"id": g["id"], "name": g.get("groupName")}
                               for g in (r.get("data") or [])]
            _groups["t"] = time.time()
        except Exception:
            pass
    return _groups["data"]


# ============================================================
# 首页专题墙（视频全本地；专题名字典走 theme/list，与 /api/groups 同模式）
# ============================================================
_home = {"t": 0.0, "data": []}
# 专题字典 id->title：仅 20 条轻量元数据，缓存 24h；视频数据仍全部本地。
# 原站下架/隐藏的专题取不到名 → 首页自动跳过该块。
_theme_names = {"t": 0.0, "data": {}}


def theme_names() -> dict:
    """专题名字典 id->title。分类固定不变，不走实时接口，只读 settings 表
    (settings.theme_names 已持久化；5 分钟轻量重读，改库后免重启生效)。
    JSON 序列化会把 int 键变字符串，读回时转回 int 以匹配 themes 里的数字 id。"""
    if time.time() - _theme_names["t"] <= 300 and _theme_names["data"]:
        return _theme_names["data"]
    stored = c.get_setting("theme_names")
    if isinstance(stored, dict) and stored:
        _theme_names["data"] = {int(k): v for k, v in stored.items()}
        _theme_names["t"] = time.time()
    return _theme_names["data"]


@app.get("/api/home")
def home(_=Depends(require_auth)):
    """首页专题墙：视频全部取自本地 videos 表，按 themes(专题id) 分组；
    专题名来自 theme/list 字典(24h缓存)。下架专题取不到名 → 跳过该块。"""
    if time.time() - _home["t"] > 300 or not _home["data"]:
        try:
            names = theme_names()
            rows = db.query(
                "SELECT id, title, vodPic, duration, author, readNumber, likeNumber, "
                "createTime, themes FROM videos "
                "ORDER BY createTime DESC LIMIT 3000")
            buckets = {}   # 专题id -> {id,title,items}；按 createTime 倒序填，每题留最新 12 条
            used = set()   # 一个视频只进一个专题：避免「一片多标签」的最新视频霸占多个专题头部
            for r in rows:
                if r["id"] in used:
                    continue
                try:
                    ts = json.loads(r.get("themes") or "[]")
                except Exception:
                    ts = []
                if not isinstance(ts, list):
                    ts = []
                for tid in ts:
                    name = names.get(tid)
                    if not name:               # 下架/隐藏专题，无名 → 跳过
                        continue
                    b = buckets.setdefault(tid, {"id": tid, "title": name, "items": []})
                    if len(b["items"]) < 12:    # 归入第一个未满的所属专题即停，自动均摊
                        b["items"].append({
                            "id": r["id"], "title": r["title"],
                            "cover": pic_url(r["vodPic"]),
                            "duration": r["duration"], "author": r["author"],
                            "readNumber": r["readNumber"], "createTime": r["createTime"],
                        })
                        used.add(r["id"])
                        break
            # 视频太少的专题不单独成块；视频多的专题排前面
            out = sorted((b for b in buckets.values() if len(b["items"]) >= 4),
                         key=lambda b: len(b["items"]), reverse=True)
            if out:
                _home["data"] = out
                _home["t"] = time.time()
        except Exception:
            pass
    return _home["data"]


@app.get("/api/themes")
def themes_dict(_=Depends(require_auth)):
    """专题字典 id->title，前端点专题进列表时显示专题名用（复用 theme_names 缓存）。"""
    return theme_names()


# 下载改由浏览器直连 CDN 完成（拉分片 + AES 解密 + 拼 .ts），不经后端，见 web/app.js


# ---- 静态前端（必须最后挂，让 /api 路由优先）----
app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
