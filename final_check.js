// 最终综合检查
const fs = require('fs');

console.log('=== 最终综合检查 ===\n');

let pass = 0, fail = 0;
function check(name, cond) {
    console.log((cond ? '✅' : '❌') + ' ' + name);
    if (cond) pass++; else fail++;
}

// 1. 文件结构
console.log('--- 文件结构 ---');
const required = [
    'android/build.gradle',
    'android/settings.gradle',
    'android/app/build.gradle',
    'android/app/src/main/AndroidManifest.xml',
    'android/app/src/main/java/com/ncmdecoder/pro/MainActivity.java',
    'android/gradle/wrapper/gradle-wrapper.properties',
    '.github/workflows/build-apk.yml',
    'www/index.html',
    'www/app.js',
    'www/aes-js.min.js',
];
for (const f of required) {
    check('文件存在: ' + f, fs.existsSync(f));
}

// 2. build.gradle 无 jcenter
console.log('\n--- 构建配置 ---');
const rootGradle = fs.readFileSync('android/build.gradle', 'utf8');
check('根 build.gradle 无 jcenter()', !rootGradle.includes('jcenter'));
check('根 build.gradle 有 google()', rootGradle.includes('google()'));
check('根 build.gradle 有 mavenCentral()', rootGradle.includes('mavenCentral()'));
check('AGP 版本 8.x', rootGradle.includes('com.android.tools.build:gradle:8.'));

const appGradle = fs.readFileSync('android/app/build.gradle', 'utf8');
check('app build.gradle namespace 正确', appGradle.includes('com.ncmdecoder.pro'));
check('app build.gradle applicationId 正确', appGradle.includes('com.ncmdecoder.pro'));
check('compileSdk 33', appGradle.includes('compileSdk 33'));
check('versionName "2.0"', appGradle.includes('versionName "2.0"'));

// 3. Workflow
console.log('\n--- GitHub Actions ---');
const workflow = fs.readFileSync('.github/workflows/build-apk.yml', 'utf8');
check('Workflow 触发 main', workflow.includes("branches: [main]"));
check('JDK 17', workflow.includes("java-version: '17'"));
check('Gradle 8.4', workflow.includes('gradle-version: 8.4'));
check('APK 名称 NCM-Decoder-Pro', workflow.includes('NCM-Decoder-Pro'));
check('无 jcenter 引用', !workflow.includes('jcenter'));
check('无 gradle/wrapper 缓存问题', !workflow.includes('actions/cache'));
check('使用 gradle-build-action', workflow.includes('gradle/gradle-build-action'));

// 4. Java 代码
console.log('\n--- Java 代码 ---');
const java = fs.readFileSync('android/app/src/main/java/com/ncmdecoder/pro/MainActivity.java', 'utf8');
check('包名正确', java.includes('package com.ncmdecoder.pro'));
check('注册 "Android" 桥接', java.includes('"Android"'));
check('重写 onShowFileChooser', java.includes('onShowFileChooser'));
check('pickFiles 方法', java.includes('public void pickFiles'));
check('pickFolder 方法', java.includes('public void pickFolder'));
check('saveFile 方法(3参数)', java.includes('public void saveFile(final String name, final String ext, final String base64)'));
check('onFilesPicked 回调', java.includes('onFilesPicked'));
check('onFolderPicked 回调', java.includes('onFolderPicked'));
check('转义函数 esc()', java.includes('private String esc('));

// 5. JS 代码
console.log('\n--- JS 代码 ---');
const js = fs.readFileSync('www/app.js', 'utf8');
check('调用 Android.pickFiles', js.includes('window.Android.pickFiles'));
check('调用 Android.pickFolder', js.includes('window.Android.pickFolder'));
check('调用 Android.saveFile(3参数)', js.includes('window.Android.saveFile(outName'));
check('window.onFilesPicked 定义', js.includes('window.onFilesPicked = function'));
check('window.onFolderPicked 定义', js.includes('window.onFolderPicked = function'));
check('decryptNCM 函数', js.includes('function decryptNCM'));
check('decryptKey 正确的解密顺序', js.includes('const aes = new AESJS.AES(seedKey)') && js.includes('xorBytes(decrypted, 0x64)'));
check('RC4 函数', js.includes('function rc4Crypt'));
check('格式检测', js.includes('function detectFormat'));
check('语法检查通过', (() => { try { new Function(js); return true; } catch(e) { return false; } })());

// 6. Manifest
console.log('\n--- AndroidManifest ---');
const manifest = fs.readFileSync('android/app/src/main/AndroidManifest.xml', 'utf8');
check('包名正确', manifest.includes('com.ncmdecoder.pro'));
check('应用名 中文', manifest.includes('NCM 解密器 Pro'));
check('requestLegacyExternalStorage', manifest.includes('requestLegacyExternalStorage="true"'));
check('MainActivity 注册', manifest.includes('.MainActivity'));

// 7. AES 库
console.log('\n--- AES 库 ---');
const aesSize = fs.statSync('www/aes-js.min.js').size;
check('aes-js.min.js 存在且 > 10KB', aesSize > 10000);

console.log('\n=== 结果: ' + pass + '/' + (pass + fail) + ' ===');
process.exit(fail > 0 ? 1 : 0);
