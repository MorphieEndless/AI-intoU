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

两个子项目各自保留完整 git 历史，`git log -- server/` 或 `git log -- android/` 都能溯源。

## 快速开始（给群友的傻瓜版）

### 你需要准备

1. 一台有公网 IP 的机器（VPS 就行，1C1G 够用），装好 Docker
2. 一个安卓手机（Android 8.0+），放在玩具旁边
3. 一只 SVAKOM SX589B（震动 0-10 档、吮吸 0-5 档）
4. 一个 RikkaHub（或任何支持 MCP 的 Agent 客户端）

### 第一步：部署服务端

```bash
git clone <本仓库地址> && cd <本仓库>
bash deploy/setup-server.sh
```

脚本会自动生成 `.env` 和密钥，构建镜像并启动服务，最后打印出你的 **Bearer Token**，抄下来。

> 公网使用必须配 HTTPS/WSS（Caddy 反代 8420 端口即可），明文 HTTP 只建议内网用。

### 第二步：装 App

去 Actions 页面下载最新 `yingti-bridge-debug-apk`（或签名版 release APK），装到玩具旁边的手机上。

App 里填三样：

- 服务器地址：`https://你的域名`
- Bearer Token：上一步抄下来的那个
- 保存，它会自动测连接，然后就能扫码连上你的 SX589B

### 第三步：让 AI 连上来

在 RikkaHub 里添加 MCP 服务器，地址填 `https://你的域名/mcp`，Authorization 用同一个 Bearer Token。

然后你就可以对它说："启动玩具，波浪模式，中等强度"了。

## 功能

- SX589B 原生 BLE 直连：震动 0-10 档、吮吸 0-5 档 / 模式 1-8
- 命令类型：direct / pulse / wave / escalate / custom pattern，全支持
- 服务端有 safety governor（过热自动冷却）、断线急停、心跳保活
- App 记住密码（EncryptedSharedPreferences）、5 套换肤、深色模式、开发者 HEX 调试
- 支持静态 Bearer Token（单用户自部署）与账号/JWT/OAuth（多用户）两种模式
- CI 自动出 APK，配好 secrets 后自动签名

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

## 状态

**内测中**。公开纯粹是因为群里呼声太高顶不住了。代码能跑，但别指望它是 polished 产品。有问题提 issue，修不修看 AI 当天的智商（这个项目也是 AI Agent 写的）。

## License

上游 Signal Bridge 是 MIT。本仓库 license 待定，公开前会补上。
