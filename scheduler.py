#!/usr/bin/env python3
"""
xx9 计划任务调度器 —— 自包含守护进程，可单独部署（海外服务器）。

基于 APScheduler 的 BlockingScheduler，按 cron 表达式周期调用项目里已有的命令。
一个长驻进程即可，systemd / docker / nohup 任意方式拉起。

内置任务：
  qg_refresh      青果住宅 IP 每日重提取：拉满到 size 个，防 0 点失效后当天无可用代理（进程内执行）
  creds_refresh   重跑 fetch_creds.py 刷新 api 域名/jwt/access_token（写 credentials 表），防过期取址失败
  crawler_refresh crawler.py --refresh 增量爬新片（默认走青果代理绕 WAF 限速）

特性：
  - APScheduler CronTrigger：下次触发算出来，DST/边界都交给久经考验的库
  - 防重叠：max_instances=1（同任务上次没跑完，本次自动错过）+ coalesce（堆积的触发合并成一次）
  - 每任务独立滚动日志（logs/<job>.log）+ 主日志（logs/scheduler.log，同时打到 stdout 供 journald 收）
  - 运行结果写 scheduler_runs 表（best-effort，库挂了不影响调度）
  - SIGINT/SIGTERM 优雅退出，等在跑的任务收尾

用法：
  uv run python scheduler.py                 # 启动守护进程
  uv run python scheduler.py --once <job>    # 立刻跑一次某任务后退出（测试/手动）
  uv run python scheduler.py --list          # 列出任务、cron、下次触发时间
  uv run python scheduler.py --status [N]    # 看最近 N 条运行记录（默认 20）
"""
import argparse
import json
import logging
import os
import signal
import subprocess
import sys
import time
from datetime import datetime
from logging.handlers import RotatingFileHandler

import pymysql
import requests
from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

# ============================================================
# 默认任务配置（可被 config.json 的 "scheduler" 段覆盖）
# cron：标准 5 段「分 时 日 月 周」，本地时区。错峰挑了非整点的分钟。
# ============================================================
DEFAULT_SCHEDULER = {
    "run_on_start": ["qg_refresh"],       # 启动时立刻补一次青果 IP（刚部署/重启即可用）
    "log_dir": "logs",
    "jobs": {
        "qg_refresh": {
            "enabled": True,
            "cron": "5 0 * * *",          # 每天 0:05（0 点失效后立刻补满）
            "timeout": 120,
            "size": 3,                    # 目标 IP 数（= 买的通道数）
        },
        "creds_refresh": {
            "enabled": True,
            "cron": "13 */6 * * *",       # 每 6 小时
            "timeout": 300,
            # 默认不带 --sync-config：凭证写 credentials 表即可（server/crawler 读库），
            # 不去改服务器上只存 mysql 连接的 config.json。
            "args": [],
        },
        "crawler_refresh": {
            "enabled": True,
            "cron": "27 */2 * * *",       # 每 2 小时
            "timeout": 3600,
            "args": ["--refresh", "--proxy"],
        },
    },
}

# 触发堆积/错过的宽限：进程忙或刚启动时，迟到的触发在此秒数内仍执行（且 coalesce 合并成一次）
MISFIRE_GRACE = 3600


# ============================================================
# 配置加载
# ============================================================
def load_config():
    if not os.path.exists(CONFIG_PATH):
        sys.exit(f"[致命] 找不到 {CONFIG_PATH}")
    with open(CONFIG_PATH) as f:
        cfg = json.load(f)
    if not cfg.get("mysql"):
        sys.exit("[致命] config.json 缺 mysql 连接")
    return cfg


