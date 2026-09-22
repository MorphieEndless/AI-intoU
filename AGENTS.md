# AGENTS.md — 接手本仓库的 AI Agent 作业手册

> 这份文件写给**下一个 Agent**。人类读者请看 `README.md` 与 `docs/redesign/`。
> 最后更新：2026-09-21

---

## 0. 开工前必读（顺序不要变）

1. `docs/redesign/02-architecture.md` —— **设计宪法**。§1 的设计铁律是硬约束，违反即视为错误交付。
2. `docs/redesign/01-product.md` —— **D1–D6 已锁定决策**。不要重新论证已经定过的事。
3. `docs/redesign/00-current-state.md` —— 重构前的现状与 K1–K9 问题清单（理解“为什么这么设计”）。
4. 本文档 §2 硬规则。

## 1. 项目现状（截至 2026-09-21）

- **阶段**：设计文档已定稿合并 → M0（修复 K1 + 测试门禁）已合并 → M1（数据层）待合并 → 下一步 M2。
- **里程碑表**：`docs/redesign/02-architecture.md` §9，每个里程碑独立可交付、可回滚。
- **重要事实**：main 曾经因为一个行尾反斜杠而完全无法 import（K1）；**生产环境跑的是另一份独立 checkout**，其代码路径与 monorepo 并不同步。任何“生产也这么改”的假设都不成立，改动前先确认目标目录。

## 2. 硬规则（违反即视为错误交付）

1. **材料卫生**：仓库文件、PR 标题/描述、commit message、代码注释里**永远不要出现**真实 IP、域名、Token、密钥、服务器路径、账号名。验证记录统一写“在专用验证机上按 CI 同版依赖实测”。生产环境细节只存在于项目所有者手上的交接材料，**不进门（仓库）**。
2. **文档先行**：任何与 `02-architecture.md` 冲突的实现，先改文档（写明理由），再改代码。不得不偏离时在对应文档的“决策记录”追加 ADR。
3. **测试即契约**：每个里程碑先写验收测试。验收标准是**测试全绿**，不是“看起来能跑”。
4. **行为兼容**：Android App 与 MCP 客户端的既有协议不破坏，除非里程碑明确列出并有 ADR。
5. **不要重新引入**（这些是被明确删除的）：静态 Bearer Token、sole-phone fallback、OAuth 模块、用 JSON 文件存业务数据、`/docs` 与 `/openapi.json` 端点。出处见 `01-product.md` D1/D4/D5 与 `00-current-state.md` K2。

## 3. 标准工作流

1. 从 `main` 拉分支：`feat/m<里程碑>-<主题>` 或 `fix/<主题>`。
2. 读 `02-architecture.md` 对应章节 + 本次里程碑的验收标准。
3. **先写测试，再写实现**，迭代到全绿。
4. **在真机验证**（见 §4）。不要试图在临时沙箱里搭环境——版本锁定、路径行为与生产不一致，会得出错误结论。
5. 推送 → 做完整性校验（§5）→ 开 PR（base=`main`）→ PR 描述附验证记录（**已脱敏**）。

## 4. 验证（必须做，命令照抄）

```bash
# 依赖（与 CI 同版本）
python -m pip install -r server/requirements-server.txt httpx pytest buttplug

cd server

# 1) 全量 pytest（冒烟 + 行为契约 + 数据层）
PYTHONPATH=. python -m pytest tests/ -v

# 2) 既有回归套件（独立脚本，不归 pytest 收集）
python tests/verify_server.py
python tests/verify_static_token.py
python tests/verify_governor.py
python tests/verify_numeric_inputs.py
PYTHONPATH=. python tests/verify_patterns.py
PYTHONPATH=. python tests/verify_pattern_routes.py
PYTHONPATH=. python tests/verify_relays.py

# 3) 数据层工具
python -m scripts.migrate_legacy --dry-run    # 迁移演练，零副作用
```

