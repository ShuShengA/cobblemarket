package com.shusheng.cobblemarket.client.icon

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.client.ClientRestCache
import com.cobblemon.mod.common.client.gui.calculateHeadYawAndPitch
import com.cobblemon.mod.common.client.render.models.blockbench.PosableModel
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.client.render.models.blockbench.animation.ActiveAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.animation.PoseTransitionAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.animation.PrimaryAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockAnimationRepository
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockKeyFrameBoneValue
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockPoseAnimation
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockSoundKeyframe
import com.cobblemon.mod.common.client.render.models.blockbench.pose.Pose
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext
import com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
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

    /** 上一次因「水面姿势」退回待机的 `物种|姿势` —— 给日志去重，同一只只打一次。 */
    private var lastWaterFallback: String? = null

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
        // ⚠ **只有战吼（非过渡的 primary）才强制待机姿势** —— 姿势过渡**本身也是 primary**，
        //   但它期间必须让 poseType 保持**目标姿势**：否则 `drawProfilePokemon` 里的
        //   `setPoseToFirstSuitable` 会把 `currentPose` 切回待机，过渡一结束又判定"该睡"、再播一次
        //   ⇒ **1 秒一轮的死循环**（2026-09-22 实测「所有动画播一秒就重置」就是这么来的；
        //   悬停走路 WALK ⇄ PROFILE 同理）。
        val battleCryRunning = state.primaryAnimation?.let { !it.animation.isTransition } == true
        val poseType = when {
            // ⚠ 战吼期间强制待机姿势：点击图标时鼠标**必然停在那一行上** → 行内图标正处于悬停预览态
            //   → 不拦就会切到走路姿势、战吼与走路动画混在一起（2026-09-22 日志实测 `pose=walking`），
            //   既看不出战吼、身体姿态也不是待机那个。primary 播完框架会自动清空，姿势随之恢复。
            battleCryRunning -> PoseType.PROFILE
            isSleeping(renderable) && hasSleepPose(renderable, state) -> PoseType.SLEEP
            preview -> walkPoseOrProfile(renderable, state)
            else -> PoseType.PROFILE
        }

        // 姿势要变就播一次过渡（待机 ↔ 睡眠这类），别瞬切。详见 [ensurePoseTransition] 的注释。
        ensurePoseTransition(state, poseType)

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
     * 姿势要变时播一次过渡（待机 ↔ 睡眠这类），而不是瞬切。
     *
     * ⚠ **为什么现在能做、当年却判"做不了"**（2026-09-22 推翻了 2026-09-11 的结论）：
     * 当年卡在「`drawProfilePokemon` 每帧无条件 `setPoseToFirstSuitable`，过渡期间 `currentPose`
     * 会被切走」。但那其实**无害** —— primary 在跑时 `poseIntensity = 0`，`currentPose` 那条姿势的
     * 动画强度归零、**不参与画面**；而 `PoseTransitionAnimation` 是**自足**的（先 `model.setDefault()`，
     * 再自己把前后两个姿势按互补强度应用上去），压根不读 `currentPose`。**不需要 mixin。**
     *
     * ⚠ **必须在 `drawProfilePokemon` 之前调** —— `resolve()` 正好在那之前：此时 `currentPose`
     * 还是**上一帧的旧姿势**，正好当过渡起点；`drawProfilePokemon` 随后把 `currentPose` 切成新的
     * （强度 0、无害）。过渡播完 primary 自动清空，新姿势自然生效。
     *
     * 自洽性：过渡期间 `primaryAnimation != null` ⇒ 不会重复播；播完 `currentPose` 已是新姿势
     * ⇒ 判据不成立 ⇒ 也不会再播。
     */
    private fun ensurePoseTransition(state: PosableState, poseType: PoseType) {
        // ① 先清掉**已经播完**的过渡。
        //   ⚠ **必须自己清**：`PoseTransitionAnimation.run` 的返回值是 `ratio <= 1F`，
        //   而它上面有 `min(..., 1F)` ⇒ **恒为 true**，框架永远等不到它返回 false，
        //   primary 会一直挂着、`poseIntensity` 恒为 0 ⇒ **所有姿势动画都不生效**。
        //   症状（2026-09-22 实测踩到）：精灵晚上睡下后**白天醒不过来**，重开界面新建 state 才恢复。
        //   官方的 `moveToPose` 是靠另设 `afterAction` 收尾清 primary 的；我们不走那条路，自己清更可控。
        val running = state.primaryAnimation
        if (running != null && running.animation.isTransition &&
            running.started + running.duration <= state.animationSeconds
        ) {
            state.primaryAnimation = null
            state.poseIntensity = 1F
        }

        // 已经有东西在跑（战吼、上一次过渡）就不打断
        if (state.primaryAnimation != null) return
        val transition = poseTransitionTo(state, poseType) ?: return
        state.addPrimaryAnimation(
            PrimaryAnimation(animation = transition, excludedLabels = emptySet(), curve = { 1f })
        )
    }

    /**
     * 造一条「**当前姿势 → [poseType]**」的过渡动画；**不需要过渡时返回 null**（调用方按"零延迟"处理）。
     *
     * 两种返回 null 的情况：
     * - 首次渲染还没有 `currentPose` —— 没有旧姿势可插值，交给 `drawProfilePokemon` 直接切（从无到有）
     * - 已经站在目标姿势上 —— 没什么可过渡的
     *
     * 过渡时长读 poser 的 `transformTicks`（见 [poseTransitionTicks]），不写死。
     * 本函数只**造**不**播**：调用方自己决定是当独立的 primary 播（[ensurePoseTransition]），
     * 还是接在别的动画前面当入场段（[playBattleCry] 的战吼起手）。
     */
    /**
     * ⚠⚠⚠ **临时诊断开关（2026-10-07，用完删）**：战吼入场过渡的放慢倍数，正常值 = `1`。
     *
     * 用途：用户报「悬停聊天大图时从 walk 到战吼会**闪一下看不清是什么**的动作」，
     * 把它调大后那段过渡变慢、肉眼能看清 —— 看清了才知道要修哪儿。
     * ⚠ 这是**诊断版**，定位完必须改回 `1`。
     */
    private const val BATTLE_CRY_TRANSITION_SLOWDOWN = 1

    /**
     * ⚠⭐ **战吼入场要不要先播一段姿势过渡 —— 2026-10-07 用户实测定案：`false`（直接切换）。**
     *
     * ## 为什么最终选择"直接切换"
     *
     * 起因是用户报「走路 → 战吼动作不连贯（腿啪地换掉）」，于是加了入场过渡（[PoseTransitionThenBattleCry]）。
     * 但实测把三种做法全试了一遍，**跨类型姿势切换（喷火龙：水面游动 ⇄ 直立）没有"平滑"可言**：
     *
     * | 做法 | 原理 | 实测观感 |
     * |---|---|---|
     * | **直接切换**（本开关 = `false`） | 一帧内换姿势 | ✅ 不塌不扭曲；只是"啪"一下 —— **最终采用** |
     * | **官方的 `PoseTransitionAnimation`**（两套动画互补叠加） | 走路 ×(1−t) **+** 站立 ×t | ❌ 「闪一下奇怪的画面」—— 两个**偏移量相加**（往水平躺 + 站直）出来一个**拧着的角度** |
     * | **只淡出当前姿势的动画** | 走路 ×(1−t)，目标姿势不跑动画 | ❌ 更糟：偏移量归零 ⇒ **回模型原始建模姿势**（用户原话「头伸直往上、其他关节全都伸直僵硬」） |
     *
     * **根因（别再重复试）**：Cobblemon 的关键帧是**相对模型原始姿势的偏移量**，而 `BedrockAnimation`
     * 写骨骼是**累加**的（`part.xRot += value * intensity`）⇒ 两套动画的权重混合 = **偏移量相加**，
     * 只有当两套动画本来就接近（`ground_idle` ⇄ `battle_idle` 这类官方场景）时才近似正确。
     * 喷火龙的「水面游动」和「直立待机」差了一个 90° 的身体朝向 ⇒ 相加、归零都不对。
     *
     * ⚠ 官方那个过渡类不是"更高级的插值"，它对**跨类型**姿势一样无能为力 —— 别再回头试它。
     * ⚠ 真要再做，方向只能是"不切姿势"（战吼在走路姿势下播）—— 但那会与走路动画**累加**打架
     *   （2026-09-22 已经踩过：见 [PoseWithBattleCry] 的说明），**也不是路**。
     */
    private const val BATTLE_CRY_POSE_TRANSITION = false

    private fun poseTransitionTo(
        state: PosableState,
        poseType: PoseType,
        /** 诊断用的放慢倍数；**正式代码一律不传**（默认 1） */
        ticksScale: Int = 1
    ): PoseTransitionAnimation? {
        val model = state.currentModel ?: return null
        val current = state.currentPose?.let { model.poses[it] } ?: return null
        val target = model.getFirstSuitablePose(state, poseType)
        if (target.poseName == current.poseName) return null
        val ticks = (poseTransitionTicks(current, target) * ticksScale).coerceAtLeast(1)
        if (BATTLE_CRY_TRANSITION_SLOWDOWN != 1) {
            CobbleMarket.LOGGER.info(
                "[icon-anim] battle-cry transition: from={} to={} ticks={}x{} poseType={}",
                current.poseName, target.poseName, ticks, ticksScale, poseType
            )
        }
        return PoseTransitionAnimation(beforePose = current, afterPose = target, durationTicks = ticks)
    }

    /**
     * 姿势过渡时长（tick）：官方 `moveToPose` 取的是「目标的 `transformToTicks` ?: 源的 `transformTicks`」，
     * 照抄 —— 读 poser 而不是写死，官方调了我们自动跟上。
     */
    private fun poseTransitionTicks(from: Pose, to: Pose): Int =
        (to.transformToTicks ?: from.transformTicks).takeIf { it > 0 } ?: 10

    /**
     * 预览态用的姿势：模型注册了 MOVING_POSES 的姿势就返回 [PoseType.WALK]，否则退回 PROFILE。
     *
     * **不能直接返回 WALK 了事**：`setPoseToFirstSuitable` 找不到合适姿势时会 fallback 到
     * `poses.values.first()`，模型会莫名抽一下（已查证其实现）。所以先自己确认有走路姿势。
     * 姿势名也不写死——走路姿势按介质不同（ground_walk / air_fly / water_swim），由模型自己挑。
     */
    private fun walkPoseOrProfile(renderable: RenderablePokemon, state: PosableState): PoseType {
        val model = modelFor(renderable, state) ?: return PoseType.PROFILE
        val candidates = model.poses.values.filter { pose ->
            pose.poseTypes.any { it in PoseType.MOVING_POSES }
        }
        val hasWalk = candidates.any { it.isSuitable(state) }

        if (!hasWalk) return PoseType.PROFILE

        // ⚠⚠ **无实体 state 上「水里 / 陆地」这层区分是失效的**（2026-10-12 查实）：
        //   Cobblemon 的 `PoseAdapter` 把 `isTouchingWater` 这类条件编译成
        //   `{ it.getEntity()?.let { e -> … } ?: true }` —— 我们的图标**没有实体** ⇒ **条件一律为真**
        //   ⇒ `getFirstSuitablePose` 退化成「取注册顺序里第一条带 WALK 的」。
        //   喷火龙 / 皮卡丘这类的水面版恰好排在陆地版前面 ⇒ 在陆地上播「划水」。
        //
        //   这里**不去猜那个顺序**（离线算不出来，实测已证它 ≠ JSON 文件里的顺序），
        //   而是直接问渲染自己要调的那个函数 —— 它返回谁、画面就是谁。
        //   若那条播的是水面动画，就**这一帧不请求 WALK、退回待机**：宁可不动，也不播错的。
        //   ⚠ 代价：这些物种失去走路预览。**别写成"跟官方 PC 一致"** ——
        //     官方图标**从来就没有**走路动画（只有点击吼叫与转头），这是我们自己的功能缺口。
        val chosen = runCatching { model.getFirstSuitablePose(state, PoseType.WALK) }.getOrNull()
        if (chosen != null && playsWaterAnimation(chosen)) {
            val sp = renderable.species.name
            if ("$sp|${chosen.poseName}" != lastWaterFallback) {
                lastWaterFallback = "$sp|${chosen.poseName}"
                println("[CM-Walk-Fallback] $sp 官方会选=${chosen.poseName} 动画=${poseAnimationNames(chosen)} → 退回待机")
            }
            return PoseType.PROFILE
        }

        return PoseType.WALK
    }

    /** 这条姿势实际播的 bedrock 动画全名（形如 `animation.pikachu.surfacewater_swim`）。 */
    private fun poseAnimationNames(pose: Pose): List<String> =
        pose.animations.filterIsInstance<BedrockPoseAnimation>().map { it.animation.name }

    /**
     * 这条姿势是不是**水面姿势** —— 判据是它**实际播的动画名**里带 `water` / `seafloor`。
     *
     * ⚠ **只看动画名、不看姿势名**（2026-10-12 全量核对）：`Caterpie` / `Weedle` 的姿势名叫
     *   `swimming` 却播 `ground_walk`，`Muk` 的 `surface_swimming` 播 `ground_idle`
     *   ⇒ 用姿势名会**误伤这几只**（它们本来是对的）。
     * ⚠ **也不能只看 `swim`**：`sand_swim`（沙里游）会被一起抓进来 —— 那是**沙地**姿势不是水，
     *   对 `Sandile` / `Krokorok` / `Krookodile` 反而未必算错（2026-10-12 用户拍板：沙地不算）。
     */
    private fun playsWaterAnimation(pose: Pose): Boolean =
        poseAnimationNames(pose).any { n ->
            n.contains("water", ignoreCase = true) || n.contains("seafloor", ignoreCase = true)
        }

    /**
     * 取这个图标当前的模型：**优先用已经建好的** `currentModel`（O(1)），首帧还没有时**自己按物种取一个**。
     *
     * ⚠ **为什么非这样不可**：`currentModel` 是**第一次 `drawProfilePokemon` 内部**才建的
     * （`PokemonGuiUtils` 里那句 `state.currentModel = model`），而那一句拿到的 `poseType`
     * 又是我们先算好传进去的 ⇒ **首帧必然拿不到模型**。于是「该不该睡」「有没有走路姿势」
     * 这两个判断首帧双双落空、退回 PROFILE（待机），要到**第二帧**才判对；此时若要切
     * 睡眠 / 走路，[ensurePoseTransition] 就会补一段**待机 → 睡眠**的过渡动画。
     * 表现：「**晚上打开界面 / 翻页，精灵先站着、再慢慢躺下**」（2026-09-25 用户报）。
     *
     * ⚠ **别指望在过渡那一层拦**：首帧本来就判不出该睡，过渡只是把「迟一帧」放大成了
     * 一段看得见的动画 —— 拦过渡等于把瞬切放行，第 1 帧那一下还在。
     *
     * 取法与 `drawProfilePokemon` 内部**完全一致**（就是它建模型用的那句），
     * `species` 也照它那样从 `renderablePokemon.species.resourceIdentifier` 取。
     * ⚠ `getPoser` 只是**取**、不会写 `state.currentModel`（写它的是 `drawProfilePokemon`），
     * 所以这里调用没有副作用；取过之后 `drawProfilePokemon` 那句拿到的是同一个实例。
     */
    private fun modelFor(renderable: RenderablePokemon, state: PosableState): PosableModel? =
        state.currentModel ?: runCatching {
            // 包一层：`getPoser` 内部走脱 `variations[fallback]!!`，取不到会抛 ——
            // 那种情况下退回老行为（判不出、走 PROFILE），别让一个图标带崩整个界面
            VaryingModelRepository.getPoser(renderable.species.resourceIdentifier, state)
        }.getOrNull()

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
    private fun hasSleepPose(renderable: RenderablePokemon, state: PosableState): Boolean {
        val model = modelFor(renderable, state) ?: return false
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
    fun playBattleCry(state: PosableState, renderable: RenderablePokemon): Boolean =
        playBattleAction(state, renderable, "battle_cry")

    /**
     * 播放该物种的**战斗入场**动画（`battle_intro`）；没有 / 模型没建好返回 false。
     *
     * 2026-10-18 新增：苍炎刃鬼这类物种的 poser 在 `standing` 上定义了
     * `"battle-standing": q.bedrock_primary(..., 'battle_intro')` 这条过渡 —— 放出来的精灵
     * 在**别的精灵开战**时也会进入战斗状态（`BattleRegistry` 给参战玩家整队实体都设 `battleId`），
     * 于是从站姿播这套入场动作（多数自带叫声关键帧），观感与 `battle_cry` 完全不同。
     *
     * 与战吼共用 [PoseWithBattleCry]：入场动画同样是「自带战斗姿态的一整段动作」，
     * 播完维持战斗姿态、再淡出交还姿势动画。
     */
    fun playBattleIntro(state: PosableState, renderable: RenderablePokemon): Boolean =
        playBattleAction(state, renderable, "battle_intro")

    /**
     * 点击图标时可随机播放的叫声类动画名（**至少含 `cry`**）。
     *
     * - `battle_cry`：该物种有就加入（沿用原来的「战吼」选项）
     * - `battle_intro`：仅当该物种有、且动画里**带声音关键帧**时加入 —— 它是「站姿 → 战斗姿态」的
     *   入场动作，有些物种（如烈咬陆鲨）整条**没有任何音效**，放进点击会让那次点击「只动不响」，
     *   违反「点击精灵图标必须有叫声」的约定
     *
     * ⇒ 苍炎刃鬼这类两样都有的物种是**三选一**；普通物种维持原来的二选一 / 只有普通叫。
     */
    fun cryAnimationNames(renderable: RenderablePokemon): List<String> {
        val names = mutableListOf("cry")
        if (animationGroup(renderable, "battle_cry") != null) names += "battle_cry"
        if (hasBattleIntroWithSound(renderable)) names += "battle_intro"
        return names
    }

    /**
     * 庆祝动画（买到 / 拍到 / 求购接受交付）的登场吼叫：**战吼 / 战斗入场二选一**，都没有返回 false。
     *
     * 与点击图标不同，这里**不掺普通叫** —— 原来的行为就是「固定战吼」，2026-10-18 用户只要求把它改成
     * 「战吼 / 战斗入场二选一」；调用方在没有战吼时仍降级到 `cry`（维持原兜底）。
     *
     * ⚠ `battle_intro` 同样要求自带声音关键帧（见 [hasBattleIntroWithSound]），否则会「只动不响」。
     */
    fun playCelebrationCry(state: PosableState, renderable: RenderablePokemon): Boolean =
        when (listOfNotNull(
            "battle_cry".takeIf { animationGroup(renderable, "battle_cry") != null },
            "battle_intro".takeIf { hasBattleIntroWithSound(renderable) }
        ).randomOrNull()) {
            "battle_cry" -> playBattleCry(state, renderable)
            "battle_intro" -> playBattleIntro(state, renderable)
            else -> false
        }

    /**
     * 该物种是否有 `battle_intro`，且动画里**带声音关键帧**。
     *
     * 声音门槛是必须的：`battle_intro` 是「站姿 → 战斗姿态」的入场动作，个别物种（烈咬陆鲨）
     * 整条**没有任何音效**，进随机池会让那次播放「只动不响」。
     */
    private fun hasBattleIntroWithSound(renderable: RenderablePokemon): Boolean {
        val group = animationGroup(renderable, "battle_intro") ?: return false
        val intro = BedrockAnimationRepository.getAnimationOrNull(group, "animation.$group.battle_intro")
            ?: return false
        return intro.effects.any { it is BedrockSoundKeyframe }
    }

    /** [playBattleCry] / [playBattleIntro] 的共同实现：取名、判断自带姿态、用 primary 播两段式。 */
    private fun playBattleAction(
        state: PosableState,
        renderable: RenderablePokemon,
        animationName: String
    ): Boolean {
        val group = animationGroup(renderable, animationName)
        val model = state.currentModel
        if (group == null || model == null) return false
        val cry = BedrockAnimationRepository.getAnimationOrNull(group, "animation.$group.$animationName")
            ?: return false
        // 用 primary 播「战吼 → 维持战斗姿态」两段式动画（见 PoseWithBattleCry 的说明）：
        // 独占是必须的 —— 否则框架后跑的姿势动画会再累加一份、把战吼淹掉。
        val selfContained = cryIsSelfContained(model, cry)
        logBattleCryDiagnostics(renderable, model, state, group, cry, selfContained)
        val anim = PoseWithBattleCry(
            cry = cry,
            // ⚠ 诊断版：把入场缓动也一起放慢（见 [BATTLE_CRY_TRANSITION_SLOWDOWN]）——
            //   用户报「闪一下」，但过渡段放慢后闪**没跟着变慢** ⇒ 需把战吼入场也拉长才能定位它在哪一段。
            transitionSeconds = (battlePoseTransformSeconds(model) ?: DEFAULT_TRANSITION_SECONDS) *
                BATTLE_CRY_TRANSITION_SLOWDOWN,
            selfContained = selfContained
        )
        // ⚠⭐ **战吼的入场姿势过渡（`BATTLE_CRY_POSE_TRANSITION`）2026-10-07 试过三轮、最终关掉**：
        //   背景是用户报「走路 → 战吼动作不连贯（腿啪地换掉）」—— 成因确实是战吼强制 `PROFILE` 让
        //   `setPoseToFirstSuitable` 把 `currentPose` **瞬切**（走路那套动画当帧从播放列表消失）。
        //   但补过渡的两种做法实测都更糟（拧着的怪画面 / 回原始建模姿势），
        //   **跨类型姿势切换没有"平滑"可言** —— 完整实测记录与根因见 [BATTLE_CRY_POSE_TRANSITION]。
        //   ⇒ 现在就是**直接切换**：那一"啪"是数据层的固有代价，不塌不扭曲。
        //   ⚠ 开关留着只为将来官方改了动画机制时好重试（组合动画本体的说明见 [PoseTransitionThenBattleCry]）。
        val transition = if (!BATTLE_CRY_POSE_TRANSITION) null else
            poseTransitionTo(state, PoseType.PROFILE, BATTLE_CRY_TRANSITION_SLOWDOWN)
        if (transition == null) {
            state.addPrimaryAnimation(
                PrimaryAnimation(
                    animation = anim,
                    excludedLabels = emptySet(),
                    curve = { t -> anim.intensityAt(t) }
                )
            )
        } else {
            val combined = PoseTransitionThenBattleCry(transition, anim)
            state.addPrimaryAnimation(
                PrimaryAnimation(
                    animation = combined,
                    excludedLabels = emptySet(),
                    curve = { t -> combined.intensityAt(t) }
                )
            )
        }
        return true
    }
}

