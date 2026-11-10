package com.shusheng.cobblemarket.mixin.client;

import com.shusheng.cobblemarket.client.IdCardRenderState;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 画 ID 卡 3D 模型期间**屏蔽玩家名字 / 称号标签**（2026-11-10 用户实测）。
 *
 * 原版 `EntityRenderer.render` 在实体渲染后调用 `renderLabelIfPresent`；玩家走的是
 * `PlayerEntityRenderer` 的覆写版本（带队伍前缀 / 称号）。展示者在附近时，分享卡里会画出
 * 他的名字（有称号模组时还带称号）；名字长会顶出卡片，字号也被模型缩放放得很大。
 *
 * ⚠ 必须注入**子类覆写**而不是 `EntityRenderer.renderLabelIfPresent`：基类 `render` 里是虚调用，
 *   实际执行的是 PlayerEntityRenderer 的覆写，注入基类方法不会生效。
 * ⚠ 只在 [IdCardRenderState.suppressNameTags] 为 true 时取消，世界里的名字完全不受影响。
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void cobblemarket$hideIdCardNameTag(
        AbstractClientPlayerEntity entity,
        Text text,
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (IdCardRenderState.suppressNameTags) {
            ci.cancel();
        }
    }
}
