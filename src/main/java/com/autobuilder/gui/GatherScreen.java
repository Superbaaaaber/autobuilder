package com.autobuilder.gui;

import com.autobuilder.ABConfig;
import com.autobuilder.Lang;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Gather;
import com.autobuilder.build.Selection;
import com.autobuilder.build.SupplyManager;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** 砍树、支巷挖矿、透视挖矿、挖空选区：参数用滑块调，选好直接开始；参数记进设置，下次还是这个 */
public class GatherScreen extends Screen {
    private final Screen parent;
    private IntSlider chopR, mineLen, mineBranch, oreR, layers;
    private int oreKind;
    private Button oreBtn;
    private String message = "";
    private int labelX, rowY0;
    private static final int ROW = 32;

    public GatherScreen(Screen parent) {
        super(Component.literal(Lang.t("gather.screen_title")));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 每种矿用哪个方块的名字和图标 */
    private static Block oreBlock(String kind) {
        return switch (kind) {
            case "diamond" -> Blocks.DIAMOND_ORE;
            case "iron" -> Blocks.IRON_ORE;
            case "gold" -> Blocks.GOLD_ORE;
            case "redstone" -> Blocks.REDSTONE_ORE;
            case "lapis" -> Blocks.LAPIS_ORE;
            case "emerald" -> Blocks.EMERALD_ORE;
            case "coal" -> Blocks.COAL_ORE;
            case "copper" -> Blocks.COPPER_ORE;
            case "debris" -> Blocks.ANCIENT_DEBRIS;
            case "quartz" -> Blocks.NETHER_QUARTZ_ORE;
            default -> null;
        };
    }

    private Component oreLabel() {
        String kind = Gather.ORE_KINDS.get(oreKind);
        Block b = oreBlock(kind);
        return Component.literal(Lang.t("gather.kind", b == null ? Lang.t("gather.kind_all") : b.getName().getString()));
    }

    @Override
    protected void init() {
        int labelW = 70, startW = 56, gap = 4;
        int total = Math.min(width - 20, 420);
        labelX = (width - total) / 2;
        int cx = labelX + labelW, ctrlW = total - labelW - startW - gap;
        int half = (ctrlW - gap) / 2;
        int sx = labelX + total - startW;
        rowY0 = 32;
        int y = rowY0;
        // 砍树
        chopR = addRenderableWidget(new IntSlider(cx, y, ctrlW, "gather.radius", 4, 64, ABConfig.CHOP_RADIUS.get()));
        addRenderableWidget(btn(Lang.t("gather.start_btn"), sx, y, startW, () -> {
            ABConfig.CHOP_RADIUS.set(chopR.value());
            run("ab chop " + chopR.value());
        }));
        y += ROW;
        // 支巷挖矿
        mineLen = addRenderableWidget(new IntSlider(cx, y, half, "gather.length", 4, 256, ABConfig.MINE_LENGTH.get()));
        mineBranch = addRenderableWidget(new IntSlider(cx + half + gap, y, half, "gather.branch", 0, 64, ABConfig.MINE_BRANCH.get()));
        addRenderableWidget(btn(Lang.t("gather.start_btn"), sx, y, startW, () -> {
            ABConfig.MINE_LENGTH.set(mineLen.value());
            ABConfig.MINE_BRANCH.set(mineBranch.value());
            run("ab mine " + mineLen.value() + " " + mineBranch.value());
        }));
        y += ROW;
        // 透视挖矿：矿种按钮点一下换一种
        oreBtn = addRenderableWidget(Button.builder(oreLabel(), b -> {
            oreKind = (oreKind + (hasShiftDown() ? Gather.ORE_KINDS.size() - 1 : 1)) % Gather.ORE_KINDS.size();
            b.setMessage(oreLabel());
        }).bounds(cx, y, half, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(Lang.t("gather.kind_tip")))).build());
        oreR = addRenderableWidget(new IntSlider(cx + half + gap, y, half, "gather.radius", 4, 64, ABConfig.ORE_RADIUS.get()));
        addRenderableWidget(btn(Lang.t("gather.start_btn"), sx, y, startW, () -> {
            ABConfig.ORE_RADIUS.set(oreR.value());
            run("ab ore " + Gather.ORE_KINDS.get(oreKind) + " " + oreR.value());
        }));
        y += ROW;
        // 挖空选区
        layers = addRenderableWidget(new IntSlider(cx, y, half, "gather.layers", 1, 4, ABConfig.EXCAVATE_LAYERS.get()));
        addRenderableWidget(btn(Lang.t("gather.start_btn"), sx, y, startW, () -> {
            if (!Selection.complete()) {
                message = "§c" + Lang.t("tools.need_corners");
                return;
            }
            ABConfig.EXCAVATE_LAYERS.set(layers.value());
            run("ab excavate");
        }));
        y += ROW + 4;
        int n = 3, bw = (total - gap * (n - 1)) / n;
        addRenderableWidget(btn(Lang.t("gather.stop_btn"), labelX, y, bw, () -> {
            BuildController.stop();
            message = "§a" + Lang.t("gather.stopped");
        }));
        addRenderableWidget(btn(Lang.t("gui.tools"), labelX + bw + gap, y, bw, () -> minecraft.setScreen(new ToolsScreen(this))));
        addRenderableWidget(btn(Lang.t("gui.back"), labelX + 2 * (bw + gap), y, bw, this::onClose));
    }

    private void run(String cmd) {
        ABConfig.SPEC.save();
        minecraft.setScreen(null);
        minecraft.player.connection.sendCommand(cmd);
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    @Override
    public void onClose() {
        // 没开始也记住调过的数
        if (chopR != null) {
            ABConfig.CHOP_RADIUS.set(chopR.value());
            ABConfig.MINE_LENGTH.set(mineLen.value());
            ABConfig.MINE_BRANCH.set(mineBranch.value());
            ABConfig.ORE_RADIUS.set(oreR.value());
            ABConfig.EXCAVATE_LAYERS.set(layers.value());
            ABConfig.SPEC.save();
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        String[] labels = {"gather.chop_label", "gather.mine_label", "gather.ore_label", "gather.excavate_label"};
        net.minecraft.world.item.Item[] icons = {net.minecraft.world.item.Items.IRON_AXE, net.minecraft.world.item.Items.IRON_PICKAXE,
                net.minecraft.world.item.Items.DIAMOND_ORE, net.minecraft.world.item.Items.DIAMOND_SHOVEL};
        String[] notes = {Lang.t("gather.chop_note"), Lang.t("gather.mine_note"), Lang.t("gather.ore_note"),
                Selection.complete() ? Lang.t("gather.sel_status", Selection.describe()) : "§c" + Lang.t("tools.need_corners")};
        for (int i = 0; i < 4; i++) {
            int y = rowY0 + i * ROW;
            g.renderItem(new ItemStack(icons[i]), labelX, y + 2);
            g.drawString(font, Lang.t(labels[i]), labelX + 19, y + 6, 0xFFFFFF);
            g.drawString(font, "§7" + font.plainSubstrByWidth(notes[i], width - labelX - 20 - 70), labelX + 70, y + 22, 0xFFFFFF);
        }
        int y = rowY0 + 4 * ROW + 28;
        List<net.minecraft.core.BlockPos> dumps = SupplyManager.dumps();
        g.drawCenteredString(font, "§7" + Lang.t("gather.dump_status", dumps.size()), width / 2, y, 0xFFFFFF);
        if (!message.isEmpty()) g.drawCenteredString(font, message, width / 2, y + 12, 0xFFFFFF);
    }
}
