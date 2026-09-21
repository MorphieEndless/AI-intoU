# 02 — 目标架构与工程契约

> 每一轮实现会话（AI 或人类）开工前**必读本文档**。
> 与本文档冲突的代码不允许合入。变更先改文档（ADR），再改代码。

## 1. 设计铁律

1. **一个身份系统**：全系统只有一套用户与令牌模型（见 §4）。禁止再造
   平行认证、假用户、未认证后门。sole-phone fallback 永久删除，
   OAuth 模块本期移除（见 01-product.md D4）。
2. **状态只许有两个家**：持久化状态进数据库（经 SQLAlchemy 模型），
   运行时状态进内存注册表（registry / governor）。不存在第三个地方
   （禁止 JSON 文件存储业务数据、禁止配置文件里塞运行时数据）。
3. **协议即 Schema**：所有 WS 消息与 REST 请求/响应用 pydantic 模型定义，
   禁止裸 dict 在层间飞来飞去。MCP 工具参数同理。
4. **分层单向依赖**：`api / mcp / relay`（接入层）→ `domain`（业务）→
   `core / infra`（基础）。`domain` 不得 import FastAPI。
5. **行为兼容**：既有 Android App 与 MCP 客户端协议不破坏，
   除非里程碑明确列出并经 ADR 确认。
6. **安全适度**：做「值得做」的安全（见 §7），不做为安全而安全的累赘。
7. **单进程 SQLite**：不引入外部依赖（无 Redis、无 Postgres）。
8. **对外入口不暴露源站**：对外域名必须自有，并挂在 CDN / 反向代理之后，
   源站防火墙只放行 CDN 回源。**禁止**使用 `sslip.io` / `nip.io` / `xip.io` /
   `traefik.me` 这类把 IP 编码进域名的通配 DNS 服务——那等于把源站地址写在门牌上。
   同理，任何真实 IP / 域名 / 生产路径都不得进入仓库（含私有库），详见 `AGENTS.md` R1–R4。

## 2. 目标目录结构

```
server/
  app/
    main.py              # 只做装配：创建 app、挂路由、lifespan
    config.py            # pydantic-settings，启动时集中校验
    db.py                # engine / session 工厂
    cli.py               # python -m app.cli：create-user / create-token / doctor ...
    models/              # SQLAlchemy ORM 模型（一张表一个文件）
    schemas/             # pydantic：WS 消息、REST 请求/响应、MCP 参数
    core/                # security（哈希/token 生成）、rate_limit、ip_ban
    domain/              # governor、safety(deadman)、patterns（纯业务，无 FastAPI）
    infra/               # session_registry、ws 封装
    api/                 # REST 路由：auth / tokens / safety / patterns / admin
    mcp/                 # transport.py（Streamable HTTP）+ tools.py（工具定义）
    relay/               # WS 端点与消息循环
  alembic/               # 迁移脚本（启动时自动 upgrade head）
  tests/                 # 冒烟（import 全应用）+ 行为契约测试 + 模块单测
```

命名统一：代码内全部使用 **AI-intoU**（包名 `app`，MCP serverInfo 改名），
清除 `Signal Bridge Remote` 残留。

## 3. 技术选型

| 层 | 选型 | 理由 |
| --- | --- | --- |
| Web 框架 | FastAPI + Uvicorn（维持） | 现状可用，生态熟悉 |
| ORM | **SQLAlchemy 2.x**（SQLite 后端） | 标准、AI 生成质量高、支持迁移 |
| 迁移 | **Alembic**（启动时自动 upgrade） | 解决「表结构随代码漂移」问题 |
| 配置 | pydantic-settings | 启动集中校验，非法配置直接拒启动 |
| 密码 | bcrypt（维持）；token 用 SHA-256 哈希存储 | 随机 token 熵足够，无需慢哈希 |

## 4. 数据模型

