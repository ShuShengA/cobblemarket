package com.shusheng.cobblemarket.client.icon

import com.shusheng.cobblemarket.client.ClientRestCache
import com.shusheng.cobblemarket.client.CobbleMarketClient
import com.cobblemon.mod.common.client.gui.calculateHeadYawAndPitch
import com.cobblemon.mod.common.client.render.models.blockbench.PosableModel
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.client.render.models.blockbench.animation.ActiveAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.animation.PrimaryAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockAnimationRepository
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockPoseAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.pose.Pose
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext
import com.cobblemon.mod.common.client.render.models.blockbench.wavefunction.WaveFunction
import com.cobblemon.mod.common.client.render.models.blockbench.wavefunction.sineFunction
import com.cobblemon.mod.common.pokemon.ai.RestBehaviour
import net.minecraft.client.MinecraftClient
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.entity.Entity
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

        // 姿势优先级：战吼中 > 睡眠时段（且该物种有睡眠姿态）> 预览态走路 > 待机。
        // 睡眠优先于预览，正是「白天 walk / 晚上 sleep」；晚上又没有 sleep 姿态的物种继续走 walk。
        val poseType = when {
            // ⚠ 战吼期间强制待机姿势：点击图标时鼠标**必然停在那一行上** → 行内图标正处于悬停预览态
            //   → 不拦就会切到走路姿势、战吼与走路动画混在一起（2026-09-22 日志实测 `pose=walking`），
            //   既看不出战吼、身体姿态也不是待机那个。primary 播完框架会自动清空，姿势随之恢复。
            state.primaryAnimation != null -> PoseType.PROFILE
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

    /**
     * 游戏时间是否处于该物种的休息时段（复刻原版 `DrowsySensor` 的判定）。
     *
     * 刻意忽略 `drowsyChance` / `rouseChance` 随机与光照、方块条件 —— 那些是野外「真躺下」的门槛，
     * 图标只按物种作息走：到点睡、过点醒，不能忽睡忽醒。
     */
    fun isSleeping(renderable: RenderablePokemon): Boolean {
        // 作息表由服务端下发（见 RestSyncNetwork）：Cobblemon 的 PokemonBehaviour 网络同步
        // 只带 combat，resting 在专用服务器上永远是默认值（canSleep=false），直接读它会全域不睡。
        // 必须用 timeOfDay（一天内时间），**不能**用 world.time：那是世界总 tick 数，
        // 不受 /time set 影响，且新世界从 0 开始（0 % 24000 = 0 恒为白天），会让图标死活不睡。
        val dayTime = MinecraftClient.getInstance()?.world?.timeOfDay ?: return false
        return ClientRestCache.isSleeping(
            renderable.species.resourceIdentifier.toString(),
            (dayTime % 24000L).toInt()
        )
    }

    /**
     * 该物种的**动画组名**（组名不等于物种名：阿罗拉雷丘 `raichu_alolan`、洗翠火暴兽 `typhlosion_hisuian`），
     * 没有这个动画时返回 null，由调用方降级。
     *
     * `BedrockAnimationRepository` 的 key 是全名 `animation.<组名>.<动画名>`，组名 = 动画文件名，
     * 形态（aspects）拼在物种名后面，所以按「物种名 → 物种名_形态」逐个探测。
     *
     * ⚠ **别退回 `"battle_cry"` 这种短名**：`PosableModel.getAnimation` 的三级查找对短名全落空
     * （① 姿势的具名动画；② `animations` 表——各模型类手动注册的稀疏表；③ 兜底的 `extractAnimation`
     * 要求字符串形如 `组名:动画名`），于是静默落到 `cry` —— 而 `cry` 走模型的
     * `CryProvider { if (it.isBattling) battle_cry else cry }`，我们的 FloatingState 没有 entity、
     * `isBattling` 恒 false，拿到手的仍是普通 cry（2026-09-15 查证：此前写的 battle_cry 从未生效过）。
     */
    fun animationGroup(renderable: RenderablePokemon, animation: String): String? {
        val base = renderable.species.name.lowercase()
        val groups = buildList {
            add(base)
            renderable.aspects.forEach { aspect ->
                if (aspect.isNotBlank()) add("${base}_${aspect.lowercase()}")
            }
        }
        return groups.firstOrNull { group ->
            BedrockAnimationRepository.getAnimationOrNull(group, "animation.$group.$animation") != null
        }
    }

    /** 入场/收尾的过渡时长读不到 `transformTicks` 时的兜底（秒）。 */
    private const val DEFAULT_TRANSITION_SECONDS = 0.5f

    /**
     * 播放该物种的战吼；没有战吼（或模型还没建好）返回 false，由调用方降级到普通叫。
     *
     * 播的是 [PoseWithBattleCry]（把当前姿势的 idle 与战吼合成一条）并用 primary 独占 ——
     * 为什么非这样不可见该类的说明（单纯叠加会被姿势动画盖掉、单纯 primary 又会让姿势归零）。
     *
     * ⚠ **战吼是「战斗姿势专属」的**：poser 里 `battle_cry` 只注册在战斗姿势的
     * `namedAnimations.cry` 下（暴鲤龙/化石翼龙在 `battle-standing`），官方**从不在待机姿势下播它**。
     * 想复刻那个上下文得切到 `battle-standing`，但它的 `condition` 是 `isBattle = true`，
     * 我们的 FloatingState 没有 entity、条件不成立 —— `setPoseToFirstSuitable` 永远挑不到它
     * （2026-09-22 查证：`getFirstSuitablePose` 按 `poseTypes + isSuitable(condition)` 逐个筛，
     * 挑不到就 fallback 到第一个姿势）。合成动画正是对这个缺口的补偿。
     *
     * ⚠ 调用方必须**先渲染过一帧**：`currentModel` 是 `drawProfilePokemon` 里才建好的。
     */
    fun playBattleCry(state: PosableState, renderable: RenderablePokemon): Boolean {
        val group = animationGroup(renderable, "battle_cry")
        val model = state.currentModel
        CobbleMarketClient.LOGGER.info(
            "[battleCry] group=$group model=${model != null} pose=${state.currentPose} " +
                "active=${state.activeAnimations.size} " +
                "primary=${state.primaryAnimation != null} poseIntensity=${state.poseIntensity}"
        )
        if (group == null || model == null) return false
        val cry = BedrockAnimationRepository.getAnimationOrNull(group, "animation.$group.battle_cry")
            ?: return false
        // 用 primary 播「战吼 → 维持战斗姿态」两段式动画（见 PoseWithBattleCry 的说明）：
        // 独占是必须的 —— 否则框架后跑的姿势动画会再累加一份、把战吼淹掉。
        val anim = PoseWithBattleCry(
            cry = cry,
            transitionSeconds = battlePoseTransformSeconds(model) ?: DEFAULT_TRANSITION_SECONDS,
            selfContained = cryIsSelfContained(model, cry)
        )
        state.addPrimaryAnimation(
            PrimaryAnimation(
                animation = anim,
                excludedLabels = emptySet(),
                curve = { t -> anim.intensityAt(t) }
            )
        )
        return true
    }
}

