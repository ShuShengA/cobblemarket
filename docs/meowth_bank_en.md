# Meowth Bank Guide (for Server Owners)

This document explains Meowth Bank's fund model, repayment and sanction mechanics, credit limit calculation, anti-abuse design, and every owner-facing config and tool.

## 1. What Meowth Bank is

Credit purchasing for the market: players borrow future money to spend now, injecting liquidity into the server economy — and giving owners a set of operating levers (rates, limits, sanctions are all configurable).

Two features share one credit pool:

| Feature | Entry | Money flow |
|---|---|---|
| Emergency loan (Meowth's Help) | Counter loan inside Meowth Bank | Money goes to the player's wallet, repaid in installments |
| Meowth Pay (consumer credit) | The "Meowth Pay" button in Pokémon/item purchase dialogs | The reserve pool pays the seller directly; the buyer repays in installments |

## 2. Fund model: the reserve pool

- The reserve pool is the single gateway for all loan money (`FinanceState`, persisted across restarts)
- Lending / Meowth Pay → money leaves the pool (to the player/seller); principal and interest repayments return 100% to the pool
- **The pool may go negative** (= owner debt, shown as a red alert in the admin panel); owners never need to pre-fund it
- Bad-debt write-off does not move the pool (the money already left at lending time; the loss is already reflected)

## 3. Loans and repayment

- Each loan splits into **3/6/12 installments, one every 7 days**, with fixed due dates (day 7/14/21… after borrowing — early repayment never postpones them)
- Interest = remaining principal × daily rate × days since last repayment (daily rate = per-period fee ÷ 7, snapshotted at creation; config changes don't affect existing loans)
- Each due installment is auto-deducted in full from the player's balance, keeping a **minimum balance** (`autoRepayMinBalance`) so wallets are never emptied; insufficient funds → overdue
- Players may repay one installment or settle early at any time (interest calculated to the day)
- A yellow reminder arrives one day before each installment is due

## 4. Three-tier overdue sanctions

| Overdue | Sanction | How it lifts |
|---|---|---|
| Any | Overdue status: new loans / Meowth Pay blocked, red notice | Catching up on installments restores it |
| ≥ 7 days | Market fees doubled (for the payer) | Drops back under 7 days |
| ≥ 14 days | Market trading frozen (reuses the ban system — blocks trading, never asset claims) | Repayment unfreezes automatically |
| ≥ 30 days | Written off as bad debt: principal unrecoverable, trading stays frozen, OPs get an alert | Owner intervention (see §7) |

- Sanctions recompute in real time after every repayment: one installment paid reduces overdue days by 7, downgrades apply immediately
- Bad-debt players stay frozen permanently. Owners have two levels of intervention: **unban only** (trading restored, borrowing still blocked) or **revoke the bad debt** (trading and borrowing fully restored)

## 5. Credit limit (credit-card model)

```
Credit base = max(last-30-day buying volume × 0.5 + all-time buying volume × 0.1, limit min)
Available limit = max(0, credit base − total outstanding debt), then clamped to limit max
```

- **Debt reduces the limit fully**: borrow 200, 800 remains; repayment restores it — the limit is a pool of spendable credit
- Volume counts the **buyer** side only (borrowing measures spending power); it scales automatically with the server's economy level
- Three anti-abuse layers (snapshotted at trade time, silently excluded from the volume):
  1. Meowth Pay trades never count; buying trades never count while the buyer has any open loan (blocks borrow→buy→limit-up→borrow loops)
  2. After 3 trades between the same buyer-seller pair within 30 days, later trades between them don't count
  3. Same IP on both sides (buyer not OP) → the trade counts ×0.9
- **Same-IP debt cap** (`ipDebtLimit`): total outstanding debt of every player who used this IP within 30 days, plus the new loan, must not exceed the cap — blocks alt-army borrowing (OPs exempt; 0 = disabled)

## 6. Configuration (editable in the in-game Server Config screen)

| Config | Default | Notes & advice |
|---|---|---|
| `finance.enabled` | **off** | Master switch. Lending affects economic safety — owners opt in explicitly. When off, new loans/Meowth Pay are blocked but existing loan state machines keep running (repayment/overdue/bad debt unaffected) |
| `cashLoanEnabled` | on | Cash loan (Meowth's Help) switch |
| `consumerLoanEnabled` | on | Meowth Pay switch; when off, purchase dialogs shrink and the button disappears |
| `loanPlans` | 3/6/12 periods, fees 0.5%/0.8%/1.2% | Installment plan array (periods + per-period fee, 7 days each); changes only affect new loans |
| Limit weight · last 30 days | 0.5 | Credit formula weight (see §5) |
| Limit weight · all-time | 0.1 | Credit formula weight |
| Limit min / max | 0 / 100000 | Min only backs players **without** debt (cold start); with debt the limit follows the raw formula |
| `autoRepayMinBalance` | 1000 | Minimum balance kept by auto-deduct (balance never drops below it). Note: below this value auto-deduct **won't run** (goes overdue) — small economies should lower it or set 0 |
| `ipDebtLimit` | 100000 | Same-IP debt cap (see §5). Recommended: 1–3× the limit max; raise it for dorm/cyber-café servers to avoid false positives; 0 = disabled |
| Overdue sanction days | 7 / 14 / 30 | Thresholds for fee doubling / freeze / bad debt |

> All finance configs hot-apply immediately (new loans use new config; existing loans keep their snapshotted rates). The only exception remains the currency backend (restart required, same as the market).

## 7. Owner tools

- **All Loans** screen (Meowth Bank bottom-right, OP only): the server-wide loan ledger with each borrower's latest IP (alt spotting); a "Bad Debt" tab filters all bad debts; a "Revoke" button per bad-debt row (5-second cooldown confirmation dialog)
- **`/market loan clear <player>`**: the command twin of the revoke button
- **Admin panel alert line**: current reserve pool + total bad debt; a negative pool (= owner debt) renders the whole line red
- **Audit ledgers**: `config/cobblemarket/credit/` contains `loan_records_<date>_<lang>.csv` and `repayment_records_<date>_<lang>.csv` — Chinese and English copies, split by day, append-only; repayments split principal/interest with method (manual/auto/early)
- **Automatic cleanup**: repaid loans are purged from state 90 days after settlement (the audit CSVs keep full history forever), preventing save bloat

## 8. FAQ

### A player has enough balance but auto-deduct doesn't run?

The `autoRepayMinBalance` floor: the balance after deduction must stay above it, so nothing is deducted when the balance is below the floor (goes overdue instead). Lower it or set 0 for small economies.

### A player borrowed once and can never borrow again?

Limit = credit base − full debt. Borrowing 100 immediately reduces the limit by 100 until repaid — the credit-card model by design. A configured limit min only backs players without debt.

### Are same-IP players punished for each other?

`ipDebtLimit` aggregates the debt of every player who used that IP in the last 30 days. For dorm/cyber-café NAT sharing, raise the cap to 3× the limit max or set 0.

### IP features broken behind a tunnel (Sakura FRP etc.)?

Behind an FRP tunnel every player shows the same IP (127.0.0.1): the same-IP cap becomes server-wide, the same-IP discount hits everyone, and IP-based alt spotting stops working. Fix: enable PROXY Protocol on the tunnel (Sakura FRP: add `proxy_protocol_version = v2` in the tunnel's custom settings) and install a supporting server mod (e.g. HAProxyCompat on Modrinth, works on both Fabric and NeoForge). If that's not possible, set `ipDebtLimit` to 0.

### What happens to a player after bad debt?

Trading is frozen and borrowing is permanently blocked. Owner intervention has two levels: unban via the ban screen (trading restored, borrowing still blocked), or revoke the bad debt (trading and borrowing fully restored; the audit ledger keeps a revoke event).

### How do I verify the system works?

- Borrow → yellow reminder one day before each due date → auto-deduct on the due date (sufficient balance) or red overdue notice (insufficient)
- Sanctions escalate at 7/14/30 days; repayment downgrades them in real time
- Check the reserve pool and bad-debt totals in the admin panel; reconcile via the audit CSVs

## 9. Safety and trust boundaries

- The reserve pool never creates money: lending leaves the pool, repayment returns to it, and bad debt only marks (the money already left)
- Bad debt = the owner takes the loss (money taken and never repaid); a negative pool = owner debt, red-alerted in the admin panel
- IP-based anti-abuse stops casual alts, not dedicated attackers behind proxies; the credit formula (new accounts have zero limit, volume-backed) and the sanction chain are the main defenses
- Turning the master switch off only blocks **new** lending — existing loans keep running, mirroring the market switch philosophy ("block new trades, never lock up assets")
