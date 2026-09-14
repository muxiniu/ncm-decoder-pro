# 🎵 NCM 解密器 Pro

> 全新版本 —— 与旧版 `ncm-converter-android` **完全区分**（不同包名、不同应用名、可并存）

将网易云音乐 `.ncm` 加密文件一键转换为标准 **MP3 / FLAC** 的 Android 工具。

| 项目 | 值 |
|------|-----|
| 应用名 | **NCM 解密器 Pro** |
| 包名 | `com.ncmdecoder.pro` |
| 版本 | v2.0 |
| APK | `NCM-Decoder-Pro.apk` |

## ✨ 功能

- 📂 **选择文件** —— 点按钮弹出系统文件选择器（支持多选）
- 📁 **选择保存位置** —— 点按钮弹出文件夹选择器
- 🎚 **输出格式** —— 自动检测 / MP3 / FLAC
- ✏️ **文件名模板** —— 只需填名字（如 `{artist} - {title}`），扩展名自动加

## 🚀 使用

1. 下载 `NCM-Decoder-Pro.apk` 安装
2. 打开 App → 点「**选择 NCM 文件**」→ 选中 `.ncm`
3. 点「**选择保存位置**」→ 选一个文件夹
4. （可选）修改文件名模板、输出格式
5. 点「**开始转换**」→ 完成

## 🔧 技术说明

- **前端**：纯 HTML + JS，运行在 WebView 中
- **解密**：AES-128-ECB（种子密钥）+ RC4（music 流）
- **桥接**：JS ↔ Java 通过 `window.Android`（方法：`pickFiles` / `pickFolder` / `saveFile`）

## 🏗 本地构建

```bash
cd android
./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

## 📦 自动构建

推送到 `main` 分支后，GitHub Actions 自动：
1. 编译 APK
2. 重命名为 `NCM-Decoder-Pro.apk`
3. 发布到 Release `v2.0-pro`

## ⚠️ 免责声明

仅供学习研究，请尊重版权，不要用于商业用途。
