package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.SpawnEggItem
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11

/**
 * **管理界面里的 NPC 头像**（2026-10-21）。
 *
 * 按实体类型 id 在客户端建一个**假实体**，用 `InventoryScreen.drawEntity` 画 3D 模型。
 * 服务端只下发类型 id（+ 自定义名），不传整个实体 NBT —— 干净、也不用担心把坐标 / 背包带过来。
 *
 * ⚠ **自定义皮肤显示不出来**：Easy NPC 之类的皮肤存在实体 NBT 里，本期没有传；
 *   这类 NPC 会显示该实体类型的默认模型。原版村民 / 盔甲架 / 大部分生物完整。
 * ⚠ 找不到类型 / 类型不是生物 / 创建失败 ⇒ 回退成生成蛋图标，再不行用通用人形图标。
 * ⚠ 3D 模型**写深度缓冲**：画之前 flush + 清深度，画完再 flush + 清一次
 *   （照 `IdCardRenderer` 的教训，否则后续 GUI 会被模型切掉一块）。
 */
object NpcAvatarRenderer {

    private const val GENERIC_ICON = "textures/gui/button/personal.png"
    private const val GENERIC_ICON_SIZE = 48

    private var cacheWorld: net.minecraft.client.world.ClientWorld? = null
    private val cache = mutableMapOf<String, LivingEntity>()
    private val failed = mutableSetOf<String>()

    /** 实体类型 id 的**客户端语言**名字（认不出时给一个通用占位） */
    fun typeName(typeId: String): Text {
        if (typeId.isBlank()) return Text.translatable("cobblemarket.shop_admin.npc.unknown")
        val id = Identifier.tryParse(typeId) ?: return Text.translatable("cobblemarket.shop_admin.npc.unknown")
        return Registries.ENTITY_TYPE.getOrEmpty(id)
            .map { it.name }
            .orElse(Text.translatable("cobblemarket.shop_admin.npc.unknown"))
    }

    /** 行里显示的 NPC 名：有自定义名用自定义名，否则用类型名 */
    fun displayName(customName: String, typeId: String): Text =
        if (customName.isNotBlank()) Text.literal(customName) else typeName(typeId)

    /**
     * 画一个 [size] × [size] 的 NPC 头像（左上角 [x], [y]）。
     * 3D 模型会被缩放/居中到这块区域；失败时回退图标。
     */
    fun draw(context: DrawContext, x: Int, y: Int, size: Int, typeId: String) {
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
                ((size * 0.85f) / 2f).toInt().coerceAtLeast(1),
                0f,
                (x + size / 2f), (y + size / 2f),
                entity
            )
            context.draw()
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
        } catch (_: Exception) {
            // 某些模组实体的渲染器需要同步数据，客户端预览可能缺数据 —— 回退图标，别让整个界面崩
            failed += typeId
            cache.remove(typeId)
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
            drawFallback(context, x, y, size, typeId)
        }
    }

    private fun entityFor(typeId: String): LivingEntity? {
        if (typeId.isBlank() || typeId in failed) return null
        val world = MinecraftClient.getInstance().world ?: return null
        if (world !== cacheWorld) {
            cache.clear()
            failed.clear()
            cacheWorld = world
        }
        cache[typeId]?.let { return it }
        val id = Identifier.tryParse(typeId) ?: run { failed += typeId; return null }
        val type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null) ?: run { failed += typeId; return null }
        return try {
            val entity = type.create(world) as? LivingEntity
            if (entity == null) {
                failed += typeId
                null
            } else {
                entity.setPosition(0.0, 0.0, 0.0)
                entity.isSilent = true
                entity.isInvisible = false
                cache[typeId] = entity
                entity
            }
        } catch (_: Exception) {
            failed += typeId
            null
        }
    }

    private fun drawFallback(context: DrawContext, x: Int, y: Int, size: Int, typeId: String) {
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
        context.matrices.push()
        context.matrices.translate(x.toDouble(), y.toDouble(), 0.0)
        context.matrices.scale(size.toFloat() / GENERIC_ICON_SIZE, size.toFloat() / GENERIC_ICON_SIZE, 1f)
        context.drawTexture(
            Identifier.of("cobblemarket", GENERIC_ICON),
            0, 0, 0f, 0f, GENERIC_ICON_SIZE, GENERIC_ICON_SIZE, GENERIC_ICON_SIZE, GENERIC_ICON_SIZE
        )
        context.matrices.pop()
    }
}
