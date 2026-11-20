package com.shusheng.cobblemarket.mixin;

import com.shusheng.cobblemarket.peek.PeekPacketGuard;
import com.shusheng.cobblemarket.permission.MarketPermissions;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 把被 tab 伪装改过的游戏模式，**对 staff 收包人**还原成真实模式。
 *
 * ## 为什么（两件互相牵连的事）
 *
 * ① 1.3.8 的 bug：{@link PlayerListS2CPacketMixin} 在构造时把监视者的 gameMode 改成
 * 「会话开始前的模式」—— 但同一个包会发给所有人，**包括监视者自己**。客户端用自己那条
 * `PlayerListEntry.gameMode` 判断 `isSpectator`，于是服务端是旁观（别人看不到、能飞）、
 * 客户端却按生存处理（有碰撞、能看见自己的装备）。1.3.9 的初版修法是只补发**他自己**那一条。
 *
 * ② 2026-11-20 用户报：**两名监察者同时监视时，能互相看到对方身上漂浮的盔甲**。
 * 原版对旁观者会跳过**全部**人形附加渲染层（`LivingEntityRenderer` 里 `if (!isSpectator)`），
 * 1.3.7（没有伪装）是正常的「半透明头 + 名字、无装备」；伪装把对方在客户端眼里变成了
 * 「非旁观」⇒ 盔甲层照常绘制。根因同上：**客户端靠 tab 条目的游戏模式决定怎么渲染**。
 *
 * ## 现在怎么做
 *
 * 伪装只该骗**普通玩家 / 作弊者**，不该骗同样有「玩家总览」权限的 staff。所以：
 * - 收包人**有「玩家总览」权限**（含服主）⇒ 把这个包里**所有被伪装过的条目**
 *   （真实模式与条目不一致、且当前在线的玩家）用真实模式**补发**一条给他；
 * - 其余玩家收到的仍是伪装后的版本（防作弊目的不变）。
 *
 * ⚠ 补发包必须跳过 {@link PlayerListS2CPacketMixin}（用 {@link PeekPacketGuard#setBypassed(boolean)}），
 *   否则构造时又被伪装；并防止自身无限递归（{@link PeekPacketGuard#setCorrecting(boolean)}）。
 * ⚠ 判据用 `MarketPermissions.has(OVERVIEW)`（**不认 OP**，与监视入口同一判据）。
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

    @Inject(
            method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V",
            at = @At("TAIL"))
    private void cobblemarket$restorePeekerModes(
            Packet<?> packet,
            @Nullable PacketCallbacks callbacks,
            boolean flush,
            CallbackInfo ci) {
        if (PeekPacketGuard.isCorrecting()) return;
        if (!(packet instanceof PlayerListS2CPacket list)) return;
        if (!list.getActions().contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)) return;

        ClientConnection self = (ClientConnection) (Object) this;
        if (!(self.getPacketListener() instanceof ServerPlayNetworkHandler handler)) return;
        ServerPlayerEntity player = handler.player;
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // 只给 staff 还原真实模式；普通玩家（绝大多数包）直接放行，保持伪装
        if (!MarketPermissions.INSTANCE.has(player, MarketPermissions.OVERVIEW)) return;

        // 收集这个包里被伪装过的条目：真实模式与条目里的不一致 ⇒ 就是伪装过的监视者
        List<ServerPlayerEntity> faked = new ArrayList<>();
        for (PlayerListS2CPacket.Entry entry : list.getEntries()) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(entry.profileId());
            if (online != null && entry.gameMode() != online.interactionManager.getGameMode()) {
                faked.add(online);
            }
        }
        if (faked.isEmpty()) return;

        // 真实模式补发包（构造时跳过伪装，否则又被改回去）
        PlayerListS2CPacket correction;
        PeekPacketGuard.setBypassed(true);
        try {
            correction = new PlayerListS2CPacket(
                    EnumSet.of(PlayerListS2CPacket.Action.UPDATE_GAME_MODE), faked);
        } finally {
            PeekPacketGuard.setBypassed(false);
        }

        PeekPacketGuard.setCorrecting(true);
        try {
            self.send(correction, null, flush);
        } finally {
            PeekPacketGuard.setCorrecting(false);
        }
    }
}
