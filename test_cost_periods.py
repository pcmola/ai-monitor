"""Azure API는 최대 31일, 로컬 DB는 기간 제한 없이 조회한다."""
import io
import json
import gc
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

import cost_batch
import cost_monitor as monitor


class CostPeriodTests(unittest.TestCase):
    def test_presets(self):
        for key in ("month", "last7", "last30", "lastmonth"):
            start, end = monitor.period(key)
            self.assertGreaterEqual(end, start)

    def test_local_long_period_and_partial_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cost.db"
            monitor.init_db(path)
            with patch.object(monitor, "DB_PATH", path), patch.dict(monitor.SUBSCRIPTION, {"id": "test"}, clear=True):
                cost_batch.store({"from": "2026-01-01", "to": "2026-10-24", "currency": "KRW",
                                  "rows": [{"date": "2026-01-01", "group": "rg", "cost": 1},
                                           {"date": "2026-10-24", "group": "rg", "cost": 2}]}, "rg", "test")
                result = monitor.query("custom", "rg", "2026-01-01", "2026-10-31", "local")
                self.assertEqual((result["from"], result["to"]), ("2026-01-01", "2026-10-24"))
                self.assertEqual(sum(r["cost"] for r in result["rows"]), 3)
            # sqlite3 컨텍스트는 커밋만 하므로 임시 DB의 연결도 정리한다.
            gc.collect()

    def test_azure_32_days_rejected_before_cache_or_auth(self):
        with patch.object(monitor, "access_token") as token, patch.object(monitor, "urlopen") as request:
            for group in monitor.GROUPS:
                with self.subTest(group=group), self.assertRaisesRegex(ValueError, "31"):
                    monitor.query("custom", group, "2026-10-01", "2026-11-01", "azure")
            token.assert_not_called()
            request.assert_not_called()

    def test_azure_1_7_30_31_days_allowed(self):
        payload = json.dumps({"properties": {"columns": [], "rows": []}}).encode()
        for days in (1, 7, 30, 31):
            for group in monitor.GROUPS:
                start = date(2026, 10, 1)
                end = start + timedelta(days=days - 1)
                with self.subTest(days=days, group=group), \
                        patch.object(monitor, "access_token", return_value="test"), \
                        patch.object(monitor, "urlopen", return_value=io.BytesIO(payload)) as request, \
                        patch.dict(monitor.SUBSCRIPTION, {"id": "test", "name": "test"}, clear=True), \
                        patch.dict(monitor._cache, {}, clear=True):
                    self.assertEqual(monitor.query("custom", group, str(start), str(end), "azure")["to"], str(end))
                    request.assert_called_once()

    def test_invalid_dates(self):
        for start, end in [(None, None), ("bad", "2026-10-24"), ("2026-10-24", "2026-10-01")]:
            with self.subTest(start=start), self.assertRaises(ValueError):
                monitor.period("custom", start, end)

    def test_batch_seven_days_and_long_backfill(self):
        for args, spans in [(["--days", "7"], [7]),
                            (["--from-date", str(date.today() - timedelta(days=64))], [31, 31, 3])]:
            with self.subTest(args=args), patch("sys.argv", ["cost_batch.py", *args]), \
                    patch.object(monitor, "az_json", return_value={"id": "test", "name": "test"}), \
                    patch.object(monitor, "init_db"), \
                    patch.object(monitor, "azure_fetch", return_value={"rows": []}) as fetch, \
                    patch.object(cost_batch, "store"), \
                    patch.dict(monitor.SUBSCRIPTION, {}, clear=True), patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(cost_batch.main(), 0)
                self.assertEqual(fetch.call_count, 3 * len(spans))
                self.assertEqual([(c.args[1] - c.args[0]).days + 1 for c in fetch.call_args_list[:len(spans)]], spans)


if __name__ == "__main__":
    unittest.main()
