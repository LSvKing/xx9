# xx9 Android App（WebView 壳 + DLNA 投屏）

绑定 cut：`CUT-2026-10-28-app-cast` + `AMEND-1-no-app-login`。

## 行为
- 首次配置 Base URL → 直接加载 `{base}/?xx9app=1`（**无密码门**）
- Web 调 `POST /api/app/session`（头 `X-XX9-App: 1`）建会话；浏览器访问仍需 `web_password`
- 投屏：单一 **DLNA/UPnP AVTransport**（AMEND-2 R1：MulticastLock、多 ST、CastState、Transport 轮询、Pause）
- **不做** R2 自有 TV / Chromecast / AirPlay

## 构建
```bash
cd android
# 需 Android SDK；设置 ANDROID_HOME
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

服务端需部署本分支的 `server.py` / `web/`（含 `/api/app/session` 与投屏 DOM）。
