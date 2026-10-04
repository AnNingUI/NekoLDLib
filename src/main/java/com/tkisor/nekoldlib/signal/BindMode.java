package com.tkisor.nekoldlib.signal;

/**
 * 绑定的实现路径。
 *
 * <p>两种路径互斥，必须显式区分——它们的"权威值在哪"根本不同：
 *
 * <ul>
 *   <li>{@link #CLIENT_LOCAL} —— 纯客户端界面（{@code ModularUI.of(ui)}，无 player）。
 *       权威值就在脚本变量里，靠 {@link LocalBinding} 每 tick 搬运。
 *       LDLib2 的 SimpleBinding 在这条路径上是惰性的（{@code UISyncManager.tick()}
 *       开头就因 {@code player == null} 早退）。</li>
 *   <li>{@link #SERVER_MENU} —— 服务端开的菜单（{@code ModularUIContainerMenu}）。
 *       权威值在服务端，走 LDLib2 的 {@code SimpleBinding} 做 S2C/C2S 同步。
 *       阶段 3 接入。</li>
 * </ul>
 */
public enum BindMode {
    CLIENT_LOCAL,
    SERVER_MENU,
}
