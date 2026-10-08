# AI-intoU（樱趣）

让 AI 通过 MCP 远程控制你的蓝牙小玩具。服务端、Android App 和一键部署都在这一个仓库里。

> 目前原生适配 **SVAKOM SX589B**（震动 + 吮吸），不需要 Intiface Central 中转。

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
樱趣 App（装在玩具旁边那台 Android 手机上）
   │  BLE
   ▼
SVAKOM SX589B 等蓝牙玩具
```

你说的一句话会经过这条链路，最后变成玩具上的震动。可以让 AI 陪你，也可以远程操作，看你怎么玩。

**全链路自托管：** 服务器是你自己的，Token 也是你自己生成的。服务端只处理“哪个设备、多大强度、持续多久”这类结构化指令，**不经手、不保存你和 AI 的任何对话内容。**

## 仓库结构

```
├── server/         服务端（FastAPI + Docker），AI 的 MCP 入口
├── android/        樱趣 App（Kotlin + Compose），BLE 直控 + relay
├── deploy/         一键部署脚本
└── docs/           部署教程等文档
```

两个子项目都保留了完整的 git 历史，可以用 `git log -- server/` 或 `git log -- android/` 溯源。

## 快速开始

准备好以下几样：一台 Linux 服务器（装有 Git、Python 3、curl、Docker Engine 和 Compose 插件）、一部 Android 8.0+ 手机，以及一个支持 Streamable HTTP 的 MCP 客户端。

```bash
git clone https://github.com/MorphieEndless/AI-intoU.git
cd AI-intoU
bash deploy/setup-server.sh
```

首次运行时，脚本会生成密钥和 Bearer Token，强制开启 MCP 认证，并把数据存进 Docker 持久化卷。终端打印出来的 Token 请私下保存，App 和 MCP 共用这一个。脚本不会覆盖已有的 `.env`；旧部署请先备份，再按教程迁移数据。

**服务默认只监听 `127.0.0.1:8420`。要让手机从公网接入，需要先配置 HTTPS/WSS 反向代理。** 完整步骤见 [部署教程](docs/DEPLOY.md)，包括宿主机 Caddy 配置、可信内网/VPN 下的 IP 直连、APK 下载和常见故障。明文 HTTP 只建议在可信内网或 VPN 里使用。

| 配置位置 | HTTPS 示例 |
| --- | --- |
| App 服务器地址 | `https://example.com` |
| App 认证方式 | Bearer Token，只填 Token 本身 |
| MCP URL | `https://example.com/mcp` |
| MCP 请求头 | `Authorization: Bearer YOUR_SERVER_TOKEN` |

把示例里的域名和 Token 换成你自己的。APK 可以从 [Releases](https://github.com/MorphieEndless/AI-intoU/releases) 获取；也可以在 [Actions](https://github.com/MorphieEndless/AI-intoU/actions/workflows/build-apk.yml) 里找一次成功的 Build APK 运行，下载 artifact 后解压安装。

在 App 里先点“测试连接”，成功后点“保存并启动”，再按系统提示授予蓝牙权限，然后扫描设备。验证网络时，看 Relay 状态和 MCP 工具列表就够了，不用发送设备输出命令。

## 功能

- SX589B 原生 BLE 直连：震动 0-10 档，吮吸 0-5 档 / 模式 1-8。吮吸协议是拿真机反复实测出来的
- 命令类型：direct / pulse / wave / escalate / custom pattern，全部支持
- 服务端带 safety governor（按强度和时长估算负荷并限流，不监测硬件温度），另有断线急停和心跳保活
- App 用 EncryptedSharedPreferences 记住密码，提供 9 套换肤、深色模式和开发者 HEX 调试
- 两种认证模式：静态 Bearer Token（单用户自部署）和账号/JWT/OAuth（多用户）
- CI 自动构建 APK，配好 secrets 后自动签名

## ⚠️ 安全须知

- **本软件感知不到疼痛和身体状态。** safety governor 只是按强度和时长做估算，替代不了你的判断。**你自己的身体感受，才是唯一可靠的安全线。**
- AI 并不能“感觉”到它在做什么，它的输出可能失控、重复或持续升级。觉得不对就立刻叫停：对 AI 说停、在 App 里断开，或者直接关掉玩具。
- 连接断开时服务端和 App 都会急停，但请不要把它当作唯一的保险。
- 不要把 Token 发到群里或截图外传。拿到 Token 的人就能控制你的设备。
- 使用本软件的风险由使用者自行承担，详见 [LICENSE](LICENSE) 中的免责条款。

## 开发

```bash
# 服务端测试（无需硬件）
cd server && python -m pip install -r requirements-server.txt httpx
python tests/verify_server.py && python tests/verify_governor.py \
  && python tests/verify_numeric_inputs.py && python tests/verify_patterns.py \
  && python tests/verify_static_token.py && python tests/verify_relays.py

# Android 构建（需 JDK 17 + Android SDK）
cd android && ./gradlew testDebugUnitTest assembleDebug
```

想适配新的玩具型号？欢迎提 PR。附上设备型号、抓包或协议说明，会快很多。

## 状态

**内测中**，代码能跑，但还谈不上打磨完善。遇到问题请提 issue，最好附上设备型号、App 版本和日志。

顺带坦白：这个项目大部分代码是 AI Agent 写的。issue 修得快还是慢，有时要看当天模型的状态 🙃

## License

本项目采用 **[Apache License 2.0](LICENSE) + [署名附加条款](ADDITIONAL-TERMS.md)**。

- ✅ 可以免费使用、修改、再分发，**包括商用和闭源产品**
- 📌 需要保留 [NOTICE](NOTICE) 中的版权和署名声明，并标注你改动过的文件（Apache-2.0 第 4 条）
- 📌 如果把本项目代码用在**提供给他人的产品或服务**里，需要在“关于 / 致谢 / 开源许可”页面以用户可见的方式注明来源（见 [附加条款](ADDITIONAL-TERMS.md)）
- ❌ 本许可不授予“AI-intoU”“樱趣”名称的商标使用权

只是参考了实现思路、自己重写的项目，也欢迎在 README 里提一句并附上链接，这对这个项目是很大的支持 🌸

## 致谢

- **[Signal Bridge Remote](https://github.com/AletheiaVox/signal_bridge_remote)**（MIT），作者 Aletheia。本项目的服务端源自这里，包括 relay 架构、safety governor 和 OAuth 认证。服务端与 App 之间的 relay 消息协议也沿用了它的约定，App 本身则是独立编写的。上游许可声明见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
- **[buttplug.io](https://buttplug.io)**：上游项目的基础，这个领域的开源先驱。它的 [伦理框架](https://buttplug.io/docs/dev-guide/intro/buttplug-ethics) 推荐每位使用者读一读。
- **[Model Context Protocol](https://modelcontextprotocol.io)**：让 AI 能直接调用工具的协议。
