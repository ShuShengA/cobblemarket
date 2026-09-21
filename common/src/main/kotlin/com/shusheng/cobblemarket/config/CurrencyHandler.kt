package com.shusheng.cobblemarket.config

import com.shusheng.cobblemarket.CobbleMarket
import com.shusheng.cobblemarket.platform.cobecoAdd
import com.shusheng.cobblemarket.platform.cobecoGetBalance
import com.shusheng.cobblemarket.platform.cobecoRemove
import com.shusheng.cobblemarket.platform.impactorAdd
import com.shusheng.cobblemarket.platform.impactorGetBalance
import com.shusheng.cobblemarket.platform.impactorRemove
import fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

object CurrencyHandler {
    private var useCobbleDollars = false
    private var useCobeco = false
    /** Cobblemon Economy 模式下是否用 PokeCoins（PCO）结算；false=用 PokeDollars（POKE） */
    private var usePco = false
    /** Impactor 直连（不装 Cobblemon Economy 时）；优先级低于 Cobblemon Economy 与 CobbleDollars */
    private var useImpactor = false

    fun load(config: CobbleMarketConfig) {
        useCobeco = config.cobblemonEconomy
        usePco = useCobeco && config.cobecoCurrency == "PCO"
        // 优先级 Cobblemon Economy → CobbleDollars → Impactor → 物品：
        // Cobblemon Economy 在场时优先走它（其 API 内部按 main_currency 桥接路由到 CobbleDollars/Impactor 后端）；
        // 两个虚拟开关都为 true 时 CobbleDollars 优先（升级无感：Impactor 常作为其它模组的基础依赖被装，
        // 若让它优先，老服主升级后市场会突然切到 Impactor 账户）
        useCobbleDollars = !useCobeco && config.cobbledollars
        useImpactor = !useCobeco && !useCobbleDollars && config.impactor
        CobbleMarket.LOGGER.info("Currency: ${if (useCobeco) "Cobblemon Economy (" + (if (usePco) "PCO" else "PokeDollars") + ")" else if (useCobbleDollars) "CobbleDollars" else if (useImpactor) "Impactor" else config.currencyItem}")
    }

    // 货币物品动态解析：初始化时 Cobblemon 物品可能尚未注册（mod 加载顺序），
    // 运行时每次取物品都会重新查注册表，避免缓存到 air
    private fun currencyItem(): Item = CobbleMarketConfig.getCurrencyItem()

    fun getBalance(player: ServerPlayerEntity): BigInteger {
        if (useCobeco) {
            // Cobblemon Economy 余额为 BigDecimal（可能有小数），取整向零截断，与其内部扣款粒度一致
            val raw = cobecoGetBalance(player.uuid, usePco)
            if (raw == null) {
                // 桥接内部已捕获异常（含 mod 被移除时的 NoClassDefFoundError）
                CobbleMarket.LOGGER.error("Failed to get Cobblemon Economy balance for {}", player.uuid)
                return BigInteger.ZERO
            }
            return raw.setScale(0, RoundingMode.DOWN).toBigInteger()
        }
        if (useCobbleDollars) {
            return try {
                (player as CobbleDollarsPlayer).`cobbleDollars$getCobbleDollars`()
            } catch (e: Exception) { BigInteger.ZERO }
        }
        if (useImpactor) {
            // Impactor 余额为 BigDecimal（可能有小数），取整向零截断（与 Cobblemon Economy 分支同规则）
            val raw = impactorGetBalance(player.uuid)
            if (raw == null) {
                // 桥接内部已捕获异常（含 mod 被移除时的 NoClassDefFoundError）
                CobbleMarket.LOGGER.error("Failed to get Impactor balance for {}", player.uuid)
                return BigInteger.ZERO
            }
            return raw.setScale(0, RoundingMode.DOWN).toBigInteger()
        }
        val item = currencyItem()
        var total = 0
        val inv = player.inventory
        for (i in 0 until inv.size()) {
            if (inv.getStack(i).isOf(item)) total += inv.getStack(i).count
        }
        return BigInteger.valueOf(total.toLong())
    }

