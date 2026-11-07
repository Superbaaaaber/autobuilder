package com.autobuilder.gui;

import com.autobuilder.Lang;
import com.autobuilder.blueprint.Blueprint;
import com.autobuilder.blueprint.BlueprintManager;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Scanner;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** B 键打开的主界面：左边蓝图列表，右边操作按钮 */
public class BuilderScreen extends Screen {
    private BlueprintList list;
    private String selected;
    private String message = "";
    /** 标题能放的范围（左上角、右上角按钮中间）；放不下就不画 */
    private int titleLeft, titleRight;

    public BuilderScreen() {
        super(Component.literal(Lang.t("screen.title")));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int listW = Math.min(220, width / 2 - 10);
        list = new BlueprintList(minecraft, listW, height - 70, 30, 14);
        list.setX(10);
        for (BlueprintManager.Entry e : BlueprintManager.list()) list.add(e);
        addRenderableWidget(list);

        int x = 20 + listW;
        int y = 30;
        int bw = 96, gap = 4;
        addRenderableWidget(btn(Lang.t("gui.load"), x, y, bw, this::loadSelected));
        addRenderableWidget(btn(Lang.t("gui.unload"), x + bw + gap, y, bw, () -> {
            BuildController.unload();
            message = Lang.t("gui.unloaded");
        }));
        y += 24;
        addRenderableWidget(btn(Lang.t("gui.rotate"), x, y, bw, () -> withPlacement(Placement::rotateCW)));
        addRenderableWidget(btn(Lang.t("gui.mirror"), x + bw + gap, y, bw, () -> withPlacement(Placement::cycleMirror)));
        y += 24;
        int sw = 30;
        String[] labels = {"X-", "X+", "Y-", "Y+", "Z-", "Z+"};
        int[][] d = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int i = 0; i < 6; i++) {
            int[] dd = d[i];
            addRenderableWidget(btn(labels[i], x + i * (sw + 3), y, sw, () -> withPlacement(p -> p.move(dd[0], dd[1], dd[2]))));
        }
        y += 24;
        addRenderableWidget(btn(Lang.t("gui.origin_here"), x, y, bw, () -> withPlacement(p -> p.setOrigin(minecraft.player.blockPosition()))));
        addRenderableWidget(btn(Lang.t("gui.materials"), x + bw + gap, y, bw, () -> {
            if (BuildController.placement() == null) {
                message = "§c" + Lang.t("gui.need_blueprint");
                return;
            }
            minecraft.setScreen(new MaterialsScreen(this));
        }));
        y += 24;
        // 材料替换：点选方块，不用打指令、记英文名
        addRenderableWidget(btn(Lang.t("gui.replace"), x, y, bw, () -> {
            if (BuildController.placement() == null) {
                message = "§c" + Lang.t("gui.need_blueprint");
                return;
            }
            minecraft.setScreen(new ReplaceScreen(this, null));
        }));
        addRenderableWidget(btn(Lang.t("gui.gather"), x + bw + gap, y, bw, () -> minecraft.setScreen(new GatherScreen(this))));
        y += 24;
        addRenderableWidget(btn(Lang.t("gui.start"), x, y, bw, () -> {
            BuildController.start();
            onClose();
        }));
        addRenderableWidget(btn(Lang.t("gui.pause"), x + bw + gap, y, bw, () -> BuildController.pause(Lang.t("reason.manual"))));
        y += 24;
        addRenderableWidget(btn(Lang.t("gui.stop"), x, y, bw, BuildController::stop));
        addRenderableWidget(btn(Lang.t("gui.tools"), x + bw + gap, y, bw, () -> minecraft.setScreen(new ToolsScreen(this))));
        // 左上角：打开蓝图文件夹、刷新列表（不常用，放小一点）
        int fw = Math.max(60, font.width(Lang.t("gui.open_folder")) + 10), rw = Math.max(36, font.width(Lang.t("gui.refresh")) + 10);
        addRenderableWidget(btn(Lang.t("gui.open_folder"), 10, 5, fw, () -> Util.getPlatform().openPath(BlueprintManager.dir())));
        addRenderableWidget(btn(Lang.t("gui.refresh"), 14 + fw, 5, rw, () -> rebuildWidgets()));

