package com.tkisor.nekoldlib.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.tkisor.nekoldlib.NekoLDLib;
import graal.graalvm.polyglot.Value;

/**
 * 开发期自检：在 Java 侧合成一个 UI 事件并派发，用来验证
 * "事件回调能收到原生 {@code UIEvent}"这条链路。
 *
 * <h2>为什么放在 Java 侧</h2>
 *
 * 最初我试着在脚本里构造事件（{@code UIEvent.create(...)} / {@code UIEvent.$create(...)}），
 * 两次都猜错了 NekoJS 暴露 Java 静态成员的语法。而这条链路真正要验证的是
 * "Java 侧的 {@code fn.executeVoid(event)} 能让脚本拿到事件对象"——
 * 事件的构造发生在哪一侧无关紧要，放在 Java 侧反而排除了对脚本语法的猜测。
 */
final class EventProbe {

    private EventProbe() {
    }

    /**
     * 给一个元素挂上监听器，同步派发一次事件，返回回调是否收到。
     *
     * @param element 目标元素
     * @param name    事件名（同 {@code UIEvents}）
     * @return 回调收到的描述；未收到时返回失败说明
     */
    static String probe(UIElement element, String name) {
        var holder = new Object[]{null};
        element.addEventListener(name, event -> holder[0] = event);

        var event = UIEvent.create(name);
        event.target = element;
        UIEventDispatcher.dispatchEvent(event);

        var received = holder[0];
        if (received == null) {
            return "FAIL 回调未收到事件对象（监听器可能没被调用）";
        }
        return "PASS 回调收到 " + received.getClass().getSimpleName();
    }

    /**
     * 从脚本侧触发的入口：接收一个 vnode，渲染成元素，再合成事件。
     *
     * @param tree 脚本构造的 vnode
     * @return 结果描述
     */
    static String probeFromScript(Value tree) {
        try {
            var element = NekoLDLib.jsx().render(tree);
            return probe(element, UIEvents.CLICK);
        } catch (Exception e) {
            return "FAIL 渲染或派发失败: " + e;
        }
    }
}
