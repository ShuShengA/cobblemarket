# CobbleMarket Currency System (Server Owner Guide)

This document explains CobbleMarket's four currency modes, the auto-selection rules, and how it works with Cobblemon Economy, CobbleDollars, and Impactor (including Fabric / NeoForge platform differences).

## 1. Four Currency Modes

CobbleMarket's currency is controlled by the `currency` section in `config/cobblemarket.json`:

| Key | Default | Description |
|---|---|---|
| `currency.cobblemonEconomy` | auto | Prefer Cobblemon Economy's currency API (⚠ Fabric only: Cobblemon Economy has no NeoForge build, so this switch is always ignored on NeoForge) |
| `currency.cobecoCurrency` | `POKE` | Cobblemon Economy settlement currency: `POKE`=PokeDollars, `PCO`=PokeCoins (only used when `cobblemonEconomy=true`) |
| `currency.cobbledollars` | auto | Use CobbleDollars currency (ignored when `cobblemonEconomy=true`) |
| `currency.impactor` | false | Direct Impactor integration (works on both loaders): without Cobblemon Economy, the market talks to Impactor's EconomyService API directly; lower priority than cobblemonEconomy and cobbledollars; NOT auto-detected on fresh installs — set true explicitly to use it |
| `currency.item` | `minecraft:diamond` | Currency item ID (used when all three virtual currency switches are false) |

Priority: **Cobblemon Economy → CobbleDollars → Impactor → item currency**.

Currency unit display: virtual currency modes (PokeDollars / PokeCoins / CobbleDollars) use **₽** as the price unit everywhere — inline, dialogs, hovers, and chat messages (no currency names shown); item mode shows the item name.

### Platform availability

| Currency mode | Fabric | NeoForge |
|---|---|---|
| Cobblemon Economy (POKE/PCO settlement) | ✓ | ✗ Cobblemon Economy has no NeoForge build, the `cobblemonEconomy` switch is ignored |
| Cobblemon Economy bridge (`main_currency` routing to CobbleDollars/Impactor) | ✓ | ✗ same as above |
| CobbleDollars | ✓ | ✓ |
| Direct Impactor integration (`currency.impactor`) | ✓ | ✓ |
| Item currency | ✓ | ✓ |

## 2. Auto-Selection Rules

> ⚠ **All `currency.*` settings are read only at server startup — restart the server after changes.**

### Fresh install (config generated on first launch)

Chosen automatically from the mods actually installed:

- Cobblemon Economy installed → Cobblemon Economy mode
- Otherwise CobbleDollars installed → CobbleDollars mode
- Neither → item currency (diamonds by default)

> Impactor is **not** auto-detected: it is often installed as a library by other mods, and auto-enabling would silently switch the currency backend. To use direct Impactor integration, explicitly set `currency.impactor = true` (and make sure cobblemonEconomy / cobbledollars are false).

### Upgrading from an older version

**No behavior change**: old configs missing the `cobblemonEconomy` key get `false` written in, keeping the previous currency. To switch to Cobblemon Economy, set it to `true` and restart.

## 3. Inside Cobblemon Economy

Cobblemon Economy itself has **two currencies**:

- **PokeDollars**: the base currency for everyday trading, shops, and quest rewards
- **PokeCoins (PCO)**: a premium/reward currency, granted via quests, commands, etc.

**CobbleMarket settles in PokeDollars by default; owners can set `currency.cobecoCurrency = "PCO"` to settle in PokeCoins instead** — the two currencies are separate ledgers inside Cobblemon Economy: with PCO, the market balance equals what players see via `/pco`, and PokeDollars balances are untouched by the market.

### main_currency: where PokeDollars live

In Cobblemon Economy's own config (under `world/config/cobblemon-economy/`), `main_currency` decides which backend actually stores PokeDollars balances:

| Value | Balance storage |
|---|---|
| `Cobblemon Economy` (default) | Cobblemon Economy's own SQLite database |
| `cobbledollars` | the CobbleDollars mod's player balances |
| `impactor` | the Impactor mod's primary currency accounts |

**Mirror sync** (verified on 0.0.17): whenever CobbleDollars is installed, Cobblemon Economy mirrors both balances in both directions after any change — `main_currency` decides which side is the primary ledger, and changes sync to the other automatically, so both sides always agree (1:1, no exchange rate). In other words, with CobbleDollars installed, the market and CobbleDollars merchants always share the same balance regardless of `main_currency` — no manual bridging needed.

> Note: the `cobbleDollarsToPokedollarsRate` / `impactorToPokedollarsRate` fields in Cobblemon Economy's config are reserved placeholders in the current version (0.0.17) and **have no effect**.

### main_currency and POKE/PCO are two different things (don't confuse them)

