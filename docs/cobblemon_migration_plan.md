# Cobblemon / MC 大版本迁移评估与预案

> 状态：待触发（2026-09-16 定稿）—— 本文**不是待办**，是触发时才启用的预案；触发前不需要做任何准备工作
> 触发条件：Cobblemon 宣布移植到新的 Minecraft 大版本，或对其渲染 / 数据模型做大型重构
> 定位：把「迁移时会疼在哪、先做什么」提前写死，触发时照做，不重新分析

## 背景

Cobblemon 目前停留在 MC 1.21.1。社区检索到其任务追踪里有开发者表述「最终移植到新的 Minecraft 版本时（大概 26.X）才会更新」—— 说明官方**确有移植计划但无时间表**（非官方公告，仅供参考）。届时本模组必须同步迁移。

以下数据为 2026-09-16 在本仓库实测。

## 一、耦合规模实测

| 指标 | 数值 |
|---|---|
| 引用 Cobblemon 的源文件 | 41 个 |
| 引用总行数 | 221 处 |
| 反射点 | 2 处 |

高频引用类（按 import 次数）：

| 类 | 次数 | 用途 |
|---|---|---|
| `RenderablePokemon` | 17 | 精灵渲染 |
| `PokemonSpecies` | 13 | 物种数据 |
| `FloatingState` | 12 | 图标动画状态机 |
| `PosableState` | 6 | 姿态状态 |
| `Cobblemon`（主类） | 4 | 存储 / 玩家数据 |
| `Species` / `Pokemon` / `Stats` / `drawProfilePokemon` | 各 3 | 数据读写 / 详情渲染 |
| `CobblemonItemComponents` | 2 | 物品组件（招式学习器等） |

其余单点：`DataKeys`、`RestBehaviour`、`PokemonSizeCategory`、`TMMoveComponent`、`PoseType`、`renderScaledGuiItemIcon`、`BedrockAnimationRepository`、`HeldItemRenderer`、`calculateHeadYawAndPitch`、`PartyPosition`

重新统计（在模组仓库执行）：

```bash
grep -rh "^import com\.cobblemon" --include="*.kt" common/src/ | sort | uniq -c | sort -rn
```

## 二、风险排序（按本项目实际，不是泛泛而谈）

### ① 存档里的精灵 NBT —— 玩家资产，可能静默损坏（最高）

挂单中 / 拍卖中 / 求购待交付 / 待领取的精灵，**在我们的存档文件里以完整 NBT 存储**。精灵 NBT 的格式由 Cobblemon 定义 —— **本项目「升级不得损坏旧存档」铁律管不到它**。

| 失败方式 | 表现 |
|---|---|
| 解析抛异常 | 多处 `try/catch` 跳过坏条目 → **静默丢精灵** |
| 解析成功但字段降级 | 静默出错（先例：`copyFrom` 丢 `isAlpha`，alpha 体型不可逆丢失） |

涉及文件：`MarketState` / `AuctionState` / `BuyOrderState` / `FinanceState` / `TransactionHistory` / `util/PokemonLoader`

### ② 语义变更 —— 编译通过、行为错

签名变更不可怕（编译期就报错，改起来有明确指引）；**语义变更才是杀器**。已踩过的（全部编译通过）：

- `aspects` 非空被当成「抓到」（实为「见过」）
- `CAUGHT` 改名 `OWNED`（1.8）
- `FriendshipUpdatedEvent` 加参数（**官方更新日志未写**）
- 1.8.1 的 MoLang 条件求值变化（睡眠姿态集体失效）
- `drawProfilePokemon` 签名变更（1.8.0，**官方日志未写**）

**对策**：Cobblemon 一发新版就 **diff 源码**，逐条对照本项目引用的 API —— 这套方法已两次抓到官方日志漏掉的破坏性变更（见 CLAUDE.md「Cobblemon 新版必须 diff 源码」），是唯一有效的手段。

### ③ 渲染层重写 —— 工作量大，但会明确报错

`FloatingState`(12) / `PosableState`(6) / `drawProfilePokemon` / `renderScaledGuiItemIcon` / `HeldItemRenderer` / `BedrockAnimationRepository`

**图标动画、悬停预览、庆祝动画全部建立在这些内部实现之上**，Cobblemon 未提供公开 API 替代。

> ⚠「优先使用官方公开 API」这条通用建议对本项目**无效** —— 照做等于砍掉 3D 图标与动画功能。深度使用内部渲染是这类功能的固有代价。

### ④ 双平台适配

Fabric + NeoForge 对新 MC 版本的跟进节奏不同，两边可用时间可能错开，发布节奏要分别安排。

### ⑤ 反射点（数量少，但会静默失败）

| 位置 | 用途 | 失效后果 |
|---|---|---|
| `CobbleMarketConfig` | 检测 CobbleDollars 是否安装 | 误判货币模式 |
| `CurrencyHandler` | 以 `PokedexEntryProgress.OWNED` 字段是否存在判定 Cobblemon 1.8+ | 改名即误判版本，**静默走错分支** |

## 三、明确不做的事

**现在不做抽象层隔离重构。**

- 抽象层能收口的是「调用点分散」，但本项目的 221 处是**语义依赖**，不是散乱调用
- 它挡不住第 ② 类风险（语义变更）—— 那才是真正让迁移痛苦的部分
- 抽象层接口按**旧 API 语义**设计，Cobblemon 一重构，抽象层自己也要重写
- 现在做 = 立刻承担破坏现有功能的风险，去换一个触发条件未知的收益

等 Cobblemon 公布目标 API（或放出示意图）之后再设计抽象层才有意义。

## 四、行动预案（已拍板）

**核心策略：先跑通最小路径，锦上添花分批恢复。**

| 顺序 | 内容 | 说明 |
|---|---|---|
| 1 | 编译通过 + **存档精灵 NBT 兼容性验证** | 第一优先级，涉及玩家资产：用旧存档的挂单 / 拍卖 / 求购 / 待领取精灵逐个确认能读且字段无丢失 |
| 2 | 核心交易链路 | 精灵 / 物品市场买卖、拍卖、求购单 |
| 3 | 锦上添花分批恢复 | 图标动画 → 悬停预览 → 庆祝动画 → 卡片 / 金融界面特效 |

即使适配慢，核心交易服务也不至于停摆。

## 五、触发信号

- 关注：Cobblemon GitLab 任务追踪 / 官方 Discord / 官方 Wiki Roadmap
- 出现「移植到新 MC」或「重构渲染系统 / 重写数据模型」类任务 → 启动本预案
- 触发前唯一值得持续做的事：**维护 Cobblemon 版本 diff 与踩坑记录** —— 比任何提前重构都实在
