package com.tkisor.nekoldlib.jsx;

import com.tkisor.nekojs.api.annotation.HideFromJS;
import graal.graalvm.polyglot.Value;

/**
 * 一棵 JSX vnode。
 *
 * <h2>为什么需要这个包装</h2>
 *
 * vnode 由 NekoJS 的 JSX 运行时在<b>JS 侧</b>创建，是普通 JS 对象。Java 方法若直接收
 * {@code Value}，生成的类型声明就是 {@code $Value}——使用者看不出该传什么。
 * 有本类型后，配合 {@link VNodeAdapter}，签名可以写成收 {@link VNode}，
 * 声明里就是具体类型且可跳转。
 *
 * <p>本类只做"类型化的持有"，不做解析：真正的形状识别与建树仍在
 * {@link ElementRenderer} 里（它要同时兼容 classic 与 automatic 两种 vnode）。
 */
public final class VNode {

    private final Value raw;

    /** 由 {@link VNodeAdapter} 构造。 */
    public VNode(Value raw) {
        this.raw = raw;
    }

    /**
     * 原始 Graal 值，供渲染器读取。
     *
     * <p>{@code @HideFromJS}：这是 Java 侧的内部通道，暴露给脚本只会让声明里
     * 又出现 {@code $Value}，正好抵消本类型存在的意义。
     */
    @HideFromJS
    public Value raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "VNode(" + raw + ")";
    }
}
