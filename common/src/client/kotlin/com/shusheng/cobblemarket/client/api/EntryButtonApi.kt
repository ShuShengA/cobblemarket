package com.shusheng.cobblemarket.client.api

import com.shusheng.cobblemarket.CobbleMarket
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **给附属模组用的入口按钮注册 API**（2026-10-30 新增，1.3.5）。
 *
 * 背景：主模组的入口界面底部、ID 卡与「分享」之间有一个**固定空槽**（22×22）。
 * 附属模组（如 CobbleKit）在自己的客户端初始化时注册一颗按钮，主模组只负责画；
 * **主模组不引用附属模组的任何类** ⇒ 删掉附属模组的 jar，按钮自然消失，其余槽位不动。
 *
 * 规则：
 * - **只有一个槽位**：注册多颗时只画第一颗，其余记一条 warn（以后要加再说，别偷偷挤掉别人）
 * - 只能在**客户端**调用（主模组入口界面是客户端界面）
 * - 图标来自注册方自己的资源命名空间，主模组不做任何路径约定
 * - 签名一旦发布就不要再改（外部模组编译期依赖）
 */
object EntryButtonApi {

	/**
	 * 一颗外部入口按钮。
	 *
	 * @property id 唯一标识（重复注册会被忽略），建议用 modid 前缀，如 `cobblekit:main`
	 * @property icon 图标纹理（注册方自己的命名空间）
	 * @property iconTexW / [iconTexH] 源图实际尺寸（48×48 缩到 18×18 显示是入口按钮的惯例）
	 * @property iconScale 显示缩放；传 `18f / iconTexW` 即按 18×18 显示
	 * @property tooltip 悬停提示（走注册方自己的词条）
	 * @property onClick 点击动作（通常 `client.setScreen(...)`）
	 */
	data class EntryButton(
		val id: String,
		val icon: Identifier,
		val iconTexW: Int,
		val iconTexH: Int,
		val iconScale: Float,
		val tooltip: Text,
		val onClick: () -> Unit,
	)

	private val buttons = CopyOnWriteArrayList<EntryButton>()

	/** 注册（重复 id 忽略；多颗时只有第一颗会被画出来） */
	fun register(button: EntryButton) {
		if (buttons.any { it.id == button.id }) return
		buttons += button
	}

	/** 主模组入口界面读取用；外部模组不需要调 */
	fun registered(): List<EntryButton> = buttons.toList()

	/** 注册了多颗时，由入口界面在初始化时记一次 warn（避免每帧刷屏） */
	internal fun warnIfOverflow() {
		if (buttons.size > 1) {
			CobbleMarket.LOGGER.warn(
				"EntryButtonApi: {} external entry buttons registered but only one slot is available; using '{}', ignoring {}",
				buttons.size, buttons.first().id, buttons.size - 1
			)
		}
	}
}
