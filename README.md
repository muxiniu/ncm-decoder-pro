# NCM 解密器 Pro (NCM Decoder Pro)

把网易云音乐 `.ncm` 文件一键解密为 `.mp3` / `.flac`，**手机端离线处理，不上传任何服务器**。

> 与旧版 `ncm-converter-android` 完全区分：应用名 **NCM 解密器 Pro**、包名 `com.ncmdecoder.pro`、APK `NCM-Decoder-Pro.apk`、版本 `v2.0`。

## 功能
- 解析 NCM 文件头（magic、密钥、元数据）
- AES-128-ECB 解密密钥 → RC4 密钥
- RC4 解密音频数据流，自动识别 MP3 / FLAC / M4A
- 自定义文件名模板 `{artist} - {title}`
- **修复文件选择器无响应**（重写 `onShowFileChooser` + JSBridge 对齐）

## 项目结构
```
www/                        # 前端 (WebView)
  index.html
  app.js                    # NCM 解密核心 + UI
  aes-js.min.js
android/                    # 原生工程 (com.ncmdecoder.pro)
  app/build.gradle           # copyAssets: ../../www -> assets
  app/src/main/.../MainActivity.java
  app/src/main/AndroidManifest.xml
.github/workflows/build-apk.yml   # ⚠️ 需手动添加（见下）
```

## ⚠️ 启用自动构建（重要）

本项目使用 GitHub Actions 自动构建 APK。由于仓库初始化时未预置 workflow 文件，
**请按以下步骤手动添加**（仅需一次）：

### 方法：在 GitHub 网页新建文件
1. 打开 https://github.com/muxiniu/ncm-decoder-pro/new/main
2. 在顶部文件名框输入：`.github/workflows/build-apk.yml`
3. 粘贴下方 **Workflow 内容**
4. 点绿色 **Commit changes**
5. 之后每次 push 到 main 自动构建，APK 发布到 Release `v2.0-pro`

### Workflow 内容
```yaml
name: Build NCM Decoder Pro APK

on:
  push:
    branches: [main, master]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        working-directory: android
        run: chmod +x ./gradlew || true

      - name: Build debug APK
        working-directory: android
        run: |
          if [ -x ./gradlew ]; then
            ./gradlew assembleDebug --no-daemon
          else
            gradle assembleDebug --no-daemon
          fi

      - name: Rename APK
        working-directory: android/app/build/outputs/apk/debug
        run: cp app-debug.apk NCM-Decoder-Pro.apk

      - name: Create Release v2.0-pro
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v2.0-pro
          name: NCM 解密器 Pro v2.0
          files: android/app/build/outputs/apk/debug/NCM-Decoder-Pro.apk
        env:
          GITHUB_TOKEN: \${{ secrets.GITHUB_TOKEN }}
```

> 💡 如果添加时提示权限错误，说明你的 GitHub PAT 缺少 `workflow` 权限。
> 重新生成 classic token 并勾选 `workflow` 作用域即可。

## 本地构建（无需 Actions）
```bash
git clone https://github.com/muxiniu/ncm-decoder-pro.git
cd ncm-decoder-pro/android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 使用
1. 打开 App → 选择 `.ncm` 文件（可多选）
2. 选择保存文件夹
3. （可选）修改文件名模板
4. 开始转换

## 验证
解密核心、前后端桥接对齐、转义安全测试均已通过（60+ 项）。
