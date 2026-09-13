# CobbleMarket Save Data Locations

All data is persisted via Minecraft's PersistentState mechanism in the server world's **`data/` directory**, one `.dat` file per feature.

- Singleplayer: `.minecraft/saves/<world name>/data/`
- Dedicated server: `<server directory>/world/data/`

## Data File List

| Feature | File (under `<world save>/data/`) |
|---|---|
| Pokémon market (listings + pending returns + pending balance) | `cobblemarket.dat` |
| Item Market | `cobblemarket_items.dat` |
| Buy Orders | `cobblemarket_buy_orders.dat` |
| Auctions | `cobblemarket_auctions.dat` |
| Pokémon blacklist | `cobblemarket_pokemon_blacklist.dat` |
| Item blacklist | `cobblemarket_item_blacklist.dat` |
| Pokémon price limits | `cobblemarket_pokemon_price_limit.dat` |
| Item price limits | `cobblemarket_item_price_limit.dat` |
| Bans | `cobblemarket_bans.dat` |
| Offline messages | `cobblemarket_offline_messages.dat` |
| Finance system (loan state machine / reserve pool / total volume / IP records) | `cobblemarket_finance.dat` |

## Notes

- **Pokémon/items are embedded directly in the .dat files**: when listed, Pokémon/items are serialized from the player's inventory into NBT stored in the listing record (e.g. the `pokemon` field of `MarketListing.toNbt()`), not as references into Cobblemon PC storage.
- When backing up a save, include the `data/` directory alongside it.

## Backup Notes (Important)

- **Backup plugins/scripts must include the `data/` directory**: backing up only world region files while omitting `data/` creates a mismatch after rollback — player data is old (items already returned to inventories) while market data is new (listings still exist), causing item duplication.
- Each `.dat` file has a same-named `.bak` backup (maintained automatically by the mod): if a state file is corrupted, startup recovers it from `.bak`; `.bak` is refreshed on every trade save and on a clean shutdown.
- The mod writes trade data to disk shortly after every trade (~3 seconds). Even so, **server owners should still shut down with `/stop`** — killing the process loses vanilla data changes (inventories, blocks, etc.) within the autosave window; this is vanilla Minecraft behavior the mod cannot change.
- If a trade-data save fails, the server log prints `CobbleMarket state save failed` and online OPs receive a red warning — when this appears, **run `/stop` for a clean shutdown first** (the shutdown save writes again and refreshes backups, salvaging as much data as possible), then check disk space and file permissions.

## Trade History CSV and Compensation Reconciliation

Since beta.4 the mod writes every trade **synchronously** to a CSV ledger (independent of autosave, survives crashes/process kills) at `config/cobblemarket/history/history_<date>_<lang>.csv`, with fields: time (second precision), type, category, seller, buyer, Pokémon/item, price, fee, details.

Types (market/auction/buy-order full chain): listed (market/auction listing), buy order (buy order posted, the poster occupies the seller column), sold (market/auction sale, buy order delivery), removed (manual close/expired/unsold/admin force; the `reason=` column distinguishes: user / expired / admin / unsold), returned (pending return claimed).

Details column: full Pokémon stats (level/shiny/IVs/EVs/hyper training/nature/ability/gender/ball/held item/form) and item components (count/enchantment summary/full NBT) — goods can be recreated faithfully.

If a player reports "my listed Pokémon/item disappeared", server owners can reconcile in three steps to confirm whether it was lost and compensate:

1. Filter today's CSV by player name and find the "listed/buy order" record for the item (note the price)
2. Confirm there is no subsequent "sold/removed/returned" record for it
3. Confirm it is not currently in the market/auction/buy-order listings

All three hold → the data was indeed lost due to an abnormal server shutdown → compensate the player at the price recorded in the CSV, and recreate the goods faithfully from the details column.

## Finance System (Meowth Bank) Credit Ledger CSVs

Finance loan/repayment events are **synchronously appended** to a separate credit ledger (independent of autosave, survives crashes/process kills, append-only) at `config/cobblemarket/credit/` — a **separate directory, never mixed into the trade ledger**.

- `loan_records_<date>_<lang>.csv` (loan events): Time, Type, Source, Player, Principal, Periods, Daily Rate, Status, Details. Types: Created (Counter=cash loan/Jiebei; Pokémon purchase/Item purchase=consumer loan/Meowth Pay), Revoked (bad debt revoked by admin), Closed, Overdue, Bad debt.
- `repayment_records_<date>_<lang>.csv` (repayment events): Time, Player, Loan ID, Principal Part, Interest, Method, Details. Methods: Manual / Auto / Early payoff.

Split of duties: the trade ledger answers "where did the goods go", the credit ledger answers "where did the money go". For loan disputes: find the Created record in loan_records by player name (note the loan ID), then check every repayment for that loan ID in repayment_records. Full field descriptions are in the directory's README.