/**
 * 「战吼 → 维持战斗姿态」两段式动画（用户 2026-09-22 提的设计，见下）。
 *
 * ⚠ 2026-10-18 起也用于 `battle_intro`（苍炎刃鬼这类「站姿 → 战斗姿态」的入场动作）——
 * 两者都是「自带战斗姿态的整段动作」，播放机制完全相同，类名沿用历史。
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

/**
 * 是不是「战斗姿势」的名字（见 [battlePoseOf] 的说明）。
 *
 * ⚠ **必须规范化后判，不能认死某一种写法**：Cobblemon 1.8.1 正在把精灵姿势从
 * Kotlin 硬编码迁到 poser JSON，**两套命名同时在用**，而且同一套里的各物种也不统一 ——
 * 2026-09-23 全量扫描的实际分布：
 *
 * | 写法 | 用它的物种数 |
 * |---|---|
 * | `battle_idle` | 93 |
 * | `battleidle` | 19 |
 * | `battlestanding` | 3 |
 * | `battle_standing` | 2 |
 * | `battle-standing` | 只出现在**已迁移**物种的 poser JSON 里 |
 *
 * 只认连字符那版 ⇒ **271 个还没迁移的物种全部找不到战斗姿势**（铁骨土人就是这么中招的：
 * 它的姿势叫 `battleidle`、战吼又是"叠加式"的，于是战斗 idle 补不上、手臂停在身侧）。
 *
 * ⇒ 判据 = **去掉 `-` `_` 并转小写后含 `battle`**：官方再改命名、迁移到哪一步都自动跟上。
 * ⚠ 排除 `sleep`（`battlesleep` 是睡觉姿势，不是战斗姿势）。
 * ⚠ 水面变体（`water_battle_idle` / `surface_battle_idle`）也会命中 —— 靠下面的**名字排序**
 *   把它们排到 `battle_*` 之后（`w` / `s` > `b`），陆地物种仍优先拿到陆地战斗姿势。
 */
