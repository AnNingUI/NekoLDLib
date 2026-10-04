package com.tkisor.nekoldlib.types;

import com.tkisor.nekojs.api.catalog.ManualDeclarationCatalogEntry;
import com.tkisor.nekojs.core.plugin.TypeDocsRegister;
import com.tkisor.nekoldlib.NekoLDLib;
import com.tkisor.nekoldlib.jsx.ElementRegistry;
import com.tkisor.nekoldlib.jsx.ElementRenderer;

import java.util.List;

/**
 * 生成 JSX 标签的类型声明，供编辑器补全与校验。
 *
 * <h2>为什么需要（NekoJS 目前完全没有这块）</h2>
 *
 * JSX 标签名（{@code <panel/>}）在 TypeScript 里会被查 {@code JSX.IntrinsicElements}，
 * 而该接口现在只在 React 等库的声明里存在——NekoJS 自己的声明里<b>一个都没有</b>。
 * 所以编辑器对 {@code <panel} 没有任何提示，属性写错也不报错。
 *
 * <h2>落地路径</h2>
 *
 * 用 {@link ManualDeclarationCatalogEntry} 注入一段 {@code declare global} 文本，
 * NekoJS 的 {@code TypeScriptProbeBackend} 会把它渲染进
 * {@code .neko_probe/@manual/index.d.ts}，而各脚本目录的 {@code jsconfig.json}
 * 已经 include 了 {@code @manual/**\/*.d.ts}。
 *
 * <p>生成结果是 classic 模式（生成的 jsconfig 是 {@code "jsx": "react"} +
 * {@code jsxFactory}），所以走全局 {@code JSX} namespace 而不是
 * React 的 {@code JSX} import。
 *
 * <h2>声明从哪来</h2>
 *
 * 标签清单取自 {@link ElementRegistry#knownTags()}，属性名取自
 * {@link ElementRenderer#STYLE_PROPERTIES}——都<b>不硬编码</b>，避免加标签时漏更新声明。
 */
public final class JsxTypes {

    private JsxTypes() {
    }

    /** 注册到 NekoJS 的类型文档。 */
    public static void register(TypeDocsRegister registry) {
        registry.registerManualDeclaration(ManualDeclarationCatalogEntry.of(
                "nekoldlib:jsx",
                declaration(),
                "NekoLDLib JSX 标签、属性与事件名的类型声明",
                List.of(
                        "UI.open(<panel class=\"panel_bg\"><label text=\"hi\" /></panel>)",
                        "<slider min={1} max={10} bind={{ get: () => speed, set: v => { speed = v } }} />",
                        "<button text=\"点我\" on-click={e => console.info(e)} />")));

        // vnode 字面量的放行声明。必须与上一段分开注册：它是 `declare module` 增强，
        // 而上一段是 `declare global`——同一个 entry 里混两种会让结构不清晰。
        registry.registerManualDeclaration(ManualDeclarationCatalogEntry.of(
                "nekoldlib:vnode",
                vNodeAugmentation(),
                "放行手工构造的 vnode 字面量（{ tag, props, children }）",
                List.of("UI.render({ tag: 'panel', props: {}, children: [] })")));

        // RPC 的类型不加 typeOverride —— 两条路都堵死，记录在此免得再试一遍。
        //
        // 试过：把 RPC 绑定的类型指到手写的泛型接口，靠泛型推导让 fn(({msg}) => ...) 推出形参。
        // 不可行——NekoJS 的 BindingDeclarationGenerator 会**无条件**生成
        // `type <override> = $<JavaType>`（见其 :63-71），于是 typeOverride 指定的名字
        // 在模块作用域被占成了 $Rpc 的别名，手写的全局同名接口反被遮蔽：
        //
        //     type NekoRpc = $Rpc;      // NekoJS 生成
        //     let RPC: NekoRpc;         // 于是 RPC 指向 $Rpc，不是我们的接口
        //
        // 改 `interface` 增强 $RpcBuilder 也不行：实测原 class 的旧重载
        // 与新加的泛型重载并存时，TS 选前者，推导失效。
        //
        // 现状：RpcBuilder 走 `.schema({...}).returns('...').fn(({k}) => ...)`，
        // 键名与类型标签都是字面量，声明层可用类型体操推导（见 RpcBuilder 类注释）。
        // 但**这依赖 NekoJS 生成的声明也说人话** —— `schema(Map<String,String>)` 反射出来
        // 只是 `$Map`，推不出键值。等 @NekoProbe 注解落地后由注解提供手写泛型声明接管。
        // 在那之前，脚本侧的 `{msg}` 仍是 any，但 API 形状已就位。

        NekoLDLib.LOGGER.info("[NekoLDLib] 已注册 JSX 类型声明（{} 个标签）",
                NekoLDLib.elements().knownTags().size());
    }

