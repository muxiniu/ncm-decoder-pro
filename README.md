# NCM 解密器 Pro

将网易云音乐 `.ncm` 文件转换为标准 MP3/FLAC/M4A 格式的 Android 应用。

## 功能

- 📱 选择单个或多个 NCM 文件
- 📁 选择输出文件夹
- 🎵 自动检测输出格式（MP3 / FLAC / M4A）
- 🔓 完整的 NCM 解密（AES-128 + RC4）
- 💾 直接保存到用户选择的文件夹

## 技术原理

NCM 文件格式：
1. **Header**: `CTMF` magic + reserved
2. **Encrypted Key**: AES-128-ECB 加密的 16 字节音频密钥
3. **Metadata**: JSON 格式的歌曲信息（XOR 编码）
4. **Audio Data**: RC4 加密的音频数据

解密流程：
```
encrypted_key → AES-128-ECB(seed_key) → XOR 0x64 → reverse → audio_key
audio_data → RC4(audio_key) → raw audio
```

## 项目结构

```
ncm-decoder-pro/
├── www/                      # 前端 (WebView)
│   ├── index.html            # 界面
│   ├── app.js                # NCM 解密核心
│   └── aes-js.min.js         # AES 库
├── android/                   # 安卓原生工程
│   ├── build.gradle          # 根构建脚本
│   ├── settings.gradle
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── java/com/ncmdecoder/pro/
│               └── MainActivity.java   # WebView + 文件选择 + JS 桥接
├── .github/workflows/
│   └── build-apk.yml         # 自动构建 APK
└── README.md
```

## 构建

### 本地构建

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions 自动构建

推送到 `main` 分支后自动触发，产物为 `NCM-Decoder-Pro.apk`。

## 版本

- v2.0 (NCM 解密器 Pro) — 全新重写
- 包名: `com.ncmdecoder.pro`（与旧版 `com.ncmconverter.app` 区分）

## License

MIT
