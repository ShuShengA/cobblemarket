package com.shusheng.cobblemarket.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen

/**
 * 本模组界面的尺寸档位 —— **方案一：只在我们自己的界面内缩放，不碰任何全局状态**。
 *
 * 三个量：
 * - **原版倍数 S** —— 原版「界面尺寸」（自动档在 2K 上会算到 5）⇒ `window.scaledWidth = 物理宽 ÷ S`
 * - **我们的档位 N** —— [ClientConfig.guiScaleMode]，**0 = 跟随原版 = 整段短路**
 * - **虚拟画布** —— 界面布局代码读到的 `width/height`（就是 `screen.width/height`），
 *   取 **max(物理宽 ÷ N, 原版逻辑宽)**：⚠ **有下限** —— 档位只把界面调得比原版**更大**、永不更小
 *   （原版倍率会随窗口自适应，我们的是死数；不加下限的话窗口一缩就比不装模组还挤，见 [widthFor]）
 *
 * ## 两条换算式，注意它们**不是**彼此的简单反演
 *
 * - **渲染 / 裁剪（虚拟 → 原版逻辑）**：[renderFactor] = **N ÷ S**（精确整数比）
 * - **输入（原版逻辑 → 虚拟）**：整数运算 `坐标 × screen.width ÷ 物理宽`（见 `MouseMixin`）
 *
 * ⭐ 为什么渲染侧非要精确比：净物理缩放 = S × (N ÷ S) = **N**（整数）⇒ 字形永远落在整数物理像素上、
 * **不糊**。若改用 `scaledWidth ÷ screen.width` 反算，因为 `screen.width` 是取整值
 * （2560÷3 = 853.33 → 853），系数会变成 0.60023 而**不是** 0.6 ⇒ 整屏文字产生最多
 * **半个物理像素**的亚像素偏移、边缘发虚（放大截图就能看出来）。
 *
 * ⚠ 代价：虚拟尺寸**向上取整**（854 而不是 853）⇒ 854 × 0.6 = 512.4 逻辑像素，比屏幕宽出
 * 0.4 逻辑像素（2 物理像素），**右/下边缘被裁掉**。这是有意的 —— 宁可裁掉 2 像素，
 * 也不要右边露一条 1 像素的底色缝。
 *
 * ⚠ 输入侧做不到精确（`MouseMixin` 的重定向只能返回 int）⇒ 与渲染侧有 <0.1% 的偏差，
 * 折算到屏幕上 **< 1 个虚拟像素**（最小按钮也有 20 虚拟像素宽）⇒ 点不中的可能性为零。
 *
 * ⭐ **自动退化**：没设档位（N = 0）或不是目标界面时 `screen.width` 保持 = `window.scaledWidth`
 * ⇒ 渲染系数 = 1、输入系数 = 1、裁剪系数 = 1 ⇒ **逐像素等价于「没有这个功能」**，
 * 不需要任何分支判断。因此本文件的函数**一律无条件调用**（包括 push/pop 矩阵），
 * 把"要不要缩放"的判定收在唯一的策略点 [scaleFor]（只由 `Screen.init` / `Screen.resize` 的注入点调用）。
 */
object GuiScale {
    /**
     * 参与缩放的界面（**分批铺开** —— 见 `docs/gui_scale.md` 第八节）。
     *
     * ⚠ 用**类名字符串**而不是 `screen is XxxScreen`：一是免掉十几个 import，二是加界面只动这一个集合。
     * ⚠ 最终（第 3 批）整体换成 `screen.javaClass.name.startsWith("com.shusheng.cobblemarket.screen.")`
     * —— **只改这一处**，35 屏一屏不用动。
     *
     * 分批理由：静态审计看不到"运行时才暴露"的坑（本功能 15 条坑里有 5 条是那么来的）⇒
     * 每批只放一类界面，出问题时范围小、好定位。
     */
    private val TARGETS = setOf(
        "MarketScreen",              // 步骤 2：首屏（列表 + 右侧面板 + 展开浮层 + 3D 图标 + scissor）
        // ── 第 1 批：纯列表 / 表单型（无弹窗、无自绘特效）──
        "AdminBanScreen",
        "AdminItemScreen",
        "DepositScreen",
        "LoanScreen",
        "LoanHistoryScreen",
        "HistoryScreen",
        "ItemReturnScreen",
        "PokemonReturnScreen",
        "ItemSellScreen",
        // ⚠ 特意**不**放 `BalanceHudPositionScreen`：它是**全屏 HUD 的位置编辑器** ——
        //   HUD 由 ScreenMixin 画在缩放矩阵**之外**（屏幕坐标系），而该界面的 posX/posY/width
        //   全在**虚拟坐标系** ⇒ 判定框与 HUD 各在一处，表现成"只有中心一小块能拖"（2026-10-04 实测）。
        //   ⇒ **凡是"编辑全屏元素位置/尺寸"的界面都别放进白名单**（它的坐标必须与那个全屏元素同系）。
    )

