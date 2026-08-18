package com.shusheng.cobblemarket.config

import com.shusheng.cobblemarket.CobbleMarket
import fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import java.math.BigInteger

object CurrencyHandler {
    private var useCobbleDollars = false
    private var currencyItem: Item = Registries.ITEM.get(Identifier.of("minecraft", "diamond"))

    fun load(config: CobbleMarketConfig) {
        useCobbleDollars = config.cobbledollars
        CobbleMarket.LOGGER.info("Currency: ${if (useCobbleDollars) "CobbleDollars" else config.currencyItem}")
        if (!useCobbleDollars) {
            val id = Identifier.tryParse(config.currencyItem) ?: Identifier.of("minecraft", "diamond")
            currencyItem = Registries.ITEM.get(id)
        }
    }

    fun getBalance(player: ServerPlayerEntity): BigInteger {
        if (useCobbleDollars) {
            return try {
                (player as CobbleDollarsPlayer).`cobbleDollars$getCobbleDollars`()
            } catch (e: Exception) { BigInteger.ZERO }
        }
        var total = 0
        val inv = player.inventory
        for (i in 0 until inv.size()) {
            if (inv.getStack(i).isOf(currencyItem)) total += inv.getStack(i).count
        }
        return BigInteger.valueOf(total.toLong())
    }

    fun remove(player: ServerPlayerEntity, amount: Int): Boolean {
        if (amount <= 0) return false
        if (useCobbleDollars) {
            return try {
                val p = player as CobbleDollarsPlayer
                val bal = p.`cobbleDollars$getCobbleDollars`()
                val amt = BigInteger.valueOf(amount.toLong())
                if (bal < amt) return false
                p.`cobbleDollars$setCobbleDollars`(bal.subtract(amt))
                true
            } catch (e: Throwable) {
                // 捕获 Throwable：mod 被移除时 cast 可能抛 NoClassDefFoundError
                CobbleMarket.LOGGER.error("Failed to remove {} currency from {}", amount, player.uuid, e)
                false
            }
        }
        val inv = player.inventory
        var total = 0
        for (i in 0 until inv.size()) {
            if (inv.getStack(i).isOf(currencyItem)) total += inv.getStack(i).count
        }
        if (total < amount) return false
        var remaining = amount
        for (i in 0 until inv.size()) {
            val stack = inv.getStack(i)
            if (stack.isOf(currencyItem)) {
                val r = minOf(remaining, stack.count)
                stack.decrement(r)
                remaining -= r
                if (remaining <= 0) break
            }
        }
        player.inventory.markDirty()
        return true
    }

    /**
     * 发放货币，返回实际发放量。
     * CobbleDollars 模式：全额发放或 0（失败）；物品模式：只放背包、不落地，
     * 放多少算多少（背包满即停），未发放部分由调用方保留在账本。
     */
    fun give(player: ServerPlayerEntity, amount: Long): Long {
        if (amount <= 0L) return 0L
        if (useCobbleDollars) {
            return try {
                val p = player as CobbleDollarsPlayer
                val bal = p.`cobbleDollars$getCobbleDollars`()
                p.`cobbleDollars$setCobbleDollars`(bal.add(BigInteger.valueOf(amount)))
                amount
            } catch (e: Throwable) {
                // 捕获 Throwable：mod 被移除时 cast 可能抛 NoClassDefFoundError
                CobbleMarket.LOGGER.error("Failed to give {} currency to {}", amount, player.uuid, e)
                0L
            }
        }
        var given = 0L
        var remaining = amount
        while (remaining > 0L) {
            // 分块不超过物品 maxCount：不依赖 insertStack 对超限栈的拆解行为
            val chunk = minOf(remaining, currencyItem.maxCount.toLong()).toInt()
            val stack = ItemStack(currencyItem, chunk)
            // insertStack 返回 true 只表示"至少放了一个"（部分放入也返回 true）；
            // 记账必须看 stack.count（剩余量），不能用返回值判断是否全部放入
            player.inventory.insertStack(stack)
            given += (chunk - stack.count).toLong()
            if (!stack.isEmpty) break // 没放完（背包满）：剩余不再发放，由调用方留在账本
            remaining -= chunk
        }
        return given
    }

    fun getName(): String {
        return if (useCobbleDollars) "PokéDollars" else currencyItem.name.string
    }
}
