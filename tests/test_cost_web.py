"""python -m tests.test_cost_web: 실행 중인 비용 모니터 검사 (Playwright 필요)."""
import json
from datetime import date, timedelta
from urllib.parse import parse_qs, urlparse
from playwright.sync_api import expect, sync_playwright


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        try:
            page = browser.new_page()
            errors, calls = [], []
            page.on("pageerror", lambda error: errors.append(str(error)))
            def route_api(route):
                qs = parse_qs(urlparse(route.request.url).query)
                if qs.get("source") != ["azure"]:
                    route.continue_()
                    return
                calls.append(route.request.url)
                end = date.today() - timedelta(days=1)
                route.fulfill(content_type="application/json", body=json.dumps({
                    "rows": [], "currency": "KRW", "subscription": "test", "fetched": "test",
                    "from": qs.get("from", [str(end - timedelta(days=6))])[0],
                    "to": qs.get("to", [str(end)])[0], "today": str(date.today()),
                    "range": qs.get("range", ["last7"])[0], "days_in_month": 31}))
            page.route("**/api/cost?*", route_api)
            page.goto("http://cost-monitor.local", wait_until="networkidle")
            assert page.locator("#range button").all_text_contents() == ["이번 달", "최근 7일", "최근 30일", "지난 달"]
            page.locator("details summary").click()
            assert page.locator("details thead th").all_text_contents() == ["날짜", "합계", "항목", "비용"]
            first_row = page.locator("#daily tr").first
            expect(first_row.locator("td")).to_have_count(4)
            expect(first_row.locator("td").nth(3)).to_have_class("num")
            assert first_row.locator("td").nth(2).inner_text() != ""
            page.locator("#fromDate").fill("2026-01-01")
            page.locator("#toDate").fill("2026-10-24")
            page.locator("#toDate").press("Tab")
            expect(page.locator("#fromDate")).to_have_value("2026-01-01")
            with page.expect_response(lambda r: "/api/cost?" in r.url) as response:
                page.locator("#applyDates").click()
            assert "source=local" in response.value.url and "from=2026-01-01" in response.value.url
            page.wait_for_load_state("networkidle")
            page.locator('#source [data-v="azure"]').click()
            expect(page.locator("#err")).to_contain_text("최대 31일")
            assert not calls
            page.locator("#fromDate").fill("2026-10-01")
            page.locator("#toDate").fill("2026-10-31")
            page.locator("#applyDates").click()
            page.wait_for_load_state("networkidle")
            expect(page.locator("#meta")).to_contain_text("Azure API")
            # 빈 날짜도 4개 컬럼을 유지한다.
            expect(page.locator("#daily tr").first.locator("td")).to_have_count(4)
            assert len(calls) == 1
            page.locator("#toDate").fill("2026-11-01")
            page.locator("#applyDates").click()
            expect(page.locator("#err")).to_contain_text("최대 31일")
            assert len(calls) == 1
            page.locator('#range [data-v="last7"]').click()
            page.wait_for_load_state("networkidle")
            assert len(calls) == 2
            page.locator('#source [data-v="local"]').click()
            page.wait_for_load_state("networkidle")
            assert not errors, errors
            print("PASS: local unlimited, Azure 31-day allowed/32-day blocked, date inputs and presets")
        finally:
            browser.close()


if __name__ == "__main__":
    main()
