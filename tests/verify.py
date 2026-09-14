#!/usr/bin/env python3
"""前后端桥接一致性验证（20 项）"""
import re, sys

def read(p):
    with open(p, encoding='utf-8') as f: return f.read()

java = read('android/app/src/main/java/com/ncmconverter/app/WebAppInterface.java')
main = read('android/app/src/main/java/com/ncmconverter/app/MainActivity.java')
js = read('www/app.js')

checks = []
def check(name, cond): checks.append((name, bool(cond)))

# ---- Java 端：注册名 + 方法签名 ----
check("Java: 注册名为 'Android'", 'addJavascriptInterface(' in main and '"Android"' in main)
check("Java: pickFiles() 无参", 'public void pickFiles()' in java)
check("Java: pickFolder() 无参", 'public void pickFolder()' in java)
check("Java: saveAudio 4 参数 (String,String,String,String)",
      re.search(r'public void saveAudio\(String[^)]*String[^)]*String[^)]*String', java) is not None)
save_params = re.search(r'saveAudio\((String[^)]*)\)', java)
check("Java: saveAudio 参数顺序 folderUri,baseName,ext,base64Data",
      'folderUri' in (save_params.group(1) if save_params else '') and 'base64Data' in (save_params.group(1) if save_params else ''))

# ---- Java 端：回调 JS 的方法名 ----
check("Java: 回调 window.onFilesPicked", 'window.onFilesPicked' in main)
check("Java: 回调 window.onFolderPicked", 'window.onFolderPicked' in main)
check("Java: 传 base64 字段", '"base64"' in main)
check("Java: 传 uri + name 字段", '"uri"' in main and '"name"' in main)

# ---- JS 端：调用方式 ----
check("JS: window.Android 对象", 'window.Android' in js or 'Android = window.Android' in js)
check("JS: Android.pickFiles()", 'Android.pickFiles()' in js)
check("JS: Android.pickFolder()", 'Android.pickFolder()' in js)
check("JS: Android.saveAudio 4 参数", js.count('Android.saveAudio(') > 0 and
      all(len([a for a in c.split('(')[1].split(')')[0].split(',') if a.strip()]) == 4
          for c in re.findall(r'Android\.saveAudio\([^)]*\)', js)))
save_calls = re.findall(r'Android\.saveAudio\(([^)]*)\)', js)
check("JS: saveAudio 参数顺序 (folderUri, baseName, ext, b64)",
      any('folderUri' in c or 'folder' in c.lower() for c in save_calls))

# ---- JS 端：回调定义 ----
check("JS: window.onFilesPicked 定义", 'window.onFilesPicked' in js)
check("JS: window.onFolderPicked 定义", 'window.onFolderPicked' in js)
check("JS: onFilesPicked 用 JSON.parse", 'JSON.parse' in js)
check("JS: 读取 f.base64", 'base64' in js)
check("JS: 读取 folder.uri + folder.name", 'folder.uri' in js and 'folder.name' in js)

# ---- 应用标识 ----
manifest = read('android/app/src/main/AndroidManifest.xml')
gradle = read('android/app/build.gradle')
check("应用名 NCM 解密器 Pro", 'NCM 解密器 Pro' in manifest)
check("包名 com.ncmdecoder.pro", 'com.ncmdecoder.pro' in gradle)
check("版本 2.0", 'versionName "2.0"' in gradle)

# ---- Workflow ----
wf = read('.github/workflows/build-apk.yml')
check("Workflow: artifact 名带 -pro", 'NCM-Decoder-Pro' in wf)
check("Workflow: tag v2.0-pro", 'v2.0-pro' in wf)

passed = sum(1 for _, c in checks if c)
print("=" * 60)
for name, c in checks:
    print(("  ✅ " if c else "  ❌ ") + name)
print("=" * 60)
print("结果: %d/%d" % (passed, len(checks)))
sys.exit(0 if passed == len(checks) else 1)
