# Changelog

## 1.0.0-beta.4 (in development, unreleased)

### New Features: Auction House

- **Auction hall**: Pokémon / Items / Mine tabs, real-time countdown (seconds shown in the last 3 minutes), seller avatars, full row info (ball / colored species name / shiny star / gender / held item)
- **Create auction**: Pokémon (search / IV / shiny / type filters, same as regular listing) + Items (inventory scan) dual tabs; starting price validated against price limits (items scaled by unit price × quantity); min increment can be blank (server default); duration options; max 3 concurrent auctions per player (Pokémon + items combined, configurable)
- **Bidding**: bids charged instantly; outbid amounts auto-returned to pending balance (yellow notice); raising your own bid only tops up the difference; cannot bid on your own auction; min increment enforced
- **Anti-snipe**: bids within the last 120 seconds (configurable) reset the end time; bids after the end are always rejected
- **Settlement**: winner's item goes to Pending Claims, seller receives final price minus fee (configurable 0~100%); no bids = returned to the seller; finished auction records are cleaned up automatically; seller and winner get chat notifications
- **Auction sounds**: coin sound on bid confirm; three crescendo gavel knocks at 10s / 6s / 3s (with hammer icon animation in the row); final gavel + bell on settlement. Sounds are sent only to the seller and bidders — bystanders are not disturbed
- **Rules button**: hover tooltip in the auction hall with full rules (gold headers / white text / red highlights / dividers, bilingual)
- **OP force-cancel**: new "Auctions" page in the admin panel (search / full row info / tooltips / two-column confirm dialog matching the auction hall) — click any auction to force-cancel it (item returns to the seller's pending claims, the current bidder is fully refunded, removed across the server)
### Egg Trading (Cobbreeding Compatibility)

- Pokémon eggs can be listed on the market and auction house: the hatch timer doesn't interfere with trading, and different eggs are strictly distinguished — no mix-ups
- Eggs in listings never hatch, and buyers receive them with the same hatch progress shown at listing time
- The listing screen shows the live hatch time (consistent with the inventory screen) and removes hatched entries automatically
- Egg trading switch: off by default; toggle in the admin panel, enabling requires a second confirmation (3-second cooldown + red risk warnings: eggs bypass the Pokémon blacklist, and with encryption off they can be pre-filtered before hatching); listing, buying and bidding on eggs are all rejected while disabled (takes effect immediately, including existing listings)
- Blacklist integration: the blacklist takes priority over the switch (fine-grained per-variant bans), with batch ban/unban support; blacklisted eggs in existing listings can no longer be traded

### Changes

- Unified price display across all screens: `amount ◆` in currency blue (rows / tooltips / dialogs / history / pending claims)
- Global balance display: entry / market / item market / auction hall / create auction screens show live balance (auto-refreshed after trades); pending balance stays green
- "Expired Returns" renamed to "Pending Claims"; row info and tooltips aligned with the Pokémon market (ball / gender / held item / type color)
- Item market now shows remaining stock when a purchase exceeds available quantity (concurrent buying)
- Auction sales recorded in transaction history (in-game history + local Chinese/English CSV), species names properly localized
- Adjusted row / tooltip hover and selected state colors (row_background.png texture)
- Unified "Pokemon" to the official "Pokémon" spelling in English texts (UI and config comments)
- Added icons to entry panel buttons (Pokémon Market / Item Market / History / OP Only), matching the Auction House button style
- Admin panel: added the "Auctions" entry
- Item blacklist supports batch ban (one-click add all search matches, e.g. every egg variant)
- Config comments improved: max auction limit notes "Pokémon + items combined" and performance advice for crowded servers
- Market price input limit relaxed to 9 digits (consistent with auction and price limit fields)

### Fixes

- Pending Claims screen showed raw translation keys instead of localized species names (also affected regular listing returns)
- Fixed English-mode text overflow: shortened the claims button label
- Fixed currency names following the server's language instead of the player's: UI and chat now use each player's own language
- Fixed a rare case where buying/cancelling could mis-deduct identical items from a player's armor or offhand: only the main inventory is touched now
- Expired listings are now taken down immediately (they used to linger for over ten seconds and could still be bought)
