# Agent 作业手册（操作篇）

> **分工**：`AGENTS.md` 是**规范与红线**（R1–R4、泄露应急）、本文是**操作手册**
> （命令、流程、已知坑、交接模板）。两者冲突时以 `AGENTS.md` 为准。
> 面向所有 AI 编码代理与人类协作者。

---

## 1. 开工前必读（顺序不要变）

1. `AGENTS.md` —— 红线 R1–R4。**违反即视为错误交付**，没有例外。
2. `docs/redesign/02-architecture.md` —— 设计宪法。§1 设计铁律是硬约束，§4 数据模型，§9 里程碑。
3. `docs/redesign/01-product.md` —— 已锁定决策 D1–D6。不要重新论证已经定过的事。
4. `docs/redesign/00-current-state.md` —— 重构前现状与 K1–K9 问题清单（理解“为什么这么设计”）。

**文档先行**：任何与 `02-architecture.md` 冲突的实现，先改文档（写明理由）再改代码；
不得不偏离时，在对应文档的「决策记录」追加一条 ADR。

---

## 2. 里程碑进度

| 里程碑 | 状态 |
| --- | --- |
| 设计文档（现状 / 产品 / 架构） | ✅ 已合并 |
| M0 — 修复 K1 + CI 冒烟门禁 + 行为契约测试 | ✅ 已合并 |
| M1 — SQLAlchemy 数据层 + Alembic + 旧数据迁移 | ✅ 已合并 |
| **M2a — 身份/令牌内核**（`app/config` + `core/security` + `domain/identity` + CLI + 测试） | 🟡 PR（零行为变更，可独立回滚） |
| **M2b — 接线与删除**（静态 token / sole-phone fallback / OAuth 下线 + 安卓领 phone token） | ⬜ 下一步，依赖 M2a 合并 |
| M3–M7 | 见 `02-architecture.md` §9 |

每个里程碑独立可交付、可回滚。**不要跳过里程碑顺序**：M3+ 依赖 M2 的认证解析器。

---

## 3. 标准工作流

1. 从 `main` 拉分支：`feat/m<里程碑>-<主题>` 或 `fix/<主题>`。
2. 读 `02-architecture.md` 对应章节 + 本里程碑的验收标准。
3. **先写测试，再写实现**，迭代到全绿。验收标准是**测试全绿**，不是“看起来能跑”。
4. 在**生产环境等价的实例**上按 CI 同版依赖实跑（§4）。沙箱里的结论不可信：
   依赖锁定、路径行为、时区与生产不一致，会得出错误结论。
5. 推送前跑泄露自查（`bash scripts/check-leaks.sh`，见 `AGENTS.md` §4）→ 推送 → 完整性校验（§5）
   → 开 PR（base=`main`）→ PR 描述附验证记录，**写法照抄 `AGENTS.md` §2 的模板**（环境无关措辞 + 相对路径 + 占位符）。

**禁止直接推 `main`。** 一律走分支 + PR。

---

## 4. 验证命令（照抄）

```bash
# 依赖（与 CI 同版本）
python -m pip install -r server/requirements-server.txt httpx pytest buttplug

cd server

# 1) 全量 pytest（冒烟 + 行为契约 + 数据层）
PYTHONPATH=. python -m pytest tests/ -v

# 2) 既有回归套件（独立脚本，不被 pytest 收集，必须单独跑）
python tests/verify_server.py
python tests/verify_static_token.py
python tests/verify_governor.py
python tests/verify_numeric_inputs.py
PYTHONPATH=. python tests/verify_patterns.py
PYTHONPATH=. python tests/verify_pattern_routes.py
PYTHONPATH=. python tests/verify_relays.py

# 3) 数据层工具（迁移演练，零副作用）
python -m scripts.migrate_legacy --dry-run

# 4) 运维 CLI 自检（账号 / 令牌 / 迁移版本；默认读 SB_DB_PATH，可用 --db 指定）
python -m app.cli doctor
```

`app.cli doctor` 是只读体检，失败时退出码 1（缺 `SB_SECRET_KEY`、库没迁移、
没有 owner 账号都会 FAIL）。迁移库用 `create-user` / `create-token` 等写命令即可，
它们会先把 schema 升到 head。

验证记录**不要**写实例地址、路径或单元名——按 `AGENTS.md` §2 的模板写成环境无关措辞。

---

## 5. 推送完整性校验（不要跳过）

用 API / MCP 推送纯文本文件时，内容可能在二次转录中**静默损坏**。
`main` 曾因此出现过一个行尾反斜杠，导致整个服务端无法 import、无法启动（K1）。

```bash
# 推送前：算出每个文件的 blob 哈希
git hash-object server/app/db.py server/scripts/migrate_legacy.py ...
```

推送后从远端读回同一文件，比对返回的 blob SHA。不一致就重推。
**文档类文件同样适用**——文档损坏不影响运行，但会误导下一个 Agent。

---

## 6. 已知坑（省你时间）

- **Alembic 1.20** 的 `command.upgrade()` 不再接受 `x_arg=`；
  改用 `cfg.set_main_option("sqlalchemy.url", ...)`，`env.py` 优先读该 main option。
- **旧库表名冲突**：legacy 时代的 `users` / `safety_config` 与新 schema 同名，
  迁移器必须先改名 `legacy_*` 再建表（见 `migrate_legacy.py`）。
- **FastAPI TestClient 的 WebSocket**：上下文退出会触发服务端 cleanup；
  `dead_man_switch` 每 2s 发一次心跳，`receive_json()` 会等到它——不要假设没有消息。
- **`_mcp_sessions` 目前没有 TTL**（K5，M4 处理）；在此之前不要对外承诺 MCP 会话的持久性。
- **CI 的 pytest 步骤跑 `tests/` 全目录**；`verify_*.py` 是独立脚本，不被 pytest 收集。
- **生产与仓库不同步**：本仓库的 `main` 不保证等于任何人的线上部署。
  任何“线上是怎么跑的”结论都必须实地确认，不要从 README 或本手册推断。
- **两个包并存**（M2a–M4）：新增代码进 `app/`，legacy `server/*` 只允许 import `app/*`。
  `server/config.py` 是 `app/config.py` 的别名层，改配置只改一处。

---

## 7. 交接给下一个 Agent 的提示词（复制即用）

```
你要接手本项目的服务端重构。开工前按顺序读：
1. AGENTS.md（红线 R1–R4，最高优先级）
2. docs/AGENT-WORKFLOW.md（操作手册：验证命令、推送校验、已知坑）
3. docs/redesign/02-architecture.md（设计宪法：§1 铁律、§4 数据模型、§9 里程碑）
4. docs/redesign/01-product.md（已锁定决策 D1–D6）

本次任务：<里程碑编号与名称，例如 M2 — 统一令牌与认证体系>

约束：
- 不得违反 AGENTS.md 的红线（尤其 R1–R3：IP / 凭据 / 绝对路径不进任何文本框）
- 先写测试再写实现，验收以测试全绿为准
- 必须在与生产等价的实例上跑全量套件（AGENTS.md §4）
- 推送后做 blob SHA 回读校验（§5）
- 有设计疑问先问，不要自行推翻已锁定决策

请先复述你对本次任务的理解、验收标准和执行计划，等我确认后再动手。
```

**不要一次性把整个里程碑丢给 Agent**：先要它的理解与计划，确认后再让它写代码。
跑偏时不要口头纠正——让它把偏离写进对应文档的「决策记录」（ADR）。
