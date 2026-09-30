package com.shusheng.cobblemarket.util

import com.cobblemon.mod.common.api.properties.CustomPokemonProperty
import com.cobblemon.mod.common.api.properties.CustomPokemonPropertyType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.properties.FlagProperty

/**
 * 往精灵身上打的**我们自己的标记**。
 *
 * | 标记 | 含义 | 谁打 |
 * |---|---|---|
 * | [SHOP] | 这只**从哪来** = RP 商城生成的 | **自动**（购买造物那一步）⚠ **不都是非法的** |
 * | [ILLEGAL] | 这只**本身正不正常** = 服主认定非法 | **服主手动**（商城编辑窗）/ **数据包** |
 *
 * ⚠⭐ **两个标记分工不同，判禁赛只能按 [ILLEGAL]** —— 按"来源"判会把合法的商城精灵
 *   （比如卖个普通皮卡丘）一起误伤（用户 2026-09-30 原话：「从 RP 商城买来的精灵怎么可能都是非法的」）。
 *
 * ## 为什么用 `customProperties` 而不是自己发明 NBT 键
 *
 * 1. 它是**官方 API**，序列化由官方保证（`PokemonP2.kt` 的 `CUSTOM_PROPERTIES_CODEC`），
 *    以后官方改序列化我们跟着走，不用自己维护存档兼容
 * 2. ⭐ 注册之后 **`PokemonProperties` 的解析器就认得这两个 key** ⇒ **数据包 / NPC 定义 /
 *    spawn 规则 / 初始精灵**里都能直接写 —— 这正是用户点名要的能力：**服主自己做数据包
 *    （或找人定制服务器专属数据包）造「特色精灵但非法」时，可以主动打 [ILLEGAL]，
 *    我们后续的对战过滤就认得出、拦得住**（不然只能靠"不带标记 = 认不出"）
 * 3. 注册时官方会**自动同步给所有玩家**（`CustomPokemonProperty.triggerSyncAttempt`，命令补全用）
 *    ⇒ 它是一等公民，不是野路子
 *
 * ## 语法与「只能打、不能撤」
 *
 * - `cobblemarket:illegal`（建议这样写）/ `cobblemarket:illegal=yes` / `=true` ⇒ **打上**
 * - ⚠ **`=no` / `=false` 一律不认**（解析器会跳过）⇒ **谁也没法用命令把标记洗掉**
 *   （用户 2026-09-30 定：`/pokeedit` 是 Cobblemon 的指令、走 OP，我们拦不住谁会敲，
 *   那至少保证标记洗不掉 —— "非法"被洗掉等于禁赛失效）
 * - ⭐ **所以服主不需要会做数据包也能用**：`/pokeedit cobblemarket:illegal` 直接敲就行，
 *   公开免费数据包的用户同样吃得到这个接口（⚠ 代价：`/pokeedit` 是 OP 权限 ⇒ 任何 OP 都能打标，
 *   但能敲它的人本来就能把精灵改成任何样子，打标是其中破坏力最小的一项）
 * - ⚠ 撤销只走**我们自己的界面**（商城编辑窗那颗开关，只有能管商城的人点得到）
 *
 * ## ⚠⚠ 两个 key 一旦发布就不能再改
 *
 * 它们会被**逐字写进精灵的存档**（`asString()`）⇒ 改名等于认不出**已经发出去的**标记。
 * 名字定死在这儿，别为了"好看"去动。
 *
 * ## ⚠ 实现说明
 *
 * 落点**直接复用 Cobblemon 自己的 [FlagProperty]** —— 它本来就是"存在即标记、无值"那一类，
 * 语义与我们完全一致（`apply` 里先按 key 判重再 add，见 `FlagProperty.apply`），
 * 不必另写一个实现类。⚠ 但它在 `pokemon.properties` 包（不是 `api.properties`），
 * 属于"能用但没那么正式"的位置 ⇒ **跟 Cobblemon 大版本时要复核它还在不在、语义变没变**。
 */
object PokemonTags {

    /** 从 RP 商城买到的精灵（**自动**打）。⚠ 不都是非法的 —— 别拿它当禁赛判据 */
    const val SHOP = "cobblemarket:shop"

    /** 服主认定的非法精灵（手动勾 / 数据包写）。⭐ **对战禁赛按这个判** */
    const val ILLEGAL = "cobblemarket:illegal"

