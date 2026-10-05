package com.anningui.nekoldlib.jsx;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Slider;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JSX 标签名 → LDLib2 控件。
 *
 * <p>标签名统一小写、用连字符（{@code <text-field>}），因为 JSX 标签里的连字符是
 * HTML 习惯，而 LDLib2 的类名是 {@code TextField}。渲染器在查表前会
 * {@code toLowerCase()}，所以 {@code <TextField>} 与 {@code <text-field>} 都能命中。
 *
 * <p>这是一个<b>开放注册表</b>：集成方可以继续加标签，不需要改渲染器。
 */
public final class ElementRegistry {

    private final Map<String, Supplier<UIElement>> factories = new LinkedHashMap<>();

    /**
     * 标签 → 可绑定值的类型名（{@code SignalTypes.byName} 认的名字）。
     *
     * <p>只登记<b>可绑定</b>的控件：{@code bind} 的类型推导靠它生成。
     * 未登记的标签（容器、按钮等）不支持绑定，生成声明时 {@code bind} 会是
     * {@code never}，这样编辑器能直接报"这个标签不能绑定"。
     */
    private final Map<String, String> bindTypes = new LinkedHashMap<>();

    /** 值类型被泛型擦除的标签（需脚本显式写 {@code type}）。 */
    private final java.util.Set<String> erasedValueTags = new java.util.LinkedHashSet<>();

    public ElementRegistry() {
        registerDefaults();
    }

    /** 注册一个标签。重复注册会覆盖（后注册者胜）。 */
    public ElementRegistry register(String tag, Supplier<UIElement> factory) {
        factories.put(tag.toLowerCase(java.util.Locale.ROOT), factory);
        return this;
    }

    /**
     * 注册一个<b>可绑定</b>的标签。
     *
     * @param valueTypeName 值类型名（见 {@code SignalTypes.byName}）
     * @param erased        值类型是否被泛型擦除（脚本须显式写 {@code type}）
     */
    public ElementRegistry registerBindable(String tag, Supplier<UIElement> factory,
                                            String valueTypeName, boolean erased) {
        var key = tag.toLowerCase(java.util.Locale.ROOT);
        factories.put(key, factory);
        bindTypes.put(key, valueTypeName);
        if (erased) {
            erasedValueTags.add(key);
        }
        return this;
    }

    /**
     * 该标签可绑定的值类型名。
     *
     * @return 不可绑定、或值类型被擦除（需显式指定）时返回 null
     */
    @Nullable
    public String bindTypeOf(String tag) {
        var key = tag.toLowerCase(java.util.Locale.ROOT);
        return erasedValueTags.contains(key) ? null : bindTypes.get(key);
    }

    /** 该标签是否可绑定（含需要显式写 type 的）。 */
    public boolean isBindable(String tag) {
        return bindTypes.containsKey(tag.toLowerCase(java.util.Locale.ROOT));
    }

    /** 该标签的值类型是否被泛型擦除。 */
    public boolean isValueTypeErased(String tag) {
        return erasedValueTags.contains(tag.toLowerCase(java.util.Locale.ROOT));
    }

    @Nullable
    public UIElement create(String tag) {
        var factory = factories.get(tag);
        return factory == null ? null : factory.get();
    }

    public List<String> knownTags() {
        return List.copyOf(factories.keySet());
    }

    private void registerDefaults() {
        // 容器 —— <panel> 就是裸 UIElement，最常用
        register("panel", UIElement::new);
        register("element", UIElement::new);
        register("box", UIElement::new);
        register("div", UIElement::new);

        // 文本与按钮
        // Label 的值类型是 Component（不是 String）——见 SignalTypes 的说明。
        registerBindable("label", Label::new, "component", false);
        register("button", Button::new);

        // 输入
        // Slider 是抽象类，具体形态由子类给：默认水平，<slider-vertical> 取竖直。
        registerBindable("slider", Slider.Horizontal::new, "float", false);
        registerBindable("slider-horizontal", Slider.Horizontal::new, "float", false);
        registerBindable("slider-vertical", Slider.Vertical::new, "float", false);
        registerBindable("text-field", TextField::new, "string", false);
        registerBindable("textfield", TextField::new, "string", false);
        register("text-area", TextArea::new);
        register("textarea", TextArea::new);
        registerBindable("switch", Switch::new, "bool", false);
        registerBindable("toggle", Toggle::new, "bool", false);
        // Selector<T> 的值类型运行期擦除，必须由脚本显式写 type
        registerBindable("selector", Selector::new, "string", true);

        // 展示
        registerBindable("progress-bar", ProgressBar::new, "float", false);
        registerBindable("progressbar", ProgressBar::new, "float", false);
        registerBindable("progress", ProgressBar::new, "float", false);

        // 滚动与分栏
        // ScrollerView 是具体类；SplitView / Scroller 是抽象类，由子类给形态。
        register("scroller", ScrollerView::new);
        register("scroller-view", ScrollerView::new);
        register("scroll", ScrollerView::new);
        register("split-view", SplitView.Horizontal::new);
        register("split-horizontal", SplitView.Horizontal::new);
        register("split-vertical", SplitView.Vertical::new);

        // 标签页与对话框
        register("tab-view", TabView::new);
        register("tab", Tab::new);
        register("dialog", Dialog::new);

        // 裸滚动条
        register("scrollbar", Scroller.Vertical::new);
        register("scrollbar-vertical", Scroller.Vertical::new);
        register("scrollbar-horizontal", Scroller.Horizontal::new);
    }
}
