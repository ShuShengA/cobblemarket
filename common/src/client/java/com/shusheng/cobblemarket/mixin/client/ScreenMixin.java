package com.shusheng.cobblemarket.mixin.client;

import com.shusheng.cobblemarket.client.CobbleMarketClientKt;
import com.shusheng.cobblemarket.client.PokemonCelebrationAnimation;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拍卖赢家庆祝动画的最上层渲染：界面（Screen）连同 tooltip 全部画完之后补一层。
 *
 * HudRenderCallback 在 Screen 之前渲染，打开界面时会被界面盖住，所以有界面时由这里接管。
 * 注入在 Screen.renderWithTooltip 的 TAIL 而不是 GameRenderer 里的那个调用点：
 * 直接从方法参数拿 DrawContext，不必用 LocalCapture 捕获局部变量（对不上时会静默不注入），
 * 也不跟 Fabric Screen API 包装同一调用点的 @WrapOperation 抢注入顺序。
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    // 诊断日志（一次性）：验证注入点确实在运行
    private static boolean hudDiagLogged = false;

    @Inject(method = "renderWithTooltip", at = @At("TAIL"))
    private void cobblemarket$renderCelebrationOverlay(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        PokemonCelebrationAnimation.renderOverlay(context);
        // 余额 HUD：界面（含弹窗遮罩）画完后补画，保证竞价/购买弹窗打开时余额不被压暗
        CobbleMarketClientKt.renderBalanceHud(context);
    }
}
