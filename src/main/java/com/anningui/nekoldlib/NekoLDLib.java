package com.anningui.nekoldlib;

import com.lowdragmc.lowdraglib2.plugin.ILDLibPlugin;
import com.lowdragmc.lowdraglib2.plugin.LDLibPlugin;
import com.tkisor.nekojs.api.JSTypeAdapter;
import com.tkisor.nekojs.api.NekoJSPlugin;
import com.tkisor.nekojs.api.annotation.RegisterNekoJSPlugin;
import com.tkisor.nekojs.api.data.BindingRegistry;
import com.tkisor.nekojs.api.data.JSTypeAdapterRegistry;
import com.anningui.nekoldlib.client.ClientUI;
import com.anningui.nekoldlib.jsx.ElementRegistry;
import com.anningui.nekoldlib.jsx.JsxHostBinding;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NekoLDLib —— NekoJS 与 LDLib2 的桥接 mod。
 *
 * <p>让脚本侧（.jsx）声明式地构建 LDLib2 界面，并复用 LDLib2 的跨端机制。
 *
 * <p>本类同时是两个加载器的入口，因为桥接需要从两侧各拿一点东西：
 * <ul>
 *   <li>{@link ILDLibPlugin} —— LDLib2 的插件入口（{@code @LDLibPlugin} 注解扫描发现）。
 *   <li>{@link NekoJSPlugin} —— NekoJS 的插件入口（{@code @RegisterNekoJSPlugin} 注解扫描发现）。
 * </ul>
 *
 * <p>桥接的接入点是"消费"而非"替换"：NekoJS 内置的 {@code nekojs/jsx-runtime} 已经产出
 * 标准 vnode（其注释明确说渲染由用户侧处理），本 mod 把它渲染成 LDLib2 元素树即可，
 * 因此脚本侧不需要任何 pragma 或 import。
 */
@LDLibPlugin
@RegisterNekoJSPlugin(requiredMods = {"ldlib2", "nekojs", "graalmc"})
public class NekoLDLib implements ILDLibPlugin, NekoJSPlugin {
    public static final String MOD_ID = "nekoldlib";
    public static final Logger LOGGER = LoggerFactory.getLogger("NekoLDLib");

    /** 元素注册表全局一份：标签注册是进程级的，不属于某个界面。 */
    private static final ElementRegistry ELEMENTS = new ElementRegistry();
    private static final JsxHostBinding JSX = new JsxHostBinding(ELEMENTS);

    /**
     * 运行时持有的类型适配器注册表。
     *
     * <p>绑定的值转换需要它（{@code JSTypeAdapter extends Predicate<Value>, Function<Value,T>}），
     * 但该接口只提供注册与 {@code view()}，没有按类查找——所以由本 mod 持有引用，
     * 转换时自行遍历匹配。
     */
    @Nullable
    private static volatile JSTypeAdapterRegistry adapters;

    public static JsxHostBinding jsx() {
        return JSX;
    }

    public static ElementRegistry elements() {
        return ELEMENTS;
    }

    /** 可能为 {@code null}：插件装配完成前脚本侧不会走到绑定路径。 */
    @Nullable
    public static JSTypeAdapterRegistry adapters() {
        return adapters;
    }

    @Override
    public void onLoad() {
        LOGGER.info("[NekoLDLib] LDLib2 侧插件已加载，可用于渲染的标签：{}", ELEMENTS.knownTags());
    }

    /**
     * 注册脚本全局绑定。
     *
     * <p>阶段 1 暴露客户端开界面入口；阶段 2 在这里加入响应式相关的绑定。
     */
    @Override
    public void registerBinding(BindingRegistry registry) {
        ClientUI.install(registry);
        com.anningui.nekoldlib.server.PlayerUI.install(registry);
        com.anningui.nekoldlib.signal.Rpc.install(registry);
        LOGGER.info("[NekoLDLib] 脚本绑定已注册");
    }

    /**
     * 取得类型适配器注册表的引用，并注册本 mod 的适配器。
     *
     * <p>两个用途：
     * <ul>
     *   <li><b>持有引用</b>：绑定的值转换需要遍历 NekoJS 已有的适配器
     *       （{@code JSTypeAdapter} 只提供注册与 {@code view()}，没有按类查找）。</li>
     *   <li><b>注册 {@link com.anningui.nekoldlib.jsx.VNodeAdapter}</b>：让 JSX vnode
     *       能转成 {@link com.anningui.nekoldlib.jsx.VNode}，从而把
     *       {@code UI.render} / {@code PlayerUI.register} 等签名的类型声明
     *       从 {@code $Value} 变成具体类型。</li>
     * </ul>
     */
    @Override
    public void registerAdapters(JSTypeAdapterRegistry registry) {
        adapters = registry;
        registry.register(new com.anningui.nekoldlib.jsx.VNodeAdapter());
        LOGGER.info("[NekoLDLib] 已接入类型适配器（{} 个，含 VNode）", registry.view().size());
    }

    /**
     * 注册 JSX 类型声明，供编辑器补全与校验。
     *
     * <p>NekoJS 自己的声明里<b>没有</b>任何 JSX 标签类型（{@code JSX.IntrinsicElements}
     * 是空的），所以这是从 0 到 1 的补充：注入后写 {@code <panel} 就能看到属性提示，
     * 属性名拼错也会被标出。
     */
    @Override
    public void registerTypeDocs(com.tkisor.nekojs.core.plugin.TypeDocsRegister registry) {
        com.anningui.nekoldlib.types.JsxTypes.register(registry);
    }

    /**
     * 脚本重新加载前：关掉上一代脚本创建的界面。
     *
     * <p>NekoJS 在 reload 时会关闭旧 Graal {@code Context}，但已建好的元素与事件监听器
     * 不会失效——监听器闭包持有指向已死 Context 的 {@code Value}，玩家再点那些按钮就会抛
     * {@code IllegalStateException: The Context is already closed.}。
     * 所以在旧 Context 关闭之前先把残留界面收掉。
     *
     * <p>只处理客户端侧：服务端没有 Screen 概念，其元素树随菜单关闭自然释放。
     */
    @Override
    public void beforeScriptsLoaded(com.tkisor.nekojs.api.ScriptType type) {
        // 客户端侧：关掉上一代脚本创建的界面（其监听器闭包指向即将关闭的 Context）
        if (com.lowdragmc.lowdraglib2.LDLib2.isClient()) {
            com.anningui.nekoldlib.client.ScriptElements.beginNewGeneration(type.name);
        }
        // 按侧清注册：三种脚本环境独立重载，全清会误伤另一侧仍然有效的注册。
        // 侧别由 ScriptType 决定（线程/dist 在加载期不可靠）。
        com.anningui.nekoldlib.server.PlayerUIRegistry.clearSide(
                type == com.tkisor.nekojs.api.ScriptType.CLIENT, type.name);
    }
}
