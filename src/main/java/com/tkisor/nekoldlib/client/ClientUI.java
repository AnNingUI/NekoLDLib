package com.tkisor.nekoldlib.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.tkisor.nekojs.api.ScriptType;
import com.tkisor.nekojs.api.data.BindingRegistry;
import com.tkisor.nekoldlib.NekoLDLib;
import graal.graalvm.polyglot.Value;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 脚本侧的客户端 UI 入口（{@code UI} 全局）。
 *
 * <p>阶段 1 只做纯客户端界面：JSX → 元素树 → {@link ModularUIScreen}。
 * 服务端开 UI 与 RPC 在阶段 3（走 {@code PlayerUI}）。
 *
 * <h2>为什么要等客户端就绪</h2>
 *
 * NekoJS 会在 mod 加载期就求值一次脚本（在 modloading worker 线程上），此时
 * {@code Minecraft.getInstance()} 还是 {@code null}。而整条开窗链路
 * （构造 Screen、ModularUI、Taffy 布局）都依赖客户端单例——不只是 {@code setScreen}。
 *
 * <p>所以这里不做"先建好再派发"，而是把<b>整个操作</b>交给 {@link ClientTicker}
 * 推迟到客户端线程。实测踩到过：只在最后一步 {@code mc.execute(...)} 切线程，
 * 会在加载期抛 {@code NPE: ... because Minecraft.getInstance() is null}。
 */
public final class ClientUI {

    private ClientUI() {
    }

    /**
     * 注册 {@code UI} 全局绑定。
     *
     * <p>只给客户端脚本环境：服务端没有 Screen 概念。TEST 也一并注册——测试脚本
     * （{@code /nekojs test} 触发）本就用于诊断，需要能驱动界面。
     */
    public static void install(BindingRegistry registry) {
        var ui = new ClientUI();
        registry.register(ScriptType.CLIENT, "UI", ui);
        registry.register(ScriptType.TEST, "UI", ui);
    }

    /**
     * 打开一个界面。
     *
     * <pre>{@code
     * UI.open(<panel class="panel_bg"> ... </panel>)
     * UI.open(<panel/>, "My Panel")
     * }</pre>
     *
     * @param tree  JSX 树（vnode）
     * @param title 窗口标题；省略时用空标题
     */
    public void open(com.tkisor.nekoldlib.jsx.VNode tree, String title) {
        // 先在当前线程把 vnode 渲染成元素树——这是纯数据操作，任何线程都安全。
        // 这样 JSX 里的错误能在调用点直接抛出，而不是被推迟到某个 tick 里静默失败。
        var element = render(tree);
        var generation = ScriptElements.generation();

        runOnClient("open", () -> {
            // 排队期间脚本可能已经重载过了。此时 element 上的监听器指向已关闭的
            // Context，再把它显示出来等于埋一个"点按钮就炸"的雷，直接丢弃更安全。
            if (generation != ScriptElements.generation()) {
                NekoLDLib.LOGGER.info(
                        "[NekoLDLib] 丢弃过期界面（脚本在排队期间重载了：代次 {} -> {}）",
                        generation, ScriptElements.generation());
                return;
            }
            var mui = ModularUI.of(UI.of(element));
            var screen = new ModularUIScreen(mui, title == null || title.isBlank()
                    ? Component.empty()
                    : Component.literal(title));
            Minecraft.getInstance().setScreen(screen);
        });
    }

    /** 省略标题的重载。 */
    public void open(com.tkisor.nekoldlib.jsx.VNode tree) {
        open(tree, null);
    }

    /**
     * 只渲染成元素、不开窗 —— 便于把 UI 挂到别处，或先构建再决定何时显示。
     */
    public UIElement render(com.tkisor.nekoldlib.jsx.VNode tree) {
        // 纯客户端界面 -> 本地绑定（无 player，LDLib2 的同步链路是惰性的）
        var element = NekoLDLib.jsx().render(tree.raw(), com.tkisor.nekoldlib.signal.BindMode.CLIENT_LOCAL);
        // 登记以便脚本重载时能找到并清理，避免残留监听器持有已关闭的 Context
        ScriptElements.track(element);
        return element;
    }

    /**
     * 关闭当前界面。
     *
     * <p>用 {@code setScreen(null)} 而不是 {@code onClose()}，因为脚本可能在没有
     * 活跃 Screen 的情况下调用它。
     */
    public void close() {
        runOnClient("close", () -> Minecraft.getInstance().setScreen(null));
    }

    /**
     * 关闭本 mod 打开的界面（若有）。
     *
     * <p>只关 {@link ModularUIScreen}，不动玩家自己开的其它界面。供脚本重载时清理
     * 上一代残留界面使用。
     */
    static void closeAllOwned() {
        runOnClient("closeAllOwned", () -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof ModularUIScreen) {
                mc.setScreen(null);
            }
        });
    }

    /**
     * 当前是否有本 mod 打开的界面。客户端未就绪时返回 {@code false}。
     *
     * <p>名字刻意<b>不</b>用 {@code isOpen}：probe 按 JavaBean 约定把 {@code isXxx()} 渲染成
     * {@code get xxx} 属性，于是生成的声明里会出现 {@code get open(): boolean;}，
     * <b>遮蔽同一个类上的 {@code open()} 方法</b>——使用者在 .tsx 里写 {@code UI.open(...)}
     * 会报"类型 Boolean 没有调用签名"。运行时不受影响，只在编辑器里爆发，很难查。
     * 换个不以 {@code is} 开头的名字就绕开了这个坑。
     */
    public boolean hasOpenScreen() {
        var mc = Minecraft.getInstance();
        return mc != null && mc.screen instanceof ModularUIScreen;
    }

    /** 可用的 JSX 标签名清单，便于脚本侧自省与报错排查。 */
    public String tags() {
        return String.join(", ", NekoLDLib.elements().knownTags());
    }

    /**
     * 开发期自检：渲染一棵树并合成一次 click 事件，返回回调是否收到事件对象。
     *
     * <p>事件的构造放在 Java 侧，避免依赖脚本侧对 Java 静态成员的调用语法。
     *
     * @param tree 要渲染的 vnode
     */
    public String probeEvent(com.tkisor.nekoldlib.jsx.VNode tree) {
        return EventProbe.probeFromScript(tree.raw());
    }

    // ------------------------------------------------------------------
    // 客户端线程调度
    // ------------------------------------------------------------------

    /** 委托给 {@link ClientTicker}：一次性任务 + 每 tick 任务都集中在那里。 */
    private static void runOnClient(String what, Runnable task) {
        ClientTicker.once(what, task);
    }
}
