package com.shusheng.cobblemarket.currency

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.mark.Marks
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokeball.PokeBalls
import com.cobblemon.mod.common.api.pokemon.Natures
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.types.tera.TeraTypes
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.IVs
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.stat.CobblemonStatProvider
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.util.MoveText
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.StringNbtReader
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import kotlin.random.Random

/**
 * **RP 商城的「生货」工厂** —— 把服主捏的**配方**（[ShopPokemonSpec] / [ShopItemSpec]）变成实物。
 *
 * 为什么单独一层：商城卖的是**配方**不是实物（见 [ShopEntry] 注释），同一格每卖一次都要
 * **凭空造一份新的**（精灵的初训家 = 买家、UUID 全新 —— 服主的名字不会印到卖出去的精灵上）。
 * 造物是**纯对象构造**：不碰玩家数据、不写盘、不改存档，所以与 [PointMarketService] 的
 * 扣款发货事务分开 —— 造不出来时（物种被删、存档数据损坏）直接失败，**不留下半成品**。
 *
 * ⚠ **随机项的口径**：规格里 `null` = 让游戏掷（见 [ShopPokemonSpec] 注释）。
 * [Pokemon] 的出生默认值天然就是这个语义 —— `ivs` 出生即随机、`nature` 随机、
 * `teraType` 按主属性、`scaleModifier` = 1.0 —— 所以**没填的字段什么都不做**即可，
 * 不能"帮忙补一个默认值"（那会把「随机」悄悄变成「固定」）。
 */
object PointMarketFactory {

    /**
     * 六项个体值 / 努力值的顺序 —— [ShopPokemonSpec.ivs] 等列表就是这个顺序。
     * ⚠ 不直接用 `Stats.PERMANENT` 迭代（它是 EnumSet，顺序依赖枚举声明，
     * Cobblemon 哪天调整声明顺序就会静默错位），显式列出来。
     */
    private val PERMANENT_STATS: List<Stat> = listOf(
        Stats.HP, Stats.ATTACK, Stats.DEFENCE,
        Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
    )

    /** 技能槽数（Cobblemon 的四个槽） */
    private const val MOVE_SLOTS = 4

    // ── 精灵 ──

