package com.anningui.nekoldlib.server;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.tkisor.nekojs.api.ScriptType;
import com.tkisor.nekojs.api.data.BindingRegistry;
import graal.graalvm.polyglot.Value;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 脚本侧的服务端 UI 入口（{@code PlayerUI} 全局）。
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // nekojs/node_modules/mypack/panel.js —— 两端共享的界面代码
 * export function machinePanel(player) {
 *   return <panel class="panel_bg" p-all={6}>
 *     <label bind={{ get: () => 'Speed ' + speed }} />
 *     <slider min={1} max={10} bind={{ get: () => speed, set: v => { speed = v } }} />
 *   </panel>
 * }
 *
 * // server_scripts/machine.js
 * import { machinePanel } from 'mypack/panel'
 * PlayerUI.register('mypack:machine', player => machinePanel(player))
 * ServerEvents.on('playerTick', e => PlayerUI.open(e.player, 'mypack:machine'))
 * }</pre>
 *
 * <h2>为什么两端都要注册</h2>
 *
 * {@code ModularUIContainerMenu} 在服务端与客户端<b>各构造一次</b>，构造器里都调
 * {@code createUI(player)}。所以同一个 id 需要在两端的脚本环境里各自注册一份工厂。
 * 三种脚本环境（startup / server / client）的 Context 是隔离的，JS 值不能跨 Context
 * 传递——但<b>共享的界面代码可以放 {@code nekojs/node_modules} 下由两侧 import</b>，
 * 各自求值得到自己的副本。这正是隔离设计的预期用法。
 *
 * <p>跨端的状态协作交给 LDLib2 的同步机制（{@code bind} 走 SimpleBinding），
 * 而不是靠共享 JS 对象。
 */
public final class PlayerUI {

    /**
     * 当前实例绑定的脚本环境。
     *
     * <p>判定"在哪一侧"必须靠这个，<b>不能靠线程或 dist</b>：
     * <ul>
     *   <li>服务端脚本在加载期跑在 {@code Worker-Main-N} 线程上，那时
     *       {@code LDLib2.isServer()} 为 false（它要求"当前线程 == 服务端主线程"），
     *       而单人游戏的 dist 是 CLIENT —— 于是会被错判成客户端侧。
     *       实测踩到过：{@code server_scripts} 里的注册被标成了"客户端侧"。</li>
     *   <li>{@code BindingRegistry.scriptType()} 直接告诉我们这是哪个环境，
     *       与线程无关，加载期也准确。</li>
     * </ul>
     */
    private final ScriptType scriptType;

    private PlayerUI(ScriptType scriptType) {
        this.scriptType = scriptType;
    }

    /** 注册到当前脚本环境。每个环境拿到的是带自己 ScriptType 的实例。 */
    public static void install(BindingRegistry registry) {
        var type = registry.scriptType();
        registry.register(type, "PlayerUI", new PlayerUI(type));
    }

    /**
     * 注册一个界面 id 的构建函数。当前在哪一侧的脚本里调用，就注册到哪一侧。
     *
     * @param id      形如 {@code "mypack:machine"}
     * @param factory {@code player => vnode}
     */
    public void register(String id, PanelFactory factory) {
        var identifier = Identifier.tryParse(id);
        if (identifier == null) {
            throw new IllegalArgumentException(
                    "无效的界面 id: '" + id + "'。需要形如 \"mypack:machine\"。");
        }
        PlayerUIRegistry.register(identifier, factory, isClientSide());
    }

    /** 注销一个界面 id。 */
    public void unregister(String id) {
        var identifier = Identifier.tryParse(id);
        if (identifier != null) {
            PlayerUIRegistry.unregister(identifier);
        }
    }

    /**
     * 为玩家打开界面。
     *
     * <p>服务端调用即对目标玩家开界面（走网络）；客户端调用等价于打开本地界面。
     *
     * @return 是否成功打开
     */
    public boolean open(Player player, String id) {
        var identifier = Identifier.tryParse(id);
        if (identifier == null) {
            throw new IllegalArgumentException("无效的界面 id: '" + id + "'");
        }
        return com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType.openUI(player, identifier);
    }

    /** 关闭玩家当前由本 mod 打开的界面。 */
    public void close(Player player) {
        if (player != null) {
            player.closeContainer();
        }
    }

    /** 查询某 id 是否已注册（当前侧）。 */
    public boolean isRegistered(String id) {
        var identifier = Identifier.tryParse(id);
        return identifier != null && PlayerUIRegistry.isRegistered(identifier, isClientSide());
    }

    /** 列出当前侧已注册的所有 id，便于排查"忘了在某一侧注册"。 */
    public String ids() {
        var all = PlayerUIRegistry.ids(isClientSide());
        return all.isEmpty() ? "(当前侧未注册任何界面)" : String.join(", ", all);
    }

    /**
     * 当前脚本所在侧。
     *
     * <p>由 {@code ScriptType} 决定，与线程/dist 无关——理由见 {@link #scriptType} 的说明。
     * {@code STARTUP} 归为服务端侧：启动脚本在专用服务器上也要跑，把它当客户端会让
     * 开发时（单人）能用、上线（专用服）却失效。
     */
    private boolean isClientSide() {
        return scriptType == ScriptType.CLIENT;
    }
}
