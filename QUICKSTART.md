# NCM 解密器 Pro — 快速开始

## 方式一：直接用 APK（推荐）

1. 下载 `NCM-Decoder-Pro.apk`（GitHub Releases）
2. 手机安装 → 允许「未知来源」
3. 打开 → 选 .ncm 文件 → 选保存文件夹 → 转换

## 方式二：从源码构建

```bash
# 需要 JDK 17 + Android SDK (API 33)
cd android
./gradlew assembleRelease
# 输出: android/app/build/outputs/apk/release/app-release.apk
```

## 方式三：网页版测试

直接用浏览器打开 `www/index.html`（选文件 / 转换逻辑可用，保存需安卓环境）。

## 使用步骤

```
第1步  选择 NCM 文件（可多选）
第2步  选择保存文件夹
第3步  设置输出格式 + 文件名模板
第4步  开始转换 → 完成
```

## 文件名模板变量

- `{title}` - 标题
- `{artist}` - 艺术家
- `{album}` - 专辑

例：`{artist} - {title}` → `周杰伦 - 晴天.mp3`

## FAQ

**Q: 点选择文件没反应？**
A: 请确保已授予存储权限，并等待应用重启后重试。

**Q: 转换失败？**
A: 确认文件是有效的 .ncm 文件（网易云音乐下载的加密格式）。

**Q: 能否批量转换？**
A: 可以，选择文件时多选即可。

## 测试（开发用）

```bash
node tests/test_core.js         # 解密核心 13/13
node tests/verify.js            # 前后端桥接 17/17
node tests/check_structure.js   # 结构完整性 35/35
```
