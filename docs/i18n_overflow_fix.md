# 界面文字溢出修复（1.1.1）

> 2026-09-20 起。**只修界面，不动逻辑功能。**

## 一、根因

MC 的「强制使用 Unicode 字体」（Force Unicode Font）影响两处（已读源码确认）：

| 影响 | 位置 | 效果 |
|---|---|---|
| **GUI 档位** | `Window.calculateScaleFactor` → `if (forceUnicodeFont && i % 2 != 0) ++i;` | 开 ON 时奇数档位 **+1**（固定 3 → 实际 4）⇒ 整个界面放大 4/3 倍 |
| **字体宽度** | `FontManager.getActiveFilters` → `FontFilterType.UNIFORM` | ON 走 unifont（半角≈4.5px）；**OFF 走默认位图字体（半角≈6px）⇒ 宽约 20%** |

⚠ **中文完全不受影响**：默认位图字体里没有汉字，中文在两种设置下都走 unifont。
⚠ **绝大多数玩家是 OFF**（默认值），所以 **OFF 才是设计基准**。
⚠ 作者此前一直开着 ON 开发 ⇒ 所有界面余量都偏紧约 20%。

**用户可见现象**：开 ON 时「界面放大 + 字变窄」双双宽松；切到 OFF 就出现标签压住输入框、文字画出按钮等。

## 二、判定口径（做新界面时照这个算）

字体宽度（按默认位图字体）：
- 英文 / 数字 / 半角符号：**6 px/字符**
- 中文 / 全角符号：**9 px/字符**

⚠ **`W − 8` 是安全内边距约定，不是硬边界** —— 控件实际宽就是 W，只有「文字宽 > W」才真画到控件外。

## 三、已完成（4 个配置界面）

`PurpleCardConfigScreen` / `BlackCardConfigScreen` / `ServerConfigScreen` / `FinanceConfigScreen`

1. **英文词条缩短 17 条** —— 手法几乎全是「删掉重复的类型名前缀」（`Meowth·Purple Gold Card` 每行写一遍，而界面标题本来就写着它）。全部落回 164px 以内，中文一字未动。
2. **四处 `drawNumRow` 加截断兜底**：`TextUtil.truncateString(label, dialogW - 100)`
   —— 宽度从 `dialogW` 反算，与输入框位置（`dialogX + dialogW - 86`）同一口径。

## 四、待修（按严重程度，**未做**）

| 界面 | 现象 | 超出 |
|---|---|---|
| `HistoryScreen` 行渲染 | **无任何宽度防护**（同族的 `LoanHistoryScreen` 有 `truncateString`） | **+206** |
| `LoanHistoryScreen` 撤销坏账弹窗第 2 行 | 画到弹窗外 | +118 |
| `AdminBanScreen` 封禁列表行 | 压在解封按钮上 | +74 |
| `AdminPokemonScreen` 下架弹窗「性格+特性」行 | 超出 220 弹窗 | +68 |
| `MarketScreen` / `AuctionScreen` 特性筛选按钮 | 画出按钮外 | +52 |
| `AdminScreen`「所有已上架精灵」菜单按钮 | 画出按钮外 | +17 |
| `SellSelectScreen` 价格占位符（`Enter price` 64 vs 框 56） | 画出输入框 | +8 |
| `MeowthBankScreen` 卡片标题 / 市场关闭提示 / 存款按钮 | 截断 / 超框 | +45 / +52 / +4 |
| `MarketEntryScreen` 余额 HUD「Adaptive」 | 画出按钮 | +2 |

**两处与字体开关无关的硬伤**（中文就超）：
- `MarketScreen` 属性筛选按钮：中文「属性: 超能力」**55 > 60**（吃光边距）
- `PriceLimitScreen` 两处 `truncateString(x, 62)` 配 68px 按钮 → **阈值比可用宽还大**，截完仍超

**还有一处语义 bug**（顺手修）：`auction.bid_too_low` 在 lang 文件里**重复定义**（zh_cn 第 238 + 262 行，en 同样），Gson 后写覆盖前写 → 前一条从未生效。

## 五、手法约定

1. **缩短英文词条**（主力）—— 中文一般不用动
2. 控件能加宽就加宽
3. `truncateString` 兜底 —— **宽度从布局反算，别写死**
4. ❌ **不用拆行**（会动到高度；用户明确说「主要是宽度问题，跟高度没关系」）

## 六、分支

当前 **`1.1.1`**（1.1.0 已于 2026-09-20 发布）。修完 → 上浮 `1.2.0` / `1.3.0`。

⚠ **1.3.0 独有的新界面**（RP 商城那批）不在这轮范围，等上浮过去后单独补。
