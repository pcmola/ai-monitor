#!/usr/bin/env python3
"""Export allowlisted usage events, never conversation or authentication data.

No source is accessed until main()/export_usage() is called. The default source
is CODEX_HOME/sessions or ~/.codex/sessions. Exit 3 means no usable events;
an existing destination is preserved in that case and on any export failure.
usage_source is a SHA-256 of the relative source path, not a session identifier.
"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile


MAX_LINE_BYTES = 1024 * 1024
MAX_OUTPUT_BYTES = 32 * 1024 * 1024
MAX_COUNTER = (1 << 63) - 1
TIMESTAMP = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})\Z"
)
PLAN = re.compile(r"[a-z][a-z0-9_-]{0,31}\Z")


class ExportError(Exception):
    """An export could not be completed; messages contain no source data."""


def default_sessions():
    codex_root = os.environ.get("CODEX_HOME")
    return (Path(codex_root).expanduser() if codex_root else Path.home() / ".codex") / "sessions"


def default_output():
    return Path(__file__).resolve().parent / ".local-import" / "codex-usage.jsonl"


def bounded_lines(stream):
    """Discard oversized lines through their newline without unbounded reads."""
    first = True
    while True:
        line = stream.readline(MAX_LINE_BYTES + 1)
        if not line:
            return
        oversized = len(line.rstrip(b"\r\n")) > MAX_LINE_BYTES
        if not line.endswith(b"\n") and len(line) > MAX_LINE_BYTES:
            oversized = True
            while line and not line.endswith(b"\n"):
                line = stream.readline(MAX_LINE_BYTES + 1)
        if not oversized:
            if first:
                line = line.removeprefix(b"\xef\xbb\xbf")
            yield line
        first = False


def nonnegative_integer(value, maximum=MAX_COUNTER):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if isinstance(value, float) and (not math.isfinite(value) or not value.is_integer()):
        return None
    return int(value) if 0 <= value <= maximum else None


def reject_constant(_value):
    raise ValueError("Invalid JSON number.")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field.")
        result[key] = value
    return result


def bounded_nesting(text):
    depth = 0
    quoted = escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > 64:
                return False
        elif char in "]}":
            depth -= 1
            if depth < 0:
                return False
    return not quoted and depth == 0


def sanitize_event(line, now, usage_source):
    """Return (timestamp, new allowlisted row), or None for unusable input."""
    try:
        text = line.decode("utf-8")
        if not bounded_nesting(text):
            return None
        row = json.loads(text, parse_constant=reject_constant, object_pairs_hook=unique_object)
        if not isinstance(row, dict):
            return None
        payload = row.get("payload")
        if not isinstance(payload, dict) or payload.get("type") != "token_count":
            return None
        timestamp = row.get("timestamp")
        if not isinstance(timestamp, str) or not TIMESTAMP.fullmatch(timestamp):
            return None
        time = datetime.fromisoformat(timestamp.replace("Z", "+00:00")).astimezone(timezone.utc)
        if time > now:
            return None
    except (ValueError, UnicodeError, RecursionError, OverflowError):
        return None

    clean = {"type": "token_count"}
    limits = payload.get("rate_limits")
    if isinstance(limits, dict):
        windows = {}
        for name in ("primary", "secondary"):
            window = limits.get(name)
            if not isinstance(window, dict):
                continue
            used = window.get("used_percent")
            minutes = nonnegative_integer(window.get("window_minutes"))
            if (isinstance(used, bool) or not isinstance(used, (int, float))
                    or not 0 <= used <= 100 or not minutes):
                continue
            reset = window.get("resets_at")
            if reset is not None:
                reset = nonnegative_integer(reset, 253402300799)
                if reset is None:
                    continue
            windows[name] = {"used_percent": used, "window_minutes": minutes, "resets_at": reset}
        if windows:
            plan = limits.get("plan_type")
            if isinstance(plan, str) and PLAN.fullmatch(plan):
                windows["plan_type"] = plan
            clean["rate_limits"] = windows

    info = payload.get("info")
    if isinstance(info, dict):
        cumulative = info.get("total_token_usage")
        total = nonnegative_integer(cumulative.get("total_tokens")) if isinstance(cumulative, dict) else None
        if total is not None:
            clean_info = {"total_token_usage": {"total_tokens": total}}
            last = info.get("last_token_usage")
            if last is None:
                clean["info"] = clean_info
            elif isinstance(last, dict):
                turn_total = nonnegative_integer(last.get("total_tokens", 0))
                output = nonnegative_integer(last.get("output_tokens", 0))
                if turn_total is not None and output is not None and output <= turn_total:
                    clean_info["last_token_usage"] = {"total_tokens": turn_total, "output_tokens": output}
                    clean["info"] = clean_info

    if len(clean) == 1:
        return None
    return time, {
        "timestamp": time.isoformat().replace("+00:00", "Z"),
        "type": "event_msg",
        "usage_source": usage_source,
        "payload": clean,
    }


def source_files(sessions, output):
    # Never follow file/directory symlinks into unrelated data, or read our output.
    if sessions.is_symlink() or not sessions.is_dir():
        return
    def unreadable(_error):
        raise ExportError("Unable to enumerate usage sources.") from None
    for directory, subdirs, files in os.walk(sessions, followlinks=False, onerror=unreadable):
        subdirs[:] = sorted(name for name in subdirs if not (Path(directory) / name).is_symlink())
        for name in sorted(files):
            path = Path(directory) / name
            if path.suffix.lower() == ".jsonl" and not path.is_symlink() and path.resolve() != output:
                yield path


def export_usage(sessions, output, *, now=None):
    """Atomically publish at most 32 MiB. Return the number of exported rows.

    Preserve input order per source for cumulative counters. Keep recent events
    and the latest usable limits even when only an older snapshot is available;
    an older fallback carries no historical token metrics. Ties prefer the first
    encountered snapshot. Future events do not affect either selection.
    """
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ExportError("An explicit time zone is required.")
    now = now.astimezone(timezone.utc)
    cutoff = now - timedelta(days=7)
    sessions = Path(sessions).expanduser().absolute()
    output = Path(output).expanduser().resolve()
    temporary = None
    stream = None
    count = size = 0
    latest = None

    def write(row):
        nonlocal temporary, stream, count, size
        data = (json.dumps(row, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n").encode("utf-8")
        if len(data) - 1 > MAX_LINE_BYTES or size + len(data) > MAX_OUTPUT_BYTES:
            raise ExportError("Sanitized usage exceeds the export size limit.")
        if stream is None:
            output.parent.mkdir(parents=True, exist_ok=True)
            descriptor, name = tempfile.mkstemp(prefix=".codex-usage-", suffix=".tmp", dir=output.parent)
            temporary = Path(name)
            stream = os.fdopen(descriptor, "wb")
        stream.write(data)
        size += len(data)
        count += 1

    try:
        for path in source_files(sessions, output):
            source = hashlib.sha256(path.relative_to(sessions).as_posix().encode("utf-8", "surrogatepass")).hexdigest()
            with path.open("rb") as source_stream:
                for line in bounded_lines(source_stream):
                    event = sanitize_event(line, now, source)
                    if event is None:
                        continue
                    time, row = event
                    if "rate_limits" in row["payload"] and (latest is None or time > latest[0]):
                        latest = event
                    if time >= cutoff:
                        write(row)
        if latest is not None and latest[0] < cutoff:
            row = latest[1]
            row["payload"].pop("info", None)
            write(row)
        if stream is not None:
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
            stream = None
            os.replace(temporary, output)
            temporary = None
        return count
    finally:
        if stream is not None:
            stream.close()
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Export sanitized Codex usage for an owned debug Android app.")
    parser.add_argument("--sessions", type=Path, default=default_sessions(), help="Source sessions directory (recursive JSONL only).")
    parser.add_argument("--output", type=Path, default=default_output(), help="Atomic sanitized JSONL destination.")
    args = parser.parse_args(argv)
    try:
        count = export_usage(args.sessions, args.output)
    except Exception:
        # Parser/OS exceptions can contain source text, paths, or other secrets.
        print("Codex usage export failed; no new export was published.", file=sys.stderr)
        return 1
    if not count:
        print("No usable Codex usage events; no new export was published.")
        return 3
    print("Sanitized Codex usage export ready.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
