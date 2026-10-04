package com.tkisor.nekoldlib.signal;

import com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEventBuilder;
import com.tkisor.nekojs.api.annotation.NekoProbe;
import com.tkisor.nekoldlib.NekoLDLib;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链式声明 RPC 的构建器：{@code RPC.create(id).schema({...}).returns('...').fn(arg => ...)}。
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // 有参数、有返回
 * const echo = RPC.create('mypack:echo')
 *   .schema({ msg: 'string' })
 *   .returns('string')
 *   .fn(({ msg }) => '应答:' + msg)
 *
 * echo.send({ msg: 'hi' })          // 注意：send 也收对象，键名与 schema 对应
 *
 * // 无返回：写 returns('void')（省略会让声明层的 fn 门禁拒掉，见 @NekoProbe 的 this）
 * const ping = RPC.create('mypack:ping')
 *   .schema({ n: 'int' })
 *   .returns('void')
 *   .fn(({ n }) => console.info(n))
 * }</pre>
 *
 * <h2>为什么 schema 是对象而不是逐个 {@code .str().i32()}</h2>
 *
 * 早先是「每个类型一个同名方法」（{@code .str()} / {@code .i32()} / {@code .retStr()}）。
 * 那样声明层无法推导 lambda 形参：Java 泛型运行期擦除，probe 只能把 {@code fn} 写成
 * {@code (arg0: ((...args: any[]) => any) | $RpcImpl)}，于是 {@code msg} 是 {@code any}。
 *
 * <p>改成「一次传入 schema 对象」后，键名与类型标签都成了<b>字面量</b>，
 * 声明层可以用类型体操（{@code const} 泛型参数 + 映射类型）推出
 * {@code (arg: { msg: string }) => string}，形参不再是 {@code any}。
 *
 * <h2>键名与传输顺序</h2>
 *
 * 底层 LDLib2 的 {@code RPCEvent} 是<b>位置传输</b>（{@code SyncValueHolder("arg0", ...)}），
 * 键名不进网络。这里用 {@link LinkedHashMap} 保住序列化顺序，按<b>传入顺序</b>对应位置参数：
 * 脚本写 {@code { msg: 'string', n: 'int' }}，对端 {@code fn} 收到的对象里
 * {@code msg} 在 {@code n} 前。两端 schema 顺序必须一致。
 *
 * <h2>参数顺序</h2>
 *
 * {@code schema} → {@code returns}（可选）→ {@code fn}，{@code fn} 必须是最后一个。
 */
@NekoProbe(
        extra = """
                /** RPC 支持的类型标签。与 SignalTypes.byName 的 case 一一对应。 */
                export type SType =
                    | "bool" | "int" | "long" | "short" | "byte"
                    | "float" | "double" | "char" | "string"
                    | "item" | "block" | "fluid" | "entityType" | "blockEntityType"
                    | "itemStack" | "fluidStack" | "component" | "identifier" | "uuid"
                    | "blockState" | "blockPos" | "chunkPos" | "aabb" | "tag" | "dyeColor"
                    /** 无返回。调 .returns('void') 才好过 fn 的门禁 —— 见 fn 的 this。 */
                    | "void";

                /**
                 * 标签 → 脚本可传的类型。
                 *
                 * <p>用的是 NekoJS 的<b>输入别名</b>（`_` 后缀）而非裸类型：脚本写
                 * `.schema({ b: 'block' })` 后传的是 `"minecraft:stone"` 这类松散值，
                 * 而 `$Block_ = $Block | RegistryTypes.Block | $Item | $ItemStack | $NekoId`
                 * 正是为接受这种写法而生成的。裸 `$Block` 会拒绝字符串。
                 */
                export interface STypeMap {
                    bool: boolean;
                    int: number;
                    long: number;
                    short: number;
                    byte: number;
                    float: number;
                    double: number;
                    char: string;
                    string: string;
                    item: {{ import(net.minecraft.world.item.Item_) }};
                    block: {{ import(net.minecraft.world.level.block.Block_) }};
                    fluid: {{ import(net.minecraft.world.level.material.Fluid) }};
                    entityType: {{ import(net.minecraft.world.entity.EntityType_) }};
                    blockEntityType: {{ import(net.minecraft.world.level.block.entity.BlockEntityType_) }};
                    itemStack: {{ import(net.minecraft.world.item.ItemStack_) }};
                    fluidStack: {{ import(net.neoforged.neoforge.fluids.FluidStack_) }};
                    component: {{ import(net.minecraft.network.chat.Component_) }};
                    identifier: {{ import(net.minecraft.resources.Identifier_) }};
                    uuid: string;
                    blockState: {{ import(net.minecraft.world.level.block.state.BlockState_) }};
                    blockPos: {{ import(net.minecraft.core.BlockPos_) }};
                    chunkPos: {{ import(net.minecraft.world.level.ChunkPos) }};
                    aabb: {{ import(net.minecraft.world.phys.AABB) }};
                    tag: {{ import(net.minecraft.tags.TagKey_) }};
                    dyeColor: {{ import(net.minecraft.world.item.DyeColor_) }};
                    void: void;
                }

                export type Of<S extends SType> = STypeMap[S];
                export type OfReturn<R> = R extends SType ? STypeMap[R] : void;
                export type ArgsOf<T extends Record<string, SType>> = { [K in keyof T]: Of<T[K]> };
                """,
        type = """
                export class {{ classtype }}<
                    T extends Record<string, {{ extra.SType }}> = {},
                    R extends {{ extra.SType }} | undefined = undefined,
                > {
                    schema<const S extends { [K in keyof S]: {{ extra.SType }} }>(
                        this: {{ classtype }},
                        sch: S,
                    ): {{ classtype }}<S>;

                    returns<Ret extends {{ extra.SType }}>(
                        this: {{ classtype }}<T, undefined>,
                        ret: Ret,
                    ): {{ classtype }}<T, Ret>;

                    fn(
                        this: R extends {{ extra.SType }} ? {{ classtype }}<T, R> : never,
                        impl: (arg: {{ extra.ArgsOf }}<T>) => {{ extra.OfReturn }}<R>,
                    ): {{ import(com.tkisor.nekoldlib.signal.RpcCollector$Entry) }};
                }
                """)
