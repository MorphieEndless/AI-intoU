#!/usr/bin/env bash
# 在仓库根目录运行 bash deploy/setup-server.sh；仅配置单用户自部署。
set -euo pipefail
umask 077
cd "$(dirname "$0")/../server"

for cmd in python3 docker curl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[错误] 缺少 $cmd，请先按 docs/DEPLOY.md 安装依赖。" >&2
    exit 1
  fi
done
docker compose version >/dev/null
docker info >/dev/null

if [ ! -e .env ]; then
  python3 - <<'PY'
from pathlib import Path
import secrets

values = {
    "SB_SECRET_KEY": secrets.token_hex(32),
    "SB_STATIC_BEARER_TOKEN": secrets.token_urlsafe(32),
    "SB_REQUIRE_MCP_AUTH": "true",
    "SB_REGISTRATION_OPEN": "false",
    "SB_DB_PATH": "/data/signal_bridge.db",
    "SB_PATTERNS_DIR": "/data/patterns",
    "SB_BIND_ADDRESS": "127.0.0.1",
}
lines = Path(".env.example").read_text().splitlines()
seen = set()
for i, line in enumerate(lines):
    key = line.partition("=")[0]
    if key in values:
        lines[i] = f"{key}={values[key]}"
        seen.add(key)
lines.extend(f"{key}={value}" for key, value in values.items() if key not in seen)
# 不执行或 source .env；独占创建，避免意外覆盖已有配置。
with open(".env", "x") as env:
    env.write("\n".join(lines) + "\n")
print("[1/3] 已生成 .env（权限 600），强制认证，关闭注册，数据存入 /data。")
print("Bearer Token（请私下保存，App / MCP 共用，勿截图外传）")
print(values["SB_STATIC_BEARER_TOKEN"])
PY
else
  echo "[1/3] .env 已存在，保留原配置和密钥。"
  echo "      如需找回 Token，请在服务器私下查看 .env 的 SB_STATIC_BEARER_TOKEN。"
fi

# 旧配置需要人工迁移，不能悄悄轮换密钥或切换数据库路径。
python3 - <<'PY'
from pathlib import Path
import sys

values = {}
for line in Path(".env").read_text().splitlines():
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    key, sep, value = line.partition("=")
    if sep:
        values[key.strip()] = value.strip().strip("\"'")
if not values.get("SB_SECRET_KEY") or values["SB_SECRET_KEY"] == "your-secret-key-here":
    sys.exit("[错误] SB_SECRET_KEY 未正确配置，已停止部署。")
if len(values.get("SB_STATIC_BEARER_TOKEN", "")) < 32:
    sys.exit("[错误] 单用户部署需要至少 32 字符的 SB_STATIC_BEARER_TOKEN；请检查现有 .env。")
if values.get("SB_REQUIRE_MCP_AUTH", "false").lower() != "true":
    sys.exit("[错误] 请先将 .env 中 SB_REQUIRE_MCP_AUTH 设为 true，再运行脚本。")
if values.get("SB_REGISTRATION_OPEN", "true").lower() != "false":
    print("[警告] 当前仍允许公开注册；单用户部署建议设 SB_REGISTRATION_OPEN=false。", file=sys.stderr)
for key, expected in (("SB_DB_PATH", "/data/signal_bridge.db"), ("SB_PATTERNS_DIR", "/data/patterns")):
    if values.get(key) != expected:
        sys.exit(f"[错误] {key} 尚未配置到持久化卷。请先按 docs/DEPLOY.md 备份并迁移旧数据，勿直接重建容器。")
PY

echo "[2/3] 构建并启动容器..."
docker compose up -d --build

echo "[3/3] 等待健康检查（最多 30 次，每次请求超时 3 秒）..."
for attempt in $(seq 1 30); do
  if curl --max-time 3 -fsS http://127.0.0.1:8420/health >/dev/null; then
    echo "部署完成。/health 已通过；这不代表手机或 BLE 已连接。"
    echo "默认仅监听本机 127.0.0.1:8420，公网请配宿主机 Caddy 反代。"
    echo "下一步见 docs/DEPLOY.md；App 与 MCP 使用同一个静态 Bearer Token。"
    exit 0
  fi
  sleep 2
done
echo "[错误] 健康检查超时。进入 server 目录运行 docker compose logs --tail=100。" >&2
exit 1
