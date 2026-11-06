package com.autobuilder.gui;

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
        super(Component.literal("保存建筑 / 材料箱"));
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
        addRenderableWidget(btn("角1 = 准星方块", x0, y, bw, () -> {
            Selection.set(1, aimed);
            message = "§a角1 = " + aimed.toShortString();
        }));
        addRenderableWidget(btn("角2 = 准星方块", x1, y, bw, () -> {
            Selection.set(2, aimed);
            message = "§a角2 = " + aimed.toShortString();
        }));
        y += 36;
        name = new EditBox(font, x0, y, bw * 2 + gap - 0, 18, Component.literal("名字"));
        name.setMaxLength(64);
        name.setHint(Component.literal("蓝图名字，例如 我的房子"));
        addRenderableWidget(name);
        y += 24;
        addRenderableWidget(Button.builder(airLabel(), b -> {
            includeAir = !includeAir;
            b.setMessage(airLabel());
        }).bounds(x0, y, bw, 20).build());
        addRenderableWidget(btn("保存选区", x1, y, bw, this::save));
        y += 34;
        addRenderableWidget(btn("准星处箱子设为材料箱", x0, y, bw, () -> {
            if (!SupplyManager.isContainer(aimed)) {
                message = "§c准星对着的不是箱子/木桶/潜影盒";
                return;
            }
            message = SupplyManager.add(aimed) ? "§a已添加材料箱 " + aimed.toShortString() : "§7已经是材料箱了";
        }));
        addRenderableWidget(btn("移除准星处材料箱", x1, y, bw, () ->
                message = SupplyManager.remove(aimed) ? "§a已移除 " + aimed.toShortString() : "§7这里不是材料箱"));
        y += 24;
        addRenderableWidget(btn("清空材料箱列表", x0, y, bw, () -> {
            SupplyManager.clear();
            message = "§a已清空";
        }));
        addRenderableWidget(btn("返回", x1, y, bw, () -> minecraft.setScreen(parent)));
    }

    private Component airLabel() {
        return Component.literal(includeAir ? "空格：保存（会清理）" : "空格：不保存");
    }

    private Button btn(String text, int x, int y, int w, Runnable r) {
        return Button.builder(Component.literal(text), b -> r.run()).bounds(x, y, w, 20).build();
    }

    private void save() {
        if (!Selection.complete()) {
            message = "§c先设好两个角";
            return;
        }
        String n = name.getValue().trim();
        if (n.isEmpty()) {
            message = "§c先输入名字";
            return;
        }
        try {
            Path out = BlueprintSaver.save(minecraft.level, Selection.pos1(), Selection.pos2(), n, includeAir);
            message = "§a已保存：" + out.getFileName();
        } catch (Exception e) {
            message = "§c保存失败：" + e.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        g.drawCenteredString(font, "§7准星对着：" + aimed.toShortString() + "    选区：" + Selection.describe(), width / 2, 22, 0xFFFFFF);
        List<BlockPos> sup = SupplyManager.list();
        StringBuilder sb = new StringBuilder("§7材料箱 " + sup.size() + " 个");
        for (int i = 0; i < Math.min(4, sup.size()); i++) sb.append("  ").append(sup.get(i).toShortString());
        if (sup.size() > 4) sb.append(" …");
        g.drawCenteredString(font, sb.toString(), width / 2, height - 34, 0xFFFFFF);
        if (!message.isEmpty()) g.drawCenteredString(font, message, width / 2, height - 22, 0xFFFFFF);
    }
}
