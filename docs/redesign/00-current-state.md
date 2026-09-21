# 00 — 服务端现状盘点（冻结于 2026-09-21）

> 本文档描述重构**前**的服务端实际状态，用于建立共识与回归对照。
> 描述以 main 分支代码为准，不以 README 宣称的行为为准。

## 1. 一句话概括

一个 FastAPI 单进程同时扮演三个角色：账号认证、MCP 入口、手机 WebSocket 中继。
核心选型（单进程 + SQLite + 内存态会话）对自部署场景是合理的，
问题不在选型，在于**代码由 AI 多轮生成、从未有过架构约束**。

## 2. 架构图（现状）

```
┌────────────────────── AI-intoU Server（单进程） ──────────────────────┐
│                                                                      │
│  角色1: 账号认证           角色2: MCP 入口          角色3: 手机中继      │
│  POST /auth/register      POST /mcp (JSON-RPC)     WS /ws/phone       │
│  POST /auth/login         GET  /mcp (SSE 占位)     心跳/命令/ACK       │
│  OAuth 全家桶 (/oauth/*)   tools/list, tools/call  device_list 同步    │
│        │                        │                       │             │
│        ▼                        ▼                       ▼             │
│  ┌───────────┐          ┌───────────────┐       ┌──────────────┐     │
│  │ auth.py   │          │ mcp_tools.py  │       │ registry     │     │
│  │ 用户表/JWT │ ───────▶ │ 工具定义+handler│ ────▶ │ 内存会话表    │     │
│  └───────────┘          └───────────────┘       │ user→phone   │     │
│        │                                        └──────────────┘     │
│  ┌───────────┐          ┌───────────────┐              ▲              │
│  │ SQLite    │          │ Governor      │ ──────────────┘              │
│  │ 用户+安全配置│          │ 热量模型(内存) │   心跳驱动 tick              │
│  └───────────┘          └───────────────┘                             │
│                                                                      │
│  持久化三分裂: SQLite(用户/安全) + JSON文件(patterns) + OAuth独立库      │
│  运行时状态全部在内存单例: registry / governor / ip_tracker /           │
│    rate_limiter / _mcp_sessions / ws_count_by_ip                     │
└──────────────────────────────────────────────────────────────────────┘
```

## 3. 模块清单

| 文件 | 约行数 | 职责 | 健康度备注 |
| --- | --- | --- | --- |
| `server/app.py` | ~600 | 三角色合一的入口：auth/MCP/WS/safety 端点全在这 | **含语法错误，见 K1**；职责过载 |
| `server/auth.py` | ~380 | 用户表、bcrypt、JWT、IPBanTracker、RateLimiter | 三个不相关职责挤在一个文件 |
| `server/config.py` | ~100 | 环境变量配置 | 默认值与文档不一致，见 K7 |
| `server/mcp_tools.py` | ~1000 | MCP 工具定义与 handler | 未细审，M4 里程碑处理 |
| `server/oauth.py` + `oauth_routes.py` | ~1000 | OAuth 服务端（claude.ai 连接器用） | 独立的第三套认证，见 K2 |
| `server/pattern_routes.py` + `pattern_store.py` | ~300 | 波形 REST + JSON 文件存储 | 存储与主库分裂，见 K4 |
| `server/relay_hub.py` | ~60 | WS IP 限流工具 | 名字唬人，实际只是限流 |
| `server/session_registry.py` | ~200 | 用户→手机会话映射（内存） | 设计尚可，保留演进 |
| `server/governor.py` | ~330 | 热量模型 + 每用户覆盖配置 | **质量最好的模块**，保留 |
| `server/safety.py` | ~130 | dead man's switch（断线急停） | 保留 |
| `server/models.py` | ~150 | pydantic 模型 | 仅覆盖部分协议消息 |
| `termux_relay_v3.py` / `phone/` | ~800+ | 独立的 Termux 中继脚本 | 与 App relay 平行存在，文档需说明定位 |

## 4. 认证体系现状（三套半）

1. **静态 Bearer Token**：环境变量配置一个万能 token，验证通过则伪造
   `static-bearer-user` 假用户，**完全绕过用户表**。
2. **JWT 账号体系**：`/auth/register`、`/auth/login` 签发 JWT。
   每次签发的 JWT 无差别：不可命名、不可单独撤销、无法区分来源平台。
3. **OAuth 全家桶**：独立的数据库初始化（`init_oauth_db`），
   与用户体系平行存在，主要服务 claude.ai 远程连接器。
4. **（半个幽灵）sole-phone fallback**：`REQUIRE_MCP_AUTH=false` 时，
   若全服恰好只有一台手机在线，**未认证的 MCP 请求直接控制它**。
   单用户时是便利，多用户时是事故。

## 5. 已知问题清单

| # | 严重度 | 问题 |
| --- | --- | --- |
| K1 | 🔴 严重 | `app.py` 的 `device_list` 分支有一个行尾反斜杠，把 `await registry.update_devices(...)` 与 `log.info(...)` 续接成一行 → **SyntaxError，main 分支当前无法 import / 启动**。已用 `py_compile` 复现确认。 |
| K2 | 🔴 高 | 三套半认证体系并存互不认识；sole-phone fallback 是未认证控制后门 |
| K3 | 🔴 高 | Token 无实体模型：不可命名、不可撤销、不区分平台，多 AI 平台需求无处安放 |
| K4 | 🟡 中 | 持久化三分裂：SQLite / JSON 文件 / OAuth 独立库 |
| K5 | 🟡 中 | `_mcp_sessions` 字典永不过期：内存缓慢泄漏 + 重启即失忆 |
| K6 | 🟡 中 | 命名残留：代码内到处是 `Signal Bridge Remote`，与 AI-intoU/樱趣 混用 |
| K7 | 🟢 低 | `SB_HOST` 默认 `0.0.0.0`，README 宣称默认仅 `127.0.0.1`，二者必有一个在说谎 |
| K8 | 🟢 低 | CORS 配置 `allow_origins=["*"]` 且 `allow_credentials=True`：浏览器规范下的无效且危险组合 |
| K9 | 🟡 中 | CI 缺少「能 import 整个应用」的冒烟测试——否则 K1 不可能活着进 main |

## 6. 现有行为中**必须保留**的（重构不可破坏）

- MCP Streamable HTTP 传输（POST JSON-RPC + GET SSE），工具列表与参数契约
- 手机 WS 协议：`phone_auth` → `auth_ok` → `scan` / `device_list` / `heartbeat_pong` / `command_ack` / `phone_emergency_stop`
- Governor 热量模型语义（热量累积/消散/冷却触发与退出）及心跳 piggyback 下发
- Dead man's switch 断线急停
- 每用户安全配置覆盖（数据库 NULL = 跟随全局默认）
- Android App 侧对 `auth_error` / close code 4001/4003 的处理预期
