package com.anningui.nekoldlib.types;

import com.tkisor.nekojs.api.catalog.ClassDeclarationCatalogEntry;
import com.tkisor.nekojs.core.plugin.TypeDocsRegister;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * RPC 的 TypeScript 声明（插件方式注入）。
 *
 * <h2>为什么需要手写声明</h2>
 *
 * {@code RpcBuilder} 的类型信息只在<b>运行期链式调用</b>里存在：
 * {@code .schema({ msg: 'string' })} 的键与标签都是字符串字面量，反射看到的只有
 * {@code Map<String, String>}。生成的声明因此把 {@code fn} 的回调形参写成
 * {@code any}，脚本侧 {@code fn(({ msg }) => ...)} 里的 {@code msg} 没有类型。
 *
 * <h2>为什么走插件 API 而不是类上的注解</h2>
 *
 * 声明里要引用「本类名」与「一堆外部类型」。注解是纯字符串，生成前拿不到这些，
 * 只能自造占位符语法（{@code {{ classtype }}} / {@code {{ import(...) }}}）让框架替换；
 * 插件是运行期 Java，两者都能直接算——见 {@link #TARGET} 与 {@link #IMPORTS}。
 */
public final class RpcTypes {

    private RpcTypes() {
    }

    /**
     * 声明里引用到的一个外部类型。
     *
     * <p>NekoJS 的注册口只收 FQN，而声明正文里写的是 {@code $Foo}/{@code $Foo_}（TS 引用名，
     * 由 {@link #tsReference} 从 {@code Class} 派生）。两边都在本类内，故这个小结构留在本类——
     * 不必为它开一个公共 API。
     *
     * @param relaxed 用放宽形态 {@code $Foo_} 还是裸类型 {@code $Foo}
     */
    private record ImportSpec(Class<?> type, boolean relaxed) {
        static ImportSpec relaxed(Class<?> type) {
            return new ImportSpec(type, true);
        }

        static ImportSpec plain(Class<?> type) {
            return new ImportSpec(type, false);
        }
    }

    /**
     * 类的 TS 引用名（{@code RpcBuilder → $RpcBuilder}、{@code RpcCollector.Entry → $RpcCollector$Entry}）。
     *
     * <p>内部类用单个 {@code $} 连接——这是生成器实际发射的名字。
     */
    private static String tsReference(Class<?> cls) {
        Class<?> enclosing = cls.getEnclosingClass();
        return "$" + (enclosing == null
                ? cls.getSimpleName()
                : tsReference(enclosing).substring(1) + "$" + cls.getSimpleName());
    }

    /**
     * extra 辅助声明的命名空间名（{@code $RpcBuilder$$$Extra}）。
     *
     * <p>三 {@code $} 是为了避开内部类的单 {@code $} 连接（{@code RpcBuilder$Extra} 会被读成
     * 「RpcBuilder 的内部类 Extra」），不是随手定的。
     */
    private static String extraNamespace(Class<?> cls) {
        return tsReference(cls) + "$$$Extra";
    }

    /**
     * 被替换的类。
     *
     * <p>用 {@code Class} 而非字符串 FQN：FQN 写错<b>不会编译报错</b>，只会让替换静默失效
     * （probe 照常产出，内容却还是反射结果）。下面几个名字全部由它派生。
     */
    private static final Class<?> TARGET = com.anningui.nekoldlib.signal.RpcBuilder.class;

    /** RPC 句柄（{@code fn} 的返回值）——也要手写声明，见 {@link #entryDeclaration()}。 */
    private static final Class<?> ENTRY = com.anningui.nekoldlib.signal.RpcCollector.Entry.class;

    /** 句柄类的 TS 引用名（如 {@code $RpcCollector$Entry}）——由类派生，不硬编码。 */
    private static final String ENTRY_TS = tsReference(ENTRY);

    /**
     * {@code event()} 的返回类型（{@code com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEvent}）。
     *
     * <p>由类派生而非在正文里手写 {@code $RPCEvent}：类型改名时 import 表与正文会**各错各的**
     * （前者影响 import、后者影响引用），改名只改一处不会同时生效。
     */
    private static final Class<?> ENTRY_EVENT = com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEvent.class;

    private static final String ENTRY_EVENT_TS = tsReference(ENTRY_EVENT);

    /** 句柄声明引用的外部类型：与 {@link #ENTRY_EVENT} 同源，不另写一份。 */
    private static final Set<String> ENTRY_IMPORTS = Set.of(ENTRY_EVENT.getName());

    /** 该类的 TS 引用名（如 {@code $RpcBuilder}），声明里实际写出来的那个。 */
    private static final String TS_NAME = tsReference(TARGET);

    /**
     * extra 辅助声明的命名空间（{@code $RpcBuilder$$$Extra}）。
     *
     * <p>由 {@link #extraNamespace} 算——三 {@code $} 是为了避开内部类的单 {@code $}
     * 连接（{@code Outer.Inner → $Outer$Inner}），不是随手定的。
     */
    private static final String NS = extraNamespace(TARGET);

    /**
     * 标签 → 该标签对应的 Java 类型，以及它在声明里用放宽形态还是裸类型。
     *
     * <p><b>唯一事实来源</b>：{@code STypeMap} 的 TS 文本与交给 NekoJS 的 import 集合
     * 都由这一份表渲染 —— 否则同一个类型要在两处各写一遍（{@code IMPORTS} 里写
     * {@code Item.class}、{@code STypeMap} 里写 {@code item: $Item_}），改一处漏一处。
     *
     * <p>放宽/裸靠人工判断：{@code Item}/{@code Block} 这类脚本会传
     * {@code 'minecraft:stone'} 简写，要写 {@code $Item_}；{@code AABB}/{@code ChunkPos}
     * 只能传实例，写裸 {@code $AABB}。标错<b>没有兜底</b>——只会在产物里留一个悬空名字。
     *
     * <p>标签名须与 {@link com.anningui.nekoldlib.signal.SignalTypes#byName} 的 case 对应：
     * 两处漂移会让脚本按产物补全写出的标签，在 {@code schema()} 处报「类型无法识别」。
     * 当前靠人工核对——NekoLDLib 没有测试设施，加运行期自检也已在评估后去掉。
     */
    private static final Map<String, ImportSpec> TAGS = tagTable();

    private static Map<String, ImportSpec> tagTable() {
        Map<String, ImportSpec> m = new LinkedHashMap<>();
        // 基础类型：TS 原生，不需要 import
        m.put("bool", null);
        m.put("int", null);
        m.put("long", null);
        m.put("short", null);
        m.put("byte", null);
        m.put("float", null);
        m.put("double", null);
        m.put("char", null);
        m.put("string", null);
        m.put("uuid", null);
        // 接受字符串/注册表项简写：用放宽形态 $Foo_
        m.put("item", ImportSpec.relaxed(Item.class));
        m.put("block", ImportSpec.relaxed(Block.class));
        m.put("entityType", ImportSpec.relaxed(EntityType.class));
        m.put("blockEntityType", ImportSpec.relaxed(BlockEntityType.class));
        m.put("itemStack", ImportSpec.relaxed(ItemStack.class));
        m.put("fluidStack", ImportSpec.relaxed(FluidStack.class));
        m.put("component", ImportSpec.relaxed(Component.class));
        m.put("identifier", ImportSpec.relaxed(Identifier.class));
        m.put("blockState", ImportSpec.relaxed(BlockState.class));
        m.put("blockPos", ImportSpec.relaxed(BlockPos.class));
        m.put("tag", ImportSpec.relaxed(TagKey.class));
        m.put("dyeColor", ImportSpec.relaxed(DyeColor.class));
        // 只能传实例：裸类型 $Foo
        m.put("fluid", ImportSpec.plain(Fluid.class));
        m.put("chunkPos", ImportSpec.plain(ChunkPos.class));
        m.put("aabb", ImportSpec.plain(AABB.class));
        // 无返回。单独一档，与 SignalTypes.byName 无对应（Java 侧由 returns("void") 特判）
        m.put("void", null);
        // 不能用 Map.copyOf/Map.of：它们**拒绝 null 值**，而基础类型的值就是 null
        // （表示"TS 原生、不需要 import"）。实测踩过——静态初始化直接抛 NPE，
        // mod 加载失败且堆栈只显示 ExceptionInInitializerError，排查成本很高。
        return Collections.unmodifiableMap(m);
    }

    /** 基础类型标签在 TS 里的写法（不需要 import）。 */
    private static final Map<String, String> PRIMITIVE_TS = Map.ofEntries(
            Map.entry("bool", "boolean"),
            Map.entry("int", "number"),
            Map.entry("long", "number"),
            Map.entry("short", "number"),
            Map.entry("byte", "number"),
            Map.entry("float", "number"),
            Map.entry("double", "number"),
            Map.entry("char", "string"),
            Map.entry("string", "string"),
            Map.entry("uuid", "string"),
            Map.entry("void", "void"));

    /**
     * 交给 NekoJS 的 import 集合：由 {@link #TAGS} 派生，不含基础类型与 void。
     *
     * <p>给<b>真实类 FQN</b> 即可——NekoJS 发射 import 时会自己查该类有无输入别名，
     * 有则连同 {@code $Foo_} 一起导入（适配器与枚举两套来源都查），所以这里不必
     * 判断该写 {@code Foo} 还是 {@code Foo_}。
     */
    private static final Set<String> IMPORTS = TAGS.values().stream()
            .filter(Objects::nonNull)
            .map(spec -> spec.type().getName())
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 由 {@link #TAGS} 渲染 {@code STypeMap} 的 TS 文本。
     *
     * <p>不再手写那 25 行：手写时同一个类型要在 {@link #TAGS}（交给 NekoJS 收 import）与
     * 这里各写一遍，改一处漏一处；而且 {@code item: $Item_} 的 {@code _} 后缀靠人工判断
     * 哪个类型有适配器别名，手写等于把框架的知识抄了一遍。
     *
     * <p>这里只拼 TS 文本：放宽/裸由 {@link #TAGS} 的 {@code relaxed} 表达。
     */
    private static String renderSTypeMap() {
        StringBuilder sb = new StringBuilder();
        for (var e : TAGS.entrySet()) {
            String tag = e.getKey();
            ImportSpec spec = e.getValue();
            String ts = spec == null
                    ? PRIMITIVE_TS.get(tag)
                    : tsReference(spec.type()) + (spec.relaxed() ? "_" : "");
            // 缩进对齐 text block 的成员层级：`export interface STypeMap {` 在 8 空格处，
            // 故成员用 12 空格。placeholders 替换前先对齐好，replace 后不会错位。
            sb.append("            ").append(tag).append(": ").append(ts).append(";\n");
        }
        return sb.toString().stripTrailing();
    }

    public static void register(TypeDocsRegister registry) {
        registry.registerClassDeclaration(ClassDeclarationCatalogEntry.of(
                TARGET.getName(),
                declaration(),
                IMPORTS,
                "RPC 链式构建器的泛型声明：让 schema/returns/fn 的回调形参可推导",
                List.of(
                        "RPC.create('x:echo').schema({ msg: 'string' }).returns('string').fn(({ msg }) => msg)",
                        "RPC.create('x:ping').schema({ n: 'int' }).returns('void').fn(({ n }) => console.info(n))")));

        // 句柄也要走手写声明：反射产物把 send 写成 `send(arg0: { [key: string]: any })`，
        // 与 schema 完全脱节。泛型化后 send 的参数类型由 fn 的返回值带过来——
        // 见下面 entryDeclaration() 的说明。
        registry.registerClassDeclaration(ClassDeclarationCatalogEntry.of(
                ENTRY.getName(),
                entryDeclaration(),
                ENTRY_IMPORTS,
                "RPC 句柄的泛型声明：send 的参数类型由 schema 决定",
                List.of("echo.send({ msg: 'hi' })   // 键名与类型都对齐 schema")));
    }

    /**
     * {@code $RpcCollector$Entry<T>} 的手写声明。
     *
     * <p>{@code T} 是 <b>schema 本身</b>（如 {@code { msg: 'string' }}），不是展开后的参数类型——
     * 因为脚本调 {@code send({ msg: 'hi' })} 传的是原始字面量形状，键名要与 schema 一致。
     * 故 {@code send} 收的是 {@code Record<T>} 形状的映射类型（把标签展开成真实类型）。
     *
     * <p>泛型参数由 {@code $RpcBuilder.fn} 的返回值带上（{@code fn(...): $RpcCollector$Entry<T>}），
     * 所以链式写完后 {@code echo.send} 的补全与校验都是对的。
     */
    private static String entryDeclaration() {
        return """
export class @ENTRY@<T extends Record<string, @NS@.SType> = Record<string, @NS@.SType>> {
    event(): @ENTRY_EVENT@;
    id(): string;

    /** 发到对端。键名与类型须与声明时的 schema 一致。 */
    send(args: @NS@.ArgsOf<T>): boolean;

    /** 无参 RPC 的快捷形式。 */
    send(): boolean;
}
"""
                .replace("@NS@", NS)
                .replace("@ENTRY@", ENTRY_TS)
                .replace("@ENTRY_EVENT@", ENTRY_EVENT_TS);
    }

    /**
     * 完整声明文本。
     *
     * <p>{@code @NS@} / {@code @CLS@} 用 {@link String#replace} 而非
     * {@code formatted()}：文本里已有大量 {@code %} 无关字符，按位置传参会错位。
     */
    private static String declaration() {
        return """
export namespace @NS@ {
    /** RPC 支持的类型标签。与 SignalTypes.byName 的 case 一一对应。 */
    export type SType =
        | "bool" | "int" | "long" | "short" | "byte"
        | "float" | "double" | "char" | "string"
        | "item" | "block" | "fluid" | "entityType" | "blockEntityType"
        | "itemStack" | "fluidStack" | "component" | "identifier" | "uuid"
        | "blockState" | "blockPos" | "chunkPos" | "aabb" | "tag" | "dyeColor"
        /** 无返回。调 .returns('void') 才好过 fn 的门禁。 */
        | "void";

    /**
     * 标签 → 脚本可传的类型。
     *
     * 用的是 NekoJS 的输入别名（`_` 后缀）而非裸类型：脚本写
     * `.schema({ b: 'block' })` 后传的是 `"minecraft:stone"` 这类松散值，
     * 而 `$Block_ = $Block | RegistryTypes.Block | $Item | $ItemStack | $NekoId`
     * 正是为接受这种写法而生成的。裸 `$Block` 会拒绝字符串。
     */
    export interface STypeMap {
@STypeMap@
    }

    export type Of<S extends SType> = STypeMap[S];
    export type OfReturn<R> = R extends SType ? STypeMap[R] : void;
    export type ArgsOf<T extends Record<string, SType>> = { [K in keyof T]: Of<T[K]> };
}

export class @CLS@<
    T extends Record<string, @NS@.SType> = {},
    R extends @NS@.SType | undefined = undefined,
> {
    schema<const S extends { [K in keyof S]: @NS@.SType }>(
        this: @CLS@,
        sch: S,
    ): @CLS@<S>;

    returns<Ret extends @NS@.SType>(
        this: @CLS@<T, undefined>,
        ret: Ret,
    ): @CLS@<T, Ret>;

    fn(
        this: R extends @NS@.SType ? @CLS@<T, R> : never,
        impl: (arg: @NS@.ArgsOf<T>) => @NS@.OfReturn<R>,
    ): @ENTRY@<T>;
}
"""
                .replace("@NS@", NS)
                .replace("@STypeMap@", renderSTypeMap())
                .replace("@CLS@", TS_NAME)
                .replace("@ENTRY@", ENTRY_TS);
    }
}
