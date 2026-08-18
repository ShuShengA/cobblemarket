# CobbleMarket

A player-to-player trading market for Cobblemon servers — buy and sell Pokémon and items, with listing fees, per-player bans, transaction history, expired listing returns, and optional CobbleDollars currency support.

一个面向 Cobblemon 服务器的玩家交易市场模组：支持精灵与物品的挂单买卖，包含手续费、封禁、交易历史、过期退回，并支持 CobbleDollars 虚拟货币。

## Features

- **Pokémon Market** — list Pokémon from your party or PC, browse with filters (species, shiny, gender, type, exact IV match, price/level/newest sorting) and paging
- **Item Market** — sell items from your inventory; buyers purchase any quantity; item blacklist for admins
- **Listing Fees** — configurable percentage fee for Pokémon and item listings
- **Listing Expiry** — configurable duration (default 14 days); expired listings go to a return list you can claim anytime (partial claims supported)
- **Pending Balance** — seller earnings accrue in a pending balance; collect anytime, safely kept even if your inventory is full
- **Ban System** — ban players from trading with `/market ban <player> [duration] [reason]`; bans only restrict trading, never freeze assets
- **Transaction History** — in-game history screen plus CSV logs under `config/cobblemarket/history/`
- **Currency** — item currency (diamond by default, configurable) or **CobbleDollars** virtual currency (auto-detected)
- **Admin Tools** — cancel any listing, Pokémon blacklist (species + IV), item blacklist
- **Open the market** — press `K`, use `/market gui`, or the Cobblemon Smartphone app (auto-integrated)

## 功能特性

- **精灵市场** — 从队伍或 PC 上架精灵；支持物种、闪光、性别、属性、IV 精确匹配、价格/等级/最新排序与分页
- **物品市场** — 背包物品上架，买家可购买任意数量；管理员可设物品黑名单
- **上架手续费** — 精灵与物品分别可配置费率
- **挂单过期** — 可配置天数（默认 14 天）；过期进入退回列表，随时可领取（支持部分领取）
- **待领余额** — 卖家收益进入待领余额，随时领取，背包满也不会丢
- **封禁系统** — `/market ban <玩家> [时长] [原因]` 封禁交易；封禁只限制交易，不冻结资产
- **交易历史** — 游戏内历史界面 + `config/cobblemarket/history/` 下的 CSV 日志
- **货币** — 物品货币（默认钻石，可配置）或 **CobbleDollars** 虚拟货币（自动检测）
- **管理员工具** — 取消任意挂单、精灵黑名单（物种+IV）、物品黑名单
- **打开市场** — 按 `K`、`/market gui`，或 Cobblemon Smartphone 应用（自动集成）

## Screenshots / 截图

<!-- TODO: add screenshots -->
<!-- 待补充：市场界面、上架界面、交易历史等截图 -->

## Requirements / 安装要求

- Minecraft 1.21.1
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- [Cobblemon](https://modrinth.com/mod/cobblemon) ≥ 1.7.0
- Optional: [CobbleDollars](https://modrinth.com/mod/cobbledollars) for virtual currency; Cobblemon Smartphone for the smartphone app entry

## Commands / 命令

| Command | Description |
|---|---|
| `/market gui` | Open the market entry screen |
| `/market ban <player>` | Ban a player permanently from trading |
| `/market ban <player> <duration>` | Ban for a duration, e.g. `7d`, `12h`, `30m` |
| `/market ban <player> <duration> <reason>` | Ban with a reason shown to the player |
| `/market unban <player>` | Remove a ban |
| `/market banlist` | List all active bans |

## Data Safety / 数据安全

Market data is stored in `world/data/cobblemarket*.dat`. On every startup, the mod verifies file integrity and maintains a rolling `.bak` backup:

- **Auto-verify on startup** — if the main file is corrupted, it is automatically restored from `.bak` (only changes since the last backup are lost)
- **Corrupt-file preservation** — if no backup exists, the damaged file is kept as `cobblemarket*.dat.corrupt` for manual recovery, and the server starts with fresh data
- **Log messages** — watch for `restored from backup` (recovered) or `preserved as .corrupt` (needs manual attention) in the server log

市场数据保存在 `world/data/cobblemarket*.dat`。每次启动模组会校验文件完整性并维护 `.bak` 滚动备份：

- **启动自动校验** — 主文件损坏时自动从 `.bak` 恢复（仅丢失上次备份后的增量）
- **损坏文件保留** — 若无备份可用，损坏文件会保留为 `.dat.corrupt` 供人工修复，服务器以空数据启动
- **日志提示** — 服务器日志中出现 `restored from backup`（已恢复）或 `preserved as .corrupt`（需人工处理）时请留意

## Configuration / 配置

Config file: `config/cobblemarket.json` (generated on first launch)

| Key | Default | Description |
|---|---|---|
| `currency.cobbledollars` | auto | Use CobbleDollars currency (auto-enabled when the mod is installed) |
| `currency.item` | `minecraft:diamond` | Currency item ID when not using CobbleDollars |
| `pokemonListingFeePercent` | 5.0 | Pokémon listing fee percentage (0 = no fee) |
| `itemListingFeePercent` | 5.0 | Item listing fee percentage (0 = no fee) |
| `maxPokemonListingsPerPlayer` | 0 | Max active Pokémon listings per player (0 = unlimited) |
| `maxItemListingsPerPlayer` | 0 | Max active item listings per player (0 = unlimited) |
| `listingDurationDays` | 14 | Days before a listing expires |
| `pendingReturnRetentionDays` | 30 | Days to keep unclaimed returns; overdue returns are **permanently deleted without refund** (0 = keep forever) |

## Security / 安全声明

**Please download only from official channels** (CurseForge / Modrinth / this repository's Releases). Jars from any other source (QQ groups, "friend repacks", third-party download sites) cannot be guaranteed safe. You can verify every official file against the SHA-256 hash shown on its CurseForge/Modrinth page.

This mod is designed with **server-authoritative architecture**: all transactions are validated server-side, the client is display-only, and no client data (prices, stats, balance) is ever trusted as an asset source. Cheating on the client cannot produce money or items. See [docs/security/SECURITY_en.md](docs/security/SECURITY_en.md) for the full defense design.

**请仅从官方渠道下载**（CurseForge / Modrinth / 本仓库 Releases）。来自其他渠道的 jar（QQ 群、他人转发、第三方下载站）无法保证安全。官方文件均可与 CurseForge/Modrinth 页面显示的 SHA-256 哈希核对。

本模组采用**服务端权威架构**：所有交易由服务端校验，客户端仅负责显示，任何客户端数据（价格、数值、余额）都不会被当作资产依据——客户端作弊无法凭空获得货币或物品。完整防御设计见 [docs/security/SECURITY_zh.md](docs/security/SECURITY_zh.md)。

## License / 许可

MIT — see [LICENSE](LICENSE).
