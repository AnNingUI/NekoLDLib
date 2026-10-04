package com.tkisor.nekoldlib.jsx;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.tkisor.nekoldlib.NekoLDLib;
import com.tkisor.nekoldlib.signal.BindMode;
import graal.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * vnode → {@link UIElement} 树。
 *
 * <h2>跨端语义（重要）</h2>
 *
 * LDLib2 的 {@code ModularUIContainerMenu} 在<b>服务端也会完整构建 UI 树</b>
 * （{@code this.modularUI = uiHolder.createUI(inventory.player)}），所以同一份 JSX
 * 会在两端各求值一次。本渲染器的行为因此必须两端一致：
 *
 * <ul>
 *   <li><b>结构</b>（元素、id、class、children、事件监听）—— 两端都建。这些是纯数据，
 *       服务端安全。</li>
 *   <li><b>样式与布局</b> —— 走 {@link UIElement#lss(String, Object)}，它<b>没有</b>
 *       {@code isServer()} 早退，两端都会写入 StyleBag。注意 {@code UIElement.layout()}
 *       和 {@code style()} 在服务端是 no-op，所以本渲染器不使用它们。</li>
 * </ul>
 */
public final class ElementRenderer {

    /** 组件展开的最大嵌套深度，防止组件直接返回自己导致栈溢出。 */
    private static final int MAX_DEPTH = 64;

    /**
     * 样式/布局属性名清单，供类型声明生成。
     *
     * <p>逐条抄自 LDLib2 {@code LayoutProperties} 里 {@code PropertyRegistry} 的
     * 实际注册名（kebab-case）。运行时对未知属性是<b>静默忽略</b>的，所以这份清单
     * 对使用者尤其重要——属性名写错不会有任何提示，只是没效果。
     */
    public static final java.util.List<String> STYLE_PROPERTIES = java.util.List.of(
            // 尺寸
            "width", "height", "min-width", "min-height", "max-width", "max-height",
            // 定位
            "position", "left", "top", "right", "bottom",
            // flex
            "display", "flex-basis", "flex-grow", "flex-shrink",
            "flex-direction", "flex-wrap", "align-items", "align-self", "align-content",
            "justify-content", "justify-items", "justify-self", "layout-direction",
            // 间距
            "gap", "gap-all", "gap-row", "gap-column",
            "padding", "padding-all", "padding-left", "padding-top", "padding-right",
            "padding-bottom", "padding-horizontal", "padding-vertical",
            "margin", "margin-all", "margin-left", "margin-top", "margin-right",
            "margin-bottom", "margin-horizontal", "margin-vertical",
            // grid
            "grid-template-rows", "grid-template-columns", "grid-template-areas",
            "grid-auto-rows", "grid-auto-columns", "grid-auto-flow", "grid-row", "grid-column");

    /**
     * 事件名清单，取自 LDLib2 {@code UIEvents} 的常量值。
     *
     * <p>脚本侧写 {@code on-<名字>}。
     */
    public static final java.util.List<String> EVENT_NAMES = java.util.List.of(
            "mouseDown", "mouseUp", "mouseClick", "doubleClick", "mouseMove",
            "mouseEnter", "mouseLeave", "mouseWheel",
            "dragEnter", "dragLeave", "dragUpdate", "dragSourceUpdate", "dragPerform", "dragEnd",
            "focus", "blur", "focusIn", "focusOut",
            "keyDown", "keyUp", "charTyped",
            "hoverTooltips", "validateCommand", "executeCommand",
            "layoutChanged", "fileDrop", "styleChanged", "removed", "added", "muiChanged");

    /**
     * 事件别名：写 {@code on-click} 比 {@code on-mouseClick} 自然。
     *
     * <p>只放显示层别名，其余原样透传——不维护会过期的全量映射表。
     */
    public static final java.util.List<String> EVENT_ALIASES = java.util.List.of("click");

    private final ElementRegistry registry;

    public ElementRenderer(ElementRegistry registry) {
        this.registry = registry;
    }

    /**
     * 渲染期的共享上下文。
     *
     * <p>把 host、绑定模式与 RPC 收集器打包传递，避免给每个递归方法多加一个参数——
     * 渲染器方法较多，逐个加参会让签名噪音盖过逻辑。
     *
     * <p>公开是因为 {@code ElementBinder} 在另一个包里要用它做绑定分派。
     *
     * @param rpc 本次渲染期间脚本声明的 RPC。渲染完成后由调用方挂到根元素上——
     *            {@code UIElement.addRPCEvent} 会在元素挂上 ModularUI 时自动注册到
     *            syncManager（与 syncValues 同一套延迟注册机制，见 UIElement:228）。
     */
    public record Ctx(JsxHost host, BindMode bindMode,
                      com.tkisor.nekoldlib.signal.RpcCollector rpc) {
    }

    /**
     * 渲染一个 vnode 为元素树。
     *
     * @param node  JSX 运行时产出的 vnode（{@code { type, props, key, children }}）
     * @param host  宿主句柄，用于泛化地读取 JS 属性（见 {@link JsxHost}）
     */
    @Nullable
    public UIElement render(Value node, JsxHost host, com.tkisor.nekoldlib.signal.BindMode mode) {
        // 抽取本线程待挂载的 RPC 声明。
        //
        // 注意是"抽取"而不是"设置渲染期上下文"：声明发生得比渲染早——
        // UI.open(<Panel/>) 里 JSX 参数先求值，PlayerUIRegistry 也是先调工厂再渲染。
        // 所以 RPC.define 只是入队，由这里在渲染时取走并挂到根元素。
        var rpc = com.tkisor.nekoldlib.signal.RpcCollector.drainForRender();

        var root = render(node, new Ctx(host, mode, rpc), 0);
        if (root != null) {
            // addRPCEvent 会先存起来，等元素挂上 ModularUI 时自动注册到 syncManager
            rpc.attachTo(root);
        }
        return root;
    }

    @Nullable
    private UIElement render(Value node, Ctx ctx, int depth) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "组件嵌套超过 " + MAX_DEPTH + " 层。常见原因是组件直接返回自己（无限递归）。");
        }

        var root = createElement(node, ctx, depth);
        if (root == null) {
            // 根节点没有产出元素 —— 通常是 <>...</> 这种 Fragment 根。
            // 用一个裸容器承接它的子节点，否则这种写法无法作为根，用户会被迫多写一层。
            var container = new UIElement();
            appendChildren(container, node, ctx, depth);
            return container;
        }
        appendChildren(root, node, ctx, depth);
        return root;
    }

    // ------------------------------------------------------------------
    // 元素创建
    // ------------------------------------------------------------------

    @Nullable
    private UIElement createElement(Value node, Ctx ctx, int depth) {
        var type = ctx.host.typeOf(node);

        // Fragment：不产生元素，只展开子节点
        if (ctx.host.isFragment(type)) {
            return null;
        }

        // 函数组件：调用它拿到真正要渲染的 vnode，再递归。
        // JSX 里 <MyPanel a={1}/> 与 MyPanel({a: 1}) 是等价的。
        if (type != null && type.canExecute()) {
            var produced = ctx.host.call(type, ctx.host.propsOf(node));
            if (produced == null || produced.isNull()) {
                return null;
            }
            return render(produced, ctx, depth + 1);
        }

        var tag = type == null ? null : type.asString();
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("JSX 元素缺少有效的标签（type 或 tag）");
        }

        var element = registry.create(tag.trim().toLowerCase(Locale.ROOT));
        if (element == null) {
            throw new IllegalArgumentException(
                    "未知的 JSX 标签 <" + tag + ">。已注册：" + registry.knownTags());
        }

        applyProps(element, ctx.host.propsOf(node), ctx);
        return element;
    }

    private void applyProps(UIElement element, @Nullable Value props, Ctx ctx) {
        if (props == null || props.isNull()) {
            return;
        }

        for (var key : ctx.host.keys(props)) {
            var value = ctx.host.member(props, key);
            if (value == null || value.isNull()) {
                continue;
            }

            switch (key) {
                case "class", "className" -> applyClasses(element, value, ctx);
                case "id" -> element.setId(value.asString());
                case "style", "layout" -> applyStyleObject(element, value, ctx);
                // Slider 的 min/max 不是 LSS 属性，必须走 setRange——否则会被当作
                // 无效 LSS 名静默忽略，滑块停在默认的 0~1 区间。
                case "min", "max" -> applySliderRange(element, key, value);
                // 数据绑定。走哪条路径由渲染上下文决定：
                //   CLIENT_LOCAL —— 纯客户端界面，本地绑定（每 tick 搬运）
                //   SERVER_MENU  —— 服务端菜单，LDLib2 SimpleBinding（跨端同步）
                // 二选一是必须的：两条路径的"权威值"不同，同时接会互相覆盖。
                case "bind" -> com.tkisor.nekoldlib.signal.ElementBinder.bind(element, value, ctx);
                default -> applyOtherProp(element, key, value, ctx);
            }
        }

        // text / value 在结构属性之后应用，因为部分控件（Button/Label）的文本
        // 依赖于元素已建好。
        applyContentProps(element, props, ctx);
    }

    private void applyClasses(UIElement element, Value value, Ctx ctx) {
        for (var clazz : ctx.host.stringList(value)) {
            if (!clazz.isBlank()) {
                element.addClass(clazz.trim());
            }
        }
    }

    /**
     * 应用 {@code style}/{@code layout} 对象。
     *
     * <p>走 {@code lss(属性名, 字符串值)} —— 它以 {@code PropertyRegistry} 的属性名
     * （kebab-case，与 CSS 一致）接收字符串，因此 JS 侧的心智模型和写 LSS 一样。
     */
    private void applyStyleObject(UIElement element, Value styleObject, Ctx ctx) {
        if (!styleObject.hasMembers()) {
            // 允许 style="width: 100; height: 20"
            for (var decl : ctx.host.stringList(styleObject)) {
                applyStyleDeclaration(element, decl);
            }
            return;
        }
        for (var prop : ctx.host.keys(styleObject)) {
            var raw = ctx.host.member(styleObject, prop);
            if (raw == null || raw.isNull()) {
                continue;
            }
            applyStyleDeclaration(element, prop, JsxHost.numericToLss(raw));
        }
    }

    private void applyStyleDeclaration(UIElement element, String declaration) {
        var idx = declaration.indexOf(':');
        if (idx <= 0) {
            return;
        }
        applyStyleDeclaration(element, declaration.substring(0, idx), declaration.substring(idx + 1));
    }

    private void applyStyleDeclaration(UIElement element, String prop, String rawValue) {
        var name = camelToKebab(prop.trim());
        if (name.isEmpty()) {
            return;
        }
        element.lss(name, rawValue.trim());
    }

    private void applyContentProps(UIElement element, Value props, Ctx ctx) {
        var text = ctx.host.member(props, "text");
        if (text != null && !text.isNull()) {
            setText(element, text.asString());
        }
    }

    private void setText(UIElement element, String text) {
        // 注意 translate=false：TextElement.setText(String) 默认把字符串当翻译键
        // （Component.translatable），对脚本里写的中文/任意文本会渲染成原始 key。
        switch (element) {
            case Button button -> button.setText(text, false);
            case Label label -> label.setText(text, false);
            case TextElement textElement -> textElement.setText(text, false);
            default -> {
                // 其余控件没有文本概念，静默忽略比抛错更友好——用户可能在
                // 一个容器上误写 text，不该因此炸掉整个界面。
            }
        }
    }

    /**
     * Slider 的范围。
     *
     * <p>{@code setRange(min, max)} 要两个值一起给，而 JSX 里 min / max 是两个独立属性，
     * 谁先谁后不确定。所以先把读到的值记在元素上，两个都齐了再一次性设定。
     */
    private static final Map<UIElement, float[]> SLIDER_RANGES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private void applySliderRange(UIElement element, String key, Value value) {
        if (!(element instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.Slider slider)) {
            // 非 Slider 上写 min/max 当作普通 LSS 属性处理（例如自定义控件）
            if (isStyleLike(value)) {
                element.lss(camelToKebab(key), JsxHost.numericToLss(value));
            }
            return;
        }
        var range = SLIDER_RANGES.computeIfAbsent(element, e -> {
            // 用 Slider 自己的默认区间作为初值，只覆盖脚本真正写了的那个
            var s = (com.lowdragmc.lowdraglib2.gui.ui.elements.Slider) e;
            return new float[]{s.getMinValue(), s.getMaxValue()};
        });
        var number = (float) value.asDouble();
        if ("min".equals(key)) {
            range[0] = number;
        } else {
            range[1] = number;
        }
        slider.setRange(range[0], range[1]);
    }

    /**
     * 处理事件绑定（{@code on-*}）与其它未知属性。
     *
     * <p>事件写法是 {@code on-<事件名>}，事件名与 LDLib2 的 {@code UIEvents} 一一对应，
     * 例如 {@code on-mouseClick} / {@code on-mouseEnter} / {@code on-keyDown}。
     *
     * <p>之所以不用 {@code on.click} 这种点号写法：JSX 属性名里的点号不是合法标识符，
     * NekoJS 的脚本成员校验器会报 <em>Unknown identifier 'on'</em>，即便它能侥幸编译通过，
     * 也容易踩到校验失败。连字符是标准 JSX 属性名，无副作用。
     */
    private void applyOtherProp(UIElement element, String key, Value value, Ctx ctx) {
        if (key.startsWith("on-") || key.startsWith("on.")) {
            var eventName = key.substring(3);
            if (eventName.isEmpty() || !value.canExecute()) {
                return;
            }
            element.addEventListener(resolveEventName(eventName), toListener(value));
            return;
        }

        // 剩余属性一律当 LSS 处理：这样 <panel width={100} gap-all={4}> 这类
        // 简写也能工作，且不需要维护属性白名单。
        if (isStyleLike(value)) {
            element.lss(camelToKebab(key), JsxHost.numericToLss(value));
        }
    }

    /**
     * 把简写事件名归一成 LDLib2 的 {@code UIEvents} 常量值。
     *
     * <p>只做少数几个显示层别名（写 {@code on-click} 比 {@code on-mouseClick} 自然），
     * 其余原样透传——保持与 LDLib2 事件名一一对应，不维护会过期的全量映射表。
     */
    private static String resolveEventName(String name) {
        return switch (name) {
            case "click" -> UIEvents.CLICK;
            case "doubleClick" -> UIEvents.DOUBLE_CLICK;
            case "mouseDown" -> UIEvents.MOUSE_DOWN;
            case "mouseUp" -> UIEvents.MOUSE_UP;
            case "mouseEnter" -> UIEvents.MOUSE_ENTER;
            case "mouseLeave" -> UIEvents.MOUSE_LEAVE;
            case "mouseMove" -> UIEvents.MOUSE_MOVE;
            case "mouseWheel" -> UIEvents.MOUSE_WHEEL;
            case "keyDown" -> UIEvents.KEY_DOWN;
            case "keyUp" -> UIEvents.KEY_UP;
            case "charTyped" -> UIEvents.CHAR_TYPED;
            case "focus" -> UIEvents.FOCUS;
            case "blur" -> UIEvents.BLUR;
            default -> name;
        };
    }

    private boolean isStyleLike(Value value) {
        return value.isNumber() || value.isString() || value.isBoolean();
    }

    /**
     * 把 JS 函数包成 LDLib2 的监听器。
     *
     * <p>事件对象<b>直接传原生的 {@code UIEvent}</b>：Graal 会把 Java 对象自动包成
     * host object 交给脚本（NekoJS 自己也是这么做的——{@code EventBusJS} 里
     * {@code listener.executeVoid(event)} 传的就是原生事件）。所以脚本侧
     * {@code on-click={(e) => ...}} 里的 {@code e} 可以直接用事件的 Java getter
     * （{@code e.x} / {@code e.y} / {@code e.target} …），不需要额外桥接层。
     *
     * <p>反过来（JS 值 → Java）不自动，那侧必须显式转换——见
     * {@link com.tkisor.nekoldlib.signal.SignalTypes#toJava}。
     */
    private UIEventListener toListener(Value fn) {
        return event -> {
            try {
                fn.executeVoid(event);
            } catch (IllegalStateException e) {
                // Context 已关闭：脚本重载后残留的监听器。本 mod 会主动关掉旧界面，
                // 这里只是兜底，避免刷屏。
                NekoLDLib.LOGGER.debug("[NekoLDLib] 忽略已失效的事件回调（脚本已重载）");
            } catch (Exception e) {
                NekoLDLib.LOGGER.error("[NekoLDLib] UI 事件回调抛错", e);
            }
        };
    }

    // ------------------------------------------------------------------
    // 子节点
    // ------------------------------------------------------------------

    private void appendChildren(UIElement parent, Value node, Ctx ctx, int depth) {
        var children = ctx.host.childrenOf(node);
        if (children == null || children.isNull()) {
            return;
        }
        for (var child : ctx.host.iterable(children)) {
            appendChild(parent, child, ctx, depth);
        }
    }

    private void appendChild(UIElement parent, Value child, Ctx ctx, int depth) {
        if (child == null || child.isNull()) {
            return;
        }

        // 文本节点
        if (child.isString() || child.isNumber()) {
            // LDLib2 没有独立的文本节点，用 Label 承载裸文本
            var label = new Label();
            label.setText(child.asString(), false);
            parent.addChild(label);
            return;
        }

        if (child.isBoolean()) {
            // {cond && <x/>} 中 cond 为 false 时产出 false，跳过
            return;
        }

        if (ctx.host.isArray(child)) {
            for (var item : ctx.host.iterable(child)) {
                appendChild(parent, item, ctx, depth);
            }
            return;
        }

        if (!ctx.host.isElement(child)) {
            NekoLDLib.LOGGER.warn("[NekoLDLib] 跳过无法渲染的子节点：{}", child);
            return;
        }

        var childType = ctx.host.typeOf(child);

        // Fragment：自身不产生元素，把子节点提升到当前父级
        if (ctx.host.isFragment(childType)) {
            appendChildren(parent, child, ctx, depth);
            return;
        }

        // 函数组件：先展开，再把展开结果挂上
        if (childType != null && childType.canExecute()) {
            var produced = ctx.host.call(childType, ctx.host.propsOf(child));
            if (produced != null && !produced.isNull()) {
                appendChild(parent, produced, ctx, depth + 1);
            }
            return;
        }

        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("组件嵌套超过 " + MAX_DEPTH + " 层，疑似无限递归。");
        }

        var element = createElement(child, ctx, depth);
        if (element != null) {
            appendChildren(element, child, ctx, depth);
            parent.addChild(element);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** {@code paddingAll} / {@code padding-all} 都归一化为 {@code padding-all}。 */
    static String camelToKebab(String name) {
        if (name.indexOf('-') >= 0 && name.equals(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        var sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
