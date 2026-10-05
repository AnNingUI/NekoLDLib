package com.anningui.nekoldlib.server;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.anningui.nekoldlib.NekoLDLib;
import com.anningui.nekoldlib.signal.BindMode;
import com.anningui.nekoldlib.client.ScriptElements;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按"端"分流的 UI 工厂注册表。
 *
 * <h2>为什么不直接往 LDLib2 的注册表里塞</h2>
 *
 * {@link PlayerUIMenuType} 的 {@code UI_HOLDERS} 是一个<b>进程级静态 map</b>，
 * 而单人游戏里服务端与客户端在同一个 JVM。两端各注册一次同一 id 就会互相覆盖，
 * 结果是服务端去调客户端的工厂（或反之）—— Graal 的 {@code Value} 绑定了具体
 * Context，跨过去必然失败。
 *
 * <p>所以这里按 id 存两个工厂（服务端侧 / 客户端侧），只向 LDLib2 注册<b>一个</b>
 * 转发入口，在 {@code createUI(player)} 被调用时按 {@code player} 所在侧选择。
 *
 * <h2>为什么这样能成立</h2>
 *
 * {@code ModularUIContainerMenu} 在两端各构造一次，构造器里都调
 * {@code uiHolder.createUI(inventory.player)}：
 * <ul>
 *   <li>服务端：{@code player.openMenu(...)} 在服务端线程执行 → 取服务端工厂，
 *       此时正处 SERVER Context 的线程上。</li>
 *   <li>客户端：收到开界面包后构造 menu，在客户端线程执行 → 取客户端工厂。</li>
 * </ul>
 * 两侧各自在自己的 Context 里求值，互不越界——这正是 NekoJS 三种脚本环境隔离
 * 的设计意图，跨端协作交给 LDLib2 的同步机制。
 *
 * <h2>代次守卫</h2>
 *
 * 脚本重载会关闭旧 Context，注册表里的 {@code Value} 随之失效，所以重载时整表清空。
 */
public final class PlayerUIRegistry {

    /** 一个 id 在两端各自的工厂。任一侧可以为空（例如只做单人用的纯客户端界面）。 */
    private record Factories(@Nullable PanelFactory server, @Nullable PanelFactory client) {
        Factories with(boolean clientSide, PanelFactory factory) {
            return clientSide ? new Factories(server, factory) : new Factories(factory, client);
        }

        @Nullable
        PanelFactory pick(boolean clientSide) {
            return clientSide ? client : server;
        }
    }

    private static final Map<Identifier, Factories> FACTORIES = new ConcurrentHashMap<>();

    /** 代次，用于丢弃重载前残留的注册。 */
    private static volatile long generation;

    private PlayerUIRegistry() {
    }

    /**
     * 注册一个 id 的 UI 工厂。
     *
     * @param id      界面 id
     * @param factory 界面工厂：{@code player => vnode}
     * @param clientSide 当前调用方所在侧
     */
    public static void register(Identifier id, PanelFactory factory, boolean clientSide) {
        var hadEntry = FACTORIES.containsKey(id);
        FACTORIES.merge(id, new Factories(null, null).with(clientSide, factory),
                (old, added) -> old.with(clientSide, factory));

        // 向外层注册转发入口。用 hasEntry 判断而不是"是否首次"：某侧被清掉后
        // 转发入口也会被注销，此时另一侧重新注册必须把它补回来。
        if (!hadEntry) {
            PlayerUIMenuType.register(id, player -> new Holder(id));
        }
        NekoLDLib.LOGGER.info("[NekoLDLib] 注册界面 id {}（{} 侧）",
                id, clientSide ? "客户端" : "服务端");
    }

    /**
     * 清空某一侧的注册（脚本重载时调用）。
     *
     * <p><b>必须按侧清，不能全清。</b>每种脚本环境（startup / server / client）是
     * 独立重载的，而两端都要各自注册同一 id。某个环境重载时把全表清掉，会把另一侧
     * 仍然有效的注册一并抹掉——实测踩到过：服务端脚本重载清掉了客户端的注册，
     * 随后服务端开界面时客户端侧报"没有注册工厂"，玩家直接被断连。
     *
     * @param clientSide true 清客户端侧，false 清服务端侧
     */
    public static void clearSide(boolean clientSide, String reason) {
        generation++;
        int removed = 0;
        for (var entry : FACTORIES.entrySet()) {
            var id = entry.getKey();
            var old = entry.getValue();
            var kept = clientSide
                    ? new Factories(old.server(), null)
                    : new Factories(null, old.client());
            removed++;
            if (kept.server() == null && kept.client() == null) {
                // 两侧都没了 —— 整个 id 注销，避免留下空的转发入口
                FACTORIES.remove(id);
                PlayerUIMenuType.unregister(id);
            } else {
                FACTORIES.put(id, kept);
            }
        }
        if (removed > 0) {
            NekoLDLib.LOGGER.info("[NekoLDLib] 脚本重载（{}）：注销{}侧的 {} 个界面 id",
                    reason, clientSide ? "客户端" : "服务端", removed);
        }
    }

    /** 注销单个 id。 */
    public static void unregister(Identifier id) {
        FACTORIES.remove(id);
        PlayerUIMenuType.unregister(id);
    }

    /** 指定侧是否已注册该 id。 */
    public static boolean isRegistered(Identifier id, boolean clientSide) {
        var entry = FACTORIES.get(id);
        return entry != null && entry.pick(clientSide) != null;
    }

    /** 指定侧已注册的全部 id，按字典序。 */
    public static java.util.List<String> ids(boolean clientSide) {
        return FACTORIES.entrySet().stream()
                .filter(e -> e.getValue().pick(clientSide) != null)
                .map(e -> e.getKey().toString())
                .sorted()
                .toList();
    }

    /**
     * 建 UI。由 {@link Holder#createUI} 在对应侧调用。
     *
     * @throws IllegalStateException 该 id 在当前侧没有注册工厂
     */
    static ModularUI create(Player player, Identifier id) {
        var entry = FACTORIES.get(id);
        if (entry == null) {
            throw new IllegalStateException(
                    "界面 id " + id + " 未注册（可能在脚本重载时被注销）");
        }
        var clientSide = player.level().isClientSide();
        var factory = entry.pick(clientSide);
        if (factory == null) {
            throw new IllegalStateException(
                    "界面 id " + id + " 在" + (clientSide ? "客户端" : "服务端") + "侧没有注册工厂。"
                            + "两端都会调用 createUI，所以需要在该侧的脚本环境里各自注册一次"
                            + "（共享的界面代码可放 nekojs/node_modules 下由两侧 import）。");
        }

        // 用同一侧已有的渲染入口，保证绑定走 SERVER_MENU 路径（跨端同步）
        var vnode = factory.create(player);
        var element = NekoLDLib.jsx().render(vnode.raw(), BindMode.SERVER_MENU);
        return ModularUI.of(com.lowdragmc.lowdraglib2.gui.ui.UI.of(element), player);
    }

    /**
     * LDLib2 的 holder 实现。
     *
     * <p>只作为转发壳：每次 {@code createUI} 都重新查表，这样脚本 reload 后新建的界面
     * 能拿到新代次的工厂，而旧界面自然失效。
     */
    private record Holder(Identifier id) implements PlayerUIMenuType.PlayerUIHolder {

        @Override
        public ModularUI createUI(Player player) {
            return PlayerUIRegistry.create(player, id);
        }
    }
}
