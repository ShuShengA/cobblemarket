package com.shusheng.cobblemarket.client.icon

import com.cobblemon.mod.common.client.gui.calculateHeadYawAndPitch
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.pokemon.ai.RestBehaviour
import net.minecraft.client.MinecraftClient
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import org.joml.Quaternionf

/**
 * 图标动画解析器：决定一个图标这一帧用什么姿势、头部朝哪。
 *
 * 这是整批图标动画功能唯一的收口点 —— 头部跟随、昼夜姿态、悬停预览的差异全部落在本文件，
 * 各界面调用点只认 [PokemonIconRenderer] 这一个入口，不随功能增加而改动。
 *
 * 「是否播放动画」由调用方传入，而不是在这里读 `ClientConfig.iconAnimMode`：
 * 庆祝动画与悬停大图恒为动态，不受图标静态开关影响。
 */
object IconAnimationResolver {

    /** 解析结果：姿势交给 `drawProfilePokemon` 的 `poseType`，头部角度交给它的 `headYaw` / `headPitch`。 */
    data class Result(
        val poseType: PoseType,
        val headYaw: Float,
        val headPitch: Float
    )

    /** 默认结果：PROFILE 姿势、头部不偏转 —— 也正是改造前的行为。 */
    val DEFAULT = Result(PoseType.PROFILE, 0f, 0f)

    /** 鼠标静止多久后头部转回正前方；照 Cobblemon 详情页的 `MAX_SECONDS_LOOKING = 6` 秒。 */
    private const val LOOK_RESET_MS = 6_000L

    /**
     * @param renderable 该图标渲染的精灵（物种与形态决定有没有睡眠姿势、头部能不能转）
     * @param state      该图标的持久状态，跨帧的 yaw/pitch 从这里取
     * @param rotation   图标的固定旋转，头部角度要扣掉它才不会偏（全项目统一 XYZ 13°/35°/0°）
     * @param centerX    图标中心 X（屏幕坐标）
     * @param centerY    图标中心 Y（屏幕坐标）
     * @param mouseX     当前鼠标 X（屏幕坐标）
     * @param mouseY     当前鼠标 Y（屏幕坐标）
     * @param animate    该图标是否在播放动画
     * @param preview    是否处于预览态（悬停行 / 悬停大图）：切走路姿势，且用独立的一份 yaw/pitch
     *                   （大图与行内共用 state，共用一份会被互相拉拽）
     */
    fun resolve(
        renderable: RenderablePokemon,
        state: PosableState,
        rotation: Quaternionf,
        centerX: Float,
        centerY: Float,
        mouseX: Int,
        mouseY: Int,
        animate: Boolean,
        preview: Boolean = false
    ): Result {
        // 静态模式：不推进动画也不跟随鼠标（与 cry 点击、睡眠一致）
        if (!animate) return DEFAULT

        // 姿势优先级：睡眠时段（且该物种有睡眠姿态）> 预览态走路 > 待机。
        // 睡眠优先于预览，正是「白天 walk / 晚上 sleep」；晚上又没有 sleep 姿态的物种继续走 walk。
        val poseType = when {
            isSleeping(renderable) && hasSleepPose(state) -> PoseType.SLEEP
            preview -> walkPoseOrProfile(state)
            else -> PoseType.PROFILE
        }

        // 睡觉时头不跟随鼠标（用户拍板）。姿势的实际切换由 drawProfilePokemon 内部的
        // setPoseToFirstSuitable(poseType) 完成 —— 别在这里自己 setPose，会被那句覆盖（踩过）。
        if (poseType == PoseType.SLEEP) return Result(poseType, 0f, 0f)

        // 头部跟随鼠标：复刻 Cobblemon 详情页大模型的做法（atan 求角 + rotLerp 平滑 + 扣掉图标自身的旋转角）。
        // 无条件跟随：只悬停才跟的话，图标这么小可能一直触发不了。
        val animState = IconAnimationStates.of(state, preview)
        val (yaw, pitch) = calculateHeadYawAndPitch(
            xCoordinate = centerX,
            yCoordinate = centerY,
            rotation = rotation,
            mouseX = mouseX,
            mouseY = mouseY,
            currentYaw = animState.headYaw,
            currentPitch = animState.headPitch,
            // 鼠标长时间不动就把头转回正前方（详情页是「鼠标离开模型 6 秒」，见 mouseIdleMillis 注释）
            resetToCenter = IconAnimationStates.mouseIdleMillis(mouseX, mouseY) >= LOOK_RESET_MS
        )
        animState.headYaw = yaw
        animState.headPitch = pitch
        // 姿势：预览态且模型确实有走路姿势时切 walk，否则待机。
        // 这里只“返回”姿势类型，实际切换由 drawProfilePokemon 内部的 setPoseToFirstSuitable(poseType) 完成
        // —— 若在这里自己 setPose，紧接着就会被那句覆盖掉（踩过一次）。
        return Result(poseType, yaw, pitch)
    }

