package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.ui.IdCardBackgroundImage
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/** 文件名长度上限（防解码期无界字符串；够放中文名 + 后缀） */
private const val MAX_FILE_NAME_LENGTH = 128

/**
 * 请求 ID 卡背景图（C2S）—— 客户端手里没有这张、或换过图名时才发。
 *
 * 传文件名而不是"给我背景图"：客户端据此判断手里那张是不是最新的
 * （服主换图后文件名变了 ⇒ 重新请求）；**没配图时根本不发这个包**。
 */
data class RequestIdCardBackgroundPayload(val fileName: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestIdCardBackgroundPayload>(CobbleMarket.id("request_idcard_bg"))
        val CODEC: PacketCodec<PacketByteBuf, RequestIdCardBackgroundPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.fileName, MAX_FILE_NAME_LENGTH) },
            { b -> RequestIdCardBackgroundPayload(b.readString(MAX_FILE_NAME_LENGTH)) }
        )
    }
}

/**
 * ID 卡背景图字节（S2C）。
 *
 * ⚠ **空数组 = 读不出来**（文件不存在 / 不是 PNG / 太大）：客户端据此**回退到模组内置的背景**，
 * 而不是留一块空白 —— 与项目里其它动态图同一个约定。
 *
 * ⚠ 用普通 class 而非 data class：`byte[]` 的 equals/hashCode 是引用比较，data class 会误导。
 */
class IdCardBackgroundPayload(val fileName: String, val bytes: ByteArray) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<IdCardBackgroundPayload>(CobbleMarket.id("idcard_bg"))
        val CODEC: PacketCodec<PacketByteBuf, IdCardBackgroundPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.fileName, MAX_FILE_NAME_LENGTH)
                b.writeByteArray(p.bytes)
            },
            { b -> IdCardBackgroundPayload(b.readString(MAX_FILE_NAME_LENGTH), b.readByteArray()) }
        )
    }
}

/**
 * ID 卡背景图的网络通道。
 *
 * ⚠⚠ **S2C 是两步注册，缺一不可**（项目为此栽过两次）：
 * 1. 这里的 `registerS2CType` —— 漏了**客户端启动即崩**
 *    （`Cannot register handler as no payload type has been registered with name ...`）
 * 2. 客户端 `CobbleMarketClient` 里的 `registerS2C` —— 漏了**静默无反应**
 *
 * ⚠ **NeoForge 不显形**：它的 `playToClient` 自带类型注册 ⇒ 漏第 1 步**只在 Fabric 上崩**。
 *  别拿 NeoForge 跑通当没问题。
 */
object IdCardNetwork {

    fun register() {
        // S2C：类型注册（客户端接收侧的 handler 在 CobbleMarketClient 里另注册一次）
        registerS2CType(IdCardBackgroundPayload.ID, IdCardBackgroundPayload.CODEC)

        // ⚠ C2S 必须写在**本函数体内** —— 插到别的函数里会编译通过但永不注册，第一次发包直接断连
        registerC2S(RequestIdCardBackgroundPayload.ID, RequestIdCardBackgroundPayload.CODEC) { payload, player ->
            // 读不出来就回空数组（客户端回退到内置背景）；「没配图」是正常状态，不报错
            val bytes = IdCardBackgroundImage.load(payload.fileName) ?: ByteArray(0)
            sendToPlayer(player, IdCardBackgroundPayload(payload.fileName, bytes))
        }

        // 确保图目录存在（服主打开 config 就知道图该往哪放）
        IdCardBackgroundImage.ensureDir()
    }
}
