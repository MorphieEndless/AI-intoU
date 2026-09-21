# 部署 AI-intoU

这份教程使用单用户静态 Bearer Token，把服务端、Android App 和 MCP 客户端接通。命令默认在 Linux 服务器上运行。旧部署请先看文末迁移说明，避免重建容器时丢掉数据库。

## 准备

- 一台可以登录终端的 Linux 服务器，安装 Git、Python 3、curl、Docker Engine 和 Docker Compose 插件。可按 [Docker 官方安装说明](https://docs.docker.com/engine/install/) 安装；`docker info` 和 `docker compose version` 都应成功。没有 Docker 权限时，请让管理员配置，勿把 Docker socket 开成公开可写。
- Android 8.0 或更新版本的手机，部署使用时需要保持联网，并位于 BLE 设备附近。当前适配 SX589B。
- 支持 Streamable HTTP 的 MCP 客户端，例如 RikkaHub。
- 公网部署准备一个自己持有的域名，将 DNS 的 A 记录指向服务器。设置了 AAAA 记录时，IPv6 也必须能到达这台机器。

文中的 `example.com` 与 `192.0.2.14` 都是占位符。后者取自 RFC 5737 的文档专用网段（`192.0.2.0/24`），**不指向任何真实主机**，照抄也不会打到别人的机器上。请全部替换成自己的地址。

## 1. 下载项目

```bash
git clone https://github.com/MorphieEndless/AI-intoU.git
cd AI-intoU
```

仓库私有期间，需要有访问权限的 GitHub 账号。可以先用 GitHub CLI 的 `gh auth login` 登录，再执行 `gh repo clone MorphieEndless/AI-intoU`。不要把 GitHub Token 拼进 clone URL、脚本或聊天记录。GitHub Token 只用于访问代码，和稍后生成的服务端 Bearer Token 无关。

## 2. 启动服务端

```bash
bash deploy/setup-server.sh
```

首次运行会生成 `server/.env`，设置随机密钥和静态 Token，强制 MCP 认证并关闭公开注册。数据库与自定义模式目录放在 Docker 的 `/data` 持久化卷内。脚本会构建镜像、启动容器，再等待 `/health` 成功。

**把终端打印的 Bearer Token 私下保存。** App 与 MCP 用同一个值，任何拿到它的人都可能访问你的服务。不要截图发群，不要提交 `.env`。再次运行脚本会保留原配置，不重新生成或打印 Token；忘记时，在服务器私下查看 `.env` 中的 `SB_STATIC_BEARER_TOKEN`。

服务默认绑定 `127.0.0.1:8420`，此时外部手机还不能直连。下面两条路径选一条。

### A. 域名与 HTTPS，适合公网

此方案把 **Caddy 装在宿主机**，与 Docker 在同一台服务器。安装方法见 [Caddy 官方说明](https://caddyserver.com/docs/install)。如果用的是容器版 Caddy，容器里的 `127.0.0.1` 指向它自身，不能直接照抄本节。

放行服务器安全组与防火墙的 TCP 80、443，保留你自己的 SSH 管理端口。8420 无须向公网放行。将下面内容加入 `/etc/caddy/Caddyfile`，保留已有站点，只把域名换成自己的。

```caddyfile
example.com {
    reverse_proxy 127.0.0.1:8420
}
```

验证配置后重载。以下命令适用于使用 systemd 的官方 Caddy 服务。

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl --max-time 10 -fsS https://example.com/health
```

Caddy 会申请与续期证书，并代理 WebSocket，无需另写 Upgrade 请求头。首次签发失败时检查 DNS、80/443 端口和 Caddy 日志，不要用关闭证书校验的方式绕过去。

| 填写位置 | 域名示例 |
| --- | --- |
| App 服务器地址 | `https://example.com` |
| App 自动推导的 Relay | `wss://example.com/ws/phone` |
| MCP 地址 | `https://example.com/mcp` |

Nginx 也可以使用，但要自行配置证书、WebSocket Upgrade 转发与合适的代理超时。公网全程使用 HTTPS/WSS。

### B. IP 直连，仅限可信内网或可信 VPN

在 `server/.env` 末尾设置下面这项。如果已有同名项，修改原项，不要重复添加。

```dotenv
SB_BIND_ADDRESS=0.0.0.0
```

然后从项目根目录重新运行部署脚本。这个设置会把 8420 绑定到所有 IPv4 网卡。**必须在服务器安全组或适用于 Docker 流量的防火墙规则中，仅允许可信内网/VPN 来源访问**；不要只依赖一条可能被 Docker 端口映射绕过的 UFW 规则。手机和 MCP 客户端都需要能到达该网络。

| 填写位置 | IP 格式示例，务必替换 |
| --- | --- |
| App 服务器地址 | `http://192.0.2.14:8420` |
| App 自动推导的 Relay | `ws://192.0.2.14:8420/ws/phone` |
| MCP 地址 | `http://192.0.2.14:8420/mcp` |

HTTP 会明文传输 Token 和命令。没有可信网络时，请使用上面的域名 HTTPS 方案。某些托管 Agent 不接受 HTTP 或不能访问你的 VPN，此时也需要 HTTPS 入口。

## 3. 安装和配置 App

在 [Actions](https://github.com/MorphieEndless/AI-intoU/actions/workflows/build-apk.yml) 打开一次成功的 Build APK 运行，到 Artifacts 下载 `yingti-bridge-debug-apk`，解压后安装里面的 APK。私有仓库需要登录有权限的账号，artifact 也有保存期限。已发布的安装包则从 [Releases](https://github.com/MorphieEndless/AI-intoU/releases) 获取。

只有配置了完整签名 Secrets 并成功构建时，才会有 `yingti-bridge-release-apk`。Actions 上传 artifact 不需要把仓库的 `contents` 权限改成写入。当前工作流只上传构建产物，**不会自动创建 GitHub Release**。

打开连接设置，按顺序填写。

1. 服务器地址填上表的 App 地址，不加 `/mcp` 或 `/ws/phone`。
2. 认证方式选择 Bearer Token，只粘贴 Token 本身，不加 `Bearer ` 前缀。
3. 高级路径保留 `/mcp` 和 `/ws/phone`。
4. 点击“测试连接”，成功后点击“保存并启动”。按系统提示授予蓝牙相关权限，然后在主界面扫描设备。

这里是蓝牙扫描，不需要扫描二维码。可先让设备保持断开，只验证网络连接。需要后台持续联网时，检查 Android 对本 App 的电池和后台限制；不同厂商的设置入口不同。

## 4. 配置 RikkaHub 或其他 MCP 客户端

新增远程 MCP，传输方式选择 **Streamable HTTP**。URL 填上表的 MCP 地址，请求头名称填 `Authorization`，值填 `Bearer ` 加 Token，注意中间有一个空格。

连接设置页底部的 RikkaHub Remote MCP 区可以复制 URL、Authorization 或完整 JSON。复制前会提醒包含凭证，完整配置仅粘贴到你信任的客户端。

以下 JSON 展示 HTTPS 配置。把域名和占位 Token 换成自己的；不同客户端的导入格式可能不同，不支持该格式时按字段手动填。

```json
{
  "mcpServers": {
    "yingti": {
      "type": "streamableHttp",
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_SERVER_TOKEN"
      }
    }
  }
}
```

先确认客户端能列出工具，再调用只读的 `list_devices` 检查设备发现情况。`/health` 成功仅表示服务在线，不证明认证、Relay、BLE 三项都正常。连通性排查不需要发送设备输出命令。

## 常见问题与维护

| 现象 | 先检查 |
| --- | --- |
| clone、Releases 或 artifact 返回 404 | GitHub 登录账号是否有私有仓库权限，是否已经发布 Release |
| 本机 `/health` 失败 | 在 `server` 目录运行 `docker compose ps` 和 `docker compose logs --tail=100` |
| 本机正常，域名访问失败或 502 | DNS、80/443、防火墙，以及宿主机 Caddy 是否能访问 `127.0.0.1:8420` |
| 401 或 403 | 两端 Token 是否一致，App 不带前缀，请求头带 `Bearer ` 前缀 |
| 手机 Relay 断开 | 手机网络、后台限制、代理的 WebSocket 配置 |
| 找不到 BLE 设备 | 蓝牙与相关权限、设备电量和距离，是否被另一个 App 占用 |
| 安装提示签名冲突 | 保持使用同一签名的构建；先备份设置，别直接卸载清数据 |

更新服务端前先备份配置与数据，在项目根目录运行 `git pull --ff-only`，再运行 `bash deploy/setup-server.sh`。如果脚本提示旧配置需要迁移，按下一节处理。不要运行 `docker compose down -v`，它会删除数据卷。

泄露服务端 Token 时，使用 Python 的 `secrets.token_urlsafe(32)` 生成新值，修改 `.env`，重建服务并同步修改 App 和 MCP 客户端。GitHub 访问凭证泄露时，去 GitHub 撤销并重建对应 Token；二者互不替代。

## 已有部署的迁移

新脚本不会覆盖旧 `.env`，遇到未强制认证或数据尚未放进挂载卷时会停止。**先备份，后改路径，最后重建容器。** 仅修改路径不会自动搬运旧数据。

1. 私下备份 `.env`，保存当前镜像/代码版本信息。记录旧配置里的 `SB_DB_PATH`、`SB_PATTERNS_DIR`，也要查看运行容器实际使用的路径，不要假定旧数据库就在 `/data`。这些信息可能包含凭证，勿将整份 inspect 输出公开。
2. 暂停使用并在 `server` 目录执行 `docker compose stop signal-bridge`。从这个尚未删除的容器复制数据库及其同目录的 SQLite `-wal`、`-shm` 文件（若存在），同时备份自定义模式目录。`docker cp` 支持从停止的容器复制文件。原 `.env.example` 的相对数据库路径在该镜像中通常对应 `/app/signal_bridge.db`，模式目录默认为 `/app/server/data/patterns`，以实际配置为准。
3. 将备份的数据复制到同一容器的 `/data/signal_bridge.db` 和 `/data/patterns`。`/data` 是 Compose 挂载的数据卷；先确认挂载存在，且不要覆盖卷中另一份需要保留的数据。没有自定义模式时可以不迁移模式文件。
4. 将 `.env` 设置为 `SB_DB_PATH=/data/signal_bridge.db`、`SB_PATTERNS_DIR=/data/patterns`、`SB_REQUIRE_MCP_AUTH=true`。单用户部署设置 `SB_REGISTRATION_OPEN=false`，保留原有有效密钥和静态 Token。\n5. 公网反代用 `SB_BIND_ADDRESS=127.0.0.1`，可信网络直连按 B 节设置。新版 Compose 默认仅绑定回环地址，旧 IP 直连部署需要显式配置。\n6. 运行部署脚本，检查健康、原有数据和客户端认证。验证完成前保留备份与旧镜像。\n\n教程按仓库内的部署脚本、Compose、Android 连接设置和 CI 工作流编写。具体环境仍需要实际验证，尤其是证书签发、手机后台联网与 APK 安装。\n