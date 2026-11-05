package com.shusheng.cobblemarket.client

import com.mojang.blaze3d.systems.RenderSystem
import com.shusheng.cobblemarket.network.ListingEntry
import com.shusheng.cobblemarket.util.StatNames
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.BufferRenderer
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * **精灵能力值六边形**（2026-11-05，服主建议）—— 照搬 Cobblemon 队伍详情的做法：
 * 底图用 Cobblemon 自己的 `summary_stats_chart.png`，填充是自绘多边形，6 个角落标数值。
 *
 * ⚠ **计算口径必须与 Cobblemon 完全一致**（`CobblemonStatProvider`）：
 * - HP：`floor((2*base + iv + ev/4 + 100) * level / 100 + 10)`；**脱壳忍者 HP = 1**
 * - 其他：`nature.modifyStat(stat, (2*base + iv + ev/4) * level / 100 + 5)`
 * - 有效 IV = 极限特训值（`ht >= 0`）否则原 IV —— 与 `IVs.getEffectiveBattleIV` 同口径
 *
 * 归一化照 Cobblemon：**÷ 400**（不是 255、不是物种最大值）。
 * 顶点顺序照 Cobblemon：HP / 攻 / 防 / 速 / 特防 / 特攻（从 12 点顺时针）。
 *
 * ## 尺寸（2026-11-05 第三轮：动态适配悬停窗）
 *
 * 悬停窗里 IV 六行下面紧接**整宽**技能块 ⇒ 六边形一旦超过「内容顶部 → 技能块上分割线」
 * 这段高度，就会压到技能文字。固定 0.35 偏小、固定 0.4375/0.65625 又会溢出
 *（用户逐轮实测报出）。所以改成：
 * - **垂直**：从悬停窗内容顶部 `ty` 开始画，高度取「到技能块分割线」的可用空间
 *   （[scaleForAvailableHeight] 按可用高度反算缩放，限制在 0.32~0.5）
 * - **水平**：按实际左侧标签宽度把图往左贴（[leftInset]），去掉左侧那圈固定空档
 * - 标签**始终原字号**（不随图放大）
 *
 * ⚠ 贴图是 Cobblemon 的**内部资源路径**（不是 API），大版本升级时复核一眼。
 */
object StatsChart {

	private val CHART_TEX = Identifier.of("cobblemon", "textures/gui/summary/summary_stats_chart.png")
	private const val TEX_W = 166
	private const val TEX_H = 192

	/**
	 * 六边形中心 / 半径（**底图原图坐标**）。
	 *
	 * ⚠⚠ 别照抄 Cobblemon `drawStatPolygon` 里的 `(67, 70)` / `r=48` —— 那是它自己
	 * widget 坐标系的数值（它先把底图按 `(x+25.5)/SCALE` 偏移再画）。换算回底图原图：
	 * 中心 = `((67-25.5)/0.5, (70-22)/0.5)` = **(83, 96) = 图片正中心**，半径 = **96**（六边形顶到图顶边）。
	 * 2026-11-05 第一版按 (67,70)/48 画 → 填充偏左上、只有正常大小的 1/4（用户截图报出）。
	 */
	private const val HEX_CX = 83f
	private const val HEX_CY = 96f
	private const val HEX_R = 96f

	/**
	 * 填充下限 —— 照 Cobblemon `drawStatPolygon` 的 `coerceIn(5 / radius, 1F)`（radius = 48）。
	 *
	 * ⚠ 不设下限时，低等级精灵（如 Lv.5，能力值 10~20）÷400 只有 2%~5%，
	 *   蓝色区域会缩成一个点（2026-11-05 用户截图报「蓝色区域确实偏小，比例不对」，
	 *   原版 Cobblemon 会把它抬到约 10.4%）。
	 */
	private const val MIN_RATIO = 5f / 48f

	/** cos30°（六边形顶点水平投影系数） */
	private const val COS30 = 0.8660254f

	/** 最宽英文标签 `Defense` 的宽度（原字号）；标签区左右各按它预留 */
	private const val MAX_LABEL_W = 42f

	/** 上下两行标签占的高度（原字号：名称一行 + 数值一行） */
	private const val LABEL_BLOCK_H = 18

	/** 上下留白 */
	private const val V_PAD = 3

	/** 上下标签到六边形顶点的外推距离 */
	private const val LABEL_GAP_V = 12

	/** 左右标签到六边形斜边的外推距离 */
	private const val LABEL_GAP_H = 4