/**
 * 「战吼 → 维持战斗姿态」两段式动画（用户 2026-09-22 提的设计，见下）。
 *
 * ## 为什么不能简单地把两条动画叠一起
 *
 * `BedrockAnimation.run` 写骨骼是**累加**的（`part.xRot += ...`），不是覆盖 ——
 * 所以「姿态 + 战吼」同时跑等于**两份旋转相加**：走路的手臂位置叠上抬手的战吼动作，
 * 手就跑到胸前去了（2026-09-22 实测）。火焰鸡此前「完全还原」恰恰是因为那时
 * 姿势被压到 0、**只有战吼一条在累加**。
 *
 * ## 野外那一下是什么样
 *
 * 野外战吼时 cry 是 primary，姿势动画的强度同样被压到 0 —— 也就是说**那 1.5 秒里
 * 并没有"战斗姿态"，只有 rest + battle_cry**；战斗姿态是**战吼之后**姿势强度恢复时的事。
 *
 * ## 所以分两段
 *
 * ```
 * 0 ~ cry 时长          只累加战吼       → 与野外那一下一致（干净、不掺走路姿势）
 * cry 时长 ~ +[HOLD_SECONDS]  只累加战斗 idle → 「维持战斗姿态」（用户要的那 3 秒）
 * ```
 *
 * 整段作为 primary 存在（独占），末段由 [battleCryCurve] 缓出、把控制权交还框架的姿势动画。
 */
/** Cobblemon 自己姿势过渡用的曲线（`PoseTransitionAnimation` 的默认值），原样照搬，观感才对得上。 */
private val TRANSITION_CURVE: WaveFunction =
    sineFunction(amplitude = 0.5F, period = 2F, phaseShift = 0.5F, verticalShift = 0.5F)

/** 该模型的「战斗姿势」（名字以 `battle-` 开头、排除睡眠那几个）；没有则 null。 */
private fun battlePoseOf(model: PosableModel): Pose? =
    model.poses.entries
        .firstOrNull { it.key.startsWith("battle-") && !it.key.contains("sleep") }
        ?.value

