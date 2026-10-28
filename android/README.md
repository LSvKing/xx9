# xx9 Android（原生）

CUT-2026-10-28-native-android：**Compose 原生 UI**，直调 FastAPI，**禁止** WebView/Capacitor 装 `web/`。

- 默认 Base URL：`http://49.232.9.67:8000/`（设置可改）
- 免密：请求头 `X-XX9-App: 1`（后端 `app_open` + `require_auth` 旁路；可选 `/api/app/session`）
- 播放：Media3 ExoPlayer HLS
- 投屏：AMEND-2 R1 DLNA（`cast/`，MulticastLock / 多 ST / CastState / 轮询）

```bash
./gradlew :app:assembleDebug
```

主交付 APK 文件名建议含 `native`。
