package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.network.encodeHeldItemNbt
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer

/**
 * CSV 交易账本「详情」列的构建：精灵完整数值 / 物品 NBT 文本化。
 * 跨语言紧凑格式（中英两份 CSV 内容一致），字段顺序：
 * 精灵：lv=50|shiny=Y|ivs=31/31/31/31/31/31|ht=HP,ATK|nature=timid|base=jolly|ability=blaze|gender=MALE|ball=cobblemon:poke_ball|held=cobblemon:leftovers|form=...
 * 物品：count=5|nbt={...}
 * 缺失/无意义字段整段省略；nature/base/ability 去掉翻译 key 前缀保留英文名。
 */
object RecordDetail {

    private val STAT_KEYS = listOf("ivsHp", "ivsAtk", "ivsDef", "ivsSpAtk", "ivsSpDef", "ivsSpd")
    private val EV_KEYS = listOf("evsHp", "evsAtk", "evsDef", "evsSpAtk", "evsSpDef", "evsSpd")
    private val HT_KEYS = listOf(
        "htHp" to "HP", "htAtk" to "ATK", "htDef" to "DEF",
        "htSpAtk" to "SPA", "htSpDef" to "SPD", "htSpd" to "SPE"
    )

    /** 证章在详情列里每行排几个（纯粹为了可读性，与界面上的 `MARKS_PER_ROW` 无关） */
    private const val MARKS_PER_CSV_LINE = 10

    private val STATS = listOf(
        Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
    )

    /** 极训项 → 缩写（重建路径用；老口径的 `HT_KEYS` 是快照 key 版） */
    private val HT_STATS = listOf(
        Stats.HP to "HP", Stats.ATTACK to "ATK", Stats.DEFENCE to "DEF",
        Stats.SPECIAL_ATTACK to "SPA", Stats.SPECIAL_DEFENCE to "SPD", Stats.SPEED to "SPE"
    )

    /**
     * 精灵详情列 —— **优先从精灵完整 NBT 重建后取值**，重建不出来再按老口径从快照取。
     *
     * ⚠ **为什么要重建**：CSV 是**审计底账**，出事时要靠它复刻货物。原先只从 `extraData` 快照取 12 项，
     *   而**昵称 / 体型 / 极巨化 / 初始训练家快照里根本没有**，携带物也只有一个 id
     *   （**附魔与自定义名全丢**）⇒ 真出事时复刻不出来，底账就白记了（用户 2026-09-27 提出）。
     * ⚠ **老账本同样受益**：挂单 / 拍卖 / 求购本来就存着完整 NBT ⇒ 升级前挂上的条目也能补全。
     * ⚠ 开销可接受：CSV 只在**成交那一刻**写一次（不像悬停窗每帧走），重建一次精灵不值一提。
     *
     * @param pokemonNbt 挂单 / 拍卖 / 求购存着的**完整精灵 NBT**
     * @param server     取 `registryManager` 解 NBT + 解析初始训练家的玩家名；**传 null 时退回快照口径**
     * @param fallbackExtra/Level/Shiny 重建失败（NBT 损坏 / 没有 server）时的老口径取值 ——
     *   **保证任何情况下这一条都写得出来**，绝不因为重建失败就丢一条交易记录
     */
    fun pokemon(
        pokemonNbt: NbtCompound?,
        server: MinecraftServer?,
        fallbackExtra: Map<String, String>,
        fallbackLevel: Int,
        fallbackShiny: Boolean
    ): String {
        val registryManager = server?.registryManager
        val rebuilt = try {
            if (registryManager != null && pokemonNbt != null) PokemonLoader.fromNbt(registryManager, pokemonNbt) else null
        } catch (e: Exception) {
            // 数据损坏不吞交易：记一条告警、退回快照口径照常写（只是少几个字段）
            CobbleMarket.LOGGER.warn("CSV detail: failed to rebuild pokemon from NBT, using snapshot instead: {}", e.message)
            null
        }
        return if (rebuilt != null && registryManager != null) fromPokemon(rebuilt, registryManager, server)
        else legacy(fallbackExtra, fallbackLevel, fallbackShiny)
    }