/**
 * 该物种战斗姿势的**过渡时长**（秒），读不到返回 null。
 *
 * 来源是 poser 里的 `transformTicks`（单位 tick）—— Cobblemon 的 `moveToPose` 就是用它决定
 * 姿势切换过渡多长的。**照读而不是写死**：官方以后给某个物种补上或调长这个字段时，
 * 我们这边要自动跟上、不用改代码（用户 2026-09-22 提）。
 */
private fun battlePoseTransformSeconds(model: PosableModel): Float? =
    battlePoseOf(model)?.transformTicks?.takeIf { it > 0 }?.let { it / 20f }

/**
 * 这条战吼**自带完整姿态**没有 —— 判据是「战吼动的骨骼数 vs 该模型战斗 idle 动的骨骼数」。
 *
 * 2026-09-22 实测三个物种，分界极干净（战吼 vs idle）：
 * - 火焰鸡 **65 vs 54**（战吼比 idle 动的还多）→ 自带完整姿态 → 战吼段**不能**补 idle
 * - 化石翼龙 19 vs 121、暴鲤龙 11 vs 61 → 只动了零头 → 身体没东西撑 → **必须**补 idle
 *
 * ⚠ 用**相对比例**而不是绝对骨骼数、也不列精灵名单：官方改动画、出新精灵都自动跟上。
 * ⚠ 化石翼龙是必须这么判的反例 —— 它战吼含 `torso`（旧判据「含躯干就算自带」会放它过去），
 *   但**没动翅膀分段** `wing_ground_left1~4`，那些骨骼回 rest pose、翅膀就成直片了。
 */
private fun cryIsSelfContained(model: PosableModel, cry: BedrockAnimation): Boolean {
    val idle = battlePoseOf(model)
        ?.animations
        ?.filterIsInstance<BedrockPoseAnimation>()
        ?.firstOrNull()
        ?.animation
        ?: return true // 拿不到 idle 就无从比较，按自带处理（至少不会叠加污染）
    return cry.boneTimelines.size >= idle.boneTimelines.size * 0.5f
}

