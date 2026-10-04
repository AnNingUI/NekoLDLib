package com.tkisor.nekoldlib.jsx;

import graal.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Graal 值访问的集中适配层。
 *
 * <p>渲染器只通过本接口碰 JS 对象，好处有二：
 * <ul>
 *   <li>Graal 的包名（{@code graal.graalvm.polyglot}，是 NekoJS 的 shading）只出现在一个文件里，
 *       上游改包名时改动面最小。</li>
 *   <li>NekoJS 的 {@code JSTypeAdapter} 只服务于"参数从 JS 转进 Java"，并不覆盖
 *       "Java 主动读 JS 对象"这个方向。这层补上了缺的那一半。</li>
 * </ul>
 *
 * <p>阶段 1 用 Graal {@link Value} 的反射式访问（{@code getMember} / {@code getArrayElement}）。
 * 已保留 vnode 上的 {@code __nekoLdlibHost} 约定，若后续发现 Java 直接调 {@code Value} 的
 * 成员方法不稳，可以换成 JS 侧装一个窄 host 对象、由 Java 调用它——届时只改本类的实现。
 */
public interface JsxHost {

    /** 读成员；不存在时返回 {@code null}。 */
    @Nullable
    Value member(Value object, String name);

    /** 对象自身的可枚举键，按插入顺序。 */
    List<String> keys(Value object);

    /** 是否是可迭代的数组/可迭代对象。 */
    boolean isArray(Value value);

    /** 按顺序展开可迭代值；不可迭代时返回空列表。 */
    List<Value> iterable(Value value);

    /**
     * 调用一个 JS 函数（函数组件）。
     *
     * @param fn   可执行值
     * @param arg  单个入参；{@code null} 表示无参调用
     * @return 返回值，可能是 {@code null} Value
     */
    @Nullable
    Value call(Value fn, @Nullable Value arg);

    /** 是否是 JSX 运行时产出的 vnode。 */
    boolean isElement(Value value);

    /**
     * vnode 的标签/组件。
     *
     * <p>两种 JSX runtime 用不同的键，这里统一：
     * <ul>
     *   <li>automatic（{@code jsxAutomaticRuntime=true}）—— {@code type}</li>
     *   <li>classic（NekoJS 默认）—— {@code tag}</li>
     * </ul>
     */
    @Nullable
    Value typeOf(Value vnode);

    /** vnode 的属性对象（两种 runtime 都是 {@code props}）。 */
    @Nullable
    Value propsOf(Value vnode);

    /** vnode 的子节点（两种 runtime 都是顶层 {@code children}）。 */
    @Nullable
    Value childrenOf(Value vnode);

    /** 是否是 Fragment 标记。 */
    boolean isFragment(@Nullable Value type);

    /** 把字符串/数组/其它值统一成字符串列表（供 class 与 style 声明使用）。 */
    List<String> stringList(Value value);

    /**
     * 把 JS 数字规范成 LSS 能解析的字符串。
     *
     * <p>LDLib2 的 {@code ValueParser.parse} 只接受 {@link String}，而 {@code lss()} 内部用
     * {@code rawValue.toString()}。Java 侧数字（Double）的 {@code toString()} 会产出
     * 科学计数法或 {@code "4.0"}，解析可能失败——所以这里统一剥掉整数值的小数部分。
     */
    static String numericToLss(Value value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return Long.toString(value.asLong());
            }
            return trimTrailingZeros(Double.toString(value.asDouble()));
        }
        return value.asString();
    }

    private static String trimTrailingZeros(String number) {
        if (number.indexOf('.') < 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
            return number;
        }
        int end = number.length();
        while (end > 0 && number.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && number.charAt(end - 1) == '.') {
            end--;
        }
        return number.substring(0, end);
    }
}
