package com.anningui.nekoldlib.signal;

/**
 * RPC 相关脚本回调。
 *
 * <h2>为什么是函数式接口而不是收 {@code Value}</h2>
 *
 * 收 {@code Value} 时生成的类型声明是 {@code $Value}——看不出该传什么。Graal 能把
 * JS 箭头函数代理成本文件的接口，声明随之具体化（可跳转、有参数类型）。
 */
public final class RpcCallbacks {

    private RpcCallbacks() {
    }

    /**
     * RPC 实现：收到对端调用时执行。
     *
     * <p>收<b>单个对象</b>而非变参：键名来自 {@code schema} 的键，{@link Rpc#invokeImpl}
     * 会把传输层的位置参数重新装成对象再交给脚本。这样脚本写
     * {@code ({ msg, n }) => ...} 与声明一一对应，也不用记位置顺序。
     *
     * <p>参数类型是 {@code Object}：Graal 侧真正看到的是宿主对象，
     * 具体类型由声明层的类型体操（{@code schema} 的字面量标签）表达——
     * Java 泛型运行期擦除，这里给不出更精确的类型。
     */
    @FunctionalInterface
    public interface RpcImpl {
        /**
         * @param arg 按 {@code schema} 键名装好的参数对象；无参数时是空对象
         * @return 返回值；声明了 {@code returns} 时应返回对应类型，否则可为 null
         */
        Object invoke(Object arg);
    }
}
