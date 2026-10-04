package com.tkisor.nekoldlib.client;

import com.tkisor.nekoldlib.NekoLDLib;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端线程调度：一次性任务 + 每 tick 任务。
 *
 * <h2>为什么要有"等就绪"这一层</h2>
 *
 * NekoJS 会在 mod 加载期求值一次脚本（modloading worker 线程），此时
 * {@code Minecraft.getInstance()} 还是 {@code null}。而建界面、建 ModularUI、
 * Taffy 布局全都依赖客户端单例——不只是 {@code setScreen}。
 *
 * <p>所以一次性任务在客户端未就绪时先入队，等首个 {@link ClientTickEvent.Post} 再跑。
 * 实测踩到过：只把最后一步切线程，会在加载期抛
 * {@code NPE: ... because Minecraft.getInstance() is null}。
 *
 * <p>本类只在这里引用 {@link ClientTickEvent}：所有调用方都是客户端路径，
 * 服务端不会执行到，因此不会在专用服务器上触发类加载失败。
 */
public final class ClientTicker {

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
    private static final List<Runnable> PER_TICK = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final int MAX_PENDING = 64;

    private ClientTicker() {
    }

    /**
     * 在客户端线程上执行一次。
     *
     * @param what 出错时用于定位的操作名
     */
    public static void once(String what, Runnable task) {
        var guarded = guard(what, task);
        var mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(guarded);
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            NekoLDLib.LOGGER.warn("[NekoLDLib] 挂起的 {} 操作超过 {} 个，丢弃本次调用", what, MAX_PENDING);
            return;
        }
        PENDING.add(guarded);
        install();
    }

    /**
     * 每个客户端 tick 执行一次（已在客户端线程上）。
     *
     * <p>用于本地绑定的"每 tick 推值"。返回的句柄可用于注销。
     */
    public static void everyTick(Runnable task) {
        PER_TICK.add(task);
        install();
    }

    public static void stopTick(Runnable task) {
        PER_TICK.remove(task);
    }

    private static Runnable guard(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                NekoLDLib.LOGGER.error("[NekoLDLib] {} 失败", what, e);
            }
        };
    }

    private static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(ClientTicker::onTick);
        }
    }

    private static void onTick(ClientTickEvent.Post event) {
        Runnable pending;
        while ((pending = PENDING.poll()) != null) {
            pending.run();
        }
        for (var task : PER_TICK) {
            try {
                task.run();
            } catch (Exception e) {
                NekoLDLib.LOGGER.error("[NekoLDLib] 每 tick 任务抛错，已注销该任务", e);
                PER_TICK.remove(task);
            }
        }
    }
}
