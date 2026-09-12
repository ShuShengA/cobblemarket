# CobbleMarket

A player-to-player trading market for Cobblemon servers, available for both **Fabric and NeoForge** — buy and sell Pokémon and items, with listing fees, per-player bans, transaction history, expired listing returns, and four currency modes (Cobblemon Economy / CobbleDollars / Impactor / item currency).

一个面向 Cobblemon 服务器的玩家交易市场模组，**同时提供 Fabric 与 NeoForge 两个版本**：支持精灵与物品的挂单买卖，包含手续费、封禁、交易历史、过期退回，并支持四种货币（Cobblemon Economy / CobbleDollars / Impactor / 物品货币）。

## Features

- **Pokémon Market** — sell Pokémon from your party or PC and browse every listing on the server
- **Item Market** — sell items from your inventory; buyers purchase any quantity
- **Auction House** — timed auctions with live bidding and automatic settlement
- **Buy Orders** — post what you want and your price; other players deliver it
- **Listing Fees & Returns** — configurable fees and expiry, with returns and pending earnings you can claim anytime
- **Transaction History** — in-game history screen plus CSV ledgers for server owners
- **Meowth Bank (optional finance system)** — credit loans, installment payments, deposits and card credentials (off by default)
- **Currency** — item currency (diamond by default) or virtual currency (**Cobblemon Economy** / **CobbleDollars** / **Impactor**); Cobblemon Economy is Fabric-only, so NeoForge offers CobbleDollars / Impactor / item currency
- **Ban System** — ban players from trading; bans restrict trading only and never freeze assets
- **Admin Tools** — blacklists, price limits, bans, forced delistings and server config
- **Client Settings** — per-player switches for animations, the balance HUD and the cursor
- **Open the market** — press `K`, use `/market gui`, or the Cobblemon Smartphone app (auto-integrated)

For the full feature list, see the [CHANGELOG](CHANGELOG_EN.md).

## 功能特性

- **精灵市场** — 从队伍或 PC 上架精灵，并可浏览全服挂单
- **物品市场** — 背包物品上架，买家可购买任意数量
- **拍卖场** — 限时拍卖，实时竞价与自动结算
- **求购单** — 挂出需求与价格，由其他玩家交付
- **上架与退回** — 可配置的手续费与过期时限，退回与待领收益随时可领
- **交易历史** — 游戏内历史界面 + 面向服主的 CSV 账本
- **喵喵银行（可选金融系统）** — 信用借贷、分期支付、存款与卡片凭证（默认关闭）
- **货币** — 物品货币（默认钻石）或虚拟货币（**Cobblemon Economy** / **CobbleDollars** / **Impactor**）；Cobblemon Economy 仅 Fabric 平台有，NeoForge 为 CobbleDollars / Impactor / 物品三种
- **封禁系统** — 封禁玩家交易；只限制交易，不冻结资产
- **管理工具** — 黑名单、价格限制、封禁、强制下架与服务器配置
- **客户端设置** — 动画、余额 HUD 与鼠标光标等玩家侧开关
- **打开市场** — 按 `K`、`/market gui`，或 Cobblemon Smartphone 应用（自动集成）

完整功能列表见 [CHANGELOG](CHANGELOG.md)。

## Requirements / 安装要求