    /** 重建成功：全部字段从精灵读（快照一个都不用） */
    private fun fromPokemon(
        pokemon: Pokemon,
        registryManager: net.minecraft.registry.DynamicRegistryManager,
        server: MinecraftServer?
    ): String {
        val parts = mutableListOf<String>()
        // ── 老口径的 12 项（值改从精灵读，不再依赖快照）──
        parts.add("lv=${pokemon.level}")
        parts.add("shiny=" + if (pokemon.shiny) "Y" else "N")
        parts.add("ivs=" + STATS.joinToString("/") { pokemon.ivs[it]?.toString() ?: "?" })
        parts.add("evs=" + STATS.joinToString("/") { pokemon.evs[it]?.toString() ?: "?" })
        val ht = HT_STATS.mapNotNull { (stat, abbr) ->
            pokemon.ivs.hyperTrainedIVs[stat]?.takeIf { it >= 0 }?.let { "$abbr:$it" }
        }
        if (ht.isNotEmpty()) parts.add("ht=" + ht.joinToString(","))
        val nature = pokemon.effectiveNature.name.path
        parts.add("nature=$nature")
        // 原生性格：与生效性格相同（没用过薄荷）就整段省略，与老口径一致
        val base = pokemon.nature.name.path
        if (base != nature) parts.add("base=$base")
        parts.add("ability=${pokemon.ability.name}")
        parts.add("gender=${pokemon.gender.name}")
        parts.add("ball=${pokemon.caughtBall.name}")
        pokemon.aspects.joinToString(",").takeIf { it.isNotEmpty() }?.let { parts.add("form=$it") }

        // ── 本次新增（快照里没有、只有精灵本身才有）──
        // 昵称：玩家自己起的名字，原样记（不翻译）
        pokemon.nickname?.takeIf { it.string.isNotBlank() }?.let { parts.add("name=${it.string}") }
        // 体型（XS/S/M/L/XL/ALPHA）
        PokemonSize.from(pokemon).takeIf { it.isNotEmpty() }?.let { parts.add("size=$it") }
        parts.add("gmax=" + if (pokemon.gmaxFactor) "Y" else "N")
        parts.add("dmax=${pokemon.dmaxLevel}")
        // 初始训练家：能解析出名字就记名字；解析不出（没设过 / 离线且无缓存）整段省略
        PokemonOT.from(pokemon, server).takeIf { it.isNotEmpty() }?.let { parts.add("ot=$it") }
        parts.add("tera=${pokemon.teraType.id.path}")
        parts.add("friend=${pokemon.friendship}")
        MoveText.of(pokemon).takeIf { it.isNotEmpty() }?.let { parts.add("moves=$it") }

        // 携带物：id + **完整 NBT**（附魔 / 自定义名 / 染色 —— 复刻全靠它）。
        // ⚠ 无组件时 encodeHeldItemNbt 返回空串（构造简单、不值得多记一份），那时只有 id 也够复刻
        val held = pokemon.heldItem()
        if (!held.isEmpty) {
            parts.add("held=" + Registries.ITEM.getId(held.item))
            encodeHeldItemNbt(held, registryManager).takeIf { it.isNotEmpty() }?.let { parts.add("heldNbt=$it") }
        }

        // 证章：**逗号分隔 + 每 [MARKS_PER_CSV_LINE] 个换一行**（在同一个格子里换行）。
        // 一只精灵最多能有近 200 个证章，全横着排那一行会长到没法看；
        // ⚠ 写入端会给含换行的值自动加引号包裹（见 TransactionFileLogger），
        //   所以 Excel / 标准 CSV 解析器仍然把它当**同一个格子**
        // ⚠ 记的是 `Mark.name`（**词条 key**，如 `cobblemon.mark.ribbon_best_friends`），
        //   **不是 `it.texture`**（纹理路径）—— 项目里对 marks 有两种用法：
        //   界面**画图标**要 texture（`EntryBadgeRenderer` / `MarketNetwork` 那几处），
        //   而**显示名字**要 name（拍卖播报的聊天悬停就是 `joinToString(",") { it.name }`）。
        //   CSV 是给人读的文字账本 ⇒ 走后者。本来抄了渲染那套（纹理路径），
        //   又长又难读（用户 2026-09-27 实测指出）。
        // ⚠ 用 **`identifier`**（`cobblemon:mark_jumbo` 这种 id）—— **不要用 `it.name` 也不要 `it.texture`**：
        //   `texture` 是纹理路径（`cobblemon:textures/gui/mark/xxx.png`，又长又难读）；
        //   而 `name` **实测在运行时也是纹理路径**（用户 2026-09-27 用 1.3.0 全量实测，
        //   与 `data/cobblemon/marks/*.json` 里 `"name": "cobblemon.mark.xxx"` 对不上 ——
        //   那是**数据文件字段**，运行时 `Mark` 对象上的 `name` 未必等同）。
        //   `identifier` 是唯一**不依赖数据文件字段语义**的取值，且正是玩家认的那个 id。
        val marks = pokemon.marks.map { it.identifier.toString() }
        if (marks.isNotEmpty()) {
            // ⚠ **内层必须再 join 一次**：`chunked` 返回的是 `List<List<String>>`，
            //   直接 `joinToString(",\n")` 会把每个**子列表的 `toString()`**（`[a, b, c]` 这种）
            //   当成元素 —— 出来是带方括号、逗号还多一个空格的格式（2026-09-27 用户实测抓到）
            parts.add("marks=" + marks.chunked(MARKS_PER_CSV_LINE).joinToString(",\n") { it.joinToString(",") })
        }

        return parts.joinToString("|")
    }

