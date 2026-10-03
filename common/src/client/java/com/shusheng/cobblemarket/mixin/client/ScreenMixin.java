package com.shusheng.cobblemarket.mixin.client;

import com.shusheng.cobblemarket.client.CardCelebrationAnimation;
import com.shusheng.cobblemarket.client.ChatObjectPreview;
import com.shusheng.cobblemarket.client.CloseAnimation;
import com.shusheng.cobblemarket.client.CobbleMarketClientKt;
import com.shusheng.cobblemarket.client.CustomCursor;
import com.shusheng.cobblemarket.client.EnterAnimation;
import com.shusheng.cobblemarket.client.GuiScale;
import com.shusheng.cobblemarket.client.ItemCelebrationAnimation;
import com.shusheng.cobblemarket.client.PokemonCelebrationAnimation;
import com.shusheng.cobblemarket.screen.GatedFormScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
        // 货币图标门禁**每帧复位**：界面在自己的 render 里按弹窗状态重新置位。
        // 不在这里复位的话，某个界面置过一次 true 就会残留到别的界面（图标集体消失）。
        com.shusheng.cobblemarket.client.CurrencyDisplayKt.setSuppressCurrencyIcons(false);
        // 界面尺寸缩放：压在动画**之上**（TAIL 逆序 pop ⇒ 缩放先恢复、动画后恢复）。
        // 未设档位 / 非目标界面时系数恰好 = 1，等价于没做任何事（见 GuiScale 顶部说明）。
        GuiScale.INSTANCE.pushTransform(context, (Screen) (Object) this);
    }

    @Inject(method = "renderWithTooltip", at = @At("TAIL"))
    private void cobblemarket$renderCelebrationOverlay(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        // 界面尺寸缩放**最先**恢复：下面剩下的全是全屏层（庆祝动画 / 余额 HUD / 动画矩阵 / 自绘光标 /
        // 聊天大图），都不该跟着界面一起被缩放 —— 尤其**玩家的鼠标指针绝不能变小**。
        // 无条件 pop，与 HEAD 的无条件 push 严格配对（未缩放时它只是弹掉一个 scale(1,1,1)）。
        GuiScale.INSTANCE.popTransform(context);
        // 界面收到的是**虚拟**坐标，而下面这几样按屏幕定位 ⇒ 换算回原版逻辑坐标再交出去
        int realMouseX = GuiScale.INSTANCE.toReal(mouseX, self);
        int realMouseY = GuiScale.INSTANCE.toReal(mouseY, self);

        PokemonCelebrationAnimation.renderOverlay(context, deltaTicks);
        ItemCelebrationAnimation.INSTANCE.renderOverlay(context, deltaTicks);
        CardCelebrationAnimation.INSTANCE.renderOverlay(context);
        // 关闭动画矩阵恢复（与 HEAD 配对）。⚠ 余额 HUD 画在它**之后**（见下）
        CloseAnimation.INSTANCE.popTransform(context);
        // 余额 HUD（顶层，onTop=true）：界面（含弹窗遮罩）画完后补画，保证竞价/购买弹窗打开时余额不被压暗。
        // ⚠ 这条路径只在**本模组界面**画；原版界面那种「在下面淡淡透出来」的效果由 HUD 层那条路径负责
        // ⚠⭐ **位置有讲究**（2026-10-04）：必须夹在**两个动画 pop 之间** —— 在关闭动画矩阵**之外**
        //   （不然关闭界面时 HUD 会跟着界面上滑、界面一关又跳回原位），但在进场动画矩阵**之内**（照旧）。
        CobbleMarketClientKt.renderBalanceHud(context, true);
        EnterAnimation.INSTANCE.popTransform(context);
        // 聊天播报悬浮大图：鼠标停在拍卖/求购播报上时在右半屏中心画拍品大图。
        // 必须放在矩阵恢复之后（它自己会清深度缓冲，夹在 push/pop 之间会把矩阵栈搅乱），
        // 且非聊天界面内部直接收摊，不影响上面任何一层。
        ChatObjectPreview.render(context, realMouseX, realMouseY);
        // 自定义光标：画在全部内容与动画之后——它是玩家自己的指向，不该跟着关闭动画一起滑出。
        // 方法内部同时负责每帧校正系统光标的显隐（非本模组界面会自动恢复）
        CustomCursor.INSTANCE.render(context, realMouseX, realMouseY, self);
    }

    // ── 界面尺寸（见 GuiScale 顶部说明；只对本模组界面生效，其余界面系数恰好 = 1） ──

    /**
     * 把界面拿到的宽度换成**虚拟值**（物理宽 ÷ 档位）—— 虚拟尺寸的**唯一**赋值处，走 [GuiScale.widthFor]。
     *
     * ⚠ 注入点必须是 `init(MinecraftClient,int,int)`（**无参** `init()` 才是子类重写的那一个）：
     * 这个带参版本是 `public final`、35 个界面没有一个碰它 ⇒ 一次注入全覆盖。
     * `MinecraftClient.setScreen` 走它，`Screen.resize` 也走它。
     */
    @ModifyVariable(method = "init(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cobblemarket$virtualWidth(int width) {
        return GuiScale.INSTANCE.widthFor((Screen) (Object) this, width);
    }

    /** 同 [cobblemarket$virtualWidth]，取高度 */
    @ModifyVariable(method = "init(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int cobblemarket$virtualHeight(int height) {
        return GuiScale.INSTANCE.heightFor((Screen) (Object) this, height);
    }

    /**
     * ⚠ `Screen.resize` 是**独立实现**（只写 width/height + 重排 tab 导航，**不调带参 init**），
     * 由 `MinecraftClient.onResolutionChanged` 在窗口尺寸/GUI 缩放变化时触发 ⇒ 必须**单独注入**，
     * 漏了的话：改分辨率或原版界面尺寸后，我们的界面会退回真实尺寸、失去缩放。
     */
    @ModifyVariable(method = "resize(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cobblemarket$resizeVirtualWidth(int width) {
        return GuiScale.INSTANCE.widthFor((Screen) (Object) this, width);
    }

    /** 同 [cobblemarket$resizeVirtualWidth]，取高度 */
    @ModifyVariable(method = "resize(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int cobblemarket$resizeVirtualHeight(int height) {
        return GuiScale.INSTANCE.heightFor((Screen) (Object) this, height);
    }

    /** 原版的重建入口（`clearChildren + blur + init + setInitialFocus`），见 [cobblemarket$rebuildAfterResize] */
    @Shadow
    protected abstract void clearAndInit();

    /**
     * 尺寸变化后**重建界面** —— 光改 width/height 不够。
     *
     * ⚠ 原版 `Screen.resize` 只写 width/height（外加 tab 导航），**不调 `init()`**；而
     * `Screen.init(client, w, h)` 也只在**首次**调 `init()`（`screenInitialized` 守卫）⇒
     * 我们界面的**布局代码（控件 x/y 全在 `init()` 里算）永远不会重跑**：宽高换了、控件还停在
     * 旧位置。原版界面大多在 `render` 里现算位置，所以不重建也没事；我们的界面不是 ——
     * 症状就是用户 2026-10-04 实测的「缩放窗口后界面整体跑到屏幕外 / 缩在左上角」。
     *
     * ⚠ **只对本模组被缩放的界面做**（`scaleFor > 0`）：跟随原版 / 别的界面一律不碰，
     * 免得把"跟随原版 = 零变化"一起破掉。
     * ⚠ 注入在 `resize` 的 **TAIL**：此时 width/height 已是新值，而且**子类 `super.resize` 之后
     * 的代码还没跑** —— 各界 resize 里"存下输入框内容 → super.resize → 写回去"的写法正好
     * 作用在**新控件**上（`MarketScreen.resize` 就是这么写的，搜索词不会丢）。
     * ⚠ 代价（有意）：重建会丢滚动位置 / 展开状态等非控件状态。窗口缩放是低频操作，值。
     */
    @Inject(method = "resize(Lnet/minecraft/client/MinecraftClient;II)V", at = @At("TAIL"))
    private void cobblemarket$rebuildAfterResize(MinecraftClient client, int width, int height, CallbackInfo ci) {
        if (GuiScale.INSTANCE.scaleFor((Screen) (Object) this) <= 0) return;
        clearAndInit();
    }

    /**
     * 渲染用的鼠标坐标：原版逻辑坐标 → **虚拟**坐标（与界面布局同一套坐标系）。
     *
     * ⚠ `renderWithTooltip` 是 `public final`，所有界面都走它 ⇒ 改这里的参数，
     * 界面内部的悬停判断、widget 命中、原版 tooltip 定位全都自动对上。
     * ⚠ 这也意味着 TAIL 里交出去的坐标是虚拟值，必须用 [GuiScale.toReal] 还原（见上）。
     */
    @ModifyVariable(method = "renderWithTooltip", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cobblemarket$virtualMouseX(int mouseX) {
        return GuiScale.INSTANCE.toVirtual(mouseX, (Screen) (Object) this);
    }

    /** 同 [cobblemarket$virtualMouseX]，取 Y */
    @ModifyVariable(method = "renderWithTooltip", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int cobblemarket$virtualMouseY(int mouseY) {
        return GuiScale.INSTANCE.toVirtual(mouseY, (Screen) (Object) this);
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
            // ⚠ 「叠在别的界面上」的界面（形态说明）关掉时要**回它的上层**，不是回游戏 ——
            //   服主是在编辑窗配到一半时点进来的，回游戏等于把那一窗草稿一起丢了。
            //   其余界面 backTo 为 null（= 原行为）。⚠ `start` 的默认参数 Java 不认，必须显式传。
            Screen backTo = self instanceof GatedFormScreen
                    ? ((GatedFormScreen) self).getBackScreen() : null;
            if (com.shusheng.cobblemarket.client.ClientConfig.INSTANCE.getMarketAnimation()) {
                if (CloseAnimation.INSTANCE.start(backTo)) {
                    cir.setReturnValue(true);
                }
            } else {
                CloseAnimation.INSTANCE.playCloseSound();
            }
        }
    }
}
