package com.anningui.nekoldlib.jsx;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import graal.graalvm.polyglot.Value;

/**
 * JSX 渲染入口。
 *
 * <p><b>不接管 {@code nekojs/jsx-runtime}</b>：NekoJS 内置运行时产出的
 * {@code { $$nekoJsx, type, key, props, children }} 普通对象就是官方预留的接入点
 * （其源码注释写明"渲染/消费由用户侧代码自行处理"）。本 mod 只做消费端——把这种
 * vnode 转成 LDLib2 元素树，因此脚本侧无需任何额外约定。
 */
public final class JsxHostBinding {

    private final ElementRegistry registry;
    private final ElementRenderer renderer;

    public JsxHostBinding(ElementRegistry registry) {
        this.registry = registry;
        this.renderer = new ElementRenderer(registry);
    }

    public ElementRegistry registry() {
        return registry;
    }

    /**
     * 渲染 vnode 为 LDLib2 元素树（默认客户端本地绑定模式）。
     *
     * <p>服务端菜单请显式传 {@code BindMode.SERVER_MENU}，否则绑定不会跨端同步。
     */
    public UIElement render(Value vnode) {
        return render(vnode, com.anningui.nekoldlib.signal.BindMode.CLIENT_LOCAL);
    }

    /**
     * 渲染 vnode 为 LDLib2 元素树。
     *
     * @param mode 绑定模式。客户端界面用 {@code CLIENT_LOCAL}；服务端菜单用
     *             {@code SERVER_MENU}。两种模式的"权威值"不同，必须由调用方显式指定。
     * @throws IllegalArgumentException vnode 为空，或标签名未注册
     */
    public UIElement render(Value vnode, com.anningui.nekoldlib.signal.BindMode mode) {
        var element = renderer.render(vnode, GraalJsxHost.INSTANCE, mode);
        if (element == null) {
            throw new IllegalArgumentException(
                    "渲染结果为空。常见原因：vnode 是 Fragment、或子节点全是裸文本/表达式。");
        }
        return element;
    }

    /** 渲染并包成 {@link UI}，供 {@code ModularUI.of} / {@code ModularUIContainerMenu} 使用。 */
    public UI renderToUI(Value vnode, com.anningui.nekoldlib.signal.BindMode mode) {
        return UI.of(render(vnode, mode));
    }
}