	/**
	 * 按**可用高度**反算缩放：占用高 = `2×(HEX_R×s + 12) + 18 + 3`，反解出 s。
	 * 限制 0.32~0.5：太小看不清、太大没必要（再大就超出悬停窗了）。
	 */
	fun scaleForAvailableHeight(availableH: Int): Float {
		val fixed = 2 * LABEL_GAP_V + LABEL_BLOCK_H + V_PAD
		return ((availableH - fixed) / (2f * HEX_R)).coerceIn(0.32f, 0.5f)
	}

	/** 指定缩放下的占用宽（布局用） */
	fun widthFor(scale: Float): Int =
		(2 * ((HEX_R * scale + LABEL_GAP_H) * COS30 + MAX_LABEL_W) + 9).roundToInt()

	/** 指定缩放下的占用高（布局用） */
	fun heightFor(scale: Float): Int =
		(2 * (HEX_R * scale + LABEL_GAP_V) + LABEL_BLOCK_H + V_PAD).roundToInt()

	/**
	 * 左侧实际标签比 [MAX_LABEL_W] 窄出来的空白（px）——
	 * 调用方把图整体左移这么多，左侧标签就正好贴在文本右缘（去掉一圈空档）。
	 */
	fun leftInset(font: TextRenderer, scale: Float): Int {
		val r = HEX_R * scale
		val leftLabelW = maxOf(font.getWidth(labelOf(4)), font.getWidth(labelOf(5)))
		return (widthFor(scale) / 2f - ((r + LABEL_GAP_H) * COS30 + leftLabelW)).toInt().coerceAtLeast(0)
	}

	/**
	 * 顶点顺序：HP / 攻 / 防 / **速** / 特防 / **特攻**（照 Cobblemon 的 12→10 点顺时针）。
	 * 名称走 Cobblemon 词条（客户端语言；中文「攻击/防御/速度/特防/特攻」、英文 "Attack"…）；
	 * 数值颜色 = 主模组 IV 行那套六色模板（按顶点顺序重排过：速度紫粉、特防青绿、特攻蓝）。
	 * 2026-11-05 用户报「中文模式下还是英文字母、六项没有颜色区分」后补上。
	 */
	private fun labelOf(index: Int): String = when (index) {
		0 -> StatNames.HP
		1 -> StatNames.ATK
		2 -> StatNames.DEF
		3 -> StatNames.SPEED
		4 -> StatNames.SP_DEF
		else -> StatNames.SP_ATK
	}

	private val VALUE_COLORS = intArrayOf(0x66FF66, 0xFF6666, 0xFFCC66, 0xFF99FF, 0x66FF99, 0x6699FF)

	/**
	 * 从挂单数据取**服务端算好的** 6 项能力值（顺序 = 六边形顶点）。
	 * 没有数据（旧服务端 / NBT 损坏，全 -1）返回 null。
	 *
	 * ⚠⚠ **绝不在这里自己按 IV/EV/等级算** —— 服务器装了 Fusionmon 时，
	 *   融合精灵的种族值是「头部/身体混合」的，而且 `StatProvider` 被替换成了
	 *   `FusionStatProvider`；客户端拿不到融合种族值 ⇒ 自己算会**显示头部原种的值**。
	 *   正确值只能由服务端 `pokemon.getStat(...)` 算好、随挂单字段下发。
	 */
	fun of(entry: ListingEntry): IntArray? {
		if (entry.statsHp < 0) return null
		return intArrayOf(
			entry.statsHp, entry.statsAtk, entry.statsDef,
			entry.statsSpe, entry.statsSpDef, entry.statsSpAtk,
		)
	}

