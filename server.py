#!/usr/bin/env python3
"""
视频站后端 - FastAPI

一体化：JSON API（查 MySQL）+ 密码登录 + ffmpeg 下载 + 托管前端静态页。
视频不经过后端：浏览器 hls.js 直连 CDN 播放（CDN 开放 CORS、不需 Referer）。

运行:
    uv run uvicorn server:app --host 0.0.0.0 --port 8000
"""
import os
import re
import json
import time
import tempfile
import subprocess
from datetime import datetime

from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.responses import FileResponse
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


# ---- CDN 前缀（每 60s 从 credentials 表刷新，跟随域名轮换）----
_pref = {"t": 0.0, "media": c.MEDIA_BASE, "pic": c.PIC_BASE}


def prefixes():
    if time.time() - _pref["t"] > 60:
        cr = c._load_creds_from_db(c.MYSQL)
        if cr:
            _pref["media"] = cr.get("media_base") or _pref["media"]
            _pref["pic"] = cr.get("pic_base") or _pref["pic"]
        _pref["t"] = time.time()
    return _pref


def pic_url(path):
    return c._join(prefixes()["pic"], path or "")


def m3u8_url(addr):
    return c._join(prefixes()["media"], addr or "")


def now():
    return datetime.now().isoformat()


def require_auth(request: Request):
    if not request.session.get("auth"):
        raise HTTPException(401, "需要登录")


# ============================================================
# 认证
# ============================================================
@app.get("/api/me")
async def me(request: Request):
    return {"auth": bool(request.session.get("auth"))}


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
    where = ["v.detail_at IS NOT NULL"]   # 只列有播放地址的
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

    sql = (f"SELECT v.id, v.title, v.vodPic, v.duration, v.author, v.authorAvatar, "
           f"v.readNumber, v.likeNumber, v.createTime "
           f"FROM {frm} WHERE {' AND '.join(where)} ORDER BY {order} "
           f"LIMIT %s OFFSET %s")
    rows = db.query(sql, (*params, page_size, (page - 1) * page_size))
    items = [{
        "id": r["id"], "title": r["title"],
        "cover": pic_url(r["vodPic"]),
        "duration": r["duration"], "author": r["author"],
        "readNumber": r["readNumber"], "likeNumber": r["likeNumber"],
    } for r in rows]
    return {"page": page, "page_size": page_size, "items": items, "has_more": len(rows) == page_size}


@app.get("/api/video/{vid}")
def video_detail(vid: int, _=Depends(require_auth)):
    rows = db.query("SELECT * FROM videos WHERE id=%s", (vid,))
    if not rows:
        raise HTTPException(404, "不存在")
    v = rows[0]
    play = json.loads(v.get("playUrls") or "[]")
    sources = [m3u8_url(p.get("addr")) for p in play if p.get("addr")]
    fav = bool(db.query("SELECT 1 FROM favorites WHERE video_id=%s", (vid,)))
    return {
        "id": v["id"], "title": v["title"],
        "tags": json.loads(v.get("tags") or "[]"),
        "cover": pic_url(v.get("vodPic")),
        "duration": v["duration"], "author": v["author"],
        "readNumber": v["readNumber"], "likeNumber": v["likeNumber"],
        "createTime": v["createTime"],
        "sources": sources, "fav": fav,
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
# 下载：ffmpeg 把 m3u8 转 mp4 再发（-c copy，不重编码，较快）
# ============================================================
@app.get("/api/download/{vid}")
def download(vid: int, _=Depends(require_auth)):
    rows = db.query("SELECT title, playUrls FROM videos WHERE id=%s", (vid,))
    if not rows:
        raise HTTPException(404, "不存在")
    play = json.loads(rows[0].get("playUrls") or "[]")
    if not play:
        raise HTTPException(404, "无播放地址")
    url = m3u8_url(play[0]["addr"])
    title = re.sub(r'[\\/*?:"<>|]', "_", rows[0]["title"] or str(vid))[:40]
    out = os.path.join(tempfile.gettempdir(), f"xx9_{vid}.mp4")
    if not os.path.exists(out):
        try:
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error",
                            "-headers", f"Referer: {c.FRONTEND}/",
                            "-i", url, "-c", "copy", "-bsf:a", "aac_adtstoasc", out],
                           check=True, timeout=600)
        except Exception as e:
            raise HTTPException(500, f"转码失败: {str(e)[:80]}")
    return FileResponse(out, filename=f"{title}.mp4", media_type="video/mp4")


# ---- 静态前端（必须最后挂，让 /api 路由优先）----
app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
