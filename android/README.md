# 樱媞 Bridge Android

单 APK 直接连接 SVAKOM SX589B，并作为 Signal Bridge Remote 的 phone relay。

## 构建状态

- CI Secrets（KEYSTORE_B64 等）已配置 ✅ → 每次 push 会自动产出**签名一致的 release APK**（artifact `yingti-bridge-release-apk`）。
- 从 debug 包切换到 release 包需要卸载重装一次；此后覆盖安装不再清空配置，登录持久化（记住密码）让 Token 过期重登也不用再手输。

## v0.11.1（顶栏减负 + 急停归位 + 小字修复 + 换肤颜色修复）

- 顶栏从 4 个按钮减到 3 个：红色急停按钮移出顶栏，改放到主页面设备卡（SX589B / 状态行）**右上角**，同为双通道 STOP ALL。
- 吮吸玩具档按钮副标题修复：收窄按钮内边距 + 允许最多两行换行，「持续·弱」「节奏 06」等小字不再被截成「持续…」。
- **换肤颜色修复**：`Theme.kt` 重写，5 套版式 × 亮暗共 10 组 ColorScheme 全部槽位由统一派生函数补全——slider 轨道、FilterChip 选中/未选中底色、按钮、Switch/Checkbox、输入框边框等不再回落 M3 默认紫灰，切换版式后所有组件颜色跟随主题。

## v0.11.0（UI/UX 打磨）

- 顶栏急停改为独立红色圆形按钮（白方块图标），与设置/登出分隔，按压有反馈（v0.11.1 起移至设备卡右上角，见上）；底部大 STOP 按钮保持永久废弃。
- 状态卡颜色语义化：绿=已连接，蓝=扫描/连接中，灰=断开/未开启。
- 震动 slider 下方显示当前档位语义小字（1 持续 / 2 波浪 / 3 尖锐波浪 / 4 长振循环 / 5 断续 / 6 中震×6+强震×2 / 10 满功率）。
- 吮吸自由模式按真机定论去重为 6 个有效模式（02=03 抖动、08=01 脉冲已合并）。
- 深色模式：主页面顶栏一键切换，偏好持久化；设置页可同步切换。
- 5 套 UI 版式（酒红默认 / 浅蓝 / 浅绿 / 浅黄 / 浅紫），在设置页「外观」中切换，各带亮暗配色。
- 开发者模式：默认开启，主页面显示协议调试 HEX 入口；可在设置中关闭，关闭后主页面完全不显示调试。

## v0.10.1

- 登录持久化：账号模式可勾选「记住密码」，密码以加密形式保存（EncryptedSharedPreferences），下次打开自动填充；Token 过期重登不用再手输。
- 稳定 release 签名：仓库配置 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个 Secrets 后，Actions 会产出签名一致的 release APK——覆盖安装不再清空本地配置，不用每次重填账号密码。
- 真机定论回填：02/03 抖动、08/01 脉冲均确认为同一模式，自由模式标注已更新。

## v0.10.0

- SX589B 吮吸协议按真机结论修正：byte5 仅 1–5 档有效，06 死档。
- `constrict` 支持 mode 1–8 透传 byte4，缺省 5（持续）。
- 吮吸区重构为「玩具档 / 自由模式」：6 个预设，以及完整模式表 + 1–5 档强度。
- 支持 `custom_pattern` 固定 steps；每步可携带 `constrict_mode`，缺省 5。
- 自定义波形在手机端二次校验：每步至少 100ms、最多 128 步、repeat 最多 20、总时长最多 10 分钟。
- 玩具档 4/5/6 当前暂按 byte4 06/07/08 排列，等待真机精确校准。
- 底部 STOP ALL 大按钮已移除，改为顶栏红色 STOP 图标（双通道急停）。

## v0.9.0

- 单服务器自部署连接设置，不再绑定私人服务器。
- 支持直接填写 Bearer Token，或使用账号密码调用 `/auth/login` 换取 JWT。
- 自动从服务器基址派生 MCP 与 Phone Relay 地址；支持自定义 base path、MCP Path 和 Relay Path。
- 支持 HTTPS/WSS 与 HTTP/WS；明文 HTTP 会持续显示风险提示。
- 保存前测试 `/health` 与 WebSocket `phone_auth`。
- 生成 RikkaHub Streamable HTTP 配置，可分别复制 MCP URL、Authorization 或完整 JSON。
- Token/JWT 使用 `EncryptedSharedPreferences` 保存；密码仅在勾选「记住密码」时加密保存，日志不输出凭证。

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

App 与 RikkaHub 使用同一个 Bearer Token。公网部署应优先使用 HTTPS/WSS；HTTP/WS 只建议用于可信局域网或 VPN 内。
