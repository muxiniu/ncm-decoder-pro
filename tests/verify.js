/**
 * 验证：前端 app.js 的 Android 调用 与 Java WebAppInterface 方法签名是否对齐
 * 用法: node verify.js
 */
const fs = require('fs');

const appJs = fs.readFileSync('www/app.js', 'utf-8');
const javaSrc = fs.readFileSync('android/app/src/main/java/com/ncmconverter/app/WebAppInterface.java', 'utf-8');
const mainJava = fs.readFileSync('android/app/src/main/java/com/ncmconverter/app/MainActivity.java', 'utf-8');

let pass = 0, fail = 0;
function check(cond, msg) { if (cond) { pass++; console.log('  ✅ ' + msg); }
                           else { fail++; console.log('  ❌ ' + msg); } }

console.log('=== 接口对齐验证 ===\n');

// 1. JS 端注册名 ===
console.log('1. JS 桥接注册名');
check(/window\.Android\s*=/m.test(appJs) || /Android\s*=\s*window\.Android/m.test(appJs) || /const Android = window\.Android/.test(appJs),
  '前端使用 window.Android');

// 2. Java 端注册名
console.log('2. Java 桥接注册名');
check(/addJavascriptInterface\([^,]+,\s*"Android"\)/.test(mainJava),
  'Java 注册名为 "Android"');

// 3. 方法签名对齐
console.log('3. 方法签名对齐');
const jsCalls = {
  pickFiles: /Android\.pickFiles\(\)/.test(appJs),
  pickFolder: /Android\.pickFolder\(\)/.test(appJs),
  saveAudio: /Android\.saveAudio\(/.test(appJs)
};
check(jsCalls.pickFiles, 'JS 调用 Android.pickFiles()');
check(jsCalls.pickFolder, 'JS 调用 Android.pickFolder()');
check(jsCalls.saveAudio, 'JS 调用 Android.saveAudio(...)');

// Java 端方法
check(/public void pickFiles\(\)/.test(javaSrc), 'Java 定义 pickFiles()');
check(/public void pickFolder\(\)/.test(javaSrc), 'Java 定义 pickFolder()');

// saveAudio 参数：Java 必须是 (String, String, String, String)
const saveAudioMatch = javaSrc.match(/public void saveAudio\(([^)]+)\)/);
if (saveAudioMatch) {
  const params = saveAudioMatch[1].trim();
  check(params === 'String folderUri, String baseName, String ext, String base64Data',
    'saveAudio 签名: (String, String, String, String) — 实际: ' + params);
} else {
  fail++; console.log('  ❌ Java 未定义 saveAudio');
}

// 4. JS 端 saveAudio 调用参数顺序（用正则按引号外逗号分割）
console.log('4. saveAudio 调用参数顺序');
const saveCall = appJs.match(/Android\.saveAudio\(([^)]+)\)/);
if (saveCall) {
  // 简单的括号匹配分割（忽略字符串内的逗号）
  let depth = 0, start = 0;
  const args = [];
  const raw = saveCall[1];
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (c === "'" || c === '"') {
      // 跳过字符串
      const q = c;
      i++;
      while (i < raw.length && raw[i] !== q) i++;
    } else if (c === ',' && depth === 0) {
      args.push(raw.slice(start, i).trim());
      start = i + 1;
    }
  }
  args.push(raw.slice(start).trim());
  check(args.length === 4, `参数个数 = 4 (实际 ${args.length})`);
  check(args[0].includes('folder') || args[0].includes('uri'), `第1参=uri (${args[0]})`);
  check(args[1].includes('baseName') || args[1].includes('Name'), `第2参=baseName (${args[1]})`);
  check(args[2] === 'ext', `第3参=ext (${args[2]})`);
  check(args[3].includes('b64') || args[3].includes('base64'), `第4参=base64`);
}

// 5. JS→Java 回调
console.log('5. Java→JS 回调');
check(/window\.setFiles/.test(mainJava) && /setFiles/.test(appJs),
  'setFiles 回调对齐');
check(/window\.setFolder/.test(mainJava) && /setFolder/.test(appJs),
  'setFolder 回调对齐');

// 6. 文件选择回调：onShowFileChooser
console.log('6. WebChromeClient 重写');
check(/onShowFileChooser/.test(mainJava), '重写 onShowFileChooser');

// 7. 权限
console.log('7. 存储权限');
check(/READ_EXTERNAL_STORAGE/.test(mainJava) || /READ_EXTERNAL_STORAGE/.test(
  fs.readFileSync('android/app/src/main/AndroidManifest.xml', 'utf-8')), '声明读存储权限');

console.log(`\n=== 结果: ${pass} 通过, ${fail} 失败 ===`);
process.exit(fail > 0 ? 1 : 0);
