#!/usr/bin/env bash
# 在仓库根目录运行 bash deploy/setup-server.sh。
# 首次安装：生成 .env → 构建启动 → 创建 owner 账号（随机密码只打印一次）
#           → 签发首个手机 token 与 AI token（只打印一次）。
# 再次运行：保留 .env 和已有账号，只重建容器。
# 可选环境变量：AIU_OWNER_USERNAME（owner 用户名，默认 owner）。
set -euo pipefail
umask 077
cd "$(dirname "$0")/../server"

SERVICE=signal-bridge

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
print("[1/4] 已生成 .env（权限 600）：仅邀请码注册，数据存入 /data。")
PY
else
  echo "[1/4] .env 已存在，保留原配置和密钥。"
fi

# 旧配置需要人工迁移，不能悄悄轮换密钥或切换数据库路径。
python3 - <<'PY'
from pathlib import Path
import sys

REMOVED = ("SB_STATIC_BEARER_TOKEN", "SB_STATIC_USER_ID", "SB_REQUIRE_MCP_AUTH", "SB_TOKEN_EXPIRY_HOURS")

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
leftover = [k for k in REMOVED if values.get(k)]
if leftover:
    print("[警告] .env 里仍有已停用的配置：" + "、".join(leftover), file=sys.stderr)
    print("       新版本会忽略它们（静态 Bearer Token 已失效），请删除这些行。", file=sys.stderr)
    print("       App 请改用账号登录，AI 客户端请换成新的 AI token，见 docs/MULTI-USER.md。", file=sys.stderr)
if values.get("SB_REGISTRATION_OPEN", "false").lower() != "false":
    print("[警告] 当前允许任何人注册；建议设 SB_REGISTRATION_OPEN=false，用邀请码拉人。", file=sys.stderr)
for key, expected in (("SB_DB_PATH", "/data/signal_bridge.db"), ("SB_PATTERNS_DIR", "/data/patterns")):
    if values.get(key) != expected:
        sys.exit(f"[错误] {key} 尚未配置到持久化卷。请先按 docs/DEPLOY.md 备份并迁移旧数据，勿直接重建容器。")
PY

echo "[2/4] 构建并启动容器（旧数据库会在首次启动时自动迁移并留备份）..."
docker compose up -d --build

echo "[3/4] 等待健康检查（最多 30 次，每次请求超时 3 秒）..."
healthy=0
for attempt in $(seq 1 30); do
  if curl --max-time 3 -fsS http://127.0.0.1:8420/health >/dev/null; then
    healthy=1
    break
  fi
  sleep 2
done
if [ "$healthy" != 1 ]; then
  echo "[错误] 健康检查超时。进入 server 目录运行 docker compose logs --tail=100。" >&2
  exit 1
fi

cli() {
  docker compose exec -T "$SERVICE" python -m app.cli "$@"
}

echo "[4/4] 检查账号..."
if ! users_out=$(cli list-users); then
  echo "[错误] 无法在容器内运行管理命令。运行 docker compose logs --tail=100 查看原因。" >&2
  exit 1
fi

if printf '%s\n' "$users_out" | grep -q "No accounts yet"; then
  owner="${AIU_OWNER_USERNAME:-owner}"
  password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(12))')
  # 密码经 stdin 传入，不出现在进程参数和 shell 历史里。
  printf '%s\n' "$password" | cli create-user --username "$owner" --admin --password-stdin >/dev/null
  echo
  echo "已创建 owner 账号（密码只显示这一次，请私下保存，勿截图外传）："
  echo "  用户名：$owner"
  echo "  密码：  $password"
  echo
  echo "首个手机 token（App 里选「Token」模式时填；用账号登录则不需要）："
  cli create-token --username "$owner" --kind phone --name "首个手机"
  echo "首个 AI token（填进 RikkaHub / Claude Desktop 等 MCP 客户端）："
  cli create-token --username "$owner" --kind agent --name "首个 AI 客户端"
  echo "部署完成。/health 已通过；这不代表手机或 BLE 已连接。"
  echo "下一步：App 里填服务器地址，用上面的账号登录；之后在「设置 → AI 接入」管理 AI token。"
else
  echo "已有账号，跳过创建："
  printf '%s\n' "$users_out" | sed -n '2,6p' | sed 's/  id=.*//'
  echo "部署完成。/health 已通过；这不代表手机或 BLE 已连接。"
  echo "忘记密码：docker compose exec $SERVICE python -m app.cli reset-password --username <用户名>"
fi
echo "邀请朋友：docker compose exec $SERVICE python -m app.cli create-invite --note <备注>"
echo "默认仅监听本机 127.0.0.1:8420，公网请配宿主机反向代理；详见 docs/DEPLOY.md。"
exit 0