    /**
     * 生成声明文本。
     *
     * <p>{@code export {}} + {@code declare global}：{@code @manual/index.d.ts} 是模块，
     * 想要全局生效必须先声明为模块再进 {@code declare global}
     * （与 NekoJS 自己的 {@code BindingDeclarationGenerator} 同一模式）。
     */
    static String declaration() {
        var sb = new StringBuilder(4096);

        sb.append("// NekoLDLib JSX 标签声明。由 mod 在运行时生成，不要手改。\n");
        sb.append("// 标签与属性清单来自 ElementRegistry / ElementRenderer，加标签后自动更新。\n\n");
        sb.append("export {};\n\n");
        sb.append("declare global {\n");

        // ---------------------------------------------------------------
        // NekoJS 缺失的运行时全局
        //
        // 这几项在运行时都有（NekoJS 的沙盒提供），但类型层面 NekoJS 一个都没声明，
        // 于是每个 .tsx 脚本满屏报错：找不到 console / setInterval、
        // 以及 classic JSX 要求的 __nekoJsxFactory 不在作用域（TS2874，每个标签一条）。
        //
        // 这里补齐。用 `var` 而不是 `const`：TS 允许重复的 var 声明合并，
        // 万一 NekoJS 之后自己补上同名声明，也不会因重复定义而冲突。
        // ---------------------------------------------------------------
        sb.append("  /** 脚本控制台。NekoJS 运行时提供，类型层面此前没有声明。*/");
        sb.append("  var console: {\n");
        sb.append("    log(...args: unknown[]): void;\n");
        sb.append("    info(...args: unknown[]): void;\n");
        sb.append("    warn(...args: unknown[]): void;\n");
        sb.append("    error(...args: unknown[]): void;\n");
        sb.append("    debug(...args: unknown[]): void;\n");
        sb.append("  };\n\n");

        sb.append("  /** 定时器。NekoJS 提供 node:timers 模块，也把这两个挂了全局。*/");
        sb.append("  function setTimeout(callback: (...args: unknown[]) => void, delay?: number, ...args: unknown[]): number;\n");
        sb.append("  function setInterval(callback: (...args: unknown[]) => void, delay?: number, ...args: unknown[]): number;\n");
        sb.append("  function clearTimeout(id: number): void;\n");
        sb.append("  function clearInterval(id: number): void;\n\n");

        sb.append("  /**\n");
        sb.append("   * classic JSX runtime 的工厂。\n");
        sb.append("   *\n");
        sb.append("   * NekoJS 生成的 jsconfig 用的是 classic 模式（`\"jsx\": \"react\"` +\n");
        sb.append("   * `jsxFactory: \"__nekoJsxFactory\"`）。TS 在 classic 模式下要求该工厂\n");
        sb.append("   * **在作用域内可见**，否则每个 JSX 标签都报 TS2874。\n");
        sb.append("   * 运行时由 NekoJS 的 define.js 提供实现，类型层面此前没有声明。\n");
        sb.append("   */\n");
        sb.append("  function __nekoJsxFactory(type: unknown, props: unknown, ...children: unknown[]): any;\n");
        sb.append("  function __nekoJsxFragment(...children: unknown[]): any;\n\n");

        // ---------------------------------------------------------------
        // 公共属性：所有标签都支持。带值类型参数，供 bind 推导。
        // ---------------------------------------------------------------
        sb.append("  /**\n");
        sb.append("   * 所有 NekoLDLib 元素共有的属性。\n");
        sb.append("   *\n");
        sb.append("   * 类型参数 {@code TValue} 是这个控件的可绑定值类型，由具体标签传入\n");
        sb.append("   * （见 IntrinsicElements），因此 {@code bind.set} 的参数能被推导出来：\n");
        sb.append("   *\n");
        sb.append("   * ```\n");
        sb.append("   * <switch bind={{ get: () => on, set: v => { /* v: boolean *\\/ } }} />\n");
        sb.append("   * <slider bind={{ get: () => n,  set: v => { /* v: number  *\\/ } }} />\n");
        sb.append("   * ```\n");
        sb.append("   *\n");
        sb.append("   * 不可绑定的标签传 {@code never}，此时 {@code bind} 也是 {@code never}，\n");
        sb.append("   * 写了就会报错——这正好把\"这个标签不支持绑定\"变成编译期错误。\n");
        sb.append("   */\n");
        sb.append("  interface NekoLDLibBaseProps<TValue = never> {\n");
        sb.append("    /** LSS class 名，空格分隔或数组 */\n");
        sb.append("    class?: string | string[];\n");
        sb.append("    className?: string | string[];\n");
        sb.append("    /** 元素 id */\n");
        sb.append("    id?: string;\n");
        sb.append("    /** 文本内容（Button / Label 等） */\n");
        sb.append("    text?: string | number;\n");
        sb.append("    /** 数据绑定。值的类型来自控件本身，不需要写 type（selector 除外） */\n");
        sb.append("    bind?: [TValue] extends [never] ? never : NekoLDLibBinding<TValue>;\n");
        sb.append("    /** 成组写 LSS 属性 */\n");
        sb.append("    style?: Record<string, string | number>;\n");
        sb.append("    layout?: Record<string, string | number>;\n");
        sb.append("    /** Slider 取值范围（走 setRange，不是 LSS 属性） */\n");
        sb.append("    min?: number;\n");
        sb.append("    max?: number;\n");
        sb.append("    /**\n");
        sb.append("     * 事件绑定，如 on-click / on-mouseEnter。\n");
        sb.append("     *\n");
        sb.append("     * 覆盖 LDLib2 UIEvents 里的常用事件名；其余事件名（dragEnter、\n");
        sb.append("     * fileDrop 等）运行时同样有效，只是不做类型约束。\n");
        sb.append("     *\n");
        sb.append("     * 不做 on-${string} 的模板索引签名：那要求所有属性都能赋成回调，\n");
        sb.append("     * 与 class?: string 等冲突，接口本身会编译不过。\n");
        sb.append("     */\n");
        sb.append(eventProps());
        sb.append("  }\n\n");

        // ---------------------------------------------------------------
        // bind 的形状。泛型参数让 set 的形参可推导。
        // ---------------------------------------------------------------
        sb.append("  /** 数据绑定。{@code TValue} 由控件决定。*/");
        sb.append("  interface NekoLDLibBinding<TValue = unknown> {\n");
        sb.append("    /** 取值函数，返回值即控件当前值（必需） */\n");
        sb.append("    get: () => TValue;\n");
        sb.append("    /** 收值函数。省略即只读绑定（值只从服务端流向控件） */\n");
        sb.append("    set?: (value: TValue) => void;\n");
        sb.append("    /** 是否接受客户端→服务端。省略时按有没有 set 推断 */\n");
        sb.append("    remote?: boolean;\n");
        sb.append("    /** 仅当控件值类型被泛型擦除时需要（selector） */\n");
        sb.append("    type?: NekoLDLibTypeName;\n");
        sb.append("  }\n\n");

        // 值类型名 → TS 类型。只用 TS 原生类型，避免引用 @package 里的声明。
        sb.append("  /** 控件值类型名 → TS 类型。*/");
        sb.append("  type NekoLDLibValueType<T> =\n");
        sb.append("    T extends 'bool' ? boolean :\n");
        sb.append("    T extends 'float' | 'double' | 'int' | 'long' | 'short' | 'byte' ? number :\n");
        sb.append("    T extends 'string' ? string :\n");
        sb.append("    T extends 'component' ? string :\n");
        sb.append("    T extends 'uuid' ? string :\n");
        sb.append("    T extends 'identifier' ? string :\n");
        sb.append("    unknown;\n\n");

        // ---------------------------------------------------------------
        // LSS 属性名：从渲染器取，保证与运行时一致
        // ---------------------------------------------------------------
        sb.append("  /** 布局与样式属性，等价于 LSS 文件里的写法 */\n");
        sb.append("  interface NekoLDLibLssProps {\n");
        for (var name : ElementRenderer.STYLE_PROPERTIES) {
            sb.append("    '").append(name).append("'?: string | number;\n");
        }
        sb.append("  }\n\n");

        // ---------------------------------------------------------------
        // 类型名（bind.type / RPC spec 用）
        // ---------------------------------------------------------------
        sb.append("  type NekoLDLibTypeName =\n");
        sb.append("    | 'bool' | 'boolean' | 'float' | 'double' | 'int' | 'integer'\n");
        sb.append("    | 'long' | 'short' | 'byte' | 'char' | 'string'\n");
        sb.append("    | 'component' | 'itemstack' | 'fluidstack' | 'block' | 'tag';\n\n");

        // ---------------------------------------------------------------
        // 事件名：从 UIEvents 取
        // ---------------------------------------------------------------
        sb.append("  /** 事件名，用于 on-<名字>；on-click 是 mouseClick 的别名 */\n");
        sb.append("  type NekoLDLibEventName =\n");
        for (var name : ElementRenderer.EVENT_NAMES) {
            sb.append("    | '").append(name).append("'\n");
        }
        // 别名
        for (var alias : ElementRenderer.EVENT_ALIASES) {
            sb.append("    | '").append(alias).append("'\n");
        }
        sb.append("    ;\n\n");

        // ---------------------------------------------------------------
        // 各标签
        // ---------------------------------------------------------------
        sb.append("  namespace JSX {\n");
        sb.append("    interface Element {}\n");
        sb.append("    interface IntrinsicAttributes {}\n");
        sb.append("    /**\n");
        sb.append("     * NekoLDLib 的 JSX 标签。\n");
        sb.append("     *\n");
        sb.append("     * 只列内置标签——<b>不</b>加字符串索引签名。加了会让任意标签名都\n");
        sb.append("     * 通过校验（索引签名会把所有键的类型放宽成 any），标签拼错就再也\n");
        sb.append("     * 报不出来了，而这正是类型声明要防的事。\n");
        sb.append("     *\n");
        sb.append("     * 自定义组件（函数组件）走 TS 自身的解析，不依赖这里。\n");
        sb.append("     */\n");
        sb.append("    interface IntrinsicElements {\n");

        for (var tag : NekoLDLib.elements().knownTags()) {
            var registry = NekoLDLib.elements();
            sb.append("      /** <").append(tag).append("> */\n");
            if (registry.isBindable(tag)) {
                var valueType = registry.bindTypeOf(tag);
                if (valueType == null) {
                    // 值类型被泛型擦除（selector）：无法从标签推出，用 unknown 让它仍可写
                    sb.append("      '").append(tag)
                            .append("': NekoLDLibBaseProps<unknown> & NekoLDLibLssProps;\n");
                } else {
                    sb.append("      '").append(tag)
                            .append("': NekoLDLibBaseProps<NekoLDLibValueType<'")
                            .append(valueType).append("'>> & NekoLDLibLssProps;\n");
                }
            } else {
                // 不可绑定：bind 是 never，写了会报错
                sb.append("      '").append(tag)
                        .append("': NekoLDLibBaseProps<never> & NekoLDLibLssProps;\n");
            }
        }
        sb.append("    }\n");
        sb.append("  }\n");

        sb.append("}\n");
        return sb.toString();
    }
    /**
     * 逐个展开 {@code on-<事件名>} 属性。
     *
     * <p>不用模板字面量索引签名 {@code [key: `on-${string}`]}：TS 要求索引签名覆盖
     * 所有已声明属性，而 {@code class?: string} 不满足回调类型，接口会直接编译不过。
     */
    private static String eventProps() {
        var sb = new StringBuilder();
        var seen = new java.util.LinkedHashSet<String>();
        seen.addAll(ElementRenderer.EVENT_ALIASES);
        seen.addAll(ElementRenderer.EVENT_NAMES);
        for (var name : seen) {
            sb.append("    'on-").append(name).append("'?: (event: unknown) => void;\n");
        }
        return sb.toString();
    }