private fun String.isBattlePoseName(): Boolean {
    val n = lowercase().replace("-", "").replace("_", "")
    return "battle" in n && "sleep" !in n
}

/**
 * 战吼诊断日志的开关（**默认关**，用户 2026-09-23 要求）。
 *
 * ⚠ 默认关的理由：**服主看到满屏 `[icon-anim]` 会有疑问**（"这是什么？是不是出问题了？"）——
 * 这条日志是给**开发排查**用的，不是给服主看的。
 *
 * 排查「某只精灵战吼不对」时把它改成 `true`：每个物种**首次点击**打一行，
 * 含 `species` / `pose` / `cryBones` / `idleBones` / `coverage` / `selfContained` /
 * `idleLen`（补跑 idle 的循环长度）/ `globalT`（全局动画秒数）/ `idleT`（取模后的实际传入时间）——
 * 对照 `docs/icon_animation.md`「战吼出问题时的排查」那张症状表即可定位该往哪边调。
 */
private const val BATTLE_CRY_DIAG = false

/** 诊断日志去重：每个物种只打一次（开关打开时才有意义）。 */
private val battleCryDiagLogged = mutableSetOf<String>()

private fun logBattleCryDiagnostics(
    renderable: RenderablePokemon,
    model: PosableModel,
    state: PosableState,
    group: String,
    cry: BedrockAnimation,
    selfContained: Boolean
) {
    if (!BATTLE_CRY_DIAG) return
    if (!battleCryDiagLogged.add(renderable.species.name)) return
    val pose = battlePoseOf(model)
    val idles = pose?.animations?.filterIsInstance<BedrockPoseAnimation>()?.map { it.animation } ?: emptyList()
    val idleBones = idles.flatMap { it.boneTimelines.keys }.toSet()
    // idleAnims / idleBones：姿势里到底跑了几条 idle、合计覆盖多少骨骼 ——
    // 只取第一条的旧写法在火岩鼠上取到了只管 2 根骨骼的装饰动画 `fire_idle`（见 [battleStanceIdles]）。
    // idleLen / globalT / idleT：针对「战吼期间身体冻成模型原始姿势」的问题（见 [loopedSeconds]），
    // 取**骨骼最多的那条**当主 idle；idleT 是取模后真正拿去取关键帧的时间，应落在 [0, idleLen)。
    val mainIdle = idles.maxByOrNull { it.boneTimelines.size }
    CobbleMarket.LOGGER.info(
        "[icon-anim] battle cry: species={} group={} pose={} cryBones={} idleAnims={} idleBones={} coverage={} selfContained={} " +
            "idleLen={} globalT={} idleT={}",
        renderable.species.name,
        group,
        pose?.poseName ?: "(none)",
        cry.boneTimelines.size,
        idles.size,
        idleBones.size,
        battleCryCoverage(model, cry)?.let { "%.2f".format(it) } ?: "(n/a)",
        selfContained,
        mainIdle?.let { "%.2f".format(loopLengthOf(it)) } ?: "(n/a)",
        "%.1f".format(state.animationSeconds),
        mainIdle?.let { "%.2f".format(loopedSeconds(it, state)) } ?: "(n/a)"
    )
}

