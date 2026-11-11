"""crawl_refresh 调用级回归：mock DB / api_call / _fetch_detail，不连真实库与上游。"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pymysql
from tests import netguard
netguard.install()

pymysql.connect = lambda *a, **k: (_ for _ in ()).throw(RuntimeError("no mysql in tests"))

import crawler
from crawler import (
    get_max_id,
    get_search_id_hint,
    preflight,
    crawl_refresh,
    crawl_backfill,
    _probe_upward,
    _fetch_detail,
    _detail_fail_reason,
    classify_detail_kind,
)
from refresh_plan import REFRESH_JOB_CAP, REFRESH_MISS_STREAK


def ok_detail(vid):
    return {"code": 0, "result": {"vod": {"id": int(vid), "title": str(vid)}}}


def fat20001():
    return {"code": "FAT20001", "message": "已删除", "result": {}}


def auth_1019(code=1019):
    return {"code": code, "result": {}}


class FakeDB:
    def __init__(self, have=0):
        self.have = have
        self.inserted = []
        self.ids = set()
        self.max_queries = 0
        self.get_max_id_via_sql = 0

    def query(self, sql, params=None):
        compact = " ".join(sql.split())
        if "MAX(id)" in compact or "MAX(ID)" in compact.upper().replace(" ", ""):
            self.max_queries += 1
            return [{"m": self.have}]
        if "detail_at IS NOT NULL" in compact:
            lo, hi = (params or (None, None))
            return [{"id": i} for i in self.ids if lo is None or (lo <= i <= hi)]
        return []

    def insert_details_batch(self, rows):
        self.inserted.extend(rows)
        for r in rows:
            vid = ((r.get("result") or {}).get("vod") or {}).get("id")
            if vid is None:
                continue
            vid = int(vid)
            self.ids.add(vid)
            if vid > int(self.have or 0):
                self.have = vid

    def count_items(self):
        return len(self.ids)

    def get_progress(self, tag):
        return {"page": 0, "total": 0}

    def set_progress(self, *a, **k):
        pass


def inserted_ids(db: FakeDB):
    out = []
    for r in db.inserted:
        vid = ((r.get("result") or {}).get("vod") or {}).get("id")
        if vid is not None:
            out.append(int(vid))
    return out


class FetchDetailReturnTypeTests(unittest.TestCase):
    """对照真实 _fetch_detail 约定：成功/空洞为 dict，鉴权失败为原因字符串。"""

    def test_fat20001_returns_dict_classified_gap(self):
        payload = fat20001()
        self.assertIsNone(_detail_fail_reason(payload))
        with patch.object(crawler, "api_call", return_value=payload), \
             patch.object(crawler.time, "sleep"):
            data = _fetch_detail(1741418, retries=2)
        self.assertIsInstance(data, dict)
        self.assertNotIsInstance(data, str)
        self.assertEqual(data.get("code"), "FAT20001")
        self.assertEqual(classify_detail_kind(data), "gap")

    def test_deleted_message_dict_is_gap(self):
        payload = {"code": 0, "message": "已删除", "result": {}}
        self.assertIsNone(_detail_fail_reason(payload))
        with patch.object(crawler, "api_call", return_value=payload):
            data = _fetch_detail(1, retries=1)
        self.assertIsInstance(data, dict)
        self.assertEqual(classify_detail_kind(data), "gap")

    def test_code_1019_int_returns_reason_str(self):
        with patch.object(crawler, "api_call", return_value=auth_1019(1019)), \
             patch.object(crawler, "_maybe_reload_creds", return_value=False), \
             patch.object(crawler.time, "sleep"):
            data = _fetch_detail(1, retries=2)
        self.assertIsInstance(data, str)
        self.assertIn("1019", data)
        self.assertEqual(classify_detail_kind(data), "err")

    def test_code_1019_str_also_reason_str(self):
        with patch.object(crawler, "api_call", return_value=auth_1019("1019")), \
             patch.object(crawler, "_maybe_reload_creds", return_value=False), \
             patch.object(crawler.time, "sleep"):
            data = _fetch_detail(1, retries=2)
        self.assertIsInstance(data, str)
        self.assertIn("1019", data)
        self.assertEqual(classify_detail_kind(data), "err")

    def test_ok_returns_dict(self):
        with patch.object(crawler, "api_call", return_value=ok_detail(9)):
            data = _fetch_detail(9, retries=1)
        self.assertIsInstance(data, dict)
        self.assertEqual(classify_detail_kind(data), "ok")


class SearchHintAndGetMaxIdTests(unittest.TestCase):
    def test_multipage_hint_uses_max_not_first_item(self):
        pages = {
            1: {"data": [{"id": 1665643}, {"id": 1741000}]},
            2: {"data": [{"id": 1741418}, {"id": 1741200}]},
            3: {"data": [{"id": 1730000}]},
        }
        calls = []

        def api_call(uri, method=2, params=None, **k):
            self.assertIn("cms/vod/search", uri)
            calls.append(dict(params or {}))
            return pages[(params or {}).get("page")]

        with patch.object(crawler, "api_call", side_effect=api_call):
            hint, ids = get_search_id_hint()
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0]["page"], 1)
        self.assertEqual(hint, 1741418)
        self.assertNotEqual(ids[0], 1741418)  # 首页首条不是 max
        calls.clear()
        with patch.object(crawler, "api_call", side_effect=api_call):
            mx = get_max_id()
        self.assertEqual(len(calls), 3)
        self.assertEqual(mx, 1741418)

    def test_get_max_id_empty_raises(self):
        with patch.object(crawler, "api_call", return_value={"data": []}):
            with self.assertRaises(RuntimeError):
                get_max_id()

    def test_preflight_false_on_search_failure_does_not_assume_site_max(self):
        with patch.object(crawler, "get_max_id", side_effect=RuntimeError("搜索挂了")):
            self.assertFalse(preflight(workers=1, samples=3, min_rate=0.7))

    def test_preflight_samples_hint_only_no_db_write(self):
        db_guard = FakeDB(have=10)
        def api_call(uri, method=1, params=None, **k):
            if "search" in uri:
                return {"data": [{"id": 100}, {"id": 90}]}
            return ok_detail(int(str(uri).rsplit("/", 1)[-1]))

        with patch.object(crawler, "api_call", side_effect=api_call):
            ok = preflight(workers=1, samples=3, min_rate=0.7)
        self.assertTrue(ok)
        self.assertEqual(db_guard.inserted, [])

    def test_backfill_with_existing_rows_does_not_call_get_max_id(self):
        db = FakeDB(have=8)
        called = {"n": 0}

        def boom(*a, **k):
            called["n"] += 1
            raise AssertionError("get_max_id should not be used when DB has rows")

        with patch.object(crawler, "get_max_id", side_effect=boom), \
             patch.object(crawler, "_run_detail_jobs", return_value=(0, 0, 0)):
            crawl_backfill(db, workers=1, batch=10, chunk=20)
        self.assertEqual(called["n"], 0)

    def test_backfill_empty_db_uses_get_max_id_as_start_hint_only(self):
        db = FakeDB(have=0)
        with patch.object(crawler, "get_max_id", return_value=5) as g, \
             patch.object(crawler, "_run_detail_jobs", return_value=(0, 0, 0)) as jobs:
            crawl_backfill(db, workers=1, batch=10, chunk=20)
        g.assert_called_once()
        # 从 hint=5 往下到 1，不应按「全站最大」再去搜一轮或向上扫
        called_ids = jobs.call_args[0][1]
        self.assertEqual(max(called_ids), 5)
        self.assertEqual(min(called_ids), 1)
        self.assertLessEqual(len(called_ids), 5)


class ProbeUpwardTests(unittest.TestCase):
    def test_stop_discards_already_fetched_and_does_not_raise_watermark(self):
        db = FakeDB(have=99)

        def fetch(vid, retries=4):
            # 100 有片；101-105 空洞触发停探；同一批已拉到的 106+ 是有片，但不得入库
            if vid == 100 or vid >= 106:
                return ok_detail(vid)
            return fat20001()

        with patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            ok, gap, err, reason = _probe_upward(
                db, start=100, cap=80, miss_streak=5,
                workers=1, batch=10,
            )
        self.assertEqual(reason, "miss_streak")
        self.assertEqual(inserted_ids(db), [100])
        self.assertEqual(db.have, 100)  # 水位不得跳到 106+
        self.assertNotIn(106, db.ids)
        # workers==1 零预取：只请求 100 + 连续 5 个空洞，106 从未发出
        fetched = [c.args[0] for c in m.call_args_list]
        self.assertEqual(fetched, [100, 101, 102, 103, 104, 105])
        self.assertEqual(m.call_count, 6)
        self.assertNotIn(106, fetched)
        self.assertEqual(err, 0)
        self.assertEqual(ok, 1)
        self.assertEqual(gap, 5)

    def test_cap_bounds_fetch_count(self):
        db = FakeDB(have=0)
        with patch.object(crawler, "_fetch_detail", side_effect=lambda vid, retries=4: ok_detail(vid)) as m:
            ok, gap, err, reason = _probe_upward(
                db, start=1, cap=60, miss_streak=50,
                workers=2, batch=20,
            )
        self.assertEqual(reason, "cap")
        self.assertEqual(m.call_count, 60)
        self.assertEqual(ok, 60)
        self.assertEqual(sorted(inserted_ids(db)), list(range(1, 61)))

    def test_error_stops_and_does_not_insert_later_fetched_ok(self):
        db = FakeDB(have=9)

        def fetch(vid, retries=4):
            if vid == 11:
                return "上游1019"
            return ok_detail(vid)

        with patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            ok, gap, err, reason = _probe_upward(
                db, start=10, cap=40, miss_streak=50,
                workers=2, batch=10,
            )
        self.assertEqual(reason, "error")
        self.assertEqual(inserted_ids(db), [10])
        self.assertEqual(db.have, 10)
        self.assertNotIn(12, db.ids)
        self.assertEqual(err, 1)
        # 遇错即停，但当前块已发出的请求仍计入；不得超过 cap
        self.assertLessEqual(m.call_count, 40)
        self.assertGreaterEqual(m.call_count, 2)


class CrawlRefreshTests(unittest.TestCase):
    def setUp(self):
        self.sleep = patch.object(crawler.time, "sleep")
        self.sleep.start()
        self.addCleanup(self.sleep.stop)

    def _search(self, hint_pages):
        def api_call(uri, method=2, params=None, **k):
            if "search" not in uri:
                raise AssertionError(uri)
            return hint_pages.get((params or {}).get("page"), {"data": []})
        return api_call

    def test_search_behind_probes_inserts_and_exits_2(self):
        # 生产 11/11 11:27：hint 1665643 < 库 1741417
        db = FakeDB(have=1741417)
        pages = {1: {"data": [{"id": 1665643}]}, 2: {"data": []}}

        def fetch(vid, retries=4):
            if vid == 1741418:
                return ok_detail(vid)
            return fat20001()

        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 2)
        self.assertEqual(inserted_ids(db), [1741418])
        self.assertEqual(db.have, 1741418)
        # workers==1：1 条成功后再连续 50 空洞即停，共 51 次，无第二块预取
        self.assertLessEqual(m.call_count, REFRESH_JOB_CAP)
        self.assertEqual(m.call_count, 1 + REFRESH_MISS_STREAK)

    def test_equal_success_exits_0(self):
        db = FakeDB(have=1741417)
        pages = {1: {"data": [{"id": 1741417}]}}

        def fetch(vid, retries=4):
            if vid == 1741418:
                return ok_detail(vid)
            return fat20001()

        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=fetch):
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 0)
        self.assertEqual(inserted_ids(db), [1741418])

    def test_equal_error_exits_1_not_false_success(self):
        db = FakeDB(have=1741417)
        pages = {1: {"data": [{"id": 1741417}]}}

        def fetch(vid, retries=4):
            if vid == 1741418:
                return "上游1019"
            return ok_detail(vid)

        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=fetch):
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 1)
        self.assertEqual(inserted_ids(db), [])
        self.assertEqual(db.have, 1741417)

    def test_normal_increment_inserts_and_exits_0(self):
        # 缩小版 09:27 窗口：库 100，hint 105
        db = FakeDB(have=100)
        pages = {1: {"data": [{"id": 105}, {"id": 104}]}}

        def fetch(vid, retries=4):
            if 101 <= vid <= 105:
                return ok_detail(vid)
            return fat20001()

        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 0)
        self.assertEqual(inserted_ids(db), [101, 102, 103, 104, 105])
        self.assertEqual(db.have, 105)
        # 区间 5 + hint 之后有界上探，连续空洞即停，总次数受 2000 限制
        self.assertLessEqual(m.call_count, REFRESH_JOB_CAP)
        self.assertLessEqual(m.call_count, 5 + 50)

    def test_normal_err_exits_1_and_skips_tail_probe(self):
        db = FakeDB(have=100)
        pages = {1: {"data": [{"id": 103}]}}

        def fetch(vid, retries=4):
            if vid == 102:
                return "超时"
            return ok_detail(vid)

        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 1)
        self.assertIn(101, db.ids)
        self.assertNotIn(102, db.ids)
        # workers=1 在失败点停止，不越过断点去写 103，也不再上探
        self.assertNotIn(103, db.ids)
        self.assertEqual([c.args[0] for c in m.call_args_list], [101, 102])

    def test_empty_search_with_existing_db_exits_2_and_probes(self):
        db = FakeDB(have=1741417)
        with patch.object(crawler, "api_call", return_value={"data": []}), \
             patch.object(crawler, "_fetch_detail", side_effect=lambda vid, retries=4: fat20001()) as m:
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 2)
        self.assertEqual(inserted_ids(db), [])
        self.assertEqual(db.have, 1741417)
        self.assertGreater(m.call_count, 0)
        self.assertLessEqual(m.call_count, 50)

    def test_search_exception_exits_2_not_success(self):
        db = FakeDB(have=1741417)
        with patch.object(crawler, "get_search_id_hint", side_effect=RuntimeError("连搜索接口失败")), \
             patch.object(crawler, "_fetch_detail", side_effect=lambda vid, retries=4: fat20001()):
            code = crawl_refresh(db, workers=1, batch=20)
        self.assertEqual(code, 2)

    def test_equal_all_ok_fetch_count_capped_at_2000(self):
        db = FakeDB(have=50)
        pages = {1: {"data": [{"id": 50}]}}
        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=lambda vid, retries=4: ok_detail(vid)) as m:
            code = crawl_refresh(db, workers=4, batch=200)
        self.assertEqual(code, 0)
        self.assertEqual(m.call_count, REFRESH_JOB_CAP)
        self.assertEqual(len(db.ids), REFRESH_JOB_CAP)
        self.assertEqual(db.have, 50 + REFRESH_JOB_CAP)

    def test_limit_caps_normal_and_skips_tail(self):
        """生产定制：预算 = min(cap, limit)，且不得再用 2000 去做尾部上探。"""
        db = FakeDB(have=100)
        pages = {1: {"data": [{"id": 500}]}}

        def fetch(vid, retries=4):
            return ok_detail(vid)

        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            code = crawl_refresh(db, workers=1, batch=20, limit=5)
        self.assertEqual(code, 0)
        self.assertEqual(inserted_ids(db), [101, 102, 103, 104, 105])
        self.assertEqual(m.call_count, 5)
        self.assertEqual(db.have, 105)

    def test_invalid_limit_exits_1_without_fetch(self):
        db = FakeDB(have=100)
        pages = {1: {"data": [{"id": 500}]}}
        with patch.object(crawler, "api_call", side_effect=self._search(pages)), \
             patch.object(crawler, "_fetch_detail", side_effect=ok_detail) as m:
            code = crawl_refresh(db, workers=1, batch=20, limit=0)
        self.assertEqual(code, 1)
        self.assertEqual(inserted_ids(db), [])
        self.assertEqual(m.call_count, 0)


    def test_serial_fail_stops_without_prefetch_and_flushes_immediately(self):
        """workers==1：每个成功 id 在下一次 fetch 前已入库；str 失败后不再请求后续 id。"""
        db = FakeDB(have=9)
        seen = []

        def fetch(vid, retries=4):
            seen.append(("fetch", vid, frozenset(db.ids)))
            if vid in (10, 11):
                return ok_detail(vid)
            if vid == 12:
                return "上游1062"
            return ok_detail(vid)

        orig_insert = db.insert_details_batch

        def tracking_insert(rows):
            seen.append(("flush", [((r.get("result") or {}).get("vod") or {}).get("id") for r in rows]))
            return orig_insert(rows)

        db.insert_details_batch = tracking_insert

        with patch.object(crawler, "_fetch_detail", side_effect=fetch) as m:
            with self.assertRaises(RuntimeError) as ctx:
                _probe_upward(
                    db, start=10, cap=40, miss_streak=50,
                    workers=1, batch=200,
                )
        self.assertIn("12", str(ctx.exception))
        self.assertEqual([c.args[0] for c in m.call_args_list], [10, 11, 12])
        self.assertNotIn(13, [c.args[0] for c in m.call_args_list])
        self.assertEqual(inserted_ids(db), [10, 11])
        self.assertEqual(db.have, 11)
        # 10 在 fetch(11) 之前已入库
        fetch_11 = next(s for s in seen if s[0] == "fetch" and s[1] == 11)
        self.assertIn(10, fetch_11[2])
        fetch_12 = next(s for s in seen if s[0] == "fetch" and s[1] == 12)
        self.assertIn(11, fetch_12[2])
        self.assertEqual([s for s in seen if s[0] == "flush"], [("flush", [10]), ("flush", [11])])

    def test_serial_gaps_do_not_prefetch_beyond_streak(self):
        db = FakeDB(have=0)
        with patch.object(crawler, "_fetch_detail", side_effect=lambda vid, retries=4: fat20001()) as m:
            ok, gap, err, reason = _probe_upward(
                db, start=1, cap=200, miss_streak=5,
                workers=1, batch=1,
            )
        self.assertEqual(reason, "miss_streak")
        self.assertEqual(m.call_count, 5)
        self.assertEqual(inserted_ids(db), [])



if __name__ == "__main__":
    unittest.main()
