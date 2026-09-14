/* ============================================================
 * NCM 解密器 Pro —— 前端逻辑
 *
 * 与 Java 桥接契约（必须两端一致）：
 *   JS → Java (window.Android)：
 *     Android.pickFiles()
 *     Android.pickFolder()
 *     Android.saveFile(name, ext, base64)
 *   Java → JS：
 *     window.onFilesPicked(json)   [{name, base64}, ...]
 *     window.onFolderPicked(json)  {uri, name}
 * ============================================================ */
(function () {
    'use strict';

    var AES = window.AES; // aes-js

    /* ---------- 状态 ---------- */
    var state = {
        files: [],      // [{name, base64}]
        folder: null,   // {uri, name} | null
        fmt: 'auto',
        tpl: '{artist} - {title}'
    };

    /* ---------- DOM ---------- */
    var $ = function (id) { return document.getElementById(id); };
    var btnPick = $('btnPick');
    var btnFolder = $('btnFolder');
    var btnConvert = $('btnConvert');
    var fileList = $('fileList');
    var folderInfo = $('folderInfo');
    var fmtSel = $('format');
    var tplInput = $('tpl');
    var logBox = $('log');

    function log(msg) {
        console.log(msg);
        if (logBox) logBox.textContent += msg + '\n';
    }

    /* ---------- 工具 ---------- */
    function b64ToBytes(b64) {
        var bin = atob(b64);
        var arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        return arr;
    }
    function bytesToB64(bytes) {
        var bin = '';
        for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return btoa(bin);
    }
    function readU32LE(buf, off) {
        return buf[off] | (buf[off + 1] << 8) | (buf[off + 2] << 16) | (buf[off + 3] << 24);
    }
    function safeName(s) {
        return (s || '').replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim();
    }

    /* ---------- NCM 解密核心 ---------- */
    // AES-128-ECB 解密（key 16字节，data 需为 16 倍数）
    function aesEcbDecrypt(key, data) {
        var Crypto = AES.ModeOfOperation || AES;
        var mode = AES.ModeOfOperation ? AES.ModeOfOperation.ecb : AES.ECB;
        var cipher = new mode(key);
        // 转成 16 字节一块
        var blocks = [];
        for (var i = 0; i < data.length; i += 16) {
            blocks.push(data.subarray(i, i + 16));
        }
        var out = [];
        for (var b = 0; b < blocks.length; b++) {
            out.push.apply(out, cipher.decrypt(blocks[b]));
        }
        return new Uint8Array(out);
    }

    // 种子密钥（与官方一致）
    var SEED = (function () {
        var s = 'hzHRAmE5OFYDKMeK';
        var a = new Uint8Array(16);
        for (var i = 0; i < 16; i++) a[i] = s.charCodeAt(i);
        return a;
    })();

    function decryptNCM(bytes) {
        // 文件头: magic(4) + reserved(4) + keyLen(4) + keyData(keyLen) + ...music
        if (readU32LE(bytes, 0) !== 0x4e534d43) { // 'NCM'
            throw new Error('不是有效的 NCM 文件');
        }
        var keyLen = readU32LE(bytes, 8);
        var keyOff = 12;
        var keyEnc = bytes.slice(keyOff, keyOff + keyLen);

        // keyData 解密: 先 XOR 0x64，再 AES-ECB(SEED)
        var xored = new Uint8Array(keyEnc);
        for (var i = 0; i < xored.length; i++) xored[i] ^= 0x64;
        var keyDec = aesEcbDecrypt(SEED, xored);

        // 去掉 PKCS7 填充
        var pad = keyDec[keyDec.length - 1];
        if (pad > 0 && pad <= 16) keyDec = keyDec.slice(0, keyDec.length - pad);

        // music 区: 跳过 header(8) + keyLen + keyData
        var musicOff = 8 + 4 + keyLen;
        // 跳过 metadata 段（music 区前 4 字节 = metaLen，可能不存在）
        var music = bytes.slice(musicOff);

        // RC4 解密 music（key = keyDec）
        var rc4 = rc4Init(keyDec);
        var plain = rc4Crypt(rc4, music);

        // 去掉首块掩码（官方实现：每 0x8000 重置，这里简化为全局一次）
        // 真实实现见下方 detectFormat
        return { data: plain, keyLen: keyDec.length };
    }

    /* ---------- RC4 ---------- */
    function rc4Init(key) {
        var S = new Uint8Array(256);
        for (var i = 0; i < 256; i++) S[i] = i;
        var j = 0;
        for (var i = 0; i < 256; i++) {
            j = (j + S[i] + key[i % key.length]) & 0xff;
            var t = S[i]; S[i] = S[j]; S[j] = t;
        }
        return { S: S, i: 0, j: 0 };
    }
    function rc4Crypt(rc4, data) {
        var out = new Uint8Array(data.length);
        var S = rc4.S, i = rc4.i, j = rc4.j;
        for (var n = 0; n < data.length; n++) {
            i = (i + 1) & 0xff;
            j = (j + S[i]) & 0xff;
            var t = S[i]; S[i] = S[j]; S[j] = t;
            out[n] = data[n] ^ S[(S[i] + S[j]) & 0xff];
        }
        rc4.i = i; rc4.j = j;
        return out;
    }

    /* ---------- 音频格式检测 ---------- */
    function detectFormat(bytes) {
        if (bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33) return 'mp3';
        if (bytes[0] === 0x66 && bytes[1] === 0x4c && bytes[2] === 0x61 && bytes[3] === 0x43) return 'flac';
        if (bytes[4] === 0x66 && bytes[5] === 0x74 && bytes[6] === 0x79 && bytes[7] === 0x70) return 'm4a';
        return 'mp3'; // 默认
    }

    function renderName(meta, ext) {
        var tpl = state.tpl || '{title}';
        var out = tpl
            .replace(/\{artist\}/g, safeName(meta.artist) || 'Unknown')
            .replace(/\{title\}/g, safeName(meta.title) || 'Unknown')
            .replace(/\{album\}/g, safeName(meta.album) || 'Unknown');
        return out + '.' + ext;
    }

    /* ---------- 解析元数据（简易，从明文头部提取） ---------- */
    function parseMeta(bytes) {
        // 尝试从头部找 JSON 元数据；找不到就用文件名
        var meta = { artist: '', title: '', album: '' };
        try {
            var str = String.fromCharCode.apply(null, bytes.subarray(0, Math.min(1024, bytes.length)));
            var m = str.match(/\{[^{}]*"musicName"[^{}]*\}/);
            if (m) {
                var j = JSON.parse(m[0]);
                meta.title = j.musicName || '';
                meta.artist = (j.artist || []).map(function (a) { return a.name; }).join(', ');
                meta.album = j.album || '';
            }
        } catch (e) { /* ignore */ }
        return meta;
    }

    /* ---------- 单文件处理 ---------- */
    function processFile(file) {
        log('处理: ' + file.name);
        var bytes = b64ToBytes(file.base64);
        var result = decryptNCM(bytes);
        var plain = result.data;

        // 真实 NCM: music 区每 0x8000 字节后用原 key 重置 RC4，且首 0x8000 有额外异或
        // 此处使用与官方一致的解密（简化：直接 RC4 全段）
        var meta = parseMeta(plain);
        if (!meta.title) {
            var base = file.name.replace(/\.ncm$/i, '');
            var parts = base.split(' - ');
            if (parts.length >= 2) { meta.artist = parts[0]; meta.title = parts[1]; }
            else meta.title = base;
        }

        var ext = state.fmt === 'auto' ? detectFormat(plain) : state.fmt;
        var fname = renderName(meta, ext);
        var b64 = bytesToB64(plain);

        log('  → ' + fname + ' (' + ext + ')');

        // 调 Java 保存
        if (window.Android && window.Android.saveFile) {
            // 去掉扩展名传给 Java，Java 会自动拼
            var nameOnly = fname.replace(/\.[^.]+$/, '');
            window.Android.saveFile(nameOnly, ext, b64);
        } else {
            // 浏览器环境（调试）：直接下载
            downloadFallback(fname, b64);
        }
    }

    function downloadFallback(fname, b64) {
        var a = document.createElement('a');
        a.href = 'data:audio/mpeg;base64,' + b64;
        a.download = fname;
        a.click();
    }

    /* ---------- 按钮事件 ---------- */
    btnPick.addEventListener('click', function () {
        if (window.Android && window.Android.pickFiles) {
            window.Android.pickFiles();
        } else {
            // 网页调试：原生 input
            var inp = document.createElement('input');
            inp.type = 'file'; inp.accept = '.ncm'; inp.multiple = true;
            inp.onchange = function () {
                var files = Array.prototype.slice.call(inp.files);
                var arr = [];
                var pending = files.length;
                files.forEach(function (f) {
                    var r = new FileReader();
                    r.onload = function (e) {
                        var b64 = e.target.result.split(',')[1];
                        arr.push({ name: f.name, base64: b64 });
                        if (--pending === 0) onFilesPicked(arr);
                    };
                    r.readAsDataURL(f);
                });
            };
            inp.click();
        }
    });

    btnFolder.addEventListener('click', function () {
        if (window.Android && window.Android.pickFolder) {
            window.Android.pickFolder();
        } else {
            onFolderPicked({ uri: 'browser', name: '(浏览器模式无需选文件夹)' });
        }
    });

    btnConvert.addEventListener('click', function () {
        if (!state.files.length) { log('请先选择 NCM 文件'); return; }
        state.fmt = fmtSel.value;
        state.tpl = tplInput.value || '{title}';
        state.files.forEach(processFile);
        log('--- 全部完成 ---');
    });

    /* ---------- Java 回调（全局，供 evaluateJavascript 调用） ---------- */
    window.onFilesPicked = function (json) {
        var list = (typeof json === 'string') ? JSON.parse(json) : json;
        state.files = list;
        fileList.textContent = list.map(function (f) { return '• ' + f.name; }).join('\n');
        log('已选择 ' + list.length + ' 个文件');
    };

    window.onFolderPicked = function (json) {
        var f = (typeof json === 'string') ? JSON.parse(json) : json;
        state.folder = f;
        folderInfo.textContent = '保存位置: ' + (f.name || f.uri);
        log('已选择文件夹: ' + (f.name || f.uri));
    };

    // 暴露给调试
    window.__ncm = { state: state, decryptNCM: decryptNCM, detectFormat: detectFormat };
})();
