package com.tkisor.nekoldlib.signal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IBindable;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableUIElement;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.tkisor.nekoldlib.NekoLDLib;
import com.tkisor.nekoldlib.client.ClientTicker;
import com.tkisor.nekoldlib.client.ScriptElements;
import graal.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端本地绑定：不涉及网络，直接在"JS 变量 ↔ 控件"之间搬值。
 *
 * <h2>为什么需要它（而不是直接用 LDLib2 的 SimpleBinding）</h2>
 *
 * LDLib2 的 {@code SimpleBinding} 是为<b>服务端菜单</b>设计的：客户端侧的
 * {@code remoteDataSource} 就是元素自己，权威值放服务端；而且
 * {@code UISyncManager.tick()} 第一行是 {@code if (modularUI.player == null) return;}。
 * 所以纯客户端界面（{@code ModularUI.of(ui)}，player 为 null）上，
 * 同步链路整个是惰性的——值既不会流动也不会触发回调。
 *
 * <p>本地绑定绕开那条链路：
 * <ul>
 *   <li><b>JS → 控件</b>：每客户端 tick 调一次 getter，把结果推给元素
 *       （只在值真的变了时才推，避免每 tick 都触发样式重算）。</li>
 *   <li><b>控件 → JS</b>：订阅元素的 {@code IDataProvider}，控件内部值变化时
 *       回调 JS setter。LDLib2 的 BindableUIElement 通过 {@code notifyListeners()}
 *       推送，正是这条路径。</li>
 * </ul>
 *
 * <h2>代次守卫</h2>
 *
 * 脚本重载会关闭旧 Graal Context，而监听器闭包持有指向死 Context 的 {@code Value}。
 * 所以每个本地绑定都记下创建时的代次，发现代次变了就自行注销，避免在已死 Context 上取值。
 */
public final class LocalBinding {

    /** 代次守卫的检查间隔（tick）。每 tick 都查一次代价可忽略，但没必要。 */
    private static final int GENERATION_CHECK_INTERVAL = 20;

    private LocalBinding() {
    }

