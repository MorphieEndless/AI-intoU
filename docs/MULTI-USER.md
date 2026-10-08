# 多用户：邀请码注册与 AI 接入

从 App 0.17.0 / 服务端 M2b 开始，一个 AI-intoU 实例可以给多个人用：owner 发邀请码，朋友在 App 里注册自己的账号，各自连自己的玩具、生成自己的 AI token。账号之间完全隔离，一个人的 AI 碰不到别人的手机，波形库也各管各的。

> 文中 `https://example.com` 换成你自己的域名，`<...>` 换成实际值。

---

## 1. 三种凭证

| 凭证 | 前缀 | 谁在用 | 有效期 | 去哪拿 |
| --- | --- | --- | --- | --- |
| 会话 | JWT（无前缀） | App 的「AI 接入」「管理」页 | 24 小时 | 账号登录时自动获得 |
| 手机 token | `aiu_phone_` | App 连 Relay（WebSocket）、读写波形库 | 长期，撤销即失效 | 账号登录时 App 自动为本机签发 |
| AI token | `aiu_agent_` | RikkaHub 等 MCP 客户端 | 长期，撤销即失效 | App →「AI 接入」→ 新建 |

三种凭证**不能混用**：
- 拿手机 token 调 MCP，会返回 401
- 拿 AI token 连 Relay，会被拒绝
- 管理 token 和邀请码时只认会话凭证

一台手机对应一个手机 token，同一台手机重新登录会替换掉旧的。AI token 可以按客户端分别生成、分别撤销。

---

## 2. 新用户：从拿到邀请码到 AI 能用

App 设置页顶部有「三步开始使用」引导卡，照着做就行：

1. **连接服务器**
   - 设置 → 服务器地址填 `https://example.com`
   - 选「账号登录」→ 点开「有邀请码？注册新账号」
   - 填邀请码（大小写、空格、横线都不影响）、用户名（3–32 位）、密码（至少 8 位）→「注册并登入」
2. **连上玩具**
   - 授予蓝牙和通知权限
   - 到「玩具」页扫描并连接设备
3. **接入 AI**
   - 注册成功后 App 会自动跳到「AI 接入」，以后也可以从设置页进入
   - 新建 → 选平台（RikkaHub / Cherry Studio / Claude Desktop / 其他）
   - 结果**只显示一次**，可以复制完整 JSON，也可以分别复制 MCP URL 和 `Authorization: Bearer aiu_agent_…`
   - 粘贴到 AI 客户端，再让 AI 调一次 `list_devices` 验证

已经有账号的人直接用账号登入，然后做第 2、3 步。

---

## 3. Owner：发邀请、管账号

### App 里操作

打开设置 → 开发者模式，右上角会出现「**管理**」（只有 owner 账号能看到）：

- **邀请码**
  - 生成时可以设置可用次数（1–50）、有效天数（1–90）和备注
  - 生成后可以一键复制「邀请信息」，里面已经带上了服务器地址
  - 列表里能看到每个码的状态、使用次数和注册人，可以撤销
- **用户**
  - 可以看到谁在线、谁是 owner
  - 可以停用或重新启用账号，也可以重置密码
  - 停用账号后，这个人的会话和所有 token 立刻失效；正在连接的手机会先收到急停，再被断开

### 服务器上用 CLI

在服务端目录下执行，Docker 部署的话放在 `docker compose exec signal-bridge` 后面执行：

```bash
python -m app.cli create-invite --uses 1 --days 7 --note "<昵称>"   # 码只打印这一次
python -m app.cli list-invites
python -m app.cli revoke-invite --invite <前4位或id>

python -m app.cli list-users
python -m app.cli disable-user --username <name>
python -m app.cli enable-user  --username <name>
python -m app.cli reset-password --username <name>
python -m app.cli set-admin --username <name> [--revoke]          # 不能撤掉最后一个 owner

python -m app.cli create-token --username <name> --kind agent --name "RikkaHub"
python -m app.cli list-tokens  --username <name>
python -m app.cli revoke-token --username <name> --token <前缀或id>

python -m app.cli doctor                                          # 只读体检
```

> CLI 停用账号后，这个账号的请求立刻失效，但**已经连上的手机不会被主动踢下线**，因为 CLI 和服务进程之间没有通知机制。需要马上断开的话，用 App 管理页操作。

### 注册策略

- `SB_REGISTRATION_OPEN=false`（默认）：只能用邀请码注册
- `SB_REGISTRATION_OPEN=true`：不填邀请码也能注册，只建议在可信内网里用
- 通过邀请码注册的账号一律**不是 owner**

---

## 4. 从旧版升级（静态 Bearer Token / 单账号）

这次升级会**删除静态 Bearer Token、OAuth，以及"服务器上只有一台手机在线时，就把不带凭证的请求交给它"的回退逻辑**。旧版 App 和旧 token 在升级后会立刻失效，服务端和 App 要**一起升级**。

1. **备份**：停服，把数据库文件和 `.env` 复制一份到别处
2. **确认数据库路径**：在 `.env` 里显式写上 `SB_DB_PATH=<旧数据库的绝对路径>`
   > ⚠️ 新版默认路径是项目根目录下的 `signal_bridge.db`。旧版的数据库如果放在别的位置（比如 `server/signal_bridge.db`），不写这一行的话，服务会新建一个空库，看起来就像"账号全没了"。
3. **更新代码并启动**：首次启动会自动识别旧库并迁移：
   - 先在副本上完成所有改动，校验无误后再一次性写回原库
   - 原库旁边会留一份 `<db>.pre-multiuser-<UTC时间>.bak`
   - 已有账号会变成 owner；波形库和 safety 配置都会保留
   - 迁移中途出错的话，原库保持不变
4. **清理 `.env`**：删掉 `SB_STATIC_BEARER_TOKEN`、`SB_STATIC_USER_ID`、`SB_REQUIRE_MCP_AUTH`、`SB_TOKEN_EXPIRY_HOURS`。留着也不会导致启动失败，只是日志和 `doctor` 里会一直出警告
5. **升级 App 到 0.17.0**：用账号登入，App 会自动签发手机 token
6. **更换 AI 客户端的 token**：到「AI 接入」新建一个 token，替换掉 RikkaHub 等客户端里原来的旧配置

**回滚**：停服 → 恢复旧代码和 `.env` → 用 `.bak` 覆盖数据库 → 启动 → App 装回旧版本。

---

## 5. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| App 提示「这是 AI token」 | 在「手机 Token」模式里填了 `aiu_agent_…`。改用账号登录，或者填 `aiu_phone_…` |
| App 提示「旧版 token 已停用」 | 填的是升级前的静态 token。改用账号登录 |
| RikkaHub 报 401 | AI token 被撤销了，或者填的是手机 token。到「AI 接入」重新生成 |
| AI 调工具返回 No phone connected | 这个账号的手机没在线：打开 App，确认 Relay 已连接 |
| 「AI 接入」页弹出输入密码框 | 会话超过 24 小时过期了。登录时勾选「记住密码」就会自动重新登录 |
| 邀请码无效 | 可能已用完、已过期或已撤销。在管理页查看状态，需要的话重新生成 |
| 看不到「管理」 | 要先打开开发者模式，而且只有 owner 账号能看到 |