        // 右上角：设置、指令帮助、隐藏投影
        int sw1 = Math.max(50, font.width(Lang.t("gui.settings")) + 12);
        int sw2 = Math.max(56, font.width(Lang.t("gui.help")) + 12);
        int sw3 = Math.max(60, Math.max(font.width(Lang.t("gui.hide_preview")), font.width(Lang.t("gui.show_preview"))) + 12);
        addRenderableWidget(btn(Lang.t("gui.settings"), width - 10 - sw1, 5, sw1, () -> com.autobuilder.AutoBuilder.openConfig(this)));
        addRenderableWidget(btn(Lang.t("gui.help"), width - 10 - sw1 - 4 - sw2, 5, sw2, () -> {
            onClose();
            minecraft.player.connection.sendCommand("ab help");
        }));
        addRenderableWidget(Button.builder(Component.literal(previewLabel()), b -> {
            com.autobuilder.ClientEvents.togglePreview();
            b.setMessage(Component.literal(previewLabel()));
        }).bounds(width - 10 - sw1 - 4 - sw2 - 4 - sw3, 5, sw3, 20).build());
        titleLeft = 18 + fw + rw;
        titleRight = width - 10 - sw1 - 4 - sw2 - 4 - sw3 - 4;
    }

    private static String previewLabel() {
        return Lang.t(com.autobuilder.render.PreviewRenderer.hidden() ? "gui.show_preview" : "gui.hide_preview");
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    private void withPlacement(java.util.function.Consumer<Placement> c) {
        Placement p = BuildController.placement();
        if (p == null) {
            message = "§c" + Lang.t("gui.need_blueprint");
            return;
        }
        c.accept(p);
    }

    private void loadSelected() {
        if (selected == null) {
            message = "§c" + Lang.t("gui.pick_first");
            return;
        }
        try {
            Blueprint bp = BlueprintManager.load(selected);
            Minecraft mc = Minecraft.getInstance();
            Placement p = Placement.inFrontOf(bp, mc.player.blockPosition(), mc.player.getDirection());
            BuildController.setPlacement(p);
            message = "§a" + Lang.t("msg.loaded", bp.name, bp.sizeText(), bp.solidCount());
        } catch (Exception ex) {
            message = "§c" + Lang.t("msg.load_failed", ex.getMessage());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (font.width(title) <= titleRight - titleLeft) g.drawCenteredString(font, title, (titleLeft + titleRight) / 2, 11, 0xFFFFFF);
        int y = height - 36;
        Placement p = BuildController.placement();
        if (p != null) {
            g.drawString(font, "§b" + p.describe(), 10, y, 0xFFFFFF);
            Scanner.Snapshot s = BuildController.snapshot();
            if (s != null) {
                g.drawString(font, "§7" + Lang.t("gui.progress", s.percent(), s.place.size(), s.breaks.size(), BuildController.state(), BuildController.activity()), 10, y + 11, 0xFFFFFF);
            }
        } else {
            g.drawString(font, "§7" + Lang.t("gui.folder", BlueprintManager.dir()), 10, y, 0xFFFFFF);
        }
        if (!message.isEmpty()) g.drawString(font, message, 10, y - 12, 0xFFFFFF);
    }

    private class BlueprintList extends ObjectSelectionList<BlueprintList.Entry> {
        BlueprintList(Minecraft mc, int w, int h, int y, int itemH) {
            super(mc, w, h, y, itemH);
        }

        void add(BlueprintManager.Entry e) {
            addEntry(new Entry(e));
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final BlueprintManager.Entry e;

            Entry(BlueprintManager.Entry e) {
                this.e = e;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                String name = e.display();
                if (font.width(name) > width - 4) name = font.plainSubstrByWidth(name, width - 10) + "…";
                g.drawString(font, (e.builtin() ? "§e" : "§f") + name, left + 2, top + 2, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mx, double my, int button) {
                BlueprintList.this.setSelected(this);
                selected = e.name();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(e.name());
            }
        }
    }
}
