package com.anningui.nekoldlib.signal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IBindable;
import com.tkisor.nekojs.api.JSTypeAdapter;
import com.anningui.nekoldlib.NekoLDLib;
import graal.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * 绑定值的类型解析与 JS ↔ Java 转换。
 *
 * <h2>为什么类型由控件决定，而不是让脚本写</h2>
 *
 * LDLib2 里每个可绑定控件的值类型是固定的：
 * <pre>
 *   Label        -> Component（不是 String！）
 *   Switch/Toggle-> Boolean
 *   Slider       -> Float
 *   ProgressBar  -> Float
 *   TextField    -> String
 *   Selector     -> T（泛型，运行期被擦除）
 * </pre>
 *
 * 所以让脚本自由指定 {@code type} 只会制造不一致——{@code type:'string'} 绑到 Label 上
 * 必然失败。这里改为从元素的 {@link IBindable} 实现反射推断，脚本只需写 get/set。
 * 仅当泛型被擦除（{@code Selector<T>} 的实例不带类型参数）时才要求显式指定。
 */
public final class SignalTypes {

    private SignalTypes() {
    }

    /** 推断结果。{@code type} 为 null 表示泛型擦除、必须由调用方显式给出。 */
    public record Resolved(@Nullable Type type, @Nullable String erasedReason) {
        public boolean needsExplicit() {
            return type == null;
        }
    }

    /**
     * 从元素类推断它 {@code IBindable<T>} 的 {@code T}。
     *
     * <p>必须做<b>类型变量替换</b>——继承链上每一层的实参都要代进去，否则会撞到裸 {@code T}。
     * 例如：
     * <pre>
     *   Switch   extends BindableUIElement&lt;Boolean&gt;      -> Boolean
     *   Slider.Horizontal extends Slider extends BindableUIElement&lt;Float&gt;  -> Float
     *   Label    extends TextElement implements IBindable&lt;Component&gt;       -> Component
     *   Selector&lt;T&gt; extends BindableUIElement&lt;T&gt;          -> 仍是 T，判定为擦除
     * </pre>
     */
    public static Resolved resolve(Class<?> elementClass) {
        var found = findBindableArg(elementClass, new java.util.HashMap<>());
        if (found == null) {
            return new Resolved(null, elementClass.getSimpleName() + " 不是可绑定控件");
        }
        // 替换后仍是类型变量 => 泛型被擦除（如 Selector<T> 的裸实例）
        if (found instanceof TypeVariable<?> variable) {
            return new Resolved(null, elementClass.getSimpleName() + " 的值类型是泛型 "
                    + variable.getName() + "，运行期被擦除");
        }
        return new Resolved(found, null);
    }

    /**
     * 沿继承链找到 {@code IBindable} 的实参，同时把类型变量替换成实际类型。
     *
     * @param type     当前遍历到的类型（可能是 {@code BindableUIElement<Boolean>} 这样的参数化类型）
     * @param bindings 累积的类型变量绑定，例如 {@code T -> Boolean}
     */
    @Nullable
    private static Type findBindableArg(Type type, Map<TypeVariable<?>, Type> bindings) {
        var raw = rawTypeOf(type);
        if (raw == null) {
            return null;
        }

        // 把本层自己的参数化信息并入绑定表
        var local = new HashMap<>(bindings);
        if (type instanceof ParameterizedType pt) {
            var vars = raw.getTypeParameters();
            var args = pt.getActualTypeArguments();
            for (int i = 0; i < vars.length && i < args.length; i++) {
                local.put(vars[i], substitute(args[i], bindings));
            }
        }

        for (var iface : raw.getGenericInterfaces()) {
            var ifaceRaw = rawTypeOf(iface);
            if (ifaceRaw == null) {
                continue;
            }
            if (IBindable.class.equals(ifaceRaw)) {
                if (iface instanceof ParameterizedType pt) {
                    return substitute(pt.getActualTypeArguments()[0], local);
                }
                return null; // 裸用 IBindable，无语型信息
            }
            var nested = findBindableArg(iface, local);
            if (nested != null) {
                return nested;
            }
        }

        var superType = raw.getGenericSuperclass();
        return superType == null ? null : findBindableArg(superType, local);
    }