    fun isTarget(screen: Screen): Boolean = screen.javaClass.simpleName in TARGETS

    /**
     * 本界面该用的档位：**0 = 不缩放**（跟随原版 / 非目标界面 / 未选择）。
     * ⚠ 这是全流程**唯一**的策略判定点，只被 `Screen.init` / `Screen.resize` 的混入调用。
     */
    fun scaleFor(screen: Screen): Int =
        if (isTarget(screen)) ClientConfig.guiScaleMode.scale else 0

    /** 向上取整的整数除法：虚拟尺寸宁可多占一像素，也不能少（少了屏幕边缘会露底） */
    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /**
     * 界面应该拿到的宽度 = **max(物理宽 ÷ 档位, 原版逻辑宽)**；未设档位时原样返回 `realWidth`。
     * 由 `Screen.init` / `Screen.resize` 的注入点调用 —— 这是虚拟尺寸的**唯一**赋值处。
     *
     * ⚠ 用**物理宽**而不是 `scaledWidth × S ÷ N`：前者正是原版「档位」的定义，且没有取整误差
     * （`scaledWidth` 本身是 物理宽÷S 取整来的，乘回去会偏小几像素）。
     *
     * ⚠⭐ **那条下限（原版逻辑宽）是有意加的**（用户 2026-10-04 拍板）：原版**倍率会随窗口自适应**
     * （窗口一小，自动档就从 3 降到 2），而我们的档位是死数 ⇒ 不加下限的话「窗口一缩，我们的界面
     * 比没装模组时还挤」—— 玩家第一反应就是"这模组有 bug"。
     * 加了下限后语义变成：**档位只用来把界面调得比原版更大，永远不会比原版更小**。
     * 代价（有意）：原版倍率已经很小（小窗口）时档位显得没效果 —— 那时"更小的界面"本就没有意义。
     */
    fun widthFor(screen: Screen, realWidth: Int): Int {
        val n = scaleFor(screen)
        if (n <= 0) return realWidth
        return maxOf(ceilDiv(MinecraftClient.getInstance().window.width, n), realWidth)
    }

    /** 同 [widthFor]，取高度。⚠ 宽高**分别**除以档位（不要拿宽算比例反推高） */
    fun heightFor(screen: Screen, realHeight: Int): Int {
        val n = scaleFor(screen)
        if (n <= 0) return realHeight
        return maxOf(ceilDiv(MinecraftClient.getInstance().window.height, n), realHeight)
    }

    /**
     * 本界面的缩放系数（**虚拟坐标 → 原版逻辑坐标**）：未设档位 / 非目标界面时 = 1。
     *
     * ⚠ 渲染矩阵、裁剪换算、坐标换算**全都走它** —— 三处必须严格同源，否则内容与命中区会错开。
     *
     * ⚠⭐ **必须从"界面实际持有的宽"反算，不能用精确比 N÷S**（2026-10-04 用户实测踩到）：
     * [widthFor] 有**两个分支**（档位算出来的宽 / 被原版下限顶掉），只要渲染系数与它不同步一次
     * （例如界面按更大的宽居中排布、而系数没跟着缩小），就会**界面整体画到右边、而点击判定还在原处**
     * （症状：看着偏右、点按钮却要点原来那个位置）。
     * 反算保证恒等式 `screen.width × factor ≡ window.scaledWidth` 成立 ⇒ **任何分支下界面都恰好铺满**。
     * 代价：`screen.width` 是取整值 ⇒ 系数带 ~0.08% 误差、文字可能有半个物理像素的亚像素偏移 ——
     * 但"**不错位**"比那点清晰度重要得多。
     */
    fun factorFor(screen: Screen): Float {
        if (scaleFor(screen) <= 0) return 1f
        val scaledWidth = MinecraftClient.getInstance().window.scaledWidth
        if (screen.width <= 0 || scaledWidth <= 0) return 1f
        return scaledWidth.toFloat() / screen.width.toFloat()
    }

