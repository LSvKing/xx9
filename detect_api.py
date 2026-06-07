#!/usr/bin/env python3
"""
自动检测 API 域名 - 用 Playwright 打开前端页面，拦截真实 API 请求
用法: uv run python detect_api.py [https://2fue20zkk5hbe5.xyz]
"""

import asyncio
import json
import os
import re
import sys

try:
    from playwright.async_api import async_playwright
except ImportError:
    print("pip3 install playwright && playwright install chromium")
    sys.exit(1)

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")


async def main():
    # 从 config 读取前端地址，或命令行参数
    frontend = "https://2fue20zkk5hbe5.xyz"
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg = json.load(f)
        frontend = cfg.get("frontend", frontend)
    if len(sys.argv) > 1:
        frontend = sys.argv[1]

    print(f"前端: {frontend}")
    print("启动浏览器...")

    pw = await async_playwright().start()
    browser = await pw.chromium.launch(headless=True)
    context = await browser.new_context(
        user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/147.0.0.0 Safari/537.36",
        viewport={"width": 1440, "height": 900},
        locale="zh-CN",
    )
    page = await context.new_page()

    captured = {}

    # 拦截所有请求，找到 fast-endecode
    def on_request(request):
        url = request.url
        if "fast-endecode" in url and url not in captured:
            captured["api_url"] = url
            for h in ["accesstoken", "jwttoken", "origin"]:
                v = request.headers.get(h) or request.headers.get(h.lower())
                if v:
                    captured[h] = v
            # 也记录 POST body 中的 time（用于验证密钥正确）
            post = request.post_data
            if post:
                try:
                    body = json.loads(post)
                    captured["sample_time"] = body.get("time")
                    captured["sample_data"] = body.get("data", "")[:50]
                except:
                    pass

    page.on("request", on_request)

    print(f"打开 {frontend}/recommend ...")
    try:
        await page.goto(f"{frontend}/recommend", wait_until="networkidle", timeout=30000)
    except Exception:
        pass  # 网络超时也继续

    await asyncio.sleep(5)  # 等待 JS 发起请求
    await browser.close()
    await pw.stop()

    if not captured.get("api_url"):
        print("未捕获到 API 请求，请确认:")
        print("  1. 网络能访问该前端")
        print("  2. 前端域名是最新的")
        return

    print(f"\n捕获到 API 请求:")
    api_domain = re.match(r"(https?://[a-zA-Z0-9.-]+)", captured["api_url"])
    if api_domain:
        api_domain = api_domain.group(1)
        print(f"  api_base: {api_domain}")
    print(f"  accesstoken: {captured.get('accesstoken', 'N/A')[:30]}...")
    print(f"  jwttoken: {captured.get('jwttoken', 'N/A')[:50]}...")
    print(f"  origin: {captured.get('origin', 'N/A')}")

    # 更新 config.json
    cfg = {}
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg = json.load(f)

    if api_domain:
        cfg["api_base"] = api_domain
    if captured.get("accesstoken"):
        cfg["access_token"] = captured["accesstoken"]
    if captured.get("jwttoken"):
        cfg["jwt_token"] = captured["jwttoken"]
    if captured.get("origin"):
        cfg["frontend"] = captured["origin"].rstrip("/")

    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)

    print(f"\nconfig.json 已更新")


if __name__ == "__main__":
    asyncio.run(main())
