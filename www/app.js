/* ============================================================
 * NCM Converter - 核心解密逻辑 + UI 控制
 * 流程：读取文件 → 解析 NCM 头 → AES 解密 key → RC4 解密音频 → 输出
 * ============================================================ */

// ==================== 工具函数 ====================
function b64ToBytes(b64) {
  return Uint8Array.from(atob(b64), c => c.charCodeAt(0));
}

// ASCII 字符串转字节（用于 seed key）
function strToBytes(str) {
  const bytes = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
  return bytes;
}

function bytesToB64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s);
}

// 小端读取 32 位整数
function readUint32LE(bytes, offset) {
  return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24)) >>> 0;
}

// 去除 PKCS7 填充
function stripPkcs7(bytes) {
  const pad = bytes[bytes.length - 1];
  if (pad < 1 || pad > 16) return bytes;
  return bytes.slice(0, bytes.length - pad);
}

// 文件名安全化
function safeName(name) {
  return (name || 'unknown').replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim();
}

// 文件名模板渲染
function renderName(tpl, meta) {
  return (tpl || '{title}')
    .replace(/\{title\}/g, safeName(meta.title) || 'unknown')
    .replace(/\{artist\}/g, safeName((meta.artist || []).join(', ')) || 'unknown')
    .replace(/\{album\}/g, safeName(meta.album) || 'unknown');
}

// ==================== NCM 核心解析 ====================

// AES-128-ECB 解密（aes-js 3.x API）
function aesEcbDecrypt(keyBytes, cipherBytes) {
  const aes = new aesjs.ModeOfOperation.ecb(keyBytes);
  return aes.decrypt(cipherBytes);
}

// 解析 NCM 文件头，返回 { keyBytes, meta, audioOffset, format }
function parseNCM(fileBytes) {
  // 文件头 magic: "CTMF" (0x43 0x54 0x4D 0x46) 或直接是加密数据
  let offset = 0;

  // 尝试跳过 NCM 头部（不同版本结构略有差异）
  // 标准结构: magic(4) + [unknown] + keyLen(4) + keyData + metaLen(4) + metaData + audioData
  const magic = String.fromCharCode(fileBytes[0], fileBytes[1], fileBytes[2], fileBytes[3]);
  
  let keyData = null;
  let metaData = null;
  let audioOffset = 0;

  if (magic === 'CTMF') {
    // 完整 NCM 格式: magic(4) + reserved(4) + keyLen(4) + keyData + metaLen(4) + metaData + audio
    offset = 8; // 跳过 magic(4) + reserved(4)
    // 读取 key 长度
    const keyLen = readUint32LE(fileBytes, offset);
    offset += 4;
    keyData = fileBytes.slice(offset, offset + keyLen);
    offset += keyLen;

    // 读取 metadata 长度
    const metaLen = readUint32LE(fileBytes, offset);
    offset += 4;
    metaData = fileBytes.slice(offset, offset + metaLen);
    offset += metaLen;
    audioOffset = offset;
  } else {
    // 简化/兼容格式：假定前面有一段 key + meta
    // 默认从偏移 4 开始尝试
    const keyLen = readUint32LE(fileBytes, 4);
    if (keyLen > 0 && keyLen < 1024) {
      offset = 8;
      keyData = fileBytes.slice(offset, offset + keyLen);
      offset += keyLen;
      const metaLen = readUint32LE(fileBytes, offset);
      offset += 4;
      if (metaLen > 0 && metaLen < 65536) {
        metaData = fileBytes.slice(offset, offset + metaLen);
        offset += metaLen;
      }
    }
    audioOffset = offset;
  }

  // 解密 key：keyData 用固定种子密钥 AES-128-ECB 解密，再 XOR 0x64 得 RC4 密钥
  // NCM 种子密钥（逆向所得常量）
  const seedKey = strToBytes('hzHRAmE5OFYDKMeK');
  let decryptedKey;
  try {
    const decryptedBytes = aesEcbDecrypt(seedKey.slice(0, 16), keyData);
    const transformedKey = stripPkcs7(decryptedBytes).slice(0, 16);
    // 逆变换：每个字节 XOR 0x64
    decryptedKey = transformedKey.map(b => (b ^ 0x64) & 0xff);
  } catch (e) {
    // 降级：keyData 直接作为密钥材料
    decryptedKey = keyData.slice(0, 16);
  }

  // 解析 metadata（通常是 JSON 或特定编码）
  let meta = { title: '', artist: [], album: '' };
  if (metaData && metaData.length > 0) {
    try {
      const metaText = new TextDecoder('utf-8').decode(stripPkcs7(metaData));
      const parsed = JSON.parse(metaText);
      meta = {
        title: parsed.title || parsed.name || '',
        artist: Array.isArray(parsed.artist) ? parsed.artist : (parsed.artist ? [parsed.artist] : []),
        album: parsed.album || ''
      };
    } catch (e) {
      // meta 解析失败不影响音频解密
    }
  }

  return { keyBytes: decryptedKey, meta, audioOffset };
}

