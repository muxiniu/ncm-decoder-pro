#!/usr/bin/env node
/** NCM 解密核心测试（纯逻辑，不依赖安卓） */
const fs = require('fs');
const path = require('path');

let passed = 0, failed = 0;
const checks = [];
function check(name, cond) { checks.push([name, !!cond]); if (cond) passed++; else failed++; }

// 加载 app.js 里的核心函数（通过 eval 提取）
const appJs = fs.readFileSync(path.join(__dirname, '..', 'www', 'app.js'), 'utf-8');

// 提取纯函数（convertNCM, renderName, readUint32LE, bytesToB64 等）
const funcNames = ['readUint32LE', 'bytesToB64', 'b64ToBytes', 'renderName', 'convertNCM'];
let sandbox = '';
for (const name of funcNames) {
  const idx = appJs.indexOf('function ' + name + '(');
  if (idx >= 0) {
    const end = appJs.indexOf('\n}', idx) + 2;
    sandbox += appJs.substring(idx, end) + '\n';
  }
}
// 提取常量
const constMatch = appJs.match(/const AUDIO_MAGIC[\s\S]*?const FORMAT_NAMES[\s\S]*?};/);
if (constMatch) sandbox += constMatch[0] + '\n';

const vm = require('vm');
const ctx = { Buffer, Uint8Array, console, Math, parseInt, String, Array, Object, JSON, RegExp, escape };
ctx.sandbox = ctx;
vm.createContext(ctx);
vm.runInContext(sandbox + '\nglobal.readUint32LE=readUint32LE; global.bytesToB64=bytesToB64; global.b64ToBytes=b64ToBytes; global.renderName=renderName; global.convertNCM=convertNCM;', ctx);

const { readUint32LE, bytesToB64, b64ToBytes, renderName, convertNCM } = ctx;

// ===== 测试 =====
check('readUint32LE 小端序', readUint32LE(new Uint8Array([0x78,0x56,0x34,0x12]).buffer, 0) === 0x12345678);
check('readUint32LE 边界', readUint32LE(new Uint8Array([1,0,0,0]).buffer, 0) === 1);

// base64 往返
const sample = 'Hello, NCM! 🎵';
const buf = Buffer.from(sample, 'utf-8');
const b64 = bytesToB64(new Uint8Array(buf));
const back = b64ToBytes(b64);
check('bytesToB64 往返', Buffer.from(back).toString('utf-8') === sample);
check('bytesToB64 是标准 base64', /^[A-Za-z0-9+/]+=*$/.test(b64));

// 构造一个最小 NCM 样本并解密
function buildNCM(audioData, meta) {
  // NCM 文件结构（简化版，用于测试核心逻辑）
  const header = Buffer.from([0x4E,0x43,0x4D,0x4D]); // magic NCMM
  const reserved = Buffer.from([0,0,0,0]);
  // 简化：直接包装音频 + meta
  const metaBuf = Buffer.from(JSON.stringify(meta || {title:'Test',artist:'Dev',album:'Unit'}), 'utf-8');
  return Buffer.concat([header, reserved, metaBuf, audioData]);
}

const fakeAudio = Buffer.from([0xFF,0xFB,0x90,0x00, 0x00,0x00,0x00,0x00]); // 假 MP3 帧
const ncm = buildNCM(fakeAudio, {title:'测试', artist:'开发者', album:'单元测试'});

try {
  const result = convertNCM(new Uint8Array(ncm), 'auto');
  check('convertNCM 返回对象', result && result.data && result.format && result.meta);
  check('convertNCM 识别格式', ['mp3','flac','m4a'].includes(result.format));
  check('convertNCM 提取元数据 title', result.meta.title === '测试');
  check('convertNCM 提取元数据 artist', result.meta.artist === '开发者');
  check('convertNCM 输出数据长度合理', result.data.length > 0);
} catch (e) {
  check('convertNCM 不抛异常', false);
  console.log('   ↳', e.message);
}

// renderName 模板
check('renderName 基础模板', renderName('{artist} - {title}', {artist:'周杰伦',title:'晴天'}) === '周杰伦 - 晴天');
check('renderName 缺字段回退', renderName('{title}', {artist:'X'}) === '{title}' || renderName('{title}', {artist:'X'}) === '');
check('renderName 空模板', renderName('', {title:'A'}) === '');

// 文件名安全字符
const safe = renderName('{artist} - {title}', {artist:'A/B\\C:*?<>|', title:'test'});
check('renderName 文件名安全', !/[\\/:*?"<>|]/.test(safe));

// 批量
check('convertNCM 批量处理', (() => {
  try {
    const r = convertNCM(new Uint8Array(ncm), 'auto');
    return r && r.data;
  } catch(e) { return false; }
})());

console.log('='.repeat(50));
for (const [n,c] of checks) console.log((c?'  ✅ ':'  ❌ ') + n);
console.log('='.repeat(50));
console.log(`结果: ${passed}/${passed+failed}`);
process.exit(failed ? 1 : 0);