- `main_currency` only decides **where the PokeDollars ledger lives** (`Cobblemon Economy` / `cobbledollars` / `impactor`) — it is **not** a POKE/PCO selector
- **PCO never participates in bridging**: PCO is an independent internal Cobblemon Economy ledger. No matter what `main_currency` is set to, PCO balances stay inside Cobblemon Economy. When the market sets `cobecoCurrency = "PCO"`, it operates on the PCO ledger and has nothing to do with Impactor / CobbleDollars
- Cobblemon Economy's `shops.*.currency` (POKE/PCO) is the currency of **Cobblemon Economy's NPC shops** — it only affects shop display and is unrelated to the market; which currency the market uses is decided solely by `currency.cobecoCurrency` in `cobblemarket.json`

## 4. Scenario Quick Reference

| Installed mods | Cobblemon Economy's main_currency | What the market charges |
|---|---|---|
| Cobblemon Economy only | `Cobblemon Economy` (default) | Cobblemon Economy SQLite PokeDollars |
| CobbleDollars only | — | CobbleDollars balances |
| Both (fresh install) | `Cobblemon Economy` (default) | Cobblemon Economy SQLite PokeDollars; mirror sync keeps CobbleDollars merchant balances consistent |
| Both (fresh install) | `cobbledollars` | CobbleDollars balances as primary; mirror sync keeps both sides consistent |
| Both (upgraded server) | any | previous currency unchanged (no surprise) |
| Cobblemon Economy + Impactor | `impactor` | Impactor primary currency accounts (via Cobblemon Economy bridge) |
| Impactor only (no Cobblemon Economy) | — | Impactor primary currency accounts (direct integration, requires `currency.impactor=true`) |
| Neither | — | inventory items (diamonds by default) |

> Rows involving Cobblemon Economy apply to **Fabric only** (Cobblemon Economy is unavailable on NeoForge, those rows are skipped; NeoForge can use CobbleDollars / direct Impactor / items).

## 5. FAQ

### The market's money differs from the CobbleDollars merchants' money?

As long as CobbleDollars is installed, Cobblemon Economy's mirror sync keeps both balances consistent (two-way sync) — they cannot diverge. Only when CobbleDollars is **not** installed does the market balance live exclusively in Cobblemon Economy's SQLite.

### A player says their market balance suddenly became 0?

The owner switched currency backends (e.g. from item currency to Cobblemon Economy without the bridge). The market ledger (listings, pending returns, frozen funds) keeps its numbers, but future payouts/charges land in the new backend. Announce the switch to players beforehand to avoid "lost money" reports.

### Can the market settle in PCO (PokeCoins)?

Yes. Set `currency.cobecoCurrency = "PCO"` (either `PCO` or `PokeCoins`, case-insensitive; requires `cobblemonEconomy=true`) and the whole market settles in PokeCoins; players' `/pco` balance is the market balance. PCO and PokeDollars do not mix — announce the switch to players beforehand.

### Can the market use Impactor currency?

Yes. Configuration combination:

- Install Impactor + Cobblemon Economy
- Cobblemon Economy config: `main_currency = "impactor"`
- Market config: `cobblemonEconomy = true`, `cobecoCurrency = "POKE"` (**must be POKE** — PCO does not bridge to Impactor; with PCO the market operates on Cobblemon Economy's internal PokeCoins and has nothing to do with Impactor)
- Restart the server (currency settings are startup-level). Once the log shows `Currency: Cobblemon Economy (PokeDollars)`, market charges/credits go to Impactor's primary currency account

Verification: give a player money with an Impactor command → the market balance should show the same value → after buying/selling, both balances stay 1:1.

### Use Impactor directly without Cobblemon Economy? (direct integration, works on both loaders)

Yes. Configuration combination:

- Install Impactor (**without** Cobblemon Economy)
- Market config: `cobblemonEconomy = false`, `cobbledollars = false`, `impactor = true`
- Restart the server; once the log shows `Currency: Impactor`, market balances read/write Impactor's EconomyService directly

Priority note: with all three virtual switches true, Cobblemon Economy wins first (if installed), then CobbleDollars, Impactor last — to use direct Impactor integration the first two must be false.

### Both mods are installed, but I want the market to use CobbleDollars instead of Cobblemon Economy?

Set `currency.cobblemonEconomy = false` and `currency.cobbledollars = true` in your config.

## 6. Security & Trust Boundary

- The market **never creates money out of thin air**: every fund change is ledger flow (buyer payment, refund, seller income)
- Virtual currency is managed by upstream mods (Cobblemon Economy / CobbleDollars / Impactor); if an upstream mod has its own money exploit, the market cannot tell "illegally sourced" money apart — that is an upstream trust boundary, not a market defect
- Emergency response: set both virtual currency switches to `false` and fall back to item currency; the market keeps working

See also [docs/security/SECURITY_en.md](security/SECURITY_en.md), section 7 "Currency Dependency Boundary".
