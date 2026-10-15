# Changelog

## 1.2.2 (in development, unreleased)

### New Feature

- **Finance Management screen**: the entry button in Meowth Bank now opens **Finance Management** — a screen with **four tabs** (that button used to open the card management screen directly). The tabs are:
  - **Loan Ban**: type a player name (with autocomplete) plus a duration and a reason to add them to the list — listed players **cannot take loans** (⚠ deposits, withdrawals and **repayments still work**: repayments must keep working, since paying back is how a player clears their overdue status). Players are also removed from the list on this tab
  - **Cards**: the list of purple / black card holders, with a revoke button on each row (everything the old card management screen did)
  - **Credits**: look up a player and set their **borrowable limit** to whatever you want (see the next entry)
  - **Change Log**: a ledger of all of the above **plus self-service card applications** (who, when, to whom, what, and the previous value), written to its **own CSV** under `config/cobblemarket/finance/` — kept separate from the trading / loan / ban / permission ledgers, so each can be read on its own. ⚠ This ledger **starts recording with this version**; earlier card grants / revokes have no history
  - **Top Limits**: the **top 50 borrowable limits** server-wide (pulled automatically when you open the tab, or on demand via the Refresh button) — ⭐ for **discovering** players with suspiciously high limits and seeing how far ahead the #1 is (previously you could only look up names you already knew, so **players you had not thought of stayed invisible**). Each row has the same "set limit" button, so a suspicious row **can be adjusted right there**

- **The borrowable limit can now be adjusted by hand**: the limit used to be **computed from a formula only, and it never went down** (not even on overdue loans), so a server owner facing a player with an illegitimately high limit had **only a ban as a blunt instrument**. Finance Management can now lower a player's limit, or zero it.
  ⚠ **It does not pin that number down** — what gets stored is a correction applied **on top of** the formula, so afterwards **repayments and a growing trade volume still move it**. That is exactly why a zeroed limit can grow back: stopping it from growing would be the same thing as a ban.
  ⚠ **Card holders cannot be adjusted** (their limit comes from the card config and does not use the formula) — the button is greyed out for them.
  ⚠ The affected player **receives a notice** (including the reason you typed) — otherwise they would only notice "why can't I borrow any more" and come asking you

- **Pokémon nicknames are now shown**: a player-given nickname now appears in **hover windows and dialogs** — on the line **right below the Pokémon's name** (e.g. "Nickname: Sparky"). That covers the market, auctions, buy orders, the listing picker, pending returns, the admin screens, and the chat broadcast hover. Nicknames also show in the **broadcast text itself**, right after the species name (e.g. "Charizard (Sparky)"). ⚠ **Not shown in list rows** (there is no room there); **Pokémon without a nickname get no extra line**, so those screens look exactly as before

- **Pokémon icons now blink and do idle quirks**: the 3D Pokémon shown in interfaces now **blink at random intervals**, and some species occasionally **play a little idle animation** (e.g. Timburr turning its log, Lucario shifting about). ⚠ The interval is **randomized over 8~30 seconds** and a blink lasts only **~0.17 s**, so it can take a full **30 seconds** of watching one Pokémon to be sure of catching one (they do not all blink at once). ⚠ What plays is **decided by Cobblemon's own animation data**: no idle quirks while walking and barely anything while asleep; it is disabled when the icon is set to "fully static", and it cannot be seen during the purchase / sale **celebration animation** (that animation only lives 4 seconds, while a blink needs at least 8)

- **A notice now pops up when someone else lists something**: when another player lists a Pokémon or an item on either market, or puts one up for auction, a **round bubble drops down from the top of your screen** (showing that Pokémon's 3D model / that item's icon) along with a short **chime**. ⚠ **Your own listings produce no notice** (you just did it yourself), and **buy orders don't count** (they aren't a physical item). Up to **3 show at once** — when a 4th arrives the leftmost one slides away; each stays for 3 seconds and then fades on its own.
  Settings gained a new **Notices** section with two switches: **① Market / auction notice icon** (off = neither the bubble nor the sound) and **② Notice sound** (mutes the sound only; the bubble still shows).
  ⚠ This switch covers **the bubble at the top of the screen only** — the **chat broadcast** the auction house already had ("so-and-so listed such-and-such") is **not affected by it**.
  ⚠ This feature **adds a network packet ⇒ both client and server must be updated** (updating only one side will fail to connect)

### Changes

- **"Also block loans" moved to the Finance Management screen**: it used to be a toggle in the **ban** confirmation dialog (ticked while banning someone); it is now a **separate list** on the Finance Management screen's "Loan Ban" tab — finance belongs on the finance side, and bans only cover trading. ⚠ The ban ledger (`config/cobblemarket/bans/`) drops its "Block Loans / Prev Block Loans" columns accordingly

## 1.2.1 (released)

⚠ **Server and client must be updated together** (this release changes the packet format).

### New Feature

- **The transaction ledger now records full Pokemon details**: the CSVs under `config/cobblemarket/history/` now also record the nickname, size, Gigantamax and Dynamax Level, Original Trainer, Tera type, moves, friendship and the held item's full data (previously only an item id). ⚠ Rows already written to the CSV before this update are not rewritten

- **Tera type shown in Pokemon info**: the type line now lists the Pokemon's Tera type (e.g. "(Tera: Electric)") — in the market, auctions, buy orders, the listing picker, pending returns and the chat hover popups; listings already up before this update show it too

- **Gigantamax / Dynamax Level shown in Pokemon info**: a new line under the type line (e.g. "Gigantamax (Dynamax Level: 5)"), in the same places as above. ⚠ When both are at their defaults the line does not appear at all

- **Original Trainer shown in Pokemon info**: a new line under the friendship line reads "Original Trainer: XX"; it still shows for Pokemon that changed hands, in the same places as above. ⚠ When there is no OT, or the name cannot be resolved, the line is not shown

- **The filter panel can now filter by Tera type and Gigantamax**: the market, auctions, the listing picker, the auction-creation picker and the admin screen each gained these two buttons in the right-hand filter panel. ⚠ Listings already on the market before this update did not record either field, so filtering by them will not list those (list the Pokemon again to have them recorded); auctions are unaffected

- **A crowded mark row can now be read in full**: a mark row collapsed into "+N" pops up a small window listing every mark when hovered (dialogs only)

- **The item market and the auction house can filter by creative category**: the filter panel gained a **Category** button that opens a grid of the creative inventory's categories; picking one shows only that category, and it combines with the search box as AND. ⚠ In the auction house it filters the item tab only; Pokemon entries are never affected

- **The auction house can be sorted**: all three tabs used to be listed "newest first"; a **Sort** button now cycles through Newest → Price ↑ → Price ↓ → Ends soon. ⚠ Sorting applies to all three tabs, and "price" means the price shown in the list

