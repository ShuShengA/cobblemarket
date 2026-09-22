package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.item.HeldItemRenderer
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.item.ItemStack
import org.joml.Quaternionf

/**
 * 界面里 3D 精灵图标的统一渲染入口。
 *
 * 原先各界面调用点各自复制一份 `push → scissor → translate → scale → drawProfilePokemon → pop`，
 * 现收口到这里 —— 后续的动画能力只动 [IconAnimationResolver]，这些调用点不再改动。
 */
object PokemonIconRenderer {

    /** 携带物渲染器：无状态，复用一个实例即可（照 Cobblemon Summary 的 ModelWidget） */
    private val heldItemRenderer = HeldItemRenderer()

    /**
     * 把携带物渲染到模型的手/嘴 locator 上。
     *
     * **`drawProfilePokemon` 不负责携带物**——它第一步就是把 `RenderablePokemon` 拆成
     * `species` + `aspects`，`heldItem` 被丢弃。Cobblemon Summary 的做法是两者成对调用：
     * 先 `drawProfilePokemon(...)`、紧接着 `HeldItemRenderer.renderOnModel(...)`
     * （见 `ModelWidget.kt:113-131`，2026-09-12 查证）。必须在同一个矩阵作用域内、
     * 且在 `drawProfilePokemon` **之后**调用（它内部才会给 state 设好 currentModel 与 locator）。
     *
     * @param matrices 默认用 context 当前的矩阵栈；三处 profile 渲染都在自己的 push 作用域内调
     */
    fun drawHeldItem(
        context: DrawContext,
        item: ItemStack,
        state: PosableState,
        matrices: MatrixStack = context.matrices
    ) {
        if (item.isEmpty) return
        heldItemRenderer.renderOnModel(item, state, matrices, context.vertexConsumers, light = 0xF000F0)
    }

    /** 全项目统一的图标缩放。庆祝动画与悬停大图走各自的 scale，不经过这里。 */
    const val ICON_SCALE = 4.5f

    /**
     * 绘制一个 3D 精灵图标，图标矩形以左上角定位。
     *
     * @param animate       是否播放动画，即原先各调用点手里的 `useFloat`
     * @param preview       是否处于预览态（该行正被悬停 / 是大图本身）：切走路姿势
     * @param useScissor    是否裁剪到图标外扩 4px；三个「裸渲染」处（两个弹窗预览 + 整屏确认页）传 false
     * @param clip          自定义裁剪框 `[left, top, right, bottom]`（**屏幕坐标**）：非空时**整个覆盖**
     *                      [useScissor] 的默认外扩。给「允许长出去」的大图用 —— 槽位只有那么大，
     *                      而高的（伽勒尔椰蛋树）／宽的精灵塞不下（`PointsMarketScreen.editPreviewClip`）
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
        clip: IntArray? = null,
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
            // ⚠ 也要把「正被大图预览」算进去：**同一个 state 不能被两个调用点用不同的 preview 掰**
            //   —— 大图要 WALK、小图要 PROFILE 的话，姿势过渡会被反复触发（2026-09-22 实测，
            //   典型是 SellSelectScreen 的选中行大图常驻）。读的是**上一帧**的登记（见 markPreviewing）。
            preview = (preview || IconRegistry.isPreviewing(state)) &&
                com.shusheng.cobblemarket.client.ClientConfig.hoverPreview
        )

        val matrices = context.matrices
        // 裁剪框：自定义的优先，否则按 useScissor 走全项目统一的「图标外扩 4px」。
        // ⚠ 收在一个变量里 → 开/关永远是配对的，不会出现「开了没关」（NineSliceButton 那边踩过）
        val scissor = clip ?: if (useScissor) intArrayOf(x - 4, y - 4, x + size + 4, y + size + 4) else null
        matrices.push()
        try {
            if (scissor != null) {
                context.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3])
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
            // 携带物：与 drawProfilePokemon 成对（它不画这个），详见 drawHeldItem 注释
            drawHeldItem(context, renderable.heldItem, state, matrices)
        } finally {
            if (scissor != null) {
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
