# 樱趣 v0.13 实施记录 · UI 去赘与版式重做

本轮把已确认的 HTML 样板落到 Compose 实装。样板：`<LOCAL_WORKDIR>/ui-clean-v013-preview.html`。
代码基准：v0.12（`ai-intou-v012/`），本轮成果在 `ai-intou-v013/`。版本号 0.13.0 / 18。

## 改动清单

### 1 玩具页
- 删除操作失败时弹出的「设备未连接」错误红条（原 `state.error` 独立提示块）。蓝牙 / Relay 状态卡保留，仍显示实时状态。
- 删除底部说明「断网、Relay 断开或服务退出时会自动发送停止帧……STOP ALL。」
- 删除吮吸卡两处说明：「对应玩具机身档位（1-3 档为持续档……协议 06/07/08）」「0 档 = 停止；拖到 0 后松手立即停止」。交互行为不变（拖到 0 仍立即发停止）。
- 顶栏 logo 换成樱花（见 5）。

### 2 底栏
- 删除常驻「全部停止」按钮（原 `AppNavigation` 的 `running` / `onStop` 参数一并去掉）。设备卡右上角红色停止钮保留。

### 3 日志页
- 页面结构改为「固定头部 + 独立滚动列表」：标题、「使用频率」热力图、来源筛选 chips 固定，只有下方日志列表滚动。
- 删除三处说明文字：热力图计数口径长文、「仅本机保存最近 500 条……」、底部「『已提交蓝牙』表示 Android 蓝牙接口接受写入……」。
- 日志条目改为紧凑组件（动作 / 来源·状态 / 时间），筛选与空状态保留。

### 4 连接设置
- 删除「高级路径」整节（MCP Path、Phone Relay Path 输入与其端点展示）。路径沿用既有配置值，不改写用户已保存的自定义路径。
- 删除「测试连接」按钮及其回调（`onTest`）。登录仍然是一次请求完成校验并保存。
- 「保存并启动」→「登入」，移入「认证方式」卡片内、位于「记住密码」下方；Token 模式同样在该位置提交。
- 删除副标题「单服务器 · 自部署优先」及若干解释性文案（Token 加密说明、密码用途说明、外观「主页面顶栏也可直接切换」、开发者模式说明、关于页说明）。
- RikkaHub 区块：新增「预览完整 JSON」，位于「复制完整 JSON」上方，默认折叠、点击展开；内容由 `draft.rikkaHubJson()` 实时生成，token 取自已填写的 Token 输入框。删除原先复制凭证前的确认弹窗（`pendingSensitiveCopy` 流程）。
- 保留：HTTP 明文地址的红色安全警告、检查更新与作者信息入口。
- 「作者信息 · MorphieEndless」→「作者信息」。

### 5 版式与配色
- 页面改用渐变背景，由 `YingtiTheme` 在根容器统一绘制：
  - Gemini 按图标配色走粉（左上）→ 蓝（右下）对角双色。
  - 其余版式为主色 / 辅色的径向柔光叠在底色上，避免整条色带。
  - `colorScheme.background` 置为透明，让各页 Scaffold 透出渐变，不逐页改背景。
- Gemini 主色由粉改为图标粉 `#D9659B`（暗色 `#EE9FC4`），容器色同步，控件（滑杆、按钮、选中态、底栏、热力图）整体跟随。
- 琥珀黄改蜂蜜黄：亮色 `#9A7B2D` → `#EAB308`，容器 `#FEF0B3`；暗色主色提亮到 `#F0CE60`。因主色变亮，`onPrimary` 改用深色（#4A3806 / #3E3109）。
- 新增樱花 logo 组件 `SakuraLogo.kt`：取自 `sakura-signal.svg` 的主樱花路径，用 `Canvas` + `PathParser` 绘制；五瓣各自取主题派生的一档色（压暗 16% / 30% / 主色 / 提亮 26% / 10%），花心与主色保持可感知明度差；纯黑/纯白版式退化为灰阶阶梯。无底色、放平，随版式与亮暗整体位移。

## 测试

- `ThemeTest`：版式名列表同步为「蜂蜜黄」；Gemini 断言更新为图标粉蓝；对比度检查范围从 `drop(5)` 扩到 `drop(3)`（覆盖重配色后的蜂蜜黄与 Gemini）；新增樱花五瓣互异与花心提亮两项。
- 已用脚本按 sRGB 相对亮度复算 12 组（6 套 × 亮暗）共 72 对前景/背景，全部 ≥ 4.5。修正了两处原本会挂的搭配：Gemini 亮色 `onPrimary`（白字仅 3.34 → 改深色 4.88）、蜂蜜黄暗色 `onPrimaryContainer`（4.22 → 调整容器色后 4.98）。
- 未改动的既有测试（PatternApi / ConnectionConfig / SvakomProtocol / FlexibleNumber / ActivityHistory）不受影响。

## 未做 / 待确认

- 本地无 JDK 与 Android SDK，未编译；Kotlin 语法用词法扫描（括号 / 字符串 / 模板串）核对通过，交叉引用与残留文案均已 grep 验证。
- 真机未验：樱花 logo、渐变背景、五瓣配色的实际观感仍需装机确认。
- `LoginScreen.kt` 不在当前导航流内（设置页承担登录），其中的「登录并启动」文案未动。
- 应用图标（`ic_launcher`）未随樱花 logo 调整。

## 提交与 CI

- 分支 `feat/v013-ui-cleanup`，PR https://github.com/MorphieEndless/AI-intoU/pull/3
- 11 个文件，+416 / −229，三个提交：
  1. 主体改动
  2. 修复 `SakuraLogo` 编译错误（`ImageVector` 的 `addPathNodes` 在 Builder DSL 里解析到 lambda 重载，改用 `Canvas` + `PathParser` 直接绘制）
  3. 修正樱花配色在极端主题下的退化，并加固单测
- CI：`Test server` success；`Build APK`（`test` + `Debug APK (test + assemble)` + `Release APK (signed)`）全部 success
  - 产物：`yingti-bridge-debug-apk` 16.8 MB、`yingti-bridge-release-apk` 11.0 MB
- 首轮 CI 暴露的两个问题均由本地 Oklab 预演复现并修正：五瓣在纯黑/纯白主色下塌成一色、花心在极亮主色下与花瓣同色消失。单测已改为覆盖全部九套版式的亮暗主色。