    /**
     * {@code $VNode} 的补充声明：放行手工构造的 vnode 字面量。
     *
     * <h2>为什么用可选属性而不是索引签名</h2>
     *
     * 手工构造 vnode（{@code { tag: 'panel', props: {}, children: [] }}）在自检与调试里很常用
     * ——它能绕开 JSX 编译直接喂给渲染器。但 probe 把 {@code VNode} 渲染成空 class，
     * 字面量会报 TS2353「tag 不在类型 $VNode 中」。
     *
     * <p>我第一版加了索引签名 {@code [key: string]: unknown}，结果<b>更糟</b>：
     * 索引签名要求**所有**传入值都具备索引签名，而 {@code JSX.Element} 没有，
     * 于是 {@code UI.render(<panel/>)} 全线报错（20 → 8 全靠移除它）。
     *
     * <p>可选属性只**放行**这几个键，不放宽整体——实测两种写法都能通过：
     * <pre>
     *   UI.render(&lt;panel/&gt;)                                    ✓
     *   UI.render({ tag: 'panel', props: {}, children: [] })    ✓
     * </pre>
     */
    static String vNodeAugmentation() {
        var sb = new StringBuilder(512);
        sb.append("declare module \"java:com/tkisor/nekoldlib/jsx\" {\n");
        sb.append("  /** 手工构造 vnode 时用到的键。两种 JSX runtime 分别用 tag / type。*/");
        sb.append("  export interface $VNode {\n");
        sb.append("    tag?: unknown;\n");
        sb.append("    type?: unknown;\n");
        sb.append("    key?: unknown;\n");
        sb.append("    props?: unknown;\n");
        sb.append("    children?: unknown;\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

}
