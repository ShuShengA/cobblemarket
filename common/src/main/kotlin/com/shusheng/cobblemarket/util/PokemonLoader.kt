package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.DynamicRegistryManager

/**
 * 「NBT → 精灵对象」的唯一入口。市场/拍卖/待归还/租赁/黑市等所有从存档快照还原精灵的地方都必须走这里。
 *
 * 为什么不能用 `Pokemon().loadFromNBT(...)`：Cobblemon 有两个同名方法，语义不同——
 * - 实例方法走 `copyFrom`（逐字段复制），而 1.8.0 的 `copyFrom` 漏了 `isAlpha`，
 *   还原出的头目精灵会永久变成普通体型（存盘后 `Alpha` 写死 false，不可逆）；
 * - 伴生方法直接 `CODEC.decode`，忠实还原 NBT，Cobblemon 自己的 PCBox/PartyStore 也都用它。
 *
 * 本函数包装的正是伴生方法（解码失败抛异常，由调用方按各自场景处理，如购买失败提示、取回留在待领取）。
 * 判断其他 Cobblemon API 是否安全时可用同一判据：内部走 `CODEC.decode` 的安全（如 `Pokemon.clone()`），
 * 走 `copyFrom` 的要怀疑字段覆盖不全。
 */
object PokemonLoader {

    /** 从 NBT 还原精灵；数据损坏时抛异常，不返回残缺对象 */
    fun fromNbt(registryAccess: DynamicRegistryManager, nbt: NbtCompound): Pokemon =
        Pokemon.loadFromNBT(registryAccess, nbt)
}