/**
 * 该模型的「战斗姿势」；没有则 null。
 *
 * ⚠ **优先精确取 `battle-standing`**（已迁移物种的规范名，陆地站立的战斗姿势），两条弯路都别再走：
 * - **不能按前缀 `firstOrNull` 取**：1.8.1 把 `poses` 的 map 换成了无序的 `HashMap`，
 *   喷火龙的 `battle-standing` / `battle-flying` 谁在前是随机的 —— 用户实测战吼后"变成飞行"。
 * - **也不能按"当前姿势的介质"去配**（站立→standing、飞行→flying）：点击图标时鼠标必然停在
 *   那一行上、当前姿势是**悬停的走路态**，喷火龙那里是飞行 ⇒ 又会配成 `battle-flying`（同样实测点破）。
 *
 * 本来就飞行 / 水生的物种、以及**还没迁移到 poser JSON** 的那批（战斗姿势叫 `battle_idle`
 * 等其他写法，见 [isBattlePoseName]）⇒ 落到兜底；兜底按**名字排序**，
 * 保证结果稳定、不再依赖 Map 顺序。
 */
private fun battlePoseOf(model: PosableModel): Pose? =
    model.poses["battle-standing"]
        ?: model.poses.keys
            .filter { it.isBattlePoseName() }
            .sorted()
            .firstOrNull()
            ?.let { model.poses[it] }

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
 * 「战吼自带完整姿态」的判据阈值：战吼要覆盖战斗 idle 多少**比例**的骨骼才算自带。
 *
 * ⚠ 2026-09-23 从「骨骼**数量**比 ≥ 0.5」改成「集合**覆盖**率 ≥ 0.9」，原因是数量比会放过
 * "骨骼数够多、但动的全是增量"的那种战吼 —— **铁骨土人就是这个反例**：`battle_cry` 32 骨骼
 * vs `battle_idle` 52，数量比 0.62 过了老阈值被判"自带"，可它动的 `arm_right` 只有 -17.5°，
 * 而 idle 里是 -187° —— 不补 idle 的话这些增量全作用在 rest pose 上，**手臂停在身侧、钢材举不起来**
 * （用户 2026-09-23 实测发现）。
 *
 * 集合覆盖率能抓住这一点：它问的是「战斗 idle 动的那些骨骼，战吼**是否都动了**」，
 * 漏掉任何一根都意味着那根停在 rest pose（化石翼龙缺 `wing_ground_left1~4` 就是同一类问题）。
 *
 * ⚠ **阈值 0.8 是实测校准出来的**（2026-09-23，用户逐个点过去看）：
 * 需要叠加的（**铁骨土人 0.60** ✅ 实测修好）与不该叠加的（**火焰鸡 0.96**、**巨锻匠 1.00**、
 * **铝钢桥龙 0.96** —— 试"永远叠加"时分别表现为手偏中间 / 锤子斜着立起来还站在空气上 /
 * 脚底伸出两根笔直的棍子 ❌）之间，数据分界落在 **0.71 ~ 0.89**，取中点 0.8。
 * ⚠ 别再用"永远叠加"一刀切 —— 试过了，上面那三个物种当场就坏（[ALWAYS_LAYER_BATTLE_CRY] 那次实验）。
 */