```sql
users(
  id            TEXT PK,          -- uuid
  username      TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  is_admin      INTEGER NOT NULL DEFAULT 0,   -- owner=1
  is_active     INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT NOT NULL
)

api_tokens(
  id            TEXT PK,
  user_id       TEXT NOT NULL REFERENCES users(id),
  name          TEXT NOT NULL,                  -- 如 "claude-desktop"
  kind          TEXT NOT NULL CHECK(kind IN ('agent','phone','human')),
  token_hash    TEXT UNIQUE NOT NULL,           -- sha256(token)
  prefix        TEXT NOT NULL,                  -- token 前 12 字符，仅用于展示辨认
  scopes        TEXT NOT NULL DEFAULT '["control","status","config"]',
  expires_at    TEXT,                           -- NULL = 永不过期
  revoked_at    TEXT,                           -- NULL = 有效
  last_used_at  TEXT,
  created_at    TEXT NOT NULL
)

devices(
  id            TEXT PK,
  user_id       TEXT NOT NULL REFERENCES users(id),
  name          TEXT NOT NULL,
  platform      TEXT,                            -- "android" / "termux"
  created_at    TEXT NOT NULL,
  last_seen_at  TEXT
)                       -- 一对多预留；v1 运行时仍一用户一台手机在线

patterns(
  id            TEXT PK,
  user_id       TEXT NOT NULL REFERENCES users(id),
  name          TEXT NOT NULL,
  definition    TEXT NOT NULL,                   -- JSON
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
)                       -- 从 JSON 文件迁入

safety_config( ... 维持现状结构 ... )
```

OAuth 相关表**不再创建**；旧 OAuth 库文件随迁移废弃（见 01-product.md D4）。

Token 本体格式：`aiu_<kind>_<32 位 urlsafe 随机>`。
只存 `sha256` 哈希与 `prefix`；**完整 token 只在创建时展示一次**。

## 5. 认证与鉴权流程

```
请求进入
  │
  ▼
唯一认证解析器（core/security.py::resolve_principal）
  │  1. Authorization: Bearer aiu_***  → 查 api_tokens（哈希匹配、未撤销、未过期）
  │  2. 短期会话 JWT（账号密码登录签发，仅用于 Web/CLI，24h）
  │  3. 都不存在 → 401。没有第三条路。
  ▼
Principal(user_id, token_kind, scopes)
  │
  ▼
接入层检查 kind 是否匹配入口：
  · WS /ws/phone     仅接受 kind='phone'
  · POST /mcp        仅接受 kind='agent'（或会话 JWT）
  · /api/tokens 等   仅接受会话 JWT（人类操作）
scope 检查：control=发指令 / status=读状态 / config=改安全配置
```

被删除的旧机制：静态 Bearer Token、sole-phone fallback、`REQUIRE_MCP_AUTH`、
OAuth 全家桶。MCP session 表增加 TTL（默认 24h，随请求滑动续期）。

## 6. 运行时与协议

- **WS 消息**：全部 pydantic schema（`phone_auth` / `auth_ok` / `scan` /
  `device_list` / `heartbeat_pong` / `command_ack` / `phone_emergency_stop` /
  `command`），字段与现状保持一致。
- **Registry**：维持 `user_id → PhoneSession` 映射；`PhoneSession` 增加
  `device_id` 字段（可空），为未来一对多路由预留。
- **Governor**：整体保留，仅把「每用户配置合并」逻辑挪入 domain 层，
  配置读取改走 ORM。
- **MCP transport**：从 `main.py` 抽出为 `mcp/transport.py`；工具列表与
  参数契约不变。

## 7. 安全与鲁棒性（适度清单）

**做**：
- token 哈希存储、日志永不打印完整 token、token 分类互不通婚
- 启动校验：SECRET_KEY、CORS 非 `*` 配 credentials、DB 可写、迁移到 head
- **优雅停机**：收到 SIGTERM 时先向所有在线手机广播 `stop_all`，再关闭连接
- 心跳超时 + dead man's switch 维持现状
- 注册默认关闭（D5）；IP 封禁与限流维持现有参数
- CORS 默认收紧为 `SB_CORS_ORIGINS` 显式列表
- CI 增加「import 整个应用 + py_compile 全量」冒烟门禁（K1/K9 的教训）

