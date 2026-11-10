package com.shusheng.cobblemarket.mixin.client;

import com.shusheng.cobblemarket.client.PlayerModelPartsAccess;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 给玩家实体加一个「打开全部皮肤层」的入口（见 {@link PlayerModelPartsAccess}）。
 *
 * ## 为什么需要
 *
 * 聊天里分享的**市场 ID 卡**要为展示者画 3D 模型。展示者不在客户端加载范围内时，
 * `ChatObjectPreview.resolveIdCardPlayer` 会创建一个 `OtherClientPlayerEntity` 假实体来渲染。
 * 假实体的 data tracker 用默认值初始化，其中 `PLAYER_MODEL_PARTS = 0`；而原版
 * `PlayerEntityRenderer.setModelPose` 会按这个值隐藏 HAT / JACKET / 左右袖 / 左右裤腿
 * ⇒ 假实体**只画第一层皮肤**。
 *
 * 附近玩家走的是真实实体（服务端同步过 model parts），所以看起来「偶尔正常」；
 * 小服务器大家靠得近，更难复现。Skin Layers 3D 之类的模组也依赖这个值。
 *
 * ⚠ 只给客户端自己创建的假玩家用；**不要**去改真实玩家的数据（那是服务端同步的）。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin implements PlayerModelPartsAccess {

    @Shadow
    @Final
    protected static TrackedData<Byte> PLAYER_MODEL_PARTS;

    @Override
    public void setModelParts(int mask) {
        // 位含义：CAPE(1) | JACKET(2) | LEFT_SLEEVE(4) | RIGHT_SLEEVE(8)
        //         | LEFT_PANTS_LEG(16) | RIGHT_PANTS_LEG(32) | HAT(64)
        // ⚠ 按服务端下发的实际掩码设，**不要无条件全开**（分享者可能关了披风）
        // ⚠ dataTracker 声明在父类 Entity，走 EntityDataTrackerAccessor（原因见那个文件）
        ((EntityDataTrackerAccessor) this).cobblemarket$dataTracker()
            .set(PLAYER_MODEL_PARTS, (byte) (mask & 0x7F));
    }
}