    /** 重建不出来时的**老口径**：只有快照能取的那 12 项（原实现原样保留，保证向后行为不变） */
    private fun legacy(extraData: Map<String, String>, level: Int, shiny: Boolean): String {
        val parts = mutableListOf<String>()
        parts.add("lv=$level")
        parts.add("shiny=" + if (shiny) "Y" else "N")
        parts.add("ivs=" + STAT_KEYS.joinToString("/") { extraData[it] ?: "?" })
        parts.add("evs=" + EV_KEYS.joinToString("/") { extraData[it] ?: "?" })
        val ht = HT_KEYS.mapNotNull { (key, abbr) ->
            (extraData[key]?.toIntOrNull() ?: -1).takeIf { it >= 0 }?.let { "$abbr:$it" }
        }
        if (ht.isNotEmpty()) parts.add("ht=" + ht.joinToString(","))
        val nature = extraData["nature"]?.stripPrefix()
        if (!nature.isNullOrEmpty()) parts.add("nature=$nature")
        val base = extraData["natureBase"]?.stripPrefix()
        if (!base.isNullOrEmpty() && base != nature) parts.add("base=$base")
        val ability = extraData["ability"]?.stripPrefix()
        if (!ability.isNullOrEmpty()) parts.add("ability=$ability")
        val gender = extraData["gender"]
        if (!gender.isNullOrEmpty()) parts.add("gender=$gender")
        val ball = extraData["ballItem"]
        if (!ball.isNullOrEmpty()) parts.add("ball=$ball")
        // 携带物（heldItemId 为空 = 无携带物，省略整段）
        val held = extraData["heldItemId"]
        if (!held.isNullOrEmpty()) parts.add("held=$held")
        val form = extraData["aspects"]
        if (!form.isNullOrEmpty()) parts.add("form=$form")
        return parts.joinToString("|")
    }

