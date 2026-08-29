# UI/UX 遗留问题清单（v0.11 之后 · 下一版处理）

> 状态：**待修**（v0.12 处理）
> 记录人：DemiLure（小深） 2026-08-28
> 关联 PR：#2（v0.11.0 UI/UX 打磨）

---

## 背景

v0.11.0 引入了 5 套可切换色板（`Theme.kt`：酒红/浅蓝/浅绿/浅黄/浅紫，各带亮暗），
但每套色板只显式覆盖了 **部分 ColorScheme 槽位**：

- ✅ 已定义：`primary / onPrimary / primaryContainer / onPrimaryContainer / secondary / background / surface / surfaceVariant / error`
- ❌ 未定义：`secondaryContainer / onSecondaryContainer / outline / outlineVariant / surfaceContainerLowest~Highest / onSurface / inverse* / surfaceTint / scrim`

Material3 组件中**未显式覆盖的槽位会回落到 M3 baseline 默认值**（紫色调 `#E7E0EC` 系），
导致切换 UI 版式（浅蓝/浅绿/浅黄/浅紫）时，部分组件颜色不跟随主题。

---

## 问题清单（真机/预览实测反馈）

### 1. Slider 轨道不跟随主题 ⭐

**现象**：切换版式后，滑块 thumb（圆点）颜色变了，但底下的轨道（track）颜色不变。

**根因**：Material3 `SliderDefaults.colors()`：
- `activeTrackColor = colorScheme.primary` → 会变 ✅
- `inactiveTrackColor = colorScheme.surfaceContainerHighest` → **未定义，回落 M3 默认紫灰** ❌

**影响组件**：
- 震动 slider（0-10 档）
- 吮吸·自由模式强度 slider（1-5 档）

**修法**：补全 `surfaceContainer` 系列（见下文推荐方案）。

---

### 2. FilterChip「玩具档 / 自由模式」底色不跟随主题 ⭐

**现象**：切换版式后，两个 chip 的选中态/未选中态底色不变。

**根因**：Material3 `FilterChipDefaults`：
- 选中态容器色 = `colorScheme.secondaryContainer` → **未定义** ❌
- 未选中态容器色 = `colorScheme.surfaceContainerLow` → **未定义** ❌
- 边框 = `colorScheme.outline` → **未定义** ❌

**修法**：补全 `secondaryContainer / onSecondaryContainer / surfaceContainerLow / outline`。

---

### 3. 同根因的其他组件（顺带检查，一改全好）

以下组件同样受「槽位未定义 → M3 默认紫灰」影响，补全 ColorScheme 后自动修复：

| 组件 | 用到的未定义槽位 |
|---|---|
| `ElevatedButton`（玩具档 1-6 按钮） | `surfaceContainerLow` |
| `AssistChip`（协议调试快捷帧） | `surfaceContainerLow` / `outline` |
| `OutlinedTextField`（HEX 输入框、设置页输入框） | `outline` / `outlineVariant` |
| `Switch`（设置页深色/开发者模式开关） | `surfaceContainerHighest`（未选中轨道） |
| `Checkbox`（记住密码） | `outline`（未选中边框） |
| 暗色模式全局 | `onSurface`（默认 #1C1B1F 在深色底上偏暗，建议配 #E6E1E5） |

---

## 推荐修法（v0.12 实施）

**方案 A（推荐）**：在 `Theme.kt` 写一个派生函数，从每套已有基础色自动补全全部槽位：

```kotlin
// 伪代码，落地时按 Compose API 微调
fun deriveScheme(base: YingtiPaletteBase, dark: Boolean): ColorScheme {
    val s = base.surface; val sv = base.surfaceVariant
    val surfaceContainerLowest = if (dark) blend(s, Color.Black, 0.08f) else s
    val surfaceContainerLow    = lerp(s, sv, 0.30f)
    val surfaceContainer       = lerp(s, sv, 0.50f)
    val surfaceContainerHigh   = lerp(s, sv, 0.70f)
    val surfaceContainerHighest= lerp(s, sv, 0.88f)
    val secondaryContainer     = if (dark) lerp(s, base.primary, 0.28f) else lerp(s, base.primary, 0.12f)
    val onSecondaryContainer   = if (dark) Color.White else Color(0xFF1C1B1F)
    val outline                = lerp(sv, onSurface, 0.38f)
    val outlineVariant         = sv
    ...
}
```

要点：
- `surfaceContainerHighest` 决定 slider 未激活轨道 → **必须补**
- `secondaryContainer` 决定 FilterChip 选中态 → **必须补**
- 只补槽位，**不改已冻结的 primary 系色值**（色板冻结铁律不变）
- 补完后 10 组（5 色 × 亮暗）由同一函数派生，一致性有保证

**方案 B**：逐套手写 30+ 槽位 × 10 组（工作量大、易错，不推荐）。

**验证清单**（修完必测）：
- [ ] 切 5 套版式，亮/暗各一遍：震动 slider 轨道变色
- [ ] 玩具档/自由模式 chip 选中态变色
- [ ] 玩具档 1-6 按钮、协议调试 AssistChip、HEX 输入框边框变色
- [ ] 设置页 Switch / Checkbox 变色
- [ ] 暗色模式下 onSurface 可读性

---

## 备注

- v0.11.0 已完成的项（顶栏急停红按钮、状态卡语义色、震动档位小字、吮吸 6 模式去重、开发者模式开关）不受影响，无需回退。
- 此问题只影响「换肤后的次级组件色」，默认酒红色板下 M3 baseline 紫灰与酒红近似，观感不明显；换成浅蓝/绿/黄后差异暴露。
