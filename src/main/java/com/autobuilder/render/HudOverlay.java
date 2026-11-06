package com.autobuilder.render;

import com.autobuilder.Lang;
import com.autobuilder.AutoBuilder;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Scanner;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** 屏幕右上角的状态信息 */
public final class HudOverlay {
    private HudOverlay() {}

    public static void register(RegisterGuiLayersEvent e) {
        e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(AutoBuilder.MODID, "hud"), HudOverlay::render);
    }

    private static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        Placement pl = BuildController.placement();
        if (pl == null || mc.options.hideGui || mc.player == null) return;
        List<String> lines = new ArrayList<>();
        String st = switch (BuildController.state()) {
            case IDLE -> "§7" + Lang.t("hud.idle");
            case RUNNING -> BuildController.phase() == BuildController.Phase.BUILD ? "§a" + Lang.t("hud.building") : "§b" + Lang.t("hud.cleanup");
            case PAUSED -> "§e" + Lang.t("hud.paused");
            case DONE -> "§a" + Lang.t("hud.done");
        };
        lines.add("§6AutoBuilder §r" + st + " §7| §f" + pl.bp.name);
        Scanner.Snapshot s = BuildController.snapshot();
        if (s != null) {
            lines.add("§f" + Lang.t("hud.progress", s.percent(), s.ok, s.total, s.place.size(), s.breaks.size())
                    + (s.wrong.isEmpty() ? "" : "  §d" + Lang.t("hud.wrong", s.wrong.size())));
        } else {
            lines.add("§7" + Lang.t("hud.scanning"));
        }
        String act = BuildController.activity();
        if (!act.isEmpty()) lines.add("§7" + act);
        if (!BuildController.missing().isEmpty()) lines.add("§c" + Lang.t("hud.missing", BuildController.missingText(4)));
        int y = 4;
        int sw = g.guiWidth();
        for (String line : lines) {
            int w = mc.font.width(line);
            g.fill(sw - w - 6, y - 1, sw - 2, y + 9, 0x80000000);
            g.drawString(mc.font, line, sw - w - 4, y, 0xFFFFFF, false);
            y += 10;
        }
    }
}