    /**
     * 预览态用的姿势：模型注册了 MOVING_POSES 的姿势就返回 [PoseType.WALK]，否则退回 PROFILE。
     *
     * **不能直接返回 WALK 了事**：`setPoseToFirstSuitable` 找不到合适姿势时会 fallback 到
     * `poses.values.first()`，模型会莫名抽一下（已查证其实现）。所以先自己确认有走路姿势。
     * 姿势名也不写死——走路姿势按介质不同（ground_walk / air_fly / water_swim），由模型自己挑。
     */
    private fun walkPoseOrProfile(state: PosableState): PoseType {
        val model = state.currentModel ?: return PoseType.PROFILE
        val hasWalk = model.poses.values.any { pose ->
            pose.poseTypes.any { it in PoseType.MOVING_POSES } && pose.isSuitable(state)
        }
        return if (hasWalk) PoseType.WALK else PoseType.PROFILE
    }

    /**
     * 该模型有没有可用的睡眠姿态。
     *
     * 判断条件与 `PosableModel.getFirstSuitablePose` 内部保持一致（`SLEEP in poseTypes && isSuitable(state)`），
     * **必须先自己确认**：`setPoseToFirstSuitable(SLEEP)` 找不到时不会保持原样，而是 fallback 到
     * `poses.values.first()`，模型会莫名抽一下（已查证）。
     *
     * `SLEEP` 是个槽位、一个物种可能注册多个（陆地 `sleep` / 浮水 `water_surface_sleep` / 沉水 `water_sleep` /
     * 战斗版 / 悬空版）。`FloatingState.getEntity() == null` 让 `isUnderWater` / `isInWater` / `isBattling`
     * 恒为 false，水中版与战斗版的条件自然不通过，落到陆地/野外版 —— 所以不用自己按 poseName 挑。
     */
    private fun hasSleepPose(state: PosableState): Boolean {
        val model = state.currentModel ?: return false
        return model.poses.values.any { pose ->
            PoseType.SLEEP in pose.poseTypes && pose.isSuitable(state)
        }
    }

    /** `behaviour.resting` 按「物种 + 形态」缓存：`getForm(aspects)` 每帧每图标调用有开销（满页 45 个）。 */
    private val restCache = HashMap<String, RestBehaviour>()

    /**
     * 游戏时间是否处于该物种的休息时段（复刻原版 `DrowsySensor` 的判定）。
     *
     * 刻意忽略 `drowsyChance` / `rouseChance` 随机与光照、方块条件 —— 那些是野外「真躺下」的门槛，
     * 图标只按物种作息走：到点睡、过点醒，不能忽睡忽醒。
     */
    fun isSleeping(renderable: RenderablePokemon): Boolean {
        val key = renderable.species.resourceIdentifier.toString() + renderable.aspects.sorted().joinToString(",")
        val rest = restCache.getOrPut(key) {
            renderable.species.getForm(renderable.aspects).behaviour.resting
        }
        if (!rest.canSleep) return false
        // 必须用 timeOfDay（一天内时间）—— 等价于原版的 `world.dayTime`。
        // **不能**用 `world.time`：那是世界总 tick 数，不受 /time set 影响，且新世界从 0 开始
        // （0 % 24000 = 0 恒等于白天），会让图标在明明是黑夜时死活不睡。
        val dayTime = MinecraftClient.getInstance()?.world?.timeOfDay ?: return false
        return (dayTime % 24000L).toInt() in rest.times
    }
}
