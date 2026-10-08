# AI-intoU（樱趣）

让 AI 通过 MCP 远程控制你的蓝牙小玩具。一个仓库收齐全部组件：服务端 + Android App + 一键部署。

## 这是什么

```
你（聊天/Agent 客户端）
   │  说一句"帮我打开玩具，慢慢来"
   ▼
AI Agent（RikkaHub / Claude / 任意 MCP 客户端）
   │  MCP（HTTPS / JSON-RPC）
   ▼
AI-intoU Server（可跑在任何 VPS / NAS / 家里的机器上）
   │  WebSocket（WSS）
   ▼
Android App（樱趣 App，装在玩具旁边那台手机上）
   │  BLE
   ▼
SVAKOM SX589B 等蓝牙玩具
```

把一句话变成玩具上的震动，中间全是这套链路在跑。AI 可以陪你，也可以远程操作，全看你怎么玩。

## 仓库结构

```
├── server/         服务端（FastAPI + Docker），AI 的 MCP 入口
├── android/        Android App（Kotlin + Compose），BLE 直控 + relay
└── deploy/         一键部署脚本
```

两个子项目各自保留完整 git历史，`git log -- server/` 或 `git log -- android/` 都能溯源。

## 快速开始

准备 Linux 服务器（Git、Python 3、curl、Docker Engine 与 Compose 插件）、Android 8.0+ 手机和支持 Streamable HTTP 的 MCP 客户端。私有仓库需要有访问权限的 GitHub 账号。

```bash
git clone https://github.com/MorphieEndless/AI-intoU.git
cd AI-intoU
bash deploy/setup-server.sh
```

首次运行会生成密钥、创建 owner 账号（随机密码只打印一次），并签发首个手机 token 与 AI token，数据存进 Docker 持久化卷。已有 `.env` 不会覆盖；旧部署先备份，并按 [多用户与升级指南](docs/MULTI-USER.md) 迁移。

**默认仅监听 `127.0.0.1:8420`。公网需要配置 HTTPS/WSS 反向代理后，手机才能接入。** 完整步骤见 [部署教程](docs/DEPLOY.md)，包含宿主机 Caddy 配置、可信内网/VPN 的 IP 直连、APK 下载和常见故障。明文 HTTP 仅建议在可信内网或 VPN 内使用。

| 配置位置 | HTTPS 示例 |
| --- | --- |
| App 服务器地址 | `https://example.com` |
| App 认证方式 | 账号登录（推荐），或手机 token `aiu_phone_…` |
| MCP URL | `https://example.com/mcp` |
| MCP 请求头 | `Authorization: Bearer aiu_agent_…`（App →「AI 接入」生成） |

将示例域名换成自己的。给朋友用：在 App 管理页或 CLI 生成邀请码，对方在 App 里用邀请码注册，详见 [MULTI-USER.md](docs/MULTI-USER.md)。APK 可从 [Actions](https://github.com/MorphieEndless/AI-intoU/actions/workflows/build-apk.yml) 中成功的 Build APK 运行下载并解压安装。配置签名后才会提供签名构建；Actions 构建产物不会自动成为 Release。

App 中点击“测试连接”，成功后“保存并启动”，再按系统提示授予蓝牙权限并扫描设备。先检查 Relay 状态和 MCP 工具列表，无需发送设备输出命令来验证网络。

## 功能

- SX589B 原生 BLE 直连：震动 0-10 档、吮吸 0-5 档 / 模式 1-8
- 命令类型：direct / pulse / wave / escalate / custom pattern，全支持
- 服务端有 safety governor（按强度与时长估算负荷并限流，不是硬件温度监测）、断线急停、心跳保活
- App 记住密码（EncryptedSharedPreferences）、9 套换肤、深色模式、开发者 HEX 调试
- 多用户：邀请码注册、账号隔离；手机 token / AI token 分离，可命名、可撤销；owner 管理页与 CLI
- CI 自动出 APK，配好 secrets 后自动签名

## 开发

```bash
# 服务端测试（无需硬件）
cd server && python -m pip install -r requirements-server.txt httpx
python tests/verify_server.py && python tests/verify_governor.py \
  && python tests/verify_numeric_inputs.py && python tests/verify_patterns.py \
  && python tests/verify_relays.py

# Android 构建（需 JDK 17 + Android SDK）
cd android && ./gradlew testDebugUnitTest assembleDebug
```

## 状态

**内测中**。公开纯粹是因为群里呼声太高顶不住了。代码能跑，但别指望它是 polished 产品。有问题提 issue，修不修看 AI 当天的智商（这个项目也是 AI Agent 写的）。

## License

上游 Signal Bridge 是 MIT。本仓库 license 待定，公开前会补上。