def merge_scheduler_cfg(cfg: dict) -> dict:
    """把 config.json 的 scheduler 段叠加到默认上（jobs 按 job 名逐项浅合并）。"""
    out = json.loads(json.dumps(DEFAULT_SCHEDULER))  # 深拷贝
    user = cfg.get("scheduler") or {}
    for k in ("run_on_start", "log_dir"):
        if k in user:
            out[k] = user[k]
    for name, jc in (user.get("jobs") or {}).items():
        out["jobs"].setdefault(name, {}).update(jc)
    return out


def db_conn(mysql_cfg: dict):
    return pymysql.connect(
        host=mysql_cfg["host"], port=int(mysql_cfg.get("port", 3306)),
        user=mysql_cfg["user"], password=mysql_cfg.get("password", ""),
        database=mysql_cfg["database"], charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor, connect_timeout=5,
    )


def load_proxy(cfg: dict):
    """青果代理配置：优先 MySQL proxy 表（enabled=1 最新一条），回退 config.json 的 proxy 段。"""
    try:
        conn = db_conn(cfg["mysql"])
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT get_url, query_url, auth FROM proxy "
                            "WHERE enabled=1 ORDER BY id DESC LIMIT 1")
                row = cur.fetchone()
                if row and row.get("get_url"):
                    return row
        finally:
            conn.close()
    except Exception:
        pass
    return cfg.get("proxy") or {}


# ============================================================
# 运行记录表（best-effort）
# ============================================================
def ensure_runs_table(cfg: dict):
    try:
        conn = db_conn(cfg["mysql"])
        try:
            with conn.cursor() as cur:
                cur.execute("""
                    CREATE TABLE IF NOT EXISTS scheduler_runs (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        job VARCHAR(64) NOT NULL,
                        started_at DATETIME NOT NULL,
                        finished_at DATETIME,
                        status VARCHAR(16) NOT NULL,
                        duration_ms INT,
                        detail TEXT,
                        INDEX idx_job_started (job, started_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """)
            conn.commit()
        finally:
            conn.close()
    except Exception as e:
        logging.getLogger("scheduler").warning(f"scheduler_runs 表初始化失败（不影响调度）：{e}")


def record_run(cfg, job, started, finished, status, detail):
    try:
        conn = db_conn(cfg["mysql"])
        try:
            with conn.cursor() as cur:
                dur = int((finished - started).total_seconds() * 1000) if finished else None
                cur.execute(
                    "INSERT INTO scheduler_runs (job, started_at, finished_at, status, duration_ms, detail) "
                    "VALUES (%s,%s,%s,%s,%s,%s)",
                    (job, started, finished, status, dur, (detail or "")[:60000]),
                )
            conn.commit()
        finally:
            conn.close()
    except Exception:
        pass  # 库不可用不影响调度


