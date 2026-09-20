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

**用户可见现象**：切到 OFF（默认）后界面**缩小**、英文**变粗变宽**，于是出现标签压住输入框、文字画出按钮等。

⚠ **措辞口径（2026-09-20 用户纠正）**：写日志/公告/跟玩家解释时，一律按「**关掉**强制 Unicode 字体之后，界面会**缩小**、英文文字会**变粗变宽**，于是放不下」这个方向说。
玩家默认就是 OFF，站在作者视角写「开 ON 会把界面放大」容易被理解成反的 —— 技术描述没错，但读者看到的不是那样。

## 二、判定口径（做新界面时照这个算）

字体宽度（按默认位图字体）：
- 英文 / 数字 / 半角符号：**6 px/字符**
- 中文 / 全角符号：**9 px/字符**

⚠ **`W − 8` 是安全内边距约定，不是硬边界** —— 控件实际宽就是 W，只有「文字宽 > W」才真画到控件外。

## 三、已完成

### 3.1 配置界面四件套（2026-09-20 第一轮）

`PurpleCardConfigScreen` / `BlackCardConfigScreen` / `ServerConfigScreen` / `FinanceConfigScreen`

1. **英文词条缩短 17 条** —— 手法几乎全是「删掉重复的类型名前缀」（`Meowth·Purple Gold Card` 每行写一遍，而界面标题本来就写着它）。全部落回 164px 以内，中文一字未动。
2. **四处 `drawNumRow` 加截断兜底**：`TextUtil.truncateString(label, dialogW - 100)`
   —— 宽度从 `dialogW` 反算，与输入框位置（`dialogX + dialogW - 86`）同一口径。

### 3.2 其余界面（2026-09-20 第二轮，全部完成）

| 界面 | 处理 |
|---|---|
| `HistoryScreen` 行渲染 | 行尾（价格/买家）先占位，中间段按剩余像素截断 |
| `LoanHistoryScreen` 撤销弹窗 | 英文 4 行缩短；玩家名先按剩余宽截、整行再兜底 |
| `AdminBanScreen` 封禁列表行 | 截断到解封按钮左侧 4px（`panelWidth - 68`） |
| `AdminPokemonScreen` / `MarketScreen` / `BuyOrderScreen` 弹窗 | `drawInfoLines` 新增 `maxWidth` 参数，传 `dialogW - 24` |
| `MarketScreen` / `AuctionScreen` 筛选按钮 | 新增 `filterButtonText()`：值按按钮宽截断（前缀宽反算） |
| `AdminScreen`「所有已上架精灵」 | 英文 `All Listed Pokémon` → `Listed Pokémon`（**该词条同时是页面标题**） |
| `SellSelectScreen` 价格占位符 | 英文 `Enter price` → `Price`（框仅 56px） |
| `MeowthBankScreen` 面板标题 / 关市提示 | 卡名去掉 `Meowth·` 前缀；关市提示英文缩短 |
| `MarketEntryScreen` 余额 HUD 模式 | 英文 `Adaptive` → `Auto`（按钮仅 46px） |
| `PriceLimitScreen` V 档/形态按钮 | 阈值 `62` → `selectBtnW - 8`（68px 按钮），按钮宽提成常量 |
| `auction.bid_too_low` 重复定义 | 删掉后写那条；文案改成覆盖「过低 + 未达最低加价」两条校验 |

**新增工具**（本轮为截断引入，后续复用）：
- `TextUtil.truncateStyled(Text, maxWidth)` —— **保留分段样式**的截断。`truncateText` 会把整串压成一段
  （类型行属性色 / IV 行 EV 红 / 闪光星标金全丢），走 `Text.visit` 按样式段裁剪。
- `EntryBadgeRenderer.drawInfoLines(..., maxWidth = Int.MAX_VALUE)` —— 默认不截断（`BuyConfirmScreen`
  是无边框整页，不传）；有边框容器一律传 `dialogW - 24`。

### 3.3 判定口径补充（本轮实测）

**文档里那列「超出」是按 6px/字符的上界估的，比真实字宽宽**（`i l t : .` 等窄字符实际 2~4px）。
所以有几条按估算超、按真实字体不超，**不改**：

- `MeowthBankScreen` 存款按钮 `Deposit/Withdraw`：真实约 84px，按钮 100px → 不溢出
- `AdminScreen` 其余按钮（`Ban Management` 74、`All Buy Orders` 74、`All History` 52…）：均 < 79px 预算

反过来，**只有真超的才动**：`All Listed Pokémon`（真实约 97 vs 按钮 87）、`Enter price`（58 vs 可用 48）。


## 四、手法约定

1. **缩短英文词条**（主力）—— 中文一般不用动
2. 控件能加宽就加宽 —— ⚠ 但**动坐标/间距前先问用户**（筛选按钮行、管理面板双列都试过，没有加宽空间）
3. `truncateString` 兜底 —— **宽度从布局反算，别写死**；富文本用 `truncateStyled`
4. ❌ **不用拆行**（会动到高度；用户明确说「主要是宽度问题，跟高度没关系」）

## 五、分支

当前 **`1.1.1`**（1.1.0 已于 2026-09-20 发布）。修完 → 上浮 `1.2.0` / `1.3.0`。

⚠ **1.3.0 独有的新界面**（RP 商城那批）不在这轮范围，等上浮过去后单独补。
