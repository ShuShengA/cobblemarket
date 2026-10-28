package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.currency.MarketCurrency
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
    val currencyName: String,
    /**
     * 这条挂单的币种（枚举名，服务端权威值）。
     *
     * ⚠ **币种是挂单自己的属性，不是"这一屏"的属性** —— 界面显示价格单位必须读它，
     * 别读界面上的什么"当前币种"状态：那样任何一处漏改就会把 RP 商品标成主货币。
     * 客户端解析走 `MarketCurrency.fromStored`（服务端发的，容错即可，别用 fromRequest）。
     *
     * **故意不给默认值**：服务端哪一处漏填就编译不过，好过静默当成主货币。
     */
    val currency: String,
    /**
     * **客户端视角的物品 NBT**（只有 Polymer 等「客户端伪装物品」才有值，普通物品为 null）。
     *
     * 服务端真实物品（如 `mega_showdown:x`）客户端根本没有注册表项 ⇒ 拿 [itemNbt] 渲染会变成
     * Air / 没图标（2026-10-28 用户实测）。列表显示与悬停一律用 [renderNbt]；
     * **交易逻辑（购买 / 扣物 / 黑名单 / 限价）一律用 [itemNbt]**，别混。
     * ⚠ 末尾追加（协议铁律）⇒ 两端同版
     */
    val displayNbt: NbtCompound? = null
) {
    /** 渲染 / 悬停用的物品 NBT：优先客户端视角；旧挂单 / 普通物品回退真实物品 */
    fun renderNbt(): NbtCompound = displayNbt ?: itemNbt
    /**
     * 这条挂单是不是 RP 计价 —— **渲染货币单位就读它**。
     * 构造时解析一次（渲染每帧每格都判断，别每帧重新 parse 字符串）。
     */
    val isPoints: Boolean = MarketCurrency.fromStored(currency) == MarketCurrency.POINTS

    fun write(buf: PacketByteBuf) {
        buf.writeUuid(id)
        buf.writeUuid(sellerUuid)
        buf.writeString(sellerName)
        buf.writeString(itemId)
        PacketCodecs.NBT_COMPOUND.encode(buf, itemNbt)
        buf.writeInt(count)
        buf.writeInt(price)
        buf.writeString(currencyName)
        buf.writeString(currency)
        buf.writeBoolean(displayNbt != null)
        displayNbt?.let { PacketCodecs.NBT_COMPOUND.encode(buf, it) }
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
            buf.readString(),
            buf.readString(),
            displayNbt = if (buf.readBoolean()) PacketCodecs.NBT_COMPOUND.decode(buf) else null
        )
    }
}
