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
            com.autobuilder.build.Gather.tick();
            com.autobuilder.build.SupplyManager.watchOpen(Minecraft.getInstance());
            Diag.tick();
        } catch (RuntimeException ex) {
            AutoBuilder.LOG.error("AutoBuilder tick 出错", ex);
            Diag.error("tick", ex);
            BuildController.stop();
            BuildController.msg(Lang.t("msg.internal_error", ex), net.minecraft.ChatFormatting.RED);
        }
    }

    /** 指令里打开界面：聊天框发完指令会把界面关掉，等下一 tick 再开 */
    private static net.minecraft.client.gui.screens.Screen pendingScreen;
    private static boolean holdingWand;

    public static void openLater(net.minecraft.client.gui.screens.Screen s) {
        pendingScreen = s;
    }

    @SubscribeEvent
    public static void onTickPost(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (pendingScreen != null) {
            mc.setScreen(pendingScreen);
            pendingScreen = null;
        }
        // 刚拿起木棍：在快捷栏上方提示一下木棍能做什么
        boolean wand = com.autobuilder.build.SelectionWand.holding(mc.player);
        if (wand && !holdingWand && mc.screen == null) {
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(Lang.t("wand.hint")).withStyle(net.minecraft.ChatFormatting.GRAY), false);
        }
        holdingWand = wand;
        while (Keys.OPEN.consumeClick()) mc.setScreen(new BuilderScreen());
        while (Keys.TOGGLE.consumeClick()) BuildController.toggle();
        while (Keys.ROTATE.consumeClick()) {
            Placement p = BuildController.placement();
            if (p != null) p.rotateCW();
        }
        while (Keys.POS1.consumeClick()) {
            var p = com.autobuilder.build.Selection.crosshairOrFeet();
            com.autobuilder.build.Selection.set(1, p);
            BuildController.msg(Lang.t("msg.corner", 1, p.toShortString(), com.autobuilder.build.Selection.describe()), net.minecraft.ChatFormatting.YELLOW);
        }
        while (Keys.POS2.consumeClick()) {
            var p = com.autobuilder.build.Selection.crosshairOrFeet();
            com.autobuilder.build.Selection.set(2, p);
            BuildController.msg(Lang.t("msg.corner", 2, p.toShortString(), com.autobuilder.build.Selection.describe()), net.minecraft.ChatFormatting.YELLOW);
        }
        while (Keys.HIDE.consumeClick()) togglePreview();
        while (Keys.LOAD_HERE.consumeClick()) {
            Placement p = BuildController.placement();
            if (p != null) p.setOrigin(mc.player.blockPosition());
        }
    }

    /** 记下右键的方块：打开箱子时用来知道是哪个箱子（材料清单要算上材料箱里的） */
    @SubscribeEvent
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide()) com.autobuilder.build.SupplyManager.clicked(e.getPos());
    }

    /** 隐藏/显示投影：隐藏时照常建造，只是看不到半透明方块和框 */
    public static void togglePreview() {
        com.autobuilder.render.PreviewRenderer.toggleHidden();
        boolean h = com.autobuilder.render.PreviewRenderer.hidden();
        BuildController.msg(h ? Lang.t("msg.preview_hidden", Keys.HIDE.getTranslatedKeyMessage().getString()) : Lang.t("msg.preview_shown"),
                net.minecraft.ChatFormatting.AQUA);
    }

    /** 选区木棍：拿着木棍左键/右键设选区角，点击不会真的挖方块或用方块 */
    @SubscribeEvent
    public static void onClickInput(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered e) {
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !(e.isAttack() || e.isUseItem())) return;
        if (com.autobuilder.build.SelectionWand.click(e.isAttack())) {
            e.setCanceled(true);
            e.setSwingHand(true);
        }
    }

    /** 木棍 + Shift + 滚轮：平移蓝图（不切换快捷栏） */
    @SubscribeEvent
    public static void onScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent e) {
        if (com.autobuilder.build.SelectionWand.scroll(e.getScrollDeltaY())) e.setCanceled(true);
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