public final class RpcBuilder {

    private final String id;
    /** 参数名 → 类型标签，按传入顺序（决定位置参数次序）。 */
    private final Map<String, String> schema = new LinkedHashMap<>();
    @Nullable
    private String returnName;

    RpcBuilder(String id) {
        this.id = id;
    }

    /**
     * 声明入参：键是形参名，值是类型标签（见 {@link SignalTypes#knownNames()}）。
     *
     * <p>类型标签在<b>这一步</b>就交给 {@link SignalTypes} 校验，而不是拖到 {@code fn}。
     * 链式写法最容易错的就是类型，早失败才能把错误定位到出错的那一行。
     *
     * @throws IllegalArgumentException 类型标签无法识别时
     */
    public RpcBuilder schema(Map<String, String> spec) {
        if (spec == null) {
            throw new IllegalArgumentException("RPC " + id + " 的 schema 不能为 null（无参数请传空对象 {}）");
        }
        for (var entry : spec.entrySet()) {
            var name = entry.getKey();
            var typeName = entry.getValue();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("RPC " + id + " 的 schema 里有空参数名");
            }
            if (typeName == null || SignalTypes.byName(typeName) == null) {
                throw new IllegalArgumentException("RPC " + id + " 的参数 '" + name
                        + "' 类型 '" + typeName + "' 无法识别。可用：" + SignalTypes.knownNames());
            }
            schema.put(name, typeName);
        }
        return this;
    }

    /**
     * 声明返回类型。不调用即为无返回。
     *
     * @throws IllegalArgumentException 类型标签无法识别时
     */
    public RpcBuilder returns(String typeName) {
        // 'void' = 显式声明无返回。与「不调 returns」等价，但能让声明层的
        // fn 门禁（this: R extends SType ? ... : never）通过——见 RpcBuilder 的 @NekoProbe。
        if ("void".equalsIgnoreCase(typeName)) {
            returnName = null;
            return this;
        }
        if (typeName == null || SignalTypes.byName(typeName) == null) {
            throw new IllegalArgumentException("RPC " + id + " 的返回类型 '"
                    + typeName + "' 无法识别。可用：void, " + SignalTypes.knownNames());
        }
        returnName = typeName;
        return this;
    }

    /**
     * 链的终点：给出收到对端调用时执行的函数，并返回句柄。
     *
     * <p>回调收<b>单个对象</b>，键名与 {@link #schema} 一致；返回值按 {@link #returns} 声明的类型。
     *
     * <p>句柄随界面挂载才可用（RPC 是 per-界面的）；界面关闭后 {@code send} 返回 false。
     */
    public RpcCollector.Entry fn(RpcCallbacks.RpcImpl impl) {
        var argTypes = new ArrayList<Type>(schema.size());
        for (var typeName : schema.values()) {
            argTypes.add(SignalTypes.byName(typeName));
        }
        Type returnType = returnName == null ? null : SignalTypes.byName(returnName);
        var keys = List.copyOf(schema.keySet());

        var builder = RPCEventBuilder.create();
        for (var type : argTypes) {
            builder.args(type);
        }
        if (returnType != null) {
            builder.returnType(returnType);
        }
        builder.executor(argv -> Rpc.invokeImpl(id, impl, argv, keys));

        var entry = RpcCollector.declare(id, builder.build(), keys);
        NekoLDLib.LOGGER.info("[NekoLDLib] 声明 RPC {}（{} 参数{}），待随界面挂载",
                id, argTypes.size(), returnType == null ? "，无返回" : "，有返回");
        return entry;
    }

    /** 供诊断/日志用：参数名列表。 */
    public List<String> argNames() {
        return List.copyOf(schema.keySet());
    }
}
