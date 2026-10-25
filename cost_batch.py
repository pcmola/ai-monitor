"""Fetch recent Azure costs into the local SQLite database.

Run once a day from Windows Task Scheduler:
    python cost_batch.py

The default refreshes the latest 7 days because Azure Cost Management data can
arrive late. Existing rows are updated with UPSERT, so rerunning is safe.
"""

from __future__ import annotations

import argparse
import sqlite3
from datetime import date, datetime, timedelta

import cost_monitor


def store(result: dict, group: str, subscription_id: str):
    now = datetime.now().isoformat(timespec="seconds")
    with sqlite3.connect(cost_monitor.DB_PATH) as conn:
        for row in result["rows"]:
            conn.execute(
                """INSERT INTO daily_cost
                   (usage_date, group_type, group_name, currency, cost, subscription_id, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(usage_date, group_type, group_name, subscription_id)
                   DO UPDATE SET currency=excluded.currency, cost=excluded.cost, updated_at=excluded.updated_at""",
                (row["date"], group, row["group"], result["currency"], row["cost"], subscription_id, now),
            )
        conn.execute(
            """INSERT OR REPLACE INTO batch_runs
               (group_type, from_date, to_date, subscription_id, updated_at)
               VALUES (?, ?, ?, ?, ?)""",
            (group, result["from"], result["to"], subscription_id, now),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Azure 비용을 SQLite에 저장하는 일 배치")
    parser.add_argument("--days", type=int, default=None, help="최근 며칠을 수집할지 (기본: 7)")
    parser.add_argument("--from-date", help="수집 시작일 (YYYY-MM-DD). 지정하면 오늘까지 수집")
    parser.add_argument("--subscription", default=None, help="구독 이름 또는 ID")
    args = parser.parse_args()
    if args.days is not None and args.from_date:
        parser.error("--days와 --from-date는 함께 사용할 수 없습니다.")
    if args.days is not None and args.days < 1:
        parser.error("--days는 1 이상이어야 합니다.")

    sub = cost_monitor.az_json("account", "show", *( ["--subscription", args.subscription] if args.subscription else [] ))
    cost_monitor.SUBSCRIPTION.update(id=sub["id"], name=sub["name"])
    cost_monitor.init_db()
    end = date.today()
    if args.from_date:
        try:
            start = date.fromisoformat(args.from_date)
        except ValueError as exc:
            parser.error("--from-date는 YYYY-MM-DD 형식이어야 합니다.")
    else:
        days = args.days or 7
        start = end - timedelta(days=days - 1)
    if start > end:
        parser.error("--from-date는 오늘보다 늦을 수 없습니다.")
    print(f"수집 기간: {start} ~ {end}")

    failures = []
    for group in cost_monitor.GROUPS:
        cursor = start
        while cursor <= end:
            chunk_end = min(cursor + timedelta(days=cost_monitor.MAX_QUERY_DAYS[group] - 1), end)
            try:
                result = cost_monitor.azure_fetch(cursor, chunk_end, group)
                store(result, group, sub["id"])
                print(f"[{group}] {cursor} ~ {chunk_end}: {len(result['rows'])}건 저장")
            except Exception as exc:
                failures.append(f"{group} ({cursor}~{chunk_end}): {exc}")
                print(f"[{group}] {cursor} ~ {chunk_end} 실패: {exc}")
            cursor = chunk_end + timedelta(days=1)

    if failures:
        print("일부 그룹 수집 실패:", "; ".join(failures))
        return 1
    print(f"완료: {cost_monitor.DB_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
