# AI-intoU Server

AI-intoU 的服务端：基于 FastAPI 的远程 MCP 服务器，负责接收 AI Agent 的工具调用，再通过 WebSocket 转发给手机上的樱趣 App。

> **部署请看根目录的 [部署教程](../docs/DEPLOY.md)**，用 `bash deploy/setup-server.sh` 一键完成。本文只介绍服务端的结构和机制。

## 架构

```
MCP 客户端 ──HTTPS / JSON-RPC──▶ /mcp
                                   │
                              AI-intoU Server（FastAPI，默认 127.0.0.1:8420）
                                   │
樱趣 App   ◀──────WSS──────── /ws/phone
```

## 目录

| 路径 | 说明 |
| --- | --- |
| `server/` | 主服务：MCP 工具、relay hub、认证 / OAuth、safety governor、pattern 库 |
| `app/` | 配置、数据库模型、CLI 等基础模块 |
| `alembic/` | 数据库迁移 |
| `tests/` | 不需要硬件的测试 |
| `phone/`、`termux_relay_v3.py` | 上游保留的 Python relay 客户端（经 Intiface 中转）。樱趣 App 用不到它们 |

## MCP 工具

| 工具 | 说明 |
| --- | --- |
| `list_devices` / `scan_devices` | 列出或重新扫描设备，同时显示 governor 状态 |
| `vibrate` / `constrict` | 震动；吮吸（SX589B 支持模式 1-8） |
| `rotate` / `oscillate` / `temperature` / `led` / `position` / `spray` | 扩展输出，需要设备支持 |
| `pulse` / `wave` / `escalate` | 节奏、正弦波、渐强 |
| `create_pattern` / `play_pattern` / `list_patterns` / … | 自定义波形库 |
| `stop` | 立即停止全部输出，同时取消正在运行的 pattern |
| `read_battery` / `read_sensor` | 读取电量和传感器 |

## 安全机制

- **断线急停**：服务端定时向手机发心跳，超时就下发急停并断开会话。App 端也会在断线时本地急停。
- **Safety governor**：按强度 × 时长估算负荷，过高时强制冷却。可以通过 `SB_GOVERNOR_*` 环境变量调整，见 [.env.example](.env.example)。它只是估算，**不检测体温和硬件温度**。
- **时长自动停止**、**限流**、**认证失败封禁 IP**、**多用户设备隔离**。

## 配置

完整的环境变量说明见 [.env.example](.env.example)。部署脚本默认使用的关键项：

- `SB_REGISTRATION_OPEN=false`：关闭公开注册，只能用邀请码注册（见 [MULTI-USER.md](../docs/MULTI-USER.md)）
- 所有 MCP 请求都必须带 agent token（`aiu_agent_…`）；手机 relay 只接受手机 token（`aiu_phone_…`）
- `SB_DB_PATH=/data/signal_bridge.db`、`SB_PATTERNS_DIR=/data/patterns`：数据放在持久化卷里

## 测试

```bash
python -m pip install -r requirements-server.txt httpx
python tests/verify_server.py && python tests/verify_governor.py \
  && python tests/verify_numeric_inputs.py && python tests/verify_patterns.py \
  && python tests/verify_relays.py && python -m pytest -q
```

## 来源

服务端源自 [Signal Bridge Remote](https://github.com/AletheiaVox/signal_bridge_remote)（MIT，作者 Aletheia）。在上游基础上，增加了账号 / 邀请码 / 分类 token 认证、pattern 库、吮吸输出、樱趣 App 的原生 relay 协议和部署脚本等改造。上游许可见 [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md)，本项目许可见 [LICENSE](../LICENSE) 与 [附加条款](../ADDITIONAL-TERMS.md)。

代码里的 `signal_bridge` 等命名是从上游继承下来的，保留它们是为了兼容已有的部署。
