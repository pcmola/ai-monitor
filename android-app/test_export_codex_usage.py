"""Synthetic-only tests. Never access default session/credential locations or adb."""

from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("export_codex_usage", Path(__file__).with_name("export-codex-usage.py"))
exporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(exporter)
NOW = datetime(2026, 10, 24, 12, tzinfo=timezone.utc)
SECRET = "INJECTION_SECRET_DO_NOT_EXPORT"


def event(age=timedelta(hours=1), *, limits=True, info=True, total=100):
    payload = {"type": "token_count"}
    if limits:
        payload["rate_limits"] = {
            "plan_type": "plus",
            "primary": {"used_percent": 25.5, "window_minutes": 300, "resets_at": 1792857600},
            "secondary": {"used_percent": 50, "window_minutes": 10080, "resets_at": None},
        }
    if info:
        payload["info"] = {
            "total_token_usage": {"total_tokens": total},
            "last_token_usage": {"total_tokens": 30, "output_tokens": 10},
        }
    return {"timestamp": (NOW - age).isoformat(), "type": "event_msg", "payload": payload}


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sessions = self.root / "synthetic-sessions"
        self.sessions.mkdir()
        self.output = self.root / "import" / "codex-usage.jsonl"

    def source(self, rows, name="day/session.jsonl"):
        path = self.sessions / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("wb") as stream:
            for row in rows:
                stream.write(row if isinstance(row, bytes) else (json.dumps(row) + "\n").encode())
        return path

    def export(self):
        return exporter.export_usage(self.sessions, self.output, now=NOW)

    def rows(self):
        return [json.loads(line) for line in self.output.read_text().splitlines()]

    def test_exact_allowlist_strips_secrets_at_every_level(self):
        row = event()
        row.update({"session_metadata": SECRET, "account_id": SECRET, "usage_source": SECRET, "message": SECRET, "type": SECRET})
        payload = row["payload"]
        payload.update({"message": SECRET, "tool_calls": [SECRET], "accessToken": SECRET, "auth": SECRET})
        payload["rate_limits"]["account_id"] = SECRET
        payload["rate_limits"]["primary"]["refreshToken"] = SECRET
        payload["info"]["model_context_window"] = SECRET
        payload["info"]["total_token_usage"]["input_tokens"] = SECRET
        payload["info"]["last_token_usage"]["reasoning_output_tokens"] = SECRET
        self.source([row])
        self.assertEqual(1, self.export())
        clean = self.rows()[0]
        self.assertEqual({"timestamp", "type", "usage_source", "payload"}, set(clean))
        self.assertEqual("event_msg", clean["type"])
        self.assertEqual(event()["payload"], clean["payload"])
        self.assertNotIn(SECRET, self.output.read_text())
        self.assertNotIn("session.jsonl", self.output.read_text())

    def test_source_hash_is_stable_distinct_and_replaces_injected_value(self):
        a = self.source([event(total=100), event(total=100)], "a.jsonl")
        self.source([event(total=100)], "nested/b.jsonl")
        self.assertEqual(3, self.export())
        sources = [row["usage_source"] for row in self.rows()]
        self.assertEqual(sources[0], sources[1])
        self.assertNotEqual(sources[0], sources[2])
        self.assertEqual(hashlib.sha256(a.relative_to(self.sessions).as_posix().encode()).hexdigest(), sources[0])
        self.assertTrue(all(len(source) == 64 and set(source) <= set("0123456789abcdef") for source in sources))
        self.export()
        self.assertEqual(sources, [row["usage_source"] for row in self.rows()])

    def test_recent_inclusive_and_latest_older_limits_with_recent_metrics(self):
        self.source([
            event(timedelta(days=10)),
            event(timedelta(days=8)),
            event(timedelta(days=7), limits=False),
            event(timedelta(hours=1), limits=False),
            event(timedelta(seconds=-1)),
            event(timedelta(days=9), limits=False),
        ])
        self.assertEqual(3, self.export())
        rows = self.rows()
        self.assertEqual(["2026-10-17T12:00:00Z", "2026-10-24T11:00:00Z", "2026-10-16T12:00:00Z"], [row["timestamp"] for row in rows])
        self.assertNotIn("info", rows[-1]["payload"])

    def test_only_latest_older_limits_without_recent_data(self):
        self.source([event(timedelta(days=20)), event(timedelta(days=8)), event(timedelta(days=9))])
        self.assertEqual(1, self.export())
        self.assertEqual("2026-10-16T12:00:00Z", self.rows()[0]["timestamp"])
        self.assertNotIn("info", self.rows()[0]["payload"])

    def test_recent_limits_make_older_fallback_unnecessary(self):
        self.source([event(timedelta(days=8)), event(), event(timedelta(hours=2))])
        self.assertEqual(2, self.export())
        self.assertEqual("2026-10-24T11:00:00Z", self.rows()[0]["timestamp"])

    def test_latest_snapshot_across_recursive_files_ignores_future(self):
        self.source([event(timedelta(days=9))], "one/a.jsonl")
        self.source([event(timedelta(days=8)), event(timedelta(days=-1))], "two/b.jsonl")
        self.assertEqual(1, self.export())
        self.assertEqual("2026-10-16T12:00:00Z", self.rows()[0]["timestamp"])

    def test_malformed_unrelated_and_future_do_not_create_output(self):
        invalid = [b"{broken secret\n", b"\xff\n", [], {},
                   {"timestamp": NOW.isoformat(), "payload": {"type": "user_message", "text": SECRET}},
                   {"timestamp": NOW.isoformat(), "payload": {"type": "function_call", "arguments": SECRET}},
                   {"type": "session_meta", "payload": {"id": SECRET}},
                   event(timedelta(seconds=-1)), event(limits=False, info=False)]
        for timestamp in ("bad", "2026-10-24T11:00:00", "2026-02-30T11:00:00Z", 123, None):
            row = event()
            row["timestamp"] = timestamp
            invalid.append(row)
        self.source(invalid)
        self.assertEqual(0, self.export())
        self.assertFalse(self.output.parent.exists())

    def test_no_valid_input_preserves_existing_output(self):
        self.output.parent.mkdir()
        self.output.write_bytes(b"previous sanitized export\n")
        self.source([b"not JSON\n"])
        self.assertEqual(0, self.export())
        self.assertEqual(b"previous sanitized export\n", self.output.read_bytes())

    def test_old_metrics_without_limits_do_not_create_output(self):
        self.source([event(timedelta(days=8), limits=False)])
        self.assertEqual(0, self.export())
        self.assertFalse(self.output.exists())

    def test_missing_and_empty_sources(self):
        self.assertEqual(0, self.export())
        self.assertEqual(0, exporter.export_usage(self.root / "absent", self.output, now=NOW))
        self.assertFalse(self.output.exists())

    def test_numeric_validation_and_non_object_fields(self):
        rows = []
        for bad in (True, -1, "20", {}, [], float("nan"), float("inf"), 1 << 70):
            row = event(limits=False)
            row["payload"]["info"]["total_token_usage"]["total_tokens"] = bad
            rows.append(row)
        for bad in (True, -1, "20", {}, [], float("nan"), float("inf"), 101):
            row = event(info=False)
            row["payload"]["rate_limits"].pop("secondary")
            row["payload"]["rate_limits"]["primary"]["used_percent"] = bad
            rows.append(row)
        for field in ("info", "rate_limits"):
            row = event(limits=field == "rate_limits", info=field == "info")
            row["payload"][field] = SECRET
            rows.append(row)
        self.source(rows + [event()])
        self.assertEqual(1, self.export())

    def test_invalid_window_and_turn_fields_cannot_become_fake_metrics(self):
        rows = []
        for field, value in (("window_minutes", 0), ("window_minutes", True), ("resets_at", SECRET), ("resets_at", -1)):
            row = event(info=False)
            row["payload"]["rate_limits"].pop("secondary")
            row["payload"]["rate_limits"]["primary"][field] = value
            rows.append(row)
        for last in ({"total_tokens": 10, "output_tokens": 11}, {"total_tokens": -1}, SECRET):
            row = event(limits=False)
            row["payload"]["info"]["last_token_usage"] = last
            rows.append(row)
        self.source(rows)
        self.assertEqual(0, self.export())

    def test_zero_counters_and_missing_last_are_usable(self):
        row = event(limits=False, total=0)
        row["payload"]["info"].pop("last_token_usage")
        self.source([row])
        self.assertEqual(1, self.export())
        self.assertEqual({"total_token_usage": {"total_tokens": 0}}, self.rows()[0]["payload"]["info"])

    def test_oversized_line_is_drained_before_next_event(self):
        oversized = event()
        oversized["payload"]["message"] = SECRET * (exporter.MAX_LINE_BYTES // len(SECRET) + 1)
        self.source([oversized, event(), b"x" * (exporter.MAX_LINE_BYTES + 10)])
        self.assertEqual(1, self.export())
        self.assertLess(self.output.stat().st_size, exporter.MAX_LINE_BYTES)

    def test_bom_crlf_and_timezone_are_supported(self):
        row = event()
        row["timestamp"] = "2026-10-24T20:00:00+09:00"
        self.source([b"\xef\xbb\xbf" + json.dumps(row).encode() + b"\r\n"])
        self.assertEqual(1, self.export())
        self.assertEqual("2026-10-24T11:00:00Z", self.rows()[0]["timestamp"])

    def test_deep_nesting_duplicate_keys_and_trailing_garbage_skipped(self):
        valid = json.dumps(event()).encode()
        self.source([b"[" * 2000 + b"]" * 2000 + b"\n", valid + b" garbage\n",
                     b'{"payload":{},"payload":' + json.dumps(event()["payload"]).encode() + b'}\n', event()])
        self.assertEqual(1, self.export())

    def test_only_jsonl_and_no_output_self_read(self):
        self.source([event()], "skip.json")
        self.source([event()], "nested/keep.jsonl")
        self.output = self.sessions / "generated.jsonl"
        self.assertEqual(1, self.export())
        self.assertEqual(1, self.export())

    def test_output_cap_is_atomic_and_cleans_temporary(self):
        self.source([event(), event()])
        self.output.parent.mkdir()
        self.output.write_bytes(b"previous sanitized export\n")
        one_row = exporter.sanitize_event(json.dumps(event()).encode(), NOW, "0" * 64)[1]
        one_size = len((json.dumps(one_row, separators=(",", ":")) + "\n").encode())
        with patch.object(exporter, "MAX_OUTPUT_BYTES", one_size):
            with self.assertRaises(exporter.ExportError):
                self.export()
        self.assertEqual(b"previous sanitized export\n", self.output.read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_replace_failure_preserves_previous_export_and_cleans_temporary(self):
        self.source([event()])
        self.output.parent.mkdir()
        self.output.write_bytes(b"previous\n")
        with patch.object(exporter.os, "replace", side_effect=OSError(SECRET)):
            with self.assertRaises(OSError):
                self.export()
        self.assertEqual(b"previous\n", self.output.read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_cli_errors_do_not_print_source_or_raw_exception(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(exporter, "export_usage", side_effect=OSError(SECRET)), redirect_stdout(stdout), redirect_stderr(stderr):
            result = exporter.main(["--sessions", str(self.sessions), "--output", str(self.output)])
        self.assertEqual(1, result)
        self.assertNotIn(SECRET, stdout.getvalue() + stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())

    def test_cli_exit_codes_use_explicit_synthetic_paths(self):
        args = ["--sessions", str(self.sessions), "--output", str(self.output)]
        with redirect_stdout(io.StringIO()):
            self.assertEqual(3, exporter.main(args))
            self.source([event()])
        with redirect_stdout(io.StringIO()), patch.object(exporter, "datetime") as clock:
            clock.now.return_value = NOW
            clock.fromisoformat.side_effect = datetime.fromisoformat
            self.assertEqual(0, exporter.main(args))

    def test_defaults_are_computed_without_reading_any_real_source(self):
        with patch.dict(os.environ, {"CODEX_HOME": str(self.root / "custom-codex")}):
            self.assertEqual(self.root / "custom-codex" / "sessions", exporter.default_sessions())
        with patch.dict(os.environ, {}, clear=True), patch.object(exporter.Path, "home", return_value=self.root):
            self.assertEqual(self.root / ".codex" / "sessions", exporter.default_sessions())
        self.assertEqual("codex-usage.jsonl", exporter.default_output().name)
        self.assertEqual(".local-import", exporter.default_output().parent.name)


if __name__ == "__main__":
    unittest.main()
