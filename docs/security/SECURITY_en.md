# CobbleMarket Security Design Document

> This document records the mod's economic security defenses and threat model. Attack surface defined as: **players illegally obtaining in-game assets via client cheats, packet tampering/replay, or business-logic exploits**.

---

## 1. Core Architecture Principles

### 1.1 Server Authority
All trading logic runs server-side. The client only displays data and sends operation requests; **no client data is used as an asset source of truth**:

- Currency balance: server-side storage only (Cobblemon Economy / CobbleDollars server player data / inventory currency items)
- The client only caches the balance for display (BalanceCache); modifying client memory does not affect the server ledger
- All fund/item changes are validated and persisted server-side

### 1.2 Main-Thread Serialization
All trading requests enter the server main-thread queue via `server.execute` and execute **one at a time** — no concurrency windows:

- Two players buy the same item simultaneously → first wins, second is rejected against the latest state
- The same player replays the same request → the second execution sees the state already updated by the first and gets nothing

### 1.3 Atomic State Deduction
"Validate → charge → deduct stock/deliver → clear ledger" completes **within a single handler block** — no intermediate state to exploit:

- Pending balance claim: after the first execution the ledger is zero; replay is rejected immediately
- Pending return claim: the listing record is deleted right after delivery; replay finds nothing to claim

---

## 2. Anti-Forgery

| Data | Defense |
|---|---|
| Listing a Pokémon | Client sends only the Pokémon UUID; the server looks up the **real Pokémon** from the Cobblemon storage (party/PC). IVs / shiny / forms are never trusted from the client |
| Listing items | Server rebuilds items with `fromNbtOrEmpty` and deducts from the **server-side real inventory**; the authoritative item ID comes from the rebuilt stack |
| Purchase price | Uses the **server-stored listing price**; the client cannot alter it |
| Purchase count | Server validates `0 < count ≤ remaining stock` |
| Bid amount | Server validates: > current price, increment ≥ min increment, and charges instantly |
| Negative/zero prices | Starting price and listing price must be > 0 server-side |
| Fake Pokémon selling | The Pokémon is removed from storage the moment it is listed; re-listing or re-selling the same UUID always fails |

---

## 3. Fund Safety

### 3.1 Validate Before Charging
All fallible checks (blacklist, price limits, quantity, stock, balance) run **before** any charge — no "charged then failed" paths.

### 3.2 Refund Fallback Chain
- Outbid: previous bidder's amount instantly returned to pending balance (persisted)
- Self re-bid: only the difference is charged (no pending-balance round trip)
- Purchase with full inventory: full refund; any remainder that cannot fit goes to pending balance
- Settlement enqueue failure: auction restored to ACTIVE for retry — no asset destruction, no duplicate transfers
- Defensive item deduction path: already-deducted items are given back on anomaly

### 3.3 Ledger Consistency
- Pending balance: persisted server ledger; claiming does "pay out → clear exactly the paid amount"
- Auction invariant: `currentPrice` always equals the leader's cumulative net input

### 3.4 Numeric Safety
- Price × quantity computed in Long to prevent Int overflow
- Fee percentage clamped to 0~100% (above 100% would drive seller balance negative)
- Auction duration options clamped to ≥1 minute (0/negative caused instant expiry)

---

## 4. Business Rule Defenses

- **Price limits**: listing/starting price validated (Pokémon by species/V-count/shiny; items scaled by unit price × quantity — blocks the "dilute unit price with quantity" exploit)
- **Blacklist**: checked at listing time, enforcement takes effect immediately (including blocking purchases of existing listings)
- **Ban**: blocks all trading operations (claiming assets is allowed by design)
- **Rate limiting**: per-player per-operation 250ms throttle against scripted request floods
- **Auction deadline**: bids validate `endsAt` directly; any bid after the end is rejected (blocks the anti-snipe revival exploit)
- **Anti-snipe**: bids within the final window reset the end time to a fixed value (no infinite accumulation)
- **Concurrency cap**: max active auctions per player (Pokémon + items combined), which also caps total server-wide listings

---

## 5. Persistence & Recovery

- All asset states (auctions, listings, pending balance, pending returns, transaction history) are persisted immediately via `markDirty`
- Server restart: ongoing auctions resume correctly from absolute timestamps; charged bids are never lost
- Settled auctions are removed immediately after asset transfer (prevents save bloat)
- Corrupted saves: `StateBackup` verification + .bak recovery
- Local CSV transaction logs (Chinese + English) serve as an extra audit trail

---

## 6. Threat Model Boundary

**Defended against: players** (client cheats, packet tampering/replay, business-logic exploits) — fully covered by the mechanisms above.

**Not defended against: people with server file/admin access** (the server owner, authorized admins). Reasons:

1. Physical access = game over: whoever can edit save files can also extract signing keys, replace the mod jar, or patch the verification logic — any anti-tamper signature is pointless
2. OPs can already spawn items in-game; defending against their file edits is meaningless
3. This is an operational trust problem (who can touch server files), not a mod-level technical problem

**Conclusion**: the mod's security boundary is "defend against players." Admin-level trust should be handled by the server owner via file permissions and staffing.

---

## 7. Currency Dependency Boundary

- **The market never creates money out of thin air**: every fund change is ledger flow (buyer payment, refund, seller income). No "free money" path exists — money exploits can never originate from the market code
- **Item currency mode** (`cobbledollars=false` and `cobblemonEconomy=false`): currency is inventory items, fully managed by the server-authoritative vanilla inventory — zero external dependency risk
- **Cobblemon Economy mode** (`cobblemonEconomy=true`): currency is managed by the Cobblemon Economy mod, whose API can bridge to CobbleDollars/Impactor backends via its main_currency config. If that mod (or its bridged backend) has its own money exploit, the market cannot distinguish "illegally sourced" money — this is an upstream dependency boundary, not a market defect
- **PCO settlement** (`cobecoCurrency=PCO`): the same trust boundary applies when the market settles in PokeCoins — the PCO ledger is also managed by cobeco, so the market cannot tell illegally sourced PCO apart
- **Mirror sync widens the trust boundary**: with CobbleDollars installed, cobeco mirrors both balances two-way (verified on 0.0.17) — an exploit in either upstream spreads to the other side through the mirror; matching balances mean sync, not security
- **CobbleDollars mode**: currency is managed by the CobbleDollars mod; same trust boundary as above
- **Mitigation**: server owners can turn off the relevant switches (`currency.cobblemonEconomy = false`, `currency.cobbledollars = false`) and fall back to item currency mode at any time; the market is unaffected

---

## 8. Historical Exploits (Fixed)

| Exploit | Type | Fix |
|---|---|---|
| Item auction quantity diluting unit price past price limits | Business logic | Price limits scaled by unit price × quantity |
| Expired auction revived indefinitely via anti-snipe | Timing logic | Bids validate the deadline directly, independent of settle throttle |
| 15-second settle throttle delaying staggered auctions | UX/timing | Throttle removed |
| Asset destruction when settlement enqueue failed | Error path | Restore ACTIVE and retry; no transfer, no destruction |
| Fee >100% driving seller balance negative | Config boundary | Clamped to 0~100% at load |
| Item auction listing not syncing client inventory | Display sync | Added markDirty + sendContentUpdates |
| Packet replay (theoretical) | Replay attack | Architecturally immune: main-thread serialization + atomic state deduction |
