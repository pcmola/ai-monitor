"""실행 중인 PC 모니터 화면을 README용으로 캡처합니다."""

from pathlib import Path

from playwright.sync_api import sync_playwright


def main():
    output = Path(__file__).resolve().parent.parent / "docs" / "images"
    output.mkdir(parents=True, exist_ok=True)
    targets = (
        ("hub", "http://ai-monitor.local", "#rows tr button", 320),
        ("usage", "http://usage-monitor.local", "#elevenlabs h2", 660),
        ("cost", "http://cost-monitor.local", "#tbl tr", 900),
    )
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True)
        try:
            for name, url, selector, height in targets:
                page = browser.new_page(viewport={"width": 1440, "height": height}, device_scale_factor=1)
                page.goto(url, wait_until="networkidle", timeout=60000)
                page.locator(selector).first.wait_for(timeout=60000)
                page.screenshot(path=str(output / f"{name}.png"), full_page=True)
                print(f"Captured {name}: {output / f'{name}.png'}")
                page.close()
        finally:
            browser.close()


if __name__ == "__main__":
    main()
