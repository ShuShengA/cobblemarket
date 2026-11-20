package com.shusheng.cobblemarket.peek;

/**
 * tab 列表伪装的线程局部开关。
 *
 * ⚠ 绝不能放在 `com.shusheng.cobblemarket.mixin` 包 —— 那是 mixin 配置声明的专属包，
 *   Mixin 会拒绝直接引用包内的普通类
 *   （`IllegalClassLoadError: ... is in a defined mixin package ... cannot be referenced directly`）。
 *
 * - {@link #isBypassed()}：构造「给监视者自己的真实模式包」时置位，让
 *   `PlayerListS2CPacketMixin` 跳过伪装
 * - {@link #isCorrecting()}：补发真实模式包时置位，让
 *   `ClientConnectionMixin` 不再递归补发
 *
 * 包构造 / 发送都在服务端线程完成，但仍用 ThreadLocal —— 避免未来某条路径
 * 从网络线程发包时串状态。
 */
public final class PeekPacketGuard {

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> CORRECTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PeekPacketGuard() {
    }

    public static boolean isBypassed() {
        return BYPASS.get();
    }

    public static void setBypassed(boolean value) {
        BYPASS.set(value);
    }

    public static boolean isCorrecting() {
        return CORRECTING.get();
    }

    public static void setCorrecting(boolean value) {
        CORRECTING.set(value);
    }
}
