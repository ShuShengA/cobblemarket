package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.config.CobbleMarketConfig
import com.shusheng.cobblemarket.platform.configDir
import com.shusheng.cobblemarket.platform.registerC2S
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import com.shusheng.cobblemarket.util.PlayerNameText
import com.shusheng.cobblemarket.util.RequestThrottle
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.io.File
import java.util.UUID

/** 照片 id 字符串上限（UUID 为 36） */
private const val PHOTO_ID_MAX_LENGTH = 40

/** 照片名字长度上限（服务端截断；留空时聊天里回退成「照片」/ "Photo"） */
private const val PHOTO_NAME_MAX_LENGTH = 32

/** 名字在协议里的读取上限（比实际长一点，防解码期无界字符串） */
private const val PHOTO_NAME_PACKET_MAX = 64

// ── C2S：上传一张照片 ──

/**
 * 客户端把**已经裁剪、缩放、压缩好的 JPEG 字节**发上来（相框内框比例、宽 1280 起步，≤256KB）。
 *
 * ⚠ 读端带最大长度 —— 防止恶意客户端发一个超大长度让服务端分配巨量内存。
 * ⚠ 用普通 class 而非 data class：`byte[]` 的 equals/hashCode 是引用比较，data class 会误导。
 */
class UploadPhotoPayload(
    val bytes: ByteArray,
    /** 照片名字（玩家在确认界面填，可留空）；服务端会清洗与截断 */
    val name: String,
    /** 是否随照片分享拍摄位置（确认界面的位置按钮选中） */
    val locationEnabled: Boolean,
    /** 拍摄位置（[locationEnabled] 为 false 时忽略） */
    val x: Int,
    val y: Int,
    val z: Int,
) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<UploadPhotoPayload>(CobbleMarket.id("upload_photo"))
        val CODEC: PacketCodec<PacketByteBuf, UploadPhotoPayload> = PacketCodec.of(
            { p, b ->
                b.writeByteArray(p.bytes)
                b.writeString(p.name, PHOTO_NAME_PACKET_MAX)
                b.writeBoolean(p.locationEnabled)
                b.writeInt(p.x)
                b.writeInt(p.y)
                b.writeInt(p.z)
            },
            // +1：超限的包也要能读出来再统一走「过大」的失败提示（而不是解码期断连）
            { b ->
                UploadPhotoPayload(
                    b.readByteArray(PhotoNetwork.MAX_PHOTO_BYTES + 1),
                    b.readString(PHOTO_NAME_PACKET_MAX),
                    b.readBoolean(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt()
                )
            }
        )
    }
}

// ── C2S：请求一张照片（聊天悬停时发） ──

data class RequestPhotoPayload(val photoId: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<RequestPhotoPayload>(CobbleMarket.id("request_photo"))
        val CODEC: PacketCodec<PacketByteBuf, RequestPhotoPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.photoId, PHOTO_ID_MAX_LENGTH) },
            { b -> RequestPhotoPayload(b.readString(PHOTO_ID_MAX_LENGTH)) }
        )
    }
}

// ── S2C：照片数据（客户端解码成动态纹理后画进相框） ──

/**
 * [bytes] 为空 = 照片不存在（被清理 / 服务端重启后丢失 / 伪造 id）——
 * 客户端据此画空相框 + 「照片已失效」，而不是一直等待。
 */
class PhotoDataPayload(val photoId: String, val bytes: ByteArray) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PhotoDataPayload>(CobbleMarket.id("photo_data"))
        val CODEC: PacketCodec<PacketByteBuf, PhotoDataPayload> = PacketCodec.of(
            { p, b ->
                b.writeString(p.photoId, PHOTO_ID_MAX_LENGTH)
                b.writeByteArray(p.bytes)
            },
            { b ->
                PhotoDataPayload(
                    b.readString(PHOTO_ID_MAX_LENGTH),
                    b.readByteArray(PhotoNetwork.MAX_PHOTO_BYTES)
                )
            }
        )
    }
}

