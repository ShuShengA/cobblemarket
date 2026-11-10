package com.shusheng.cobblemarket.client

/**
 * 由客户端 mixin `PlayerEntityMixin` 实现，给普通客户端代码一个入口去打开玩家的**第二层皮肤**。
 *
 * ⚠ 必须放在**普通客户端包**，不能放 `com.shusheng.cobblemarket.mixin.client`：
 *   Mixin 会拒绝外部直接引用 mixin 专用包里的非 mixin 类（2026-11-10 实测启动崩溃：
 *   `is in a defined mixin package ... and cannot be referenced directly`）。
 *
 * 背景：客户端自己 new 的 `OtherClientPlayerEntity`（聊天分享 ID 卡的模型）的
 * `PLAYER_MODEL_PARTS` 默认是 0，渲染器会把第二层全部隐藏。
 */
interface PlayerModelPartsAccess {

    /**
     * 设置皮肤层位掩码（`PlayerModelPart` 的 bit 组合）。
     *
     * ⚠ **不要把 CAPE 位无条件打开**：分享者可能关闭了披风显示，Cosmetica 这类模组还会给
     *   没皮肤玩家强行挂披风（2026-11-10 用户实测）。传服务端下发的 `PlayerOverviewRow.modelParts`
     *   即可完整尊重分享者自己的设置。
     */
    fun setModelParts(mask: Int)
}
