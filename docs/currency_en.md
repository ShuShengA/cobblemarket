# CobbleMarket Currency System (Server Owner Guide)

This document explains CobbleMarket's three currency modes, the auto-selection rules, and how it works with Cobblemon Economy (cobeco), CobbleDollars, and Impactor.

## 1. Three Currency Modes

CobbleMarket's currency is controlled by the `currency` section in `config/cobblemarket.json`:

| Key | Default | Description |
|---|---|---|
| `currency.cobblemonEconomy` | auto | Prefer Cobblemon Economy's currency API |
| `currency.cobecoCurrency` | `POKE` | cobeco settlement currency: `POKE`=PokeDollars, `PCO`=PokeCoins (only used when `cobblemonEconomy=true`) |
| `currency.cobbledollars` | auto | Use CobbleDollars currency (ignored when `cobblemonEconomy=true`) |
| `currency.item` | `minecraft:diamond` | Currency item ID (used when both virtual currency switches are false) |

Priority: **cobeco → CobbleDollars → item currency**.

Currency unit display: PokeDollars / CobbleDollars modes use **₽** (the Pokémon currency symbol) as the price unit everywhere, inline and in dialogs alike; PCO mode shows **PokeCoins** in dialogs and **PCo** inline (matching cobeco's in-game display); item mode shows the item name.

## 2. Auto-Selection Rules

> ⚠ **All `currency.*` settings are read only at server startup — restart the server after changes.**

### Fresh install (config generated on first launch)

Chosen automatically from the mods actually installed:

- Cobblemon Economy installed → cobeco mode
- Otherwise CobbleDollars installed → CobbleDollars mode
- Neither → item currency (diamonds by default)

### Upgrading from an older version

**No behavior change**: old configs missing the `cobblemonEconomy` key get `false` written in, keeping the previous currency. To switch to cobeco, set it to `true` and restart.

## 3. Inside Cobblemon Economy (cobeco)

cobeco itself has **two currencies**:

- **PokeDollars**: the base currency for everyday trading, shops, and quest rewards
- **PokeCoins (PCO)**: a premium/reward currency, granted via quests, commands, etc.

**CobbleMarket settles in PokeDollars by default; owners can set `currency.cobecoCurrency = "PCO"` to settle in PokeCoins instead** — the two currencies are separate ledgers inside cobeco: with PCO, the market balance equals what players see via `/pco`, and PokeDollars balances are untouched by the market.

### main_currency: where PokeDollars live

In cobeco's own config (under `world/config/cobblemon-economy/`), `main_currency` decides which backend actually stores PokeDollars balances:

| Value | Balance storage |
|---|---|
| `cobeco` (default) | cobeco's own SQLite database |
| `cobbledollars` | the CobbleDollars mod's player balances |
| `impactor` | the Impactor mod's primary currency accounts |

**Mirror sync** (verified on 0.0.17): whenever CobbleDollars is installed, cobeco mirrors both balances in both directions after any change — `main_currency` decides which side is the primary ledger, and changes sync to the other automatically, so both sides always agree (1:1, no exchange rate). In other words, with CobbleDollars installed, the market and CobbleDollars merchants always share the same balance regardless of `main_currency` — no manual bridging needed.

> Note: the `cobbleDollarsToPokedollarsRate` / `impactorToPokedollarsRate` fields in cobeco's config are reserved placeholders in the current version (0.0.17) and **have no effect**.

## 4. Scenario Quick Reference

| Installed mods | cobeco's main_currency | What the market charges |
|---|---|---|
| cobeco only | `cobeco` (default) | cobeco SQLite PokeDollars |
| CobbleDollars only | — | CobbleDollars balances |
| Both (fresh install) | `cobeco` (default) | cobeco SQLite PokeDollars; mirror sync keeps CobbleDollars merchant balances consistent |
| Both (fresh install) | `cobbledollars` | CobbleDollars balances as primary; mirror sync keeps both sides consistent |
| Both (upgraded server) | any | previous currency unchanged (no surprise) |
| cobeco + Impactor | `impactor` | Impactor primary currency accounts |
| Neither | — | inventory items (diamonds by default) |

## 5. FAQ

### The market's money differs from the CobbleDollars merchants' money?

As long as CobbleDollars is installed, cobeco's mirror sync keeps both balances consistent (two-way sync) — they cannot diverge. Only when CobbleDollars is **not** installed does the market balance live exclusively in cobeco's SQLite.

### A player says their market balance suddenly became 0?

The owner switched currency backends (e.g. from item currency to cobeco without the bridge). The market ledger (listings, pending returns, frozen funds) keeps its numbers, but future payouts/charges land in the new backend. Announce the switch to players beforehand to avoid "lost money" reports.

### Can the market settle in PCO (PokeCoins)?

Yes. Set `currency.cobecoCurrency = "PCO"` (either `PCO` or `PokeCoins`, case-insensitive; requires `cobblemonEconomy=true`) and the whole market settles in PokeCoins; players' `/pco` balance is the market balance. PCO and PokeDollars do not mix — announce the switch to players beforehand.

### Can the market use Impactor currency?

Yes. Install cobeco and set `main_currency=impactor` — the market then charges Impactor's primary currency automatically.

### Both mods are installed, but I want the market to use CobbleDollars instead of cobeco?

Set `currency.cobblemonEconomy = false` and `currency.cobbledollars = true` in your config.

## 6. Security & Trust Boundary

- The market **never creates money out of thin air**: every fund change is ledger flow (buyer payment, refund, seller income)
- Virtual currency is managed by upstream mods (cobeco / CobbleDollars / Impactor); if an upstream mod has its own money exploit, the market cannot tell "illegally sourced" money apart — that is an upstream trust boundary, not a market defect
- Emergency response: set both virtual currency switches to `false` and fall back to item currency; the market keeps working

See also [docs/security/SECURITY_en.md](security/SECURITY_en.md), section 7 "Currency Dependency Boundary".
