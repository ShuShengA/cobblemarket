# Changelog

## 1.0.0 (in development, unreleased)

### New Feature

- New "Claim overflow" toggle in Settings (off by default): when enabled, claiming item returns drops anything that doesn't fit into your inventory onto the ground (they may despawn or be picked up by others — at your own risk); when off, the remainder stays in pending returns for next time
- **Admin "All Buy Orders" screen**: new entry in the admin panel to view every buy order and force-cancel them — the buyer's frozen money is refunded, pending deliveries return to their sellers, and both sides get notified (queued for offline players); admin panel buttons rearranged into a two-column layout
- **Market entry animation**: opening the market entry via hotkey K / smartphone app / other entry points plays a drop animation — the animation image falls from above the screen onto the entry position while scaling up, holds briefly, then fades out revealing the entry screen; new "Market Animation" toggle in Settings (on by default, per-player) that also controls the slide-out close animation when pressing E/Esc
- **Cobblemon Economy currency support**: a third currency mode — servers with Cobblemon Economy installed use its currency API directly (its built-in bridge routes to CobbleDollars/Impactor backends; set main_currency to share one balance between the market and CobbleDollars merchants). Currency priority: Cobblemon Economy → CobbleDollars → items; auto-detected on fresh installs, no behavior change on config upgrades; new `currency.cobblemonEconomy` switch plus optional `currency.cobecoCurrency` (POKE default / PCO) to settle in PokeDollars or PokeCoins; prices now use ₽ as the unified unit in PokeDollars/CobbleDollars modes (inline and dialogs alike); full server-owner currency guide in docs/currency_en.md
- **Native NeoForge support**: a NeoForge build (cobblemarket-neoforge-1.0.0.jar) with feature parity and save compatibility with the Fabric build; requires Kotlin for Forge and Cobblemon (NeoForge), no Architectury API needed; Cobblemon Economy has no NeoForge build, so that platform falls back to CobbleDollars / item currency
- **Container content validation**: the item blacklist, price limits, and the egg-trading switch now apply to items inside containers too — listings, auctions, and buy order deliveries recursively inspect container contents (shulker boxes etc.) so restricted items can't be smuggled past governance
- **Item variant selection for buy order delivery**: when your inventory has the same item in multiple component variants (e.g. shulker boxes with different contents), you can now pick which variant to deliver — the selection list shows icons and counts with full tooltips, and the delivery dialog has a change button; single-variant delivery is unchanged
- **Professor Oak & tip bubble**: a Professor Oak portrait now stands permanently at the market entry screen, with a speech bubble above his head showing random Pokémon trivia (498 built-in tips in Chinese and English, editable and replaceable); a new random tip is picked each time the entry screen opens, and clicking Oak switches to the next one
- **Config hot reload**: new `/market reload` command (OP) — fees, limits, durations, and toggles take effect immediately after editing the config file, no restart needed; changes to the market master switch are broadcast to everyone; currency settings still require a restart (reload notifies you if they were changed)
- **In-game server config editor**: a new "Server Config" button (OP only) sits left of the market master switch on the entry screen — fees, limits, durations, and toggles (15 settings) can now be edited in-game (number fields save when you click elsewhere, toggles apply instantly), no config file editing needed; currency settings and auction duration options still require editing the config file
- **Direct Impactor integration**: use Impactor currency without Cobblemon Economy — new `currency.impactor` switch (works on both loaders; NeoForge owners now have a direct virtual-currency path), the market reads/writes Impactor's EconomyService API directly; priority is Cobblemon Economy → CobbleDollars → Impactor → items; Impactor is not auto-detected on fresh installs (it is often installed as a library by other mods — auto-enabling would silently switch the currency), owners opt in explicitly; config comments now note cobblemonEconomy is Fabric-only
- **In-game balance HUD**: a market balance display in the top-left corner of the game (gold amount + dark rounded background frame, visible on every screen — players can see their remaining balance even inside bid/purchase dialogs); item currency mode counts the inventory locally in real time (dropping/picking up currency items updates instantly); virtual currencies refresh after trades plus a 30-second low-frequency fallback; three-state Settings toggle — always show (default) / show 5 seconds on balance change / off

