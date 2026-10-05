package com.anningui.nekoldlib.server;

import com.anningui.nekoldlib.jsx.VNode;
import net.minecraft.world.entity.player.Player;

/**
 * 界面工厂：{@code player => vnode}。
 *
 * <h2>为什么是函数式接口而不是收 {@code Value}</h2>
 *
 * 收 {@code Value} 时生成的类型声明是 {@code $Value}——看不出参数该传什么。
 * Graal 的 {@code allowAllImplementations}（见 {@code NekoSharedHostAccess:41}）
 * 能把 JS 箭头函数代理成本接口，声明因此变成 {@code $PanelFactory}，参数类型
 * {@code $Player} 也可跳转。
 *
 * <p>实测确认：转换成功，且调用语义正确（Java 侧 {@code create(player)} 时
 * JS 收到单个参数）。
 *
 * <h2>返回 {@link VNode} 而非 {@code Value}</h2>
 *
 * JS 返回的 JSX vnode 是普通 JS 对象，但它经 {@code VNodeAdapter} 转换后就是
 * {@link VNode}——这样声明的返回类型也具体化，且调用方不必自己解包。
 */
@FunctionalInterface
public interface PanelFactory {

    /**
     * @param player 界面所属玩家
     * @return JSX vnode
     */
    VNode create(Player player);
}
