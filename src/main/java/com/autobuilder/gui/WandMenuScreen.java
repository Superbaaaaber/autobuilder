package com.autobuilder.gui;

import com.autobuilder.Lang;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Selection;
import com.autobuilder.build.SelectionWand;
import com.autobuilder.build.SupplyManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 木棍 Shift+右键 的快捷菜单：按准星对着的东西列出能做的事。
 * 对着箱子 → 材料箱/存放箱；对着蓝图里的方块（投影也算）→ 替换这种方块；还有蓝图、选区、砍树挖矿的常用操作。
 */
public class WandMenuScreen extends Screen {
    /** 对着的格子（可能是投影里还没放的），没有为 null */
    private final BlockPos pos;
    /** 蓝图挪过来时放在哪（对着的真实方块外侧那格），没有为 null */
    private final BlockPos place;
    private String message = "";
    private int top;

    public WandMenuScreen(BlockPos pos, BlockPos place) {
        super(Component.literal(Lang.t("wand.menu_title")));
        this.pos = pos;
        this.place = place;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Item(String label, Runnable run) {}

    @Override
    protected void init() {
        List<Item> items = new ArrayList<>();
        // 箱子：材料箱 / 存放箱（再点一次取消）
        if (pos != null && SupplyManager.isContainer(pos)) {
            boolean sup = SupplyManager.list().contains(pos), dump = SupplyManager.dumps().contains(pos);
            items.add(new Item((sup ? "§a✔ " : "") + Lang.t("wand.supply"), () -> {
                if (sup) SupplyManager.remove(pos);
                else SupplyManager.add(pos);
                message = "§a" + Lang.t(sup ? "wand.supply_off" : "wand.supply_on", pos.toShortString());
                rebuildWidgets();
            }));
            items.add(new Item((dump ? "§a✔ " : "") + Lang.t("wand.dump"), () -> {
                if (dump) SupplyManager.removeDump(pos);
                else SupplyManager.addDump(pos);
                message = "§a" + Lang.t(dump ? "wand.dump_off" : "wand.dump_on", pos.toShortString());
                rebuildWidgets();
            }));
        }
        Placement pl = BuildController.placement();
        if (pl != null) {
            BlockState o = pos == null ? null : pl.originalAt(pos);
            if (o != null && !o.isAir()) {
                items.add(new Item(Lang.t("wand.replace", o.getBlock().getName().getString()),
                        () -> minecraft.setScreen(new ReplaceScreen(null, o.getBlock()))));
            }
            if (place != null) items.add(new Item(Lang.t("wand.move_here"), () -> {
                if (SelectionWand.moveBlueprintTo(place)) onClose();
            }));
            items.add(new Item(Lang.t("gui.rotate"), () -> {
                if (busy()) return;
                pl.rotateCW();
                message = "§a" + pl.describe();
            }));
            items.add(new Item(Lang.t("gui.mirror"), () -> {
                if (busy()) return;
                pl.cycleMirror();
                message = "§a" + pl.describe();
            }));
            boolean running = BuildController.state() == BuildController.State.RUNNING;
            items.add(new Item(Lang.t(running ? "gui.pause" : "gui.start"), () -> {
                if (running) BuildController.pause(Lang.t("reason.manual"));
                else BuildController.start();
                onClose();
            }));
            items.add(new Item(Lang.t("gui.materials") + "…", () -> minecraft.setScreen(new MaterialsScreen(null))));
        }
        if (pos != null) {
            items.add(new Item(Lang.t("wand.corner1"), () -> {
                Selection.set(1, pos);
                message = "§e" + Lang.t("msg.corner", 1, pos.toShortString(), Selection.describe());
            }));
            items.add(new Item(Lang.t("wand.corner2"), () -> {
                Selection.set(2, pos);
                message = "§e" + Lang.t("msg.corner", 2, pos.toShortString(), Selection.describe());
            }));
        }
        if (Selection.complete()) {
            items.add(new Item(Lang.t("wand.excavate"), () -> {
                onClose();
                minecraft.player.connection.sendCommand("ab excavate");
            }));
            items.add(new Item(Lang.t("wand.save"), () -> minecraft.setScreen(new ToolsScreen(null))));
            items.add(new Item(Lang.t("wand.clear_sel"), () -> {
                Selection.clear();
                message = "§a" + Lang.t("wand.cleared_sel");
                rebuildWidgets();
            }));
        }
        items.add(new Item(Lang.t("gui.gather") + "…", () -> minecraft.setScreen(new GatherScreen(null))));
        items.add(new Item(Lang.t("wand.main"), () -> minecraft.setScreen(new BuilderScreen())));

        int bw = 150, gap = 4, cols = width >= 2 * bw + gap + 20 ? 2 : 1;
        int rows = (items.size() + cols - 1) / cols;
        int totalW = cols * bw + (cols - 1) * gap, totalH = rows * 22;
        int x0 = (width - totalW) / 2;
        top = Math.max(40, (height - totalH) / 2);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int x = x0 + (i % cols) * (bw + gap), y = top + (i / cols) * 22;
            addRenderableWidget(Button.builder(Component.literal(it.label()), b -> it.run().run()).bounds(x, y, bw, 20).build());
        }
    }

    private boolean busy() {
        if (BuildController.state() == BuildController.State.RUNNING) {
            message = "§c" + Lang.t("wand.busy");
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, top - 30, 0xFFFFFF);
        String what;
        if (pos == null) {
            what = "§7" + Lang.t("wand.nothing");
        } else {
            BlockState w = minecraft.level.getBlockState(pos);
            what = "§7" + Lang.t("wand.pointing", w.isAir() ? Lang.t("wand.air") : w.getBlock().getName().getString(), pos.toShortString());
            Placement pl = BuildController.placement();
            BlockState t = pl == null ? null : pl.originalAt(pos);
            if (t != null && !t.isAir()) what += "  §b" + Lang.t("wand.ghost", t.getBlock().getName().getString());
        }
        g.drawCenteredString(font, font.plainSubstrByWidth(what, width - 20), width / 2, top - 16, 0xFFFFFF);
        if (!message.isEmpty()) g.drawCenteredString(font, font.plainSubstrByWidth(message, width - 20), width / 2, height - 20, 0xFFFFFF);
    }
}
