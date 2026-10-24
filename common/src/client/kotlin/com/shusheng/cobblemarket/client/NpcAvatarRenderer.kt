package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.client.icon.PokemonIconRenderer
import com.shusheng.cobblemarket.currency.NpcDisplayMeta
import com.shusheng.cobblemarket.screen.EntryBadgeRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.SpawnEggItem
import net.minecraft.registry.Registries
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Identifier
import org.lwjgl.opengl.GL11
import kotlin.math.roundToInt

/**
 * **管理界面里的 NPC 头像**（2026-10-21）。
 *
 * 两条渲染路径：
 * - **宝可梦**：走全项目统一的 [PokemonIconRenderer]（与市场 / 拍卖列表同一套 profile 图标）——
 *   服务端下发物种 + aspects，图标自动缩放到格子内、**完整显示整只**。
 *   ⚠ 不能按实体类型造假实体：所有物种共用 `cobblemon:pokemon`，而且模型实际大小和碰撞箱常常对不上
 *   （按碰撞箱缩放会把身体切掉，2026-10-23 实测）。
 * - **其它实体**：按实体类型 id 建客户端假实体，用 `InventoryScreen.drawEntity` 画 3D 模型。
 *
 * ⚠ **自定义皮肤显示不出来**：Easy NPC 之类的皮肤存在实体 NBT 里，本期没有传；
 *   这类 NPC 会显示该实体类型的默认模型。原版村民 / 盔甲架 / 大部分生物完整。
 * ⚠ 找不到类型 / 类型不是生物 / 创建失败 ⇒ 回退成生成蛋图标，再不行用通用人形图标。
 *   **宝可梦不画占位图**：物种为空（旧绑定）或渲染失败时格子留空（2026-10-23 用户要求）。
 * ⚠ 3D 模型**写深度缓冲**：画之前 flush + 清深度，画完再 flush + 清一次
 *   （宝可梦那条路径由 [PokemonIconRenderer] 自己处理）。
 */
object NpcAvatarRenderer {

    private const val GENERIC_ICON = "textures/gui/button/npc_placeholder.png"
    private const val GENERIC_ICON_SIZE = 48

    /** 通用人形图标占格子的比例（留边距；1 = 铺满） */
    private const val GENERIC_ICON_FILL = 0.8f

    private var cacheWorld: ClientWorld? = null

    /** 非宝可梦实体的假实体缓存（键 = 实体类型 id） */
    private val entityCache = mutableMapOf<String, LivingEntity>()
    private val entityFailed = mutableSetOf<String>()

    /** 宝可梦头像：物种 + aspects → 渲染对象 / 姿态状态（缓存键与图标一一对应） */
    private val pokemonCache = mutableMapOf<String, Pair<RenderablePokemon, FloatingState>>()

    /**
     * 实体类型 id 的**客户端语言**名字（认不出时给一个通用占位）；宝可梦优先显示物种名，
     * 并按**形态主属性色**着色（全模组统一口径，见 [EntryBadgeRenderer.speciesTypeColor]）。
     */
    fun typeName(typeId: String, speciesId: String = "", aspectsRaw: String = ""): Text {
        speciesName(speciesId)?.let { name ->
            val color = EntryBadgeRenderer.speciesTypeColor(
                speciesId, NpcDisplayMeta.aspectsFrom(aspectsRaw)
            )
            return name.styled { it.withColor(TextColor.fromRgb(color)) }
        }
        if (typeId.isBlank()) return Text.translatable("cobblemarket.shop_admin.npc.unknown")
        val id = Identifier.tryParse(typeId) ?: return Text.translatable("cobblemarket.shop_admin.npc.unknown")
        return Registries.ENTITY_TYPE.getOrEmpty(id)
            .map { it.name }
            .orElse(Text.translatable("cobblemarket.shop_admin.npc.unknown"))
    }

    /** 行里显示的 NPC 名：有自定义名用自定义名，否则用类型名（宝可梦 = 带属性色的物种名） */
    fun displayName(
        customName: String, typeId: String, speciesId: String = "", aspectsRaw: String = ""
    ): Text =
        if (customName.isNotBlank()) Text.literal(customName) else typeName(typeId, speciesId, aspectsRaw)

    /** 物种 id → 客户端语言的物种名；认不出返回 null（调用方回退到实体类型名） */
    private fun speciesName(speciesId: String): MutableText? {
        if (speciesId.isBlank()) return null
        val id = Identifier.tryParse(speciesId) ?: return null
        return PokemonSpecies.getByIdentifier(id)?.translatedName
    }

