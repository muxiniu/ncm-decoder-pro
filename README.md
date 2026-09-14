# NCM 解密器 Pro

将网易云音乐的 `.ncm` 加密文件转换为标准 **MP3 / FLAC** 的安卓工具。

> 这是全新版本（v2.0），与旧版「NCM 转换器」完全区分，可同时安装。

## ✨ 功能

- 🎵 **选择文件** → 支持多选 .ncm 文件
- 📁 **选择保存文件夹** → 自定义输出目录
- 🏷️ **输出格式** → 自动 / MP3 / FLAC
- ✏️ **文件名模板** → `{artist} - {title}` 等，扩展名自动追加
- 📱 **纯安卓原生** + WebView，无需联网

## 🆕 与旧版的区别

| 项目 | 旧版 | **Pro (本版)** |
|------|------|---------------|
| 仓库 | ncm-converter-android | **ncm-decoder-pro** |
| 应用名 | NCM 转换器 | **NCM 解密器 Pro** |
| 包名 | com.ncmconverter.app | **com.ncmdecoder.pro** |
| 版本 | v1.0 | **v2.0** |
| APK | NCM-Converter.apk | **NCM-Decoder-Pro.apk** |

## 🚀 使用

1. 安装 `NCM-Decoder-Pro.apk`
2. 打开应用 → **选择 NCM 文件**
3. **选择保存文件夹**
4. 设置输出格式 / 文件名模板
5. 点 **开始转换** → 完成

## 🔧 技术原理

1. 解析 NCM 文件头（magic `4E434D4D`）
2. 用种子密钥 `hzHRAmE5OFYDKMeK` + AES-128-ECB 解密密钥
3. RC4 解密音频字节流
4. 还原为标准 MP3 / FLAC

## 📦 项目结构

```
www/                # 前端（WebView 加载）
android/            # 安卓原生工程（WebView + 文件选择 + 保存）
.github/workflows/  # GitHub Actions 自动构建 APK
tests/              # 验证逻辑（开发用）
```

## 🛠️ 本地构建

```bash
cd android
./gradlew assembleRelease
# 或
gradle assembleRelease
```

## 📄 License

MIT
