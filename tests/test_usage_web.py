"""Tavily 카드 표시 검사. API 응답을 대체하며 실제 검색·사용량 호출은 하지 않는다."""
from playwright.sync_api import expect, sync_playwright
from providers.tavily_usage import normalize
from providers.elevenlabs_usage import normalize as normalize_eleven


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        try:
            page = browser.new_page(viewport={"width": 1280, "height": 1000})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            state = {"now": "2026-10-25T00:00:00Z", "claude": {"limits": []}, "chatgpt": {"limits": []},
                     "tavily": normalize({"account": {"current_plan": "Researcher", "plan_usage": 28, "plan_limit": 1000}}),
                     "elevenlabs": normalize_eleven({"tier": "free", "character_count": 595, "character_limit": 10000})}
            windows = [{"label": "5시간 세션", "used": 15, "resets_at": "2026-11-01T00:00:00Z"},
                       {"label": "주간 (전체 모델)", "used": 51, "resets_at": "2026-11-01T00:00:00Z"}]
            state["claude"].update(plan="pro", limits=windows)
            state["chatgpt"].update(plan="plus", limits=windows, local={
                "windows": {"5h": {"requests": 64, "output": 42100, "total": 14240000},
                            "7d": {"requests": 1140, "output": 701300, "total": 168740000}},
                "last_activity": "2026-10-25T00:00:00Z"})
            page.route("**/api/usage", lambda route: route.fulfill(json=state))
            page.goto("http://usage-monitor.local", wait_until="networkidle")
            card = page.locator("#tavily")
            expect(card.locator("h2")).to_have_text("Tavily 웹검색")
            expect(card).to_contain_text("972 크레딧")
            expect(card.locator(".left")).to_contain_text("97.2%")
            assert card.locator(".bar i").evaluate("e=>e.style.width") == "97.2%"
            eleven_card = page.locator("#elevenlabs")
            expect(eleven_card.locator("h2")).to_have_text("ElevenLabs")
            expect(eleven_card).to_contain_text("9,405 크레딧")
            expect(eleven_card.locator(".left")).to_contain_text("94.05%")
            assert eleven_card.locator(".bar i").evaluate("e=>e.style.width") == "94.05%"
            expect(page.locator("#claude h2")).to_have_text("Claude")
            expect(page.locator("#chatgpt h2")).to_have_text("ChatGPT")
            expect(page.locator("table")).to_have_count(0)
            expect(page.get_by_text("로컬 기록 기준 토큰 사용량")).to_have_count(0)
            for width, height in [(1366, 768), (1280, 720), (1024, 768)]:
                page.set_viewport_size({"width": width, "height": height})
                for selector in ("#claude", "#chatgpt", "#tavily", "#elevenlabs"):
                    bounds = page.locator(selector).bounding_box()
                    assert bounds and bounds["y"] + bounds["height"] <= height, (width, height, selector, bounds)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                assert page.evaluate("document.documentElement.scrollHeight <= innerHeight")
            page.screenshot(path="logs/usage-four-cards.png", full_page=True)
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            assert page.locator("#tavily").bounding_box()["y"] > page.locator("#chatgpt").bounding_box()["y"]
            page.set_viewport_size({"width": 1280, "height": 720})
            state["tavily"] = normalize({"account": {"current_plan": "Researcher", "plan_usage": 1000, "plan_limit": 1000}})
            page.reload(wait_until="networkidle")
            expect(card).to_contain_text("잔여 0 크레딧")
            assert card.locator(".bar i").evaluate("e=>e.style.width") == "0%"
            state["tavily"] = {"limits": [], "credits": None, "error": "TAVILY_API_KEY를 설정하세요."}
            state["elevenlabs"] = {"limits": [], "credits": None, "error": "사용자 조회(user_read) 권한을 확인하세요."}
            page.reload(wait_until="networkidle")
            expect(card.locator(".err")).to_contain_text("TAVILY_API_KEY")
            expect(card.locator(".bar")).to_have_count(0)
            expect(eleven_card.locator(".err")).to_contain_text("user_read")
            expect(eleven_card.locator(".bar")).to_have_count(0)
            assert not errors, errors
            print("PASS: four cards fit desktop viewports; local token table removed; mobile and quota states")
        finally:
            browser.close()


if __name__ == "__main__":
    main()
