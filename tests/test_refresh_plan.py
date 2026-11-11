"""定向回归：增量规划与有界上探。不连库、不打上游、不改水位。"""
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from refresh_plan import (
    REFRESH_JOB_CAP,
    REFRESH_MISS_STREAK,
    REFRESH_BACKFILL_GAP,
    MODE_EMPTY_DB,
    MODE_NORMAL,
    MODE_EQUAL,
    MODE_SEARCH_BEHIND,
    MODE_GAP_TOO_LARGE,
    plan_refresh,
    parse_search_page_ids,
    search_hint_max,
    classify_detail_kind,
    ProbeCursor,
)


class ParseSearchTests(unittest.TestCase):
    def test_first_item_need_not_be_max(self):
        payload = {"data": [{"id": 1665643}, {"id": 1741418}, {"id": 1741000}]}
        ids = parse_search_page_ids(payload)
        self.assertEqual(ids, [1665643, 1741418, 1741000])
        self.assertEqual(search_hint_max([ids]), 1741418)

    def test_empty_and_bad_payload(self):
        self.assertEqual(parse_search_page_ids({}), [])
        self.assertEqual(parse_search_page_ids({"data": []}), [])
        self.assertEqual(parse_search_page_ids(None), [])
        self.assertEqual(search_hint_max([[], []]), 0)


class PlanRefreshTests(unittest.TestCase):
    def test_empty_db(self):
        p = plan_refresh(0, 1741870)
        self.assertEqual(p.mode, MODE_EMPTY_DB)
        self.assertEqual(p.anomaly_exit, 0)
        self.assertFalse(p.alert)

    def test_normal_increment(self):
        # 生产 11/11 09:27：源 1741870、库 1741317
        p = plan_refresh(1741317, 1741870)
        self.assertEqual(p.mode, MODE_NORMAL)
        self.assertEqual(p.start, 1741318)
        self.assertEqual(p.end, 1741870)
        self.assertFalse(p.truncated)
        self.assertEqual(p.anomaly_exit, 0)
        self.assertEqual(len(range(p.start, p.end + 1)), 553)
        # 不会从更小的搜索 id 往下扫
        self.assertGreater(p.start, p.have)

    def test_normal_truncated_at_cap(self):
        have = 1000
        hint = have + REFRESH_JOB_CAP + 100
        p = plan_refresh(have, hint)
        self.assertEqual(p.mode, MODE_NORMAL)
        self.assertTrue(p.truncated)
        self.assertEqual(p.end, have + REFRESH_JOB_CAP)
        self.assertLessEqual(p.end - have, REFRESH_JOB_CAP)

    def test_gap_too_large_not_unbounded_range(self):
        have = 100
        hint = have + REFRESH_BACKFILL_GAP + 1
        p = plan_refresh(have, hint)
        self.assertEqual(p.mode, MODE_GAP_TOO_LARGE)
        self.assertEqual(p.start, 0)
        self.assertEqual(p.end, 0)
        self.assertIn("告警", p.alert)
        self.assertIn("backfill", p.alert)

    def test_equal_does_not_return_silent_no_range(self):
        p = plan_refresh(1741417, 1741417)
        self.assertEqual(p.mode, MODE_EQUAL)
        self.assertEqual(p.start, 1741418)
        self.assertEqual(p.cap, REFRESH_JOB_CAP)
        self.assertEqual(p.anomaly_exit, 0)
        # equal 走有界上探，而不是「无新数据」空返回
        self.assertGreater(p.cap, 0)

    def test_search_behind_alerts_and_is_not_success_noop(self):
        # 生产 11/11 11:27 与 13:27：搜索 1665643 < 库 1741417
        p = plan_refresh(1741417, 1665643)
        self.assertEqual(p.mode, MODE_SEARCH_BEHIND)
        self.assertEqual(p.start, 1741418)
        self.assertEqual(p.cap, REFRESH_JOB_CAP)
        self.assertLessEqual(p.cap, REFRESH_JOB_CAP)
        self.assertEqual(p.anomaly_exit, 2)
        self.assertIn("告警", p.alert)
        self.assertIn("1665643", p.alert)
        self.assertIn("1741417", p.alert)
        self.assertIn("不视为无新数据", p.alert)
        # 绝不规划从搜索小 id 扫到库水位（那会近 8 万次）
        self.assertNotEqual(p.start, 1665644)
        self.assertGreater(p.start, 1741417)

    def test_hint_zero_with_existing_db_is_behind(self):
        p = plan_refresh(1741417, 0)
        self.assertEqual(p.mode, MODE_SEARCH_BEHIND)
        self.assertEqual(p.anomaly_exit, 2)
        self.assertTrue(p.alert)