### Changes

- The egg trading toggle moved from the admin panel to the new "Server Config" screen — entry button next to the market master switch on the entry screen (OP only); changes apply when you click Save, and enabling egg trading still shows the confirmation dialog (3-second cooldown); the admin panel's back button is now centered
- Bidding below the current price + min increment now shows a red hint under the button and plays a fail sound (previously silent, and the coin sound played by mistake)
- Quantity input limit raised from 3 to 4 digits: item sell count, item buy count, and auction item count now accept up to 9999
- New entries in the ban, blacklist, and price limit screens appear at the top (newest first) for easier management
- Auction house and buy order lists also show the newest first (live new listings insert at the top)
- Entry screen background redrawn at 256×213 and displayed larger (bottom aligned to the original bottom edge, shifted down 20px, expanding upward so the hotbar/health/armor HUD stays visible); button layout unchanged; admin panel background synced and aligned exactly with the entry screen (no jump when switching between them); several textures redrawn
- Entry screen layout: title bolded and moved up; rows 1–2 spacing restored to 8px (the OP-only row stays put, keeping symmetric 2px gaps to the divider line and the switch buttons); divider line ends clear the background border; market-closed banner moved down to avoid overlapping the title
- Admin panel: back button centered when Cobbreeding is not installed; title matches the entry screen position and style (gold + bold); buttons moved down
- Pagination layout revamp (pokemon market, all listed pokemon, item market, all listed items): a divider line symmetric to the top one added below the list; prev/next buttons now sit between the divider and the panel bottom edge, no longer covering the bottom border
- Panel background stitching fix: in all 15 screens with three-part backgrounds, the last middle slice is drawn clipped to the remaining height and no longer covers the rounded corners of the bottom slice
- Panel texture consolidation: buy order and auction screens now use the shared middle/bottom textures; three duplicated dedicated textures removed; buy order panel textures upgraded to 640×32
- Buy order entry button icon upgraded to a 48×48 high-res texture (displayed at 18×18, same sharpness as the market master switch)
- Divider line added between the button row and the record list in the transaction history screen (both personal and all-history views)
- Added docs/save-data-locations.md: where each feature's data lives in the world save
- Transaction history CSVs gain a "Details" column: full Pokémon stats (level/shiny/IVs/hyper training/nature/ability/gender/ball/held item/form) and item NBT as text, so compensation can recreate items faithfully from the ledger
- Price units and currency names are now unified across all modes: virtual currencies (Cobblemon Economy POKE/PCO, CobbleDollars) show only ₽ everywhere — inline, dialogs, hovers, and chat messages no longer display names like PCo/PokeDollars/PokeCoins (PCO now only routes the backend ledger); item currency still shows the item name

### Fixes

- Fixed item icons (balls/held items — drawItem render layer) and some Pokémon model icons (emissive layer) piercing through dialog masks — present in existing screens (market/auction/admin) since beta.1; item icons are now hidden while dialogs are open (render-layer limit), and Pokémon 3D icons are dimmed via color parameters
- Fixed new item blacklist entries still appearing at the bottom of the list (the send path was missing the reverse); re-adding the same entry in blacklist/ban/price limit now moves it to the top instead of keeping its old position
- Fixed buy order creation dialog validation messages being darkened and invisible under the dialog overlay (now rendered above the overlay)
- Fixed Int overflow in fee calculation (auction settlement, seller notification, and pokemon listing): price × feePercent could wrap around above ~214.7M, producing a negative or zero fee (fee evasion, phantom seller credit, or data corruption in the extreme case) — now computed in Long
- Fixed sustained FPS drops while market screens are open — they stay smooth no matter how many listings there are
- Fixed the garbled seller notification after a buy order delivery was accepted — the message template has 5 placeholders but only 4 args were passed, with the amount/currency order swapped (mixed-up amounts and leftover %s)
- Fixed the first row's 3D icon in the Pokémon picker always showing the first party Pokémon after searching (buy order delivery and auction creation — same root cause): the filtered-position index was used to look up the icon cache built with original list indices; filtering now keeps the original index
- Fixed a false "CobbleMarket state save failed" error when players log out: on NeoForge, persistent state writes are asynchronous, so verifying right after saving misreported failures; verification is now delayed, and saves are skipped entirely when there is nothing unsaved
- Fixed purchase success messages (Pokémon/items) showing amounts in green instead of the standard gold: the %d placeholders dropped the text color; they now use %s with gold-formatted amount text
- Fixed rapid page-turning in market screens permanently graying out the prev/next buttons and leaving stale content: paging now merges clicks into a target page — each click updates the page number immediately (instant feedback), requests queue behind the server-side throttle window (pokemon market 250ms, item market/pending claims 500ms), and rapid clicks only send one request for the final page; a 1-second response timeout also force-resets the in-flight flag, so a silently dropped request can no longer lock the paging buttons (pokemon/item markets and both pending claims screens; the two admin screens also got the timeout fallback)