**不做**（v1）：RBAC、审计日志、密钥轮换、2FA、数据库加密、限流外置。

## 8. 部署与使用引导（面向自部署者）

`deploy/setup-server.sh` v2 交互式向导：

```
1. 依赖检查（docker / compose 插件）→ 缺失时打印一行安装命令
2. 生成 .env：SECRET_KEY 自动生成；询问域名（可选）→ 输出 Caddy 配置模板；
   询问是否开放注册（默认关）
3. 启动容器，等待 /health 通过
4. 交互创建 owner 账号（或随机生成、仅打印一次）
5. 自动创建首个 phone token + agent token，完整值**仅打印一次**
6. 打印「下一步」：App 填什么地址/token；MCP 客户端填什么 URL/请求头
7. 自检：`docker compose exec server python -m app.cli doctor`
   （DB 连通 / 迁移版本 / token 有效 / WS 路径可达 / 心跳正常）
```

CLI 命令集：`create-user` / `reset-password` / `create-token` /
`list-tokens` / `revoke-token` / `doctor`。
文档：`docs/DEPLOY.md` 重写为「向导式」，含 Caddy 模板、常见故障决策树。

## 9. 里程碑

| # | 内容 | 验收标准 |
| --- | --- | --- |
| M0 | 修复 K1；CI 加 import/compile 冒烟；补行为契约测试（MCP init/tools、WS auth 流） | 现有 verify_* 全绿 + 新冒烟绿 |
| M1 | SQLAlchemy 模型 + Alembic 初始化 + 旧数据迁移脚本（SQLite 表 + patterns JSON → DB） | 迁移后旧数据完整可读 |
| M2 | api_tokens 体系 + 唯一认证解析器 + 删除静态 token/fallback + 移除 OAuth 模块 + CLI | 三类 kind 互不通婚有测试；旧静态 token 有迁移向导 |
| M3 | relay 重构：pydantic WS schema、registry 加 device_id | App 无感知兼容 |
| M4 | MCP transport 抽出 + session TTL | 客户端无感知兼容 |
| M5 | 安全与停机加固（§7「做」清单） | SIGTERM 时手机收到 stop_all 有测试 |
| M6 | 部署向导 v2 + doctor + DEPLOY.md 重写 | 干净机器 15 分钟跑通 |
| M7 | （后期）极简 Web 管理页：token 管理 + 在线状态 | 静态页 + 现有 API |

每个里程碑独立可交付、可回滚；M0–M2 是重构主体，M3+ 可在主体稳定后穿插。

## 10. 与 AI 协作的实施方式

- 实现主力模型建议 **GLM-5.3**（工程 Bench 与吞吐优势）；
  架构层面的极端边界推演可用 Kimi K3 复审。
- 每轮会话 prompt 必带：本文档 §1/§2/§4 + 当前里程碑的验收标准。
- 每个里程碑完成后更新本文档「决策记录」，保持文档与代码同步。

## 决策记录

| 日期 | 决策 | 理由 |
| --- | --- | --- |
| 2026-09-21 | 初版架构（铁律、目录、数据模型、里程碑 M0–M7） | 依据 01-product.md 的 D1–D6 |
| 2026-09-21 | 增设设计铁律 8：对外入口不暴露源站，禁止 sslip.io 类域名 | 客户端源码曾把源站 IP 编码进 `*.sslip.io` 域名；该主机名已从历史清除并下线 |
| 2026-09-21 | 移除 OAuth 模块，不再创建相关表；M2 同步调整 | 依据修订后的 D4：不为假想需求付维护成本，等真实用户提需求再以插件回归 |
