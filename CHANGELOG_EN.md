# Changelog

## 1.1.0 (in development, unreleased)

### New Feature

#### Finance System (Meowth Bank)

- **Meowth Bank credit & loan system**: a new "Meowth Bank" entry on the market entry screen — credit loans repay in 3/6/12 installments (7 days each), the credit limit is computed automatically from each player's trading history, and loans pay out instantly; all loan money is accounted through a server-side reserve pool
- **Emergency loan (Meowth's Help)**: apply for a loan inside Meowth Bank — live credit limit and current debt, enter an amount, pick a plan (3/6/12 periods with configurable per-period fees), confirm and receive the money immediately
- **Repayment Counter**: a new repayment entry inside Meowth Bank — a list of outstanding loans (remaining principal / periods / overdue status at a glance) with "Pay 1 Period" or "Settle Early" (remaining principal + real-time interest in one payment)
- **Auto-deduct & overdue**: each due period is automatically deducted from the player's balance (a configurable minimum balance is kept so wallets are never emptied); insufficient funds mark the loan overdue — new borrowing is blocked with a red notice, overdue interest accrues daily, and catching up on payments restores normal status
- **Credit audit ledger**: every loan and repayment is written to standalone CSV ledgers (loan created/overdue/closed events plus per-repayment principal/interest splits, with auto/manual/early methods distinguished), in Chinese and English, split by day, for server owner auditing
- **Meowth Bank Config screen**: every finance parameter (master switch / cash loan switch / installment plans & fee rates / credit limit weights / auto-deduct minimum balance / same-IP debt cap / three overdue sanction thresholds) is edited in a dedicated Meowth Bank Config screen (entry button at the bottom of the Server Config screen)
- **Three-tier overdue sanctions**: unpaid loans escalate with overdue days — market fees double at 7 days, market trading freezes at 14 days (auto-unfreeze once repaid), and the loan is written off as bad debt at 30 days (the owner gets an alert; the player stays frozen until manually unbanned)
- **Meowth Pay (credit purchases)**: buying Pokémon/items now offers "Meowth Pay" — pick an installment plan (3/6/12 periods with per-period fees) and the server reserve pool pays the seller directly, while the buyer repays in installments; overdue or written-off players can't use it
- **Owner finance report & bad debt intervention**: the admin panel gains a reserve pool / total bad debt line (red alert when the pool is negative = owner debt); the all-loans ledger shows each borrower's latest IP (for spotting alts); bad debts can be revoked (a "Revoke" button in the all-loans screen or the `/market loan clear` command) — the player regains borrowing eligibility and is auto-unfrozen
- **Server-wide total volume**: the market entry screen now shows the server's all-time total trading volume right under the title (an economy-scale showcase)
- **Meowth Bank deposits (demand)**: Meowth Bank gains a "Deposit/Withdraw" entry — idle money earns daily interest (rate configurable, paid from the reserve pool) and can be withdrawn anytime (interest included); deposits fund the reserve pool for a full save-lend loop, and withdrawals stay available even when the master switch is off
- **Credit growth cooldown**: trades don't count toward the credit limit until a delay passes (default 24 hours, owner-configurable, 0 = off) — the limit grows on a delay, closing the "farm-then-borrow" window for organized quick cash-out groups; the trade-pair detection window and trade cap are also configurable (default 30 days / 3 trades)
- **Due-date reminder**: one day before each installment is due, the player gets a yellow reminder (with the principal amount), so nobody forgets a due date and slips into overdue by accident
- **Meowth Bank rules panel**: a new "Rules" button under the back button in Meowth Bank — hover it to see loan rules and consequences (installments / auto-deduct / three-tier sanctions / shared limit / interest), with key points in red
- **Bad-debt tab in the all-loans ledger**: the OP all-loans screen gains "All / Bad Debt" tabs to filter every bad debt record at once; repaid loan records are auto-purged after 90 days (the audit ledger keeps the full history forever)
- **Deposit-rate guard**: the deposit daily rate is now automatically constrained by the loan installment plans — setting it above the arbitrage-safe line clamps it back to the safe value and logs a warning, making "borrow, deposit, farm interest" impossible; lowering loan fee rates tightens the deposit rate accordingly; after saving, a yellow notice at the bottom of the config screen shows the clamped result (e.g. fee-free plans zero the rate), so the reset button never looks broken

#### Finance System · Card Credentials (Meow·Purple Gold Card / Meow·Black Gold Card)

- **Meow·Purple Gold Card**: a high-limit credential item — holders get a fixed borrowing limit set by the owner (default 1M), with a configurable server-wide card cap (default 20); the limit is bound to holder state, not the item (cards duplicated by item bugs are worthless); dropped cards vanish instantly and can be reissued at Meowth Bank (apply/reissue with a full inventory is refused with a clear-inventory hint, checked before any fee is taken); card holders are exempt from the per-IP debt cap (the anti-alt limit only applies to regular players); owners use `/market card` to give/revoke/list
- **Meow·Black Gold Card**: a credential one tier above the Purple Gold Card — limit defaults to 5M with a server cap of 5 (both configurable); applying requires already holding the Purple Gold Card (hard requirement), with the same six configurable conditions as the Purple Gold Card; obtaining the Black Gold Card automatically removes the Purple Gold Card qualification (an upgrade replacement, no double slot); the Black Gold Card's limit and fee discount apply; the Black Gold Card icon on the right side of Meowth Bank opens the application screen; owner commands gain a card-kind argument (`/market card give <player> black`, same for revoke/list, purple by default)
- **Card management screen (OP only)**: a new "Cards" button under the Rules button in Meowth Bank (with Purple Gold Card/Black Gold Card mini icons) — lists every Purple Gold Card/Black Gold Card holder (name + held-card icons) with a per-row "Revoke" button, same effect as the command
- **Holder display panels**: a small info panel under each card in Meowth Bank (visible to everyone) — the title shows "current holders / server cap" and the panel lists the holders (name + skin avatar, row dividers, scrollable); refreshed live on give/revoke/apply
- **Purple Gold Card self-application**: owners can let players apply themselves — clicking the Purple Gold Card on the left of Meowth Bank opens the application screen, listing all seven conditions (asset / spending / credit / deposit / Pokedex count / clean record / application fee) with live progress; apply once everything passes, and the fee goes to the reserve pool; enabling the switch takes a 5-second cooldown confirmation (prevents opening it before the conditions are configured); both cards' apply screens show the card's credit limit and fee discount perks
- **Purple Gold Card holder fee discount**: owners can configure a market fee discount for Purple Gold Card holders (covers listing / auction settlement / buy-order fees, stacks with overdue doubling, off by default) — holding the card makes trading cheaper
- **Purple Gold Card reissue fee**: reissuing a Purple Gold Card now costs a configurable fee (free by default), which goes to the reserve pool; the application screen shows the reissue fee for holders
- **Net-deposit card requirement**: the Purple Gold Card / Black Gold Card "deposit balance" requirement is now judged by net deposit (demand deposit − outstanding debt), so borrowed money can't inflate deposits to qualify; the application screen shows the net value

#### Others

- **Pokémon friendship display**: every Pokémon detail view (market hover / auction details / purchase confirm / buy-order delivery / admin lists / sell previews / pending returns) now shows friendship under the six IVs
- **Item search upgrade (follows the vanilla creative search semantics, and goes further)**: item search boxes (item market / admin list / blacklist / price limits / auctions / buy orders) now match item IDs, names, and full tooltip text; Cobblemon 1.8 technical machines (TMs) and enchanted books can be **searched down to the specific variant by move / enchantment name** (the vanilla creative search can't find TM moves — we filled in the missing move enumeration) — searching "snore" reaches the Snore TM directly in the item market / auctions, and yields a "TM · Snore" candidate in the blacklist / price-limit / buy-order add dialogs; enchanted books expand level by level ("Enchanted Book · Sharpness I" through "Sharpness V", entries matching that level and above — picking I bans every Sharpness book, picking the max level bans only the max level), so the resulting entry affects only that variant instead of every TM; future Cobblemon moves keep working automatically
- **Blacklist / price limits now go down to item variants**: the add dialogs gain an "Add from held item" button (a hand icon that lights up on selection, confirmed with the Add button — the dialog shows the held item's icon and name, and starting a search or picking an item exits the mode) — whatever you hold gets banned/limited, e.g. banning only Sharpness V enchanted books without touching other books; entries use containment matching ("Sharpness V" also matches "Sharpness V + Unbreaking III", so a junk enchantment can't bypass it); multiple price-limit rules apply the most specific entry first ("Sharpness V + Looting III" follows its own price range instead of being crushed by the "Sharpness V" rule); search filters down to the variant (searching "snore" only shows the Snore TM entry); batch-unban unblocks exactly what the search shows
- **Buy orders can require item components**: publishing an item buy order can select a hand icon to carry the held item's enchantments etc. as requirements (e.g. only accepting Sharpness V books); deliveries not satisfying the component requirements are rejected, and the order list rows show the requirement
- **Item hover tooltips overhaul**: every item list and icon hover now shows the real item tooltip — TM moves in the local language, enchanted book enchantment names with roman-numeral levels, all visible in list rows and hovers; hold Shift for the full tooltip and Ctrl for component details (matching the vanilla key habits, with instant refresh)
- **Pokémon mark display**: Pokémon detail panels (market hover / auction / auction bid dialog / force-cancel dialog / admin auction / admin Pokémon list / pending return / purchase confirm / buy-order review etc.) now show a mark section below friendship — all marks the Pokémon owns displayed between two divider lines (10 per row); marks are cosmetic and do not affect trading rules
- **Pokémon size badges**: every Pokémon list and detail view (market / auction / buy orders / admin / listing preview / pending return / sell select) now shows a size badge — XS/S/M/L/XL, or ALPHA for alpha Pokémon; in list rows it sits after the held-item icon; the auction chat announcement's hover shows the size as a letter after the gender
- **Custom balance HUD position**: the market entry settings gain a "Balance HUD position" row — click "Custom" to enter drag mode, hold left-click to drag the balance HUD anywhere on screen and release to place it; it snaps to screen edges and center lines and shows alignment guides; the position is stored proportionally, so changing resolution or GUI scale won't shift it; the "Show market balance HUD" label is now simply "Balance HUD"

### Changes

- Selected buttons and labels de-texted
- Item names in item list rows now use rarity colors (matching the inventory tooltip)
- Auction list rows (auction house and admin panel) now show abbreviated prices (e.g. 1.2k / 3.5M, consistent with the item market and buy orders; hover tooltips and bid dialogs still show full amounts with thousands separators), and the "From" prefix is dropped from the row; the seller avatar now sits at a fixed position (aligned across rows, matching the Pokémon Market) with the countdown right after it, so Pokémon names and size badges no longer get squeezed
- Pokémon names in auction list rows (auction house and admin panel) now display up to 5 characters in full, truncating with an ellipsis only beyond that
- The level position in the sell-selection list is moved right (matching the auction Pokémon picker), so long names with a full set of icons no longer cover it
- Pokémon Market list rows move the seller avatar and level right and show abbreviated prices inline (hover tooltips keep full amounts), so long names with a full set of icons no longer cover them
- Auction house and admin auction rows move the avatar and countdown right so size badges no longer overlap the avatar
- Admin Pokémon list and pending-return list move the level right (matching the Pokémon Market), and admin Pokémon rows show abbreviated prices inline
- Buy order rows show longer Pokémon and item names (up to 6 CJK characters in full) instead of being cut down to three
- Auction house, admin auction, Pokémon Market, admin Pokémon list and pending-return rows move the avatar, level and countdown further right, leaving a gap after the size badge; Pokémon names in the Pokémon Market, admin list and pending returns are truncated with an ellipsis past 6 CJK characters; the inline bid count in auction rows now sits flush against the price, with a tighter gap to the bid button
- The minimum-increment hint on the auction creation screen now shows the server's configured default amount (e.g. "blank = 100") instead of a bare "blank = default"
- Project license changed from MIT to GPL-3.0 (releases up to 1.0.1 remain under MIT)
- **Cobblemon Economy compatibility warning**: Cobblemon 1.8 renamed the Pokédex field `PokedexEntryProgress.CAUGHT` to `OWNED`, but Cobblemon Economy (up to 0.0.17) still references the old name — this crashes the server when a player **chooses a starter Pokémon**. Servers with this currency enabled now get a prominent warning at startup; switching to CobbleDollars / Impactor / item currency is recommended

### Fixes

- **Deposit interest lost the partial day**: interest was settled in whole days since the last deposit/withdrawal, so any partial day was discarded by the next action — depositing for 23h59m paid the same as one minute, and players who deposited daily never earned anything. Interest now accrues by actual deposit time, prorated for partial days; the meaning of the daily rate is unchanged
- **Abbreviated prices overflowing their cells**: abbreviations such as "200.0k" for 200,000 were wider than the grid/list cell when the integer part had several digits; values of 10 or more now drop the decimal ("200k"), matching the width of entries like "5.0M"
- **Meowth Bank background sat 7px higher than the market entry screen**: the background shifted up a little when entering Meowth Bank from the entry screen; one term in the anchor formula was wrong (160 instead of 146) and it now lines up exactly
- **Loan History: "Open Folder" is now an info tooltip**: on a server that button only opened the client's own folder, so it is replaced by a small icon in the top-left whose tooltip shows where the loan ledgers are stored; the All / Bad Debt tabs are now centred
- **Auction house "Mine" tab**: like the item tab, it has no filter row, so its list now starts right below the search box (it was laid out like the pokemon tab, leaving a blank row) and the divider follows suit; the "create auction" button is no longer shown on this tab
- Fixed an exploit with party Pokémon traded during battles: listing/auctioning/delivering is now blocked while in battle (previously taking a Pokémon out broke its in-battle model, and a listed Pokémon could still be switched in to fight); Pokémon bought or unlisted during a battle now go to Pending Returns instead of the party
- Fixed type names missing their type colors in the auction chat announcement's Pokémon hover details
- Fixed personal trade history being squeezed out by other players' trades: the screen now reads the last 14 days of CSV ledgers (previously only the 200 shared in-memory records), showing up to 500 entries per player
- Fixed the "Mine" button not refreshing after resetting filters in the Pokémon Market and admin Pokémon list (it could keep showing the "mine only" state after a reset)
- Fixed the buy-order delivery Pokémon icon being clipped: the idle-animation rework had overwritten its previously widened clip area back to the old values
- Fixed buy-order row icons showing the default form when the order requests a special form
- Fixed the false "market data save failed" red alert during automatic backup mods' backup runs: backup mods temporarily suspend server saving (savingDisabled), which silently skips the forced save-after-trade and tripped the mtime verification — the forced save is now deferred while saving is suspended and runs right after the backup ends, eliminating the false alarm
- Fixed missing thousands separators in price displays: the price in the admin Pokémon list hover, the admin item cancel dialog, the pending item return hover, the price-limit list and its hover, the buy-order publish freeze hint and the item market purchase total, plus every chat amount (auction broadcasts and bid warnings, card fee shortfalls, sale and refund notices, loans, price-limit warnings), now shows thousands separators
- Fixed the held-item line in the auction bid and admin auction detail dialogs missing its item icon and using a grey label (now consistent with every other screen: white label plus item icon)
- Fixed the coin sound and the failure sound playing together when bidding with insufficient funds: only the failure sound now plays, with a red on-dialog message (an invalid bid amount likewise plays only the failure sound)
- Fixed the auction chat announcement's hover details missing the colon after "Starting price" and "Min. increment", and showing the minimum increment as a plain number without gold color or currency unit
- Fixed prices missing their currency unit in the price-limit screen: range / min / max prices in list rows and hovers now show a unit (₽ for virtual currencies, the item name for item currencies)
- Fixed the search box placeholder in the Auction Hall's Item and Mine tabs not following the active tab: returning from the auction listing screen or resizing the window reverted it to "Species name..."; it now always shows the current tab's hint. Item search hints are now consistently "Search items..." across the Auction Hall, blacklist and price-limit screens
- Fixed long item names squeezing out the count in Auction Hall and admin auction rows: an over-long name truncated the "×N" suffix along with it, hiding how many are for sale — the count now always shows in full and the name truncates on its own
- **Item deduction now counts what was actually removed**: listing, auctioning or delivering to a buy order used to count the *intended* amount — if an external mod intercepted the deduction at the data layer, a listing could be recorded while the item stayed in the inventory. It now counts what was actually removed and rolls back otherwise (theoretical boundary, never observed in practice)

## 1.0.1 (released)

### Changes

- **Cobblemon 1.8 adaptation, 1.7 no longer supported**: this version and all future versions require Cobblemon 1.8+. Reason: 1.8's GUI Pokémon renderer (drawProfilePokemon) had a breaking signature change (boolean → ProfileTransformType + a new parameter); 1.7.x players should keep using 1.0.0

## 1.0.0 (released)

### New Feature

- New "Claim overflow" toggle in Settings (off by default): when enabled, claiming item returns drops anything that doesn't fit into your inventory onto the ground (they may despawn or be picked up by others — at your own risk); when off, the remainder stays in pending returns for next time
- **Admin "All Buy Orders" screen**: new entry in the admin panel to view every buy order and force-cancel them — the buyer's frozen money is refunded, pending deliveries return to their sellers, and both sides get notified (queued for offline players); admin panel buttons rearranged into a two-column layout
- **Cobblemon Economy currency support**: a new currency mode on Fabric (four in total now: Cobblemon Economy / CobbleDollars / Impactor / items) — servers with Cobblemon Economy installed use its currency API directly (its built-in bridge routes to CobbleDollars/Impactor backends; set main_currency to share one balance between the market and CobbleDollars merchants; Impactor can also be used standalone without Cobblemon Economy). Currency priority: Cobblemon Economy → CobbleDollars → Impactor → items; auto-detected on fresh installs, no behavior change on config upgrades; new `currency.cobblemonEconomy` switch plus optional `currency.cobecoCurrency` (POKE default / PCO) to settle in PokeDollars or PokeCoins; prices now use ₽ as the unified unit in PokeDollars/CobbleDollars modes (inline and dialogs alike)
- **Native NeoForge support**: a NeoForge build (cobblemarket-neoforge-1.0.0.jar) with feature parity and save compatibility with the Fabric build; requires Kotlin for Forge and Cobblemon (NeoForge), no Architectury API needed; Cobblemon Economy has no NeoForge build, so that platform falls back to CobbleDollars / Impactor / item currency (three in total)
- **Container content validation**: the item blacklist, price limits, and the egg-trading switch now apply to items inside containers too — listings, auctions, and buy order deliveries recursively inspect container contents (vanilla containers like shulker boxes; mod containers are not checked) so restricted items can't be smuggled past governance
- **Item variant selection for buy order delivery**: when your inventory has the same item in multiple component variants (e.g. shulker boxes with different contents), you can now pick which variant to deliver — the selection list shows icons and counts with full tooltips, and the delivery dialog has a change button; single-variant delivery is unchanged
- **Professor Oak & tip bubble**: a Professor Oak portrait now stands permanently at the market entry screen, with a speech bubble above his head showing random Pokémon trivia (498 built-in tips in Chinese and English, editable and replaceable); a new random tip is picked each time the entry screen opens, and clicking Oak switches to the next one
- **Config hot reload**: new `/market reload` command (OP) — fees, limits, durations, and toggles take effect immediately after editing the config file, no restart needed; changes to the market master switch are broadcast to everyone; currency settings still require a restart (reload notifies you if they were changed)
- **In-game server config editor**: a new "Server Config" button (OP only) sits left of the market master switch on the entry screen — fees, limits, durations, and toggles (15 settings) can now be edited in-game (number fields save when you click elsewhere, toggles apply instantly), no config file editing needed; currency settings and auction duration options still require editing the config file
- **Direct Impactor integration**: use Impactor currency without Cobblemon Economy — new `currency.impactor` switch (works on both loaders; NeoForge owners now have a direct virtual-currency path), the market reads/writes Impactor's EconomyService API directly; priority is Cobblemon Economy → CobbleDollars → Impactor → items; Impactor is not auto-detected on fresh installs (it is often installed as a library by other mods — auto-enabling would silently switch the currency), owners opt in explicitly; config comments now note cobblemonEconomy is Fabric-only
- **In-game balance HUD**: a market balance display in the top-left corner of the game (gold amount + dark rounded background frame, visible on every screen — players can see their remaining balance even inside bid/purchase dialogs); item currency mode counts the inventory locally in real time (dropping/picking up currency items updates instantly); virtual currencies refresh after trades plus a 30-second low-frequency fallback; three-state Settings toggle — always show (default) / show 5 seconds on balance change / off
- **Server-wide auction broadcasts**: creating or selling an auction now broadcasts to everyone in chat — hovering the lot name shows full details (Pokémon level/IVs/EVs/nature/ball, item enchantments), and clicking it jumps straight to that lot's bid dialog
- **Buy order review shortcut**: new-delivery notifications now carry a "Review" button that jumps straight to the review dialog; offline queued notices work the same way
- **Pokémon icon animation**: icons on every screen now play Cobblemon's built-in idle animation by default, with a new two-state setting to switch back to fully static
- **Enchantment details visible**: items in auctions and buy order deliveries now show their full enchantments and other tooltip lines — list hover, bid dialog, broadcast hover, and review dialog all match
- **Ledger covers auctions and buy orders**: auctions (listed/sold/unsold/force-cancelled) and buy orders (placed/filled/closed/expired/force-cancelled) are all written to the transaction history CSV, with a new enchantment summary in the Details column for exact recreation

### Changes

- The egg trading toggle moved from the admin panel to the new "Server Config" screen — entry button next to the market master switch on the entry screen (OP only); changes apply when you click Save, and enabling egg trading still shows the confirmation dialog (3-second cooldown); the admin panel's back button is now centered
- Bidding below the current price + min increment now shows a red hint under the button and plays a fail sound (previously silent, and the coin sound played by mistake)
- Quantity input limit raised from 3 to 4 digits: item sell count, item buy count, and auction item count now accept up to 9999
- New entries in the ban, blacklist, and price limit screens appear at the top (newest first) for easier management
- Auction house and buy order lists also show the newest first (live new listings insert at the top)
- Entry screen and admin panel backgrounds redrawn and displayed larger, several textures upgraded, no jump when switching between them; button layout unchanged
- Entry screen layout tweaks: title bolded and moved up, row spacing tightened, divider line and market-closed banner repositioned
- Admin panel title aligned with the entry screen (gold + bold), button layout improved, back button centered when Cobbreeding is not installed
- Pagination layout revamp (pokemon/item markets, admin lists): a symmetric divider line added below the list, prev/next buttons no longer cover the bottom border
- Fixed the last background slice covering the rounded corners of the bottom border in all 15 three-part screens
- Buy order and auction panel textures consolidated and upgraded: shared textures, duplicates removed
- Buy order entry button icon sharpened to match the market master switch
- Divider line added between the button row and the record list in the transaction history screen (both personal and all-history views)
- Transaction history CSVs gain a "Details" column: full Pokémon stats (level/shiny/IVs/hyper training/nature/ability/gender/ball/held item/form) and item NBT as text, so compensation can recreate items faithfully from the ledger
- Price units and currency names are now unified across all modes: virtual currencies (Cobblemon Economy POKE/PCO, CobbleDollars) show only ₽ everywhere — inline, dialogs, hovers, and chat messages no longer display names like PCo/PokeDollars/PokeCoins; item currency still shows the item name
- Added a ball-type text label to hover panels and confirmation dialogs (addon balls are recognizable at a glance)

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
- Custom Poké Balls from addon mods now show their icon and name correctly (previously blank due to hard-coded Cobblemon namespace)
- Pokémon/item market listings force-cancelled by an admin now notify the seller with a dedicated red message (consistent with auctions and buy orders)

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
- Gender filter is now a male/female icon button (♂♀ both = any, cycling to male-only / female-only); the listing selector (including delivery mode) gains the same button

### New Feature: Trading Experience Enhancements

- Nature mint compatibility: minted Pokémon show "italic base nature (effective nature Mint)", e.g. *Timid* (Bold Mint); unminted show normally; nature filters and buy-order matching use the effective nature
- Gender icons (♂ blue / ♀ red, baseline-aligned) added to the name line of every screen showing Pokémon details (market/auction/admin/returns/confirm dialogs)
- Pokémon acquisition celebration: **buying a Pokémon, winning an auction, or accepting a buy order delivery** plays a bouncing-ball animation of that Pokémon on the receiver's screen (synced with the gavel bell for auctions), visible over any screen; multiple Pokémon obtained in one batch play one after another; **two layers of switches**: server owners can disable it globally via the `celebrationAnimationEnabled` config (on by default), and players get per-scenario toggles under the **Settings** button at the bottom-right of the market entry screen — one for **market purchases** and one for **auctions / buy orders**
- The ban screen's player name input now suggests names from every player who ever logged into this save (including offline, from usercache) — type a prefix and pick, no more mistyped names
- **Market master switch**: server owners can shut down the whole market in an emergency (exploit, maintenance) via the bottom-center switch button on the entry screen (OP only, dual-state icon, same size as the buy-order/settings buttons) or in-game `/market off` (`/market on` to restore), config `marketEnabled` (on by default); **the button asks for confirmation before shutting down** (3-second cooldown with red/white warning lines, while restoring takes effect immediately with no dialog); while closed, all buy/sell/auction/buy-order operations are blocked with a "market closed" notice, and players' entry screens update in real time (state sent on join and broadcast on toggle) with a red banner; **players cannot enter the market screens while it is closed, so pending claims, balance collection, and cancelling listings must wait until the market reopens** (assets are never lost and everything is intact after reopening); **OPs keep management access** (admin panel stays available during closure for force-removal, blacklist and price-limit cleanup)

### Changes

- Config files self-update: new keys missing from old configs are filled in with defaults on load, so server owners no longer need to delete the config when upgrading
- Auction duration buttons on the create-auction screen now read the server's actual configuration: server owners can set any number of duration options with any values, and what players see always matches what actually settles
- Icon buttons at the bottom of the entry screen: **buy orders at the bottom-left** (opens the buy-order screen), **settings at the bottom-right** (opens the settings dialog, currently holding the two celebration animation toggles — market purchases and auctions / buy orders; future client-side personal settings all go here), and the **market master switch** bottom-center (OP only, dual-state icon); a divider line sits above the three small buttons, and rows 1/2 are tightened to give the bottom row breathing room
- Unified dialog look: the list below stays visible and dimmed under the dialog mask (Pokémon 3D icons dimmed in sync), with an opaque backing behind dialog backgrounds for a clean dialog area
- Price display polish: buy-order price ranges use k/M/B abbreviations above 10,000 (full value in hover panel); balances and pending balances use B once they reach 1 billion (full value below)
- Unified currency units: popups and hover panels now always show the currency name with prices (PokéDollars in CobbleDollars mode, the localized item name in item mode) in the same blue as the inline diamond symbol; inline rows keep the ◆ symbol to save space
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
- Hover panels and confirm dialogs now show a "Ball:" text line (custom balls are recognizable at a glance)

### Fixes

- Pending Claims screen showed raw translation keys instead of localized species names (also affected regular listing returns)
- Fixed English-mode text overflow: shortened the claims button label
- Fixed currency names following the server's language instead of the player's: UI and chat now use each player's own language
- Fixed a rare case where buying/cancelling could mis-deduct identical items from a player's armor or offhand: only the main inventory is touched now
- Expired listings are now taken down immediately (they used to linger for over ten seconds and could still be bought)
- Blacklist and price limit screens kept stale remove/edit buttons after searching (only cleared after clicking or scrolling): row buttons now rebuild immediately as the search text changes
- Searching by name in the item market and the admin "all Pokémon/items" screens only filtered the current page (targets on other pages couldn't be found without paging manually): search is now server-side global filtering, matching the Pokémon market — results appear on the first page immediately

## 1.0.0-beta.3 (released)

### New Feature

- **Price limits**: a new "Price Limits" entry on the admin panel, managed with Pokémon / item tabs — Pokémon rules cover four dimensions (species, blank = all Pokémon; IV count, any or exactly 0~6 perfect IVs; shiny filter, any / shiny only / non-shiny only; min / max price, either side optional); item rules are item + min / max price. When several rules match, the strictest intersection applies and over-limit prices are rejected at listing time (existing listings and purchases are unaffected); rules can be added, edited (adding the same combination overwrites it) and deleted; the data persists and is included in the save backup chain
- **Shiny filter for the Pokémon blacklist**: blacklist entries gain a shiny filter (any / shiny only / non-shiny only), enforced at both listing and purchase time; existing data is treated as "any"

### Changes

- Shiny markers unified across the mod: gold ★ = shiny, white ☆ = non-shiny / off. The filter buttons in the market, sell-select, admin listings, price limits and blacklist screens are now symbols only (labels removed)
- In-row shiny markers changed from white ☆ to gold ★ (market / admin / sell-select / returned-Pokémon rows, tooltips, confirmation dialog info lines, market icon badges)
- Row icons and the add-dialog preview model on the price limit and blacklist screens now render with shiny colours when the rule is "shiny only"
- Language files cleaned up: removed a duplicate shiny-button key and the unused `sell.shiny`

## 1.0.0-beta.2 (released)

### Fixes

- **IV filter input debounce**: typing "31" quickly could leave the list stuck on the results for IV 3; requests are now debounced and sent once typing stops
- **Ban messages follow the client language**: on an English server, Chinese players used to see English ban notices
- **Pagination button position**: the pagination buttons on all listed-item screens no longer press against the panel border
