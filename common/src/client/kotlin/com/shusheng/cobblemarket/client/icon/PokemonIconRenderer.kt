package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.gui.DrawContext
import org.joml.Quaternionf

/**
 * 界面里 3D 精灵图标的统一渲染入口。
 *
 * 原先各界面调用点各自复制一份 `push → scissor → translate → scale → drawProfilePokemon → pop`，
 * 现收口到这里 —— 后续的动画能力只动 [IconAnimationResolver]，这些调用点不再改动。
 */
object PokemonIconRenderer {

    /** 全项目统一的图标缩放。庆祝动画与悬停大图走各自的 scale，不经过这里。 */
    const val ICON_SCALE = 4.5f

    /**
     * 绘制一个 3D 精灵图标，图标矩形以左上角定位。
     *
     * @param animate       是否播放动画，即原先各调用点手里的 `useFloat`
     * @param preview       是否处于预览态（该行正被悬停 / 是大图本身）：切走路姿势
     * @param useScissor    是否裁剪到图标外扩 4px；三个「裸渲染」处（两个弹窗预览 + 整屏确认页）传 false
     * @param centerAnchorY 纵向锚点取矩形中心而非 +1px；原先只有整屏确认页这样写
     */
    fun draw(
        context: DrawContext,
        renderable: RenderablePokemon,
        state: PosableState,
        x: Int,
        y: Int,
        size: Int,
        delta: Float,
        animate: Boolean,
        preview: Boolean = false,
        useScissor: Boolean = true,
        centerAnchorY: Boolean = false
    ) {
        IconRegistry.register(renderable, state, x, y, size)

        val rotation = iconRotation()

        val anim = IconAnimationResolver.resolve(
            renderable = renderable,
            state = state,
            rotation = rotation,
            centerX = x + size / 2f,
            centerY = y + size / 2f,
            mouseX = IconRegistry.mouseX,
            mouseY = IconRegistry.mouseY,
            animate = animate,
            // 「精灵预览图」关掉时行内也不进预览态 —— 否则大图不显示、行里却自顾自走起来。
            // 大图那一路是直接调解析器的（preview = true 恒成立），由 PokemonHoverPreview 自己把关开关
            preview = preview && com.shusheng.cobblemarket.client.ClientConfig.hoverPreview
        )

        val matrices = context.matrices
        matrices.push()
        try {
            if (useScissor) {
                context.enableScissor(x - 4, y - 4, x + size + 4, y + size + 4)
            }
            matrices.translate(x + size / 2.0, y + if (centerAnchorY) size / 2.0 else 1.0, 0.0)
            matrices.scale(size / 25f * 2.5f, size / 25f * 2.5f, 1f)
            drawProfilePokemon(
                renderablePokemon = renderable,
                matrixStack = matrices,
                rotation = rotation,
                poseType = anim.poseType,
                state = state,
                partialTicks = if (animate) delta else 0f,
                scale = ICON_SCALE,
                headYaw = anim.headYaw,
                headPitch = anim.headPitch
            )
        } finally {
            if (useScissor) {
                context.disableScissor()
            }
            matrices.pop()
        }
    }

    /**
     * 全项目统一的图标朝向（XYZ 13°/35°/0°）。
     *
     * **必须每次新建**：`drawProfilePokemon` 内部会对传入的 `rotation` 调 `conjugate()`（就地修改），
     * 复用同一个实例会让下一次渲染拿到镜像后的朝向。
     */
    fun iconRotation(): Quaternionf = Quaternionf().rotateXYZ(
        Math.toRadians(13.0).toFloat(),
        Math.toRadians(35.0).toFloat(),
        0f
    )
}
