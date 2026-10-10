# 版本号与发版规范

> 适用于所有改动 `android/` 或 `server/` 的 PR，**人和 Agent 一视同仁**。
> CI 会检查本文的硬性规则（见 §6），不合规的 PR 无法合并。

---

## 0. 一句话版

**一个版本号只能发布一次。** 已经发布过的版本号，后续任何改动都不得再用。
改了 App 就要升 App 的版本号，改了服务端就要升服务端的版本号，并在对应的变更记录里写一条。

---

## 1. 两条独立的版本线

App 和服务端**各自编号，互不跟随**。改其中一边，不需要动另一边。

| | App（Android） | 服务端 |
| --- | --- | --- |
| 唯一来源 | `android/app/build.gradle.kts` 的 `versionName` + `versionCode` | `server/app/__init__.py` 的 `__version__` |
| 格式 | `MAJOR.MINOR.PATCH`（如 `0.18.0`） | `MAJOR.MINOR.PATCH`（如 `1.2.0`） |
| 变更记录 | `docs/release-notes/v<versionName>.md`（面向用户，中文） | `server/CHANGELOG.md`（面向部署者） |
| Git tag | `v<versionName>`，如 `v0.18.0`——触发 `release.yml` 发版 | `server-v<版本>`，如 `server-v1.2.0` |
| 用户能在哪里看到 | 设置页底部、更新弹窗 | `/health`、MCP `serverInfo.version`、`doctor` |

> **版本号只在唯一来源里写一次。** 其他地方（FastAPI `version=`、MCP `serverInfo`、更新清单）
> 一律从唯一来源读取，不得手写字面量。

---

## 2. 什么时候升哪一位

现阶段 App 处于 `0.x`，规则如下：

| 改动 | App | 服务端 |
| --- | --- | --- |
| 新功能、可见的 UI 变化、协议新增字段 | MINOR +1，PATCH 归零（`0.17.3 → 0.18.0`） | MINOR +1 |
| 只修 bug，没有新功能 | PATCH +1（`0.18.0 → 0.18.1`） | PATCH +1 |
| **不兼容变更**（旧客户端会坏、要求两端同时升级） | MINOR +1，release notes 必须有「⚠️ 不兼容变更」一节 | MAJOR +1 |
| 只改测试、CI、文档、注释 | 不升 | 不升 |

**`versionCode` 每次升 `versionName` 都 +1，永远只增不减，不回收、不跳着复用。**
它是 Android 判断「能不能覆盖安装」的唯一依据，也是 App 内更新比较新旧的依据。

App 进入 `1.0.0` 之后，不兼容变更改为升 MAJOR。什么时候进 1.0 由项目所有者决定。

---

## 3. 一个开发周期怎么走

```
上次发布 v0.17.0（versionCode 23）
   │
   ├─ 第一个改 App 的 PR：直接把版本升到 0.18.0 / 24，
   │   新建 docs/release-notes/v0.18.0.md，写下这个 PR 的条目
   │
   ├─ 后续 PR：版本号已经是「未发布的 0.18.0」，不用再升，
   │   只往 v0.18.0.md 里追加条目
   │
   └─ 发版：项目所有者推送 tag v0.18.0 → release.yml 构建并发布
       从这一刻起 0.18.0 已发布，下一个改 App 的 PR 必须升到 0.18.1 或 0.19.0
```

怎么判断当前版本号「发没发过」：看 GitHub Releases 或 `git tag`。
**有同名 tag = 已发布 = 必须升。** 不要凭感觉、不要看 PR 标题判断。

服务端同理：`server/CHANGELOG.md` 顶部的 `## Unreleased` 段落收集条目，
发布时把它改成 `## v1.2.0 — 日期`，打 `server-v1.2.0` tag，再新开一个空的 `## Unreleased`。

---

## 4. 改 App 的 PR 必须同时做到

- [ ] `versionName` 高于最近一个已发布的 tag（如果当前值已经高于它，说明本周期已升过，保持不动）
- [ ] 如果升了 `versionName`，`versionCode` 恰好 +1
- [ ] `docs/release-notes/v<versionName>.md` 存在，并写了本 PR 的用户可见变化
- [ ] PR 描述里写明「App 版本：x.y.z / versionCode N」

改服务端的 PR：

- [ ] `server/CHANGELOG.md` 的 `## Unreleased` 下有本 PR 的条目
- [ ] 如果本周期第一次改服务端，`__version__` 已按 §2 升位
- [ ] 不兼容变更写进 `### Breaking`，并说明需要哪个 App 版本配合

---

## 5. 发版流程（项目所有者执行）

App：

1. 确认 main 上的 `versionName` 就是要发的版本，`docs/release-notes/v<版本>.md` 已写完。
2. 推送 tag：`git tag v0.18.0 && git push origin v0.18.0`。
3. `release.yml` 会检查 tag 和 `versionName` 是否一致，签名、构建，并以 release notes 作为正文发布。
4. 更新通道（App 内检查更新，v0.18.0 起）由同一个 workflow 发布，不需要手动操作。

服务端：

1. `CHANGELOG.md` 的 `Unreleased` 改为 `## v<版本> — <日期>`，合入 main。
2. 推送 tag：`git tag server-v1.2.0 && git push origin server-v1.2.0`。
3. 生产部署只从 tag 拉取（见 `ROADMAP.md`），不部署未打 tag 的 main。

**禁止**：删除或移动已推送的 tag；修改已发布 Release 附带的 APK；用同一个版本号重新发布。
发错了就再发一个更高的版本。

---

## 6. CI 门禁

`.github/workflows/version-check.yml` 在每个 PR 上执行：

| 条件 | 检查 |
| --- | --- |
| PR 改了 `android/app/src/main/**`、`android/app/build.gradle.kts` 或 `proguard-rules.pro` | `versionName` 高于最近一个 `v*` tag；`versionCode` 高于该 tag 中的值；`docs/release-notes/v<versionName>.md` 存在 |
| PR 改了 `versionName` | `versionCode` 恰好比 base 分支 +1 |
| PR 改了 `server/app/**`、`server/server/**`、`server/alembic/**` 或 `server/requirements-server.txt` | `server/CHANGELOG.md` 也在本 PR 的改动里 |
| 任何 PR | `versionCode` 不得低于 base 分支；不改 `versionName` 就不得改 `versionCode` |

只改测试（`android/app/src/test/**`）、文档、CI 的 PR 不触发 App 版本检查。

---

## 7. 历史

| 日期 | 事项 |
| --- | --- |
| 2026-08-26 | App 从 0.8.0 / 8 起导入本仓库 |
| 2026-10-04 | PR #7 修复吮吸滑条，改了 App 行为，但版本号停在 0.15.0 / 20 没动——同一个版本号对应了两份不同的 App。本规范 §4、§6 就是为了防止这种情况 |
| 2026-10-06 | `versionCode` 从 20 直接跳到 22（0.15.0 → 0.16.0），21 从未使用。此后遵守 §2 的 +1 规则 |
| 2026-10-08 | 首个 tag 发布：`v0.17.0`（versionCode 23）。此前的版本没有 tag |
| 2026-10-10 | 本规范生效。服务端版本号此前为手写字面量 `1.1.0`，改为唯一来源 `__version__`（落地见 v0.18.0 待办） |