private const val SELF_CONTAINED_COVERAGE = 0.8f

/**
 * ⚠ **实验开关**（2026-09-23，用户提议）：战吼**永远叠加**在战斗 idle 上，不再判断"要不要补"。
 *
 * 依据是官方的做法：**官方播战吼从不独占、也从不判断** —— `PokemonClientDelegate.cry()` 里
 * 拿到的 `cryAnimation` 若不是 `PrimaryAnimation` 就走 `activeAnimations.add(...)`，也就是
 * **叠加在当前（战斗）姿势之上**。既然官方永远叠加，我们也永远叠加，就**不需要再猜**
 * （那套 [SELF_CONTAINED_COVERAGE] 判据是"我们把精灵弄成待机姿势、又想独占播战吼"才需要补的窟窿）。
 *
 * ⚠ 之所以不能整条照搬官方：官方靠 `PosableState.isBattling`（扩展属性，只能从真实体取）让框架
 * 自动选到战斗姿势，而我们的图标是**无实体的假精灵** ⇒ 框架永远选 `standing` ⇒ 只能自己在
 * primary 里把战斗 idle 应用上去。这个开关就是"自己模拟官方"的开关。
 *
 * ⚠ **2026-09-23 实测结论：这条一刀切是错的，已回滚为 `false`。**
 * - 铁骨土人（覆盖率 0.60）叠加后**修好了** ✅
 * - 但 **火焰鸡（0.96）手偏中间、巨锻匠（1.00）锤子斜着立起来、精灵站在空气上** ❌
 *   —— 典型的"两份旋转相加"（巨锻匠的锤子转了近两倍角度，位置自然就飘了）
 *
 * ⇒ 两种战吼**确实并存**，判据必须留着：`true` = 永远叠加（实验用）；
 * `false` = 按 [SELF_CONTAINED_COVERAGE] 判断（**当前**）。
 */
