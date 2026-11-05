package com.shusheng.cobblemarket.util

import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.DynamicRegistryManager

/**
 * 挂单精灵的**当前能力值**（2026-11-05，悬停窗口六边形用）。
 *
 * ⚠⚠ **必须走 `pokemon.getStat(...)`，不能自己按 IV/EV/等级套公式算** ——
 * 服务器装了 **Fusionmon**（融合模组），它把 Cobblemon 的全局 `StatProvider` 换成了
 * `FusionStatProvider`（`Fusionmon.checkStatProvider()` → `Cobblemon.setStatProvider(...)`）。
 * 融合精灵的种族值是「HP/特攻/特防 = (2×头 + 身体)/3，攻/防/速 = (2×身体 + 头)/3」，
 * 还有 `FusionConfig.statBonusPercent()` 的额外加成 —— **客户端拿不到这些数据**
 *（Fusionmon 只在战斗开始时把融合属性写进 Showdown JSON，不创建客户端可读的 species/form）。
 * 只有服务端调 `getStat` 才会经过 FusionStatProvider 拿到正确值。
 *
 * 返回顺序 = 六边形顶点顺序：**HP / 攻 / 防 / 速 / 特防 / 特攻**（照 Cobblemon 的 STATS 页）。
 * NBT 为空 / 损坏返回 null（客户端按「没有能力值」处理，不画）。
 */
object PokemonStats {

	fun fromNbt(registryAccess: DynamicRegistryManager, nbt: NbtCompound?): IntArray? {
		if (nbt == null) return null
		return runCatching {
			val pokemon = PokemonLoader.fromNbt(registryAccess, nbt)
			intArrayOf(
				pokemon.maxHealth,
				pokemon.attack,
				pokemon.defence,
				pokemon.speed,
				pokemon.specialDefence,
				pokemon.specialAttack,
			)
		}.getOrNull()
	}
}
