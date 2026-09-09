#!/usr/bin/env bash
# AI-intoU (樱趣) — 一键部署 server 端
# 用法：在 VPS 上 clone 本仓库后执行  bash deploy/setup-server.sh
set -euo pipefail

cd "$(dirname "$0")/../server"

echo "=============================================="
echo "  AI-intoU / 樱趣 — Server 一键部署"
echo "=============================================="

# 1. 准备 .env
if [ ! -f .env ]; then
  cp .env.example .env
  # 自动生成密钥
  SECRET=$(python3 -c "import secrets; print(secrets.token_hex(32))")
  STATIC=$(python3 -c "import secrets; print(secrets.token_urlsafe(32))")
  sed -i.bak "s/^SB_SECRET_KEY=.*/SB_SECRET_KEY=$SECRET/" .env
  sed -i.bak "s/^SB_STATIC_BEARER_TOKEN=.*/SB_STATIC_BEARER_TOKEN=$STATIC/" .env
  rm -f .env.bak
  echo "[1/3] .env 已生成，密钥已自动填充"
  echo "      你的静态 Bearer Token（App / MCP 都要用，请抄下来）："
  echo "      $STATIC"
else
  echo "[1/3] .env 已存在，跳过生成"
fi

# 2. 检查 docker
if ! command -v docker >/dev/null 2>&1; then
  echo "[错误] 没找到 docker。先装：curl -fsSL https://get.docker.com | sh" >&2
  exit 1
fi

# 3. 起服务
echo "[2/3] 构建并启动容器..."
docker compose up -d --build

# 4. 健康检查
echo "[3/3] 健康检查..."
sleep 3
curl -fsS http://localhost:8420/health || {
  echo "[警告] health 没通过，用 docker compose logs 查看日志" >&2
  exit 1
}

echo ""
echo "=============================================="
echo "  部署完成！"
echo "  - Health:  http://localhost:8420/health"
echo "  - 要 HTTPS 公网接入，请配 Caddy/Nginx 反代到 8420"
echo "  - Android App 和 RikkaHub 都用上面的静态 Bearer Token"
echo "=============================================="