- Minecraft 1.21.1
- [Cobblemon](https://modrinth.com/mod/cobblemon) ≥ 1.8.0
- **Fabric**: [Fabric API](https://modrinth.com/mod/fabric-api) + [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- **NeoForge**: [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) + Cobblemon (NeoForge build) — Architectury API is **not** required
- Optional: [CobbleDollars](https://modrinth.com/mod/cobbledollars), [Cobblemon Economy](https://modrinth.com/mod/cobblemon-economy) (Fabric only) or [Impactor](https://modrinth.com/mod/impactor) for virtual currency; Cobblemon Smartphone for the smartphone app entry

## Commands / 命令

| Command | Description |
|---|---|
| `/market gui` | Open the market entry screen |
| `/market on` \| `/market off` | Turn the whole market on / off (emergency master switch) |
| `/market ban` \| `unban` \| `banlist` | Ban a player from trading (`<player> [duration] [reason]`, e.g. `7d`, `12h`, `30m`), lift a ban, list active bans |
| `/market card` | Manage Meow·Purple Gold Card / Meow·Black Gold Card (`give` / `revoke` / `list`) |
| `/market loan clear` | Revoke a player's bad debts |
| `/market reload` | Apply config file edits without restarting (currency settings still need a restart) |

Admin commands require OP; `/market loan clear` is owner-only.

| 命令 | 说明 |
|---|---|
| `/market gui` | 打开市场入口界面 |
| `/market on` \| `/market off` | 开启 / 关闭整个市场（紧急总开关） |
| `/market ban` \| `unban` \| `banlist` | 封禁玩家交易（`<玩家> [时长] [理由]`，如 `7d`、`12h`、`30m`）、解封、列出生效中的封禁 |
| `/market card` | 管理喵·紫金卡 / 喵·黑金卡（`give` / `revoke` / `list`） |
| `/market loan clear` | 撤销某玩家的坏账 |
| `/market reload` | 应用配置文件的修改、免重启（货币配置仍需重启） |

管理命令需 OP 权限；`/market loan clear` 仅服主可用。

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

Config file: `config/cobblemarket.json` (generated on first launch). Most settings can be edited in-game — the **Server Config** screen (at the bottom of the admin panel) covers fees, limits, durations and switches, and the **Meowth Bank Config** screen reached from there covers the finance section. Editing the file by hand works too; `/market reload` applies the changes without a restart, except for currency settings, which still need one.

Full currency system guide (server owners): [中文](docs/currency_zh.md) / [English](docs/currency_en.md)
Meowth Bank finance guide (server owners): [中文](docs/meowth_bank_zh.md) / [English](docs/meowth_bank_en.md)

| Key | Default | Description |
|---|---|---|
| `currency.cobblemonEconomy` | auto | Prefer Cobblemon Economy's currency API (auto-enabled when the mod is installed; its built-in bridge can route to CobbleDollars/Impactor — set `main_currency` in the cobeco config to share one balance with CobbleDollars) |
| `currency.cobecoCurrency` | `POKE` | cobeco settlement currency: `POKE`=PokeDollars, `PCO`=PokeCoins (only used when `cobblemonEconomy=true`) |
| `currency.cobbledollars` | auto | Use CobbleDollars currency (auto-enabled when the mod is installed; ignored when `cobblemonEconomy=true`) |
| `currency.impactor` | false | Talk to Impactor's economy API directly, on either loader (ignored when `cobblemonEconomy` or `cobbledollars` is on; never auto-enabled — set `true` explicitly) |
| `currency.item` | `minecraft:diamond` | Currency item ID when no virtual currency is enabled |
| `pokemonListingFeePercent` | 5.0 | Pokémon listing fee percentage (0 = no fee) |
| `itemListingFeePercent` | 5.0 | Item listing fee percentage (0 = no fee) |
| `maxPokemonListingsPerPlayer` | 0 | Max active Pokémon listings per player (0 = unlimited) |
| `maxItemListingsPerPlayer` | 0 | Max active item listings per player (0 = unlimited) |
| `listingDurationDays` | 14 | Days before a listing expires |
| `pendingReturnRetentionDays` | 30 | Days to keep unclaimed returns; overdue returns are **permanently deleted without refund** (0 = keep forever) |

## Security / 安全声明

**Please download only from CurseForge.** Jars from any other source (QQ groups, "friend repacks", third-party download sites) cannot be guaranteed safe. You can verify every official file against the SHA-256 hash shown on its CurseForge page.

This mod is designed with **server-authoritative architecture**: all transactions are validated server-side, the client is display-only, and no client data (prices, stats, balance) is ever trusted as an asset source. Cheating on the client cannot produce money or items. See [docs/security/SECURITY_en.md](docs/security/SECURITY_en.md) for the full defense design.

**请仅从 CurseForge 官方页面下载**。来自其他渠道的 jar（QQ 群、他人转发、第三方下载站）无法保证安全。官方文件均可与 CurseForge 页面显示的 SHA-256 哈希核对。

本模组采用**服务端权威架构**：所有交易由服务端校验，客户端仅负责显示，任何客户端数据（价格、数值、余额）都不会被当作资产依据——客户端作弊无法凭空获得货币或物品。完整防御设计见 [docs/security/SECURITY_zh.md](docs/security/SECURITY_zh.md)。

## License / 许可

**GPL-3.0** — see [LICENSE](LICENSE).

Copyright (C) 2026 Shu_ShengA. This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3. Any modified version you distribute must also be licensed under GPL-3.0 and must keep the original copyright notice.

**Version note:** releases up to and including **1.0.1** were published under the MIT License; **1.1.0 and later** are licensed under GPL-3.0.

**版本说明**：**1.0.1 及更早版本**以 MIT 许可证发布，**1.1.0 起**改用 GPL-3.0。分发修改后的版本时必须以同样的许可证开源，并保留原版权声明。
