package com.tkisor.nekoldlib.signal;

import com.tkisor.nekojs.api.data.BindingRegistry;
import com.tkisor.nekoldlib.NekoLDLib;
import graal.graalvm.polyglot.proxy.ProxyObject;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本侧的 RPC 入口（{@code RPC} 全局）。
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // 声明（在界面工厂内）
 * const echo = RPC.create('mypack:echo')
 *   .schema({ msg: 'string', n: 'int' })
 *   .returns('bool')
 *   .fn(({ msg, n }) => n > 0)
 *
 * // 使用：send 也收对象，键名与 schema 对应
 * echo.send({ msg: 'hi', n: 3 })
 * }</pre>
 *
 * <h2>作用域</h2>
 *
 * RPC 是 <b>per-界面</b> 的：LDLib2 把 {@code RPCEvent} 注册到具体 {@code ModularUI}
 * 的 syncManager 上。声明进待挂载队列，随下一次渲染挂到根元素；界面关闭后句柄失效
 * （{@code send} 返回 false 而不是抛错）。
 *
 * <h2>方向</h2>
 *
 * {@code send()} 发到<b>对端</b>：服务端侧的句柄发给客户端，客户端侧的发给服务端。
 * impl 在<b>接收方</b>执行。所以同一个 id 在两端各自声明、各自实现，天然形成双向通道。
 *
 * <h2>为什么是链式 + schema 对象</h2>
 *
 * 早先的 {@code define(id, { args: [...], returns: '...' }, impl)} 里 {@code spec} 是
 * <b>值</b>而非类型，TS 无从推导 lambda 的形参类型。
 *
 * <p>改为链式后，类型信息放在<b>实际参数</b>上——{@code 'string'} 是字符串字面量，
 * 可当类型用。而 {@code schema} 用<b>对象</b>而非逐个 {@code .str().i32()}，
 * 是为了让<b>键名也是字面量</b>：声明层可用 {@code const} 泛型参数 + 映射类型
 * 把 {@code { msg: 'string' }} 推成 {@code { msg: string }}，
 * 于是 {@code fn(({msg}) => ...)} 里的 {@code msg} 有确定的类型而不是 {@code any}。
 */
public final class Rpc {

    /** 无参数 RPC 传给脚本的空对象（复用同一实例，避免每次分配）。 */
    private static final Object EMPTY_OBJECT = ProxyObject.fromMap(Map.of());

    private Rpc() {
    }

    public static void install(BindingRegistry registry) {
        registry.register(registry.scriptType(), "RPC", new Rpc());
    }

    /**
     * 链式声明 RPC 的起点。
     *
     * @param id 形如 {@code "mypack:echo"}；重复声明同一 id 时后者覆盖，便于热重载
     */
    public RpcBuilder create(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RPC id 不能为空");
        }
        return new RpcBuilder(id);
    }

    /**
     * 调用脚本实现。
     *
     * <p>传输层是位置参数（{@code RPCEvent} 的 {@code arg0}/{@code arg1}…），
     * 而脚本侧的 {@code fn} 收的是<b>单个对象</b>（键名来自 schema）。
     * 这里按 {@code keys} 把位置数组重新装成对象再交给脚本，键名因此有了运行时意义。
     *
     * @param keys 参数名，顺序与 {@code argv} 一一对应（来自 {@code RpcBuilder.schema}）
     */
    @Nullable
    static Object invokeImpl(String id, RpcCallbacks.RpcImpl impl, Object[] argv, List<String> keys) {
        Object arg;
        try {
            arg = keys.isEmpty() ? EMPTY_OBJECT : ProxyObject.fromMap(toKeyedMap(keys, argv));
        } catch (RuntimeException e) {
            // keys 与 argv 长度不一致属于内部错误（两端 schema 不一致时会先在传输层失败）
            NekoLDLib.LOGGER.error("[NekoLDLib] RPC {} 的参数名与实参数量不匹配（{} 名 vs {} 值），已按空对象调用",
                    id, keys.size(), argv == null ? 0 : argv.length, e);
            arg = EMPTY_OBJECT;
        }
        try {
            return impl.invoke(arg);
        } catch (Exception e) {
            NekoLDLib.LOGGER.error("[NekoLDLib] RPC {} 的实现抛错", id, e);
            return null;
        }
    }

    /** 位置实参 → 键名对象；多出的实参忽略，缺失的置 null。 */
    private static Map<String, Object> toKeyedMap(List<String> keys, @Nullable Object[] argv) {
        var map = new LinkedHashMap<String, Object>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            map.put(keys.get(i), argv != null && i < argv.length ? argv[i] : null);
        }
        return map;
    }
}
