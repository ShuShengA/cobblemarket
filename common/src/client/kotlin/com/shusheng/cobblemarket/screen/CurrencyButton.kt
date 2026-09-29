package com.shusheng.cobblemarket.screen

import com.shusheng.cobblemarket.client.CURRENCY_BUTTON_ICON_SIZE
import com.shusheng.cobblemarket.client.POINTS_SYMBOL_TEXTURE
import com.shusheng.cobblemarket.client.currencyUnitStack
import net.minecraft.text.Text

/**
 * 刷新「货币切换按钮」的外观 —— **三态**（2026-09-29 用户口径）。
 *
 * | 当前页 | 按钮上显示 |
 * |---|---|
 * | RP 页 | RP 图标 |
 * | 主货币页 · **物品本位** | **那件物品的图标** |
 * | 主货币页 · 虚拟货币 | `₽` |
 *
 * ⚠ **物品图标与 RP 图标同尺寸**（[CURRENCY_BUTTON_ICON_SIZE]）—— 切换币种时不该有大小跳变。
 * ⚠ 物品本位那一态**只有 1.3.0 有**：1.2.x 没有 RP、也就没有这个切换按钮。
 *
 * ⚠ **幂等，可以在 `render` 里每帧调**：主货币名来自余额包，而余额包是**异步**到的
 * （界面 `init()` 才发 `RequestBalancePayload`）⇒ 首帧可能还拿不到，那时先显示 `₽`、
 * 包一到就自动换成图标。状态没变时本函数**什么都不做**（不建 Text、不赋值）。
 */
fun updateCurrencyButtonIcon(btn: NineSliceButton, points: Boolean) {
    if (points) {
        // RP 页：RP 图标（与余额 HUD 同源的贴图版）
        if (btn.iconLeft == POINTS_SYMBOL_TEXTURE) return
        btn.iconStack = null
        btn.iconLeft = POINTS_SYMBOL_TEXTURE
        btn.setMessage(Text.literal(""))
        return
    }
    val unitStack = currencyUnitStack()
    if (unitStack != null) {
        // 主货币页 · 物品本位：那件物品的图标
        // ⚠ 比 `item` 而不是比引用 —— `currencyUnitStack()` 每次返回的是**新的** ItemStack
        if (btn.iconStack?.item === unitStack.item) return
        btn.iconLeft = null
        btn.iconStack = unitStack
        btn.iconStackSize = CURRENCY_BUTTON_ICON_SIZE
        btn.setMessage(Text.literal(""))
        return
    }
    // 主货币页 · 虚拟货币：仍是 ₽
    if (btn.iconLeft == null && btn.iconStack == null && btn.message.string == "₽") return
    btn.iconStack = null
    btn.iconLeft = null
    btn.setMessage(Text.literal("₽"))
}