    /** 物品附魔等级表（NBT components.minecraft:enchantments.levels → id:等级；无附魔/解析失败为空表） */
    fun enchantmentLevels(itemNbt: NbtCompound?): Map<String, Int> {
        val nbt = itemNbt ?: return emptyMap()
        val components = try { nbt.getCompound("components") } catch (e: Exception) { return emptyMap() }
        if (!components.contains("minecraft:enchantments")) return emptyMap()
        val ench = try { components.getCompound("minecraft:enchantments") } catch (e: Exception) { return emptyMap() }
        if (!ench.contains("levels")) return emptyMap()
        val levels = try { ench.getCompound("levels") } catch (e: Exception) { return emptyMap() }
        return levels.keys.associateWith { levels.getInt(it) }
    }

    fun item(itemNbt: NbtCompound?, count: Int?, registries: net.minecraft.registry.DynamicRegistryManager): String {
        val parts = mutableListOf<String>()
        if (count != null) parts.add("count=$count")
        // 附魔摘要（可读，英文 key 形式与 ivs/nature 字段风格一致）；NBT 全文仍保留（精确复刻）
        val ench = enchantmentLevels(itemNbt)
        if (ench.isNotEmpty()) parts.add("ench=" + ench.entries.joinToString(",") { "${it.key}:${it.value}" })
        if (itemNbt != null && !itemNbt.isEmpty) parts.add("nbt=${itemNbt.toString()}")
        // 稀有度（**历史界面的物品名上色用**，2026-09-26 加）：界面那边只有 itemId，
        // 拿 `defaultStack` 取不到「附魔把稀有度顶上去」那一档（下界合金剑默认 COMMON、
        // 附了魔在物品栏里是黄的 → 历史里永远显示白）。这里手上还有 nbt，就地算好带上。
        rarityOf(registries, itemNbt)?.let { parts.add("rarity=$it") }
        return parts.joinToString("|")
    }

    /**
     * 从详情列里取出 [item] 写进去的稀有度段（`RARE` 这种枚举名）；旧记录没这段 → 空串。
     * 给历史界面用 —— 它只收这一个短字符串，**不把整个详情列塞进 payload**。
     */
    fun rarityFromDetail(detail: String): String =
        detail.split("|").firstOrNull { it.startsWith("rarity=") }?.removePrefix("rarity=").orEmpty()

    /**
     * 这件物品的**稀有度枚举名**（`RARE` 这种），取不到返回 null（整段省略）。
     *
     * ⚠ **重建完整栈、直接读 `stack.rarity`** —— 这与项目里那个唯一入口
     * [com.shusheng.cobblemarket.util.ItemNameText.rarityColor]（`stack.rarity.formatting.colorValue`）
     * **同一个来源**，物品市场 / 购买弹窗 / 球种行全都是这么取色的。
     *
     * ⚠⚠ **我在这里栽过一次，两次都错在同一个念头**（2026-09-26 用户实测连抓两条）：
     * 第一版为了"不动 8 个调用点"，**放着现成入口不用**、改去手推稀有度规则 ⇒ 结果照了**旧版** MC 的
     * 「COMMON + 附魔 → UNCOMMON」，把附魔的下界合金剑算成**黄**（实际是青 RARE）。
     * 用户原话：「我们模组别的地方处处都有显示物品稀有度颜色名的代码，你偏偏自己又去翻源码」。
     * **判据**：凡是「游戏里已经存在的一个值」，先 grep 项目里有没有现成入口 ——
     * **有就复用，别自己算**；实在要自己算，才去抄反编译源码。
     */
    private fun rarityOf(registries: net.minecraft.registry.DynamicRegistryManager, itemNbt: NbtCompound?): String? {
        return try {
            val stack = itemNbt?.let { net.minecraft.item.ItemStack.fromNbtOrEmpty(registries, it) }
                ?: return null
            if (stack.isEmpty) return null
            stack.rarity.name
        } catch (e: Exception) {
            null
        }
    }

    private fun String.stripPrefix(): String =
        removePrefix("cobblemon.nature.").removePrefix("cobblemon.ability.")
}