/**
 * **照片分享**（2026-11-20）：玩家在快速功能界面点相机按钮拍下当前画面，
 * 确认后发到全服聊天；其他玩家在聊天里悬停那条播报即可看到相框里的照片。
 *
 * 与精灵 / 物品展示的关系：
 * - **照片算「展示」**（用户 2026-11-20 定）⇒ 共用 [CobbleMarketConfig.shareEnabled] 开关与
 *   `shareCooldownSeconds` 冷却（同一个 RequestThrottle key `share`，两条命令互相占用冷却）
 * - 数据不走聊天消息本身：消息只挂一个 `/cobblemarket photo <id>` 的 clickEvent 供客户端识别，
 *   照片字节由客户端悬停时按需向服务端请求（与拍卖 / 分享大图同一条路）
 *
 * ⚠ 照片是**玩家生成内容**：服务端只校验「是合法大小范围内的 PNG」与冷却，内容本身由服主开关负责。
 *   文件统一放 `config/cobblemarket/photos/`，文件名 `<照片id>_<上传者id>.png`；
 *   请求时用照片 id 前缀匹配（id 经过 UUID 解析，不会成为路径）。
 */
object PhotoNetwork {

    /** 单张照片字节上限（客户端 1280 宽 JPEG 自适应压缩到 256KB 内） */
    const val MAX_PHOTO_BYTES = 256 * 1024

    /** 照片宽高上限（防止恶意客户端塞一张超大解压图；客户端预览也会再限一次） */
    private const val MAX_SIDE = 2048

    /** MC 世界坐标范围（±30,000,000）—— 分享位置时把坐标夹在这里 */
    private const val MAX_COORD = 30_000_000

    /** 照片保留时长：聊天记录里的照片长期可看，但也不能无限堆 */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 全服最多保留多少张 */
    private const val GLOBAL_MAX = 500

    /** 每个玩家最多保留多少张（超出删该玩家最旧的） */
    private const val PER_PLAYER_MAX = 20

