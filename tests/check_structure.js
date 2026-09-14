#!/usr/bin/env node
/** 项目结构与代码质量检查（30 项） */
const fs = require('fs');
const path = require('path');

let passed = 0, failed = 0;
const checks = [];
function check(name, cond) { checks.push([name, !!cond]); if (cond) passed++; else failed++; }

const ROOT = path.join(__dirname, '..');

// 必需文件清单
const required = [
  'www/index.html',
  'www/app.js',
  'www/aes-js.min.js',
  'android/app/src/main/AndroidManifest.xml',
  'android/app/src/main/java/com/ncmconverter/app/MainActivity.java',
  'android/app/src/main/java/com/ncmconverter/app/WebAppInterface.java',
  'android/app/build.gradle',
  'android/build.gradle',
  'android/settings.gradle',
  '.github/workflows/build-apk.yml',
  'README.md',
  '.gitignore',
];

for (const f of required) {
  check(`文件存在: ${f}`, fs.existsSync(path.join(ROOT, f)));
}

// 内容检查
const manifest = fs.readFileSync(path.join(ROOT, 'android/app/src/main/AndroidManifest.xml'), 'utf-8');
check('Manifest: 声明 INTERNET 权限', /INTERNET/.test(manifest));
check('Manifest: 声明读存储权限', /READ_EXTERNAL_STORAGE|READ_MEDIA_AUDIO/.test(manifest));
check('Manifest: application 标签有 label', /android:label/.test(manifest));
check('Manifest: 应用名 NCM 解密器 Pro', /NCM 解密器 Pro/.test(manifest));

const gradle = fs.readFileSync(path.join(ROOT, 'android/app/build.gradle'), 'utf-8');
check('Gradle: applicationId com.ncmdecoder.pro', /com\.ncmdecoder\.pro/.test(gradle));
check('Gradle: versionName 2.0', /versionName "2\.0"/.test(gradle));
check('Gradle: minSdk <= 21', /minSdkVersion 21/.test(gradle));
check('Gradle: copyAssets 任务', /copyAssets/.test(gradle));

const mainActivity = fs.readFileSync(path.join(ROOT, 'android/app/src/main/java/com/ncmconverter/app/MainActivity.java'), 'utf-8');
check('Java: 重写 onShowFileChooser', /onShowFileChooser/.test(mainActivity));
check('Java: 注册 "Android" 桥接', /"Android"/.test(mainActivity));
check('Java: 处理 REQ_FILE', /REQ_FILE/.test(mainActivity));
check('Java: 处理 REQ_FOLDER', /REQ_FOLDER/.test(mainActivity));
check('Java: 处理 REQ_SAVE', /REQ_SAVE/.test(mainActivity));
check('Java: takePersistableUriPermission', /takePersistableUriPermission/.test(mainActivity));
check('Java: 回调 window.onFilesPicked', /window\.onFilesPicked/.test(mainActivity));
check('Java: 回调 window.onFolderPicked', /window\.onFolderPicked/.test(mainActivity));
check('Java: 无编译错误特征（无 TODO/FIXME 残留）', !/TODO|FIXME|XXX/.test(mainActivity));

const webInterface = fs.readFileSync(path.join(ROOT, 'android/app/src/main/java/com/ncmconverter/app/WebAppInterface.java'), 'utf-8');
check('WebAppInterface: @JavascriptInterface', /@JavascriptInterface/.test(webInterface));
check('WebAppInterface: saveAudio 4 参数', /saveAudio\(String[^)]*String[^)]*String[^)]*String/.test(webInterface));
check('WebAppInterface: pickFiles', /pickFiles\(\)/.test(webInterface));
check('WebAppInterface: pickFolder', /pickFolder\(\)/.test(webInterface));

const appJs = fs.readFileSync(path.join(ROOT, 'www/app.js'), 'utf-8');
check('JS: window.Android 桥接', /window\.Android|Android = window/.test(appJs));
check('JS: Android.saveAudio 调用', /Android\.saveAudio\(/.test(appJs));
check('JS: 读取 folder.uri', /folder\.uri/.test(appJs));
check('JS: 读取 folder.name', /folder\.name/.test(appJs));
check('JS: 读取 f.base64', /base64/.test(appJs));
check('JS: onFilesPicked 回调', /onFilesPicked/.test(appJs));
check('JS: onFolderPicked 回调', /onFolderPicked/.test(appJs));
check('JS: convertNCM 函数', /function convertNCM/.test(appJs));
check('JS: renderName 函数', /function renderName/.test(appJs));

const wf = fs.readFileSync(path.join(ROOT, '.github/workflows/build-apk.yml'), 'utf-8');
check('Workflow: 触发 on push main', /push:[\s\S]*?main/.test(wf));
check('Workflow: 使用 JDK 17', /java-version: ['"]17/.test(wf));
check('Workflow: 构建 assembleRelease', /assembleRelease/.test(wf));
check('Workflow: 上传 artifact', /upload-artifact/.test(wf));
check('Workflow: 创建 Release (v2.0-pro)', /v2\.0-pro/.test(wf));
check('Workflow: APK 名 NCM-Decoder-Pro', /NCM-Decoder-Pro/.test(wf));

// 无遗留文件
const gitignore = fs.readFileSync(path.join(ROOT, '.gitignore'), 'utf-8');
check('.gitignore: 忽略 build/', /build\//.test(gitignore));
check('.gitignore: 忽略 *.apk', /\*\.apk/.test(gitignore));

// 前端能加载 aes-js
const aes = fs.readFileSync(path.join(ROOT, 'www/aes-js.min.js'), 'utf-8');
check('aes-js.min.js 有效（含 CryptoJS 或 aesjs）', /CryptoJS|AES|aesjs/.test(aes));

console.log('='.repeat(50));
for (const [n,c] of checks) console.log((c?'  ✅ ':'  ❌ ') + n);
console.log('='.repeat(50));
console.log(`结果: ${passed}/${passed+failed}`);
process.exit(failed ? 1 : 0);
