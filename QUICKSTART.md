# 快速开始 & 构建指南

## 一、启用自动构建（GitHub Actions）

> 仓库已含全部源代码，**只需添加 `build-apk.yml` 即可自动构建 APK**。

### GitHub 网页新建（仅需一次）
1. 打开 👉 https://github.com/muxiniu/ncm-decoder-pro/new/main
2. 文件名框**完整输入**：`.github/workflows/build-apk.yml`
3. 粘贴下方 Workflow 内容 → Commit changes
4. 之后 push 到 main 自动构建，APK 发布到 Release `v2.0-pro`

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
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin
      - uses: android-actions/setup-android@v3
      - name: Build debug APK
        working-directory: android
        run: gradle assembleDebug --no-daemon
      - name: Rename APK
        working-directory: android/app/build/outputs/apk/debug
        run: cp app-debug.apk NCM-Decoder-Pro.apk
      - uses: softprops/action-gh-release@v2
        with:
          tag_name: v2.0-pro
          name: NCM 解密器 Pro v2.0
          files: android/app/build/outputs/apk/debug/NCM-Decoder-Pro.apk
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```
> 💡 保存提示权限错误 → PAT 需勾选 `workflow` 权限。

### 构建完成
- APK：https://github.com/muxiniu/ncm-decoder-pro/releases/tag/v2.0-pro
- 日志：https://github.com/muxiniu/ncm-decoder-pro/actions

## 二、本地构建
```bash
git clone https://github.com/muxiniu/ncm-decoder-pro.git
cd ncm-decoder-pro/android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 三、使用 App
1. 安装 APK → 打开
2. 选择 `.ncm` 文件（可多选）
3. 选保存文件夹
4. （可选）改文件名模板 `{artist} - {title}`
5. 开始转换 ✅

## 四、与旧版区分（可同时安装）
| 项目 | 旧版 | 新版 |
|------|------|------|
| 仓库 | ncm-converter-android | **ncm-decoder-pro** |
| 应用名 | NCM 转换器 | **NCM 解密器 Pro** |
| 包名 | com.ncmconverter.app | **com.ncmdecoder.pro** |
| APK | NCM-Converter.apk | **NCM-Decoder-Pro.apk** |
| 版本 | v1.0 | **v2.0** |