private class PoseWithBattleCry(
    private val cry: BedrockAnimation,
    /** 入场/收尾的过渡时长（秒），见 [battlePoseTransformSeconds]；读不到时由调用方给兜底值。 */
    private val transitionSeconds: Float,
    /** 战吼是否自带完整姿态（见 [cryIsSelfContained]）：自带 ⇒ 战吼段不补 idle。 */
    private val selfContained: Boolean
) : ActiveAnimation {

    /** 「维持战斗姿态」的时长（秒）。用户 2026-09-22 要求 3~5 秒，取 3.5。 */
    private val holdSeconds = 3.5f

    /**
     * 战吼本身的时长（秒），之后才进维持段。
     *
     * ⚠ **优先保证战吼完整播完** —— 所以取该精灵自己的动画长度（`animationLength`），
     * 不是写死的常量：各精灵战吼长短不一，写死会把长的那批腰斩（用户 2026-09-22 提醒）。
     *
     * ⚠ 少数动画的 `animationLength` 会是 `0` 或 `-1`（未定义），那时退回保守值，
     * 免得整个战吼段被跳过、只剩维持段。
     */
    private val crySeconds = cry.animationLength.toFloat().takeIf { it > 0f } ?: 2f

    override val isTransition = false
    override val duration = crySeconds + holdSeconds
    override val enduresPrimaryAnimations = false

    private var startedSeconds = -1f

    override fun start(state: PosableState) {
        startedSeconds = state.animationSeconds
    }

    /**
     * 战吼进行到 [t]（0~1）时的 primary 强度：**两端缓动、中间满强度**。
     *
     * 两端都走 Cobblemon 自己的姿势过渡曲线（[TRANSITION_CURVE]，缓入缓出），不是线性 ——
     * 线性降权看起来就是"被匀速拖慢"，官方那条才是自然的手感（2026-09-22 用户报「慢动作」后查证）。
     * 这里 primary 强度与姿势动画的强度互补（`1 - 它`），两端因此都是"一边淡出一边淡入"的插值，
     * 与官方的 `PoseTransitionAnimation` 同构。
     */
    fun intensityAt(t: Float): Float {
        val elapsed = t * duration
        val fade = transitionSeconds.coerceAtMost(duration / 2f) // 防两端重叠
        // ① 入场：0 → 1 缓入。**必须做** —— 否则点图标那一刻姿势动画瞬间归零、
        //    战吼"啪"一下跳出来，看着像硬切（用户 2026-09-22：「主动触发叫声时没有过渡」）。
        if (elapsed < fade) return TRANSITION_CURVE((elapsed / fade).coerceIn(0f, 1f))
        // ② 收尾：1 → 0 缓出，把控制权交还框架的姿势动画
        val fadeStart = duration - fade
        if (elapsed > fadeStart) {
            return 1f - TRANSITION_CURVE(((elapsed - fadeStart) / fade).coerceIn(0f, 1f))
        }
        return 1f
    }

    override fun run(
        context: RenderContext,
        model: PosableModel,
        state: PosableState,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        headYaw: Float,
        headPitch: Float,
        intensity: Float
    ): Boolean {
        val elapsed = state.animationSeconds - startedSeconds
        return if (elapsed < crySeconds) {
            // ① 战吼段：只动末端骨骼的战吼要先补一份战斗 idle，否则身体没东西撑、掉回 rest pose。
            //    自带躯干的（火焰鸡那类）反而**不能**补 —— 累加会把两份旋转加起来（手跑到胸前）。
            if (!selfContained) {
                battleStanceIdle(model)
                    ?.run(model, state, state.animationSeconds, limbSwing, limbSwingAmount, ageInTicks, intensity)
            }
            cry.run(model, state, elapsed, limbSwing, limbSwingAmount, ageInTicks, intensity)
        } else {
            // ② 维持段：只跑战斗 idle，撑住"战斗姿态"
            // ⚠ 强度必须跟着 [intensity] 走，**不能写死 1f**：收尾缓出时框架的待机/走路 idle
            //   正从 0 渐渐恢复，这边若仍满强度，两条 idle 会同时满强度**累加**、姿势互相打架 ——
            //   观感就是「从战斗姿态切回待机的过程变成了慢动作」（2026-09-22 用户报）。
            //   跟着 intensity 走才是一进一出：战斗 idle 淡出、待机 idle 淡入。
            battleStanceIdle(model)
                ?.run(model, state, state.animationSeconds, limbSwing, limbSwingAmount, ageInTicks, intensity)
            elapsed < duration
        }
    }

    /**
     * 「战斗姿态」那条 idle：取名字以 `battle-` 开头的姿势的 idle 动画
     * （暴鲤龙/化石翼龙/火焰鸡都是 `battle-standing`；水面空中的精灵另有 `battle-*` 变体）。
     *
     * ⚠ **每帧现取而不是构造时取死**：点击那一刻鼠标还停在行上、姿势是 `walking`，
     * 那时取到的是 `ground_walk`，会把走路姿势焊进整段战吼（2026-09-22 日志实测 `idle=ground_walk`）。
     */
    private fun battleStanceIdle(model: PosableModel): BedrockAnimation? =
        battlePoseOf(model)
            ?.animations
            ?.filterIsInstance<BedrockPoseAnimation>()
            ?.firstOrNull()
            ?.animation

    /**
     * 转发战吼的音效与粒子关键帧（吼声、火焰鸡脚底那簇火）。
     *
     * ⚠ **必须自己转发**：Cobblemon 的 `PrimaryAnimation` **没有 override `applyEffects`**
     * （接口里是空实现），所以凡是走 primary 的动画，音效一律不播 —— 这正是
     * 「有些动画的音效丢了」的原因（丢的就是战吼那条，姿势动画的照常）。
     *
     * ⚠ **还要换算成动画内的时间**：`BedrockAnimation.applyEffects` 拿
     * `effect.seconds in previousSeconds..newSeconds` 判定，而这里收到的时间轴是 `state` 的
     * 全局秒数，不减掉起始时刻就永远对不上关键帧。
     */
    override fun applyEffects(entity: Entity?, state: PosableState, previousSeconds: Float, newSeconds: Float) {
        if (startedSeconds < 0f) return
        val prev = previousSeconds - startedSeconds
        val next = newSeconds - startedSeconds
        // ⚠ **两段都要判**：只判 next<0 的话，进入维持段后区间会被夹成 [大, crySeconds]、
        //   即 prev > next —— 而 BedrockAnimation 对「prev > next」走的是**跨循环**分支
        //   （`it.seconds >= prev || it.seconds <= next`），几乎命中所有关键帧 ⇒ **每帧重播全部音效**，
        //   听感就是声音卡死一样的噪音（2026-09-22 实测踩到）。
        if (next < 0f || prev >= crySeconds) return
        cry.applyEffects(entity, state, prev.coerceAtLeast(0f), next.coerceAtMost(crySeconds))
    }
}
