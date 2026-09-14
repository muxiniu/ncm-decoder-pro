# NCM Decoder Pro

网易云音乐 NCM 格式批量解密工具 (Android)

## 功能
- 🎵 批量解密 `.ncm` 文件为 `.mp3` / `.flac`
- 🏷️ 自动识别歌名 / 歌手 / 专辑 (解析 metadata)
- 🖼️ 写入 ID3v2 标签 + 专辑封面 (APIC)
- 📂 输出到公共音乐库 `Music/NCM Decoder/` (Android 10+ 可见)
- ⚡ 前台 Service + 通知栏进度
- 🔁 MD5 去重, 跳过已解密文件
- 📤 解密完成一键分享
- 🌙 DayNight 主题 (跟随系统)
- 🛡️ 全局崩溃日志

## 构建

### 方式一: GitHub Actions (推荐, 零环境)
1. 把本仓库推送到 GitHub (可用 `push-to-github.sh`)
2. 打开 `Actions` 标签页 → 找到 `Build NCM Decoder Pro APK`
3. 点 `Run workflow` → 选 `main` → 运行
4. 等待 3-5 分钟, 变绿后下载 Artifacts 里的 APK

> CI 通过 `gradle/gradle-build-action` 自动下载 Gradle 8.4, **不依赖 gradlew / gradle-wrapper.jar**,
> 彻底避免 wrapper 损坏导致的 `NoClassDefFoundError` / `gradlew not found`。

### 方式二: 本地构建 (需要 JDK 17 + Android SDK 34)
```bash
# 如果有 gradle 命令
gradle :app:assembleDebug

# 或用 Android Studio 打开本目录, 点 Build → Build Bundle(s) / APK(s)
```

## 项目结构
```
ncm-decoder-pro/
├── .github/workflows/build-apk.yml   # CI 自动构建
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ncm/decoder/
│       │   ├── MainActivity.java      # 主界面 (单文件/批量/设置/关于/分享)
│       │   ├── DecodeService.java     # 前台 Service + 通知栏进度
│       │   ├── NcmDecoder.java        # 核心解密算法 (RC4/AES)
│       │   ├── Id3Util.java           # 流式 ID3v2 标签 + APIC 封面
│       │   ├── MediaStoreHelper.java  # P0: 写入公共音乐库
│       │   ├── AppSettings.java       # 偏好设置 (目录/封面/去重)
│       │   ├── CrashHandler.java      # 全局崩溃捕获
│       │   └── SettingsActivity.java  # 设置页
│       └── res/layout/activity_main.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
└── push-to-github.sh                  # 一键推送脚本
```

## 使用声明
本工具仅限用于解密您本人合法拥有/下载的音乐, 请勿传播侵权内容。