private const val ALWAYS_LAYER_BATTLE_CRY = false

/**
 * 「战斗姿态」这个姿势里**所有**的 bedrock 动画 —— 框架跑姿势动画时是**全跑**的，我们照做。
 *
 * ⚠⚠ **不能只取第一条**（原来的 `firstOrNull()`，2026-09-23 火岩鼠暴露的坑）：
 * 一个姿势的 `animations` 是「头部跟随 + 若干 bedrock 动画」的列表，而**装饰性动画可能排主 idle 前面**。
 * 火岩鼠的 `battle_idle` 姿势 = `[singleBoneLook, fire_idle(2 骨骼), battle_idle(20 骨骼)]` ——
 * 过滤掉 look 后取第一条拿到的是 **`fire_idle`**（只管背后那簇火），**身体和四条腿全程没人驱动** ⇒
 * 战吼时掉回模型原始姿势（前腿竖直、后腿水平伸直，用户 2026-09-23 报）；而且覆盖率按那 2 根骨骼算
 * 得到 1.00 ⇒ 被误判成「自带完整姿态」⇒ 连补 idle 都省了。
 * ⇒ 一律**全跑**（`singleBoneLook` 不是 [BedrockPoseAnimation]，自然被过滤掉；头部跟随图标另有实现）。
 * 未迁移物种里只有火岩鼠（含洗翠形态）踩中；烈焰马（伽勒尔）也挂了 `hair`，但它排在 `battle_idle` 后面，恰好没事。
 *
 * ⚠ **每帧现取而不是构造时取死**：点击那一刻鼠标还停在行上、姿势是 `walking`，
 * 那时取到的是 `ground_walk`，会把走路姿势焊进整段战吼（2026-09-22 日志实测 `idle=ground_walk`）。
 */
private fun battleStanceIdles(model: PosableModel): List<BedrockAnimation> =
    battlePoseOf(model)
        ?.animations
        ?.filterIsInstance<BedrockPoseAnimation>()
        ?.map { it.animation }
        ?: emptyList()

/**
 * 这条战吼覆盖了战斗 idle 多少比例的骨骼（集合覆盖率）；拿不到 idle 返回 null。
 *
 * ⚠ 是**集合**口径（`cry` 的骨骼里有几成出现在 idle 的骨骼里），不是数量比 ——
 * 见 [SELF_CONTAINED_COVERAGE] 里铁骨土人的反例。
 * ⚠ 分母用姿势里**所有** idle 动画的骨骼**并集**（不是某一条的骨骼数）—— 与 [battleStanceIdles]
 * 全跑的语义对齐，否则像火岩鼠那样"第一条只管 2 根骨骼"会把覆盖率虚高成 1.00。
 */
private fun battleCryCoverage(model: PosableModel, cry: BedrockAnimation): Float? {
    val idleBones = battleStanceIdles(model).flatMap { it.boneTimelines.keys }.toSet()
    if (idleBones.isEmpty()) return null
    val covered = cry.boneTimelines.keys.count { it in idleBones }
    return covered.toFloat() / idleBones.size
}

/**
 * 这条战吼**自带完整姿态**没有。
 *
 * ⚠ 判据是 [battleCryCoverage]（集合覆盖率 ≥ [SELF_CONTAINED_COVERAGE]）：
 * - 自带（火焰鸡那类）→ 战吼段**不补** idle，补了反而两份旋转累加（手跑到胸前）
 * - 不自带（化石翼龙、暴鲤龙、**铁骨土人**那类）→ 必须补，否则身体没东西撑、掉回 rest pose
 *
 * ⚠ 用**相对比例**而不是绝对骨骼数、也不列精灵名单：官方改动画、出新精灵都自动跟上。
 * ⚠ 拿不到战斗 idle 时按"自带"处理 —— 无从比较，而且至少不会叠加污染。
 */
