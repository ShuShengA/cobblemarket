# Changelog

## 1.0.0-beta.4 (in development, unreleased)

### New Features: Auction House

- **Auction hall**: Pokémon / Items / Mine tabs, real-time countdown (seconds shown in the last 3 minutes), seller avatars, full row info (ball / colored species name / shiny star / gender / held item)
- **Create auction**: Pokémon (search / IV / shiny / type filters) + Items (inventory scan) dual tabs; starting price validated against price limits (items scaled by unit price × quantity); min increment can be blank (server default); duration options; max 3 concurrent auctions per player (Pokémon + items combined, configurable)
- **Bidding**: bids charged instantly; outbid amounts auto-returned to pending balance (yellow notice); raising your own bid only costs the difference; cannot bid on your own auction; min increment enforced
- **Anti-snipe**: bids within the last 120 seconds (configurable) reset the end time; bids after the end are always rejected
- **Settlement**: winner's item goes to Pending Claims, seller receives final price minus fee (configurable 0~100%); no bids = returned to the seller; auction records removed immediately after settlement (prevents save bloat); seller and winner get chat notifications
- **Auction sounds**: coin sound on bid confirm; three crescendo gavel knocks at 10s / 6s / 3s (with hammer icon animation in the row); final gavel + bell on settlement. Sounds are sent only to the seller and bidders — bystanders are not disturbed
- **Rules button**: hover tooltip in the auction hall with full rules (gold headers / white text / red highlights / dividers, bilingual)
- **OP force-cancel**: new "Auctions" page in the admin panel (search / full row info / tooltips / two-column confirm dialog matching the auction hall) — click any auction to force-cancel it (item returns to the seller's pending claims, the current bidder is fully refunded, removed across the server)

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
- Config comments improved: max auction limit notes "Pokémon + items combined" and performance advice for crowded servers

### Fixes

- Pending Claims screen showed raw translation keys instead of localized species names (also affected regular listing returns)
- Fixed English-mode text overflow: icon buttons now center their label in the space right of the icon, shortened the claims button label and auction dialog placeholders
- Fixed item currency failing for mod items (mod load order cached the currency as air): the currency item is now resolved dynamically on every use
- Item purchase/cancel rollback now scans only the main inventory (matching insertStack's range), preventing rare mis-deduction of armor/offhand items
- Removed the 15-second listing expiry throttle: expired listings are taken down immediately (fixes expired listings still being purchasable within the throttle window)
