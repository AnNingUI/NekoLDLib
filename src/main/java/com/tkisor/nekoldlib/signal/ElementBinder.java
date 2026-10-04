package com.tkisor.nekoldlib.signal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IBindable;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.IBinding;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.tkisor.nekoldlib.NekoLDLib;
import com.tkisor.nekoldlib.jsx.JsxHost;
import graal.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 把 JSX 里的 {@code bind} 属性接到 LDLib2 的数据绑定上。
 *
 * <h2>脚本写法</h2>
 *
 * <pre>{@code
 * let enabled = false
 * let speed = 1
 *
 * <switch  bind={{ get: () => enabled, set: v => { enabled = v }, remote: true }} />
 * <slider  bind={{ get: () => speed,   set: v => { speed = v },   remote: true }} />
 * <label   bind={{ get: () => 'Speed ' + speed }} />   // 只读：省略 set
 * }</pre>
 *
 * <ul>
 *   <li>{@code get} —— 取值函数，返回值即为控件当前值（必需）</li>
 *   <li>{@code set} —— 收值函数。省略即为只读绑定（值只从服务端流向控件）</li>
 *   <li>{@code remote} —— 是否接受客户端→服务端方向。省略时按控件是否有 {@code set} 推断：
 *       有 {@code set} 即双向</li>
 *   <li>{@code type} —— 仅当控件值类型被泛型擦除时需要（如 {@code Selector}），
 *       平时由控件自动推断，不要写</li>
 * </ul>
 *
 * <h2>为什么不重造同步</h2>
 *
 * LDLib2 的 {@link DataBindingBuilder} + {@code SimpleBinding} 已经把双向方向、
 * S2C/C2S 策略、以及"元素挂到 ModularUI 后才注册到 syncManager"这套生命周期处理好了
 * （{@code UIElement.addSyncValue} 会先存起来，等 {@code modularUI != null} 再注册）。
 * 本类只负责把脚本的 get/set 接上去。
 */
public final class ElementBinder {

    private ElementBinder() {
    }

    /**
     * @param element 目标控件
     * @param spec    脚本给的 {@code bind} 对象
     * @param ctx     渲染上下文（host + 绑定模式）
     */
    public static void bind(UIElement element, Value spec,
                            com.tkisor.nekoldlib.jsx.ElementRenderer.Ctx ctx) {
        var host = ctx.host();

        if (!(element instanceof IBindable<?> bindable)) {
            throw new IllegalArgumentException(
                    "<" + element.getClass().getSimpleName() + "> 不支持数据绑定。"
                            + "可绑定的控件：Label / Switch / Toggle / Slider / ProgressBar / TextField / Selector。");
        }

        var getter = host.member(spec, "get");
        if (getter == null || getter.isNull() || !getter.canExecute()) {
            throw new IllegalArgumentException("bind 缺少 get 函数：bind={{ get: () => value }}");
        }
        var setter = host.member(spec, "set");
        var hasSetter = setter != null && !setter.isNull() && setter.canExecute();

        // 类型解析对两条路径都是必需的——本地绑定写元素时也要知道目标类型，
        // 才能在类型不匹配时给出可读报错而不是运行期 ClassCastException。
        resolveType(element, spec, host);
        var name = nameOf(element);

        // 能否绑定、能否双向，由 LocalBinding / SimpleBinding 各自校验并给出针对性报错
        if (ctx.bindMode() == BindMode.CLIENT_LOCAL) {
            LocalBinding.install(element, getter, hasSetter ? setter : null, name);
            return;
        }
        bindServerMenu(bindable, spec, host, getter, setter, hasSetter, element, name);
    }

    /**
     * 服务端菜单路径：接 LDLib2 的 {@code SimpleBinding}。
     *
     * <p>权威值在服务端，客户端侧的数据源就是元素自己（{@code IBindable.bind} 会把元素
     * 设为 {@code remoteDataSource}），跨端由 {@code UISyncManager} 搬运。
     */
    private static void bindServerMenu(IBindable<?> bindable, Value spec, JsxHost host,
                                       Value getter, Value setter, boolean hasSetter,
                                       UIElement element, String name) {
        var type = resolveType(element, spec, host);
        var targetClass = type instanceof Class<?> c ? c : null;

        // remote 省略时按"有没有 set"推断：只读绑定不该被客户端改写。
        var remoteNode = host.member(spec, "remote");
        boolean remote = remoteNode != null && !remoteNode.isNull()
                ? remoteNode.asBoolean()
                : hasSetter;

        // getter 必须返回 Java 值而不是 Graal Value —— SyncValue 会按声明的 Type
        // 序列化它，拿包装对象去序列化会抛 ClassCastException。
        Supplier<Object> jsGetter = () -> safeGet(getter, name, targetClass);
        Consumer<Object> jsSetter = hasSetter
                ? v -> safeSet(setter, v, name)
                : v -> { };

        DataBindingBuilder<Object> builder = DataBindingBuilder
                .create(jsGetter, jsSetter)
                .syncType(type);
        builder.name(name);
        if (hasSetter) {
            builder.initialValue(safeGet(getter, name, targetClass));
        }

        var binding = builder.build(isRemote());
        bindable.bind(cast(binding));
    }