    /**
     * 建立本地绑定。
     *
     * @param element 目标控件（必须是 BindableUIElement —— 只有它能推送内部变化）
     * @param getter  JS 取值函数
     * @param setter  JS 收值函数；可为 null 表示只读
     * @param name    绑定名，用于日志
     */
    public static void install(UIElement element,
                               Value getter,
                               @Nullable Value setter,
                               String name) {
        // LDLib2 里可绑定元素分两类，能力不同：
        //   BindableUIElement<T>（Switch/Slider/TextField…）—— 有 registerValueListener，可双向
        //   IBindable<T>（Label 这类只实现接口的）—— 只有 setValue，只能单向
        // Label 是最常用的绑定目标（显示文本），所以必须支持只可写这一档。
        if (!(element instanceof IBindable<?> bindable)) {
            throw new IllegalArgumentException(
                    "<" + element.getClass().getSimpleName() + "> 不支持数据绑定。"
                            + "可绑定的控件：Label / Switch / Toggle / Slider / ProgressBar / TextField / Selector。");
        }

        var canPushBack = bindable instanceof BindableUIElement<?>;
        if (setter != null && !setter.isNull() && setter.canExecute() && !canPushBack) {
            throw new IllegalArgumentException(
                    "<" + element.getClass().getSimpleName() + "> 只能做只读绑定（它不会产生值变化）。"
                            + "去掉 set，只保留 get：bind={{ get: () => ... }}。");
        }
        // 用局部变量而非改写参数：后面的 lambda 要捕获它，参数被重新赋值后就不是 effectively final 了
        final Value effectiveSetter =
                canPushBack && setter != null && !setter.isNull() && setter.canExecute() ? setter : null;

        var createdGeneration = ScriptElements.generation();
        var ticks = new int[]{0};
        var lastPushed = new Object[]{UNSET};
        var cancelled = new boolean[]{false};
        // 目标类型：把 JS 值写进元素前必须按它做转换（见 setElementValue 的说明）
        var targetType = resolveTargetClass(bindable);

        Runnable tickTask = () -> {
            if (cancelled[0]) {
                return;
            }
            // 脚本重载后旧 Context 已死，本绑定作废（界面本身会被 ScriptElements 关掉）
            if (++ticks[0] % GENERATION_CHECK_INTERVAL == 0
                    && createdGeneration != ScriptElements.generation()) {
                cancelled[0] = true;
                return;
            }

            Value raw;
            try {
                raw = getter.execute();
            } catch (Exception e) {
                // Context 已关闭 / getter 自身抛错 —— 注销自己，避免每 tick 刷屏
                NekoLDLib.LOGGER.info("[NekoLDLib] 本地绑定 {} 停止推送：{}", name, e.toString());
                cancelled[0] = true;
                return;
            }

            // Value -> Java 值。这一步不能省：Value.execute() 返回的是 Graal 的包装对象，
            // 直接交给强类型 setter 会抛 ClassCastException（Value cannot be cast to X）。
            Object current;
            try {
                current = targetType == null ? raw : SignalTypes.toJava(raw, targetType);
            } catch (RuntimeException e) {
                NekoLDLib.LOGGER.error(
                        "[NekoLDLib] 本地绑定 {} 的值转换失败（目标 {}）：{}", name, targetType, e.getMessage());
                return;
            }

            // 只在真的变了时写回，避免每 tick 触发样式重算。
            // 比较用转换后的值：JS 侧的原始 Value 每次都是新对象，比不出相等。
            if (java.util.Objects.equals(lastPushed[0], current)) {
                return;
            }
            lastPushed[0] = current;
            if (!setElementValue(bindable, current, name)) {
                // 写不进去就没有继续推的意义，停掉以免每 tick 刷屏
                cancelled[0] = true;
            }
        };

        // 控件 -> JS
        // 用 registerValueListener 而非 registerListener：后者是 IDataProvider 上的方法，
        // 这条路径由元素内部的 notifyListeners() 在 setValue(..., notify=true) 时触发。
        if (effectiveSetter != null && bindable instanceof BindableUIElement<?> bElement) {
            bElement.registerValueListener(value -> {
                if (cancelled[0]) {
                    return;
                }
                try {
                    effectiveSetter.execute(value);
                } catch (Exception e) {
                    NekoLDLib.LOGGER.error("[NekoLDLib] 本地绑定 {} 的 set 回调抛错", name, e);
                }
            });
        }

        ClientTicker.everyTick(tickTask);

        // 立即推一次，避免界面刚开出来显示的是元素默认值
        ClientTicker.once("本地绑定首次推送 " + name, tickTask);
    }

    /** 用 Sentinel 区分"尚未推送过"和"推送过 null"。 */
    private static final Object UNSET = new Object();

    /**
     * 把 JS 值写进元素。
     *
     * <p>走 {@link IBindable#setValue} 而不是具体的 setter —— 它在
     * {@code BindableUIElement} 和只实现接口的 {@code Label} 上都存在，
     * 因此对 Switch / Toggle / Slider / TextField / ProgressBar / Label 一视同仁。
     *
     * <p>写失败<b>只在首次</b>记 ERROR 并停掉本绑定：静默吞错会让"绑定其实没生效"
     * 看起来像正常工作（这个坑真实踩过——所有写值都失败，却因为每 tick 都刷同样的
     * 错误日志而被误读成正常）。
     */
    @SuppressWarnings("unchecked")
    private static boolean setElementValue(IBindable<?> bindable, Object value, String name) {
        try {
            ((IBindable<Object>) bindable).setValue(value);
            return true;
        } catch (RuntimeException e) {
            NekoLDLib.LOGGER.error(
                    "[NekoLDLib] 本地绑定 {} 写值失败，已停用该绑定（值 {} [{}], 目标类型 {}）：{}",
                    name, value, value == null ? "null" : value.getClass().getSimpleName(),
                    elementTypeName(bindable), e.toString());
            return false;
        }
    }

    /**
     * 解析绑定的目标类型（如 Label → Component、Slider → Float）。
     *
     * @return 无法解析（泛型擦除）时返回 null，此时不做转换、直接原样写入
     */
    @Nullable
    private static Class<?> resolveTargetClass(IBindable<?> bindable) {
        var type = SignalTypes.resolve(bindable.getClass()).type();
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private static String elementTypeName(IBindable<?> bindable) {
        return SignalTypes.displayName(SignalTypes.resolve(bindable.getClass()).type());
    }
}