private fun cryIsSelfContained(model: PosableModel, cry: BedrockAnimation): Boolean {
    // 实验：永远叠加（见 [ALWAYS_LAYER_BATTLE_CRY]）—— 复刻官方"从不判断、永远叠加"的做法
    if (ALWAYS_LAYER_BATTLE_CRY) return false
    val coverage = battleCryCoverage(model, cry) ?: return true
    return coverage >= SELF_CONTAINED_COVERAGE
}

/**
 * 动画的循环长度（秒）：优先取它自己声明的 `animation_length`，**没声明就用关键帧的最大时间兜底**；
 * 两者都拿不到返回 0。
 *
 * ⚠ 结果按动画对象缓存（[java.util.IdentityHashMap]）：每帧重算一遍是白费功夫，而动画对象来自
 * 资源仓库、长期存活，用它当 key 不会积压。
 */
private val animationLoopLengths = java.util.IdentityHashMap<BedrockAnimation, Float>()

private fun loopLengthOf(animation: BedrockAnimation): Float =
    animationLoopLengths.getOrPut(animation) {
        val declared = animation.animationLength.toFloat()
        if (declared > 0f) {
            declared
        } else {
            animation.boneTimelines.values
                .flatMap { listOf(it.position, it.rotation, it.scale) }
                .filterIsInstance<BedrockKeyFrameBoneValue>()
                .maxOfOrNull { if (it.isEmpty()) 0f else it.lastKey().toFloat() }
                ?: 0f
        }
    }

/**
 * 补跑的 idle 该用哪个时间点：把全局动画秒数**折算到该动画自己的循环内**。
 *
 * ⚠ 直接把 `state.animationSeconds` 传进去会让 idle「冻住」—— 框架 `BedrockPoseAnimation.setupAnim`
 * 就是这么传的，但那只对有 `animation_length` 的动画成立：
 * - `BedrockAnimationAdapter`：`animationLength = json["animation_length"] ?: -1.0`、
 *   `shouldLoop = animationLength > 0 && loop` ⇒ **没写 animation_length 的动画 shouldLoop 恒为 false**
 *   （**1.8.0 与 1.8.2 两版实测一致**：96 个有战吼的物种里 **59 个的 idle 没写**，而它们的 `loop` 都是 true；
 *   上面这条链条的四段实现在两版里逐字相同）
 * - `BedrockAnimation.run()`：shouldLoop 为 false 时既不取模、`animationLength > 0` 也不成立 ⇒ 时间原样用
 * - 关键帧查找（`BedrockKeyFrameBoneValue.resolve`）超出末尾时**停在最后一帧**
 * ⇒ 精灵在屏幕上待满一个循环后 idle 就冻结在最后一帧，而**这些动画的最后一帧多数是归零帧**
 *   （脱壳忍者 100%、黑眼鳄 94%、火岩鼠 70% 的骨骼都是 0）⇒ 战吼期间身体掉回模型原始姿势
 *   （火岩鼠四条腿全归零：前腿竖直、后腿水平伸直 —— 用户 2026-09-23 报）。
 *
 * ⚠ **这是通法，不含任何物种名单**：长度从动画数据里算（声明值 → 关键帧最大时间兜底），
 * 官方加新精灵 / 改动画都自动跟上；本来就带 `animation_length` 的物种框架已自行取模，这里再取模结果相同。
 */
