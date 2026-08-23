# CobbleMarket 货币系统说明（服主向）

本文档解释 CobbleMarket 支持的三种货币模式、自动选择规则，以及它与 Cobblemon Economy（cobeco）、CobbleDollars、Impactor 的配合方式。

## 一、三种货币模式

CobbleMarket 的货币由 `config/cobblemarket.json` 中的 `currency` 配置决定：

| 字段 | 默认 | 说明 |
|---|---|---|
| `currency.cobblemonEconomy` | 自动 | 是否优先使用 Cobblemon Economy 的货币 API |
| `currency.cobecoCurrency` | `POKE` | cobeco 结算货币：`POKE`=PokeDollars，`PCO`=PokeCoins（仅 `cobblemonEconomy=true` 时生效） |
| `currency.cobbledollars` | 自动 | 是否使用 CobbleDollars 货币（`cobblemonEconomy=true` 时被忽略） |
| `currency.item` | `minecraft:diamond` | 货币物品 ID（两个虚拟货币开关均为 false 时生效） |

优先级：**cobeco → CobbleDollars → 物品货币**。

货币单位显示：PokeDollars / CobbleDollars 模式的价格单位统一为 **₽**（宝可梦货币符号，行内与弹窗一致），PCO 模式弹窗显示 **PokeCoins**、行内显示 **PCo**（与 cobeco 游戏内一致），物品模式显示物品名。

## 二、自动选择规则

> ⚠ **所有 `currency.*` 配置仅在服务器启动时读取，修改后需重启服务器生效。**

### 全新安装（首次启动自动生成配置）

按服务器实际安装的模组自动选择：

- 装了 Cobblemon Economy → cobeco 模式
- 否则装了 CobbleDollars → CobbleDollars 模式
- 都没有 → 物品货币（默认钻石）

### 从旧版本升级

**行为完全不变**：旧配置缺少 `cobblemonEconomy` 字段时自动补写 `false`，继续使用原有货币。想切换到 cobeco，手动改成 `true` 并重启即可。

## 三、Cobblemon Economy（cobeco）内部结构

cobeco 本身有**两种货币**：

- **PokeDollars**：基础货币，用于日常交易、商店、任务奖励
- **PokeCoins（PCO）**：高级/奖励货币，通过任务、指令等方式发放

**CobbleMarket 默认使用 PokeDollars 结算；服主可配置 `currency.cobecoCurrency = "PCO"` 改用 PokeCoins 结算**——两种货币是 cobeco 内部两套独立账本，市场用 PCO 时玩家 `/pco` 查到的余额就是市场余额，PokeDollars 余额不受市场影响。

### main_currency：PokeDollars 存在哪里

cobeco 自己的配置（`world/config/cobblemon-economy/` 下）中，`main_currency` 决定 PokeDollars 余额实际存储的后端：

| 值 | 余额存储位置 |
|---|---|
| `cobeco`（默认） | cobeco 自己的 SQLite 数据库 |
| `cobbledollars` | CobbleDollars 模组的玩家余额 |
| `impactor` | Impactor 模组的主货币账户 |

**镜像同步机制**（0.0.17 已实测）：只要服务器装了 CobbleDollars，cobeco 会在余额变动后把两边余额**双向镜像**——`main_currency` 决定哪个是主账本，变动后自动同步到另一个，两边始终一致（1:1，无汇率）。也就是说，装了 CobbleDollars 时无论 `main_currency` 怎么配，市场与 CobbleDollars 商人的余额**必然一致**，无需手动开桥接。

> 注：cobeco 配置中的 `cobbleDollarsToPokedollarsRate` / `impactorToPokedollarsRate` 汇率字段在当前版本（0.0.17）是预留项，**不生效**。

## 四、组合场景速查表

| 安装的模组 | cobeco 的 main_currency | 市场扣的钱 |
|---|---|---|
| 仅 cobeco | `cobeco`（默认） | cobeco SQLite 的 PokeDollars |
| 仅 CobbleDollars | — | CobbleDollars 余额 |
| 两者都装（新服） | `cobeco`（默认） | cobeco SQLite 的 PokeDollars；镜像机制使 CobbleDollars 商人余额同步一致 |
| 两者都装（新服） | `cobbledollars` | CobbleDollars 余额为主账本；镜像机制使两边一致 |
| 两者都装（老服升级） | 任意 | 原货币不变（升级无感） |
| cobeco + Impactor | `impactor` | Impactor 主货币账户 |
| 都不装 | — | 背包物品（默认钻石） |

## 五、常见问题

### 市场里的钱和 CobbleDollars 商人的钱不一样？

只要装了 CobbleDollars，cobeco 的镜像机制会保持两边余额一致（双向同步），不会出现不一样的情况。只有**未装** CobbleDollars 时，市场余额才独立存在于 cobeco SQLite 中。

### 玩家说市场余额突然变成 0？

服主切换了货币后端（例如从物品货币切到 cobeco 且未开桥接）。市场账本（挂单、待领取、冻结金）里的数字仍然有效，但后续发放/扣款落到新后端。切换前请向玩家公告，避免被误解为丢钱。

### 市场能用 PCO（PokeCoins）结算吗？

可以。把 `currency.cobecoCurrency` 设为 `"PCO"`（写 `PCO` 或 `PokeCoins` 均可，不区分大小写；需 `cobblemonEconomy=true`），市场全链路改用 PokeCoins 结算；玩家 `/pco` 查到的就是市场余额。PCO 与 PokeDollars 互不相通，切换前请向玩家公告。

### 市场能用 Impactor 货币吗？

可以。装 cobeco 并把 `main_currency` 设为 `impactor`，市场自动扣 Impactor 主货币。

### 两个模组都装，想让市场用 CobbleDollars 而不是 cobeco？

改你的配置：`currency.cobblemonEconomy = false`，`currency.cobbledollars = true`。

## 六、安全与信任边界

- 市场本身**从不凭空产生货币**：所有资金变动都是账面流转（买家付款、退款、卖家收款）
- 虚拟货币由上游模组（cobeco / CobbleDollars / Impactor）管理，若上游存在刷钱漏洞，市场无法识别"非法来源"的货币——这是上游依赖的信任边界，不是市场的缺陷
- 紧急应对：把两个虚拟货币开关都设为 `false`，切换回物品货币模式，市场功能不受影响

详见 [docs/security/SECURITY_zh.md](security/SECURITY_zh.md) 第 7 节「货币系统依赖边界」。
