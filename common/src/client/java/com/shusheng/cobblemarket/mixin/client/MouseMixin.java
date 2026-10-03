package com.shusheng.cobblemarket.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 界面尺寸（见 `GuiScale` 顶部说明）的**输入侧**：让发给界面的鼠标坐标落在**虚拟坐标系**里。
 *
 * 为什么在这而**不在 `Screen` 上**注入：`Screen.mouseClicked` / `mouseReleased` / `mouseMoved` /
 * `mouseDragged` / `mouseScrolled` **都不在 `Screen` 类里**（是 `net.minecraft.client.gui.Element`
 * 接口的默认方法），而我们的 35 个界面**个个都重写了它们** ——
 * 注入基类只会被子类绕过（静默失效）。故只能从原版的分发源头改。
 *
 * 做法：原版把坐标算成 `mouse.x × Window.getScaledWidth() ÷ Window.getWidth()`（= 原版逻辑坐标）
 * 再喂给 `currentScreen`。我们把这 8 处 `getScaledWidth/Height()` 换成**界面自己的宽高**
 * （`screen.width/height`）——
 * - 界面被缩放时，它就是**虚拟宽**（物理宽 ÷ 档位）⇒ 喂过去的正好是虚拟坐标 ✓
 * - 没缩放时它 = `getScaledWidth()` ⇒ **恰好等于原值**，等价于没做任何事 ✓
 *
 * ⚠ 这 8 处**全部**是坐标/位移计算（`onMouseButton` 2 处、`onMouseScroll` 2 处、`tick` 4 处），
 * 所以整方法重定向是安全的。⚠ `onMouseScroll` 的后两个参数是**滚动量**（不是坐标），
 * 它们由鼠标滚轮选项算出、不走这两个调用 ⇒ 天然不受影响。
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Shadow
    @Final
    private MinecraftClient client;

    /** 分发给界面用的"逻辑宽"：界面被本模组缩放时是虚拟宽，否则与原值相同 */
    private int cobblemarket$screenScopedWidth(Window window) {
        Screen screen = this.client.currentScreen;
        return screen != null ? screen.width : window.getScaledWidth();
    }

    /** 分发给界面用的"逻辑高"（同 {@link #cobblemarket$screenScopedWidth}） */
    private int cobblemarket$screenScopedHeight(Window window) {
        Screen screen = this.client.currentScreen;
        return screen != null ? screen.height : window.getScaledHeight();
    }

    // ── onMouseButton：mouseClicked / mouseReleased 的 (mouseX, mouseY) ──

    @Redirect(method = "onMouseButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getScaledWidth()I"))
    private int cobblemarket$clickX(Window window) {
        return cobblemarket$screenScopedWidth(window);
    }

    @Redirect(method = "onMouseButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getScaledHeight()I"))
    private int cobblemarket$clickY(Window window) {
        return cobblemarket$screenScopedHeight(window);
    }

    // ── onMouseScroll：mouseScrolled 的 (mouseX, mouseY)（滚动量不走这两个调用） ──

    @Redirect(method = "onMouseScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getScaledWidth()I"))
    private int cobblemarket$scrollX(Window window) {
        return cobblemarket$screenScopedWidth(window);
    }

    @Redirect(method = "onMouseScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getScaledHeight()I"))
    private int cobblemarket$scrollY(Window window) {
        return cobblemarket$screenScopedHeight(window);
    }

    // ── tick：mouseMoved 的坐标 + mouseDragged 的坐标与位移（一个方法里两组，各自都会被重定向） ──

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getScaledWidth()I"))
    private int cobblemarket$tickX(Window window) {
        return cobblemarket$screenScopedWidth(window);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;getScaledHeight()I"))
    private int cobblemarket$tickY(Window window) {
        return cobblemarket$screenScopedHeight(window);
    }
}