- **UI scale (affects this mod's screens only)**: the market entry screen's settings dialog gained a "UI scale" row cycling through Auto / 1-5, **default 3**, with "Auto" meaning follow vanilla exactly — on 2K / 4K displays vanilla's Auto squeezes this mod's screens, and they can now be enlarged on their own; the chat and other mods are unaffected

- **Ban management gained a "Change Log" tab**: it lists **every ban and unban** (time, operator → target, action, duration, reason), and each unban entry carries the original banner. The ledger lives in `config/cobblemarket/bans/` and is kept forever

- **A ban can now be edited directly**: every row in the ban list has an **Edit** button, so duration, reason and "Also block loans" are changed in one dialog. ⚠ Changing the duration counts from the original ban time, not from the moment you edit

### Changes

- **The force-cancel dialog now shows an item's full tooltip**: it used to show only "item name + ×count" for item auctions; it now matches the item bid dialog — enchantments, attribute modifiers, potions and custom lore all included, and expandable with Shift / Ctrl

- **The Type filter can now take two types at once**: picking a type leaves the list open so you can pick a second. ⚠ With one type selected the behaviour is exactly as before; with two, it matches only Pokemon whose types are exactly those two

- **How the UI marks a selected entry is now consistent**: lists, tabs and dropdowns use the button's own selected highlight instead of a "●" in front of the label

- **Hovering a chat announcement now shows the full details of the Pokemon or the item**: the large render sits in the centre of the screen with a backed panel of full details to its right — Pokemon and items both match their regular tooltips, and auction announcements also list the seller, current price, starting price, minimum increment, time left and who is leading. ⚠ The vanilla grey box next to the cursor is gone

- **The admin buy-order screen no longer shows a balance**: it is hidden in admin mode; the player-side buy-order screen keeps it

- **Item-backed currencies now show that item's icon inline, instead of `₽`**: inline amounts no longer read `₽` (balance HUD, market / auction / buy-order list rows, bid and purchase dialogs, loan / deposit / repayment screens). ⚠ Hover windows and chat broadcasts still spell out the item name, and virtual currencies are unchanged

- **"Reset" no longer wipes the search box**: the ↺ button in the Pokemon market's and the admin screen's filter panels now resets the filter options only and keeps your search text

- **The item market's top bar now uses the Pokemon market's layout**: Sort and Mine moved into the right-hand filter panel. ⚠ The ↺ Reset in that panel's first row only resets those three (Sort / Mine / Category), leaving your search keyword alone

- **Ban remaining time now follows your client language**: "3 天 5 小时" in Chinese, `3d 5h` in English — in the admin ban list and the chat message a banned player receives

- **The ban management screen now explains what a ban does and does not affect**: an info icon in the top-left corner shows the full scope on hover

- **Banned players are now told when they open the market**: the first time you open the market after logging in, a red message (remaining time + reason) appears in chat

- **Bans can now also block borrowing**: the admin ban confirmation dialog has a new "Also block loans" toggle (off by default). ⚠ Borrowing only — deposits, withdrawals and repayments still work

- **The borrowing entry is easier to find in English**: the Meowth Bank button (and the screen title behind it) was labelled "Meowth's Help", which gave no clue that it means borrowing — the config switch controlling it and the "unavailable" notice read the same way. They are now **Counter Loans** (the config switch is **Cash loan**), the wording already used for "counter loans" in the in-game rules and clearly distinct from "Meowth Pay" for checkout credit. ⚠ English text only; the Chinese label is unchanged

### Fixes

- **The trade history recorded the leftover stock for purchases, not the quantity bought**: listing 64 and buying 60 showed up as "4" — it now records the amount actually bought (purchase rows already written to the CSV keep the old value; delist / return / list records were correct all along)

- **The trade history did not show how many items were involved**: item records showed only the item name, so there was no way to tell how many of your listings had sold — both the row and its hover window now show "×N" (unstackable items are left alone — their count is always 1; records that predate this have no count stored, so they simply show nothing rather than "×0")

- **Some Pokémon played a swimming animation for the walk preview**: Pokémon such as Charizard and Pikachu played their water pose when hovered — they now fall back to idle (better to stand still than to play the wrong animation)

- **After a failed "Start auction" the dialog stayed open and could be clicked again and again**: price-limit failures still keep the dialog so you can fix the number and retry; every other failure closes it and returns to the auction house, with the reason still in chat

- **Rejected loan requests had no failure sound**: those refusals now play it

- **The buy dialog's input box and buttons did not follow the tooltip**: they now follow the dialog every frame, exactly like the item bid dialog

- **Buttons drifted away from the background in Meowth Bank when permissions changed while it was open**: they now move along with it

- **The item icon in the "Set up auction" dialog sat off to the right**: both modes now share one set of coordinates

- **Mark icons were drawn inconsistently on a few screens**: not centred in the bid dialog and the admin auction detail, a stray divider in the auction and admin row tooltips, a first icon row running past the divider on the buy-confirm screen, and one row of extra height in four dialogs — all four now follow the same layout

- **Ball names rendered as white text in the UI**: they now use the rarity colour, and the screens that showed no ball line at all (listing picker, auction creation picker, pending returns, admin) now show it

- **Some Pokémon had the wrong battle-cry animation** (e.g. Gurdurr could not lift its girder): fixed

- **The body froze during a battle cry, with limbs snapping back to their default pose** (e.g. Quilava): fixed

- **Pokémon listing screen: the bottom of the list wasted a full row**: it now matches the item listing screen

- **At night, icons stood up before lying down when a screen opened**: fixed, so the icon is already asleep as the screen opens

- **A Pokémon previewed right after another one performed the previous Pokémon's animation**: fixed

- **A tooltip with many lines had its top cut off screen**: it is now cut to fit the screen, with a grey "… (N more rows)" line at the end

- **Picking a Type filter left the list empty**: fixed, including the button showing an internal name such as `fire`

- **Part of the Pokemon icon was being cut off**: fixed

- **The buy dialog was too narrow and long tooltip lines spilled outside it**: it now sizes itself to the widest tooltip line

## 1.2.0 (released)

### New Feature

#### Pokemon Icon Animations (built on Cobblemon's own underlying animations; this mod only invokes and stitches them in the right places and contexts)

- **Pokemon preview switch**: a new setting to turn the hover preview off on its own (without affecting the icons themselves)
- **Keeps up with Cobblemon automatically**: when the official mod adds battle cry animations to more Pokemon, this mod **displays them without needing an update**
- **Known issue: some Pokemon play a swimming animation on hover under Cobblemon 1.8.1**: starting with the official 1.8.1, pose conditions for Pokemon evaluate differently than in 1.8.0, so some Pokemon (Charizard and Pikachu, for example) show a water-swimming motion in their icons and hover preview. It is not yet clear whether this is an intended change on Cobblemon's side, so we are not working around it for now

#### Item Animations

- **Item celebration animation**: buying an item, winning an item auction or receiving an item buy-order delivery now flies the item icon from its position in the dialog to the centre of the screen (the same entrance the Pokemon celebration uses), holds for a moment, then fades out, with a pickup sound; when no icon is available (Meowth Pay, offline delivery) it still drops in from the top with a bounce
- **Item hover preview**: hovering an item slot in the Item Market, or an item row in the Auction House / Buy Orders, shows an enlarged icon of that item on the left side of the screen
- **Separate item switches**: a new "Items" group in Settings (market purchase / auction & orders / item preview), independent from the "Pokemon" group

#### Settings Screen

- **Grouped settings**: options in the settings panel are now grouped under gold "Pokemon" / "Items" headings
- **Celebration animations are now entirely the player's choice**: the server-side master switch was removed from the Server Config; the switches in each player's settings decide everything

#### Interface

- **Custom mouse cursor**: the pointer becomes the mod's own icon while you are in the market's screens, and switches back to the system cursor when you leave; can be turned off in Settings

#### Pokemon Info Display

- **Moves shown in trading screens**: the tooltips of the Pokemon market / auction house / Buy Orders, the purchase and bid dialogs, the admin lists and dialogs, the Pokemon pickers when listing or creating an auction, and auction announcement hovers in chat now all show the Pokemon's four moves (two fixed rows, two per row, each name **coloured by its type**) — you no longer have to buy it to find out what it can do

#### Admin Permission Nodes

- **Admin duties can be delegated**: hand a single admin duty to a specific player — 9 nodes by domain (blacklist / price limits / bans / listing admin / finance / auctions / Buy Orders / market switch / trade history). ⚠ **The market switch and trade history are both their own nodes**: the former is a nuke that hits everyone, an order of magnitude away from "take down one listing"; the latter is **read-only** yet the trade log is **not public** (listings are visible to all, the ledger is not) — asking someone to watch listings should not also hand them everyone's economic history. Splitting them means anyone who wants both combined just ticks both. Delegated players now see the "Admin Panel" button on the entry screen; inside the panel, the entries they hold can be opened while the rest are greyed out with a notice on click
- **Server owner list**: the owner is the only person with all admin permissions, and the only one who can edit the server config or grant admin permissions to others. The list stores UUIDs only (renaming a player changes nothing, and nobody can claim the spot by taking that name) and **can only be edited in `serverOwners` in `config/cobblemarket.json`** (takes effect on `/market reload`). ⚠ **OP is not the owner**: any OP can pass OP on with `/op`, while only you can edit this list — so the market trusts the list, not OP. An empty list means nobody has admin access (secure default). **How to add yourself and where to find your UUID: see [Permission Nodes](https://shushenga.github.io/CobbleMarket-Website/#/en/permission) on the website**
- **Permissions screen**: a new entry at the bottom-left of the Admin Panel (server owner only) — search a player, tick the nodes, save, and it applies immediately; **hovering a node explains what granting it lets someone do** (the matching Admin Panel buttons carry the same hint), so you can see the boundary before handing it out — the node names alone don't reveal their scope; online players need no relog
- **Permission commands**: `/market perm grant|revoke|list` (owner-only; `list` shows every grant)
- **Permission change log**: the permissions screen gains a "Change Log" tab
- **Permission changes now notify the target**: when an admin node is granted or revoked the player gets a chat notice listing their full node list afterwards — no more silent changes: whoever gains a permission knows what changed, and whoever loses one knows what's gone; changes made while offline are delivered on next login

#### Sound Effects

- **Server-side event sounds**: previously only what the player started made noise (trade success/failure, buttons, card animation) while anything the server did on its own relied on chat text; now those have dedicated sounds too — **outbid**, **auto-deduct** (a loan payment taken from your balance), **overdue warning** (entering overdue / one day before a due date), **item sold** (your listing bought or your buy order delivered) and **permission change** (admin node granted or revoked) each play a sound. **Online players only**; whoever was offline gets the "offline rewards" cue when they log back in
- **Offline rewards cue**: the first time you open a market screen after logging in, a sound plays if there are Pokémon/items waiting to be claimed or unclaimed balance to collect (once per login)
- **Market open / close**: when the owner flips the market master switch, everyone online hears a sound and gets a chat broadcast ("The market is open again" / "The market is closed") instead of the market going dark silently until someone's trade gets blocked

### Changes

- **The admin panel's species and player search boxes are now one**: the admin "All Listed Pokémon" and "All Listed Items" screens each had two search fields; they are merged into a single box that searches **both the name and the seller** (the server matches either one, Chinese player names included). The placeholder text across the market screens now mentions **player** as well — it only said species / item before, so nobody knew these boxes could find people
- **Filter controls moved into a panel on the right**: in the Pokémon Market, the Auction House and the admin "All Listed Pokémon" screen the filter controls used to sit in a row above the list — opening the type / ability / nature dropdown pushed the list start down by several rows, and the dropdown itself covered the rows beneath it. They now live in a **dedicated panel to the right of the list** (toggled by the "Filter" button at the right end of the search row, collapsed by default), so the list start never moves and the four workarounds written for that overlay are gone entirely (the Pokémon tab also fits one more row per page)
- **Server Config and Permissions are now server-owner only (existing owners: read this first)**: those two entries can only be opened by the **server owner** — the accounts listed in `serverOwners`. ⚠ **The Admin Panel itself is not owner-only**: the owner, delegated admins and OPs can all open it (so they can see which features exist), and every button inside is judged by that player's own permission nodes — no node, no click. ⚠ **Add yourself before anything else** — skip this and those two entries plus every admin duty become unreachable (inside the market **the owner list is what counts, not OP**). How to add yourself: see [Permission Nodes](https://shushenga.github.io/CobbleMarket-Website/#/en/permission) on the website
- The market master switch and Server Config entries moved from the market entry screen to the bottom of the Admin Panel — the master switch is gated by the "market switch" node while Server Config is gated by ownership (delegated players and owners each operate what they are allowed to)
- Removed the Cobblemon Economy incompatibility warning: version 0.0.18 of that mod has adapted to Cobblemon 1.8 (it no longer references the renamed Pokédex field), so enabling the currency no longer logs the "will crash" warning at startup; the config comments and website docs were updated to match
- The four history lists (Trade History, All Trade History, Loan History, All Loan History) now highlight the row under the cursor and show a popup with that row's full details (time, type or status, buyer/seller, Pokemon or item name, amount) — long player names, Pokemon names and amounts that had to be truncated inside the row can now be read in full by hovering
- Trade history now shows Pokemon names in their **primary-type colour** and item names in their **rarity colour** (the same helpers the market lists use), with amounts in gold; the ledger CSV gains a "Primary Type" column (form-aware — Alolan Vulpix records Ice) to drive the colouring
- **The two finance cards were renamed**: "Meowth·Purple Gold Card" / "Meowth·Black Gold Card" are now **"Meowth·Volt Card"** / **"Meowth·Genesis Card"**
- **Both cards gained a flavour-text line** — localised for both Chinese and English

### Fixes

- **Untradeable Pokémon could still be traded through the market / auctions / buy orders**: Cobblemon lets a Pokémon be marked untradeable, but our market ignored that flag entirely — the Pokémon could still be listed, auctioned, or delivered to someone else. All three paths now block it with a message, and **the selection rows show the very same lock as vanilla**
- **"All Trade History" sent you back to the market entry screen**: it is a second-level screen opened from the **Admin Panel**, but its Back button went to the market entry — throwing you out of the admin panel entirely. It now returns to the previous level (the Admin Panel). Lists opened from the entry screen (such as Trade History) are unaffected
- **One wrong character in the config file used to wipe the whole config**: editing `config/cobblemarket.json` by hand and missing a quote (or leaving a stray comma, or using full-width quotes) made the file fail to parse — and the mod then **overwrote it with defaults**, resetting every fee, limit and finance parameter you had tuned, with **no way to get them back** and only an easily-missed note in the log. It now **leaves your file untouched** (however broken, it stays there so you can see what is wrong), saves a `.broken` copy for reference, and logs a loud error explaining the cause
- **Enchanted and renamed items rendered incorrectly in the Auction House and its Admin Panel**: enchanted books appeared as plain books and renamed items showed their default name in those views; they now render from the item's full data (both list rows and detail dialogs)
- **Held item icons ignored enchantments and custom names**: when a Pokemon's held item was enchanted or renamed, its icon in lists and detail views showed the plain item; the icons now render from the item's full data (Auction House, Buy Orders, sell-select and Admin Panels)
- **Pokemon and item names were not colour-coded**: in the blacklist and price-limit screens the names in list rows and tooltips were plain white — Pokemon names now use the **form's** primary type colour (Alolan Vulpix reads as ice, not the base species' fire) and item names use their rarity colour (matching the inventory tooltip); the species name in the buy order "View pending delivery" notice is fixed too (the whole line used to be green, tinting the name along with it)
- **Marks were missing from the auction announcement hover in chat**: every Pokemon detail panel shows marks, yet hovering an auction announcement in chat showed none — the same Pokemon read differently in the two places. Now it matches the rest (same position as elsewhere: below friendship, with anything past the first three folded into "+N")
- **The settings dialog ran off-screen (most visible with GUI scale set to Auto)**: the window behind the entry screen's Settings button stuck out past both edges, leaving even the Done button unreachable; its height is now clamped to the screen, its position is kept on-screen, and the content scrolls when it does not fit (the same approach the Server Config screen uses)
- **The "Create buy order" dialog ran off-screen (same cause: GUI scale set to Auto)**: the dialog was taller than the screen, so the top fields and the Confirm button at the bottom were cut off; its height is now clamped to the screen and the form scrolls with the wheel, with **Confirm / Cancel pinned to the bottom edge** so they are always reachable — the same approach the Server Config screen uses
- **Names in chat notices were not colour-coded**: the "your X was sold" notice was plain white — the only colourless trade notice in the mod — and now matches "listed / bought" in green; item names in bought / sold / listed / cancelled notices, auction and settlement broadcasts and buy-order messages now use their rarity colour, matching the inventory tooltip (renamed or enchanted items are coloured from their full data); Pokemon names in the outbid / unsold / force-cancelled / settled notices now carry their type colour, consistent with the server-wide broadcasts; a shiny Pokemon gets the gold star in **all** of them, buyer side and seller side, market and auction alike
- **Hovering a button under a dialog still showed that button's tooltip**: a button covered by an open dialog still popped up its tooltip when hovered; fixed for the Pokemon market, item market and admin Pokemon list dialogs

- **The Buy Order "force delist" dialog showed nothing but a name**: an admin deciding whether to force-delist an order could not see what that order actually asks for (conditions, nature/ability, IV requirements, price range, note…); it now shows exactly the same details as the row tooltip, and clamps to the screen with wheel scrolling when too tall
- **The Meowth Pay dialog gave no sign of your available credit**: you could not tell whether the instalments would fit your limit until you confirmed and the server rejected it; the dialog now shows "Available credit" centred under the title, **turned red when it falls short of the amount**
- **Icon-only buttons had no tooltip**: the "list / create auction / add" buttons with nothing but an icon (Pokémon market, item market, Auction House, blacklist, price limits) gave no hint on hover, leaving players to guess — the listing entry especially; they now all show a text tooltip. Three hand-drawn tooltips (buy-order, blacklist, price limits) that looked different from the rest were also switched to the standard one
- **Five config screens gained a scroll bar**: the finance config, the Volt / Genesis Card configs and the two card-condition screens now show that there is more to scroll
- **The hover panel jumped from side to side as the mouse moved down the list**: each row flipped it from left to right at the same height, and the next row flipped it back; the "don't cover the row button" check followed the cursor, so a dozen pixels of vertical movement inside one row reversed the decision. The panel is now simply **not drawn while the cursor sits on a row button** (that is the moment you are about to click, not read), which removes the avoidance logic entirely and with it the jumping
- **A trade history row ran past the panel edge when both the price and the buyer name were long**: the two right-hand segments were positioned by adding on to the end of the middle segment, so a long buyer name plus a large price pushed them past the panel's right border (and squeezed the middle segment down to a single "…"); the segments are now positioned from the right edge instead, with an over-long buyer name truncated (its full text is in the hover popup)
- **Pokemon names read back from the ledger showed as raw species ids**: the ledger stores a species id (such as `pikachu`) and the reader never restored the translation key, so the list displayed "pikachu"; it is restored now and the localised name shows correctly
- **The input fields on all six config screens (Server Config and its five sibling screens) were too narrow**: long values such as the auction duration tiers (`720,1440,2880,4320`) or the loan plan list showed only their first few characters, which made them easy to misread while checking or editing; the fields are now wider — long-value fields fit the whole value on screen, and plain numeric fields hold a 10-digit amount
- **The last Pokémon was never reachable at the bottom of the sell-select list**: with 41 Pokémon the list stopped at #40 (the counter read "29-40/41") and the last one could only be found through the search box. The visible-row count was computed twice with two formulas that differed by 4 pixels, which under some window sizes or GUI scales rounded to one row fewer — so it looked like "changing the GUI scale fixes it". Both now use the same formula
- **A long balance overlapped the title**: in the Auction House, Buy Orders and the auction-create screen the balance in the top-left corner grew long enough at billion scale to overlap the centred title (Buy Orders hit it sooner since its panel is narrower); it now falls back to a short form such as `12.3M` when it does not fit, and only truncates as a last resort

- **Hiding a held item made it disappear from the trading screens**: switching a held item's visibility off in Cobblemon's summary removed the whole "Held" line from the market and auction rows, their tooltips and the purchase dialogs, leaving buyers unable to tell what a Pokemon was carrying (hiding it is the player's own choice for the model — the information should not vanish with it). It is now listed as usual and marked "(hidden)"; only the Pokemon model itself goes without it

- **Installments are no longer offered when the amount is too small**: with an amount smaller than the number of periods (say 10 over 12 periods), the per-period principal divides down to 0 — the "~0 per period" figure made players think nothing would be charged, while the final period actually took the entire principal at once. Such plans are now **greyed out, with a notice when clicked**, and the server rejects them as well (blocked on both the client and the server)

- **The smartphone Market icon now reads "Open CobbleMarket" on hover** (it used to show "Pokémon" / the Chinese market label)

- **The balance HUD slid up together with the screen when closing a market screen**: with the "market animation" setting on, closing any market screen made the balance number slide up with the screen and then snap right back once the screen was gone (present since 1.1.1). The close animation now only moves the screen itself — the balance stays where it is

- **Chinese punctuation no longer leaks into English clients**: the item count line, the rule summaries in Blacklist / Price Limits / Buy Orders, and the nature line all had **hard-coded full-width** brackets and separators (`（）`, `、`) — the right typography for a Chinese UI, but on an English client they wrap English text in full-width brackets, which are also twice as wide and read as clearly out of place. Punctuation now **follows the client language**: full-width for Chinese, half-width for English

- **Player names in chat are no longer tinted by the message colour**: the auction listing and sale broadcasts are blue throughout, rejected buy-order notices are yellow, and notices and command feedback alike (cards, permissions, bans, bad-debt revocation…) go green on success and red on failure — a player name sitting inside them used to be **tinted along with everything else**, as if we had coloured it ourselves. Player names in these messages now stay **default white**, while the rest of the text keeps its colour

- **Pokemon auctions were called "items" in the notifications**: winning an auction, an auction ending unsold and an auction being force-cancelled all said "**item** sent to / returned to your pending claims" no matter what the lot actually was — a Pokemon is not an item. The same wording was on the Auction House rules page ("the winner's **item** goes to their returns"), the admin's force-cancel confirmation and the market-close confirmation (present since 1.1.1). The wording now either drops the noun or uses the neutral "**lot**"

- **The listing confirmation did not colour the species name by its type**: in "Listed X (Lv.10)…" the name took **the line's colour** instead of its own type colour — the same Pokemon read one way in the market list and another way in chat. Fixed, and shiny Pokemon get the gold star there too

- **On English clients several field hints spilled outside their boxes**: the two price fields in "Create Buy Order" (`Max unit price` measures 84px against a 74px field), its note field (`Note (extra needs, optional)` at 168px vs 148px), the blacklist add field (`Enter species name or ID` at 144px vs 132px usable) and the price-limit min/max fields (`Min price` at 54px against a field only 46px wide) — vanilla does **not** clip these hints, so the overflow was drawn straight outside the box. Chinese never showed it (「最高单价」 is 36px) — English simply runs wider than Chinese. These fields now **pick by measured width**: the full wording when it fits, a shorter one (`Max price` / `Note (optional)` / `Min` / `Max`…) only when it does not — **Chinese keeps the full wording**

- **The item market briefly said "No items." while opening, which reads as "the market is empty"**: the list is requested only once the screen opens, and until it arrives the list is still empty — "**not loaded yet**" and "**genuinely nothing there**" used to share the same message, sending players off to ask the server owner. The former now shows "**Loading…**", and "No items." appears only when there really are none

## 1.1.3 (released)

### Fixes

- **Closing a screen with Esc could make items on it briefly "vanish"**: with materials in a crafting table (or an item on your cursor), pressing Esc to close meant they didn't return to your inventory right away — they looked gone until you opened any container again (your own inventory counts). With shop mods like Shopkeepers / PlayerShops it could also leave their trade state out of sync. The cause: our "close animation" intercepted Esc and closed the screen its own way, skipping the vanilla step that tells the server "the container is closed" — so the server still believed the container was open. The close animation now only applies to this mod's own screens; everything else goes through vanilla. ⚠ Affects versions from 1.0.1 onward

## 1.1.2 (released)

### New Feature

- **Update available notice** (can be turned off in the settings on the entry screen)

### Changes

- **The repay counter shows when the next instalment is due**: the repay dialog gains a "Next period due in X days X hours" line, so you can see at a glance how long is left; overdue loans keep showing "N periods in arrears" in that same slot (the two never stack)

### Fixes

- **Loan periods actually lasted 1 day, not the documented 7**: the config comments, the in-game screens and the website all said "each period is 7 days", but the period arithmetic used 24 hours — a 12-period loan fell due in full after 12 days, nothing like the schedule players were budgeting against, and it pushed them into overdue (and the sanctions that follow) far too early (fees double at 7 days overdue / trading freezes at 14 / the loan is written off as bad debt at 30). Periods are now **a true 7 days**, matching the description. ⚠ **Loans taken out before the update**: recomputing overdue days on the new period length **cuts `6 × (periods already repaid + 1)` days** off them (a loan with nothing repaid is 6 days short of the line), so bills about to hit the 14-day freeze / 30-day write-off **can fall back inside the safe line**; the doubled fee is computed at every charge, so under 7 days it **simply stops doubling** with no action needed; freezes are re-evaluated at login / repayment against the latest overdue days — under 14 days with no bad debt **unfreezes automatically at the next login** (with a green notice), and 14+ days stays frozen. ⚠ **The "overdue" mark itself does not clear on its own** (it blocks new loans and Meowth Pay) — the player must **repay one period to catch up** or settle early to return to normal. ⚠ **Loans already written off as bad debt are not affected by this fix** (the debt is gone but borrowing stays blocked) — only the owner can handle those: `/market loan clear <player>` (or "Revoke" in the admin panel) deletes the record (debt cleared, the audit ledger keeps the trace, the money is not recovered), after which the freeze is re-evaluated and lifted; `/market unban` only lifts the market trading block and **does not revoke bad debt**. ⚠ For owners: the money behind a bad debt left the reserve pool at the moment of borrowing — an existing loss this fix does not recover

## 1.1.1 (released)

### Fixes

- **Text overflowing its control in a few screens in English mode**: with "Force Unicode Font" off, some English text drew past its button or dialog border (the Chinese UI is unaffected). Every issue found has been fixed
- **Chinese search missed Pokemon**: when one term matches several names (searching "鬼斯" — but also "鬼斯通") only one of them ever showed up; now **every name containing the text appears** (English search is unaffected)
- **Typing a Chinese Pokemon name in the blacklist / price limit / buy order screens picked the wrong Pokemon**: typing "鬼斯" actually selected "鬼斯通", so the rule silently landed on a different Pokemon. Now **an exact name match wins**, and when several match **left/right arrows** appear next to the preview to flip through them
- **Balance HUD was not hidden by F1**: it stayed on screen after the vanilla HUD was hidden (in the way when taking screenshots or recording); it now hides together with the vanilla HUD
- **Balance HUD sat on top of vanilla screens**: it covered the pause menu, options and inventory. It now **drops below those screens** (still faintly visible), while **this mod's own screens keep it on top**
- **Marks were missing from the auction announcement hover in chat**: every Pokemon detail panel shows marks, yet this one place showed none — the same Pokemon read differently in two places. It now matches the rest

## 1.1.0 (released)

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
- **Owner finance report & bad debt intervention**: the Admin Panel gains a reserve pool / total bad debt line (red alert when the pool is negative = owner debt); the all-loans ledger shows each borrower's latest IP (for spotting alts); bad debts can be revoked (a "Revoke" button in the all-loans screen or the `/market loan clear` command) — the player regains borrowing eligibility and is auto-unfrozen
- **Server-wide total volume**: the market entry screen now shows the server's all-time total trading volume right under the title (an economy-scale showcase)
- **Meowth Bank deposits (demand)**: Meowth Bank gains a "Deposit/Withdraw" entry — idle money earns daily interest (rate configurable, paid from the reserve pool) and can be withdrawn anytime (interest included); deposits fund the reserve pool for a full save-lend loop, and withdrawals stay available even when the master switch is off
- **Credit growth cooldown**: trades don't count toward the credit limit until a delay passes (default 24 hours, owner-configurable, 0 = off) — the limit grows on a delay, closing the "farm-then-borrow" window for organized quick cash-out groups; the trade-pair detection window and trade cap are also configurable (default 30 days / 3 trades)
- **Due-date reminder**: one day before each installment is due, the player gets a yellow reminder (with the principal amount), so nobody forgets a due date and slips into overdue by accident
- **Meowth Bank rules panel**: a new "Rules" button under the back button in Meowth Bank — hover it to see loan rules and consequences (installments / auto-deduct / three-tier sanctions / shared limit / interest), with key points in red
- **Bad-debt tab in the all-loans ledger**: the OP all-loans screen gains "All / Bad Debt" tabs to filter every bad debt record at once; repaid loan records are auto-purged after 90 days (the audit ledger keeps the full history forever)
- **Deposit-rate guard**: the deposit daily rate is now automatically constrained by the loan installment plans — setting it above the arbitrage-safe line clamps it back to the safe value and logs a warning, making "borrow, deposit, farm interest" impossible; lowering loan fee rates tightens the deposit rate accordingly; after saving, a yellow notice at the bottom of the config screen shows the clamped result (e.g. fee-free plans zero the rate), so the reset button never looks broken

#### Finance System · Card Credentials (Meow·Purple Gold Card / Meow·Black Gold Card)

- **Meow·Purple Gold Card**: a high-limit credential item — holders get a fixed borrowing limit set by the owner (default 1M), with a configurable server-wide card cap (default 20); the limit is bound to holder state, not the item (cards duplicated by item bugs are worthless); dropped cards vanish instantly and can be reissued at Meowth Bank (apply/reissue with a full inventory is refused with a clear-inventory hint, checked before any fee is taken); card holders are exempt from the per-IP debt cap (the anti-alt limit only applies to regular players); owners use `/market card` to give/revoke/list
- **Meow·Black Gold Card**: a credential one tier above the Purple Gold Card — limit defaults to 5M with a server cap of 5 (both configurable); applying requires already holding the Purple Gold Card (hard requirement), with the same seven configurable conditions as the Purple Gold Card; obtaining the Black Gold Card automatically removes the Purple Gold Card qualification (an upgrade replacement, no double slot); the Black Gold Card's limit and fee discount apply; the Black Gold Card icon on the right side of Meowth Bank opens the application screen; owner commands gain a card-kind argument (`/market card give <player> black`, same for revoke/list, purple by default)
- **Card management screen (OP only)**: a new "Cards" button under the Rules button in Meowth Bank (with Purple Gold Card/Black Gold Card mini icons) — lists every Purple Gold Card/Black Gold Card holder (name + held-card icons) with a per-row "Revoke" button, same effect as the command
- **Holder display panels**: a small info panel under each card in Meowth Bank (visible to everyone) — the title shows "current holders / server cap" and the panel lists the holders (name + skin avatar, row dividers, scrollable); refreshed live on give/revoke/apply
- **Purple Gold Card self-application**: owners can let players apply themselves — clicking the Purple Gold Card on the left of Meowth Bank opens the application screen, listing all seven conditions (asset / spending / credit / deposit / Pokedex seen count / Pokedex caught count / clean record) with live progress; apply once everything passes, and the fee goes to the reserve pool; enabling the switch takes a 5-second cooldown confirmation (prevents opening it before the conditions are configured); both cards' apply screens show the card's credit limit and fee discount perks
- **Purple Gold Card holder fee discount**: owners can configure a market fee discount for Purple Gold Card holders (covers listing / auction settlement / buy-order fees, stacks with overdue doubling, off by default) — holding the card makes trading cheaper
- **Purple Gold Card reissue fee**: reissuing a Purple Gold Card now costs a configurable fee (free by default), which goes to the reserve pool; the application screen shows the reissue fee for holders
- **Net-deposit card requirement**: the Purple Gold Card / Black Gold Card "deposit balance" requirement is now judged by net deposit (demand deposit − outstanding debt), so borrowed money can't inflate deposits to qualify; the application screen shows the net value
- **Card grant animation and sound**: obtaining a Meowth Purple Gold Card / Black Gold Card now plays a dedicated sound as the card flies from the application screen to the centre of the screen (one per card, the black card's is longer); the two cards have their own pacing — the purple card flies 0.7s, holds 0.6s, then fades out over 0.6s, while the black card flies 1s, holds 1.5s and fades over 0.7s — slower and more ceremonial

#### Others

- **Pokémon friendship display**: every Pokémon detail view (market hover / auction details / purchase confirm / buy-order delivery / admin lists / sell previews / pending returns) now shows friendship under the six IVs
- **Item search upgrade (follows the vanilla creative search semantics, and goes further)**: item search boxes (Item Market / admin list / blacklist / price limits / auctions / Buy Orders) now match item IDs, names, and full tooltip text; Cobblemon 1.8 technical machines (TMs) and enchanted books can be **searched down to the specific variant by move / enchantment name** (the vanilla creative search can't find TM moves — we filled in the missing move enumeration) — searching "snore" reaches the Snore TM directly in the Item Market / auctions, and yields a "TM · Snore" candidate in the blacklist / price-limit / buy-order add dialogs; enchanted books expand level by level ("Enchanted Book · Sharpness I" through "Sharpness V", entries matching that level and above — picking I bans every Sharpness book, picking the max level bans only the max level), so the resulting entry affects only that variant instead of every TM; future Cobblemon moves keep working automatically
- **Blacklist / price limits now go down to item variants**: the add dialogs gain an "Add from held item" button (a hand icon that lights up on selection, confirmed with the Add button — the dialog shows the held item's icon and name, and starting a search or picking an item exits the mode) — whatever you hold gets banned/limited, e.g. banning only Sharpness V enchanted books without touching other books; entries use containment matching ("Sharpness V" also matches "Sharpness V + Unbreaking III", so a junk enchantment can't bypass it); multiple price-limit rules apply the most specific entry first ("Sharpness V + Looting III" follows its own price range instead of being crushed by the "Sharpness V" rule); search filters down to the variant (searching "snore" only shows the Snore TM entry); batch-unban unblocks exactly what the search shows
- **Buy Orders can require item components**: publishing an item buy order can select a hand icon to carry the held item's enchantments etc. as requirements (e.g. only accepting Sharpness V books); deliveries not satisfying the component requirements are rejected, and the order list rows show the requirement
- **Item hover tooltips overhaul**: every item list and icon hover now shows the real item tooltip — TM moves in the local language, enchanted book enchantment names with roman-numeral levels, all visible in list rows and hovers; hold Shift for the full tooltip and Ctrl for component details (with instant refresh)
- **Pokémon mark display**: Pokémon detail panels (market hover / auction / auction bid dialog / force-cancel dialog / admin auction / admin Pokémon list / pending return / purchase confirm / buy-order review etc.) now show a mark section below friendship — all marks the Pokémon owns displayed between two divider lines (10 per row); marks are cosmetic and do not affect trading rules
- **Pokémon size badges**: every Pokémon list and detail view (market / auction / Buy Orders / admin / listing preview / pending return / sell select) now shows a size badge — XS/S/M/L/XL, or ALPHA for alpha Pokémon; in list rows it sits after the held-item icon; the auction chat announcement's hover shows the size as a letter after the gender
- **Custom balance HUD position**: the market entry settings gain a "Balance HUD position" row — click "Custom" to enter drag mode, hold left-click to drag the balance HUD anywhere on screen and release to place it; it snaps to screen edges and center lines and shows alignment guides; the position is stored proportionally, so changing resolution or GUI scale won't shift it; the "Show market balance HUD" label is now simply "Balance HUD"
- **Item icons show durability**: item icons in the market, auctions, Buy Orders, pending claims and the admin panel now show a durability bar (only when damaged, never at full durability); hovers add a "Durability: X / Y" line without holding Shift — no more paying full price for a nearly-broken tool

### Changes

- Card apply Pokédex thresholds: the seen count is auto-raised to the caught count when set lower (prevents self-contradictory configs)
- Selected buttons and labels de-texted
- Item names in item list rows now use rarity colors (matching the inventory tooltip)
- Auction list rows (Auction House and Admin Panel) now show abbreviated prices (e.g. 1.2k / 3.5M, consistent with the Item Market and Buy Orders; hover tooltips and bid dialogs still show full amounts with thousands separators), and the "From" prefix is dropped from the row; the seller avatar now sits at a fixed position (aligned across rows, matching the Pokémon Market) with the countdown right after it, so Pokémon names and size badges no longer get squeezed
- Pokémon names in auction list rows (Auction House and Admin Panel) now display up to 5 characters in full, truncating with an ellipsis only beyond that
- The level position in the sell-selection list is moved right (matching the auction Pokémon picker), so long names with a full set of icons no longer cover it
- Pokémon Market list rows move the seller avatar and level right and show abbreviated prices inline (hover tooltips keep full amounts), so long names with a full set of icons no longer cover them
- Auction House and admin auction rows move the avatar and countdown right so size badges no longer overlap the avatar
- Admin Pokémon list and pending-return list move the level right (matching the Pokémon Market), and admin Pokémon rows show abbreviated prices inline
- Buy order rows show longer Pokémon and item names (up to 6 CJK characters in full) instead of being cut down to three
- Auction House, admin auction, Pokémon Market, admin Pokémon list and pending-return rows move the avatar, level and countdown further right, leaving a gap after the size badge; Pokémon names in the Pokémon Market, admin list and pending returns are truncated with an ellipsis past 6 CJK characters; the inline bid count in auction rows now sits flush against the price, with a tighter gap to the bid button
- The minimum-increment hint on the auction creation screen now shows the server's configured default amount (e.g. "blank = 100") instead of a bare "blank = default"
- Project license changed from MIT to GPL-3.0 (releases up to 1.0.1 remain under MIT)
- **Cobblemon Economy compatibility warning**: Cobblemon 1.8 renamed the Pokédex field `PokedexEntryProgress.CAUGHT` to `OWNED`, but Cobblemon Economy (up to 0.0.17) still references the old name — this crashes the server whenever a player **obtains, levels up or evolves a Pokémon** (starter selection, catching, hatching, trading, levelling, evolution, form changes). Servers with this currency enabled now get a prominent warning at startup; switching to CobbleDollars / Impactor / item currency is recommended

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
- **Item icons showed no durability bar**: items in lists and detail dialogs never showed a durability bar, so players could unknowingly pay full price for a nearly-broken tool or piece of gear — damaged items now show the vanilla durability bar, and the tooltip adds a "Durability: X / Y" line (no Shift needed)
- **Buyer's note hidden behind the "Select variant" button in the delivery dialog**: when a buyer left a note such as "any durability is fine", the seller could not see it in the delivery dialog (the text sat underneath the button); the content below now shifts down one row when a note is present, so the note is fully visible
- **The minimum bid increment hint on the auction listing screen never showed the server default**: the placeholder stayed at "leave blank = default" instead of showing the actual amount — the config was already fetched when the screen opened, but the field is only created when you open the listing window, so the update was always skipped (it only happened to show correctly after a window resize rebuilt the screen); it now displays correctly either way
- **Borrowing was still allowed while the market was closed**: the market master switch (emergency stop) only blocked trading / auctions / buy orders — the finance side was not gated, so players could still take out a loan from Meowth Bank while the market was stopped (not an OP privilege: everyone could). Loans are now refused while the market is closed and the "Emergency Loan" button inside Meowth Bank is greyed out; **deposits, withdrawals, repayments and card applications are unaffected** (none of them moves money out of the reserve pool)
- **Gating while the market was closed was incomplete on the client**: with the market stopped, the Pokémon / Items / Auction / Buy Orders entries still looked clickable and only told you the market was closed after you clicked, while Settings (all client-side visual toggles) and History (a read-only ledger) were blocked along with them; and Meowth Bank's "Meowth's Help" was greyed out yet still opened the loan screen, so players filled in the whole form and were only refused by the server at the end. The four trading entries are now greyed out (still clickable — you get a sound and a "market closed" notice), Settings and History stay available while the market is closed, and the greyed-out "Meowth's Help" now only shows the notice instead of opening the screen
- **Saving, adding or deleting in the admin screens gave no feedback at all**: saving the server config or permissions, adding or removing blacklist and price-limit entries, revoking a card or a bad debt — these buttons used to be completely silent (just a faint button click), so you could not tell whether the action had gone through. The server now plays a result sound for whoever performed the action: one for success, and one for a rejection (species not resolved, invalid price, the player is not a card holder, and so on) instead of red chat text alone; these results match the volume of the trade success / failure sounds

## 1.0.1 (released)

### Changes

- **Cobblemon 1.8 adaptation, 1.7 no longer supported**: this version and all future versions require Cobblemon 1.8+. Reason: 1.8's GUI Pokémon renderer (drawProfilePokemon) had a breaking signature change (boolean → ProfileTransformType + a new parameter); 1.7.x players should keep using 1.0.0

## 1.0.0 (released)

### New Feature

- New "Claim overflow" toggle in Settings (off by default): when enabled, claiming item returns drops anything that doesn't fit into your inventory onto the ground (they may despawn or be picked up by others — at your own risk); when off, the remainder stays in pending returns for next time
- **Admin "All Buy Orders" screen**: new entry in the Admin Panel to view every buy order and force-cancel them — the buyer's frozen money is refunded, pending deliveries return to their sellers, and both sides get notified (queued for offline players); Admin Panel buttons rearranged into a two-column layout
- **Cobblemon Economy currency support**: a new currency mode on Fabric (four in total now: Cobblemon Economy / CobbleDollars / Impactor / items) — servers with Cobblemon Economy installed use its currency API directly (its built-in bridge routes to CobbleDollars/Impactor backends; set main_currency to share one balance between the market and CobbleDollars merchants; Impactor can also be used standalone without Cobblemon Economy). Currency priority: Cobblemon Economy → CobbleDollars → Impactor → items; auto-detected on fresh installs, no behavior change on config upgrades; new `currency.cobblemonEconomy` switch plus optional `currency.cobecoCurrency` (POKE default / PCO) to settle in PokeDollars or PokeCoins; prices now use ₽ as the unified unit in PokeDollars/CobbleDollars modes (inline and dialogs alike)
- **Native NeoForge support**: a NeoForge build (cobblemarket-neoforge-1.0.0.jar) with feature parity and save compatibility with the Fabric build; requires Kotlin for Forge and Cobblemon (NeoForge), no Architectury API needed; Cobblemon Economy has no NeoForge build, so that platform falls back to CobbleDollars / Impactor / item currency (three in total)
- **Container content validation**: the item blacklist, price limits, and the egg-trading switch now apply to items inside containers too — listings, auctions, and buy order deliveries recursively inspect container contents (vanilla containers like shulker boxes; mod containers are not checked) so restricted items can't be smuggled past governance
- **Item variant selection for buy order delivery**: when your inventory has the same item in multiple component variants (e.g. shulker boxes with different contents), you can now pick which variant to deliver — the selection list shows icons and counts with full tooltips, and the delivery dialog has a change button; single-variant delivery is unchanged
- **Professor Oak & tip bubble**: a Professor Oak portrait now stands permanently at the market entry screen, with a speech bubble above his head showing random Pokémon trivia (498 built-in tips in Chinese and English, editable and replaceable); a new random tip is picked each time the entry screen opens, and clicking Oak switches to the next one
- **Config hot reload**: new `/market reload` command (OP) — fees, limits, durations, and toggles take effect immediately after editing the config file, no restart needed; changes to the market master switch are broadcast to everyone; currency settings still require a restart (reload notifies you if they were changed)
- **In-game Server Config editor**: a new "Server Config" button (OP only) sits left of the market master switch on the entry screen — fees, limits, durations, and toggles (15 settings) can now be edited in-game (number fields save when you click elsewhere, toggles apply instantly), no config file editing needed; currency settings and auction duration options still require editing the config file
- **Direct Impactor integration**: use Impactor currency without Cobblemon Economy — new `currency.impactor` switch (works on both loaders; NeoForge owners now have a direct virtual-currency path), the market reads/writes Impactor's EconomyService API directly; priority is Cobblemon Economy → CobbleDollars → Impactor → items; Impactor is not auto-detected on fresh installs (it is often installed as a library by other mods — auto-enabling would silently switch the currency), owners opt in explicitly; config comments now note cobblemonEconomy is Fabric-only
- **In-game balance HUD**: a market balance display in the top-left corner of the game (gold amount + dark rounded background frame, visible on every screen — players can see their remaining balance even inside bid/purchase dialogs); item currency mode counts the inventory locally in real time (dropping/picking up currency items updates instantly); virtual currencies refresh after trades plus a 30-second low-frequency fallback; three-state Settings toggle — always show (default) / show 5 seconds on balance change / off
- **Server-wide auction broadcasts**: creating or selling an auction now broadcasts to everyone in chat — hovering the lot name shows full details (Pokémon level/IVs/EVs/nature/ball, item enchantments), and clicking it jumps straight to that lot's bid dialog
- **Buy order review shortcut**: new-delivery notifications now carry a "Review" button that jumps straight to the review dialog; offline queued notices work the same way
- **Pokémon icon animation**: icons on every screen now play Cobblemon's built-in idle animation by default, with a new two-state setting to switch back to fully static
- **Enchantment details visible**: items in auctions and buy order deliveries now show their full enchantments and other tooltip lines — list hover, bid dialog, broadcast hover, and review dialog all match
- **Ledger covers auctions and Buy Orders**: auctions (listed/sold/unsold/force-cancelled) and Buy Orders (placed/filled/closed/expired/force-cancelled) are all written to the transaction history CSV, with a new enchantment summary in the Details column for exact recreation

### Changes

- The egg trading toggle moved from the Admin Panel to the new "Server Config" screen — entry button next to the market master switch on the entry screen (OP only); changes apply when you click Save, and enabling egg trading still shows the confirmation dialog (3-second cooldown); the Admin Panel's back button is now centered
- Bidding below the current price + min increment now shows a red hint under the button and plays a fail sound (previously silent, and the coin sound played by mistake)
- Quantity input limit raised from 3 to 4 digits: item sell count, item buy count, and auction item count now accept up to 9999
- New entries in the ban, blacklist, and price limit screens appear at the top (newest first) for easier management
- Auction House and buy order lists also show the newest first (live new listings insert at the top)
- Entry screen and Admin Panel backgrounds redrawn and displayed larger, several textures upgraded, no jump when switching between them; button layout unchanged
- Entry screen layout tweaks: title bolded and moved up, row spacing tightened, divider line and market-closed banner repositioned
- Admin Panel title aligned with the entry screen (gold + bold), button layout improved, back button centered when Cobbreeding is not installed
- Pagination layout revamp (pokemon/Item Markets, admin lists): a symmetric divider line added below the list, prev/next buttons no longer cover the bottom border
- Fixed the last background slice covering the rounded corners of the bottom border in all 15 three-part screens
- Buy order and auction panel textures consolidated and upgraded: shared textures, duplicates removed
- Buy order entry button icon sharpened to match the market master switch
- Divider line added between the button row and the record list in the transaction history screen (both personal and all-history views)
- Transaction history CSVs gain a "Details" column: full Pokémon stats (level/shiny/IVs/hyper training/nature/ability/gender/ball/held item/form) and item NBT as text, so compensation can recreate items faithfully from the ledger
- Price units and currency names are now unified across all modes: virtual currencies (Cobblemon Economy POKE/PCO, CobbleDollars) show only ₽ everywhere — inline, dialogs, hovers, and chat messages no longer display names like PCo/PokeDollars/PokeCoins; item currency still shows the item name
- Added a ball-type text label to hover panels and confirmation dialogs (addon balls are recognizable at a glance)

### Fixes

- **Item icons and some Pokémon model icons pierced through dialog masks**: present in existing screens (market/auction/admin) since beta.1; item icons are now hidden while dialogs are open (render-layer limit), and Pokémon 3D icons are dimmed via color parameters
- **New item blacklist entries still appeared at the bottom of the list** (the send path was missing the reverse); re-adding the same entry in blacklist/ban/price limit now moves it to the top instead of keeping its old position
- **Buy order creation dialog validation messages were darkened by the dialog overlay** (now rendered above the overlay)
- **Int overflow in fee calculation (auction settlement, seller notification, pokemon listing)**: price × feePercent could wrap around above ~214.7M, producing a negative or zero fee (fee evasion, phantom seller credit, or data corruption in the extreme case) — now computed in Long
- **Sustained FPS drops while market screens are open**: they stay smooth no matter how many listings there are
- **Garbled seller notification after a buy order delivery was accepted**: the message template has 5 placeholders but only 4 args were passed, with the amount/currency order swapped (mixed-up amounts and leftover %s)
- **First row's 3D icon in the Pokémon picker always showed the first party Pokémon after searching** (buy order delivery and auction creation — same root cause): the filtered-position index was used to look up the icon cache built with original list indices; filtering now keeps the original index
- **False "CobbleMarket state save failed" error when players log out**: on NeoForge, persistent state writes are asynchronous, so verifying right after saving misreported failures; verification is now delayed, and saves are skipped entirely when there is nothing unsaved
- **Purchase success messages showed amounts in green instead of the standard gold**: the %d placeholders dropped the text color; they now use %s with gold-formatted amount text
- **Rapid page-turning in market screens permanently grayed out the prev/next buttons and left stale content**: paging now merges clicks into a target page — each click updates the page number immediately (instant feedback), requests queue behind the server-side throttle window (pokemon market 250ms, Item Market/Pending Claims 500ms), and rapid clicks only send one request for the final page; a 1-second response timeout also force-resets the in-flight flag, so a silently dropped request can no longer lock the paging buttons (pokemon/Item Markets and both Pending Claims screens; the two admin screens also got the timeout fallback)
- **Custom Poké Balls from addon mods showed no icon or name** (previously blank due to hard-coded Cobblemon namespace)
- **Seller notification for admin force-cancellations**: Pokémon/Item Market listings force-cancelled by an admin now notify the seller with a dedicated red message (consistent with auctions and Buy Orders)

## 1.0.0-beta.6 (released)

### New Feature: Buy Orders

- Players can publish Buy Orders: a Pokémon (always 1) or items (any count) with a unit price range, visible to everyone
- Pokémon orders support: species (blank = any Pokémon), shiny (3 states), hyper training (3 states), exact IV requirements for all 6 stats, form, ability, and nature
- Fund model: publishing freezes "max unit price × quantity"; fills settle at the actual price with the difference refunded; closing or expiry auto-refunds the remaining frozen money
- **Buyer confirmation**: deliveries first enter a "pending" state (goods held in escrow, no funds moved, persisted in NBT across restarts); the buyer reviews the full item details and accepts (settles the trade) or rejects (goods return to the seller, order stays open); rejection supports an optional reason shown to the seller: "Your delivery of X to Y was returned. Note: ..."
- New deliveries are locked while one is pending (prevents overselling); if the buyer never responds, goods return automatically when the order expires
- Multiple sellers can partially fill an item order (per-item settlement); the order closes automatically once fully filled
- Seller delivery: Pokémon via the sell-selection screen in delivery mode with live match/mismatch pre-check; items via direct count and price input; server re-validates (ban/blacklist/egg switch/price limits — Pokémon matched by the delivered Pokémon's form/V-count/shiny/HT dimensions, items by unit price) before submitting
- Buy Orders support an optional buyer note (extra requirements); shown in a divider block in the hover panel and in the delivery dialog
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
- Pokémon acquisition celebration: **buying a Pokémon, winning an auction, or accepting a buy order delivery** plays a bouncing-ball animation of that Pokémon on the receiver's screen (synced with the gavel bell for auctions), visible over any screen; multiple Pokémon obtained in one batch play one after another; **two layers of switches**: server owners can disable it globally via the `celebrationAnimationEnabled` config (on by default), and players get per-scenario toggles under the **Settings** button at the bottom-right of the market entry screen — one for **market purchases** and one for **auctions / Buy Orders**
- The ban screen's player name input now suggests names from every player who ever logged into this save (including offline, from usercache) — type a prefix and pick, no more mistyped names
- **Market master switch**: server owners can shut down the whole market in an emergency (exploit, maintenance) via the bottom-center switch button on the entry screen (OP only, dual-state icon, same size as the buy-order/settings buttons) or in-game `/market off` (`/market on` to restore), config `marketEnabled` (on by default); **the button asks for confirmation before shutting down** (3-second cooldown with red/white warning lines, while restoring takes effect immediately with no dialog); while closed, all buy/sell/auction/buy-order operations are blocked with a "market closed" notice, and players' entry screens update in real time (state sent on join and broadcast on toggle) with a red banner; **players cannot enter the market screens while it is closed, so Pending Claims, balance collection, and cancelling listings must wait until the market reopens** (assets are never lost and everything is intact after reopening); **OPs keep management access** (Admin Panel stays available during closure for force-removal, blacklist and price-limit cleanup)

### Changes

- Config files self-update: new keys missing from old configs are filled in with defaults on load, so server owners no longer need to delete the config when upgrading
- Auction duration buttons on the create-auction screen now read the server's actual configuration: server owners can set any number of duration options with any values, and what players see always matches what actually settles
- Icon buttons at the bottom of the entry screen: **Buy Orders at the bottom-left** (opens the buy-order screen), **settings at the bottom-right** (opens the settings dialog, currently holding the two celebration animation toggles — market purchases and auctions / Buy Orders; future client-side personal settings all go here), and the **market master switch** bottom-center (OP only, dual-state icon); a divider line sits above the three small buttons, and rows 1/2 are tightened to give the bottom row breathing room
- Unified dialog look: the list below stays visible and dimmed under the dialog mask (Pokémon 3D icons dimmed in sync), with an opaque backing behind dialog backgrounds for a clean dialog area
- Price display polish: buy-order price ranges use k/M/B abbreviations above 10,000 (full value in hover panel); balances and pending balances use B once they reach 1 billion (full value below)
- Unified currency units: popups and hover panels now always show the currency name with prices (PokeDollars in CobbleDollars mode, the localized item name in item mode) in the same blue as the inline diamond symbol; inline rows keep the ◆ symbol to save space
- All type-colored Pokémon names across screens now render with a shadow (dark type colors stay readable on gray row backgrounds)
- Pokémon Market page button spacing optimized to match the admin listing screen, showing one extra list row at some window heights

### Fixes

- **Trade data loss when the server shuts down abnormally (killed process / crash)**: listed Pokémon or items could vanish — market data is now force-saved within seconds after every trade and immediately when a player disconnects, no longer relying on the autosave cycle; online OPs get a red-text alert if a save ever fails

## 1.0.0-beta.5 (released)

### New Feature: Cobblemon Utility+ Support (Hyper Training)

- Hyper-trained IVs now display correctly: market, Auction House, listing and pending-claim screens show "real value (trained value)", e.g. 12（31） — the same format as the party details screen
- New hyper-training filter (3-state cycle: Any / No HT / HT Only): available on the Pokémon market, the admin all-listings page, the Auction House Pokémon tab and both listing screens
- IV checks now match effective values: trained-to-31 and natural 31 are equivalent (search filters, price-limit V counts and blacklist IV matching all use effective values)
- Pokémon blacklist and price limit rules gain a "hyper training" dimension: a rule can be "No HT" (applies only to untrained Pokémon, preventing trained 6V Pokémon from bypassing rules based on real IVs); existing rules load as "Any" (unchanged behavior); both screens get a 2-state list filter and tooltip display

### Changes

- Merged the Pokémon and item blacklists into a single "Blacklist" screen: Pokémon/Items tabs (same layout as Price Limits); the two entry buttons in the Admin Panel are now one
- Pokémon blacklist rules can now be edited: a new "Edit" button per row opens a dialog pre-filled with all fields (species / IVs / form / shiny / hyper training), and saving replaces the rule
- Pokémon price limits now support forms: a rule can target all forms / the default form / specific forms, and both listing and auction starting-price checks match by form; rows and tooltips show the form
- Auction House tabs reordered to Mine / Pokémon / Items, with the Rules button joining the tab row (centered as a group)

### Fixes

- **"Unban All" button in the item blacklist dialog**: it stayed clickable while the dialog was open, and its visibility did not refresh when blacklist data arrived
- **Long Pokémon blacklist rows overlapped the remove button**: they are now truncated with an ellipsis (full details remain in the hover tooltip)
- **Old price limit entry left behind after editing**: changing the Pokémon (species / V count / shiny / form) or item and saving now replaces the old entry correctly
- **Pokémon holding a blacklisted item could bypass the item blacklist**: they can no longer be listed on the market or Auction House; held-item price limits now merge into the total price: lower bounds add up, upper bounds add up only when both sides are set

## 1.0.0-beta.4 (released)

### New Feature: Auction House

- **Auction House**: Pokémon / Items / Mine tabs, real-time countdown (seconds shown in the last 3 minutes), seller avatars, full row info (ball / colored species name / shiny star / gender / held item)
- **Create auction**: Pokémon (search / IV / shiny / type filters, same as regular listing) + Items (inventory scan) dual tabs; starting price validated against price limits (items scaled by unit price × quantity); min increment can be blank (server default); duration options; max 3 concurrent auctions per player (Pokémon + items combined, configurable)
- **Bidding**: bids charged instantly; outbid amounts auto-returned to pending balance (yellow notice); raising your own bid only tops up the difference; cannot bid on your own auction; min increment enforced
- **Anti-snipe**: bids within the last 120 seconds (configurable) reset the end time; bids after the end are always rejected
- **Settlement**: settles automatically on expiry (no need to open the Auction House — the server checks every second); winner's item goes to Pending Claims, seller receives final price minus fee (configurable 0~100%); no bids = returned to the seller; finished auction records are cleaned up automatically; seller and winner get chat notifications
- **Auction sounds**: coin sound on bid confirm; three crescendo gavel knocks at 10s / 6s / 3s (with hammer icon animation in the row); final gavel + bell on settlement. Sounds are sent only to the seller and bidders — bystanders are not disturbed
- **Rules button**: hover tooltip in the Auction House with full rules (gold headers / white text / red highlights / dividers, bilingual)
- **OP force-cancel**: new "Auctions" page in the Admin Panel (search / full row info / tooltips / two-column confirm dialog matching the Auction House) — click any auction to force-cancel it (item returns to the seller's Pending Claims, the current bidder is fully refunded, removed across the server)

### New Feature: Egg Trading (Cobbreeding Compatibility)

— Pokémon eggs can be listed on the market and Auction House: they previously failed to list because the ever-changing hatch timer data (timer/second components) never matched between the listing and the inventory item — now supported; different eggs are strictly distinguished, no mix-ups

- Eggs in listings never hatch, and buyers receive them with the same hatch progress shown at listing time
- The listing screen shows the live hatch time (consistent with the inventory screen) and removes hatched entries automatically
- Egg trading switch: off by default; toggle in the Admin Panel, enabling requires a second confirmation (3-second cooldown + red risk warnings: eggs bypass the Pokémon blacklist, and with encryption off they can be pre-filtered before hatching); listing, buying and bidding on eggs are all rejected while disabled (takes effect immediately, including existing listings)
- Blacklist integration: the blacklist takes priority over the switch (fine-grained per-variant bans), with batch ban/unban support; blacklisted eggs in existing listings can no longer be traded

### Changes

- Unified price display across all screens: `amount ◆` in currency blue (rows / tooltips / dialogs / history / Pending Claims)
- Global balance display: entry / market / Item Market / Auction House / create auction screens show live balance (auto-refreshed after trades); pending balance stays green
- "Expired Returns" renamed to "Pending Claims"; row info and tooltips aligned with the Pokémon market (ball / gender / held item / type color)
- Item Market now shows remaining stock when a purchase exceeds available quantity (concurrent buying)
- Auction sales recorded in transaction history (in-game history + local Chinese/English CSV), species names properly localized
- Adjusted row / tooltip hover and selected state colors (row_background.png texture)
- Unified "Pokemon" to the official "Pokémon" spelling in English texts (UI and config comments)
- Added icons to entry panel buttons (Pokémon Market / Item Market / History / OP Only), matching the Auction House button style
- Admin Panel: added the "Auctions" entry
- Item blacklist supports batch ban (one-click add all search matches, e.g. every egg variant)
- Config comments improved: max auction limit notes "Pokémon + items combined" and performance advice for crowded servers
- Market price input limit relaxed to 9 digits (consistent with auction and price limit fields)
- Item Market and admin "all listed items" page capacity raised from 30 to 84 items: bigger windows show more per page with less paging (smaller windows show fewer)
- Hover panels and confirm dialogs now show a "Ball:" text line (custom balls are recognizable at a glance)

### Fixes

- **Pending Claims screen showed raw translation keys instead of localized species names** (also affected regular listing returns)
- **English-mode text overflow**: shortened the claims button label
- **Currency names followed the server's language instead of the player's**: UI and chat now use each player's own language
- **Buying/cancelling could mis-deduct identical items from armor or offhand** (rare case): only the main inventory is touched now
- **Expired listings lingered for over ten seconds** (and could still be bought): they are now taken down immediately
- **Stale remove/edit buttons left after searching in blacklist and price limit screens** (only cleared after clicking or scrolling): row buttons now rebuild immediately as the search text changes
- **Searching by name in the Item Market and admin screens only filtered the current page** (targets on other pages couldn't be found without paging manually): search is now server-side global filtering, matching the Pokémon market — results appear on the first page immediately

## 1.0.0-beta.3 (released)

### New Feature

- **Price limits**: a new "Price Limits" entry on the Admin Panel, managed with Pokémon / item tabs — Pokémon rules cover four dimensions (species, blank = all Pokémon; IV count, any or exactly 0~6 perfect IVs; shiny filter, any / shiny only / non-shiny only; min / max price, either side optional); item rules are item + min / max price. When several rules match, the strictest intersection applies and over-limit prices are rejected at listing time (existing listings and purchases are unaffected); rules can be added, edited (adding the same combination overwrites it) and deleted; the data persists and is included in the save backup chain
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
