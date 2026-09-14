# 测试套件

本目录包含针对 NCM 解密核心与前后端接口对齐的验证脚本。
**这些脚本只在开发调试时使用，运行 App 不需要它们。**

## 文件说明

| 脚本 | 作用 |
|------|------|
| `test_core.js` | 端到端解密逻辑测试（AES、RC4、NCM 解析、文件名渲染） |
| `verify.js` | 前后端接口对齐验证（JS↔Java 方法签名、参数顺序） |
| `check_structure.js` | 项目结构完整性检查 |

## 运行

需要 Node.js 环境与 `aes-js`：

```bash
cd ../www
npm install aes-js   # 仅首次

node ../tests/test_core.js
node ../tests/verify.js
node ../tests/check_structure.js
```

预期：全部测试 PASS。
