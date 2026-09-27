package com.shusheng.cobblemarket.event

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import java.util.UUID

class TransactionHistory private constructor() : PersistentState() {

    private val records = mutableListOf<TransactionRecord>()

    fun addRecord(record: TransactionRecord) {
        records.add(0, record)  // newest first
        if (records.size > MAX_RECORDS) {
            records.subList(MAX_RECORDS, records.size).clear()
        }
        TransactionFileLogger.log(record)
        markDirty()
    }

    fun getRecords(): List<TransactionRecord> = records.toList()

    fun getRecordsBySeller(uuid: UUID): List<TransactionRecord> =
        records.filter { it.sellerUuid == uuid }

    fun getRecordsByBuyer(uuid: UUID): List<TransactionRecord> =
        records.filter { it.buyerUuid == uuid }

    fun getRecordsByPlayer(uuid: UUID): List<TransactionRecord> =
        records.filter { it.sellerUuid == uuid || it.buyerUuid == uuid }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        records.forEach { list.add(it.toNbt()) }
        nbt.put("records", list)
        return nbt
    }

    companion object {
        private const val MAX_RECORDS = 200

        private val TYPE = PersistentState.Type(
            { TransactionHistory() },
            { nbt, _ ->
                TransactionHistory().apply {
                    nbt.getList("records", NbtList.COMPOUND_TYPE.toInt()).forEach { element ->
                        try {
                            records.add(TransactionRecord.fromNbt(element as NbtCompound))
                        } catch (e: Exception) {
                            CobbleMarket.LOGGER.warn("Skipping corrupted transaction record: {}", e.message)
                        }
                    }
                }
            },
            null
        )

        fun get(server: MinecraftServer): TransactionHistory =
            server.overworld.persistentStateManager.getOrCreate(TYPE, "${CobbleMarket.MOD_ID}_history")

        fun register() {
            MarketEvents.ADD.subscribe { e ->
                // Need server access; defer via a static holder set during onInitialize
                historyRef?.addRecord(TransactionRecord(
                    timestamp = System.currentTimeMillis(),
                    type = TransactionType.ADD,
                    category = TransactionCategory.POKEMON,
                    sellerUuid = e.listing.sellerUuid,
                    sellerName = e.listing.sellerName,
                    buyerUuid = null,
                    buyerName = "",
                    species = e.listing.extraData["speciesKey"] ?: "cobblemon.species.${e.listing.species.lowercase()}.name",
                    price = e.listing.price,
                    fee = e.fee,
                    detail = com.shusheng.cobblemarket.util.RecordDetail.pokemon(e.listing.pokemonNbt, serverRef, e.listing.extraData, e.listing.level, e.listing.shiny),
                    // 精灵名上色用（形态感知的主属性，挂单时与 speciesKey 一起快照的）
                    primaryType = e.listing.extraData["primaryType"] ?: ""
                ))
            }
            MarketEvents.PURCHASE.subscribe { e ->
                historyRef?.addRecord(TransactionRecord(
                    timestamp = System.currentTimeMillis(),
                    type = TransactionType.PURCHASE,
                    category = TransactionCategory.POKEMON,
                    sellerUuid = e.sellerUuid,
                    sellerName = e.listing.sellerName,
                    buyerUuid = e.buyerUuid,
                    buyerName = e.buyerName,
                    species = e.listing.extraData["speciesKey"] ?: "cobblemon.species.${e.listing.species.lowercase()}.name",
                    price = e.price,
                    fee = 0,
                    detail = com.shusheng.cobblemarket.util.RecordDetail.pokemon(e.listing.pokemonNbt, serverRef, e.listing.extraData, e.listing.level, e.listing.shiny),
                    // 精灵名上色用（形态感知的主属性，挂单时与 speciesKey 一起快照的）
                    primaryType = e.listing.extraData["primaryType"] ?: ""
                ))
            }
            MarketEvents.CANCEL.subscribe { e ->
                historyRef?.addRecord(TransactionRecord(
                    timestamp = System.currentTimeMillis(),
                    type = TransactionType.CANCEL,
                    category = TransactionCategory.POKEMON,
                    sellerUuid = e.sellerUuid,
                    sellerName = e.listing.sellerName,
                    buyerUuid = null,
                    buyerName = "",
                    species = e.listing.extraData["speciesKey"] ?: "cobblemon.species.${e.listing.species.lowercase()}.name",
                    price = e.listing.price,
                    fee = 0,
                    detail = com.shusheng.cobblemarket.util.RecordDetail.pokemon(e.listing.pokemonNbt, serverRef, e.listing.extraData, e.listing.level, e.listing.shiny),
                    // 精灵名上色用（形态感知的主属性，挂单时与 speciesKey 一起快照的）
                    primaryType = e.listing.extraData["primaryType"] ?: ""
                ))
            }
            MarketEvents.RETURN.subscribe { e ->
                historyRef?.addRecord(TransactionRecord(
                    timestamp = System.currentTimeMillis(),
                    type = TransactionType.RETURN,
                    category = TransactionCategory.POKEMON,
                    sellerUuid = e.playerUuid,
                    sellerName = e.listing.sellerName,
                    buyerUuid = null,
                    buyerName = "",
                    species = e.listing.extraData["speciesKey"] ?: "cobblemon.species.${e.listing.species.lowercase()}.name",
                    price = e.listing.price,
                    fee = 0,
                    detail = com.shusheng.cobblemarket.util.RecordDetail.pokemon(e.listing.pokemonNbt, serverRef, e.listing.extraData, e.listing.level, e.listing.shiny),
                    // 精灵名上色用（形态感知的主属性，挂单时与 speciesKey 一起快照的）
                    primaryType = e.listing.extraData["primaryType"] ?: ""
                ))
            }
        }

        // Static holder set once the server is available, since events fire on server thread
        var historyRef: TransactionHistory? = null

    /**
     * CSV「详情」列重建精灵时要用的 server（取 `registryManager` 解 NBT + 解析初始训练家的玩家名）。
     *
     * ⚠ 为什么要有它：下面那 4 个事件订阅（`MarketEvents.ADD.subscribe { e -> ... }`）**只有事件对象、
     *   拿不到 server**，而 CSV 详情现在要写昵称 / 体型 / 极巨化 / 初始训练家 ——
     *   这些只有**重建精灵**才拿得到（`extraData` 快照里没有）。
     * ⚠ 与 [historyRef] **同生命周期**：`onServerStarted` 设置、`onServerStopped` 清空。
     */
    var serverRef: MinecraftServer? = null
    }
}
