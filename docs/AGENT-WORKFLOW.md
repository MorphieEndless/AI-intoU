# Agent 作业手册（操作篇）

> **分工**：`AGENTS.md` 是**规范与红线**（R1–R4、泄露应急）、本文是**操作手册**
> （命令、流程、已知坑、交接模板）。两者冲突时以 `AGENTS.md` 为准。
> 面向所有 AI 编码代理与人类协作者。
>
> 最后更新：2026-10-08（仓库准备公开；见 §0 当前交接）

---

## 0. 当前交接（2026-10-08）

> 每次交接覆盖本节。已完成的事项移到 §2 进度表，不在这里堆积。

### 0.1 仓库状态

- `main` @ PR #9 合并（波形库：喜欢 / 收藏、备注、预览图、撤销删除）。App 版本 **0.16.0 / versionCode 22**。
- 仓库**即将从私有转为公开**。许可证已定：**Apache-2.0 + 署名附加条款**
  （`LICENSE` / `ADDITIONAL-TERMS.md` / `NOTICE` / `THIRD_PARTY_LICENSES.md`）。
- 署名边界：`server/` 源自 Signal Bridge Remote（MIT，保留其声明）；
  `android/` 为本项目**独立编写**，不含 Signal Bridge Android 代码，仅沿用 relay 消息协议的字段约定。
  **不要**在任何文档里写"App 参考 / 源自 Signal Bridge Android"。
- README 三份（根 / `server/` / `android/`）已于本日重写；`server/README.md` 不再是上游原文。

### 0.2 进行中

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **PR #11** Docker 镜像补齐 `app/` | 🟡 待 CI 绿后合并 | 见 §6「Dockerfile 漏拷贝」。替代 #10（#10 因 CI 占位值触发泄露门禁而重建） |
| **v0.16.0 Release** | ⬜ #11 合并后 | 由项目所有者在网页上手动建：tag `v0.16.0`、target `main`、附 main 构建的签名 `app-release.apk`、**不**勾 Pre-release |
| 清理 `ci-apk-*` 预发布 | ⬜ | 4 个旧预发布全部来自已关闭 / 已合并分支，删除，避免公开后被误下载 |
| 仓库转公开 | ⬜ 最后一步 | 转公开前在 Actions 手动触发一次 **Secret scan**（见 §6「gitleaks 只扫增量」） |

### 0.3 下一个任务：重做吮吸滑条修复（原 PR #8）

**背景**：PR #8（`fix/suction-slider-state-echo`）修的是"第一次点吮吸档位会短暂显示、随后被后台旧状态回写覆盖、需要点第二次"。
它基于 #9 之前的 main，**已关闭未合并**；#9 大改了同一个 `DashboardScreen.kt`，直接 rebase 会冲突，所以**基于最新 main 重做**。

**必须保留的行为**（来自 #8 的设计，已被用户认可）：

1. 提交吮吸档位 / 模式后，记录一个"待确认"的 `(level, mode)`；在后台状态回到与之匹配的值之前，**旧值回写不得覆盖滑条**。
2. 匹配的新值返回后恢复正常同步；**连续点击以最新一次为准**。
3. 急停（`全部停止`）、吮吸停止（`吮吸已停止`）、服务停止、BLE 断连、出现**新的**错误 —— 都**立即清除**待确认状态，不得屏蔽真实停止事件。
4. 2 秒未确认则回落到后台已应用的状态，**不重发指令**。
5. 保留：0 档即停止、拖动松手才提交、模式选择、震动滑条原行为。
6. 待确认期间如果出现**无关的** BridgeState 变化（如震动强度变化），空闲时选的模式不能被重置。

**参考实现**：PR #8 的 diff 可直接读（`pull_request_read` → `get_files`，PR 号 8）。
核心是 `pendingSuction: Pair<Int, Int>?` + `submitSuction(value, mode)` 统一入口 + `LaunchedEffect(state)` 判定 forcedReset / confirmed。
**照搬逻辑，不要照搬行号**——#9 改过这个文件的结构。

**验收**：

- 先在**当前 main** 上加入 #8 的 9 个动态 `BridgeState` 用例（`DashboardSliderTest.kt`：
  `firstTapSurvivesDelayedOlderEcho`、`latestTapSurvivesPreviousTapEcho`、`remoteUpdatesResumeAfterMatchingEcho`、
  `emergencyStopOverridesPendingTap`、`disconnectClearsPendingTap`、`staleModeEchoDoesNotChangeNextTapMode`、
  `commandFailureClearsPendingTap`、`missingAcknowledgementEventuallyRestoresAppliedState`、
  `idleModeSelectionSurvivesUnrelatedBridgeUpdate`），**确认其中至少前 4 个在旧实现上失败**（复现），再写修复。
- 修复后：完整 Android 单元测试 + 既有 5 项滑条点击回归 + 上述 9 项全绿；Debug APK 构建成功。
- 版本号：**0.16.1 / versionCode 23**。
- 只改 Android UI、测试、版本号。不碰服务端、协议、部署配置。
- 真机复验由项目所有者完成，PR 描述里写明"未操作真实设备"。

