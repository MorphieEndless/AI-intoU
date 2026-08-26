# 樱媞 Bridge Android

单 APK 直接连接 SVAKOM SX589B，并作为 Signal Bridge Remote 的 phone relay。

## v0.10.0

- SX589B 吮吸协议按真机结论修正：byte5 仅 1–5 档有效，06 死档。
- `constrict` 支持 mode 1–8 透传 byte4，缺省 5（持续）。
- 吮吸区重构为「玩具档 / 自由模式」：6 个预设，以及完整模式表 + 1–5 档强度。
- 支持 `custom_pattern` 固定 steps；每步可携带 `constrict_mode`，缺省 5。
- 自定义波形在手机端二次校验：每步至少 100ms、最多 128 步、repeat 最多 20、总时长最多 10 分钟。
- 玩具档 4/5/6 当前暂按 byte4 06/07/08 排列，等待真机精确校准。

## v0.9.0

- 单服务器自部署连接设置，不再绑定私人服务器。
- 支持直接填写 Bearer Token，或使用账号密码调用 `/auth/login` 换取 JWT。
- 自动从服务器基址派生 MCP 与 Phone Relay 地址；支持自定义 base path、MCP Path 和 Relay Path。
- 支持 HTTPS/WSS 与 HTTP/WS；明文 HTTP 会持续显示风险提示。
- 保存前测试 `/health` 与 WebSocket `phone_auth`。
- 生成 RikkaHub Streamable HTTP 配置，可分别复制 MCP URL、Authorization 或完整 JSON。
- Token/JWT 使用 `EncryptedSharedPreferences` 保存，账号密码不落盘，日志不输出凭证。

## 设备与安全能力

- 原生 Android BLE 扫描 SX/SL 系列设备，连接 FFE0/FFE1。
- SX589B 震动 0–10 档、吮吸 0–5 档 / 模式 1–8，与启动即停。
- WebSocket `phone_auth`、心跳响应、命令 ACK、`device_list`。
- direct vibrate/constrict、双通道 pulse/wave/escalate、custom steps、stop、scan。
- Relay 断线本地 emergency stop。
- 前台服务、WakeLock、通知栏 STOP ALL。

## 构建

需要 JDK 17 与 Android SDK（platform 35；target 34）：

```bash
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
./gradlew testDebugUnitTest assembleDebug
```

Release 签名需在本地或 CI 注入 keystore，不要把 keystore、口令、Token 或私人部署信息提交到仓库。

## 服务端配套

账号模式沿用现有 JWT 登录。单用户自部署可在 Signal Bridge Remote 的 `.env` 设置：

```env
SB_STATIC_BEARER_TOKEN=至少32字符的随机字符串
SB_REQUIRE_MCP_AUTH=true
SB_REGISTRATION_OPEN=false
```

App 与 RikkaHub 使用同一个 Bearer Token。公网部署应优先使用 HTTPS/WSS；HTTP/WS 只建议用于可信局域网或 VPN。
