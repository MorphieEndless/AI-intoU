# 路线图

> **进度只在这里维护一份。** 本文件取代 `docs/redesign/02-architecture.md` §9 的 M0–M7
> 与 `docs/AGENT-WORKFLOW.md` 原 §2 的进度表（2026-10-10 重写）。
>
> 写法：新需求直接以「问题 → 验收标准」追加到对应版本或优先级下，不再预先编号里程碑。
> 规则：生产与 `main` 不一致超过一个发布，即视为最高优先级问题。

---

## 旧里程碑 M0–M7 的结局

| 旧编号 | 结局 |
| --- | --- |
| M0 冒烟门禁 / 行为契约测试 | ✅ 完成 |
| M1 ORM + Alembic + 旧数据迁移 | ✅ 完成。v0.17.0 起启动时自动迁移，并留 `.pre-multiuser-*.bak` |
| M2a 身份 / 令牌内核 | ✅ 完成 |
| M2b 接线与删除 | ✅ 完成（v0.17.0）：会话 / 手机 token / AI token 分离；静态 token、OAuth、sole-phone fallback 已删除 |
| M3 WS schema + `device_id` | ✂️ 拆散。`device_id` 不做（一人一机够用，无真实需求）；WS schema 降为可选重构，见「可选重构」 |
| M4 MCP transport 抽出 + session TTL | ✂️ 作废。v0.17.0 起 MCP session id 不再存储，K5（内存泄漏）随之消失；抽文件只是整理 |
| M5 安全与停机加固 | ➡️ 有用的部分并入 v0.18.0 |
| M6 部署向导 + doctor | ✅ 基本完成：`setup-server.sh` 创建 owner、签首个 token；`app.cli doctor` 自检 |
| M7 Web 管理页 | ✂️ 作废。由 App 内置「管理」页（owner、开发者模式）取代，不做 Web 端 |

---

## v0.18.0（下一个 App 版本）

### 1. 历史问题收尾

- [ ] **重做吮吸滑条首次点击修复（原 PR #8）**。任务说明见 `AGENT-WORKFLOW.md` §0.3，原样有效。
- [ ] **优雅停机 + App 提示**
  - 问题：关停时 uvicorn 一直等 WS 连接关闭，直到 systemd 超时 SIGKILL，每次重启约 90 秒不可用；
    手机端只看到「已断开」，用户不知道发生了什么。
  - 服务端：lifespan 退出时向每台在线手机发送 `{"type":"server_shutdown","reason":"restart"}`，
    随后 `stop_all`，再以 close code 1012（Service Restart）主动关闭连接。
  - App：收到 `server_shutdown` 或 close code 1012 时，显示「服务器正在更新，马上自动重连」
    （Snackbar 加持续状态），不报错误；重连成功后自动消失。急停照常执行，**设备安全优先于体验**。
  - 验收：服务端测试断言关停时手机依次收到 `server_shutdown` → `stop_all` → close 1012；
    App 测试断言 1012 不显示为错误；生产重启数秒内完成，journal 中不再出现 `SIGKILL`。
- [ ] **CORS**：客户端只有原生 App 和 MCP 客户端，没有浏览器调用方，直接移除 `CORSMiddleware`，
    消除 `allow_origins=*` + `allow_credentials` 的组合（K8）。
- [ ] **命名残留**：代码里的 `Signal Bridge Remote`（7 个文件、systemd 描述）统一改为 AI-intoU（K6）。
    `server/` 的 MIT 署名声明保留不动。
- [ ] **生产部署从 git tag 拉取**，不再手工拷文件；清理部署目录里的 `*.bak*` 和游离文件；
    删除 `.env` 中已失效的变量（`doctor` 会逐条提示）。

### 2. App 内检查更新

- 问题：用户目前只能去 GitHub Releases 手动下载新版本。
- 思路借鉴 RikkaHub：清单 JSON + 版本号比较 + 系统 DownloadManager 下载。
  **RikkaHub 是 AGPL，只参考工程思路，代码自行编写。**
- 服务端：
  - 发版 CI 生成 `release/latest.json`（版本号、versionCode、发布时间、更新说明、APK 地址、sha256、大小、
    最低兼容版本），连同 APK 一起发布到更新路径。
  - 清单使用 **Ed25519 签名**；私钥只存在于 CI secret 中，App 内置公钥。
- App：
  - 设置页「检查更新」，冷启动时最多每 24 小时静默检查一次。
  - 有新版本时弹窗显示版本号和更新说明（Markdown 渲染）。
  - 下载后依次校验：清单签名、APK sha256、**APK 签名证书与当前已安装 App 一致**，
    全部通过才调起系统安装器（需要 `REQUEST_INSTALL_PACKAGES` + FileProvider）。
  - `min_supported` 高于当前版本时，弹窗不可跳过。
