package com.shusheng.cobblemarket.client

import com.cobblemon.mod.common.CobblemonItemComponents
import com.cobblemon.mod.common.item.components.TMMoveComponent
import net.minecraft.client.MinecraftClient
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtOps
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKeys
import net.minecraft.text.Text
import net.minecraft.text.TextCodecs
import net.minecraft.util.Identifier

/**
 * 组件快照条目的人类可读摘要（黑名单/限价/求购单列表行显示）：
 * 附魔翻译名+等级、自定义名、TM 招式名、药水（存在即显示）。
 * spec null/空 = 所有变体条目，返回空串（行显示物品名即可）。
 */
object ItemComponentsDisplay {

    private val ENCHANTMENT_IDS: Set<String> by lazy {
        setOf(
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.STORED_ENCHANTMENTS).toString(),
            Registries.DATA_COMPONENT_TYPE.getId(DataComponentTypes.ENCHANTMENTS).toString(),
        )
    }

    private val TM_MOVE_ID: String by lazy {
        Registries.DATA_COMPONENT_TYPE.getId(CobblemonItemComponents.TM_MOVE).toString()
    }

    /** 物品栈的组件摘要（内部提取白名单组件，行显示用）；无白名单组件返回空串 */
    fun summaryOfStack(stack: ItemStack): String {
        val registryManager = MinecraftClient.getInstance().world?.registryManager ?: return ""
        return summary(com.shusheng.cobblemarket.market.ItemRuleComponents.extractSpec(stack, registryManager))
    }

    /**
     * 悬停词条按键语义（⚠ **本项目自定义，非原版行为**）：Shift=完整词条、Ctrl=组件明细、Shift+Ctrl=两者、不按=普通。
     *
     * 原版并没有「按住某个键展开」这回事：`TooltipType.ADVANCED` 由 F3+H（advancedItemTooltips）决定，
     * `withCreative()` 原版只用在创造模式物品栏、且是**始终生效**不需要按键。这里的按键映射是本模组自己加的。
     * （Fabric 注入只看构建时按键，与 F3+H 无关）
     */
    fun tooltipTypeForHover(): TooltipType {
        val advanced = net.minecraft.client.gui.screen.Screen.hasShiftDown()
        val creative = net.minecraft.client.gui.screen.Screen.hasControlDown()
        return when {
            advanced && creative -> TooltipType.ADVANCED.withCreative()
            advanced -> TooltipType.ADVANCED
            creative -> TooltipType.BASIC.withCreative()
            else -> TooltipType.BASIC
        }
    }

    /** 当前是否处于展开状态（Shift 或 Ctrl 按住） */
    fun hoverExpanded(): Boolean =
        net.minecraft.client.gui.screen.Screen.hasShiftDown() || net.minecraft.client.gui.screen.Screen.hasControlDown()

    /**
     * 悬停词条统一构建：F3+H 关闭时过滤 ADVANCED 自带的物品 ID 行——
     * Shift 展开语义 = 纯信息块（原版背包按 Shift 本不显示 ID，ID 行是 F3+H 的 ADVANCED 套餐）；
     * F3+H 开启的玩家习惯常驻 ID 行，保留。
     * 只作用于 getTooltip 返回值，自定义行（itemId/价格等）由各界面自行追加不受影响。
     */
    fun itemTooltip(stack: ItemStack, player: net.minecraft.entity.player.PlayerEntity?, type: TooltipType): List<Text> {
        val lines = stack.getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, player, type)
        val filtered = if (!type.isAdvanced() || MinecraftClient.getInstance().options.advancedItemTooltips) lines
        else {
            // 原版 isAdvanced 分支附带的调试行：物品 ID 行（DARK_GRAY + 可解析 Identifier）
            // 与「N 个组件」行（DARK_GRAY + item.components 词条）——都是 F3+H 的 ADVANCED 套餐内容
            val darkGray = net.minecraft.text.TextColor.fromFormatting(net.minecraft.util.Formatting.DARK_GRAY)
            lines.filterNot { line ->
                if (line.style.color != darkGray) return@filterNot false
                val content = line.content
                net.minecraft.util.Identifier.tryParse(line.string) != null ||
                    (content is net.minecraft.text.TranslatableTextContent && content.key == "item.components")
            }
        }
        // 受损物品补一行耐久数值，**紧跟物品名**。原版只在 Shift 展开（ADVANCED）时才给这行，
        // 但在市场里买东西不该要求玩家先按住 Shift —— 看不到耐久就可能高价买到快报废的工具。
        // 满耐久不显示，与图标上的耐久条（drawItemWithBar）口径一致。
        // 插在 index 1 而不是末尾：调用方有 `.drop(1)` 丢掉物品名的用法，放末尾会被丢掉的语义带偏。
        // ⚠ 仅普通悬停补：Shift 展开（ADVANCED）时原版自己就带 item.durability 行，补了会变成两行耐久
        if (!stack.isDamaged || type.isAdvanced()) return filtered
        val out = filtered.toMutableList()
        out.add(
            minOf(1, out.size),
            Text.translatable(
                "cobblemarket.item.durability",
                stack.maxDamage - stack.damage,
                stack.maxDamage
            ).formatted(net.minecraft.util.Formatting.GRAY)
        )
        return out
    }

    fun summary(spec: NbtCompound?): String {
        if (spec == null || spec.isEmpty) return ""
        val client = MinecraftClient.getInstance()
        val registryManager = client.world?.registryManager
        val parts = mutableListOf<String>()

        // 附魔（1.21.1 NBT 格式 {levels: {"minecraft:sharpness": 5}}）：用原版 getName(等级) 拿标准显示
        //（附魔翻译名 + 罗马数字等级，如「锋利 V」——阿拉伯数字不符游戏内习惯）
        ENCHANTMENT_IDS.forEach { key ->
            if (spec.contains(key)) {
                val levels = spec.getCompound(key)?.getCompound("levels") ?: return@forEach
                levels.keys.forEach { enchId ->
                    val level = levels.getInt(enchId)
                    val text = registryManager?.let {
                        it.get(RegistryKeys.ENCHANTMENT)
                            .getOrEmpty(Identifier.tryParse(enchId) ?: return@forEach)
                            .map { e ->
                                // 照原版 Enchantment.getName：附魔名 + 罗马数字等级（enchantment.level.N 词条）；
                                // 等级 1 且上限 1 的附魔不带后缀
                                if (level == 1 && e.maxLevel == 1) e.description.string
                                else "${e.description.string} ${Text.translatable("enchantment.level.$level").string}"
                            }.orElse("$enchId $level")
                    } ?: "$enchId $level"
                    parts.add(text)
                }
            }
        }
        // 自定义名（TextCodecs 的 NBT 编码解码，1.21.1 无 Text.Serialization.fromNbt）
        if (spec.contains("minecraft:custom_name")) {
            val textNbt = spec.get("minecraft:custom_name")
            val text = if (textNbt != null && registryManager != null) {
                try {
                    TextCodecs.CODEC.parse(registryManager.getOps(NbtOps.INSTANCE), textNbt)
                        .resultOrPartial { }.orElse(null)
                } catch (_: Exception) {
                    null
                }
            } else null
            if (text != null && text.string.isNotEmpty()) parts.add(text.string)
        }
        // 药水：只显示存在标记（效果细节解析复杂，场景罕见）
        if (spec.contains("minecraft:potion_contents")) {
            parts.add(Text.translatable("cobblemarket.gui.component_potion").string)
        }
        // TM 招式：快照重建 TM 栈走 API 读招式名（不猜 TMMoveComponent 的 NBT 字段名）
        if (spec.contains(TM_MOVE_ID) && registryManager != null) {
            val stack = try {
                ItemStack.fromNbtOrEmpty(registryManager, NbtCompound().apply {
                    putString("id", "cobblemon:technical_machine")
                    putInt("count", 1)
                    put("components", spec)
                })
            } catch (_: Exception) {
                ItemStack.EMPTY
            }
            // 用翻译后的显示名（name 是英文招式 ID，displayName 才是玩家看到的招式名）
            TMMoveComponent.getTMMove(stack)?.let { parts.add(it.displayName.string) }
        }
        return parts.joinToString("、")
    }

    /** 行内图标栈：带快照的条目重建完整物品（含真实组件），无快照用默认栈 */
    fun iconStack(itemId: String, spec: NbtCompound?): ItemStack? {
        val registryManager = MinecraftClient.getInstance().world?.registryManager
        if (spec != null && !spec.isEmpty && registryManager != null) {
            val rebuilt = try {
                ItemStack.fromNbtOrEmpty(registryManager, NbtCompound().apply {
                    putString("id", itemId)
                    putInt("count", 1)
                    put("components", spec)
                })
            } catch (_: Exception) {
                ItemStack.EMPTY
            }
            if (!rebuilt.isEmpty) return rebuilt
        }
        Identifier.tryParse(itemId)?.let { return ItemStack(Registries.ITEM.get(it)) }
        return null
    }
}
