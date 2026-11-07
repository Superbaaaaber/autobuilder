package com.autobuilder.gui;

import com.autobuilder.Lang;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.InventoryHelper;
import com.autobuilder.build.Placement;
import com.autobuilder.build.StateMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 材料替换界面：左边是蓝图用到的方块，右边是能换成的方块（图标网格，可以搜中文名或英文 id）。
 * 同类形状（楼梯换楼梯、台阶换台阶）排在前面，同类里背包有的排前面、标着数量。
 */
public class ReplaceScreen extends Screen {
    private static final int CELL = 18;
    private final Screen parent;
    private Block selected;
    private final Map<Block, Integer> counts = new java.util.LinkedHashMap<>();
    private final List<Block> all = new ArrayList<>();
    private List<Block> shown = new ArrayList<>();
    private final Map<Block, String> names = new HashMap<>();
    private final Map<Item, Integer> inv = new HashMap<>();
    private FromList fromList;
    private EditBox search;
    private int gx, gy, cols, rows, scroll;
    private String message = "";

    public ReplaceScreen(Screen parent, Block preselect) {
        super(Component.literal(Lang.t("replace.screen_title")));
        this.parent = parent;
        this.selected = preselect;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        Placement p = BuildController.placement();
        counts.clear();
        if (p != null) counts.putAll(p.blockCounts());
        if (selected != null && !counts.containsKey(selected)) selected = null;
        if (selected == null && !counts.isEmpty()) selected = counts.keySet().iterator().next();
        if (all.isEmpty()) {
            for (Block b : BuiltInRegistries.BLOCK) {
                if (b == Blocks.AIR || b.asItem() == Items.AIR) continue;
                if (StateMatcher.itemFor(b.defaultBlockState()) == null) continue;
                all.add(b);
            }
        }
        inv.clear();
        if (minecraft.player != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = minecraft.player.getInventory().getItem(i);
                if (!st.isEmpty()) inv.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }

        int leftW = Math.min(200, width * 2 / 5);
        fromList = new FromList(minecraft, leftW, height - 28 - 46, 28, 20);
        fromList.setX(10);
        for (var e : counts.entrySet()) fromList.add(e.getKey(), e.getValue());
        addRenderableWidget(fromList);

        gx = 20 + leftW;
        int gw = width - gx - 10;
        search = new EditBox(font, gx, 22, gw, 16, Component.literal(Lang.t("replace.search_hint")));
        search.setHint(Component.literal(Lang.t("replace.search_hint")));
        search.setResponder(s -> {
            scroll = 0;
            refilter();
        });
        addRenderableWidget(search);
        gy = 54;
        cols = Math.max(1, (gw - 6) / CELL);
        rows = Math.max(1, (height - 46 - gy) / CELL);

        int y = height - 28, n = 4, gap = 4;
        int bw = (width - 20 - gap * (n - 1)) / n;
        addRenderableWidget(btn(Lang.t("replace.skip_btn"), 10, y, bw, () -> apply(Blocks.AIR)));
        addRenderableWidget(btn(Lang.t("replace.undo_btn"), 10 + (bw + gap), y, bw, () -> apply(null)));
        addRenderableWidget(btn(Lang.t("replace.undo_all_btn"), 10 + 2 * (bw + gap), y, bw, () -> {
            Placement pl = BuildController.placement();
            if (pl == null) return;
            pl.clearReplace();
            message = "§a" + Lang.t("replace.cleared");
        }));
        addRenderableWidget(btn(Lang.t("gui.done"), 10 + 3 * (bw + gap), y, bw, this::onClose));
        refilter();
        if (selected != null) fromList.select(selected);
        if (p == null) message = "§c" + Lang.t("msg.no_blueprint");
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private String name(Block b) {
        return names.computeIfAbsent(b, k -> k.getName().getString());
    }

    private static Item icon(Block b) {
        Item it = StateMatcher.itemFor(b.defaultBlockState());
        return it != null ? it : b.asItem();
    }

    /** 形状分类：楼梯、台阶、墙、栅栏……整方块算一类 */
    private static String shapeClass(Block b) {
        if (b instanceof net.minecraft.world.level.block.StairBlock) return "stairs";
        if (b instanceof net.minecraft.world.level.block.SlabBlock) return "slab";
        if (b instanceof net.minecraft.world.level.block.WallBlock) return "wall";
        if (b instanceof net.minecraft.world.level.block.FenceGateBlock) return "gate";
        if (b instanceof net.minecraft.world.level.block.FenceBlock) return "fence";
        if (b instanceof net.minecraft.world.level.block.DoorBlock) return "door";
        if (b instanceof net.minecraft.world.level.block.TrapDoorBlock) return "trapdoor";
        if (b instanceof net.minecraft.world.level.block.ButtonBlock) return "button";
        if (b instanceof net.minecraft.world.level.block.BasePressurePlateBlock) return "plate";
        if (b instanceof net.minecraft.world.level.block.CarpetBlock) return "carpet";
        if (b instanceof net.minecraft.world.level.block.IronBarsBlock) return "pane";
        try {
            if (b.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return "full";
        } catch (RuntimeException ignored) {
            // 个别方块算形状要世界信息，算不了就当“其他”
        }
        return "other";
    }

    private void refilter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        String cls = selected == null ? null : shapeClass(selected);
        List<Block> out = new ArrayList<>();
        for (Block b : all) {
            if (b == selected) continue;
            if (!q.isEmpty()) {
                String id = BuiltInRegistries.BLOCK.getKey(b).toString();
                if (!name(b).toLowerCase(Locale.ROOT).contains(q) && !id.contains(q)) continue;
            }
            out.add(b);
        }
        Map<Block, String> cache = new HashMap<>();
        out.sort(Comparator.<Block>comparingInt(b -> cls != null && cls.equals(cache.computeIfAbsent(b, ReplaceScreen::shapeClass)) ? 0 : 1)
                .thenComparingInt(b -> -inv.getOrDefault(icon(b), 0)));
        shown = out;
        clampScroll();
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, (shown.size() + cols - 1) / cols - rows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** to = null：恢复原样；AIR：不建 */
    private void apply(Block to) {
        Placement p = BuildController.placement();
        if (p == null) {
            message = "§c" + Lang.t("msg.no_blueprint");
            return;
        }
        if (selected == null) {
            message = "§c" + Lang.t("replace.pick_left");
            return;
        }
        int used = counts.getOrDefault(selected, 0);
        if (to == null) {
            if (!p.replacements().containsKey(selected)) {
                message = "§7" + Lang.t("replace.not_replaced", name(selected));
                return;
            }
            p.setReplace(selected, null);
            message = "§a" + Lang.t("replace.restored", name(selected), used);
            return;
        }
        p.setReplace(selected, to);
        message = "§a" + (to == Blocks.AIR ? Lang.t("replace.set_skip", name(selected), used)
                : Lang.t("replace.set", name(selected), name(to), used));
    }

    private int cellAt(double mx, double my) {
        if (mx < gx || my < gy) return -1;
        int c = (int) ((mx - gx) / CELL), r = (int) ((my - gy) / CELL);
        if (c >= cols || r >= rows) return -1;
        int i = (scroll + r) * cols + c;
        return i < shown.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = cellAt(mx, my);
        if (i >= 0 && button == 0) {
            apply(shown.get(i));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= gx && my >= gy && my < gy + rows * CELL) {
            scroll -= (int) Math.signum(sy) * 2;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Placement p = BuildController.placement();
        g.drawCenteredString(font, title.getString() + (p == null ? "" : "：" + p.bp.name), width / 2, 6, 0xFFFFFF);
        g.drawString(font, "§7" + Lang.t("replace.left_title"), 10, 17, 0xFFFFFF);
        Block cur = selected == null || p == null ? null : p.replacements().get(selected);
        // 网格
        int hover = cellAt(mouseX, mouseY);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int i = (scroll + r) * cols + c;
                if (i >= shown.size()) break;
                Block b = shown.get(i);
                int x = gx + c * CELL, y = gy + r * CELL;
                if (b == cur) g.fill(x, y, x + CELL, y + CELL, 0x8040C040);
                else if (i == hover) g.fill(x, y, x + CELL, y + CELL, 0x50FFFFFF);
                ItemStack st = new ItemStack(icon(b));
                g.renderItem(st, x + 1, y + 1);
                int have = inv.getOrDefault(icon(b), 0);
                if (have > 0) g.renderItemDecorations(font, st, x + 1, y + 1, have > 999 ? "999+" : String.valueOf(have));
            }
        int total = (shown.size() + cols - 1) / cols;
        if (total > rows) {
            // 滚动条
            int bh = rows * CELL, x = gx + cols * CELL + 2;
            int th = Math.max(8, bh * rows / total), ty = gy + (bh - th) * scroll / Math.max(1, total - rows);
            g.fill(x, gy, x + 3, gy + bh, 0x40FFFFFF);
            g.fill(x, ty, x + 3, ty + th, 0xC0FFFFFF);
        }
        String hint = selected == null ? Lang.t("replace.pick_left")
                : Lang.t("replace.grid_hint", name(selected), shown.size());
        g.drawString(font, "§7" + font.plainSubstrByWidth(hint, width - gx - 10), gx, gy - 11, 0xFFFFFF);
        if (!message.isEmpty()) g.drawString(font, font.plainSubstrByWidth(message, width - 20), 10, height - 40, 0xFFFFFF);
        if (hover >= 0) {
            Block b = shown.get(hover);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(name(b)));
            tip.add(Component.literal("§7" + BuiltInRegistries.BLOCK.getKey(b)));
            int have = inv.getOrDefault(icon(b), 0);
            if (have > 0) tip.add(Component.literal("§a" + Lang.t("replace.tip_inv", have)));
            if (selected != null) tip.add(Component.literal("§e" + Lang.t("replace.tip_click", name(selected))));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    private class FromList extends ObjectSelectionList<FromList.Entry> {
        FromList(Minecraft mc, int w, int h, int y, int itemH) {
            super(mc, w, h, y, itemH);
        }

        void add(Block b, int n) {
            addEntry(new Entry(b, n));
        }

        void select(Block b) {
            for (Entry e : children()) {
                if (e.block == b) {
                    setSelected(e);
                    ensureVisible(e);
                }
            }
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
            final Block block;
            final int count;

            Entry(Block block, int count) {
                this.block = block;
                this.count = count;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                g.renderItem(new ItemStack(icon(block)), left + 1, top + 1);
                Placement p = BuildController.placement();
                Block to = p == null ? null : p.replacements().get(block);
                String head = name(block) + " §7×" + count;
                int textW = width - 22 - (to != null ? 20 : 0);
                g.drawString(font, (to != null ? "§e" : "§f") + font.plainSubstrByWidth(head, textW), left + 20, top + 6, 0xFFFFFF);
                if (to == Blocks.AIR) {
                    g.drawString(font, "§6" + Lang.t("replace.skip"), left + width - font.width(Lang.t("replace.skip")) - 2, top + 6, 0xFFFFFF);
                } else if (to != null) {
                    g.renderItem(new ItemStack(icon(to)), left + width - 18, top + 1);
                }
                if (hovering && to != null) {
                    setTooltipForNextRenderPass(Component.literal(Lang.t("replace.tip_now", name(block),
                            to == Blocks.AIR ? Lang.t("replace.skip") : name(to))));
                }
            }

            @Override
            public boolean mouseClicked(double mx, double my, int button) {
                FromList.this.setSelected(this);
                selected = block;
                scroll = 0;
                refilter();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(name(block));
            }
        }
    }
}
