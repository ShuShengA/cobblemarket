package com.shusheng.cobblemarket.util

import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text

/**
 * 维度显示名：原版三维度用「友好名（维度 id）」，模组 / 多世界维度直接显示 registry id。
 *
 * ⚠ 只构造 `Text`，不 `.string`、不拼语言相关标点 —— 友好名和括号交给客户端翻译；
 *   模组维度没有统一的显示名 API，id（`namespace:path`）是唯一精确且跨包可用的标识。
 *
 * 服务端（监视开始 / 结束的跨维度提示）与客户端（快速功能右侧面板的悬停窗）共用这一份。
 */
object DimensionText {

    fun of(world: ServerWorld): Text = of(world.registryKey.value.toString())

    fun of(id: String): Text {
        val friendlyKey = when (id) {
            "minecraft:overworld" -> "cobblemarket.peek.dimension.overworld"
            "minecraft:the_nether" -> "cobblemarket.peek.dimension.nether"
            "minecraft:the_end" -> "cobblemarket.peek.dimension.end"
            else -> return Text.literal(id)
        }
        return Text.translatable(
            "cobblemarket.peek.dimension.with_id",
            Text.translatable(friendlyKey),
            Text.literal(id)
        )
    }
}
