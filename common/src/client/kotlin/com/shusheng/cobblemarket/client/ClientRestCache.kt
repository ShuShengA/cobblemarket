package com.shusheng.cobblemarket.client

import com.shusheng.cobblemarket.network.SpeciesRestPayload

/**
 * 物种作息表（客户端侧，由服务端下发）。
 *
 * Cobblemon 的 `PokemonBehaviour` 网络同步只带 `combat`，`resting` **永远到不了客户端**
 * （单机因为同 JVM 共享服务端对象才碰巧正确），所以图标睡眠必须靠这张表 —— 详见 `RestSyncNetwork`。
 *
 * 表里没有的物种 = 不能睡（也包括表还没送达的情况，那时不显示睡姿，安全降级）。
 */
object ClientRestCache {

    private var table: Map<String, List<IntRange>> = emptyMap()

    fun update(entries: List<SpeciesRestPayload.Entry>) {
        table = entries.associate { e ->
            e.speciesId to e.pairs.chunked(2).filter { it.size == 2 }.map { it[0]..it[1] }
        }
    }

    /** 该物种此刻是否处于作息时段（[dayTimeMod24000] 由调用方取 `world.timeOfDay % 24000`） */
    fun isSleeping(speciesId: String, dayTimeMod24000: Int): Boolean =
        table[speciesId]?.any { dayTimeMod24000 in it } ?: false
}