class ClassifyTests(unittest.TestCase):
    def test_ok_gap_err(self):
        self.assertEqual(classify_detail_kind({"result": {"vod": {"id": 1}}}), "ok")
        self.assertEqual(classify_detail_kind({"code": 0, "result": {}}), "gap")
        self.assertEqual(classify_detail_kind({"code": "FAT20001", "message": "已删除"}), "gap")
        self.assertEqual(classify_detail_kind("上游1019"), "err")
        self.assertEqual(classify_detail_kind({"code": 1019, "result": {}}), "err")
        self.assertEqual(classify_detail_kind({"code": 1034}), "err")


class ProbeCursorTests(unittest.TestCase):
    def test_equal_all_gaps_stops_at_streak_not_cap(self):
        cur = ProbeCursor(start=1741418, cap=REFRESH_JOB_CAP, miss_streak=REFRESH_MISS_STREAK)
        attempts = 0
        while True:
            chunk = cur.next_chunk(50)
            if not chunk:
                break
            for _ in chunk:
                attempts += 1
                cur.observe("gap")
                if cur.stop_reason:
                    break
        self.assertEqual(cur.stop_reason, "miss_streak")
        self.assertEqual(attempts, REFRESH_MISS_STREAK)
        self.assertLess(attempts, REFRESH_JOB_CAP)
        self.assertEqual(cur.found, 0)

    def test_finds_then_gaps_resets_streak(self):
        cur = ProbeCursor(start=10, cap=200, miss_streak=5)
        # 3 空洞 + 1 成功 + 5 空洞 -> 停，共 9 次
        kinds = ["gap", "gap", "gap", "ok", "gap", "gap", "gap", "gap", "gap"]
        for k in kinds:
            cur.observe(k)
        self.assertEqual(cur.stop_reason, "miss_streak")
        self.assertEqual(cur.attempted, 9)
        self.assertEqual(cur.found, 1)

    def test_error_stops_immediately(self):
        cur = ProbeCursor(start=10, cap=2000, miss_streak=50)
        cur.observe("ok")
        cur.observe("err")
        self.assertEqual(cur.stop_reason, "error")
        self.assertEqual(cur.attempted, 2)
        self.assertIsNone(cur.next_chunk() or None)
        self.assertEqual(cur.next_chunk(), [])

    def test_cap_bounds_walk(self):
        cur = ProbeCursor(start=1, cap=10, miss_streak=50)
        ids = []
        while True:
            chunk = cur.next_chunk(50)
            if not chunk:
                break
            for i in chunk:
                ids.append(i)
                cur.observe("ok")
        self.assertEqual(len(ids), 10)
        self.assertEqual(ids, list(range(1, 11)))
        self.assertEqual(cur.stop_reason, "cap")
        self.assertEqual(cur.found, 10)

    def test_chunk_never_exceeds_remaining_cap(self):
        cur = ProbeCursor(start=100, cap=7, miss_streak=50)
        first = cur.next_chunk(50)
        self.assertEqual(len(first), 7)
        self.assertEqual(first[0], 100)
        self.assertEqual(first[-1], 106)


if __name__ == "__main__":
    unittest.main()
