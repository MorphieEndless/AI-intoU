#!/usr/bin/env bash
# check-leaks.sh — 仓库专属泄露模式扫描（CI 第二道防线 / 本地 pre-push 可复用）
#
# 用法: bash scripts/check-leaks.sh [repo-path]     无参数则扫当前目录
#
# 设计原则：
#   · 只有失败，没有豁免 —— 白名单只允许 RFC 5737 文档地址与 RFC 1918 私有段，
#     不放行任何真实地址，也不保留「已知遗留」这个后门。
#   · 要让 CI 变绿请改文档，不要往白名单里加真实地址（见 AGENTS.md §4）。
set -uo pipefail
cd "${1:-.}"

EXC=(--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build
     --exclude-dir=__pycache__ --exclude-dir=.gradle
     --exclude=*.png --exclude=*.jpg --exclude=*.jar --exclude=*.apk --exclude=*.db
     --exclude=AGENTS.md --exclude=PULL_REQUEST_TEMPLATE.md
     --exclude=.gitleaks.toml --exclude=check-leaks.sh)

OCT='(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])'
IPV4="\\b${OCT}\\.${OCT}\\.${OCT}\\.${OCT}\\b"
# RFC 1918 / 回环 / 链路本地 / RFC 5737 / 公共 DNS
ALLOW_IP='(0\.0\.0\.0|127\.0\.0\.1|192\.168\.|10\.|169\.254\.|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.|172\.(1[6-9]|2[0-9]|3[01])\.|1\.1\.1\.1|8\.8\.8\.8|9\.9\.9\.9)'
fail=0
report() { echo "!! [$1] $2"; echo "$3" | head -8; echo; fail=1; }
section() { echo "=== $1 ==="; }

section "1. 公网 IPv4 字面量"
raw=$(grep -rnIE "$IPV4" "${EXC[@]}" . 2>/dev/null)
hits=$(echo "$raw" | grep -vE "$ALLOW_IP" | grep -vE '^\s*$')
if [ -n "$hits" ]; then report "public-ipv4" "发现公网 IP——请换成 RFC 5737 文档地址（192.0.2.0/24）" "$hits"; else echo "ok"; fi

section "2. 内部部署绝对路径"
hits=$(grep -rnIE '/(opt|srv)/[A-Za-z0-9._/-]{3,}|/etc/(nginx|systemd|ssl|fail2ban)/[A-Za-z0-9._/-]{3,}' "${EXC[@]}" . 2>/dev/null)
if [ -n "$hits" ]; then report "internal-path" "发现内网部署路径" "$hits"; else echo "ok"; fi

section "3. 项目专属服务单元操作"
hits=$(grep -rnIE 'systemctl[[:space:]]+(restart|start|stop|kill|status|enable)[[:space:]]+[A-Za-z0-9@._-]+' "${EXC[@]}" . 2>/dev/null \
       | grep -vE '(docker|caddy|nginx|ufw|fail2ban|docker\.io|docker\.socket|sshd|unattended-upgrades)')
if [ -n "$hits" ]; then report "service-name" "发现项目专属服务单元名" "$hits"; else echo "ok"; fi

section "4. 凭据形态"
hits=$(grep -rnIE 'SB_SECRET_KEY=[A-Za-z0-9_+/=-]{16,}|SB_STATIC_BEARER_TOKEN=[A-Za-z0-9_-]{16,}|(ghp|gho|ghs|ghr|github_pat)_[A-Za-z0-9_-]{16,}|sk-[A-Za-z0-9]{20,}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|BEGIN [A-Z ]*PRIVATE KEY' "${EXC[@]}" . 2>/dev/null \
       | grep -vE '(paste-your-generated-key-here|your-secret-key-here|至少32字符|YOUR_|example)')
if [ -n "$hits" ]; then report "credential" "发现疑似真实凭据" "$hits"; else echo "ok"; fi

section "5. 内部域名 / 隧道地址"
# example 只按「RFC 2606 保留域」放行：example.com / .net / .org / .edu / .gov / .mil / .int
# 以及保留 TLD（.test / .invalid / .localhost）。这些指向不了任何主机，与已放行的
# your-subdomain.duckdns.org 同类；白名单里依旧没有任何真实地址。
# 但 example.<真实 TLD>（如 example.top）照拦不误 —— 那不是占位符，是把真实域名
# 伪装成示例形态。
RE_HOST='example\.[a-z]{2,}|in2\.[a-z0-9-]+\.(top|com|net)|[a-z0-9-]+\.ngrok-free\.app|trycloudflare\.com|[a-z0-9-]{2,}\.duckdns\.org'
RE_PLACEHOLDER='example\.(com|net|org|edu|gov|mil|int|test|invalid|localhost)|your-subdomain\.duckdns\.org|www\.duckdns\.org'
# 先在命中行里抹掉占位符再复检，而不是整行剔除：这样「占位符 + 真实残留」出现在
# 同一行时仍会被拦下，不会因为一行的前半截合法就放过后半截。
raw=$(grep -rnIE "$RE_HOST" "${EXC[@]}" . 2>/dev/null || true)
hits=$(printf '%s\n' "$raw" | sed -E "s/$RE_PLACEHOLDER//g" | grep -E "$RE_HOST" || true)
if [ -n "$hits" ]; then report "internal-host" "发现内部域名 / 隧道残留" "$hits"; else echo "ok"; fi

section "6. 作者本机路径"
hits=$(grep -rnIE '/(workspace|Users|home)/[A-Za-z0-9._-]+/[A-Za-z0-9._/-]{3,}' "${EXC[@]}" . 2>/dev/null \
       | grep -vE '(/workspace/<|/home/<|handoffs-内部资料)')
if [ -n "$hits" ]; then report "local-path" "发现作者本机路径" "$hits"; else echo "ok"; fi

echo
if [ $fail -eq 0 ]; then
  echo "=== 结论：无阻塞问题 ==="
else
  echo "=== 结论：有阻塞问题，合并前必须清理 ==="
fi
exit $fail