# ============================================================
# 日志
# ============================================================
def setup_logging(log_dir: str):
    os.makedirs(log_dir, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s [%(name)s] %(levelname)s %(message)s")

    root = logging.getLogger("scheduler")
    root.setLevel(logging.INFO)
    root.handlers.clear()
    fh = RotatingFileHandler(os.path.join(log_dir, "scheduler.log"),
                             maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
    fh.setFormatter(fmt)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    root.addHandler(fh)
    root.addHandler(sh)

    # APScheduler 自身日志：错过/异常也落到主日志
    aps = logging.getLogger("apscheduler")
    aps.setLevel(logging.WARNING)
    aps.addHandler(fh)
    aps.addHandler(sh)
    return root


def job_logger(log_dir: str, job: str):
    lg = logging.getLogger(f"scheduler.{job}")
    lg.setLevel(logging.INFO)
    if not lg.handlers:
        fh = RotatingFileHandler(os.path.join(log_dir, f"{job}.log"),
                                 maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
        fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        lg.addHandler(fh)
    return lg  # propagate=True，主日志也会收到一行摘要


# ============================================================
# 任务实现
# ============================================================
def run_qg_refresh(cfg, jobcfg, log) -> str:
    """进程内补满青果 IP。get 每次进一个空闲通道，通道满时返回无害。"""
    proxy = load_proxy(cfg)
    get_url = proxy.get("get_url")
    if not get_url:
        raise RuntimeError("未配置青果 get_url（proxy 表 / config.json proxy 段都没有）")
    query_url = proxy.get("query_url")
    size = int(jobcfg.get("size", 3))

    def servers():
        if not query_url:
            return None
        d = requests.get(query_url, timeout=10).json()
        return [x.get("server") for x in (d.get("data") or []) if x.get("server")]

    before = servers()
    need = size if before is None else max(size - len(before), 0)
    got = 0
    for _ in range(need):
        try:
            requests.get(get_url, timeout=10)
            got += 1
        except Exception as e:
            log.warning(f"get 调用失败：{e}")
        time.sleep(0.5)
    after = servers()
    b = "?" if before is None else len(before)
    a = "?" if after is None else len(after)
    msg = f"青果 IP：{b} → {a}（get x{got}）"
    log.info(msg)
    return msg


def run_subprocess(script: str, args, timeout: int, log) -> str:
    """跑项目里的脚本（同解释器、同目录），回传输出尾部。失败抛异常。"""
    cmd = [sys.executable, os.path.join(BASE_DIR, script), *args]
    log.info("执行：" + " ".join(cmd))
    p = subprocess.run(cmd, cwd=BASE_DIR, capture_output=True, text=True, timeout=timeout)
    out = (p.stdout or "") + (("\n[stderr]\n" + p.stderr) if p.stderr else "")
    tail = out.strip()[-2000:]
    if p.returncode != 0:
        raise RuntimeError(f"退出码 {p.returncode}\n{tail}")
    return tail or "(无输出)"


JOB_RUNNERS = {
    "qg_refresh": lambda cfg, jc, log: run_qg_refresh(cfg, jc, log),
    "creds_refresh": lambda cfg, jc, log: run_subprocess("fetch_creds.py", jc.get("args", []), jc.get("timeout", 300), log),
    "crawler_refresh": lambda cfg, jc, log: run_subprocess("crawler.py", jc.get("args", ["--refresh", "--proxy"]), jc.get("timeout", 3600), log),
}


# ============================================================
# 调度核心
# ============================================================
class Scheduler:
    def __init__(self, cfg, scfg):
        self.cfg = cfg
        self.scfg = scfg
        self.log_dir = os.path.join(BASE_DIR, scfg.get("log_dir", "logs"))
        self.log = setup_logging(self.log_dir)
        self.sched = BlockingScheduler(timezone=_local_tz())

    def run_job(self, job, jobcfg, reason="定时触发"):
        """被 APScheduler 调用（或 --once / run_on_start 直接调）。异常自己吞下并记账，
        避免把 BlockingScheduler 的线程打挂。"""
        jlog = job_logger(self.log_dir, job)
        started = datetime.now()
        jlog.info(f"开始（{reason}）")
        try:
            detail = JOB_RUNNERS[job](self.cfg, jobcfg, jlog)
            finished = datetime.now()
            jlog.info(f"完成，用时 {(finished - started).total_seconds():.1f}s")
            record_run(self.cfg, job, started, finished, "ok", detail)
        except subprocess.TimeoutExpired:
            finished = datetime.now()
            jlog.error(f"超时（>{jobcfg.get('timeout')}s）")
            record_run(self.cfg, job, started, finished, "timeout", f"超时 {jobcfg.get('timeout')}s")
        except Exception as e:
            finished = datetime.now()
            jlog.error(f"失败：{e}")
            record_run(self.cfg, job, started, finished, "fail", str(e))

    def run_forever(self):
        ensure_runs_table(self.cfg)
        jobs = {n: j for n, j in self.scfg["jobs"].items() if j.get("enabled", True)}
        self.log.info(f"调度器启动，启用任务：{', '.join(jobs) or '(无)'}")

        for n, j in jobs.items():
            trig = CronTrigger.from_crontab(j["cron"], timezone=_local_tz())
            self.sched.add_job(
                self.run_job, trigger=trig, args=[n, j],
                id=n, name=n,
                max_instances=1,        # 同任务不并发：上次没跑完则本次错过
                coalesce=True,          # 堆积的多次触发合并成一次
                misfire_grace_time=MISFIRE_GRACE,
            )
            nxt = trig.get_next_fire_time(None, datetime.now(_local_tz()))
            self.log.info(f"  {n}: cron='{j['cron']}' 下次≈{nxt}")

        # 启动时立刻补跑（在调度线程外用一次性线程跑，不占用 cron 实例额度）
        import threading
        for n in self.scfg.get("run_on_start", []):
            if n in jobs:
                threading.Thread(target=self.run_job, args=[n, jobs[n], "启动补跑"],
                                 daemon=True, name=f"{n}-onstart").start()

        signal.signal(signal.SIGTERM, lambda *_: self.sched.shutdown(wait=True))
        try:
            self.sched.start()   # 阻塞；Ctrl-C / SIGTERM 退出
        except (KeyboardInterrupt, SystemExit):
            pass
        self.log.info("调度器已退出")


def _local_tz():
    """APScheduler 需要明确时区；取系统本地时区。"""
    return datetime.now().astimezone().tzinfo


# ============================================================
# CLI
# ============================================================
def main():
    ap = argparse.ArgumentParser(description="xx9 计划任务调度器（自包含守护进程）")
    ap.add_argument("--once", metavar="JOB", help="立刻跑一次指定任务后退出")
    ap.add_argument("--list", action="store_true", help="列出任务、cron、下次触发时间")
    ap.add_argument("--status", nargs="?", const=20, type=int, metavar="N",
                    help="查看最近 N 条运行记录（默认 20）")
    args = ap.parse_args()

    cfg = load_config()
    scfg = merge_scheduler_cfg(cfg)

    if args.list:
        now = datetime.now(_local_tz())
        print(f"{'任务':<16}{'启用':<6}{'cron':<16}下次触发")
        for n, j in scfg["jobs"].items():
            en = "✓" if j.get("enabled", True) else "✗"
            if j.get("enabled", True):
                nx = CronTrigger.from_crontab(j["cron"], timezone=_local_tz()).get_next_fire_time(None, now)
            else:
                nx = "-"
            print(f"{n:<16}{en:<6}{j['cron']:<16}{nx}")
        return

    if args.status is not None:
        try:
            conn = db_conn(cfg["mysql"])
            with conn.cursor() as cur:
                cur.execute("SELECT job, started_at, status, duration_ms, "
                            "LEFT(detail,80) AS detail FROM scheduler_runs "
                            "ORDER BY id DESC LIMIT %s", (args.status,))
                rows = cur.fetchall()
            conn.close()
        except Exception as e:
            sys.exit(f"读取 scheduler_runs 失败：{e}")
        if not rows:
            print("暂无运行记录")
            return
        print(f"{'任务':<16}{'开始':<20}{'状态':<8}{'用时':<10}摘要")
        for r in rows:
            dur = f"{r['duration_ms']/1000:.1f}s" if r["duration_ms"] is not None else "-"
            print(f"{r['job']:<16}{str(r['started_at']):<20}{r['status']:<8}{dur:<10}"
                  f"{(r['detail'] or '').replace(chr(10),' ')}")
        return

    if args.once:
        job = args.once
        if job not in scfg["jobs"]:
            sys.exit(f"未知任务 {job!r}，可选：{', '.join(scfg['jobs'])}")
        sch = Scheduler(cfg, scfg)
        ensure_runs_table(cfg)
        sch.run_job(job, scfg["jobs"][job], reason="手动 --once")
        return

    # 守护进程
    Scheduler(cfg, scfg).run_forever()


if __name__ == "__main__":
    main()
