# CobbleMarket 存档数据位置

所有数据通过 MC 的 PersistentState 机制保存在**服务端世界存档的 `data/` 目录**下，每个功能一个 `.dat` 文件。

- 单机：`.minecraft/saves/<世界名>/data/`
- 专用服务器：`<服务器目录>/world/data/`

## 数据文件清单

| 功能 | 文件（`<世界存档>/data/` 下） |
|---|---|
| 精灵市场（挂单+待领取+待领取余额） | `cobblemarket.dat` |
| 物品市场 | `cobblemarket_items.dat` |
| 求购单 | `cobblemarket_buy_orders.dat` |
| 拍卖场 | `cobblemarket_auctions.dat` |
| 精灵黑名单 | `cobblemarket_pokemon_blacklist.dat` |
| 物品黑名单 | `cobblemarket_item_blacklist.dat` |
| 精灵价格限制 | `cobblemarket_pokemon_price_limit.dat` |
| 物品价格限制 | `cobblemarket_item_price_limit.dat` |
| 封禁 | `cobblemarket_bans.dat` |
| 离线消息 | `cobblemarket_offline_messages.dat` |

## 说明

- **精灵/物品本体直接内嵌在 .dat 里**：上架时把精灵/物品从玩家背包序列化成 NBT 存进挂单记录（如 `MarketListing.toNbt()` 的 `pokemon` 字段），不是引用 Cobblemon PC 仓库。
- 备份存档时连同 `data/` 目录一起备份即可。

## 备份注意事项（重要）

- **备份插件/脚本必须包含 `data/` 目录**：只备份世界 region 文件而漏掉 `data/`，回滚后会形成错位——玩家数据是旧的（货已回到身上）而市场数据是新的（挂单还在），造成物品复制。
- 每个 `.dat` 旁边有同名 `.bak` 备份（模组自动维护）：状态文件损坏时启动会自动从 `.bak` 恢复；`.bak` 在每次交易保存和正常关服时刷新。
- 模组在每次交易后会尽快把交易数据写盘（约 3 秒内）。即使如此，**仍建议服主使用 `/stop` 正常关服**——直接杀进程会丢失原版数据（背包/方块等）自动保存周期内的改动，这是 MC 原版机制，模组无法干预。
- 交易数据保存失败时，服务器日志会输出 `CobbleMarket state save failed`，同时给在线 OP 发红字告警——看到告警请**先执行 `/stop` 正常关服**（关服保存会再次写盘并刷新备份，尽量抢救数据），然后检查磁盘空间与文件权限。

## 交易历史 CSV 与补偿对账

模组从 beta.4 起把每笔交易**同步写入** CSV 账本（写盘不依赖自动保存，崩溃/杀进程也不丢），位置：`config/cobblemarket/history/history_<日期>_<语言>.csv`，字段为：时间（秒级）、类型、分类、卖家、买家、精灵/物品、价格、手续费、详情。

类型（市场/拍卖/求购单全链路）：上架（市场/拍卖挂单）、求购（求购单发布，发起者占卖家列）、卖出（市场/拍卖成交、求购单交付成交）、下架（主动关闭/过期/流拍/管理员强制，详情列 `reason=` 区分：user / expired / admin / unsold）、退还（待领取退还）。

详情列：精灵完整数值（等级/闪光/IV/EV/特训/性格/特性/性别/球种/携带物/形态）、物品组件（数量/附魔摘要/完整 NBT）——可精确复刻货物。

若玩家报告「上架的精灵/物品消失了」，服主可按三步对账确认是否蒸发并补偿：

1. 在当天 CSV 中按玩家名筛选，找到该物品的「上架/求购」记录（记下价格）
2. 确认该物品没有后续「卖出/下架/退还」记录
3. 确认当前市场/拍卖/求购挂单里也没有该物品

三条同时满足 → 数据确实因服务器异常关闭而丢失 → 按 CSV 记录的价格补偿玩家，并可按详情列精确复刻货物。