- 验收：清单被篡改、APK 被替换、APK 用其他证书签名，这三种情况都拒绝安装且有测试覆盖；
  无网络或清单 404 时静默失败，不打扰用户。

### 3. 安全加固

- 目标：让「拿 Release 包二改、重签、冒充官方分发」这件事做不成或不划算。
  开源代码本身可以被 fork，这一点不防，也防不住；Apache-2.0 + 署名附加条款负责法律层面。
- [ ] **R8 混淆**：开启 `isMinifyEnabled` + `isShrinkResources`，补齐 keep 规则；CI 归档 `mapping.txt`，
      不随 APK 公开。
- [ ] **运行时签名自检**：App 启动时比对自身签名证书的 SHA-256 与内置值（通过 `BuildConfig` 在发版 CI 中注入）；
      不一致就把「重打包」标记带进服务端认证，服务端可以拒绝这类客户端连接（可配置）。
      本地 debug 构建不受影响。
- [ ] **更新链路防劫持**：见第 2 节的三重校验，这是防止有人通过中间人推送二改包的关键。
- [ ] **清单收尾**：关闭 `usesCleartextTraffic`（改为 network security config，只给明确的局域网调试放行）；
      确认 `allowBackup=false`（已是）；确认 token 存储使用 EncryptedSharedPreferences（已用 security-crypto）。
- 不做：在 APK 里硬编码更新 URL 加密、字符串加密这类措施。URL 本身是公开信息，加密只能拖慢对方几分钟，
  真正起作用的是签名校验。（理由见下方 ADR）
- 验收：release 包反编译后类名已混淆；用其他证书重签的包，启动时被检出并上报；
  更新篡改测试全部通过。

### 4. 波形库加载提速

- 现状：波形**存在云端**（服务端数据库 `library_patterns` 表），App **没有任何本地缓存**，
  每次进入波形页都重新请求第一页。
- 实测：服务端查库约 3–8 ms，本机回环 1 ms；从外网经 CDN 访问，一次 HTTPS 请求要 2.5–3.4 秒
  （主要耗在 DNS、TLS 握手，以及每次都新建的 OkHttpClient——见下）。慢在网络，不在库。
- [ ] **本地缓存 + 后台刷新（stale-while-revalidate）**：把最近一次的列表结果按账号缓存在本地文件
      （JSON 即可，二三十条、约 30 KB），进入页面先秒显示缓存，再在后台刷新；刷新失败保留缓存，
      并提示「离线数据」。
- [ ] **共享 HTTP 客户端**：`PatternApi`、`ApiClient` 等改为共用同一个 OkHttpClient，
      复用连接池和 TLS 会话，第二次以后的请求省掉握手开销。
- [ ] **条件请求**：服务端为列表返回 `ETag`（基于用户库的最后修改时间），App 携带 `If-None-Match`，
      内容没变时返回 304，几乎不传数据。
- [ ] **切页面不重载**：把列表状态提到页面外部（ViewModel 或进程级仓库），在 App 内切换页面不再重新请求。
- 验收：第二次进入波形页时首屏 < 100 ms（读缓存）；离线时仍能浏览缓存；
  后台刷新拿到新数据后正确合并，喜欢、收藏、删除、撤销等写操作立即反映到缓存中。

---

## 之后（未排期）

- `termux_relay_v3.py` / `phone/` 去留：App 已覆盖其功能就删除，否则在 README 里写明定位。
- `docs/redesign/00-current-state.md` 已是历史快照，挪到 `docs/archive/`。

## 可选重构（无用户可见收益，不阻塞任何功能）

- legacy `server/` 包逐步并入 `app/`（`mcp/`、`relay/`）。
- WS 消息补全 pydantic schema。

---

## 决策记录

| 日期 | 决策 | 理由 |
| --- | --- | --- |
| 2026-10-10 | 废弃 M0–M7 编号，改为按版本组织的路线图 | M0–M2b 已完成；M7 被 App 内管理页取代；M3/M4 的核心动机（K5、多设备）已消失或不成立；保留编号只会让 Agent 去做不存在的需求 |
| 2026-10-10 | 停机通知增加 `server_shutdown` 消息和 close code 1012 | 用户正在使用时被静默断开，体验像是故障；先通知再急停，既保证设备安全，也让用户知道是在重启 |
| 2026-10-10 | 更新安全依赖签名，不靠隐藏 URL | 开源仓库里的任何「加密 URL」都能从源码或抓包中还原；Ed25519 清单签名加 APK 证书比对，才能确保装上的就是官方包 |
