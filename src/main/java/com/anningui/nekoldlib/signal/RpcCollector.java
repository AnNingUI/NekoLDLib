package com.anningui.nekoldlib.signal;

import com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEvent;
import com.anningui.nekoldlib.NekoLDLib;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 已声明的 RPC 及其待挂载队列。
 *
 * <h2>为什么是"累积 + 抽取"而不是"渲染期上下文"</h2>
 *
 * 最初的设计是：渲染开始时记录一个 ThreadLocal 收集器，{@code RPC.define} 往里写。
 * <b>这是错的</b>，因为声明发生的时间早于渲染：
 *
 * <pre>
 * UI.open(&lt;Panel/&gt;)          —— JSX 在调用 open 之前就求值完了（参数先算）
 * factory.execute(player)     —— PlayerUIRegistry 先调工厂，工厂里就有 define
 * render(tree)                —— 渲染最后才发生
 * </pre>
 *
 * <p>所以改成：{@code define} 把条目放进<b>本线程的待挂载队列</b>，渲染时抽取并挂到
 * 根元素上。JSX 求值与渲染在同一线程上紧邻发生，抽取的时机因此是确定的。
 *
 * <p>队列按线程隔离：脚本可能在不同线程上构建界面（加载期 worker、客户端线程、
 * 服务端线程）。
 */
public final class RpcCollector {

    /** 一个已声明的 RPC。{@code emitter} 在根元素挂上 ModularUI 后才可用。 */
    public static final class Entry {
        private final String id;
        private final RPCEvent event;
        /** 参数名，决定 {@link #send} 收对象时的取值顺序（与 schema 一致）。 */
        private final List<String> keys;
        @Nullable
        private volatile com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEmitter emitter;

        Entry(String id, RPCEvent event, List<String> keys) {
            this.id = id;
            this.event = event;
            this.keys = List.copyOf(keys);
        }

        public String id() {
            return id;
        }

        public RPCEvent event() {
            return event;
        }

        void attach(com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEmitter holder) {
            this.emitter = holder;
        }

        /**
         * 发到对端。参数是<b>对象</b>，键名与声明时的 {@code schema} 一致。
         *
         * <pre>{@code
         * RPC.create('mypack:echo').schema({ msg: 'string' }).fn(({msg}) => ...)
         * echo.send({ msg: 'hi' })
         * }</pre>
         *
         * <p>传输层是位置参数，这里按键名顺序取出值再发——所以<b>两端 schema 顺序必须一致</b>。
         *
         * @return 是否真的发出去了。界面未挂载/未打开时为 false——这比抛异常友好，
         *         因为脚本可能在界面关闭后仍持有句柄。
         * @throws IllegalArgumentException 传入的不是对象、或缺了某个键
         */
        public boolean send(Map<String, ?> args) {
            var e = emitter;
            if (e == null) {
                return false;
            }
            var argv = new Object[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                var key = keys.get(i);
                if (args == null || !args.containsKey(key)) {
                    throw new IllegalArgumentException("RPC " + id + " 的 send 缺少参数 '" + key
                            + "'（schema 顺序：" + keys + "）");
                }
                argv[i] = args.get(key);
            }
            return e.send(argv);
        }

        /** 无参数 RPC 的发送快捷方式。 */
        public boolean send() {
            return send(Map.of());
        }
    }

    /** 各线程的待挂载队列。 */
    private static final ThreadLocal<List<Entry>> PENDING = ThreadLocal.withInitial(ArrayList::new);

    private final List<Entry> entries = new ArrayList<>();

    private RpcCollector() {
    }

    /**
     * 声明一个 RPC（由 {@code RpcBuilder.fn} 调用）。
     *
     * <p>条目先进待挂载队列，等下一次渲染时被抽取。
     *
     * @param keys 参数名，与 schema 顺序一致；{@link Entry#send} 用它把对象摊成位置参数
     */
    public static Entry declare(String id, RPCEvent event, List<String> keys) {
        var entry = new Entry(id, event, keys);
        PENDING.get().add(entry);
        return entry;
    }

    /**
     * 建一个收集器并抽取本线程所有待挂载的声明。
     *
     * <p>由渲染入口调用。
     */
    public static RpcCollector drainForRender() {
        var collector = new RpcCollector();
        var pending = PENDING.get();
        if (!pending.isEmpty()) {
            collector.entries.addAll(pending);
            pending.clear();
        }
        return collector;
    }

    /** 当前线程是否还有未挂载的声明（用于诊断"声明了但没渲染"）。 */
    public static int pendingCount() {
        return PENDING.get().size();
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /**
     * 把收集到的事件挂到根元素上。
     *
     * <p>{@code UIElement.addRPCEvent} 会先存起来，等元素挂上 ModularUI 时自动注册到
     * syncManager（与 syncValues 同一套延迟注册，见 {@code UIElement:228}）。
     */
    public void attachTo(com.lowdragmc.lowdraglib2.gui.ui.UIElement root) {
        for (var entry : entries) {
            entry.attach(root.addRPCEvent(entry.event()));
        }
        if (!entries.isEmpty()) {
            NekoLDLib.LOGGER.info("[NekoLDLib] 本次界面挂载 {} 个 RPC：{}",
                    entries.size(), entries.stream().map(Entry::id).toList());
        }
    }
}
