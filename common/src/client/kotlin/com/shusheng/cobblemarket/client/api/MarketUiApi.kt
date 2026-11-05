package com.shusheng.cobblemarket.client.api

import com.shusheng.cobblemarket.client.MarketStateCache
import com.shusheng.cobblemarket.screen.BuyOrderScreen
import net.minecraft.client.MinecraftClient

/**
 * **给附属模组用的客户端 UI 入口 API**（2026-11-06 新增，1.3.6）。
 *
 * 与 [EntryButtonApi] 同一层：主模组的界面由主模组打开，附属模组只调用这里的公开方法，
 * **不要直接 new 主模组的 Screen、也不碰它的内部字段**。这样界面内部怎么改，
 * 附属模组都不用跟着改代码。
 *
 * 规则：
 * - 只能在**客户端**调用（主模组这些界面是客户端界面）。
 * - **不新增网络包**：预填只是把界面控件填好，提交仍走原有 C2S，服务端会照常校验。
 * - **签名一旦发布就不要再改**（外部模组编译期依赖）；要扩展就新增方法 / 重载。
 */
object MarketUiApi {

    /**
     * 打开「求购单 → 创建精灵求购单」界面，并预填物种与形态；玩家只需填价格等其余项。
     *
     * @param client 客户端实例
     * @param species 物种名（`Pikachu`）或资源 id（`cobblemon:pikachu`）都可以，大小写不敏感
     * @param formAspects 形态的 Cobblemon aspects 集合（如 `setOf("alolan")`）；
     *        **空集合 = 默认形态 / 不限形态**。⚠ 只支持求购单筛选里可选的那些形态
     *        （战斗专属形态 / 超极巨化不在候选里，传进来会退回「全部形态」）
     * @return `false` = 客户端不在游戏内 / 市场关着 / 求购单-精灵类型被关闭（此时不应打开界面）
     *
     * ⚠ 预填只是便利：物种/形态以界面上的填写为准，最终由服务端校验。
     */
    fun openBuyOrderCreate(
        client: MinecraftClient,
        species: String,
        formAspects: Set<String> = emptySet(),
    ): Boolean {
        if (client.player == null) return false
        if (!canCreatePokemonBuyOrder()) return false
        client.setScreen(BuyOrderScreen(initialCreatePrefill = BuyOrderCreatePrefill(species, formAspects)))
        return true
    }

    /** 当前是否允许创建**精灵**求购单（市场总开关 / 求购单总开关 / 精灵类型开关都开）。附属模组可据此隐藏入口按钮。 */
    fun canCreatePokemonBuyOrder(): Boolean =
        MarketStateCache.enabled &&
            MarketStateCache.buyOrderEnabled &&
            MarketStateCache.buyOrderPokemonEnabled
}

/**
 * [MarketUiApi.openBuyOrderCreate] 的预填参数。
 *
 * ⚠ 附属模组**不要直接构造 `BuyOrderScreen`**，用 [MarketUiApi.openBuyOrderCreate]；
 * 这个类只随 API 方法一起出现，方便以后扩展字段（加字段只能加在末尾并给默认值）。
 */
data class BuyOrderCreatePrefill(
    val species: String,
    val formAspects: Set<String> = emptySet(),
)