验证环境由项目所有者提供。**其地址、域名、路径、凭据只存在于项目所有者手上的交接材料，不得写入仓库文件、PR 描述、commit message 或代码注释。**

## 5. 推送完整性校验（不要跳过）

用 API/MCP 推送文件时，纯文本内容可能在二次转录中**静默损坏**——main 上那个导致无法启动的 K1 就是这么来的。推送后必须回读比对：

```bash
# 推送前，在验证机上算出每个文件的 blob 哈希
git hash-object server/app/db.py ...
```

再从远端读回同一文件，比对返回的 blob SHA。不一致就重推。**文档类文件同样适用**。

## 6. 给下一个 Agent 的开场提示词（复制即用）

```
你要接手 AI-intoU 服务端重构。开工前按顺序读：
1. AGENTS.md（作业手册，硬规则在 §2）
2. docs/redesign/02-architecture.md（设计宪法：§1 铁律、§4 数据模型、§9 里程碑）
3. docs/redesign/01-product.md（已锁定决策 D1–D6）
4. docs/redesign/00-current-state.md（现状与已知问题）

本次任务：<里程碑编号与名称，例如 M2 — 统一令牌与认证体系>

约束：
- 不得违反 AGENTS.md §2 硬规则（尤其材料卫生：不写 IP/域名/凭据）
- 验收以测试结果为准，必须在验证机跑全量套件（AGENTS.md §4）
- 推送后做 blob SHA 回读校验（AGENTS.md §5）
- 有设计疑问先问，不要自行推翻已锁定决策

请先复述你对本次任务的理解、验收标准和你的执行计划，等我确认后再动手。
```

## 7. 踩过的坑（省你时间）

- **Alembic 1.20** 的 `command.upgrade()` 不再接受 `x_arg=`；改用 `cfg.set_main_option("sqlalchemy.url", ...)`。
- **旧库表名冲突**：legacy 时代的 `users` / `safety_config` 与新 schema 同名，迁移器必须先改名 `legacy_*` 再建表。
- **FastAPI TestClient 的 WebSocket**：上下文退出会触发服务端 cleanup；`dead_man_switch` 每 2s 发一次心跳，`receive_json()` 会等到它——不要假设没有消息。
- **`_mcp_sessions` 目前没有 TTL**（K5，M4 处理）；在此之前不要对外承诺 MCP 会话的持久性。
- **CI 的 pytest 步骤跑 `tests/` 全目录**；`verify_*.py` 是独立脚本，不被 pytest 收集，必须单独跑。
- **生产与仓库不同步**：本仓库的 main 不保证等于任何人的线上部署。任何“线上是怎么跑的”结论都必须实地确认，不要从 README 或本手册推断。

## 8. 公开前检查清单（仓库迟早公开，逐项过）

1. **全文搜索敏感串**：真实 IP（`\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}`）、域名、`token`/`secret`/`password` 的字面值、服务器绝对路径、账号名。
2. **检查脚本默认值**：relay/CLI 脚本的 `--server`、`--token` 等参数默认值必须是 `example.com` 之类的占位符或必填项。历史遗留真实域名要清掉。
3. **检查 `.env` 类文件**：只留字段名与说明，不留任何真实值；确认 `.env` 本身在 `.gitignore` 里。
4. **检查文档示例**：README / DEPLOY 里的示例地址、Token、密钥全部用占位符。
5. **检查 git 历史**：`git log -p --all | grep -E "<敏感特征>"`。文件改掉 ≠ 历史干净——旧提交里仍留着。若历史中确有泄露，决定是改写历史（`git filter-repo`）还是轮换掉那项凭据（更稳、更省事）。
6. **检查 PR 描述与 commit message**：它们是公开仓库最容易被忽略的泄漏面，且**不进 git 历史时也照样公开展示**。
7. 公开前如需第三方复核，可用 `run_secret_scanning` 对改动文件做一次密钥扫描。