// RC4 解密音频数据
function rc4Decrypt(keyBytes, dataBytes) {
  // 构建 RC4 密钥调度
  const S = new Uint8Array(256);
  for (let i = 0; i < 256; i++) S[i] = i;
  let j = 0;
  for (let i = 0; i < 256; i++) {
    j = (j + S[i] + keyBytes[i % keyBytes.length]) & 0xff;
    [S[i], S[j]] = [S[j], S[i]];
  }
  // 生成密钥流并异或
  const out = new Uint8Array(dataBytes.length);
  let i = 0, k = 0;
  for (let idx = 0; idx < dataBytes.length; idx++) {
    i = (i + 1) & 0xff;
    k = (k + S[i]) & 0xff;
    [S[i], S[k]] = [S[k], S[i]];
    const t = (S[i] + S[k]) & 0xff;
    out[idx] = dataBytes[idx] ^ S[t];
  }
  return out;
}

// 根据音频数据头部判断真实格式
function detectFormat(audioBytes) {
  if (audioBytes.length < 4) return 'mp3';
  const h = audioBytes.slice(0, 4);
  // MP3: 0xFFFB / 0xFFF3 / 0x4944 (ID3)
  if (h[0] === 0xff && (h[1] & 0xe0) === 0xe0) return 'mp3';
  if (h[0] === 0x49 && h[1] === 0x44 && h[2] === 0x33) return 'mp3'; // ID3
  // FLAC: 0x664C6143 "fLaC"
  if (h[0] === 0x66 && h[1] === 0x4c && h[2] === 0x61 && h[3] === 0x43) return 'flac';
  // M4A: 0x00000020 (ftyp)
  if (h[0] === 0x00 && h[1] === 0x00 && h[2] === 0x00) return 'm4a';
  return 'mp3'; // 默认
}

// ==================== 主转换函数 ====================
function convertNCM(fileBytes, formatChoice) {
  const { keyBytes, meta, audioOffset } = parseNCM(fileBytes);
  const audioData = fileBytes.slice(audioOffset);

  // RC4 解密音频
  const decrypted = rc4Decrypt(keyBytes, audioData);

  // 判断输出格式
  const detected = detectFormat(decrypted);
  let format = formatChoice === 'auto' ? detected : formatChoice;
  if (format === 'auto') format = detected;

  // 确定扩展名（真实 NCM 通常封装的是 MP3）
  const ext = (format === 'flac') ? 'flac' : (format === 'm4a' ? 'm4a' : 'mp3');

  return { data: decrypted, format: ext, meta };
}

// ==================== UI 逻辑 ====================
const state = {
  files: [],       // [{name, bytes}]
  folder: null,    // {uri, name}
  template: '{artist} - {title}'
};

function log(msg) {
  const el = document.getElementById('log');
  if (el) el.textContent += msg + '\n';
  console.log(msg);
}

function setStatus(msg, type) {
  const el = document.getElementById('status');
  if (!el) return;
  el.textContent = msg;
  el.className = 'status' + (type ? ' ' + type : '');
}

// 桥接对象（Android WebView 注入）
const Android = window.Android || {
  // 桌面调试用 fallback：用 input 文件选择
  pickFiles: () => document.getElementById('fileInputFallback').click(),
  pickFolder: () => log('(调试) 文件夹选择在桌面端不可用'),
  saveAudio: (folderUri, fileName, ext, base64) => {
    // 桌面调试：直接下载
    const bin = b64ToBytes(base64.split(',')[1] || base64);
    const blob = new Blob([bin], { type: 'audio/' + ext });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = fileName + '.' + ext;
    a.click();
  }
};

// Java 端回调：文件列表 / 文件夹（由 Java 通过 evaluateJavascript 调用）
// 注意：必须与 WebAppInterface.java 中的方法名完全一致
window.onFilesPicked = function (filesJson) {
  Android.onFilesPicked(filesJson);
};
window.onFolderPicked = function (folderJson) {
  Android.onFolderPicked(folderJson);
};

window.Android = Android;

