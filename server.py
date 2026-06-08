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
from datetime import datetime

from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

import crawler as c

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
# 图片用 playLines[0]（快线，原站图片函数就取这条），视频 m3u8 用 h5_play_line（国线/海线，可切换）。
_pref = {"t": 0.0, "media": c.MEDIA_BASE, "pic": c.PIC_BASE, "lines": []}


def _first_line(s):
    try:
        arr = json.loads(s or "[]")
        return arr[0]["line"] if arr else None
    except Exception:
        return None


def prefixes():
    if time.time() - _pref["t"] > 120:
        try:
            r = c.api_call("config/query", method=1,
                           params={"groupKey": "APP", "key": "picBaseUrl,playLines,h5_play_line"})
            conf = r.get("data") or r.get("result") or {}
            _pref["pic"] = _first_line(conf.get("playLines")) or conf.get("picBaseUrl") or _pref["pic"]
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
            pass
        _pref["t"] = time.time()
    return _pref


def pic_url(path):
    return c._join(prefixes()["pic"], path or "")


def m3u8_url(addr):
    return c._join(prefixes()["media"], addr or "")


def now():
    return datetime.now().isoformat()


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
def list_videos(q: str = "", tag: str = "", group: int = 0, sort: str = "new",
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
    addr = None
    try:
        d = c.api_call(f"cms/vod/detail/{vid}", method=1)
        play = (d.get("result") or {}).get("vod", {}).get("vodFullPlayUrl") or []
        if isinstance(play, str):
            play = [{"addr": play}]
        addr = next((p.get("addr") for p in play if p.get("addr")), None)
    except Exception:
        pass

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
# 首页分组聚合（纯本地：按库内分类聚合，不碰原站，缓存 5 分钟）
# ============================================================
_home = {"t": 0.0, "data": []}


@app.get("/api/home")
def home(_=Depends(require_auth)):
    """首页主题墙：数据全部取自本地 videos 表，按分类(groupNames)分组，
    每组放最新若干条。只收录有播放地址(detail_at 非空)的视频 → 点开必能播。"""
    if time.time() - _home["t"] > 300 or not _home["data"]:
        try:
            rows = db.query(
                "SELECT id, title, vodPic, duration, author, readNumber, likeNumber, "
                "createTime, groupNames FROM videos "
                "ORDER BY createTime DESC LIMIT 3000")
            buckets = {}   # 分类id -> {id,title,items}；按 createTime 倒序填，每类留最新 12 条
            for r in rows:
                try:
                    gs = json.loads(r.get("groupNames") or "[]")
                except Exception:
                    gs = []
                for g in gs:
                    if not isinstance(g, dict) or not g.get("id"):
                        continue
                    b = buckets.setdefault(g["id"], {"id": g["id"], "title": g.get("name") or "", "items": []})
                    if len(b["items"]) < 12:
                        b["items"].append({
                            "id": r["id"], "title": r["title"],
                            "cover": pic_url(r["vodPic"]),
                            "duration": r["duration"], "author": r["author"],
                            "readNumber": r["readNumber"], "createTime": r["createTime"],
                        })
            # 视频太少的分类不单独成块（避免单薄）；视频多的分类排前面
            out = sorted((b for b in buckets.values() if len(b["items"]) >= 4),
                         key=lambda b: len(b["items"]), reverse=True)
            if out:
                _home["data"] = out
                _home["t"] = time.time()
        except Exception:
            pass
    return _home["data"]


# 下载改由浏览器直连 CDN 完成（拉分片 + AES 解密 + 拼 .ts），不经后端，见 web/app.js


# ---- 静态前端（必须最后挂，让 /api 路由优先）----
app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