## 1.0.0-beta.6 (released)

### New Feature: Buy Orders

- Players can publish buy orders: a Pokémon (always 1) or items (any count) with a unit price range, visible to everyone
- Pokémon orders support: species (blank = any Pokémon), shiny (3 states), hyper training (3 states), exact IV requirements for all 6 stats, form, ability, and nature
- Fund model: publishing freezes "max unit price × quantity"; fills settle at the actual price with the difference refunded; closing or expiry auto-refunds the remaining frozen money
- **Buyer confirmation**: deliveries first enter a "pending" state (goods held in escrow, no funds moved, persisted in NBT across restarts); the buyer reviews the full item details and accepts (settles the trade) or rejects (goods return to the seller, order stays open); rejection supports an optional reason shown to the seller: "Your delivery of X to Y was returned. Note: ..."
- New deliveries are locked while one is pending (prevents overselling); if the buyer never responds, goods return automatically when the order expires
- Multiple sellers can partially fill an item order (per-item settlement); the order closes automatically once fully filled
- Seller delivery: Pokémon via the sell-selection screen in delivery mode with live match/mismatch pre-check; items via direct count and price input; server re-validates (ban/blacklist/egg switch/price limits — Pokémon matched by the delivered Pokémon's form/V-count/shiny/HT dimensions, items by unit price) before submitting
- Buy orders support an optional buyer note (extra requirements); shown in a divider block in the hover panel and in the delivery dialog
- Delivered goods go to the buyer's pending returns and fills are recorded in transaction history; separate fee config `buyOrderFeePercent` (default 5%), order expiry `buyOrderExpiryDays` (default 3 days), and a per-player cap on concurrent orders `maxBuyOrdersPerPlayer` (default 5, 0=unlimited; Pokémon and items combined)
- The "My Orders" tab lets buyers close their orders anytime, refunding frozen money immediately
- Buy-order list search (species/item/buyer name, instant local filtering)

### New Feature: Offline Notifications

- Trade notifications while offline (listing sold, outbid, auction settled/unsold, buy-order delivered/accepted/rejected/expired, etc.) are queued and delivered on login, rendered in the player's language; up to 10 kept per player

### New Feature: Filter Rework for Market / Auction / Sell-Selection Screens

- Pokémon Market, Auction House, and sell-selection (incl. delivery mode): type filter changed from cycling to an expandable list (type names colored by their type color), new ability and nature expandable filters (abilities appear once a species is resolved from the search box; nature matches the effective nature, mints included)
- Gender filter is now a male/female icon button (♂♀ both = any, cycling to male-only / female-only); the listing selector (including delivery mode) gains the same button, with language-adaptive button width and a rearranged third row in delivery mode

### New Feature: Trading Experience Enhancements

- Nature mint compatibility: minted Pokémon show "italic base nature (effective nature Mint)", e.g. *Timid* (Bold Mint); unminted show normally; nature filters and buy-order matching use the effective nature
- Gender icons (♂ blue / ♀ red, baseline-aligned) added to the name line of every screen showing Pokémon details (market/auction/admin/returns/confirm dialogs)
- Pokémon acquisition celebration: **buying a Pokémon, winning an auction, or accepting a buy order delivery** plays a bouncing-ball animation of that Pokémon on the receiver's screen (synced with the gavel bell for auctions), visible over any screen; multiple Pokémon obtained in one batch play one after another; **two layers of switches**: server owners can disable it globally via the `celebrationAnimationEnabled` config (on by default), and players get per-scenario toggles under the **Settings** button at the bottom-right of the market entry screen — one for **market purchases** and one for **auctions / buy orders**. Expired returns, cancelled listings, admin force-removals and claiming from pending returns do not play — those Pokémon were already yours
- The ban screen's player name input now suggests names from every player who ever logged into this save (including offline, from usercache) — type a prefix and pick, no more mistyped names
- **Market master switch**: server owners can shut down the whole market in an emergency (exploit, maintenance) via the bottom-center switch button on the entry screen (OP only, dual-state icon, same size as the buy-order/settings buttons) or in-game `/market off` (`/market on` to restore), config `marketEnabled` (on by default); **the button asks for confirmation before shutting down** (3-second cooldown with red/white warning lines, while restoring takes effect immediately with no dialog); while closed, all buy/sell/auction/buy-order operations are blocked with a "market closed" notice, and players' entry screens update in real time (state sent on join and broadcast on toggle) with a red banner; **retrieving your own assets still works** (pending claims, balance collection, cancelling listings), so owners can stop the bleeding without locking players' property; **OPs keep management access** (admin panel stays available during closure for force-removal, blacklist and price-limit cleanup)

### Changes

- Config files self-update: new keys missing from old configs are filled in with defaults on load, so server owners no longer need to delete the config when upgrading
- Auction duration buttons on the create-auction screen now read the server's actual configuration: server owners can set any number of duration options with any values, and what players see always matches what actually settles
- Icon buttons at the bottom of the entry screen: **buy orders at the bottom-left** (opens the buy-order screen), **settings at the bottom-right** (opens the settings dialog, currently holding the two celebration animation toggles — market purchases and auctions / buy orders; future client-side personal settings all go here), and the **market master switch** bottom-center (OP only, dual-state icon); a divider line sits above the three small buttons, and rows 1/2 are tightened to give the bottom row breathing room
- Unified dialog look: the list below stays visible and dimmed under the dialog mask (Pokémon 3D icons dimmed in sync), with an opaque backing behind dialog backgrounds for a clean dialog area
- Price display polish: buy-order price ranges use k/M/B abbreviations above 10,000 (full value in hover panel); balances and pending balances use B once they reach 1 billion (full value below)
- Unified currency units: popups and hover panels now always show the currency name with prices (PokéDollars in CobbleDollars mode, the localized item name in item mode) in the same blue as the inline diamond symbol; inline rows keep the ◆ symbol to save space
- The black dot on selected tabs/lists is drawn without shadow (keeps it a perfect circle)
- All type-colored Pokémon names across screens now render with a shadow (dark type colors stay readable on gray row backgrounds)
- Pokémon Market page button spacing optimized to match the admin listing screen, showing one extra list row at some window heights

### Fixes

- Fixed trade data loss when the server shuts down abnormally (killed process / crash): listed Pokémon or items could vanish — market data is now force-saved within seconds after every trade and immediately when a player disconnects, no longer relying on the autosave cycle; online OPs get a red-text alert if a save ever fails

## 1.0.0-beta.5 (released)

### New Features: Cobblemon Utility+ Support (Hyper Training)

- Hyper-trained IVs now display correctly: market, auction hall, listing and pending-claim screens show "real value (trained value)", e.g. 12（31） — the same format as the party details screen
- New hyper-training filter (3-state cycle: Any / No HT / HT Only): available on the Pokémon market, the admin all-listings page, the auction hall Pokémon tab and both listing screens
- IV checks now match effective values: trained-to-31 and natural 31 are equivalent (search filters, price-limit V counts and blacklist IV matching all use effective values)
- Pokémon blacklist and price limit rules gain a "hyper training" dimension: a rule can be "No HT" (applies only to untrained Pokémon, preventing trained 6V Pokémon from bypassing rules based on real IVs); existing rules load as "Any" (unchanged behavior); both screens get a 2-state list filter and tooltip display

### Changes

- Merged the Pokémon and item blacklists into a single "Blacklist" screen: Pokémon/Items tabs (same layout as Price Limits); the two entry buttons in the admin panel are now one
- Pokémon blacklist rules can now be edited: a new "Edit" button per row opens a dialog pre-filled with all fields (species / IVs / form / shiny / hyper training), and saving replaces the rule
- Pokémon price limits now support forms: a rule can target all forms / the default form / specific forms, and both listing and auction starting-price checks match by form; rows and tooltips show the form
- Auction hall tabs reordered to Mine / Pokémon / Items, with the Rules button joining the tab row (centered as a group)

### Fixes

- The "Unban All" button stayed clickable while the item blacklist dialog was open, and its visibility did not refresh when blacklist data arrived
- Long Pokémon blacklist rows (e.g. all six IVs filled in) are now truncated with an ellipsis instead of overlapping the remove button (full details remain in the hover tooltip)
- Editing a price limit entry that changes the Pokémon (species / V count / shiny / form) or item now replaces the old entry instead of leaving it behind
- Pokémon holding a blacklisted item can no longer be listed on the market or auction house (previously bypassed the item blacklist); held-item price limits now merge into the total price: lower bounds add up, upper bounds add up only when both sides are set

## 1.0.0-beta.4 (released)

### New Features: Auction House

- **Auction hall**: Pokémon / Items / Mine tabs, real-time countdown (seconds shown in the last 3 minutes), seller avatars, full row info (ball / colored species name / shiny star / gender / held item)
- **Create auction**: Pokémon (search / IV / shiny / type filters, same as regular listing) + Items (inventory scan) dual tabs; starting price validated against price limits (items scaled by unit price × quantity); min increment can be blank (server default); duration options; max 3 concurrent auctions per player (Pokémon + items combined, configurable)
- **Bidding**: bids charged instantly; outbid amounts auto-returned to pending balance (yellow notice); raising your own bid only tops up the difference; cannot bid on your own auction; min increment enforced
- **Anti-snipe**: bids within the last 120 seconds (configurable) reset the end time; bids after the end are always rejected
- **Settlement**: settles automatically on expiry (no need to open the auction hall — the server checks every second); winner's item goes to Pending Claims, seller receives final price minus fee (configurable 0~100%); no bids = returned to the seller; finished auction records are cleaned up automatically; seller and winner get chat notifications
- **Auction sounds**: coin sound on bid confirm; three crescendo gavel knocks at 10s / 6s / 3s (with hammer icon animation in the row); final gavel + bell on settlement. Sounds are sent only to the seller and bidders — bystanders are not disturbed
- **Rules button**: hover tooltip in the auction hall with full rules (gold headers / white text / red highlights / dividers, bilingual)
- **OP force-cancel**: new "Auctions" page in the admin panel (search / full row info / tooltips / two-column confirm dialog matching the auction hall) — click any auction to force-cancel it (item returns to the seller's pending claims, the current bidder is fully refunded, removed across the server)
### Egg Trading (Cobbreeding Compatibility)

— Pokémon eggs can be listed on the market and auction house: they previously failed to list because the ever-changing hatch timer data (timer/second components) never matched between the listing and the inventory item — now supported; different eggs are strictly distinguished, no mix-ups

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
- Item market and admin "all listed items" page capacity raised from 30 to 84 items: bigger windows show more per page with less paging (smaller windows show fewer)

### Fixes

- Pending Claims screen showed raw translation keys instead of localized species names (also affected regular listing returns)
- Fixed English-mode text overflow: shortened the claims button label
- Fixed currency names following the server's language instead of the player's: UI and chat now use each player's own language
- Fixed a rare case where buying/cancelling could mis-deduct identical items from a player's armor or offhand: only the main inventory is touched now
- Expired listings are now taken down immediately (they used to linger for over ten seconds and could still be bought)
- Blacklist and price limit screens kept stale remove/edit buttons after searching (only cleared after clicking or scrolling): row buttons now rebuild immediately as the search text changes
- Searching by name in the item market and the admin "all Pokémon/items" screens only filtered the current page (targets on other pages couldn't be found without paging manually): search is now server-side global filtering, matching the Pokémon market — results appear on the first page immediately
