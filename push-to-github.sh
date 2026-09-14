#!/bin/bash
# 一键推送到 GitHub
# 使用前: 在 GitHub 创建一个名为 ncm-decoder-pro 的空仓库 (不要初始化 README)
# 然后设置下面两个变量:
#   GITHUB_USER  - 你的 GitHub 用户名
#   GITHUB_TOKEN - 带 repo + workflow 权限的 Personal Access Token
#   或使用 SSH: 把 REPO_URL 改成 git@github.com:xxx/xxx.git

set -e

GITHUB_USER="${GITHUB_USER:-muxiniu}"
REPO_NAME="ncm-decoder-pro"
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

if [ -z "$GITHUB_TOKEN" ]; then
    echo "⚠️  未设置 GITHUB_TOKEN, 尝试用 SSH 推送"
    REPO_URL="git@github.com:${GITHUB_USER}/${REPO_NAME}.git"
else
    REPO_URL="https://${GITHUB_USER}:${GITHUB_TOKEN}@github.com/${GITHUB_USER}/${REPO_NAME}.git"
fi

cd "$(dirname "$0")"

git init 2>/dev/null || true
git checkout -B main
git config user.email "bot@ncm-decoder.local" 2>/dev/null || true
git config user.name "${GITHUB_USER}" 2>/dev/null || true

git add .
git commit -m "feat: NCM Decoder Pro v2.0 完整版" || echo "nothing to commit"

git remote remove origin 2>/dev/null || true
git remote add origin "$REPO_URL"

echo "🚀 推送到 ${REPO_URL} ..."
git push -u origin main --force

echo "✅ 推送完成! 前往 GitHub Actions 查看构建进度。"
