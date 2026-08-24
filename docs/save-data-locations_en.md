# CobbleMarket Save Data Locations

All data is persisted via Minecraft's PersistentState mechanism in the server world's **`data/` directory**, one `.dat` file per feature.

- Singleplayer: `.minecraft/saves/<world name>/data/`
- Dedicated server: `<server directory>/world/data/`

## Data File List

| Feature | File (under `<world save>/data/`) |
|---|---|
| Pokémon market (listings + pending returns + pending balance) | `cobblemarket.dat` |
| Item market | `cobblemarket_items.dat` |
| Buy orders | `cobblemarket_buy_orders.dat` |
| Auctions | `cobblemarket_auctions.dat` |
| Pokémon blacklist | `cobblemarket_pokemon_blacklist.dat` |
| Item blacklist | `cobblemarket_item_blacklist.dat` |
| Pokémon price limits | `cobblemarket_pokemon_price_limit.dat` |
| Item price limits | `cobblemarket_item_price_limit.dat` |
| Bans | `cobblemarket_bans.dat` |
| Offline messages | `cobblemarket_offline_messages.dat` |

## Notes

- **Pokémon/items are embedded directly in the .dat files**: when listed, Pokémon/items are serialized from the player's inventory into NBT stored in the listing record (e.g. the `pokemon` field of `MarketListing.toNbt()`), not as references into Cobblemon PC storage.
- When backing up a save, include the `data/` directory alongside it.

## Backup Notes (Important)

- **Backup plugins/scripts must include the `data/` directory**: backing up only world region files while omitting `data/` creates a mismatch after rollback — player data is old (items already returned to inventories) while market data is new (listings still exist), causing item duplication.
- Each `.dat` file has a same-named `.bak` backup (maintained automatically by the mod): if a state file is corrupted, startup recovers it from `.bak`; `.bak` is refreshed on every trade save and on a clean shutdown.
- The mod writes trade data to disk shortly after every trade (~3 seconds). Even so, **server owners should still shut down with `/stop`** — killing the process loses vanilla data changes (inventories, blocks, etc.) within the autosave window; this is vanilla Minecraft behavior the mod cannot change.
- If a trade-data save fails, the server log prints `CobbleMarket state save failed` and online OPs receive a red warning — when this appears, **run `/stop` for a clean shutdown first** (the shutdown save writes again and refreshes backups, salvaging as much data as possible), then check disk space and file permissions.

## Trade History CSV and Compensation Reconciliation

Since beta.4 the mod writes every trade **synchronously** to a CSV ledger (independent of autosave, survives crashes/process kills) at `config/cobblemarket/history/history_<date>_<lang>.csv`, with fields: time (second precision), type (listed/sold/removed/returned), category, seller, buyer, Pokémon/item, price, fee.

If a player reports "my listed Pokémon/item disappeared", server owners can reconcile in three steps to confirm whether it was lost and compensate:

1. Filter today's CSV by player name and find the "listed" record for the item (note the price)
2. Confirm there is no subsequent "sold/removed/returned" record for it
3. Confirm it is not currently in the market listings

All three hold → the data was indeed lost due to an abnormal server shutdown → compensate the player at the price recorded in the CSV.

Reconciliation limits: the CSV only records display names and prices — item enchantments/NBT details and Pokémon IVs/natures/shininess cannot be restored, only a fresh item of the same kind can be given; when reconciling Pokémon by display name, different forms may share the same name, so reconciliation only reaches "species" granularity.
