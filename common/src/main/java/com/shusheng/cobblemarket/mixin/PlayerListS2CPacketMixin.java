package com.shusheng.cobblemarket.mixin;

import com.shusheng.cobblemarket.peek.PeekPacketGuard;
import com.shusheng.cobblemarket.peek.PeekService;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * 监视会话中，把 tab 列表里监视者的游戏模式伪装成**会话开始前的模式**
 * （`docs/peek_feature_plan.md` §10.12）。
 *
 * ## 为什么
 * 监视的用途之一是防作弊。监视者此时实际是 {@link GameMode#SPECTATOR}，不伪装的话，
 * 作弊玩家一眼就能从 tab 列表看出「有人切了旁观」（原版斜体 / 灰名 / 无 ping），立刻收手换小号。
 *
 * ## 做法
 * 只改**下发给客户端的** `gameMode` 字段，服务端实体的真实模式不动，监视本身不受影响；
 * ping、头衔、挂机状态等其他字段原样保留（它们与 gameMode 无关）。
 *
 * 注入两个**公开构造器** —— 服务端所有下发路径都经由它们：
 * - `PlayerManager.onPlayerConnect` / `entryFromPlayer` 走 `(EnumSet, Collection)` 版
 * - `ServerPlayerInteractionManager.changeGameMode` 走 `(Action, ServerPlayerEntity)` 版
 *
 * 只有 `actions` 含 `UPDATE_GAME_MODE` 时该字段才会被写出，其余情况直接跳过。
 * 客户端解码走私有 `RegistryByteBuf` 构造器，**不注入** —— 伪装只发生在服务端。
 *
 * ⚠ 必须**持续**拦截：原版在玩家加入 / 模式变化 / 列表刷新时会反复下发真实模式，
 *   只在开始监视时发一次会被覆盖。会话结束（状态删除）后自然恢复真实模式。
 */
@Mixin(PlayerListS2CPacket.class)
public abstract class PlayerListS2CPacketMixin {

    @Mutable
    @Shadow
    @Final
    private List<PlayerListS2CPacket.Entry> entries;

    @Inject(method = "<init>(Ljava/util/EnumSet;Ljava/util/Collection;)V", at = @At("RETURN"))
    private void cobblemarket$fakePeekerGameMode(
            EnumSet<PlayerListS2CPacket.Action> actions,
            Collection<ServerPlayerEntity> players,
            CallbackInfo ci) {
        if (players.isEmpty()) return;
        this.cobblemarket$replaceGameModes(actions, players.iterator().next().getServer());
    }

    @Inject(
            method = "<init>(Lnet/minecraft/network/packet/s2c/play/PlayerListS2CPacket$Action;Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At("RETURN"))
    private void cobblemarket$fakePeekerGameModeSingle(
            PlayerListS2CPacket.Action action,
            ServerPlayerEntity player,
            CallbackInfo ci) {
        this.cobblemarket$replaceGameModes(EnumSet.of(action), player.getServer());
    }

    private void cobblemarket$replaceGameModes(
            EnumSet<PlayerListS2CPacket.Action> actions, MinecraftServer server) {
        // 给监视者自己补发的「真实模式包」必须跳过伪装（见 ClientConnectionMixin）
        if (PeekPacketGuard.isBypassed()) return;
        if (server == null) return;
        if (!actions.contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)) return;
        // 绝大多数服务器绝大多数时间没有监视会话 —— 一次判断跳过，不必逐条查状态
        if (!PeekService.hasAnySession(server)) return;

        boolean changed = false;
        List<PlayerListS2CPacket.Entry> replaced = new ArrayList<>(this.entries.size());
        for (PlayerListS2CPacket.Entry entry : this.entries) {
            GameMode fake = PeekService.displayGameMode(server, entry.profileId());
            if (fake == null || fake == entry.gameMode()) {
                replaced.add(entry);
                continue;
            }
            replaced.add(new PlayerListS2CPacket.Entry(
                    entry.profileId(), entry.profile(), entry.listed(), entry.latency(),
                    fake, entry.displayName(), entry.chatSession()));
            changed = true;
        }
        // 没有命中会话时不动原列表（大多数包都如此）
        if (changed) this.entries = replaced;
    }
}
