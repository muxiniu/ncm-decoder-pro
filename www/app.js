// ===== 全局状态 =====
let selectedFiles = [];
let outputFolder = null;

// ===== 日志 =====
function log(msg, type) {
    const el = document.getElementById('log');
    const line = document.createElement('div');
    if (type) line.className = type;
    line.textContent = '> ' + msg;
    el.appendChild(line);
    el.scrollTop = el.scrollHeight;
    console.log(msg);
}

// ===== 调用原生文件选择器 =====
function pickFiles() {
    log('打开文件选择器...');
    if (window.Android && window.Android.pickFiles) {
        window.Android.pickFiles();
    } else {
        log('Android 桥接不可用', 'err');
    }
}

function pickFolder() {
    log('打开文件夹选择器...');
    if (window.Android && window.Android.pickFolder) {
        window.Android.pickFolder();
    } else {
        log('Android 桥接不可用', 'err');
    }
}

// ===== 原生回调：文件列表 =====
window.onFilesPicked = function(json) {
    try {
        const files = JSON.parse(json);
        selectedFiles = files;
        document.getElementById('filesStatus').textContent =
            '已选择 ' + files.length + ' 个文件';
        const list = document.getElementById('fileList');
        list.innerHTML = '';
        files.forEach(f => {
            const div = document.createElement('div');
            div.textContent = '📄 ' + (f.name || 'unknown');
            list.appendChild(div);
        });
        log('已选择 ' + files.length + ' 个文件', 'ok');
        checkReady();
    } catch (e) {
        log('解析文件列表失败: ' + e.message, 'err');
    }
};

// ===== 原生回调：文件夹 =====
window.onFolderPicked = function(json) {
    try {
        const folder = JSON.parse(json);
        outputFolder = folder;
        document.getElementById('folderStatus').textContent =
            '保存到: ' + (folder.name || '已选择');
        log('输出文件夹: ' + (folder.name || folder.uri), 'ok');
        checkReady();
    } catch (e) {
        log('解析文件夹失败: ' + e.message, 'err');
    }
};

function checkReady() {
    const btn = document.getElementById('btnConvert');
    btn.disabled = !(selectedFiles.length > 0 && outputFolder);
}

// ===== 开始转换 =====
function startConvert() {
    if (!selectedFiles.length || !outputFolder) {
        log('请先选择文件和保存位置', 'err');
        return;
    }

    log('开始转换 ' + selectedFiles.length + ' 个文件...');

    selectedFiles.forEach((file, idx) => {
        processFile(file, idx).catch(e => {
            log('文件 ' + (idx + 1) + ' 失败: ' + e.message, 'err');
        });
    });
}

// ===== 处理单个文件 =====
async function processFile(file, idx) {
    log('[' + (idx + 1) + '/' + selectedFiles.length + '] 处理: ' + file.name);

    // 读取文件内容（base64）
    if (!file.base64) {
        throw new Error('文件内容为空，需要通过原生读取');
    }

    // 解码 base64
    const binary = base64ToArrayBuffer(file.base64);
    const data = new Uint8Array(binary);

    // 解析 NCM
    log('  解析 NCM 格式...');
    const result = decryptNCM(data);

    // 生成输出文件名
    const baseName = (result.metadata.title || file.name.replace(/\.ncm$/i, '')) || 'output';
    const ext = result.format === 'flac' ? 'flac' : (result.format === 'm4a' ? 'm4a' : 'mp3');
    const outName = sanitizeName(baseName) + '.' + ext;

    log('  输出: ' + outName + ' (' + result.format + ')', 'ok');

    // 通过原生保存
    if (window.Android && window.Android.saveFile) {
        const b64 = arrayBufferToBase64(result.audioData.buffer);
        window.Android.saveFile(outName.replace('.' + ext, ''), ext, b64);
        log('  已请求保存: ' + outName, 'ok');
    } else {
        // 浏览器环境 fallback：直接下载
        downloadAsFile(result.audioData, outName);
        log('  已下载: ' + outName, 'ok');
    }
}

