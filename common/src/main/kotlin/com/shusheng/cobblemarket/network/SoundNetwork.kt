package com.shusheng.cobblemarket.network

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.registerS2CType
import com.shusheng.cobblemarket.platform.sendToPlayer
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity

/**
 * 通用音效通道：**服务端事件 → 让某个玩家客户端播一个音效**。
 *
 * 此前客户端能播的音效只覆盖「客户端自己知道的操作结果」（交易成功/失败、按钮、发卡动画等）；
 * 而服务端单方面发生的事件——出价被超越、贷款自动划扣、逾期、卖出到账、权限变更——
 * 客户端拿不到任何信号，玩家只能干看着聊天栏的文字。
 *
 * 这里用**一条通用包**代替「每个音效一个包」：以后再加音效，只需服务端调 [sendSound]、
 * 素材丢进 `assets/cobblemarket/sounds/`、`sounds.json` 注册三件事，不用再动网络层。
 *
 * 音效 ID 传**不带命名空间**的短名（如 `"loan_deduct"`），客户端统一解析到 cobblemarket。
 */
data class PlaySoundPayload(val soundId: String) : CustomPayload {
    override fun getId() = ID

    companion object {
        val ID = CustomPayload.Id<PlaySoundPayload>(CobbleMarket.id("play_sound"))
        val CODEC: PacketCodec<PacketByteBuf, PlaySoundPayload> = PacketCodec.of(
            { p, b -> b.writeString(p.soundId) },
            { b -> PlaySoundPayload(b.readString()) }
        )
    }
}

/** 让该玩家客户端播一个音效（服务端调用；音量档位在客户端播放入口统一给） */
fun sendSound(player: ServerPlayerEntity, soundId: String) {
    sendToPlayer(player, PlaySoundPayload(soundId))
}

/**
 * **管理类写操作的结果反馈音**（给操作者本人）：成功一声、被拒一声。
 *
 * 为什么不复用交易链路：交易操作回 `MarketResultPayload`，客户端据此播结果音**并顺带把提示打进聊天栏**；
 * 管理操作（保存配置 / 权限 / 黑名单 / 价格限制 / 卡收回 / 撤销坏账）不想要那行聊天文字，
 * 但同样需要「点了有没有生效」的听觉反馈 —— 此前这些保存/添加按钮点下去是**完全静默**的
 * （只有一声 0.25 档的按钮点击音），玩家无法判断操作成没成功（2026-09-17 用户反馈）。
 *
 * 音量档位由客户端 [playMarketSound] 按素材统一给，`result_*` 与交易结果音同档（0.5）。
 */
fun sendResultSound(player: ServerPlayerEntity, success: Boolean = true) {
    sendSound(player, if (success) "result_success" else "result_fail")
}

object SoundNetwork {
    fun register() {
        registerS2CType(PlaySoundPayload.ID, PlaySoundPayload.CODEC)
    }
}