    /**
     * 包住本界面这一帧的绘制（由 `ScreenMixin` 在 `renderWithTooltip` 的 HEAD 调用，
     * 排在进场/关闭动画的 push **之后** ⇒ 缩放矩阵在动画矩阵**内层**）。
     *
     * ⚠ **无条件 push/pop 配对**：未缩放时 `scale(1,1,1)` 是无害的，而"条件 push"一旦与
     * TAIL 的判定不一致就会把矩阵栈搅乱（症状 = 整个界面错位）。
     */
    fun pushTransform(context: DrawContext, screen: Screen) {
        val factor = factorFor(screen)
        // 每次 push 都**重设**（而不是只在非 1 时置位）：万一上一帧渲染途中抛异常、pop 没跑到，
        // 下一帧的 push 会把标志纠正回来，不会让裁剪侧一直误判
        scalingActive = factor != 1f
        context.matrices.push()
        context.matrices.scale(factor, factor, 1f)
    }

    /**
     * 恢复（由 `ScreenMixin` 在 `renderWithTooltip` 的 TAIL **最先**调用）。
     *
     * 排在最前是因为 TAIL 里剩下的东西（庆祝动画 / 余额 HUD / 自绘光标 / 聊天大图）都是
     * **全屏层**，本就该落在缩放之外 —— 尤其**光标绝不该跟着界面一起被缩放**。
     */
    fun popTransform(context: DrawContext) {
        scalingActive = false
        context.matrices.pop()
    }

    /**
     * 当前是否正处在「本模组界面的缩放渲染」之中（一次 `renderWithTooltip` 之内）。
     *
     * ⚠ 给**裁剪侧**用：`scissor` 走的是屏幕像素坐标、不经过 `matrices`，所以界面在虚拟坐标系里
     * 调的 `enableScissor` 必须换算；而**同一帧里游戏 HUD 也会渲染**（`currentScreen` 此时正是
     * 我们的界面）⇒ 只用"当前界面被虚拟化过"判断会**误伤 HUD 的裁剪**。用这个标志把范围
     * 精确收在"我们界面的这一帧绘制"之内。
     */
    @Volatile
    var scalingActive: Boolean = false
        private set

    /**
     * 界面收到的（**虚拟**）坐标 → 原版逻辑坐标，供**全屏层**使用（自绘光标 / 聊天大图）
     * 以及**裁剪**（`scissor` 走屏幕坐标）。
     *
     * ⚠ 必须做：`renderWithTooltip` 的 mouseX/mouseY 被我们换成了虚拟坐标，而光标画在
     * 缩放矩阵**之外** ⇒ 不换算就会"界面变大了、指针还按老位置走"。
     */
    fun toReal(virtualValue: Int, screen: Screen): Int {
        val factor = factorFor(screen)
        return if (factor == 1f) virtualValue else Math.round(virtualValue * factor)
    }

    /**
     * 原版逻辑坐标 → **虚拟**坐标（`renderWithTooltip` 的 mouseX/mouseY 走这里）。
     * 与 [toReal] **严格互逆**（一个 × factor、一个 ÷ factor）；未缩放时原样返回。
     *
     * ⚠ 这一条务必与 `MouseMixin` 的正向换算（`坐标 × screen.width ÷ 物理宽`）**等价** ——
     * 两者落在同一个虚拟坐标系里，界面自己的悬停判断才不会和控件点击错开。
     */
    fun toVirtual(realValue: Int, screen: Screen): Int {
        val factor = factorFor(screen)
        return if (factor <= 0f || factor == 1f) realValue else Math.round(realValue / factor)
    }

}
