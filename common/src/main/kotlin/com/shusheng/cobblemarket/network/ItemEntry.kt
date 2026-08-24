package com.shusheng.cobblemarket.network

import net.minecraft.nbt.NbtCompound
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodecs
import java.util.UUID

data class ItemEntry(
    val id: UUID,
    val sellerUuid: UUID,
    val sellerName: String,
    val itemId: String,
    val itemNbt: NbtCompound,
    val count: Int,
    val price: Int,
    val currencyName: String
) {
    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeUuid(sellerUuid)
        buf.writeString(sellerName)
        buf.writeString(itemId)
        PacketCodecs.NBT_COMPOUND.encode(buf, itemNbt)
        buf.writeInt(count)
        buf.writeInt(price)
        buf.writeString(currencyName)
    }

    companion object {
        fun read(buf: PacketByteBuf) = ItemEntry(
            buf.readUuid(),
            buf.readUuid(),
            buf.readString(),
            buf.readString(),
            PacketCodecs.NBT_COMPOUND.decode(buf),
            buf.readInt(),
            buf.readInt(),
            buf.readString()
        )
    }
}