    /**
     * 注册这两个属性 —— 由 [com.shusheng.cobblemarket.CobbleMarket.init] 调用。
     *
     * ⚠ **两端都要跑**（common 的 init 本来就两端都执行）：
     * 服务端要解析数据包里的写法、客户端也可能要读标记做展示。
     *
     * ## ⚠⚠ 为什么**不走**官方的 [CustomPokemonProperty.register]（2026-09-30 启动崩溃的根因）
     *
     * 官方那个 `register` = `properties.add(它)` + `triggerSyncAttempt()`，而后者第一步是
     * `server()` → 读 **`Cobblemon.implementation`** —— 那是个 **lateinit**，
     * 要到 `Cobblemon.preInitialize()` 才赋值，而它发生在 **Cobblemon 自己的 `ModInitializer`** 里。
     * 我们的 mod id 是 `cobblemarket`、它是 `cobblemon`（第 8 个字符 `a` < `o`）⇒ **我们初始化在前**
     * ⇒ 直接调官方 `register` 必炸：
     * `UninitializedPropertyAccessException: lateinit property implementation has not been initialized`
     * ⇒ **客户端启动就崩**（玩家 2026-09-30 实测就是这个）。
     *
     * ⇒ 这里**只做 `register` 的实质那一步**（往列表里加）。被跳过的那次 sync 是给
     * "**服务器运行中**动态注册"同步 `/pokeedit` 补全用的 —— 我们注册时服务器还不存在，
     * 它本来就是 no-op（`triggerSyncAttempt` 开头那句 `server() ?: return` 正是为这个场景写的，
     * 只是它没防住 `server()` 内部那个 lateinit 会**抛**而不是返回 null）。
     * ⚠ 而补全本身不受影响：`PropertiesCompletionProvider` 是个 `DataRegistry`，
     * 服务器数据包加载/重载时会重建，那时我们的属性已经在列表里了。
     *
     * ⚠ **幂等**：万一以后被调两次（比如有人挪到事件里再补一次），重复加会让补全出现重名项。
     */
    fun register() {
        val registry = CustomPokemonProperty.properties
        if (registry.none { it.keys.contains(SHOP) }) registry.add(ShopProperty)
        if (registry.none { it.keys.contains(ILLEGAL) }) registry.add(IllegalProperty)
    }

    /** 这只精灵带没带某个标记（[key] 传 [SHOP] / [ILLEGAL]） */
    fun has(pokemon: Pokemon, key: String): Boolean =
        pokemon.customProperties.any { it is FlagProperty && it.key == key }

    /** 给精灵打上某个标记（已存在则什么都不做，不会重复加） */
    fun add(pokemon: Pokemon, key: String) {
        if (has(pokemon, key)) return
        pokemon.customProperties.add(FlagProperty(key, false))
    }

    /** 撤掉某个标记 */
    fun remove(pokemon: Pokemon, key: String) {
        pokemon.customProperties.removeIf { it is FlagProperty && it.key == key }
    }
}

/** 「商城来的」那个标记的属性类型 —— 结构照官方 `UncatchableProperty` */
object ShopProperty : CustomPokemonPropertyType<FlagProperty> {
    override val keys = setOf(PokemonTags.SHOP)
    override val needsKey = true
    override fun fromString(value: String?) = flagValueOf(keys.first(), value)
    override fun examples() = setOf("yes", "no")
}

/** 「非法」那个标记的属性类型 */
object IllegalProperty : CustomPokemonPropertyType<FlagProperty> {
    override val keys = setOf(PokemonTags.ILLEGAL)
    override val needsKey = true
    override fun fromString(value: String?) = flagValueOf(keys.first(), value)
    override fun examples() = setOf("yes", "no")
}

/**
 * `=yes` → **打上**；⚠ **`=no` 一律不认**（返回 `null`）。
 *
 * ⚠⚠ **"只能打、不能用命令撤"是有意的**（用户 2026-09-30 定）：
 * `/pokeedit` 的权限是 **Cobblemon 定的 OP**，我们拦不住谁会去敲它 ——
 * 那至少保证**没人能把标记洗掉**（"非法"被洗掉 = 禁赛失效，"商城"被洗掉 = 追不到源）。
 * ⇒ 撤销只走**我们自己的界面**（商城编辑窗那颗开关，只有能管商城的人点得到）。
 *
 * ⚠ 返回 `null` 是安全的：`PokemonProperties.parse` 里是 `if (property != null) add`，
 * 只会跳过这一条、不影响同一条指令里的其他属性（且那个 key 照样被消费掉）。
 *
 * ⚠ **不写值（`value == null`）按"打上"处理** ⇒ 数据包里直接写 `"cobblemarket:illegal"`
 * 也能生效（要求整合包作者写 `=yes` 太啰嗦，而这是给他们用的接口）。
 */
private fun flagValueOf(key: String, value: String?): FlagProperty? =
    if (value == null || value.lowercase() in listOf("true", "yes")) FlagProperty(key, false) else null