    /** 若 {@code type} 是已绑定的类型变量则替换；{@code null} 表示未绑定（擦除）。 */
    @Nullable
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) {
            return bindings.get(variable);
        }
        return type;
    }

    @Nullable
    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 值转换
    // ------------------------------------------------------------------

    /**
     * 把 JS 值转成绑定的目标类型。
     *
     * <p>基础类型与 {@link String} 直接走 Graal 的内建转换；其余（{@code ItemStack}、
     * {@code Component} 等）交给 NekoJS 注册的类型适配器——它们本来就是为"JS 值 →
     * Java 对象"这个方向准备的（{@code JSTypeAdapter extends Predicate<Value>, Function<Value,T>}）。
     *
     * @throws IllegalArgumentException 无法转换时抛出，并说明目标类型与收到的 JS 类型
     */
    public static Object toJava(Value jsValue, Class<?> target) {
        if (jsValue == null || jsValue.isNull()) {
            return null;
        }
        // 已经是目标类型（脚本直接传了 Java 对象，例如从事件里拿到的实例）
        if (jsValue.isHostObject() && target.isInstance(jsValue.asHostObject())) {
            return jsValue.asHostObject();
        }

        if (target == Boolean.class || target == boolean.class) {
            return jsValue.asBoolean();
        }
        if (target == Float.class || target == float.class) {
            return (float) jsValue.asDouble();
        }
        if (target == Double.class || target == double.class) {
            return jsValue.asDouble();
        }
        if (target == Integer.class || target == int.class) {
            return jsValue.asInt();
        }
        if (target == Long.class || target == long.class) {
            return jsValue.asLong();
        }
        if (target == Short.class || target == short.class) {
            return (short) jsValue.asInt();
        }
        if (target == Byte.class || target == byte.class) {
            return (byte) jsValue.asInt();
        }
        if (target == Character.class || target == char.class) {
            var s = jsValue.asString();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("无法把空字符串转成 char");
            }
            return s.charAt(0);
        }
        if (target == String.class) {
            return jsValue.asString();
        }
        // Component 显式处理，不走适配器：Label 的值类型就是它，而脚本里绝大多数情况
        // 传的是普通字符串。用 literal 而不是 translatable —— 脚本写的中文/任意文本
        // 不该被当作翻译键（TextElement.setText(String) 的默认行为就是这个坑）。
        if (target == net.minecraft.network.chat.Component.class) {
            if (jsValue.isString()) {
                return net.minecraft.network.chat.Component.literal(jsValue.asString());
            }
            if (jsValue.isNumber() || jsValue.isBoolean()) {
                return net.minecraft.network.chat.Component.literal(jsValue.toString());
            }
        }

        // 其余类型走 NekoJS 适配器
        var converted = tryAdapters(jsValue, target);
        if (converted != null) {
            return converted;
        }

        throw new IllegalArgumentException(
                "无法把 JS 的 " + describe(jsValue) + " 转成 " + target.getSimpleName()
                        + "。若是对象/字符串简写，请确认 NekoJS 已为该类型注册适配器。");
    }

    @Nullable
    private static Object tryAdapters(Value jsValue, Class<?> target) {
        var registry = NekoLDLib.adapters();
        if (registry == null) {
            return null;
        }
        for (var adapter : registry.view()) {
            if (!target.isAssignableFrom(adapter.getTargetClass())) {
                continue;
            }
            if (adapter.test(jsValue)) {
                return adapter.apply(jsValue);
            }
        }
        return null;
    }

    private static String describe(Value v) {
        if (v.isString()) return "字符串 \"" + v.asString() + "\"";
        if (v.isNumber()) return "数字 " + v;
        if (v.isBoolean()) return "布尔 " + v.asBoolean();
        if (v.hasArrayElements()) return "数组";
        if (v.hasMembers()) return "对象";
        return "值 " + v;
    }

    /** 供错误信息使用：把反射 Type 转成可读名字。 */
    public static String displayName(@Nullable Type type) {
        if (type instanceof Class<?> c) {
            return c.getSimpleName();
        }
        if (type instanceof ParameterizedType pt) {
            return displayName(pt.getRawType());
        }
        return String.valueOf(type);
    }

    /**
     * 类型名 → Java 类。
     *
     * <p>清单<b>以 LDLib2 同步层实际支持的 accessor 为准</b>
     * （见 {@code AccessorRegistries}）：名字能解析但底层没有 accessor 的类型，
     * 只会在运行时序列化阶段失败——那是很差的报错位置。
     *
     * <p>几个容易混的点：
     * <ul>
     *   <li>{@code item} 是注册表项 {@link net.minecraft.world.item.Item}，
     *       带数量的堆是 {@code itemstack}——两者不可互换。</li>
     *   <li>同理 {@code fluid} 是 {@link net.minecraft.world.level.material.Fluid}，
     *       带数量的堆是 {@code fluidstack}。</li>
     *   <li><b>没有 {@code entity}</b>：LDLib2 只支持 {@code entitytype}（种类）。
     *       要传"某一只具体的生物"请传 {@code uuid}——两端各自按 id 查自己的实体。</li>
     * </ul>
     */
    @Nullable
    public static Class<?> byName(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            // ---- 基础类型 ----
            case "bool", "boolean" -> Boolean.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "int", "integer" -> Integer.class;
            case "long" -> Long.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "char", "character" -> Character.class;
            case "string", "str", "text" -> String.class;

            // ---- 注册表类型（引用，不含数据） ----
            case "item" -> net.minecraft.world.item.Item.class;
            case "block" -> net.minecraft.world.level.block.Block.class;
            case "fluid" -> net.minecraft.world.level.material.Fluid.class;
            case "entitytype", "entity_type" -> net.minecraft.world.entity.EntityType.class;
            case "blockentitytype", "block_entity_type" ->
                    net.minecraft.world.level.block.entity.BlockEntityType.class;

            // ---- 带数据的堆 / 复合类型 ----
            case "itemstack", "item_stack" -> net.minecraft.world.item.ItemStack.class;
            case "fluidstack", "fluid_stack" -> net.neoforged.neoforge.fluids.FluidStack.class;
            case "component", "text_component" -> net.minecraft.network.chat.Component.class;
            case "identifier", "resource_location" -> net.minecraft.resources.Identifier.class;
            case "uuid" -> java.util.UUID.class;
            case "blockstate", "block_state" -> net.minecraft.world.level.block.state.BlockState.class;
            case "blockpos", "block_pos" -> net.minecraft.core.BlockPos.class;
            case "chunkpos", "chunk_pos" -> net.minecraft.world.level.ChunkPos.class;
            case "aabb" -> net.minecraft.world.phys.AABB.class;
            case "tag", "tagkey" -> net.minecraft.tags.TagKey.class;

            // ---- 染料 / 颜色 ----
            case "dyecolor", "dye_color" -> net.minecraft.world.item.DyeColor.class;

            default -> null;
        };
    }

    /** 所有可用的显式类型名，用于报错提示。 */
    public static String knownNames() {
        return "bool, float, double, int, long, short, byte, char, string | "
                + "item, block, fluid, entitytype, blockentitytype | "
                + "itemstack, fluidstack, component, identifier, uuid, blockstate, "
                + "blockpos, chunkpos, aabb, tag, dyecolor";
    }
}