// ===== NCM 解密核心 =====
function decryptNCM(data) {
    // NCM 文件格式:
    // [0-3]   magic "CTMF"
    // [4-7]   reserved
    // [8-...] encrypted key + audio data

    const magic = String.fromCharCode(data[0], data[1], data[2], data[3]);
    if (magic !== 'CTMF') {
        throw new Error('不是有效的 NCM 文件 (magic=' + magic + ')');
    }

    // 解析密钥长度
    let offset = 10; // skip magic(4) + reserved(4) + keyLen(2)
    const keyLen = (data[8] << 8) | data[9];

    // 读取加密的密钥数据
    const keyData = data.slice(10, 10 + keyLen);

    // 解密密钥（XOR with 0x64, then reverse）
    const seedKey = strToBytes('hzHRAmE5OFYDKMeK'); // 16 bytes
    const decryptedKey = decryptKey(keyData, seedKey);

    // 解析元数据长度
    offset = 10 + keyLen;
    const metaLen = (data[offset] << 24) | (data[offset + 1] << 16)
                   | (data[offset + 2] << 8) | data[offset + 3];
    offset += 4;

    let metadata = {};
    if (metaLen > 0) {
        const metaData = data.slice(offset, offset + metaLen);
        const metaText = bytesToStr(xorBytes(metaData, 0x63));
        try {
            metadata = JSON.parse(metaText);
        } catch (e) {
            log('  元数据解析失败（非致命）', 'err');
        }
    }
    offset += metaLen;

    // 跳过填充
    while (offset < data.length && data[offset] === 0) offset++;

    // 剩余是加密的音频数据
    const audioEncrypted = data.slice(offset);

    // 用解密后的密钥解密音频（RC4）
    const audioData = rc4Crypt(audioEncrypted, decryptedKey);

    // 检测音频格式
    const format = detectFormat(audioData);

    return { audioData, format, metadata };
}

// ===== 密钥解密 =====
// NCM 密钥加密流程（逆向即为解密）:
//   加密: reverse(key) → xor 0x64 → AES-128-ECB(seedKey)
//   解密: AES-128-ECB(seedKey) → xor 0x64 → reverse
function decryptKey(keyData, seedKey) {
    // Step 1: AES-128-ECB decrypt with seed key
    const aes = new AESJS.AES(seedKey);
    const decrypted = aes.decrypt(keyData.slice(0, 16));
    // Step 2: XOR with 0x64
    const xored = xorBytes(decrypted, 0x64);
    // Step 3: reverse byte order
    const reversed = new Uint8Array(xored.length);
    for (let i = 0; i < xored.length; i++) {
        reversed[i] = xored[xored.length - 1 - i];
    }
    return reversed.slice(0, 16);
}

// ===== RC4 加解密 =====
function rc4Crypt(data, key) {
    const S = new Uint8Array(256);
    for (let i = 0; i < 256; i++) S[i] = i;

    let j = 0;
    for (let i = 0; i < 256; i++) {
        j = (j + S[i] + key[i % key.length]) & 0xff;
        [S[i], S[j]] = [S[j], S[i]];
    }

    const result = new Uint8Array(data.length);
    let i = 0, k = 0;
    for (let idx = 0; idx < data.length; idx++) {
        i = (i + 1) & 0xff;
        k = (k + S[i]) & 0xff;
        [S[i], S[k]] = [S[k], S[i]];
        const t = (S[i] + S[k]) & 0xff;
        result[idx] = data[idx] ^ S[t];
    }
    return result;
}

// ===== 工具函数 =====
function xorBytes(data, val) {
    const result = new Uint8Array(data.length);
    for (let i = 0; i < data.length; i++) {
        result[i] = data[i] ^ val;
    }
    return result;
}

function strToBytes(str) {
    const bytes = new Uint8Array(str.length);
    for (let i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
    return bytes;
}

function bytesToStr(bytes) {
    let s = '';
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return s;
}

function detectFormat(data) {
    // ID3 = MP3, fLaC = FLAC, ....ftyp = M4A
    if (data[0] === 0xff && (data[1] & 0xe0) === 0xe0) return 'mp3';
    if (data[0] === 0x66 && data[1] === 0x4c && data[2] === 0x61 && data[3] === 0x43) return 'flac';
    if (data[4] === 0x66 && data[5] === 0x74 && data[6] === 0x79 && data[7] === 0x70) return 'm4a';
    // 检查是否有 ftyp box
    const view = new DataView(data.buffer);
    for (let i = 0; i < Math.min(data.length - 8, 100); i++) {
        if (view.getUint32(i) === 0x66747970) return 'm4a'; // 'ftyp'
    }
    return 'mp3'; // 默认
}

function sanitizeName(name) {
    return name.replace(/[\\/:*?"<>|]/g, '_').substring(0, 200);
}

// ===== Base64 互转 =====
function arrayBufferToBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
}

function base64ToArrayBuffer(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
}

function downloadAsFile(data, filename) {
    const blob = new Blob([data], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

// ===== 暴露给原生的全局回调 =====
window.Android = window.Android || {};
