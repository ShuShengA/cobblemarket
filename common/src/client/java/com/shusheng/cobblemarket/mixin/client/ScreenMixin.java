package com.shusheng.cobblemarket.mixin.client;

import com.shusheng.cobblemarket.client.CardCelebrationAnimation;
import com.shusheng.cobblemarket.client.CloseAnimation;
import com.shusheng.cobblemarket.client.CobbleMarketClientKt;
import com.shusheng.cobblemarket.client.EnterAnimation;
import com.shusheng.cobblemarket.client.PokemonCelebrationAnimation;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拍卖赢家庆祝动画的最上层渲染：界面（Screen）连同 tooltip 全部画完之后补一层。
 *
 * HudRenderCallback 在 Screen 之前渲染，打开界面时会被界面盖住，所以有界面时由这里接管。
 * 注入在 Screen.renderWithTooltip 的 TAIL 而不是 GameRenderer 里的那个调用点：
 * 直接从方法参数拿 DrawContext，不必用 LocalCapture 捕获局部变量（对不上时会静默不注入），
 * 也不跟 Fabric Screen API 包装同一调用点的 @WrapOperation 抢注入顺序。
 *
 * 关闭动画：renderWithTooltip HEAD/TAIL 包矩阵（整个界面整体上滑出屏）；
 * keyPressed 拦截 Esc 改启动关闭动画（无聚焦输入框时）；动画期间吞鼠标点击。
 *
 * ⚠⚠ **关闭动画只对本模组界面生效**（包名前缀判断，见 `cobblemarket$interceptEsc`）——
 * 别的界面（背包 / 合成台 / 箱子 / 其它模组的商店界面…）必须走原版 `Screen.close()`：
 * 容器界面（`HandledScreen`）的 `close()` 里有 `player.closeHandledScreen()`，
 * 被我们接管就会跳过那一步，服务端与客户端的容器状态就此错位（2026-10-03 修）。
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void cobblemarket$closeAnimHead(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        // 进场动画（聊天跳转）：先 push 进场、再 push 关闭，TAIL 逆序 pop（两者不同时进行，栈配对安全）
        EnterAnimation.INSTANCE.pushTransform(context);
        CloseAnimation.INSTANCE.pushTransform(context);
    }

    @Inject(method = "renderWithTooltip", at = @At("TAIL"))
    private void cobblemarket$renderCelebrationOverlay(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        PokemonCelebrationAnimation.renderOverlay(context, deltaTicks);
        CardCelebrationAnimation.INSTANCE.renderOverlay(context);
        // 余额 HUD（顶层，onTop=true）：界面（含弹窗遮罩）画完后补画，保证竞价/购买弹窗打开时余额不被压暗。
        // ⚠ 这条路径只在**本模组界面**画；原版界面那种「在下面淡淡透出来」的效果由 HUD 层那条路径负责
        CobbleMarketClientKt.renderBalanceHud(context, true);
        // 关闭动画矩阵恢复（与 HEAD 配对；动画中界面内容与 HUD 一起滑出）
        CloseAnimation.INSTANCE.popTransform(context);
        EnterAnimation.INSTANCE.popTransform(context);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cobblemarket$interceptEsc(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        Screen self = (Screen) (Object) this;
        // 动画进行中吞掉按键
        if (CloseAnimation.INSTANCE.isActive()) {
            cir.setReturnValue(true);
            return;
        }
        // Esc 且无聚焦输入框：市场动画开关开启时改启动关闭动画，关闭时放行原逻辑（原版 Esc 直接关闭）；
        // focused 非空放行，保持原版输入框 Esc 行为；音效与动画解绑，两条路径都播
        if (keyCode == 256 && self.shouldCloseOnEsc() && self.getFocused() == null) {
            // ⚠⚠ **只对本模组的界面接管**（2026-10-03 修的真 bug）：别的界面一律放行 —— 它们必须走
            //   原版 `Screen.close()`，而容器界面（`HandledScreen`）的 `close()` 里还有
            //   `player.closeHandledScreen()`（发包告诉服务端「容器已关」）。替它们接管、动画结束直接
            //   `setScreen(null)` 会把那一步跳掉 ⇒ **服务端以为玩家还在容器里** ⇒ 合成有时失灵 /
            //   背包↔快捷栏移动卡住 / 与商店模组交互时错位（静默、无日志、多半下次开容器自愈）。
            // ⚠ 用**包名前缀**判断而不是逐个列界面类：我们的界面全在 `com.shusheng.cobblemarket.*` 下，
            //   前缀能自动覆盖以后新增的界面（逐个列必然漏）。
            // ⚠ 排在音效分支**之前**：非本模组界面关闭时也不该播我们的关闭音效。
            if (!self.getClass().getName().startsWith("com.shusheng.cobblemarket.")) return;
            if (com.shusheng.cobblemarket.client.ClientConfig.INSTANCE.getMarketAnimation()) {
                if (CloseAnimation.INSTANCE.start()) {
                    cir.setReturnValue(true);
                }
            } else {
                CloseAnimation.INSTANCE.playCloseSound();
            }
        }
    }
}
