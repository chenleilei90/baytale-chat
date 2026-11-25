# BayChat Android 原生客户端

基于 **Kotlin + Jetpack Compose + Material 3 + 原生 Google WebRTC (`libwebrtc`)** 构建，与 `https://chat.baytale.com` Go 后端及 Web 网页端 **100% 协议互通**。

## 核心能力
- **跨端无缝互通**：手机装原生 App，电脑直接开浏览器扫码即可 P2P 直连互传文件、照片与文字消息；支持自定义自建服务器地址。
- **GB 级流式直传 + SHA-256 完整性校验**：48KB 分片 + 背压流控 + 增量 SHA-256 校验，接收端通过 Android `MediaStore` 零存储权限流式落盘至系统 `Download/BayChat/`。
- **原生体验增强**：
  - 系统「分享到 BayChat」接收单文件/多文件/文本直接发进房间；
  - 前台保活传输服务（`foregroundServiceType="dataSync"`），切后台/锁屏不断连并实时显示传输进度；
  - 无 GMS 依赖的原生竖屏扫码进房 & 离线高清二维码生成（海外 Google Play 与国内小米等机型通用）；
  - 深链自动唤起（`https://chat.baytale.com/?room=...`、`/r/...`、`baychat://room/...`）；
  - 内置首次启动隐私政策同意门禁，未同意前零联网，符合 Google Play 与国内应用商店合规要求。

## 本地 / 服务器编译打包

需要 **JDK 17**、**Android SDK 35** 与 **Gradle 8.11+**。

```bash
cd android

# 运行协议兼容性单元测试
gradle testDebugUnitTest

# 编译 Debug APK（适合手机直接安装测试）
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 编译 Release APK 与上架 Google Play 所需的 AAB 包
# （先复制 keystore.properties.example 为 keystore.properties 并填入正式签名密钥）
gradle assembleRelease bundleRelease
# 产物：
# - app/build/outputs/apk/release/app-release.apk
# - app/build/outputs/bundle/release/app-release.aab
```

## 上架 Google Play / 小米商店清单
1. 使用 `keytool` 生成正式签名 `.jks` 并配置到 `android/keystore.properties`（已被 `.gitignore` 忽略，请妥善离线备份）。
2. 将 `app-release.aab` 上传 Google Play Console（建议先走内部测试 → 封闭测试）；或将正式签名的 `app-release.apk` 直接提供给国内用户下载/提交国内商店。
3. 目标 SDK 为 **Android 15 (API 35)**，满足 2025/2026 年应用商店最新最低目标版本要求。
