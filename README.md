# NCM 解密器 Pro (NCM Decoder Pro)

把网易云音乐 `.ncm` 文件一键解密为 `.mp3` / `.flac`，**手机端离线处理，不上传任何服务器**。

> 与旧版 `ncm-converter-android` 完全区分：应用名 **NCM 解密器 Pro**、包名 `com.ncmdecoder.pro`、APK `NCM-Decoder-Pro.apk`、版本 `v2.0`。

## 功能
- 解析 NCM 文件头（magic、密钥、元数据）
- AES-128-ECB 解密密钥 → RC4 密钥
- RC4 解密音频数据流，自动识别 MP3 / FLAC / M4A
- 自定义文件名模板 `{artist} - {title}`
- **修复文件选择器无响应**（重写 `onShowFileChooser` + JSBridge 对齐）

## 结构
```
www/                        # 前端 (WebView)
android/                    # 原生工程 (com.ncmdecoder.pro)
  app/build.gradle           # copyAssets: ../../www -> assets
.github/workflows/build-apk.yml   # 自动构建 APK
```

## 构建
详见 **[QUICKSTART.md](./QUICKSTART.md)**。

## 使用
选择 `.ncm` → 选保存文件夹 → 开始转换。

## 验证
解密核心、桥接对齐、转义安全测试均通过（60+ 项）。

## License
MIT
