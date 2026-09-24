package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.item.ItemStack

/**
 * 列表行里一个精灵图标所需的**数据 + 跨帧状态**。
 *
 * 各界面此前各写一份 `private data class IconData`（`BlacklistScreen` / `AdminAuctionScreen` /
 * `AdminPokemonScreen` / `AuctionCreateScreen`，字段略有出入），2026-09-22 收到这里 ——
 * 新界面直接用它，不用再抄一遍（见 `docs/icon_animation.md`「九、新界面接入清单」）。
 *
 * ⚠ **`state` 必须跨帧常驻**（存进 map 或字段），**不能每帧 new** —— 动画进度就存在它里面；
 *   刷新列表数据时**重建**（换新的 `FloatingState` = 这个图标的动画从头开始），这正是想要的。
 *
 * ⚠ **参数顺序按原调用点排的**（`displayName` 在前）—— 收口时四个界面都是**位置传参**，
 *   顺序定成这样才能一处都不改。新界面**建议全部用具名参数**，别再依赖位置。
 *
 * @param displayName  数据里带的显示名；用不到的界面省略（默认空串）
 * @param renderable   渲染用的精灵（物种 + 形态 + 携带物）
 * @param state        该图标自己的跨帧动画状态，默认新起一个
 * @param ballStack    球种栈缓存 —— render 每帧 new `ItemStack` 是分配热点，数据到达时构建一次复用。
 *                     不画球种 / 携带物的界面留 null
 * @param heldStack    携带物栈缓存，同上
 */
data class PokemonIconData(
    val displayName: String = "",
    val renderable: RenderablePokemon,
    val state: FloatingState = FloatingState(),
    val ballStack: ItemStack? = null,
    val heldStack: ItemStack? = null,
    /**
     * 这条数据对应的**挂单 id**（有稳定 id 的界面传它，用于重建时复用 [state]）。
     *
     * ⚠ 为什么需要：筛选 / 排序 / 翻页都会让列表重新构建，若一律 `clear()` + 全新建，
     *   每条的 [state]（动画进度 + 头部跟随的跨帧平滑值）都会归零 —— 表现就是
     *   「点一下筛选类按钮，精灵的头转回去再立刻转回来、动画还顿两下」。
     *   按 id 复用即可让同一只精灵的动画跨帧延续。
     * 不传（null）= 不参与复用，行为与改造前一致。
     */
    val listingId: java.util.UUID? = null
)
