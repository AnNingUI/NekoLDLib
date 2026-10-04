package com.tkisor.nekoldlib.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.tkisor.nekoldlib.NekoLDLib;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 本 mod 从脚本侧创建出来的元素注册表。
 *
 * <h2>为什么需要它</h2>
 *
 * NekoJS 为每种脚本类型各建一个 Graal {@code Context}，reload 时旧 Context 会被关闭。
 * 但已经建好的 {@link UIElement} 和挂在上面的事件监听器不会随之失效——监听器闭包
 * 持有指向<b>已死 Context</b> 的 {@code Value}。玩家再点那个按钮就会炸：
 *
 * <pre>
 *   IllegalStateException: The Context is already closed.
 *     at ElementRenderer.lambda$toListener$0(...)
 *     at UIEventDispatcher.handleBubbleEventListener(...)
 * </pre>
 *
 * <p>NekoJS 自己的注释也承认这是宿主的责任（{@code ScriptManager} 里写明"监听器闭包
 * 持有指向已死 Context 的 Value……恢复需再次 reload"）。所以在重新加载脚本前，
 * 本 mod 必须把上一代脚本创建的界面关掉。
 *
 * <p>用 {@link WeakHashMap} 而非强引用集合：注册表只用于"重载时找出残留界面"，
 * 不该因为登记而阻止元素被回收。
 */
public final class ScriptElements {

    /** 当前代次。每次脚本重新加载 +1，用于识别跨代的残留元素。 */
    private static volatile long generation;

    private static final Set<UIElement> ELEMENTS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ScriptElements() {
    }

    /** 登记一个从脚本创建的根元素（只在客户端侧有效）。 */
    public static void track(UIElement element) {
        synchronized (ELEMENTS) {
            ELEMENTS.add(element);
        }
    }

    /** 当前脚本代次。 */
    public static long generation() {
        return generation;
    }

    /**
     * 开始新的一代：关闭所有上一代脚本创建的界面，并递增代次。
     *
     * <p>由 {@code beforeScriptsLoaded} 触发——此时旧 Context 还活着（尚未 close），
     * 但已经不会再被使用，关掉旧界面是安全的。
     */
    public static void beginNewGeneration(String reason) {
        int closed;
        synchronized (ELEMENTS) {
            closed = ELEMENTS.size();
            ELEMENTS.clear();
        }
        generation++;
        // 关界面本身要在客户端线程做，且要等客户端就绪
        ClientUI.closeAllOwned();
        NekoLDLib.LOGGER.info("[NekoLDLib] 脚本重载（{}）：代次 -> {}，清理 {} 个残留元素",
                reason, generation, closed);
    }
}
