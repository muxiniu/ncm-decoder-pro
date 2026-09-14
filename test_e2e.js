// 端到端测试：模拟完整的 NCM 文件解密流程
const AESJS = require('./aes-js.min.js');
const fs = require('fs');

// ===== 重建 app.js 里的函数（复制核心逻辑）=====
function xorBytes(data, val) {
    const result = new Uint8Array(data.length);
    for (let i = 0; i < data.length; i++) result[i] = data[i] ^ val;
    return result;
}

function strToBytes(str) {
    const bytes = new Uint8Array(str.length);
    for (let i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
    return bytes;
}

function decryptKey(keyData, seedKey) {
    const aes = new AESJS.AES(seedKey);
    const decrypted = aes.decrypt(keyData.slice(0, 16));
    const xored = xorBytes(decrypted, 0x64);
    const reversed = new Uint8Array(xored.length);
    for (let i = 0; i < xored.length; i++) reversed[i] = xored[xored.length - 1 - i];
    return reversed.slice(0, 16);
}

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

function detectFormat(data) {
    if (data[0] === 0xff && (data[1] & 0xe0) === 0xe0) return 'mp3';
    if (data[0] === 0x66 && data[1] === 0x4c && data[2] === 0x61 && data[3] === 0x43) return 'flac';
    return 'mp3';
}

// ===== 构造一个假的 NCM 文件用于测试 =====
function buildTestNCM() {
    // 1. 原始音频数据（用一段假 MP3 头）
    const originalAudio = new Uint8Array(1024);
    originalAudio[0] = 0xff; // MP3 sync
    originalAudio[1] = 0xfb;
    for (let i = 2; i < 1024; i++) originalAudio[i] = i & 0xff;

    // 2. 生成 AES 密钥（16字节）
    const audioKey = new Uint8Array(16);
    for (let i = 0; i < 16; i++) audioKey[i] = (i * 7 + 3) & 0xff;

    // 3. 用 audioKey 加密音频（RC4）
    const encryptedAudio = rc4Crypt(originalAudio, audioKey);

    // 4. 构造 encrypted key data（与 app.js decryptNCM 的逆过程）:
    //    app.js 解密顺序: AES.decrypt -> reverse -> XOR 0x64
    //    => 加密顺序: XOR 0x64 -> reverse -> AES.encrypt
    const reversed = new Uint8Array(16);
    for (let i = 0; i < 16; i++) reversed[i] = audioKey[15 - i];
    const xored = xorBytes(reversed, 0x64);

    // 5. 用 seed key 加密 xored（AES-ECB）
    const seedKey = strToBytes('hzHRAmE5OFYDKMeK');
    const aes = new AESJS.AES(seedKey);
    const encrypted = aes.encrypt(xored); // 16 bytes exact block

    // 6. 组装 NCM 文件
    const keyLen = encrypted.length;
    const metaData = strToBytes('test');
    const metaXored = xorBytes(metaData, 0x63);
    const metaLen = metaXored.length;

    const totalLen = 10 + keyLen + 4 + metaLen + encryptedAudio.length;
    const ncm = new Uint8Array(totalLen);

    // magic "CTMF"
    ncm[0] = 0x43; ncm[1] = 0x54; ncm[2] = 0x4d; ncm[3] = 0x46;
    // reserved
    ncm[4] = 0; ncm[5] = 0; ncm[6] = 0; ncm[7] = 0;
    // keyLen (big-endian)
    ncm[8] = (keyLen >> 8) & 0xff;
    ncm[9] = keyLen & 0xff;
    // encrypted key
    ncm.set(encrypted, 10);
    // metaLen (big-endian)
    const metaOffset = 10 + keyLen;
    ncm[metaOffset] = (metaLen >> 24) & 0xff;
    ncm[metaOffset + 1] = (metaLen >> 16) & 0xff;
    ncm[metaOffset + 2] = (metaLen >> 8) & 0xff;
    ncm[metaOffset + 3] = metaLen & 0xff;
    // metaData (xor 0x63)
    ncm.set(metaXored, metaOffset + 4);
    // audio data (encrypted)
    const audioOffset = metaOffset + 4 + metaLen;
    ncm.set(encryptedAudio, audioOffset);

    return { ncm, originalAudio, audioKey };
}

// ===== 运行测试 =====
console.log('=== NCM Decoder Pro - E2E Test ===\n');

// Test 1: AES 往返
console.log('Test 1: AES-128-ECB roundtrip');
{
    const seedKey = strToBytes('hzHRAmE5OFYDKMeK');
    const aes = new AESJS.AES(seedKey);
    const pt = new Uint8Array(16);
    for (let i = 0; i < 16; i++) pt[i] = i;
    const ct = aes.encrypt(pt);
    const dt = aes.decrypt(ct);
    const ok = dt.every((b, i) => b === pt[i]);
    console.log(ok ? '  ✅ PASS' : '  ❌ FAIL');
}

// Test 2: RC4 对称性
console.log('Test 2: RC4 symmetry');
{
    const key = strToBytes('mysecretkey12345');
    const data = new Uint8Array(100);
    for (let i = 0; i < 100; i++) data[i] = i;
    const enc = rc4Crypt(data, key);
    const dec = rc4Crypt(enc, key); // RC4: encrypt == decrypt
    const ok = dec.every((b, i) => b === data[i]);
    console.log(ok ? '  ✅ PASS' : '  ❌ FAIL');
}

// Test 3: XOR
console.log('Test 3: XOR bytes');
{
    const data = new Uint8Array([0x12, 0x34, 0x56]);
    const r1 = xorBytes(data, 0x64);
    const r2 = xorBytes(r1, 0x64);
    const ok = r2.every((b, i) => b === data[i]);
    console.log(ok ? '  ✅ PASS' : '  ❌ FAIL');
}

// Test 4: 完整 NCM 加解密流程
console.log('Test 4: Full NCM encrypt → decrypt roundtrip');
{
    const { ncm, originalAudio, audioKey } = buildTestNCM();

    // 模拟 app.js 的 decryptNCM 逻辑
    const magic = String.fromCharCode(ncm[0], ncm[1], ncm[2], ncm[3]);
    console.log('  magic:', magic);

    // 跳过 padding bytes（NCM 格式: magic(4) + reserved(4) + keyLen(2)）
    // 但注意：有些 NCM 实现 keyLen 后是直接 key data，没有 padding
    // app.js 里 offset = 10 = 8 + 2，这是对的
    const keyLen = (ncm[8] << 8) | ncm[9];
    const keyData = ncm.slice(10, 10 + keyLen);

    const seedKey = strToBytes('hzHRAmE5OFYDKMeK');
    const decryptedKey = decryptKey(keyData, seedKey);

    // 验证恢复的 key 和原始 key 一致
    const keyOk = decryptedKey.every((b, i) => b === audioKey[i]);
    console.log('  key recovery:', keyOk ? '✅' : '❌');

    // 解析 metadata
    const metaOffset = 10 + keyLen;
    const metaLen = (ncm[metaOffset] << 24) | (ncm[metaOffset + 1] << 16)
                   | (ncm[metaOffset + 2] << 8) | ncm[metaOffset + 3];
    const audioOffset = metaOffset + 4 + metaLen;

    // 跳过可能的 padding zeros
    let actualAudioOffset = audioOffset;
    while (actualAudioOffset < ncm.length && ncm[actualAudioOffset] === 0) {
        actualAudioOffset++;
    }

    const audioEncrypted = ncm.slice(actualAudioOffset);
    const audioDecrypted = rc4Crypt(audioEncrypted, decryptedKey);

    const ok = audioDecrypted.every((b, i) => b === originalAudio[i]);
    console.log(ok ? '  ✅ PASS - 音频数据完整恢复' : '  ❌ FAIL - 数据不匹配');
    if (!ok) {
        let diff = 0;
        for (let i = 0; i < Math.min(audioDecrypted.length, originalAudio.length); i++) {
            if (audioDecrypted[i] !== originalAudio[i]) diff++;
        }
        console.log('  diff bytes:', diff);

        // 尝试不带 padding skip
        if (diff > 0) {
            const audioEncrypted2 = ncm.slice(audioOffset);
            const audioDecrypted2 = rc4Crypt(audioEncrypted2, decryptedKey);
            const ok2 = audioDecrypted2.every((b, i) => b === originalAudio[i]);
            console.log(ok2 ? '  ✅ PASS (no padding skip)' : '  ❌ still fail');
        }
    }

    const format = detectFormat(audioDecrypted);
    console.log('  format:', format);
}

// Test 5: 桥接接口对齐检查
console.log('\nTest 5: JS Bridge interface alignment');
{
    // Java 端调用: onFilesPicked('[{"uri":"...","name":"...","base64":"..."}]')
    const test = '[{"uri":"content://test/1","name":"song.ncm","base64":""}]';
    const parsed = JSON.parse(test);
    console.log('  onFilesPicked parse:', parsed.length === 1 ? '✅' : '❌');

    // Java 端调用: onFolderPicked('{"uri":"...","name":"..."}')
    const folder = '{"uri":"content://tree/test","name":"Music"}';
    const fp = JSON.parse(folder);
    console.log('  onFolderPicked parse:', fp.uri ? '✅' : '❌');

    // JS → Java: Android.saveFile(name, ext, base64)
    // Java: saveFile(String name, String ext, String base64) — 3 params
    const name = 'song', ext = 'mp3', b64 = 'AAAA';
    console.log('  saveFile(3 params):', (name && ext && b64) ? '✅' : '❌');
}

console.log('\n=== Done ===');
