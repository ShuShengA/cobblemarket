package com.shusheng.cobblemarket.util

import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 聊天栏消息里的**玩家名统一入口** —— 一律钉成游戏默认白，不跟着我们设的行色走。
 *
 * **为什么必须显式设色**：MC 渲染 Text 时父节点的样式会往下传（`Style.withParent` —— 自己没设色
 * 就取父的）。我们的播报整行设了色（拍卖蓝 / 求购黄 / 结果包「成功绿失败红」…），名字段只要
 * 不设色就会被染成那个颜色，看起来像我们把玩家名涂了（2026-10-07 用户报的现象）。
 * ⇒ 钉 `WHITE` = 名字保持"游戏本来该有的样子"（用户定：「引用玩家的名字，不要主动上色」）。
 *
 * ⚠ **别去掉 `.formatted(Formatting.WHITE)`** —— 去掉不是"不上色"，而是**继承外层行色**，
 * 正是这里要避免的东西。
 * ⚠ 称号类模组改的名字颜色**不会**透过来 —— 用户明确「不归我们管」，别为它做兼容 / 探测。
 * ⚠ 只用于**聊天栏消息**（network 包发出的 Text / 命令反馈）；界面（GUI）里的名字各有各的
 * 配色，不走这里。
 */
object PlayerNameText {

    /** 玩家名的纯字符串（存档里的名字 / `player.name.string`）→ 默认白的 Text 段 */
    fun of(name: String): MutableText = Text.literal(name).formatted(Formatting.WHITE)
}
