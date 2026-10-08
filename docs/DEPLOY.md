# 部署 AI-intoU

这份教程用单用户静态 Bearer Token，把服务端、Android App 和 MCP 客户端接通。命令默认在 Linux 服务器上运行。旧部署请先看文末的迁移说明，避免重建容器时丢掉数据库。

## 准备

- 一台能登录终端的 Linux 服务器，装好 Git、Python 3、curl、Docker Engine 和 Docker Compose 插件。可以按 [Docker 官方安装说明](https://docs.docker.com/engine/install/) 安装，装完后 `docker info` 和 `docker compose version` 都应能成功运行。没有 Docker 权限的话请找管理员配置，不要把 Docker socket 开成公开可写。
- 一部 Android 8.0 或更新版本的手机。使用时需要保持联网，并放在 BLE 设备附近。目前适配 SX589B。
- 一个支持 Streamable HTTP 的 MCP 客户端，比如 RikkaHub。
- 公网部署需要一个你自己的域名，DNS 的 A 记录指向服务器。如果设置了 AAAA 记录，IPv6 也必须能访问到这台机器。

文中的 `example.com` 和 `192.0.2.14` 都是占位符。后者属于 RFC 5737 规定的文档专用网段（`192.0.2.0/24`），**不指向任何真实主机**，照抄也不会打到别人的机器上。请全部换成你自己的地址。

## 1. 下载项目

```bash
git clone https://github.com/MorphieEndless/AI-intoU.git
cd AI-intoU
```

## 2. 启动服务端

```bash
bash deploy/setup-server.sh
```

首次运行时，脚本会生成 `server/.env`，设置随机密钥和静态 Token，强制开启 MCP 认证并关闭公开注册。数据库和自定义模式目录都放在 Docker 的 `/data` 持久化卷里。之后脚本会构建镜像、启动容器，并等到 `/health` 返回成功。

**终端打印出来的 Bearer Token 请私下保存。** App 和 MCP 用的是同一个值，拿到它的人都可能访问你的服务。不要截图发群，也不要提交 `.env`。再次运行脚本会保留原配置，不会重新生成或打印 Token。忘了的话，在服务器上私下查看 `.env` 里的 `SB_STATIC_BEARER_TOKEN`。

服务默认绑定 `127.0.0.1:8420`，这时外部手机还连不上。下面两种方式选一种。

### A. 域名 + HTTPS（适合公网）

这个方案把 **Caddy 装在宿主机上**，和 Docker 在同一台服务器。安装方法见 [Caddy 官方说明](https://caddyserver.com/docs/install)。如果你用的是容器版 Caddy，容器里的 `127.0.0.1` 指向它自己，不能直接照抄本节。

在服务器安全组和防火墙里放行 TCP 80 和 443，同时保留你自己的 SSH 管理端口。8420 不需要对公网开放。把下面的内容加进 `/etc/caddy/Caddyfile`，保留原有站点，只把域名换成你自己的：

```caddyfile
example.com {
    reverse_proxy 127.0.0.1:8420
}
```

先验证配置再重载。以下命令适用于用 systemd 管理的官方 Caddy 服务：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl --max-time 10 -fsS https://example.com/health
```

Caddy 会自动申请和续期证书，也会代理 WebSocket，不用另写 Upgrade 请求头。如果首次签发失败，检查 DNS、80/443 端口和 Caddy 日志，不要靠关闭证书校验绕过去。

| 填写位置 | 域名示例 |
| --- | --- |
| App 服务器地址 | `https://example.com` |
| App 自动推导的 Relay | `wss://example.com/ws/phone` |
| MCP 地址 | `https://example.com/mcp` |

Nginx 也能用，但证书、WebSocket Upgrade 转发和代理超时要自己配好。公网全程请用 HTTPS/WSS。

### B. IP 直连（仅限可信内网或可信 VPN）

在 `server/.env` 末尾加上下面这一项。如果已经有同名项，直接改原来那行，不要重复添加：

```dotenv
SB_BIND_ADDRESS=0.0.0.0
```

然后回到项目根目录，重新运行部署脚本。这项设置会把 8420 绑定到所有 IPv4 网卡上，所以**必须在服务器安全组或能管到 Docker 流量的防火墙规则里，只允许可信内网/VPN 的来源访问**。不要只靠一条 UFW 规则，Docker 的端口映射可能会绕过它。手机和 MCP 客户端都要能访问这个网络。

| 填写位置 | IP 格式示例，务必替换 |
| --- | --- |
| App 服务器地址 | `http://192.0.2.14:8420` |
| App 自动推导的 Relay | `ws://192.0.2.14:8420/ws/phone` |
| MCP 地址 | `http://192.0.2.14:8420/mcp` |

HTTP 会明文传输 Token 和命令。如果没有可信网络，请用上面的域名 HTTPS 方案。另外，有些托管型 Agent 不接受 HTTP，或者访问不到你的 VPN，这种情况也需要 HTTPS 入口。

## 3. 安装和配置 App

已发布的安装包在 [Releases](https://github.com/MorphieEndless/AI-intoU/releases) 下载。想用最新构建的话，打开 [Actions](https://github.com/MorphieEndless/AI-intoU/actions/workflows/build-apk.yml) 里一次成功的 Build APK 运行，在 Artifacts 下载 `yingti-bridge-debug-apk`，解压后安装里面的 APK。下载 artifact 需要登录 GitHub 账号，而且 artifact 有保存期限。

只有配齐签名 Secrets 并构建成功时，才会生成 `yingti-bridge-release-apk`。Actions 上传 artifact 不需要把仓库的 `contents` 权限改成可写。目前的工作流只上传构建产物，**不会自动创建 GitHub Release**。

打开连接设置，按顺序填写：

1. 服务器地址填上表里的 App 地址，不要加 `/mcp` 或 `/ws/phone`。
2. 认证方式选 Bearer Token，只粘贴 Token 本身，不要加 `Bearer ` 前缀。
3. 高级路径保持默认的 `/mcp` 和 `/ws/phone`。
4. 点“测试连接”，成功后点“保存并启动”。按系统提示授予蓝牙相关权限，然后在主界面扫描设备。

这里扫描的是蓝牙设备，不是二维码。可以先让设备保持断开，只验证网络连接。如果需要 App 在后台持续联网，请检查系统对它的电池和后台限制，不同厂商的设置入口不一样。

## 4. 配置 RikkaHub 或其他 MCP 客户端

新增一个远程 MCP，传输方式选 **Streamable HTTP**。URL 填上表里的 MCP 地址；请求头名称填 `Authorization`，值填 `Bearer ` 加上 Token，注意中间有一个空格。

App 连接设置页底部的 RikkaHub Remote MCP 区域可以复制 URL、Authorization 或完整 JSON。复制前会提示内容里含有凭证。完整配置只粘贴到你信任的客户端里。

下面的 JSON 是 HTTPS 配置示例。把域名和占位 Token 换成你自己的。不同客户端的导入格式可能不一样，不支持这种格式的话，就按字段手动填写。

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

先确认客户端能列出工具，再调用只读的 `list_devices` 检查设备发现情况。`/health` 返回成功只说明服务在线，不能证明认证、Relay 和 BLE 三项都正常。排查连通性时不需要发送设备输出命令。

## 常见问题与维护

| 现象 | 先检查 |
| --- | --- |
| Releases 或 artifact 返回 404 | 是否已经发布了 Release；artifact 是否已过期，下载时是否登录了 GitHub |
| 本机 `/health` 失败 | 在 `server` 目录运行 `docker compose ps` 和 `docker compose logs --tail=100` |
| 本机正常，域名访问失败或 502 | DNS、80/443 端口、防火墙，以及宿主机 Caddy 能否访问 `127.0.0.1:8420` |
| 401 或 403 | 两端 Token 是否一致；App 里不带前缀，请求头里要带 `Bearer ` 前缀 |
| 手机 Relay 断开 | 手机网络、后台限制、反向代理的 WebSocket 配置 |
| 找不到 BLE 设备 | 蓝牙和相关权限、设备电量和距离，以及设备是否被别的 App 占用 |
| 安装时提示签名冲突 | 一直用同一个签名的构建；先备份设置，不要直接卸载清数据 |

更新服务端前，先备份配置和数据。然后在项目根目录运行 `git pull --ff-only`，再运行 `bash deploy/setup-server.sh`。如果脚本提示旧配置需要迁移，按下一节处理。不要运行 `docker compose down -v`，它会删掉数据卷。

如果服务端 Token 泄露了，用 Python 的 `secrets.token_urlsafe(32)` 生成一个新值，改进 `.env`，重建服务，再把 App 和 MCP 客户端里的 Token 同步换掉。

## 已有部署的迁移

新脚本不会覆盖旧的 `.env`。如果发现没有强制认证，或者数据还没放进挂载卷，脚本会停下来。**顺序是：先备份，再改路径，最后重建容器。** 光改路径不会自动把旧数据搬过去。

1. 私下备份 `.env`，并记下当前镜像和代码的版本信息。记录旧配置里的 `SB_DB_PATH` 和 `SB_PATTERNS_DIR`，同时查看正在运行的容器实际用的是哪个路径，不要默认旧数据库就在 `/data`。这些信息里可能有凭证，不要把整份 inspect 输出公开。
2. 暂停使用，在 `server` 目录执行 `docker compose stop signal-bridge`。从这个还没删除的容器里复制出数据库，以及同目录下的 SQLite `-wal`、`-shm` 文件（如果有），自定义模式目录也一起备份。`docker cp` 可以从已停止的容器里复制文件。按原来 `.env.example` 的相对路径，数据库在这个镜像里通常是 `/app/signal_bridge.db`，模式目录默认是 `/app/server/data/patterns`，具体以实际配置为准。
3. 把备份的数据复制到同一个容器的 `/data/signal_bridge.db` 和 `/data/patterns`。`/data` 是 Compose 挂载的数据卷，复制前先确认挂载存在，也别覆盖卷里另一份需要保留的数据。没有自定义模式的话，可以不迁移模式文件。
4. 在 `.env` 里设置 `SB_DB_PATH=/data/signal_bridge.db`、`SB_PATTERNS_DIR=/data/patterns` 和 `SB_REQUIRE_MCP_AUTH=true`。单用户部署再设置 `SB_REGISTRATION_OPEN=false`。原来有效的密钥和静态 Token 保持不变。
5. 走公网反向代理的话用 `SB_BIND_ADDRESS=127.0.0.1`；在可信网络里直连则按 B 节设置。新版 Compose 默认只绑定回环地址，旧的 IP 直连部署需要显式配置这一项。
6. 运行部署脚本，检查服务健康、原有数据和客户端认证是否正常。验证完成前，先保留备份和旧镜像。

本教程依据仓库里的部署脚本、Compose 配置、Android 连接设置和 CI 工作流编写。具体环境仍需要实际验证，尤其是证书签发、手机后台联网和 APK 安装这几项。
