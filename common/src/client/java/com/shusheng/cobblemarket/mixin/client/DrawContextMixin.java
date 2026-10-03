package com.shusheng.cobblemarket.mixin.client;

import com.shusheng.cobblemarket.client.GuiScale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 界面尺寸（见 `GuiScale` 顶部说明）的**裁剪侧**。
 *
 * `scissor` 走的是**屏幕像素坐标**、不经过 `matrices` ⇒ 界面在虚拟坐标系里调的
 * `enableScissor(x1, y1, x2, y2)` 会裁到错误的位置（症状：列表/弹窗信息区被切掉一块，
 * 或者该裁的地方没裁、内容溢出到框外）。所以要把 4 个坐标换算回原版逻辑坐标。
 *
 * ⚠ 判据用 `GuiScale.scalingActive`（"正处在我们界面的这一帧绘制中"）而不是"当前界面是
 * 不是我们的界面" —— 因为**同一帧里游戏 HUD 也会渲染**（此时 `currentScreen` 正是我们的
 * 界面），只看界面类型会误伤 HUD 自己的裁剪。
 *
 * ⚠ 这是我们**唯一**混入原版通用类（而非 `Screen` / `Mouse`）的地方：影响面靠上面这个标志
 * 精确收窄，标志之外一律原样返回 ⇒ 与"没有这个功能"等价。
 */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {

    /**
     * 虚拟坐标 → 原版逻辑坐标。⚠ 与渲染矩阵**同一个精确系数**（`N ÷ S`，见 `GuiScale` 顶部），
     * 所以裁剪边界与画出来的内容严格对齐（用 `scaledWidth ÷ screen.width` 反算会让边界差 1 像素）。
     */
    private static int cobblemarket$toLogical(int v) {
        if (!GuiScale.INSTANCE.getScalingActive()) return v;
        Screen screen = MinecraftClient.getInstance().currentScreen;
        return screen == null ? v : GuiScale.INSTANCE.toReal(v, screen);
    }

    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cobblemarket$scissorLeft(int x1) {
        return cobblemarket$toLogical(x1);
    }

    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int cobblemarket$scissorTop(int y1) {
        return cobblemarket$toLogical(y1);
    }

    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int cobblemarket$scissorRight(int x2) {
        return cobblemarket$toLogical(x2);
    }

    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private int cobblemarket$scissorBottom(int y2) {
        return cobblemarket$toLogical(y2);
    }
}