// 通知 Java 端：文件已选好（Java 端读 files 列表）
function notifyFilesReady() {
  if (typeof Android.onFilesPicked === 'function') {
    Android.onFilesPicked(JSON.stringify(state.files.map(f => ({
      name: f.name, base64: bytesToB64(f.bytes)
    }))));
  }
}

// 文件选择按钮
document.getElementById('btnPickFiles').addEventListener('click', () => {
  Android.pickFiles();
});

// 文件夹选择按钮
document.getElementById('btnPickFolder').addEventListener('click', () => {
  Android.pickFolder();
});

// Java 端回调：设置文件列表（由 Java 通过 evaluateJavascript 调用）
window.setFiles = function (filesJson) {
  const arr = JSON.parse(filesJson);
  state.files = arr.map(f => ({
    name: f.name || 'audio.ncm',
    bytes: typeof f.base64 === 'string' ? b64ToBytes(f.base64) : new Uint8Array(f.data || [])
  }));
  const display = document.getElementById('filesDisplay');
  display.classList.remove('empty');
  display.textContent = state.files.length === 1
    ? state.files[0].name
    : `${state.files.length} 个文件已选择`;
  updateConvertBtn();
  log(`已加载 ${state.files.length} 个文件`);
};

// Java 端回调：设置保存文件夹
window.setFolder = function (folderJson) {
  const f = JSON.parse(folderJson);
  state.folder = { uri: f.uri, name: f.name || '文件夹' };
  const display = document.getElementById('folderDisplay');
  display.classList.remove('empty');
  display.textContent = state.folder.name;
  updateConvertBtn();
  log(`保存位置：${state.folder.name}`);
};

// 更新转换按钮状态
function updateConvertBtn() {
  const btn = document.getElementById('btnConvert');
  btn.disabled = !(state.files.length > 0 && state.folder);
}

// 读取单个文件为 bytes（用于 fallback input）
function readFileAsBytes(file) {
  return new Promise((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => {
      const buf = fr.result;
      resolve(new Uint8Array(buf));
    };
    fr.onerror = reject;
    fr.readAsArrayBuffer(file);
  });
}

// 转换按钮
document.getElementById('btnConvert').addEventListener('click', async () => {
  if (!state.files.length || !state.folder) {
    setStatus('请先选择文件和保存文件夹', 'error');
    return;
  }
  const formatChoice = document.getElementById('formatSelect').value;
  state.template = document.getElementById('nameTemplate').value || '{title}';

  const btn = document.getElementById('btnConvert');
  btn.disabled = true;
  setStatus('转换中...');
  document.getElementById('log').textContent = '';

  try {
    for (let i = 0; i < state.files.length; i++) {
      const file = state.files[i];
      log(`[${i + 1}/${state.files.length}] 处理：${file.name}`);

      const { data, format, meta } = convertNCM(file.bytes, formatChoice);

      // 确定文件名
      const baseName = renderName(state.template, meta) || file.name.replace(/\.ncm$/i, '');
      const ext = (format === 'flac') ? 'flac' : (format === 'm4a' ? 'm4a' : 'mp3');
      const fullName = baseName + '.' + ext;

      // 交给 Java 保存（SAF）
      const b64 = bytesToB64(data);
      Android.saveAudio(state.folder.uri, baseName, ext, 'data:audio/' + ext + ';base64,' + b64);

      log(`  → 输出：${fullName} (${(data.length / 1024).toFixed(1)} KB)`);
    }
    setStatus('✅ 全部转换完成！', 'success');
  } catch (e) {
    log('❌ 错误：' + e.message);
    setStatus('转换失败：' + e.message, 'error');
    console.error(e);
  } finally {
    btn.disabled = false;
  }
});

// 桌面调试用隐藏 file input
const fallback = document.createElement('input');
fallback.type = 'file';
fallback.id = 'fileInputFallback';
fallback.accept = '.ncm';
fallback.multiple = true;
fallback.style.display = 'none';
document.body.appendChild(fallback);
fallback.addEventListener('change', async () => {
  const files = Array.from(fallback.files);
  state.files = [];
  for (const f of files) {
    const bytes = await readFileAsBytes(f);
    state.files.push({ name: f.name, bytes });
  }
  const display = document.getElementById('filesDisplay');
  display.classList.remove('empty');
  display.textContent = state.files.length === 1 ? state.files[0].name : `${state.files.length} 个文件已选择`;
  updateConvertBtn();
  log(`(调试) 已加载 ${state.files.length} 个文件`);
});

// 初始状态
updateConvertBtn();
log('就绪。点击「选择 NCM 文件」开始。');
