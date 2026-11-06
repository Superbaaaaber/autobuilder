package com.autobuilder.gui;

import com.autobuilder.blueprint.Blueprint;
import com.autobuilder.blueprint.BlueprintManager;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Scanner;
import java.util.List;
import net.minecraft.ChatFormatting;
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

    public BuilderScreen() {
        super(Component.literal("AutoBuilder 自动建造"));
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
        addRenderableWidget(btn("加载到面前", x, y, bw, this::loadSelected));
        addRenderableWidget(btn("卸载", x + bw + gap, y, bw, () -> {
            BuildController.unload();
            message = "已卸载";
        }));
        y += 24;
        addRenderableWidget(btn("旋转 90°", x, y, bw, () -> withPlacement(Placement::rotateCW)));
        addRenderableWidget(btn("镜像", x + bw + gap, y, bw, () -> withPlacement(Placement::cycleMirror)));
        y += 24;
        int sw = 30;
        String[] labels = {"X-", "X+", "Y-", "Y+", "Z-", "Z+"};
        int[][] d = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int i = 0; i < 6; i++) {
            int[] dd = d[i];
            addRenderableWidget(btn(labels[i], x + i * (sw + 3), y, sw, () -> withPlacement(p -> p.move(dd[0], dd[1], dd[2]))));
        }
        y += 24;
        addRenderableWidget(btn("原点移到脚下", x, y, bw, () -> withPlacement(p -> p.setOrigin(minecraft.player.blockPosition()))));
        addRenderableWidget(btn("材料清单", x + bw + gap, y, bw, this::printMaterials));
        y += 24;
        addRenderableWidget(btn("开始 / 继续", x, y, bw, () -> {
            BuildController.start();
            onClose();
        }));
        addRenderableWidget(btn("暂停", x + bw + gap, y, bw, () -> BuildController.pause("手动暂停")));
        y += 24;
        addRenderableWidget(btn("停止", x, y, bw, BuildController::stop));
        addRenderableWidget(btn("打开蓝图文件夹", x + bw + gap, y, bw, () -> Util.getPlatform().openPath(BlueprintManager.dir())));
        y += 24;
        addRenderableWidget(btn("刷新列表", x, y, bw, () -> rebuildWidgets()));
        addRenderableWidget(btn("保存建筑/材料箱…", x + bw + gap, y, bw, () -> minecraft.setScreen(new ToolsScreen(this))));

        // 右上角：设置、指令帮助
        addRenderableWidget(btn("设置…", width - 10 - 50, 5, 50, () -> com.autobuilder.AutoBuilder.openConfig(this)));
        addRenderableWidget(btn("指令帮助", width - 10 - 50 - 4 - 56, 5, 56, () -> {
            onClose();
            minecraft.player.connection.sendCommand("ab help");
        }));
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    private void withPlacement(java.util.function.Consumer<Placement> c) {
        Placement p = BuildController.placement();
        if (p == null) {
            message = "§c先加载一个蓝图";
            return;
        }
        c.accept(p);
    }

    private void loadSelected() {
        if (selected == null) {
            message = "§c先在左边选一个蓝图";
            return;
        }
        try {
            Blueprint bp = BlueprintManager.load(selected);
            Minecraft mc = Minecraft.getInstance();
            Placement p = Placement.inFrontOf(bp, mc.player.blockPosition(), mc.player.getDirection());
            BuildController.setPlacement(p);
            message = "§a已加载 " + bp.name + "（" + bp.sizeText() + "，" + bp.solidCount() + " 个方块）";
        } catch (Exception ex) {
            message = "§c加载失败：" + ex.getMessage();
        }
    }

    private void printMaterials() {
        List<String> lines = BuildController.materialLines();
        if (lines.isEmpty()) {
            message = "§7还没有扫描结果";
            return;
        }
        BuildController.msg("材料清单：", ChatFormatting.AQUA);
        for (String l : lines) minecraft.player.displayClientMessage(Component.literal("  " + l), false);
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int y = height - 36;
        Placement p = BuildController.placement();
        if (p != null) {
            g.drawString(font, "§b" + p.describe(), 10, y, 0xFFFFFF);
            Scanner.Snapshot s = BuildController.snapshot();
            if (s != null) {
                g.drawString(font, "§7进度 " + s.percent() + "%  待放 " + s.place.size() + "  待挖 " + s.breaks.size()
                        + "  状态 " + BuildController.state() + "  " + BuildController.activity(), 10, y + 11, 0xFFFFFF);
            }
        } else {
            g.drawString(font, "§7蓝图放在：" + BlueprintManager.dir(), 10, y, 0xFFFFFF);
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
                String name = e.name();
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