    /**
     * 画一个 [size] × [size] 的 NPC 头像（左上角 [x], [y]）。
     * 3D 模型会被缩放/居中到这块区域；失败时回退图标（宝可梦除外，见类注释）。
     *
     * [speciesId] / [aspectsRaw] 只有宝可梦才有（编码见 [NpcDisplayMeta]）；其它实体留空即可。
     * [delta] 只给宝可梦图标的浮动动画用（`iconAnimMode = FLOAT` 时生效）。
     */
    fun draw(
        context: DrawContext, x: Int, y: Int, size: Int,
        typeId: String, speciesId: String = "", aspectsRaw: String = "", delta: Float = 0f
    ) {
        if (typeId == NpcDisplayMeta.POKEMON_TYPE) {
            drawPokemon(context, x, y, size, speciesId, aspectsRaw, delta)
            return
        }
        val entity = entityFor(typeId)
        if (entity == null) {
            drawFallback(context, x, y, size, typeId)
            return
        }
        try {
            // 统一朝向：不跟随上一次绘制留下的角度
            entity.setYaw(0f)
            entity.setHeadYaw(0f)
            entity.setPitch(0f)
            entity.bodyYaw = 0f
            // 画 3D 前先提交已排队的 GUI、清深度（照 IdCardRenderer）
            context.draw()
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
            InventoryScreen.drawEntity(
                context,
                x, y, x + size, y + size,
                drawSize(size, entity),
                0f,
                (x + size / 2f), (y + size / 2f),
                entity
            )
            context.draw()
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        } catch (_: Exception) {
            // 某些模组实体的渲染器需要同步数据，客户端预览可能缺数据 —— 回退图标，别让整个界面崩
            entityFailed += typeId
            entityCache.remove(typeId)
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
            drawFallback(context, x, y, size, typeId)
        }
    }

    /**
     * 宝可梦头像：走全项目统一的图标渲染（profile 姿态 + 固定缩放，天然能完整装进格子）。
     *
     * ⚠ 物种为空（旧绑定）时**留空不画** —— 用户 2026-10-23 明确不要占位图。
     */
    private fun drawPokemon(
        context: DrawContext, x: Int, y: Int, size: Int,
        speciesId: String, aspectsRaw: String, delta: Float
    ) {
        if (speciesId.isBlank()) return
        val identifier = Identifier.tryParse(speciesId) ?: return
        val species = PokemonSpecies.getByIdentifier(identifier) ?: return
        val key = "$speciesId|$aspectsRaw"
        val (renderable, state) = pokemonCache.getOrPut(key) {
            // aspects 决定模型 / 贴图（闪光、地区形态）；本项目 NPC 不展示携带物
            RenderablePokemon(species, NpcDisplayMeta.aspectsFrom(aspectsRaw), ItemStack.EMPTY) to
                FloatingState()
        }
        try {
            // 与市场 / 拍卖列表同一档：跟随「图标动画」设置（FLOAT 才有浮动）
            val useFloat = ClientConfig.iconAnimMode == IconAnimMode.FLOAT
            PokemonIconRenderer.draw(context, renderable, state, x, y, size, delta, useFloat)
        } catch (_: Exception) {
            // 模型缺数据等异常：留空，别让整个界面崩
        }
    }

    /**
     * 画布缩放：`InventoryScreen.drawEntity` 的 `size` 参数约等于「1 格实体高度画多少像素」，
     * 所以按实体高度反算才能**铺满格子**（固定值会让小猪这类小体型只剩几个像素）。
     */
    private fun drawSize(cell: Int, entity: LivingEntity): Int =
        ((cell * 0.85f) / entity.height.coerceAtLeast(0.1f)).toInt().coerceAtLeast(1)

    private fun entityFor(typeId: String): LivingEntity? {
        if (typeId.isBlank()) return null
        val world = MinecraftClient.getInstance().world ?: return null
        if (world !== cacheWorld) {
            entityCache.clear()
            entityFailed.clear()
            cacheWorld = world
        }
        if (typeId in entityFailed) return null
        entityCache[typeId]?.let { return it }
        val entity = try {
            val id = Identifier.tryParse(typeId)
            val type = id?.let { Registries.ENTITY_TYPE.getOrEmpty(it).orElse(null) }
            type?.create(world) as? LivingEntity
        } catch (_: Exception) {
            null
        }
        if (entity == null) {
            entityFailed += typeId
            return null
        }
        entity.setPosition(0.0, 0.0, 0.0)
        entity.isSilent = true
        entity.isInvisible = false
        entityCache[typeId] = entity
        return entity
    }

    private fun drawFallback(context: DrawContext, x: Int, y: Int, size: Int, typeId: String) {
        // 宝可梦在 [draw] 里就分流了，不会走到这里；这里只管其它实体
        // 生物类型有生成蛋就用生成蛋图标（一眼能认出是什么实体）；没有就用通用人形图标
        val type = typeId.takeIf { it.isNotBlank() }
            ?.let { Identifier.tryParse(it) }
            ?.let { Registries.ENTITY_TYPE.getOrEmpty(it).orElse(null) }
        val egg = type?.let { SpawnEggItem.forEntity(it) }
        if (egg != null) {
            val stack = ItemStack(egg)
            val offset = ((size - 16) / 2).coerceAtLeast(0)
            context.drawItem(stack, x + offset, y + offset)
            return
        }
        // 通用人形图标**不铺满格子**：留一圈边距再居中（用户 2026-10-24 要求缩一点）——
        // 铺满 20px 行高时和旁边的 3D 头像相比显得偏大。
        val iconSize = (size * GENERIC_ICON_FILL).roundToInt().coerceIn(1, size)
        val offset = (size - iconSize) / 2
        context.matrices.push()
        context.matrices.translate((x + offset).toDouble(), (y + offset).toDouble(), 0.0)
        context.matrices.scale(iconSize.toFloat() / GENERIC_ICON_SIZE, iconSize.toFloat() / GENERIC_ICON_SIZE, 1f)
        context.drawTexture(
            Identifier.of("cobblemarket", GENERIC_ICON),
            0, 0, 0f, 0f, GENERIC_ICON_SIZE, GENERIC_ICON_SIZE, GENERIC_ICON_SIZE, GENERIC_ICON_SIZE
        )
        context.matrices.pop()
    }
}
