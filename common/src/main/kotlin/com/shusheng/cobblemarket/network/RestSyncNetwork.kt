package com.shusheng.cobblemarket.network

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.onPlayerJoin
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * 物种作息表同步（图标睡眠姿态用）。
 *
 * **为什么需要自己同步**：Cobblemon 的 `PokemonBehaviour.encode` 只写 `combat` 一个字段，
 * `resting`（能否睡 / 什么时段睡）根本不在网络同步内容里 —— 客户端拿到的永远是构造默认值
 * （`canSleep = false`），只有单机（同 JVM 共享服务端对象）才碰巧是对的。
 * 所以图标睡眠在专用服务器上会全域失效，必须由我们把这张表补发给客户端。
 *
 * 只发作息**不为默认**的字段其实没必要区分：默认的 `["night"]` 也是真实作息，客户端得知道。
 * 数据量按「能睡的物种」（约四成）收敛，登录时发一次。
 */

/** C2S：请求作息表（界面打开时兜底，防登录推送丢包） */
class RequestSpeciesRestPayload : CustomPayload {
    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<RequestSpeciesRestPayload>(CobbleMarket.id("request_species_rest"))
        val CODEC: PacketCodec<PacketByteBuf, RequestSpeciesRestPayload> = PacketCodec.of(
            { _, b -> b.writeInt(0) },
            { b -> b.readInt(); RequestSpeciesRestPayload() }
        )
    }
}

/** S2C：能睡的物种 → 作息时段（扁平化的「起点,终点」对，一个物种可有多段） */
data class SpeciesRestPayload(val entries: List<Entry>) : CustomPayload {

    data class Entry(val speciesId: String, val pairs: List<Int>)

    override fun getId() = ID
    companion object {
        val ID = CustomPayload.Id<SpeciesRestPayload>(CobbleMarket.id("species_rest"))
        val CODEC: PacketCodec<PacketByteBuf, SpeciesRestPayload> = PacketCodec.of(
            { p, b ->
                b.writeInt(p.entries.size)
                p.entries.forEach { e ->
                    b.writeString(e.speciesId)
                    b.writeInt(e.pairs.size)
                    e.pairs.forEach { b.writeInt(it) }
                }
            },
            { b ->
                val n = b.readInt()
                SpeciesRestPayload(List(n) {
                    val id = b.readString()
                    val m = b.readInt()
                    Entry(id, List(m) { b.readInt() })
                })
            }
        )
    }
}

object RestSyncNetwork {

    /** 默认 `drowsyChance` 是 1/30（约 0.033）；明显低于它的物种在原版里几乎不睡 */
    private const val LOW_DROWSY_THRESHOLD = 0.01f

    /** 借给「全天作息」物种的时段：Cobblemon 的 `night`（`TimeRange.timeRanges["night"]`） */
    private val NIGHT_RANGE = 12542..23459

    fun register() {
        registerS2CType(SpeciesRestPayload.ID, SpeciesRestPayload.CODEC)

        registerC2S(RequestSpeciesRestPayload.ID, RequestSpeciesRestPayload.CODEC) { _, player ->
            if (!RequestThrottle.allow(player.uuid, "request_species_rest", RequestThrottle.READ_INTERVAL_MS)) return@registerC2S
            sendToPlayer(player, SpeciesRestPayload(entries()))
        }

        // 登录补发：客户端据此建表，图标睡眠才有依据
        onPlayerJoin { player ->
            sendToPlayer(player, SpeciesRestPayload(entries()))
        }
    }

    /**
     * 遍历全部物种收集作息：只收 `canSleep` 的（不能睡的客户端查不到表 → 不显示睡姿）。
     *
     * **不缓存**：物种数据要等数据包加载完才准，缓存下来 `/reload` 后就不生效了；登录时现扫一遍
     * 一千多个物种只是内存遍历，开销可忽略。
     *
     * **`times` 覆盖全天的怎么处理**：Cobblemon 里 `["any"]`（0..23999）的含义是「任何时候都可能打盹」，
     * 不是「整天都在睡」—— 真正睡不睡由每秒掷一次的 `drowsyChance` 决定，而图标是静态判定、
     * 复刻不了概率（真做出来会忽睡忽醒）。所以按概率分档：
     * - 明确调低了 `drowsyChance` 的（如暴鲤龙 0.0014，原版平均十几分钟才困一次）→ **不收**，不显示睡姿
     * - 用默认值（1/30，原版"常困"）的（如卡比兽、凯西）→ 借给它 `night` 时段，晚上睡
     */
    private fun entries(): List<SpeciesRestPayload.Entry> {
        val list = mutableListOf<SpeciesRestPayload.Entry>()
        PokemonSpecies.species.forEach { species ->
            val rest = species.behaviour.resting
            if (!rest.canSleep) return@forEach
            val ranges = rest.times.ranges
            val effective = if (isFullDay(ranges)) {
                if (rest.drowsyChance < LOW_DROWSY_THRESHOLD) return@forEach
                listOf(NIGHT_RANGE)
            } else {
                ranges
            }
            list += SpeciesRestPayload.Entry(
                species.resourceIdentifier.toString(),
                effective.flatMap { listOf(it.first, it.last) }
            )
        }
        return list
    }

    /** 是否覆盖全天（`TimeRange(0..23999)`，即 json 里写的 `"any"`） */
    private fun isFullDay(ranges: List<IntRange>): Boolean =
        ranges.size == 1 && ranges[0].first <= 0 && ranges[0].last >= 23999
}