    fun remove(player: ServerPlayerEntity, amount: Int): Boolean {
        if (amount <= 0) return false
        if (useCobeco) {
            // subtractBalance/subtractPco 余额不足返回 false，与物品模式「余额不足」语义一致；
            // 桥接内部捕获异常（mod 被移除等）同样返回 false
            return cobecoRemove(player.uuid, BigDecimal.valueOf(amount.toLong()), usePco)
        }
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
        if (useImpactor) {
            // withdraw 余额不足返回 unsuccessful → false，与物品模式「余额不足」语义一致；
            // 桥接内部捕获异常（mod 被移除等）同样返回 false
            return impactorRemove(player.uuid, BigDecimal.valueOf(amount.toLong()))
        }
        val item = currencyItem()
        val inv = player.inventory
        var total = 0
        for (i in 0 until inv.size()) {
            if (inv.getStack(i).isOf(item)) total += inv.getStack(i).count
        }
        if (total < amount) return false
        var remaining = amount
        for (i in 0 until inv.size()) {
            val stack = inv.getStack(i)
            if (stack.isOf(item)) {
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
        if (useCobeco) {
            // 桥接内部捕获异常（mod 被移除等）返回 false，按未发放处理
            return if (cobecoAdd(player.uuid, BigDecimal.valueOf(amount), usePco)) amount else 0L
        }
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
        if (useImpactor) {
            // 桥接内部捕获异常（mod 被移除等）返回 false，按未发放处理
            return if (impactorAdd(player.uuid, BigDecimal.valueOf(amount))) amount else 0L
        }
        val item = currencyItem()
        var given = 0L
        var remaining = amount
        while (remaining > 0L) {
            // 分块不超过物品 maxCount：不依赖 insertStack 对超限栈的拆解行为
            val chunk = minOf(remaining, item.maxCount.toLong()).toInt()
            val stack = ItemStack(item, chunk)
            // insertStack 返回 true 只表示"至少放了一个"（部分放入也返回 true）；
            // 记账必须看 stack.count（剩余量），不能用返回值判断是否全部放入
            player.inventory.insertStack(stack)
            given += (chunk - stack.count).toLong()
            if (!stack.isEmpty) break // 没放完（背包满）：剩余不再发放，由调用方留在账本
            remaining -= chunk
        }
        return given
    }

    /** 虚拟货币分支（Cobblemon Economy / CobbleDollars / Impactor）——界面与聊天统一显示 ₽（见 currencyText/客户端 CurrencyDisplay） */
    private val virtualCurrency: Boolean get() = useCobeco || useCobbleDollars || useImpactor

    private fun virtualCurrencyKey(): String = when {
        usePco -> POKECOINS_KEY
        useCobeco -> POKEDOLLARS_KEY
        useImpactor -> IMPACTOR_KEY
        else -> COBBLEDOLLARS_KEY
    }

    fun getName(): String {
        return if (virtualCurrency) virtualCurrencyKey() else currencyItem().name.string
    }

    /** payload 字段用的货币标识：物品模式发物品 ID（客户端按玩家语言渲染），虚拟货币发翻译 key。
     *  服务端语言恒为 en_us 且受资源环境影响，服务端渲染货币名在专用服务器上不可靠 */
    fun getCurrencyId(): String {
        if (virtualCurrency) return virtualCurrencyKey()
        return Registries.ITEM.getId(currencyItem()).toString()
    }

    /** 聊天消息用的货币文本：虚拟货币统一 ₽ 符号（2026-08-27 用户拍板，不显示货币名），
     *  物品模式返回物品名翻译（客户端按玩家语言渲染） */
    fun currencyText(): net.minecraft.text.Text {
        if (virtualCurrency) return net.minecraft.text.Text.literal("₽")
        return net.minecraft.text.Text.translatable(currencyItem().translationKey)
    }

    /** 聊天消息用：货币名金色（与界面货币色一致，2026-08-24 拍板） */
    fun goldCurrencyText(): net.minecraft.text.Text =
        currencyText().copy().formatted(net.minecraft.util.Formatting.GOLD)

    /** 金额千分位格式化（整数类型 Long/Int/BigInteger；其它类型原样）。
     *  聊天消息里的价格/金额统一走这里——2026-09-09 拍板「显示价格默认千分位」。 */
    fun formatAmount(v: Any?): String {
        if (v !is Long && v !is Int && v !is java.math.BigInteger) return v.toString()
        return v.toString().reversed().chunked(3).joinToString(",").reversed()
    }

    /** 聊天消息用：金额数字金色（Int/Long/String 均可，整数类型自动千分位） */
    fun goldAmount(v: Any): net.minecraft.text.Text =
        net.minecraft.text.Text.literal(formatAmount(v)).formatted(net.minecraft.util.Formatting.GOLD)

    /** Cobblemon Economy POKE 结算的货币标识（翻译 key，显示 PokeDollars）；物品模式见 getCurrencyId() 的物品 ID 分支 */
    const val POKEDOLLARS_KEY = "cobblemarket.currency.pokedollars"

    /** Cobblemon Economy PCO 结算的货币标识（翻译 key，显示 PokeCoins） */
    const val POKECOINS_KEY = "cobblemarket.currency.pokecoins"

    /** CobbleDollars 模式的货币标识（翻译 key，显示 CobbleDollars） */
    const val COBBLEDOLLARS_KEY = "cobblemarket.currency.cobbledollars"

    /** Impactor 直连模式的货币标识（客户端 CurrencyDisplay 统一显示 ₽） */
    const val IMPACTOR_KEY = "cobblemarket.currency.impactor"
}
