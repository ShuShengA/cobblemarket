package com.shusheng.cobblemarket.overview

import net.minecraft.nbt.NbtCompound
import java.util.UUID

/**
 * 全服玩家总览的一行 = **悬停窗的完整内容**（方案第二节那张表）。
 *
 * 界面上的「行」只画名字 + 当前排序字段的值，其余字段全部在悬停窗里展开
 * （照「悬停窗 = 行的完整版」那条既有约定）。
 *
 * ⚠ **加了字段必须同步改 `PlayerOverviewNetwork` 的 codec 读写两端**（照 `BanChangesPayload` 的写法，
 * 读端一律具名传参）—— 顺序写错会静默串位，编译不报。本功能随 1.2.2 首发，两端同版。
 */
data class PlayerOverviewRow(
    val uuid: UUID,
    val name: String,
    val online: Boolean,

    // ── 市场 ──
    val pokemonListings: Int,
    val itemListings: Int,
    val activeAuctions: Int,
    val openBuyOrders: Int,
    val pendingPokemon: Int,
    val pendingItems: Int,
    val pendingBalance: Long,

    // ── 钱 ──
    /** 主货币余额；**null = 拿不到**（离线且还没记过快照 —— 老存档升级后第一次看到就是这样，界面显示「—」） */
    val balance: Long?,
    /**
     * **PCO 余额**（Cobblemon Economy 的 PokeCoins —— 与 PokeDollars 是**两套独立账本**）。
     * null = 没装 CE / 主货币本来就是 PCO（那行会重复）/ 读不到。
     * ⚠ CE 的接口收 **UUID** ⇒ **离线也能实时读**，不走快照。
     */
    val pcoBalance: Long?,
    /**
     * true = **余额与图鉴读的是离线快照**（界面给这两行标一句灰字「离线时记录」）。
     * 其余字段走的都是按 UUID 存的 State，离线也准 —— 只有这两个需要特殊处理。
     */
    val fromSnapshot: Boolean,

    // ── 金融 ──
    /** 可用额度（含人工修正，已扣欠款 —— 与金融管理界面同口径） */
    val creditLimit: Long,
    /** 未还本金（坏账不计） */
    val debt: Long,
    /** 未还利息（未结算部分）；界面上的「已借」= [debt] + [debtInterest] */
    val debtInterest: Long,
    val depositPrincipal: Long,
    val depositInterest: Long,
    val netDeposit: Long,
    val overdue: Boolean,
    val badDebt: Boolean,
    val purpleCard: Boolean,
    val blackCard: Boolean,

    // ── 状态 ──
    val banned: Boolean,
    /** 封禁到期时刻；null = 永久（没封禁时无意义，看 [banned]） */
    val banUntil: Long?,
    val loanBlocked: Boolean,
    val totalVolume: Long,

    // ── 图鉴 ──
    /** 图鉴遇见数；**null = 拿不到**（同 [balance]） */
    val dexSeen: Int?,
    val dexCaught: Int?,

    // ── RP（1.3.x 独有）──
    /**
     * RP 余额 —— **恒显示**（RP 总开关关着也照画，显示 0）：用户 2026-10-17 拍板「0 也显示」。
     * ⚠ 别按 [pcoBalance] 那种「没这个概念就整行不画」处理 —— 这一行总是要在的。
     *
     * ⚠ 与 [balance] **正好相反**：RP **永远实时**（在线的、离线的都准）—— 它存在自己的
     *   PersistentState 里、玩家离线期间不会有任何东西动它（主货币那套才会被别的模组改）
     *   ⇒ **不进快照**，也不区分在线离线。
     */
    val pointsBalance: Long,

    // ── 装备 / 经验（2026-10-28，**只用于分享到聊天栏的 ID 卡**）──
    /**
     * 身上四件装备的 NBT（`ItemStack.encode` 原样保真）；null = 没穿 / 拿不到。
     * ⚠ 只有「分享 ID 卡」那一刻由分享者的在线数据填充 —— 全服总览列表不填（省流量），
     *   老客户端 / 老数据的默认值同样是 null。
     */
    val headItem: NbtCompound? = null,
    val chestItem: NbtCompound? = null,
    val legsItem: NbtCompound? = null,
    val feetItem: NbtCompound? = null,
    /** 经验等级 / 进度（0~1）；分享卡底部画经验条用 */
    val experienceLevel: Int = 0,
    val experienceProgress: Float = 0f,
)