    /**
     * ⚠⚠ **S2C 是两步注册，缺一不可**（项目为此栽过两次）：
     * 1. 这里的 `registerS2CType` —— 漏了**客户端启动即崩**
     * 2. 客户端 `CobbleMarketClient` 里的 `registerS2C` —— 漏了**静默无反应**
     * ⚠ NeoForge 不显形（playToClient 自带类型注册）—— 别拿 NeoForge 跑通当没问题。
     */
    fun register() {
        registerS2CType(PhotoDataPayload.ID, PhotoDataPayload.CODEC)

        // ⚠ C2S 必须写在本函数体内：插到别处会编译通过但永不注册，第一次发包直接断连
        registerC2S(UploadPhotoPayload.ID, UploadPhotoPayload.CODEC) { payload, player ->
            val server = player.server
            server.execute {
                if (!CobbleMarketConfig.shareEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.share.disabled")))
                    return@execute
                }
                // 照片子开关：客户端会提前把按钮置灰，这里再兜一层（防旧客户端 / 改包）
                if (!CobbleMarketConfig.photoEnabled) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.photo.disabled")))
                    return@execute
                }
                // 冷却与精灵 / 物品 / ID 卡展示共用（照片算展示，用户 2026-11-20 定）
                val cooldownMs = CobbleMarketConfig.shareCooldownSeconds.coerceIn(1, 3600) * 1000L
                if (!RequestThrottle.allow(player.uuid, "share", cooldownMs)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.share.cooldown")))
                    return@execute
                }
                if (!isValidPhotoJpeg(payload.bytes)) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.photo.invalid")))
                    return@execute
                }
                val photoId = UUID.randomUUID()
                val written = runCatching {
                    photosDir().mkdirs()
                    photoFile(photoId, player.uuid).writeBytes(payload.bytes)
                }.isSuccess
                if (!written) {
                    sendToPlayer(player, MarketResultPayload(false, Text.translatable("cobblemarket.photo.failed")))
                    return@execute
                }
                cleanupPhotos()
                // 坐标只用于显示，服务端夹到 MC 世界坐标范围内（防离谱数字占屏）
                val location = if (payload.locationEnabled) {
                    Triple(
                        payload.x.coerceIn(-MAX_COORD, MAX_COORD),
                        payload.y.coerceIn(-MAX_COORD, MAX_COORD),
                        payload.z.coerceIn(-MAX_COORD, MAX_COORD)
                    )
                } else {
                    null
                }
                val message = photoMessage(player, photoId, sanitizePhotoName(payload.name), location)
                server.playerManager.playerList.forEach { it.sendMessage(message) }
                sendToPlayer(player, MarketResultPayload(true, Text.translatable("cobblemarket.photo.sent")))
            }
        }

        // 读请求：与其余悬停预览同一套限流（客户端有 550ms 让路，被拒静默即可）
        registerC2S(RequestPhotoPayload.ID, RequestPhotoPayload.CODEC) { payload, player ->
            if (!RequestThrottle.allow(player.uuid, "photo_read", RequestThrottle.READ_INTERVAL_MS)) {
                return@registerC2S
            }
            val server = player.server
            server.execute {
                // id 必须是合法 UUID —— 这是路径安全的前提（文件名前缀匹配）
                val id = runCatching { UUID.fromString(payload.photoId) }.getOrNull() ?: return@execute
                sendToPlayer(player, PhotoDataPayload(id.toString(), readPhoto(id)))
            }
        }
    }

    /** 照片目录：`config/cobblemarket/photos/`（与其余运行数据同级，不随存档走） */
    private fun photosDir(): File = configDir().resolve("cobblemarket/photos").toFile()

    /** 文件名 = `<照片id>_<上传者id>.jpg`；上传者 id 用于「每人最多 N 张」的清理 */
    private fun photoFile(photoId: UUID, owner: UUID): File =
        File(photosDir(), "${photoId}_${owner}.jpg")

    /** 按照片 id 前缀找文件（id 已校验为 UUID，不会成为路径穿越；兼容早期测试留下的 .png） */
    private fun readPhoto(photoId: UUID): ByteArray {
        val file = photosDir()
            .listFiles { _, name -> name.startsWith("${photoId}_") }
            ?.firstOrNull() ?: return ByteArray(0)
        return runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
    }

    /**
     * JPEG 校验：SOI 魔数 + SOF 段里的宽高。
     *
     * 服务端**不解码图片内容**（不需要 AWT / ImageIO），只看文件头 ——
     * 既挡住「根本不是 JPEG」的垃圾，也挡住解压后尺寸离谱的图（客户端解纹理时会再按 [MAX_SIDE] 检查一次）。
     */
    private fun isValidPhotoJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 24 || bytes.size > MAX_PHOTO_BYTES) return false
        // JPEG 以 FF D8 开头（FF D8 FF 的第三个字节是第一个段标记）
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        val (width, height) = jpegDimensions(bytes) ?: return false
        return width in 1..MAX_SIDE && height in 1..MAX_SIDE
    }

    /**
     * 扫描 JPEG 的段结构，在 SOF 段里取宽高；损坏 / 找不到（例如流式 JPEG）返回 null。
     * ⚠ 碰到 SOS（0xDA）还没找到 SOF 就放弃 —— 后面是压缩数据，继续扫只会误判。
     */
    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            // 跳过连续的 0xFF 填充字节
            var markerIndex = i + 1
            while (markerIndex < bytes.size && bytes[markerIndex] == 0xFF.toByte()) markerIndex++
            if (markerIndex >= bytes.size) return null
            val marker = bytes[markerIndex].toInt() and 0xFF

            // 无长度字段的标记
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) {
                i = markerIndex + 1
                continue
            }
            if (markerIndex + 2 >= bytes.size) return null
            val length = ((bytes[markerIndex + 1].toInt() and 0xFF) shl 8) or
                (bytes[markerIndex + 2].toInt() and 0xFF)
            if (length < 2) return null

            // SOF0..SOF15（排除 DHT=0xC4 / JPG=0xC8 / DAC=0xCC）
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (markerIndex + 7 >= bytes.size) return null
                val height = ((bytes[markerIndex + 4].toInt() and 0xFF) shl 8) or
                    (bytes[markerIndex + 5].toInt() and 0xFF)
                val width = ((bytes[markerIndex + 6].toInt() and 0xFF) shl 8) or
                    (bytes[markerIndex + 7].toInt() and 0xFF)
                return width to height
            }
            if (marker == 0xDA) return null
            i = markerIndex + 1 + length
        }
        return null
    }

    /**
     * 上传后顺带清理（上传是低频操作，不必起定时任务）：
     * ① 超过 [TTL_MS] 的；② 每人超过 [PER_PLAYER_MAX] 的最旧几张；③ 全服超过 [GLOBAL_MAX] 的最旧几张。
     */
    private fun cleanupPhotos() {
        val dir = photosDir()
        // 兼容早期测试留下的 .png；新文件一律 .jpg
        fun listPhotos(): List<File> =
            dir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
                ?.toList().orEmpty()

        val now = System.currentTimeMillis()
        listPhotos().filter { now - it.lastModified() > TTL_MS }
            .forEach { runCatching { it.delete() } }

        listPhotos()
            .groupBy { it.name.substringBeforeLast('.').substringAfterLast('_') }
            .forEach { (_, files) ->
                if (files.size > PER_PLAYER_MAX) {
                    files.sortedBy { it.lastModified() }
                        .take(files.size - PER_PLAYER_MAX)
                        .forEach { runCatching { it.delete() } }
                }
            }

        val remaining = listPhotos()
        if (remaining.size > GLOBAL_MAX) {
            remaining.sortedBy { it.lastModified() }
                .take(remaining.size - GLOBAL_MAX)
                .forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 照片名字清洗：去掉控制字符与 `§`（防聊天格式注入），截到 [PHOTO_NAME_MAX_LENGTH]。
     * 结果为空 ⇒ 聊天里回退成词条「照片」/ "Photo"。
     */
    private fun sanitizePhotoName(raw: String): String =
        raw.filter { !it.isISOControl() && it != '§' }.trim().take(PHOTO_NAME_MAX_LENGTH)

    /**
     * 全服播报：`[分享] <玩家名> 分享了 [<照片名字>]` —— **与精灵 / 物品分享播报同一套结构**
     * （2026-11-20 用户反馈旧格式「[照片] … [照片]」重复别扭）。
     * 名字留空时方括号里显示「照片」/ "Photo"（[cobblemarket.chat.photo_link]）。
     * 末尾那个链接挂 `/cobblemarket photo <id>` —— 客户端据此认出「鼠标下是哪个照片」，
     * 点击本身是客户端命令里的空分支（点了不做事，只做悬停）。
     */
    private fun photoMessage(
        player: ServerPlayerEntity,
        photoId: UUID,
        name: String,
        /** 启用「分享拍摄位置」时：坐标附在识别指令后面，客户端解析后画在照片右下角 */
        location: Triple<Int, Int, Int>?,
    ): Text {
        val linkText = if (name.isEmpty()) {
            Text.translatable("cobblemarket.chat.photo_link")
        } else {
            Text.literal(name)
        }
        // 客户端只拿这个 clickEvent 做识别（点击是空分支），坐标顺带传过去；
        // 服务端不另存坐标 —— 聊天记录里那条消息本身就是坐标的载体
        val command = if (location != null) {
            "/cobblemarket photo $photoId ${location.first} ${location.second} ${location.third}"
        } else {
            "/cobblemarket photo $photoId"
        }
        val link = Text.literal("")
            .append(Text.literal("[").formatted(Formatting.GRAY))
            .append(linkText.formatted(Formatting.GOLD))
            .append(Text.literal("]").formatted(Formatting.GRAY))
            .styled {
                it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            }
        return Text.translatable("cobblemarket.chat.share_prefix").formatted(Formatting.GOLD)
            .append(" ")
            .append(
                Text.translatable(
                    "cobblemarket.chat.photo_announce",
                    PlayerNameText.of(player.name.string),
                    link
                ).formatted(Formatting.BLUE)
            )
    }
}