private fun loopedSeconds(animation: BedrockAnimation, state: PosableState): Float {
    val length = loopLengthOf(animation)
    return if (length > 0f) state.animationSeconds % length else state.animationSeconds
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
     * 「战吼段 → 维持段」交界处的交叉淡化时长（秒）。
     *
     * ⚠ 两段跑的是**两套不同的动画**（战吼 / 战斗 idle），交界处若硬换，骨骼会在那一刻
     * 突然换一套、看着像"切换"（用户 2026-09-22 报「战吼连接战斗姿态之间有切换感」，
     * 而两头是平顺的 —— 因为只有两头做了缓动，段与段之间没做）。
     * 淡化期间战吼渐出、战斗 idle 渐入，权重互补，接缝就没了。
     */
    private val crossoverSeconds = 0.4f

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
        // 交界处的交叉权重：战吼段末尾 [crossoverSeconds] 内由 0 升到 1（进维持段后恒为 1）。
        // 战吼强度取 `1 - 它`、战斗 idle 取`它`，两者互补 ⇒ 接缝处不会"换一套骨骼"。
        val cross = crossoverSeconds.coerceAtMost(crySeconds / 2f)
        val crossT = ((elapsed - (crySeconds - cross)) / cross).coerceIn(0f, 1f)
        return if (elapsed < crySeconds) {
            // ① 战吼段：只动末端骨骼的战吼要先补一份战斗 idle，否则身体没东西撑、掉回 rest pose。
            //    自带躯干的（火焰鸡那类）**不补** —— 累加会把两份旋转加起来（手跑到胸前）；
            //    但它们在这里靠交叉淡化把战斗 idle **渐入**（`crossT` 从 0 开始），同样接得上。
            val idleWeight = if (selfContained) crossT else 1f
            // 姿势里的 idle 动画**全跑**（见 [battleStanceIdles]）；时间按各自的循环长度取模
            // （见 [loopedSeconds]）——直接传全局秒数会让缺 animation_length 的那条冻在最后一帧
            for (idle in battleStanceIdles(model)) {
                idle.run(model, state, loopedSeconds(idle, state), limbSwing, limbSwingAmount, ageInTicks, intensity * idleWeight)
            }
            cry.run(model, state, elapsed, limbSwing, limbSwingAmount, ageInTicks, intensity * (1f - crossT))
        } else {
            // ② 维持段：只跑战斗 idle，撑住"战斗姿态"
            // ⚠ 强度必须跟着 [intensity] 走，**不能写死 1f**：收尾缓出时框架的待机/走路 idle
            //   正从 0 渐渐恢复，这边若仍满强度，两条 idle 会同时满强度**累加**、姿势互相打架 ——
            //   观感就是「从战斗姿态切回待机的过程变成了慢动作」（2026-09-22 用户报）。
            //   跟着 intensity 走才是一进一出：战斗 idle 淡出、待机 idle 淡入。
            for (idle in battleStanceIdles(model)) {
                idle.run(model, state, loopedSeconds(idle, state), limbSwing, limbSwingAmount, ageInTicks, intensity)
            }
            elapsed < duration
        }
    }

    /**
     * 转发战吼的音效与粒子关键帧（吼声、火焰鸡脚底那簇火）。
     *
     * ⚠ **必须自己转发**：Cobblemon 的 `PrimaryAnimation` **没有 override `applyEffects`**
     * （接口里是空实现），所以凡是走 primary 的动画，音效一律不播 —— 这正是
     * 「有些动画的音效丢了」的原因（丢的就是战吼那条，姿势动画的照常）。
     *
     * ⚠ **还要换算成动画内的时间**：`BedrockAnimation.applyEffects` 按「窗口」判定关键帧，
     * 而这里收到的时间轴是 `state` 的全局秒数，不减掉起始时刻就永远对不上关键帧。
     * ⚠ 那个窗口的判据**官方改过**（所以别在这里写死原文）：1.8.1 是**闭区间**
     * `it.seconds in previousSeconds..newSeconds`，**1.8.2 起改成开区间**
     * `it.seconds >= previousSeconds && it.seconds < newSeconds`（官方借此修「动画特定时刻声音重复」）。
     * ⇒ 我们只是把窗口**递过去**、从不自己判命中，所以官方一改这边自动跟上、**无需改代码**。
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

/**
 * 「姿势过渡 → 战吼」的组合动画（2026-10-07 新增）：先播 [transition]（走路 → 待机），过渡完再交给 [cry]。
 *
 * ## 为什么需要它
 *
 * 战吼期间 [IconAnimationResolver.resolve] 会强制 `PoseType.PROFILE`，而 `drawProfilePokemon` 拿到 poseType 就
 * `setPoseToFirstSuitable` **瞬切** `currentPose` —— 正在播的走路动画（属于 `walking` 姿势）当帧从播放列表消失，
 * 被互补插值淡出的其实是**新姿势**的 idle ⇒ 这就是用户实报的「走路 → 战吼动作不连贯」。
 *
 * 官方的「姿势↔姿势」平滑过渡（[PoseTransitionAnimation]）本来就在，只是被 [ensurePoseTransition] 开头那句
 * `if (state.primaryAnimation != null) return` 绕过了（战吼正占着 primary 槽）。本类把过渡**前置**成战吼的入场段、
 * 两段合成**一个** primary 播 —— 而不是先后设两次 primary（那样过渡的收尾会被战吼的开场硬切）。
 *
 * ## 两段的强度怎么接
 *
 * 组合动画整体是 primary（`curve = { t -> intensityAt(t) }`）：
 * - **过渡段**：[intensityAt] 恒返回 `1f` ⇒ `poseIntensity = 0`，框架那套姿势动画**不参与**，
 *   画面完全由 [PoseTransitionAnimation] 自己控制（它自足：先 `setDefault()`、再按互补强度 apply 前后两个姿势）
 *   —— 与 [ensurePoseTransition] 里 `curve = { 1f }` 的独立过渡**同一口径**。
 * - **战吼段**：强度交给 [PoseWithBattleCry.intensityAt]（两端缓动）。起始值 0 ⇒ 姿势动画恢复满强度，
 *   而过渡动画的终点本就是"目标姿势满强度" ⇒ 交接**连续**，不会跳变。
 *
 * ## 计时
 *
 * 两段各记各的：过渡段由 [PoseTransitionAnimation] 自己在首次 `run` 时初始化起点（读 `state.animationSeconds`）；
 * 战吼段由本类在过渡结束那一帧调 [PoseWithBattleCry.start] 起算（它自己按 `state.animationSeconds` 记账）。
 *
 * ⚠ [isTransition] 在过渡段**必须为 true**：`resolve()` 靠它判断"当前是不是战吼"来决定要不要强制待机姿势 ——
 * 过渡段若报 false，`currentPose` 会在过渡动画之上又被 `setPoseToFirstSuitable(PROFILE)` 切一次。
 */
private class PoseTransitionThenBattleCry(
    private val transition: PoseTransitionAnimation,
    private val cry: PoseWithBattleCry
) : ActiveAnimation {

    /** 战吼是否已经开始（过渡段的判据，见类注释） */
    private var cryStarted = false

    /** 过渡段算「姿势过渡」、战吼段不算 —— 见类注释里那条 ⚠ */
    override val isTransition: Boolean get() = !cryStarted

    override val duration: Float = transition.duration + cry.duration
    override val enduresPrimaryAnimations = false

    private var startedSeconds = -1f

    override fun start(state: PosableState) {
        startedSeconds = state.animationSeconds
    }

    /** 过渡段恒 `1f`（姿势动画让位给过渡动画），战吼段交给 [PoseWithBattleCry.intensityAt] */
    fun intensityAt(t: Float): Float {
        val elapsed = t * duration
        if (elapsed < transition.duration) return 1f
        return cry.intensityAt((elapsed - transition.duration) / cry.duration)
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
        if (!cryStarted) {
            if (state.animationSeconds - startedSeconds < transition.duration) {
                // 过渡动画**忽略** intensity：它自己按互补曲线算前后姿势的权重（见 PoseTransitionAnimation）
                transition.run(context, model, state, limbSwing, limbSwingAmount, ageInTicks, headYaw, headPitch, intensity)
                return true
            }
            cryStarted = true
            cry.start(state)
        }
        return cry.run(context, model, state, limbSwing, limbSwingAmount, ageInTicks, headYaw, headPitch, intensity)
    }

    /** 音效只在战吼段有（过渡段没有关键帧要转发）—— 吼声就是靠这里转发出去的，漏了会静默无声 */
    override fun applyEffects(entity: Entity?, state: PosableState, previousSeconds: Float, newSeconds: Float) {
        if (!cryStarted) return
        cry.applyEffects(entity, state, previousSeconds, newSeconds)
    }
}

// ⚠ 2026-10-07 这里**删掉过一个** `SettlePoseAnimation`（"只让当前姿势的动画淡出、目标姿势不跑动画"）——
//   实测**更糟**：动画权重归零 = 骨骼回到模型**原始建模姿势**，用户看到"头伸直往上、其他关节全都伸直僵硬"。
//   教训与三种做法的完整对比见 [BATTLE_CRY_POSE_TRANSITION]，别再重新发明它。