**顺带确认**：#9 新增的 `PatternLibraryScreen` 有"停止"按钮走 `ACTION_STOP_ALL`，
它产生的 `lastMessage` 是否也是 `全部停止`——如果不是，forcedReset 条件要把它加进去。

---

## 1. 开工前必读（顺序不要变）

1. `AGENTS.md` —— 红线 R1–R4。**违反即视为错误交付**，没有例外。
2. 本文 §0 当前交接 + §6 已知坑。
3. `docs/redesign/02-architecture.md` —— 设计宪法。§1 设计铁律是硬约束，§4 数据模型，§9 里程碑。
4. `docs/redesign/01-product.md` —— 已锁定决策 D1–D6。不要重新论证已经定过的事。
5. `docs/redesign/00-current-state.md` —— 重构前现状与 K1–K9 问题清单（理解"为什么这么设计"）。
6. 涉及波形库时：`docs/WAVEFORM-LIBRARY.md`。

**文档先行**：任何与 `02-architecture.md` 冲突的实现，先改文档（写明理由）再改代码；
不得不偏离时，在对应文档的「决策记录」追加一条 ADR。

---

## 2. 里程碑进度

| 里程碑 | 状态 |
| --- | --- |
| 设计文档（现状 / 产品 / 架构） | ✅ 已合并 |
| M0 — 修复 K1 + CI 冒烟门禁 + 行为契约测试 | ✅ 已合并 |
| M1 — SQLAlchemy 数据层 + Alembic + 旧数据迁移 | ✅ 已合并 |
| M2a — 身份/令牌内核（`app/config` + `core/security` + `domain/identity` + CLI） | ✅ 已在 main（零行为变更） |
| 波形库（ORM 存储、个人标记、只读 MCP 偏好工具）— PR #9 | ✅ 已合并，2026-10-08 |
| 公开准备：许可证、README 重写、Docker 镜像修复 | 🟡 见 §0.2 |
| **M2b — 接线与删除**（静态 token / sole-phone fallback / OAuth 下线 + 安卓领 phone token） | ⬜ 未开始。**需与 App 协同发布**，不要单独上线服务端侧 |
| M3–M7 | 见 `02-architecture.md` §9 |

每个里程碑独立可交付、可回滚。**不要跳过里程碑顺序**：M3+ 依赖 M2 的认证解析器。

---

## 3. 标准工作流

1. 从 `main` 拉分支：`feat/<主题>` / `fix/<主题>` / `docs/<主题>`。
2. 读 `02-architecture.md` 对应章节 + 本次任务的验收标准。
3. **先写测试，再写实现**；修 bug 时先写能复现的失败用例。验收标准是**测试全绿**，不是"看起来能跑"。
4. 服务端在**生产环境等价的实例**上按 CI 同版依赖实跑（§4）；Android 以 GitHub Actions 为构建权威。
   沙箱里的结论不可信：依赖锁定、路径行为、时区与生产不一致，会得出错误结论。
5. 推送前跑泄露自查（`bash scripts/check-leaks.sh`，见 `AGENTS.md` §4）→ 推送 → 完整性校验（§5）
   → 开 PR（base=`main`）→ PR 描述附验证记录，**写法照抄 `AGENTS.md` §2 的模板**。

**禁止直接推 `main` 的代码改动。** 一律走分支 + PR。

---

## 4. 验证命令（照抄）

```bash
# 依赖（与 CI 同版本）
python -m pip install -r server/requirements-server.txt httpx pytest buttplug

cd server

# 1) 全量 pytest（冒烟 + 行为契约 + 数据层 + 波形库）
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

# 4) 运维 CLI 自检（默认读 SB_DB_PATH，可用 --db 指定）
python -m app.cli doctor

# 5) 镜像能否真正启动（改过 Dockerfile、新增包、改过 import 时必跑）
docker build -t aiu-server:local .
docker run --rm -e "SB_SECRET_KEY=$(python3 -c 'import secrets;print(secrets.token_hex(32))')" \
  aiu-server:local python -c "import server.app, app.domain.patterns; print('import ok')"
```

```bash
# Android（需 JDK 17 + Android SDK；无本地环境时以 Actions 为准）
cd android && ./gradlew testDebugUnitTest assembleDebug
```

验证记录**不要**写实例地址、路径或单元名——按 `AGENTS.md` §2 的模板写成环境无关措辞。

---

## 5. 推送完整性校验（不要跳过）

用 API / MCP 推送纯文本文件时，内容可能在二次转录中**静默损坏**。
`main` 曾因此出现过一个行尾反斜杠，导致整个服务端无法 import（K1）；
`docs/DEPLOY.md` 也曾因转录把换行写成字面 `\n`，迁移步骤挤成一行。

```bash
git hash-object <file> ...     # 推送前
```

推送后从远端读回同一文件，比对 blob SHA。不一致就重推。**文档类文件同样适用。**

---

## 6. 已知坑（省你时间）

### 构建与部署

