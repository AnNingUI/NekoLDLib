package com.tkisor.nekoldlib.server;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.tkisor.nekoldlib.NekoLDLib;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 阶段 3 自检：确认端判定与按端分流正确。
 *
 * <p>这几条不依赖脚本 API，能在启动后立刻给出确定性结论——比写脚本猜 API 可靠。
 * 尤其 {@link #probeSides()}：{@code PlayerUIRegistry} 的正确性完全依赖
 * "服务端侧调用 createUI 时 {@code LDLib2.isServer()} 为真"这个前提，值得实测。
 */
@EventBusSubscriber(modid = NekoLDLib.MOD_ID)
public final class Stage3SelfCheck {

    private Stage3SelfCheck() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        NekoLDLib.LOGGER.info("[NekoLDLib/verify3] ==== 阶段 3 自检 ====");
        probeSides();
        probeRegistry();
        NekoLDLib.LOGGER.info("[NekoLDLib/verify3] ==== 结束 ====");
    }

    /** 端判定：这决定 createUI 会取哪一侧的工厂。 */
    private static void probeSides() {
        log("isClient()", String.valueOf(LDLib2.isClient()));
        log("isServer()", String.valueOf(LDLib2.isServer()));
        log("isRemote()", String.valueOf(LDLib2.isRemote()));
        log("FMLEnvironment.getDist()", String.valueOf(
                net.neoforged.fml.loading.FMLEnvironment.getDist()));
    }

    /** 注册表：能否按端分流、能否给出可操作的报错。 */
    private static void probeRegistry() {
        var id = Identifier.fromNamespaceAndPath(NekoLDLib.MOD_ID, "probe");

        // 未注册时查询
        log("未注册 -> isRegistered(server)", String.valueOf(PlayerUIRegistry.isRegistered(id, false)));
        log("未注册 -> isRegistered(client)", String.valueOf(PlayerUIRegistry.isRegistered(id, true)));

        // 注册一个空工厂（用不到的 Value 无法在此构造，只验证注册表的"缺失"路径）
        try {
            PlayerUIRegistry.create(null, id);
            log("未注册 -> create()", "未报错（异常预期！）");
        } catch (IllegalStateException e) {
            log("未注册 -> create() 报错", e.getMessage());
        }

        try {
            PlayerUIRegistry.register(id, null, false);
            log("register(null) 校验", "未报错（预期应抛错！）");
        } catch (IllegalArgumentException e) {
            log("register(null) 校验", e.getMessage());
        }
    }

    private static void log(String what, String value) {
        NekoLDLib.LOGGER.info("[NekoLDLib/verify3] {} = {}", what, value);
    }
}
