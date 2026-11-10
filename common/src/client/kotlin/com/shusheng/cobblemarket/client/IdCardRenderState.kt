package com.shusheng.cobblemarket.client

/**
 * ID 卡 3D 模型的**渲染期状态**（2026-11-10）。
 *
 * 原版 `EntityRenderer.render` 画完实体会顺带调用 `renderLabelIfPresent`（名字标签）——
 * 展示者在附近时 ID 卡上会出现他的名字 / 称号模组的称号；名字长会顶出卡片，
 * 字号又被模型矩阵放大得很大（2026-11-10 用户实测）。
 *
 * 画 ID 卡模型期间把它置 true，`PlayerEntityRendererMixin` 看到就取消标签渲染。
 * ⚠ 只在模型渲染的 try/finally 里短暂置位，**绝不影响世界里的正常名字**。
 */
object IdCardRenderState {

    /** true = 正在画 ID 卡模型，屏蔽玩家名字 / 称号标签 */
    @JvmField
    var suppressNameTags = false
}
