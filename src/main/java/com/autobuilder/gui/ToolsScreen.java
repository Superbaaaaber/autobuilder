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
        y += 36;
        name = new EditBox(font, x0, y, bw * 2 + gap - 0, 18, Component.literal(Lang.t("tools.name")));
        name.setMaxLength(64);
        name.setHint(Component.literal(Lang.t("tools.name_hint")));
        addRenderableWidget(name);
        y += 24;
        addRenderableWidget(Button.builder(airLabel(), b -> {
            includeAir = !includeAir;
            b.setMessage(airLabel());
        }).bounds(x0, y, bw, 20).build());
        addRenderableWidget(btn(Lang.t("tools.save"), x1, y, bw, this::save));
        y += 34;
        addRenderableWidget(btn(Lang.t("tools.supply_add"), x0, y, bw, () -> {
            if (!SupplyManager.isContainer(aimed)) {
                message = "§c" + Lang.t("tools.not_container");
                return;
            }
            message = SupplyManager.add(aimed) ? "§a" + Lang.t("tools.supply_added", aimed.toShortString()) : "§7" + Lang.t("tools.supply_exists");
        }));
        addRenderableWidget(btn(Lang.t("tools.supply_remove"), x1, y, bw, () ->
                message = SupplyManager.remove(aimed) ? "§a" + Lang.t("tools.supply_removed", aimed.toShortString()) : "§7" + Lang.t("tools.not_supply")));
        y += 24;
        addRenderableWidget(btn(Lang.t("tools.supply_clear"), x0, y, bw, () -> {
            SupplyManager.clear();
            message = "§a" + Lang.t("tools.cleared");
        }));
        addRenderableWidget(btn(Lang.t("gui.back"), x1, y, bw, () -> minecraft.setScreen(parent)));
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
            Path out = BlueprintSaver.save(minecraft.level, Selection.pos1(), Selection.pos2(), n, includeAir);
            message = "§a" + Lang.t("tools.saved", out.getFileName());
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
        g.drawCenteredString(font, sb.toString(), width / 2, height - 34, 0xFFFFFF);
        if (!message.isEmpty()) g.drawCenteredString(font, message, width / 2, height - 22, 0xFFFFFF);
    }
}