    /**
     * 判定这次绑定属于"客户端侧"还是"服务端侧"。
     *
     * <p><b>不能只看 dist。</b>单人游戏里服务端 UI 树与客户端 UI 树在同一个 JVM 中
     * （dist 都是 CLIENT），只按 dist 会把服务端侧也标成 remote，导致同步方向错误。
     * LDLib2 用"是不是对应侧主线程"来区分，正是为了处理这种情况：
     *
     * <pre>
     *   LDLib2.isRemote()  -> 客户端主线程（客户端侧树）
     *   LDLib2.isServer()  -> 服务端主线程（含单人游戏的集成服务器）
     * </pre>
     *
     * <p>两者都不成立时说明当前是<b>非主线程</b>——脚本可能在任意线程触发渲染
     * （实测踩到过：{@code DataBindingBuilder.build()} 抛
     * <em>neither a client nor a server thread</em>）。这种情形下退回按 dist 判定：
     * 带客户端的进程按 remote 处理，专用服务器按 server 处理。
     */
    private static boolean isRemote() {
        if (com.lowdragmc.lowdraglib2.LDLib2.isRemote()) {
            return true;
        }
        if (com.lowdragmc.lowdraglib2.LDLib2.isServer()) {
            return false;
        }
        return net.neoforged.fml.loading.FMLEnvironment.getDist()
                == net.neoforged.api.distmarker.Dist.CLIENT;
    }

    /**
     * 解析绑定的值类型：优先用控件推断，脚本显式给的 {@code type} 只在擦除时兜底。
     *
     * <p>顺序是刻意的——控件类型是硬事实（{@code Label} 只吃 {@code Component}），
     * 让脚本覆盖它只会制造不匹配。
     */
    private static Type resolveType(UIElement element, Value spec, JsxHost host) {
        var resolved = SignalTypes.resolve(element.getClass());
        if (!resolved.needsExplicit()) {
            return resolved.type();
        }

        // 泛型被擦除（如 Selector<T>）——需要脚本显式给类型
        var explicit = host.member(spec, "type");
        if (explicit != null && !explicit.isNull()) {
            var name = explicit.asString();
            var clazz = SignalTypes.byName(name);
            if (clazz == null) {
                throw new IllegalArgumentException(
                        "无法识别的 bind type: '" + name + "'。可用：" + SignalTypes.knownNames());
            }
            return clazz;
        }

        throw new IllegalArgumentException(
                "<" + element.getClass().getSimpleName() + "> 无法推断绑定值类型（"
                        + resolved.erasedReason() + "）。请在 bind 里显式指定 type，例如 "
                        + "bind={{ get, set, type: 'string' }}。可用：" + SignalTypes.knownNames());
    }

    @SuppressWarnings("unchecked")
    private static <T> IBinding<T> cast(IBinding<?> binding) {
        return (IBinding<T>) binding;
    }

    // ------------------------------------------------------------------
    // JS 调用包装
    //
    // 这两个方向的值转换不一样：
    //   getter：JS 值 -> 绑定类型（走类型适配器）
    //   setter：绑定类型值 -> JS（Graal 自动包成 host object，无需转换）
    // ------------------------------------------------------------------

    @Nullable
    private static Object safeGet(Value getter, String name, @Nullable Class<?> target) {
        try {
            var raw = getter.execute();
            // Value -> Java 值：Value.execute() 返回的是 Graal 包装对象，
            // 直接交给按 Type 序列化的 SyncValue 会抛 ClassCastException。
            return target == null ? raw : SignalTypes.toJava(raw, target);
        } catch (Exception e) {
            NekoLDLib.LOGGER.error("[NekoLDLib] 绑定 {} 的 get 函数抛错", name, e);
            return null;
        }
    }

    private static void safeSet(Value setter, @Nullable Object value, String name) {
        try {
            if (value == null) {
                setter.execute((Object) null);
            } else {
                setter.execute(value);
            }
        } catch (Exception e) {
            NekoLDLib.LOGGER.error("[NekoLDLib] 绑定 {} 的 set 函数抛错", name, e);
        }
    }

    /** 生成一个稳定的绑定名：控件类型 + id，便于在同步报错里定位。 */
    private static String nameOf(UIElement element) {
        var id = element.getId();
        var base = element.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        return id == null || id.isBlank() ? base : base + "#" + id;
    }
}
