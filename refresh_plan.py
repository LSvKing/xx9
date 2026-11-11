"""xx9 增量刷新的有界规划（无网络、无数据库副作用，便于单测）。

cms/vod/search 按时间倒序首页条目不能当作全站最大 id：可能被置顶/排序打乱，
也可能阶段性回退到一个更小的 id。本模块只决定「本轮抓哪些 id、要不要告警」，
不负责真正发请求；上探有 cap 与连续空洞阈值，禁止无界遍历。
"""
from __future__ import annotations

from dataclasses import dataclass, field

# 搜索 hint：多看几页、每页多条，取其中最大 id，仍只是 hint
SEARCH_HINT_PAGES = 3
SEARCH_HINT_PAGE_SIZE = 50

# 单轮增量最多尝试的 id 数（含上探）。大于此则截断，下一轮再续；与 backfill 门槛互斥。
REFRESH_JOB_CAP = 2000
# 从库最大 id 之上连续这么多「真空洞」则停止上探
REFRESH_MISS_STREAK = 50
# 搜索 hint 与库最大 id 的差超过此值，视为非增量，交给 --backfill（保持原行为）
REFRESH_BACKFILL_GAP = 50000

MODE_EMPTY_DB = "empty_db"
MODE_NORMAL = "normal"
MODE_EQUAL = "equal"
MODE_SEARCH_BEHIND = "search_behind"
MODE_GAP_TOO_LARGE = "gap_too_large"


@dataclass(frozen=True)
class RefreshPlan:
    mode: str
    have: int
    hint_max: int
    # normal：闭区间 [start, end]；equal / search_behind：从 start 起上探
    start: int = 0
    end: int = 0
    cap: int = 0
    truncated: bool = False
    alert: str = ""
    anomaly_exit: int = 0  # 非 0 时 crawl_refresh 在跑完上探后以此码退出


def parse_search_page_ids(payload) -> list[int]:
    """从 cms/vod/search 响应取出本页 id，缺字段/空页返回空列表。"""
    if not isinstance(payload, dict):
        return []
    data = payload.get("data")
    if not isinstance(data, list):
        return []
    ids = []
    for item in data:
        if isinstance(item, dict) and item.get("id") is not None:
            try:
                ids.append(int(item["id"]))
            except (TypeError, ValueError):
                continue
    return ids


def search_hint_max(page_id_lists: list[list[int]]) -> int:
    """多页 id 的最大值；全空为 0。第一页第一条不必是最大。"""
    flat = [i for page in page_id_lists for i in page]
    return max(flat) if flat else 0


def plan_refresh(have: int, hint_max: int,
                 job_cap: int = REFRESH_JOB_CAP,
                 backfill_gap: int = REFRESH_BACKFILL_GAP,
                 miss_streak: int = REFRESH_MISS_STREAK) -> RefreshPlan:
    """根据库最大 id 与搜索 hint 规划本轮增量，不发请求。"""
    have = int(have or 0)
    hint_max = int(hint_max or 0)
    if have <= 0:
        return RefreshPlan(MODE_EMPTY_DB, have=have, hint_max=hint_max)

    if hint_max > have:
        gap = hint_max - have
        if gap > backfill_gap:
            return RefreshPlan(
                MODE_GAP_TOO_LARGE, have=have, hint_max=hint_max,
                alert=(f"[增量][告警] 与站点搜索 hint 差距过大（{gap} 个 id），"
                       f"这不是增量场景，请用 --backfill"),
            )
        end = min(hint_max, have + job_cap)
        truncated = hint_max > end
        return RefreshPlan(
            MODE_NORMAL, have=have, hint_max=hint_max,
            start=have + 1, end=end, cap=job_cap, truncated=truncated,
        )

    if hint_max == have:
        return RefreshPlan(
            MODE_EQUAL, have=have, hint_max=hint_max,
            start=have + 1, cap=job_cap,
        )

    # hint_max < have：搜索不可靠，绝不能当成「无新数据」成功返回
    alert = (
        f"[增量][告警] 站点搜索最大 id={hint_max} 低于库最大 id={have}，"
        f"搜索排序不可靠，不视为无新数据；改为从 id={have + 1} 起有界探活"
        f"（本轮上限 {job_cap}，连续空洞 {miss_streak} 则停）"
    )
    return RefreshPlan(
        MODE_SEARCH_BEHIND, have=have, hint_max=hint_max,
        start=have + 1, cap=job_cap, alert=alert, anomaly_exit=2,
    )


def classify_detail_kind(data) -> str:
    """把详情结果分成 ok / gap / err。err 含鉴权失败字符串或失败码，不当空洞。"""
    if isinstance(data, str):
        return "err"
    if not isinstance(data, dict):
        return "err"
    code = data.get("code")
    if code in (1019, 1034, 1032, 1062):
        return "err"
    vod = ((data.get("result") or {}).get("vod") or {})
    if vod.get("id"):
        return "ok"
    # 显式失败码且无 vod：非 FAT20001 则 err
    if code not in (None, 0, "0", 200, "200"):
        msg = str(data.get("msg") or data.get("message") or "")
        if "FAT20001" in str(code) or "FAT20001" in msg or "已删除" in msg:
            return "gap"
        return "err"
    return "gap"


@dataclass
class ProbeCursor:
    """从 start 起按 id 递增探活：最多 cap 个，连续 miss_streak 个空洞则停；遇到 err 立即停。"""
    start: int
    cap: int
    miss_streak: int
    attempted: int = 0
    consecutive_gaps: int = 0
    found: int = 0
    stop_reason: str | None = None

    def next_chunk(self, size: int = 50) -> list[int]:
        if self.stop_reason or self.attempted >= self.cap:
            return []
        n = min(int(size), self.cap - self.attempted)
        if n <= 0:
            return []
        begin = self.start + self.attempted
        return list(range(begin, begin + n))

    def observe(self, kind: str) -> str | None:
        """消化一个已按 id 顺序观察的结果，返回新的 stop_reason（若本次触发停止）。"""
        if self.stop_reason:
            return self.stop_reason
        self.attempted += 1
        if kind == "ok":
            self.found += 1
            self.consecutive_gaps = 0
        elif kind == "gap":
            self.consecutive_gaps += 1
            if self.consecutive_gaps >= self.miss_streak:
                self.stop_reason = "miss_streak"
        else:
            self.stop_reason = "error"
        if not self.stop_reason and self.attempted >= self.cap:
            self.stop_reason = "cap"
        return self.stop_reason