    /**
     * 按规格造一只精灵；返回 null = 造不出来（物种不存在 / 数据损坏），**调用方不得扣款**。
     *
     * [buyer] 只用来定初训家，本函数不修改买家的任何数据（把精灵塞进队伍/PC 是调用方的事）。
     */
    fun createPokemon(spec: ShopPokemonSpec, buyer: ServerPlayerEntity): Pokemon? {
        val species = Identifier.tryParse(spec.species)?.let { PokemonSpecies.getByIdentifier(it) }
            ?: return null

        val pokemon = try {
            // ⚠ species 的 setter 会连带跑 updateAspects / updateForm / checkGender / updateHP，
            //   且对未注册的物种**抛异常** —— 存档被手改过的话在这里就会炸，必须兜住
            Pokemon().also { it.species = species }
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Shop: cannot build pokemon of species {}: {}", spec.species, e.message)
            return null
        }

        // 等级：服主留空 = **1 级**（与官方 PokemonProperties 同口径 —— 那里不指定等级时就是 1 级）
        pokemon.level = spec.level ?: 1

        // ── 形态 / 闪光（都在 updateAspects 之前设好）──
        // ⚠ aspects 是**派生集合**（= 各 AspectProvider 算出来的 + forcedAspects），
        //   直接往 pokemon.aspects 里塞会被下一次 updateAspects() 冲掉 ⇒ 强制项走 forcedAspects
        val shiny = when {
            // 三态**互斥**（数据层注释有写）：固定闪光写在 aspects、概率闪光写在 shinyChance
            "shiny" in spec.aspects -> true
            spec.shinyChance > 0f -> Random.nextFloat() < spec.shinyChance
            else -> false
        }
        pokemon.shiny = shiny
        pokemon.forcedAspects = spec.aspects.filter { it != "shiny" }.toSet() +
            (if (shiny) setOf("shiny") else emptySet())
        // 形态由 aspects 决定（species.getForm(aspects)），所以必须重算 —— 且要赶在
        // 「取 form 的字段」（特性 / 技能池）之前
        pokemon.updateAspects()
        pokemon.updateForm()

        // ── 具体值项：null = 不碰（吃出生时的默认/随机）──
        spec.gender?.let { name ->
            Gender.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { pokemon.gender = it }
        }
        // ⚠ **不能把完整 id 直接喂给 `Natures.getNature(String)`**：它内部会**无条件**先
        //   `cobblemonResource(它)` 补一次命名空间 ⇒ 传 `cobblemon:adamant` 会拼成
        //   `cobblemon:cobblemon:adamant`，**构造 Identifier 时直接抛**（那个 `?:` 兜的是 null，
        //   兜不住异常）。2026-09-26 用户实测：**只要给商品配了具体性格，买这一格就毫无反应**
        //   （异常一路抛到 `server.execute` 被吞掉，界面关了、钱没扣、连红字都没有）。
        //   这里两种存法都吃：**先按裸名**（它期望的格式）查，查不到再按完整 id 走 Identifier 重载。
        spec.nature?.let { id ->
            val nature = Natures.getNature(id.substringAfter(':'))
                ?: Identifier.tryParse(id)?.let { Natures.getNature(it) }
            nature?.let { pokemon.nature = it }
        }
        spec.ability?.let { id -> applyAbility(pokemon, id) }
        spec.ball?.let { id ->
            Identifier.tryParse(id)?.let { PokeBalls.getPokeBall(it) }?.let { pokemon.caughtBall = it }
        }
        spec.scale?.let { pokemon.scaleModifier = it }
        if (spec.alpha) pokemon.isAlpha = true
        spec.teraType?.let { id ->
            Identifier.tryParse(id)?.let { TeraTypes.get(it) }?.let { pokemon.teraType = it }
        }
        pokemon.gmaxFactor = spec.gmaxFactor
        pokemon.dmaxLevel = spec.dmaxLevel
        pokemon.tradeable = spec.tradeable
        pokemon.heldItemVisible = spec.heldItemVisible

        // 个体值：**`-1` = 该项没填**（保持出生时掷的那个）；整组 null = 六项全随机
        spec.ivs?.let { ivs ->
            ivs.forEachIndexed { i, value ->
                if (i < PERMANENT_STATS.size && value >= 0) pokemon.setIV(PERMANENT_STATS[i], value)
            }
        }
        // 保底 V 数：从六项里随机挑 n 项拉满（照 PokemonProperties.commonApply 的写法）
        if (spec.minPerfectIvs > 0) {
            CobblemonStatProvider.ofType(Stat.Type.PERMANENT).shuffled()
                .take(spec.minPerfectIvs)
                .forEach { pokemon.setIV(it, IVs.MAX_VALUE) }
        }
        spec.evs?.let { evs ->
            evs.forEachIndexed { i, value ->
                if (i < PERMANENT_STATS.size && value > 0) pokemon.setEV(PERMANENT_STATS[i], value)
            }
        }

        spec.friendship?.let { pokemon.setFriendship(it) }
        spec.nickname?.let { pokemon.nickname = Text.literal(it) }
        spec.marks.forEach { id ->
            Identifier.tryParse(id)?.let { Marks.getByIdentifier(it) }?.let { pokemon.marks.add(it) }
        }

        // 技能（要在形态落定之后：可学技能表跟着形态走）
        applyMoves(pokemon, spec.moves)

        // ⚠ initialize 放最后：它会 checkGender()（把与形态性别比例冲突的值校正回来）
        //   并在招式全空时兜底 —— 得让它有机会校验上面设的那些值
        pokemon.initialize()

        // 初训家 = **买家本人**（这是「配方」而非「服主的实物」的落脚点）
        pokemon.setOriginalTrainer(buyer.uuid)
        pokemon.refreshOriginalTrainer()

        // 携带物最后给：swapHeldItem 会广播持有物变更包，此时精灵已完全成型
        spec.heldItemSpec?.let { snbt ->
            parseStackSnbt(snbt, buyer.serverWorld.registryManager)
                ?.takeIf { !it.isEmpty }
                ?.let { pokemon.swapHeldItem(it, decrement = false, aiCanDrop = false) }
        }

        return pokemon
    }

