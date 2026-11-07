package com.autobuilder.gui;

import com.autobuilder.Lang;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.SupplyManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** 材料清单：每种材料还要多少、背包有多少、材料箱有多少、还缺多少；点一行去替换这种方块 */
public class MaterialsScreen extends Screen {
    private final Screen parent;
    private List<BuildController.Material> mats;
    private MatList list;
    private int lacking;
    private int refreshTicks;
    /** 蓝图用到的方块（替换只能换这些）；算一次要扫一遍整个蓝图，打开界面时算一次 */
    private java.util.Set<Block> used = java.util.Set.of();

    public MaterialsScreen(Screen parent) {
        super(Component.literal(Lang.t("materials.title")));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        list = new MatList(minecraft, width - 20, height - 32 - 44, 32, 20);
        list.setX(10);
        addRenderableWidget(list);
        Placement pl = BuildController.placement();
        used = pl == null ? java.util.Set.of() : pl.blockCounts().keySet();
        fill();
        int y = height - 28, n = 3, gap = 4;
        int bw = (width - 20 - gap * (n - 1)) / n;
        addRenderableWidget(btn(Lang.t("gui.replace"), 10, y, bw, () -> minecraft.setScreen(new ReplaceScreen(this, null))));
        addRenderableWidget(btn(Lang.t("materials.to_chat"), 10 + bw + gap, y, bw, () -> {
            List<String> lines = BuildController.materialLines();
            BuildController.msg(Lang.t("msg.materials_title"), ChatFormatting.AQUA);
            for (String l : lines) minecraft.player.displayClientMessage(Component.literal("  " + l), false);
            minecraft.setScreen(null);
        }));
        addRenderableWidget(btn(Lang.t("gui.back"), 10 + 2 * (bw + gap), y, bw, this::onClose));
    }

    private void fill() {
        mats = BuildController.materials();
        list.clear();
        lacking = 0;
        if (mats == null) return;
        for (BuildController.Material m : mats) {
            list.add(m);
            if (m.lack() > 0) lacking++;
        }
    }

    @Override
    public void tick() {
        // 背包、材料箱会变（正在建、刚补完货）：每秒刷新一次数字
        if (++refreshTicks % 20 == 0) {
            double sc = list.getScrollAmount();
            fill();
            list.setScrollAmount(sc);
        }
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** 列的位置：名字占左边，后面四列数字 */
    private int col(int i, int left, int w) {
        int nameW = Math.max(100, w - 4 * 56);
        return left + nameW + i * ((w - nameW) / 4);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Placement p = BuildController.placement();
        g.drawCenteredString(font, title.getString() + (p == null ? "" : "：" + p.bp.name), width / 2, 6, 0xFFFFFF);
        if (p == null) {
            g.drawCenteredString(font, "§c" + Lang.t("msg.no_blueprint"), width / 2, height / 2, 0xFFFFFF);
            return;
        }
        if (mats == null) {
            g.drawCenteredString(font, "§7" + Lang.t("gui.no_scan"), width / 2, height / 2, 0xFFFFFF);
            return;
        }
        int left = list.getRowLeft(), w = list.getRowWidth();
        g.drawString(font, "§7" + Lang.t("materials.col_name"), left + 20, 20, 0xFFFFFF);
        String[] heads = {"materials.col_need", "materials.col_have", "materials.col_box", "materials.col_lack"};
        for (int i = 0; i < 4; i++) g.drawString(font, "§7" + Lang.t(heads[i]), col(i, left, w), 20, 0xFFFFFF);
        String sum = mats.isEmpty() ? "§a" + Lang.t("materials.none") : (lacking > 0 ? "§e" : "§a") + Lang.t("materials.summary", mats.size(), lacking);
        int unknown = SupplyManager.unknownSupplies();
        if (unknown > 0) sum += "  §7" + Lang.t("msg.material_unknown", unknown);
        g.drawString(font, font.plainSubstrByWidth(sum, width - 20), 10, height - 40, 0xFFFFFF);
    }

    private class MatList extends ObjectSelectionList<MatList.Entry> {
        MatList(Minecraft mc, int w, int h, int y, int itemH) {
            super(mc, w, h, y, itemH);
        }

        void add(BuildController.Material m) {
            addEntry(new Entry(m));
        }

        void clear() {
            clearEntries();
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
            final BuildController.Material m;
            final ItemStack stack;

            Entry(BuildController.Material m) {
                this.m = m;
                this.stack = new ItemStack(m.item());
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                g.renderItem(stack, left + 1, top + 1);
                int nameW = col(0, left, width) - left - 24;
                g.drawString(font, (m.lack() > 0 ? "§f" : "§a") + font.plainSubstrByWidth(stack.getHoverName().getString(), nameW), left + 20, top + 6, 0xFFFFFF);
                int[] v = {m.need(), m.have(), m.box(), m.lack()};
                for (int i = 0; i < 4; i++) {
                    String t = i == 3 ? (m.lack() > 0 ? "§c" + m.lack() : "§a✔") : (v[i] > 0 ? "§f" : "§8") + v[i];
                    g.drawString(font, t, col(i, left, width), top + 6, 0xFFFFFF);
                }
                if (hovering && blockOf() != null) setTooltipForNextRenderPass(Component.literal(Lang.t("materials.click_replace")));
            }

            /** 这种材料对应蓝图里的哪种方块（能替换的才有） */
            Block blockOf() {
                Block b = Block.byItem(m.item());
                return used.contains(b) ? b : null;
            }

            @Override
            public boolean mouseClicked(double mx, double my, int button) {
                MatList.this.setSelected(this);
                Block b = blockOf();
                if (b != null) minecraft.setScreen(new ReplaceScreen(MaterialsScreen.this, b));
                return true;
            }

            @Override
            public Component getNarration() {
                return stack.getHoverName();
            }
        }
    }
}