	/**
	 * 在 [centerX] 居中、从 [topY]（占用框顶部）开始画六边形；返回占用高度。
	 * 调用方负责开关判断、可用高度（→ [scale]）与左移量（[leftInset]）。
	 */
	fun draw(
		context: DrawContext,
		font: TextRenderer,
		centerX: Int,
		topY: Int,
		stats: IntArray,
		scale: Float,
	): Int {
		val height = heightFor(scale)
		// 底图在占用框里**水平居中、垂直居中**（上下左右的空间留给外侧标签）
		val chartW = (TEX_W * scale).toInt()
		val chartH = (TEX_H * scale).toInt()
		val x = centerX - chartW / 2
		val chartY = topY + (height - chartH) / 2
		context.matrices.push()
		context.matrices.translate(x.toDouble(), chartY.toDouble(), 0.0)
		context.matrices.scale(scale, scale, 1f)
		context.drawTexture(CHART_TEX, 0, 0, 0f, 0f, TEX_W, TEX_H, TEX_W, TEX_H)
		context.matrices.pop()

		// 填充多边形：顶点 = 12 点开始顺时针，比例为 value / 400（照 Cobblemon）。
		// ⚠ 下限同样照 Cobblemon：`coerceIn(5 / radius, 1F)`（radius = 48 ⇒ 约 0.104）。
		//   低等级精灵的能力值 ÷400 只有 2%~5%，没有这个下限时蓝色区域会缩成一个点。
		// 六边形中心 = 底图中心（底图水平居中、垂直居中 ⇒ 中心就是框的中心）
		val cx = centerX.toFloat()
		val cy = topY + height / 2f
		val r = HEX_R * scale
		val ratios = stats.map { (it / 400f).coerceIn(MIN_RATIO, 1f) }
		val vertices = ratios.indices.map { i ->
			val angle = Math.toRadians(-90.0 + i * 60.0)
			Pair(
				cx + r * cos(angle).toFloat() * ratios[i],
				cy + r * sin(angle).toFloat() * ratios[i],
			)
		}
		val fill = 0x99_00D7FF.toInt()
		// ⚠ 自绘三角形走 POSITION_COLOR 着色器（1.21.1 没有 ShaderProgramKeys，
		//   用 GameRenderer.getPositionColorProgram）；不设的话会拿上一个 texture 着色器，渲染异常
		RenderSystem.setShader(GameRenderer::getPositionColorProgram)
		RenderSystem.enableBlend()
		for (i in vertices.indices) {
			val next = vertices[(i + 1) % vertices.size]
			triangle(context, fill, cx, cy, next.first, next.second, vertices[i].first, vertices[i].second)
		}
		RenderSystem.disableBlend()

		// 6 个角落的标签（名称 + 数值），两行；**名称与数值同色**（与左侧 IV 行一致，
		// 2026-11-05 用户报「这几个文字为什么不用对应的颜色」）。名称走词条、随客户端语言。
		// **对齐方式按方位区分**：
		// - 顶部（HP）/ 底部（Speed）：水平居中（位于顶点正上/正下，宽度不向六边形方向探）
		// - 右侧（Attack/Defense）：**左对齐**贴到六边形外侧 —— 宽标签向右延伸，不压边
		// - 左侧（SpAtk/SpDef）：**右对齐**贴到六边形外侧 —— 向左延伸
		// ⚠ 2026-11-05 英文模式下居中会让 `Defense`（42px）向六边形探进 15px 压到角上
		ratios.indices.forEach { i ->
			val angle = Math.toRadians(-90.0 + i * 60.0)
			val dx = cos(angle).toFloat()
			val dy = sin(angle).toFloat()
			val label = labelOf(i)
			val labelW = font.getWidth(label)
			val valueText = stats[i].toString()
			val valueW = font.getWidth(valueText)
			val totalW = maxOf(labelW, valueW)
			// 标签块两行（名称 ly-10、数值 ly-1）—— **原字号**，间距不缩放；
			// 垂直外推到 r+12 保证不压顶点
			val ly = (cy + (r + LABEL_GAP_V) * dy).toInt()
			val left: Int
			if (i == 0 || i == 3) {
				// 顶 / 底：居中
				left = (cx - totalW / 2f).toInt()
			} else if (dx >= 0f) {
				// 右侧：左缘贴六边形外
				left = (cx + (r + LABEL_GAP_H) * dx).toInt()
			} else {
				// 左侧：右缘贴六边形外
				left = (cx + (r + LABEL_GAP_H) * dx).toInt() - totalW
			}
			context.drawTextWithShadow(font, label, left + (totalW - labelW) / 2, ly - 10, VALUE_COLORS[i])
			context.drawTextWithShadow(font, valueText, left + (totalW - valueW) / 2, ly - 1, VALUE_COLORS[i])
		}
		return height
	}

	/** 自绘三角形（照 Cobblemon `StatWidget.drawTriangle`：Tesselator + POSITION_COLOR） */
	private fun triangle(
		context: DrawContext,
		color: Int,
		x1: Float, y1: Float,
		x2: Float, y2: Float,
		x3: Float, y3: Float,
	) {
		val a = ((color shr 24) and 0xFF) / 255f
		val r = ((color shr 16) and 0xFF) / 255f
		val g = ((color shr 8) and 0xFF) / 255f
		val b = (color and 0xFF) / 255f
		val matrix = context.matrices.peek().positionMatrix
		val builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR)
		fun v(x: Float, y: Float) {
			builder.vertex(matrix, x, y, 0f).color(r, g, b, a)
		}
		v(x1, y1)
		v(x2, y2)
		v(x3, y3)
		BufferRenderer.drawWithGlobalProgram(builder.end())
	}
}