- **Dockerfile 漏拷贝（2026-10-08 发现，PR #11 修复）**：`server/Dockerfile` 从上游继承后长期只
  `COPY server/`，而 `server/*` 自 M2a 起 import `app/*`。源码树里跑 pytest 一切正常，
  **镜像却在启动时 `ModuleNotFoundError: No module named 'app'`**。
  新增顶层包或改变 import 关系时，同步检查 Dockerfile 的 `COPY` 列表；CI 的 `Docker image boots` job 会兜底。
- **生产与仓库不同步**：本仓库的 `main` 不保证等于任何人的线上部署（线上是独立 checkout）。
  上面那个 Dockerfile 问题就是这样藏了很久。任何"线上是怎么跑的"结论都必须实地确认。
- **main 不发 Release**：`build-apk.yml` 只给**非 main 分支**发 `ci-apk-*` 预发布；
  main 上的构建只进 Actions artifact（保留 7 / 14 天）。正式版由人在网页上手动建。

### 泄露门禁

- **gitleaks 扫的是提交历史**。分支上任何一个提交含命中串，后续怎么改都转不绿。
  正确做法是**重建分支**（让坏版本从未进入任何提交），不要往 `.gitleaksignore` 写 commit 指纹。
  先例：#4→#5、#10→#11。
- **CI 工作流里也不能写形如凭据的字面量**，哪怕是一次性占位值。`SB_SECRET_KEY=<16 位以上字母数字>`
  这种形态会被 `check-leaks.sh` §4 与 gitleaks 规则命中。需要时在运行时生成、用 `$VAR` 引用（参考 #11 的写法）。
- **gitleaks 只扫增量**：PR / push 触发时 gitleaks-action 只扫本次新增提交，job 名里的 "full history"
  名不副实。要扫完整历史，在 Actions 手动 **Run workflow**（`workflow_dispatch`）。
- **文档自指**：写泄露相关说明时，示例域名一律拆开书写（"`example` 配 `.com`"），否则文档本身被扫描器命中。

### 服务端

- **Alembic 1.20** 的 `command.upgrade()` 不再接受 `x_arg=`；
  改用 `cfg.set_main_option("sqlalchemy.url", ...)`，`env.py` 优先读该 main option。
- **旧库表名冲突**：legacy 时代的 `users` / `safety_config` 与新 schema 同名，
  迁移器必须先改名 `legacy_*` 再建表（见 `migrate_legacy.py`）。
- **波形库存储**：#9 起活数据在 `library_patterns` / `library_imports` 两张表；
  旧 JSON 文件首次启动时按用户**一次性**事务导入并打标记，之后不再写入，保留作回滚输入。
  JSON 损坏时启动**显式失败**，不会静默成空库。
- **FastAPI TestClient 的 WebSocket**：上下文退出会触发服务端 cleanup；
  `dead_man_switch` 每 2s 发一次心跳，`receive_json()` 会等到它——不要假设没有消息。
- **`_mcp_sessions` 目前没有 TTL**（K5，M4 处理）。
- **CI 的 pytest 步骤跑 `tests/` 全目录**；`verify_*.py` 是独立脚本，不被 pytest 收集。
- **两个包并存**（M2a–M4）：新增代码进 `app/`，legacy `server/*` 只允许 import `app/*`。
  `server/config.py` 是 `app/config.py` 的别名层，改配置只改一处。

### Android

- **滑条点击**：Material3 Slider 一次点击里连续调 `onValueChange` 与 `onValueChangeFinished`；
  完成回调若捕获子组件上一帧的值就会提交旧档位（#7 修过）。提交时读**持有状态的父组件**里的最新值。
- **后台状态回写**：`BridgeState` 由服务异步更新，旧命令的结果可能晚于新点击到达。
  只用固定 `BridgeState` 的测试抓不到这类问题——测试里要用 `mutableStateOf` 动态推送回写（见 §0.3）。
- **签名**：debug 与 release 签名不同，互相覆盖安装需先卸载；用户升级时提醒备份设置。

---

## 7. 交接给下一个 Agent 的提示词（复制即用）

```
你要接手 AI-intoU（樱趣）。开工前按顺序读：
1. AGENTS.md（红线 R1–R4，最高优先级）
2. docs/AGENT-WORKFLOW.md —— 先读 §0「当前交接」和 §6「已知坑」
3. docs/redesign/02-architecture.md（设计宪法）
4. docs/redesign/01-product.md（已锁定决策 D1–D6）

本次任务：<见 AGENT-WORKFLOW.md §0.3，或填写新任务>

约束：
- 不得违反 AGENTS.md 的红线（尤其 R1–R3：IP / 凭据 / 绝对路径不进任何文本框，包括 CI 工作流）
- 修 bug 先写复现用例，验收以测试全绿为准
- 推送后做 blob SHA 回读校验（§5）
- 有设计疑问先问，不要自行推翻已锁定决策

请先复述你对本次任务的理解、验收标准和执行计划，等我确认后再动手。
```

**不要一次性把整个任务丢给 Agent**：先要它的理解与计划，确认后再让它写代码。
跑偏时不要口头纠正——让它把偏离写进对应文档的「决策记录」（ADR）。
