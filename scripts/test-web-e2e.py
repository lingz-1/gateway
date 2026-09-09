import json
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


PROJECT_ROOT = Path(__file__).resolve().parents[1]
RESULTS_DIR = PROJECT_ROOT / "lingshu-web" / "test-results"
DESKTOP_SCREENSHOT = RESULTS_DIR / "desktop.png"
MOBILE_SCREENSHOT = RESULTS_DIR / "mobile.png"
WEB_URL = "http://127.0.0.1:5173"


def main() -> None:
    console_errors: list[str] = []
    request_failures: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.on(
            "console",
            lambda message: console_errors.append(message.text) if message.type == "error" else None,
        )
        page.on(
            "requestfailed",
            lambda request: request_failures.append(
                f"{request.method} {request.url}: {request.failure}"
            ),
        )

        page.goto(WEB_URL, wait_until="networkidle")
        expect(page.get_by_text("调用链在线")).to_be_visible()
        expect(page.locator("select option[value='stub-echo-v1']")).to_have_count(1)

        prompt = "端到端缓存验证"
        page.get_by_label("消息内容").fill(prompt)
        page.get_by_role("button", name="发送消息").click()
        expect(page.locator(".trace-state")).to_have_text("已完成", timeout=15_000)
        expect(page.locator(".message.assistant .message-body")).to_contain_text("stub:")
        expect(page.locator(".metric-grid")).to_contain_text("Token")

        page.get_by_role("button", name="新建对话").click()
        page.get_by_label("消息内容").fill(prompt)
        page.get_by_role("button", name="发送消息").click()
        expect(page.locator(".trace-state")).to_have_text("已完成", timeout=15_000)
        expect(page.locator(".metric-grid")).to_contain_text("EXACT")

        RESULTS_DIR.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(DESKTOP_SCREENSHOT), full_page=True)

        mobile = browser.new_page(viewport={"width": 390, "height": 844})
        mobile.goto(WEB_URL, wait_until="networkidle")
        expect(mobile.get_by_role("heading", name="把请求交给灵枢， 看见它穿过整条链路。")) \
            .to_be_visible()
        mobile.get_by_role("button", name="打开会话列表").click()
        expect(mobile.locator(".session-panel.is-open")).to_be_visible()
        mobile.locator(".backdrop").click()
        mobile.get_by_role("button", name="链路详情").click()
        expect(mobile.locator(".inspector.is-open")).to_be_visible()
        mobile.wait_for_timeout(300)
        mobile.screenshot(path=str(MOBILE_SCREENSHOT), full_page=True)
        browser.close()

    if console_errors or request_failures:
        raise AssertionError(
            json.dumps(
                {"consoleErrors": console_errors, "requestFailures": request_failures},
                ensure_ascii=False,
                indent=2,
            )
        )

    print(
        json.dumps(
            {
                "status": "passed",
                "url": WEB_URL,
                "cache": "EXACT",
                "screenshots": [str(DESKTOP_SCREENSHOT), str(MOBILE_SCREENSHOT)],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
