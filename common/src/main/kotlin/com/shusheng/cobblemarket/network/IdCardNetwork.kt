package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.ui.IdCardBackgroundImage
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * 版本串长度上限（防解码期无界字符串）。
 * 版本 = `文件名@修改时间`：文件名本身限 128（够放中文名 + 后缀），mtime 是 13 位毫秒时间戳。
 */
private const val MAX_VERSION_LENGTH = 192

/**
 * 请求 ID 卡背景图（C2S）—— **打开 ID 卡界面时发一次**，把手里那张图的版本报上去。
 *
 * 判定为什么放在服务端：版本的两个成分（文件名、文件修改时间）**只有服务端知道**。
 * 服务端比对相同就只回版本号、**不重传图**（回几十字节 vs 几十 KB）。
 * 首次打开 / 手里没有图时传**空串**。
 */
data class RequestIdCardBackgroundPayload(val knownVersion: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestIdCardBackgroundPayload>(CobbleMarket.id("request_idcard_bg"))
        val CODEC: PacketCodec<PacketByteBuf, RequestIdCardBackgroundPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.knownVersion, MAX_VERSION_LENGTH) },
            { b -> RequestIdCardBackgroundPayload(b.readString(MAX_VERSION_LENGTH)) }
        )
    }
}

/**
 * ID 卡背景图（S2C）。四个状态，客户端按下表处置：
 *
 * | version | bytes | 含义 ⇒ 客户端 |
 * |---|---|---|
 * | 空 | 空 | 服主**没配图** ⇒ 用模组内置的背景 |
 * | 与客户端报上去的**相同** | 空 | **没变** ⇒ 继续用手里那张（服务端有意没重传） |
 * | 其它 | 空 | 配了但**读不出来**（文件不存在 / 不是 PNG / 太大）⇒ 回退内置背景 |
 * | 非空 | 非空 | 新图 ⇒ 换掉手里那张 |
 *
 * ⚠ **后两行只差"版本是否相同"，客户端必须先比版本** —— 否则"没变"会被误判成"读不出来"，
 * 好好的图被退回内置背景。合并成一个包是为了省一趟往返（一次请求区分三种情况）。
 *
 * ⚠ 用普通 class 而非 data class：`byte[]` 的 equals/hashCode 是引用比较，data class 会误导。
 */
class IdCardBackgroundPayload(val version: String, val bytes: ByteArray) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<IdCardBackgroundPayload>(CobbleMarket.id("idcard_bg"))
        val CODEC: PacketCodec<PacketByteBuf, IdCardBackgroundPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.version, MAX_VERSION_LENGTH)
                b.writeByteArray(p.bytes)
            },
            { b -> IdCardBackgroundPayload(b.readString(MAX_VERSION_LENGTH), b.readByteArray()) }
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
            // 配置里当前那张图（含版本戳 = 文件名@修改时间）；没配 / 读不出来时 image = null
            val image = IdCardBackgroundImage.loadVersioned(CobbleMarketConfig.idCardBackground)
            val version = image?.version ?: ""
            // 版本与客户端手里那张相同 ⇒ 只回版本号，**不重传图**（客户端继续用缓存的纹理）
            val unchanged = version.isNotEmpty() && version == payload.knownVersion
            // 「没配图」（配置留空）是正常状态、不打日志；配了却读不出来的 warn 由 ConfigImage 打
            val bytes = if (unchanged) ByteArray(0) else image?.bytes ?: ByteArray(0)
            sendToPlayer(player, IdCardBackgroundPayload(version, bytes))
        }

        // 确保图目录存在（服主打开 config 就知道图该往哪放）
        IdCardBackgroundImage.ensureDir()
    }
}