    /**
     * 特性：照 `PokemonProperties.createAbility` 的写法 —— 形态的特性池里有同名项就用它
     * （带 priority，关系到"是不是隐藏特性"），池里没有才按**强制**创建。
     */
    private fun applyAbility(pokemon: Pokemon, id: String) {
        val ability = Abilities.get(id) ?: return
        val potential = pokemon.form.abilities.firstOrNull { it.template == ability }
        pokemon.updateAbility(
            potential?.let { it.template.create(false, it.priority) } ?: ability.create(true)
        )
    }

    /**
     * 四个技能槽：服主设了的**原样保留**，没设的从该形态的**可学技能**（`Learnset.getAllLegalMoves()`）
     * 里随机补满 —— Cobblemon 里不存在没有技能的精灵。
     *
     * ⚠ 不指定技能时**不能**留着让 `initialize()` 补默认招（那是"按等级学招"的默认组），
     * 用户 2026-09-20 明确：这只精灵是服主完全自定义捏出来的，该从**全部可学技能**里掷。
     */
    private fun applyMoves(pokemon: Pokemon, raw: String) {
        val slots = MoveText.slotIds(raw)
        // 可学技能池：抽走一个少一个，保证四个槽不重复
        val pool = pokemon.form.moves.getAllLegalMoves().toMutableList()

        val resolved = (0 until MOVE_SLOTS).map { i ->
            val id = slots.getOrNull(i).orEmpty()
            if (id.isNotEmpty()) {
                pool.removeAll { it.name == id }
                id
            } else if (pool.isEmpty()) {
                // 理论上不会（每个物种都有可学技能）；真出现就留空，绝不硬塞
                ""
            } else {
                pool.removeAt(Random.nextInt(pool.size)).name
            }
        }

        pokemon.moveSet.doWithoutEmitting {
            resolved.forEachIndexed { i, id ->
                if (id.isEmpty()) return@forEachIndexed
                Moves.getByName(id)?.let { pokemon.moveSet.setMove(i, it.create()) }
            }
        }
        pokemon.moveSet.update()
    }

    // ── 物品 ──

    /**
     * 按规格造物品，按 `maxCount` **拆成若干个栈**（买 100 个钻石 = 2 个栈），
     * 返回数量之和 = [count]；返回 null = 物品 id 非法或组件快照坏掉。
     *
     * ⚠ 组件走 **NBT 往返**（拼一个完整物品 NBT 交给 `ItemStack.fromNbtOrEmpty`），
     * 与 `ItemRuleComponents.extractSpec` 提出的格式同源，不直接碰 `applyComponents` 的重载。
     */
    fun createItems(
        spec: ShopItemSpec,
        count: Int,
        registryManager: RegistryWrapper.WrapperLookup
    ): List<ItemStack>? {
        if (count <= 0) return null
        val id = Identifier.tryParse(spec.itemId) ?: return null
        if (Registries.ITEM.getOrEmpty(id).isEmpty) return null

        val base = try {
            val nbt = NbtCompound().apply {
                putString("id", id.toString())
                putInt("count", 1)
                // 空快照不写（与存档同口径：无 components = 无组件）
                spec.componentsSpec?.takeIf { !it.isEmpty }?.let { put("components", it) }
            }
            ItemStack.fromNbtOrEmpty(registryManager, nbt)
        } catch (e: Exception) {
            CobbleMarket.LOGGER.warn("Shop: cannot build item {}: {}", spec.itemId, e.message)
            return null
        }
        if (base.isEmpty) return null

        val perStack = base.maxCount.coerceAtLeast(1)
        val out = ArrayList<ItemStack>()
        var left = count
        while (left > 0) {
            val n = minOf(left, perStack)
            out.add(base.copy().apply { this.count = n })
            left -= n
        }
        return out
    }

    /** SNBT 串 → 物品栈（携带物用）。解析失败返回 null，**不抛异常** */
    private fun parseStackSnbt(snbt: String, registryManager: RegistryWrapper.WrapperLookup): ItemStack? =
        runCatching { StringNbtReader.parse(snbt) }.getOrNull()
            ?.let { nbt -> runCatching { ItemStack.fromNbtOrEmpty(registryManager, nbt) }.getOrNull() }
}
