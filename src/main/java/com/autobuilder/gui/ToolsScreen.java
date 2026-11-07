package com.autobuilder.gui;

import com.autobuilder.Lang;
import com.autobuilder.blueprint.BlueprintSaver;
import com.autobuilder.build.Selection;
import com.autobuilder.build.SupplyManager;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** 保存建筑（选区 → .nbt）和材料箱管理 */
public class ToolsScreen extends Screen {
    private final Screen parent;
    private final BlockPos aimed; // 打开界面时准星对着的方块
    private EditBox name;
    private boolean includeAir = true;
    private String message = "";

    public ToolsScreen(Screen parent) {
        super(Component.literal(Lang.t("tools.title")));
        this.parent = parent;
        this.aimed = Selection.crosshairOrFeet();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int bw = 150, gap = 6;
        int x0 = cx - bw - gap / 2, x1 = cx + gap / 2;
        int y = 34;
        addRenderableWidget(btn(Lang.t("tools.corner1"), x0, y, bw, () -> {
            Selection.set(1, aimed);
            message = "§a" + Lang.t("tools.corner_set", 1, aimed.toShortString());
        }));
        addRenderableWidget(btn(Lang.t("tools.corner2"), x1, y, bw, () -> {
            Selection.set(2, aimed);
            message = "§a" + Lang.t("tools.corner_set", 2, aimed.toShortString());
        }));
        y += 26;
        name = new EditBox(font, x0, y, bw * 2 + gap - 0, 18, Component.literal(Lang.t("tools.name")));
        name.setMaxLength(64);
        name.setHint(Component.literal(Lang.t("tools.name_hint")));
        addRenderableWidget(name);
        y += 24;
        addRenderableWidget(Button.builder(airLabel(), b -> {
            includeAir = !includeAir;
            b.setMessage(airLabel());
        }).bounds(x0, y, bw, 20).build());
        // 从选区外面连进来的水（湖、河、海）存不存
        addRenderableWidget(Button.builder(waterLabel(), b -> {
            com.autobuilder.ABConfig.DROP_OUTER_WATER.set(!com.autobuilder.ABConfig.DROP_OUTER_WATER.get());
            com.autobuilder.ABConfig.SPEC.save();
            b.setMessage(waterLabel());
        }).bounds(x1, y, bw, 20).build());
        y += 24;
        addRenderableWidget(btn(Lang.t("tools.save"), x0, y, bw * 2 + gap, this::save));
        y += 26;
        // 材料箱（缺料时去取）、存放箱（砍树挖矿时存东西）：一行三个，界面矮放不下更多行
        int w3 = (bw * 2 + gap - 2 * gap) / 3;
        addRenderableWidget(btn(Lang.t("tools.supply_add"), x0, y, w3, () -> {
            if (!SupplyManager.isContainer(aimed)) {
                message = "§c" + Lang.t("tools.not_container");
                return;
            }
            message = SupplyManager.add(aimed) ? "§a" + Lang.t("tools.supply_added", aimed.toShortString()) : "§7" + Lang.t("tools.supply_exists");
        }));
        addRenderableWidget(btn(Lang.t("tools.dump_add"), x0 + w3 + gap, y, w3, () -> {
            if (!SupplyManager.isContainer(aimed)) {
                message = "§c" + Lang.t("tools.not_container");
                return;
            }
            message = SupplyManager.addDump(aimed) ? "§a" + Lang.t("tools.dump_added", aimed.toShortString()) : "§7" + Lang.t("tools.dump_exists");
        }));
        addRenderableWidget(btn(Lang.t("tools.supply_remove"), x0 + 2 * (w3 + gap), y, w3, () -> {
            boolean a = SupplyManager.remove(aimed), b = SupplyManager.removeDump(aimed);
            message = a || b ? "§a" + Lang.t("tools.supply_removed", aimed.toShortString()) : "§7" + Lang.t("tools.not_supply");
        }));
        y += 24;
        addRenderableWidget(btn(Lang.t("tools.supply_clear"), x0, y, w3, () -> {
            SupplyManager.clear();
            message = "§a" + Lang.t("tools.cleared");
        }));
        addRenderableWidget(btn(Lang.t("tools.dump_clear"), x0 + w3 + gap, y, w3, () -> {
            SupplyManager.clearDumps();
            message = "§a" + Lang.t("tools.cleared");
        }));
        addRenderableWidget(btn(Lang.t("gui.back"), x0 + 2 * (w3 + gap), y, w3, () -> minecraft.setScreen(parent)));
        y += 24;
        // 砍树、挖矿、挖空选区：参数在专门的界面里调
        addRenderableWidget(btn(Lang.t("tools.gather"), x0, y, w3 * 2 + gap, () -> minecraft.setScreen(new GatherScreen(this))));
        // 诊断日志：遇到问题点一下，记下当时的现场
        addRenderableWidget(btn(Lang.t("tools.log_mark"), x0 + 2 * (w3 + gap), y, w3, () -> {
            onClose();
            minecraft.player.connection.sendCommand("ab log mark");
        }));
    }

    private Component waterLabel() {
        return Component.literal(Lang.t(com.autobuilder.ABConfig.DROP_OUTER_WATER.get() ? "tools.water_drop" : "tools.water_keep"));
    }

    private Component airLabel() {
        return Component.literal(Lang.t(includeAir ? "tools.air_on" : "tools.air_off"));
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    private void save() {
        if (!Selection.complete()) {
            message = "§c" + Lang.t("tools.need_corners");
            return;
        }
        String n = name.getValue().trim();
        if (n.isEmpty()) {
            message = "§c" + Lang.t("tools.need_name");
            return;
        }
        try {
            BlueprintSaver.Result r = BlueprintSaver.save(minecraft.level, Selection.pos1(), Selection.pos2(), n, includeAir,
                    com.autobuilder.ABConfig.DROP_OUTER_WATER.get());
            message = "§a" + (r.droppedWater() > 0 ? Lang.t("tools.saved_water", r.path().getFileName(), r.droppedWater())
                    : Lang.t("tools.saved", r.path().getFileName()));
        } catch (Exception e) {
            message = "§c" + Lang.t("msg.save_failed", e.getMessage());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        g.drawCenteredString(font, "§7" + Lang.t("tools.status", aimed.toShortString(), Selection.describe()), width / 2, 22, 0xFFFFFF);
        List<BlockPos> sup = SupplyManager.list();
        StringBuilder sb = new StringBuilder("§7" + Lang.t("tools.supply_count", sup.size()));
        for (int i = 0; i < Math.min(4, sup.size()); i++) sb.append("  ").append(sup.get(i).toShortString());
        if (sup.size() > 4) sb.append(" …");
        sb.append("   ").append(Lang.t("tools.dump_count", SupplyManager.dumps().size()));
        g.drawCenteredString(font, sb.toString(), width / 2, height - 34, 0xFFFFFF);
        if (!message.isEmpty()) g.drawCenteredString(font, message, width / 2, height - 22, 0xFFFFFF);
    }
}
