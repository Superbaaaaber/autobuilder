package com.autobuilder;

import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.command.ABCommands;
import com.autobuilder.gui.BuilderScreen;
import com.autobuilder.render.PreviewRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre e) {
        try {
            BuildController.tick();
        } catch (RuntimeException ex) {
            AutoBuilder.LOG.error("AutoBuilder tick 出错", ex);
            BuildController.stop();
            BuildController.msg("内部错误，已停止：" + ex, net.minecraft.ChatFormatting.RED);
        }
    }

    @SubscribeEvent
    public static void onTickPost(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (Keys.OPEN.consumeClick()) mc.setScreen(new BuilderScreen());
        while (Keys.TOGGLE.consumeClick()) BuildController.toggle();
        while (Keys.ROTATE.consumeClick()) {
            Placement p = BuildController.placement();
            if (p != null) p.rotateCW();
        }
        while (Keys.POS1.consumeClick()) {
            var p = com.autobuilder.build.Selection.crosshairOrFeet();
            com.autobuilder.build.Selection.set(1, p);
            BuildController.msg("选区角1 = " + p.toShortString() + "  " + com.autobuilder.build.Selection.describe(), net.minecraft.ChatFormatting.YELLOW);
        }
        while (Keys.POS2.consumeClick()) {
            var p = com.autobuilder.build.Selection.crosshairOrFeet();
            com.autobuilder.build.Selection.set(2, p);
            BuildController.msg("选区角2 = " + p.toShortString() + "  " + com.autobuilder.build.Selection.describe(), net.minecraft.ChatFormatting.YELLOW);
        }
        while (Keys.LOAD_HERE.consumeClick()) {
            Placement p = BuildController.placement();
            if (p != null) p.setOrigin(mc.player.blockPosition());
        }
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent e) {
        if (e.getEntity() == Minecraft.getInstance().player && BuildController.state() == BuildController.State.RUNNING) {
            BuildController.walker().apply(e.getInput());
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent e) {
        ABCommands.register(e.getDispatcher(), e.getBuildContext());
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        PreviewRenderer.render(e);
    }

    /** 补货时静默打开箱子：不弹界面（菜单仍然打开，可以照常点格子） */
    @SubscribeEvent
    public static void onScreenOpen(net.neoforged.neoforge.client.event.ScreenEvent.Opening e) {
        if (com.autobuilder.build.Restocker.suppressScreen()
                && e.getNewScreen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        BuildController.detach();
        com.autobuilder.build.ServerPolicy.reset();
    }

    /** 服务器端禁用开关：系统消息里带 [NoAutoBuilder] 就停用自动建造（玩家聊天不算，防止别人捣乱） */
    @SubscribeEvent
    public static void onSystemChat(net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System e) {
        if (com.autobuilder.build.ServerPolicy.onServerMessage(e.getMessage())) e.setCanceled(true);
    }
}
