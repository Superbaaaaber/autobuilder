package com.autobuilder;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

/** 客户端配置，文件在 config/autobuilder-client.toml，也可以在模组列表里点“配置”修改。配置文件注释用英文，界面提示走语言文件。 */
public final class ABConfig {
    public enum PlaceMode implements TranslatableEnum {
        AIRPLACE, ADJACENT;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("autobuilder.configuration.placeMode." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue REACH;
    public static final ModConfigSpec.IntValue ACTION_DELAY;
    public static final ModConfigSpec.IntValue ACTIONS_PER_TICK;
    public static final ModConfigSpec.EnumValue<PlaceMode> PLACE_MODE;
    public static final ModConfigSpec.BooleanValue SILENT_ROTATION;
    public static final ModConfigSpec.BooleanValue BREAK_WRONG;
    public static final ModConfigSpec.BooleanValue CLEAR_AIR;
    public static final ModConfigSpec.BooleanValue ALLOW_SCAFFOLD;
    public static final ModConfigSpec.BooleanValue REMOVE_SCAFFOLD;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCAFFOLD_BLOCKS;
    public static final ModConfigSpec.BooleanValue ALLOW_DIG;
    public static final ModConfigSpec.IntValue MAX_FALL;
    public static final ModConfigSpec.BooleanValue AUTO_EAT;
    public static final ModConfigSpec.IntValue EAT_BELOW;
    public static final ModConfigSpec.IntValue STOP_BELOW_HEALTH;
    public static final ModConfigSpec.BooleanValue PAUSE_ON_INPUT;
    public static final ModConfigSpec.BooleanValue RENDER_GHOSTS;
    public static final ModConfigSpec.IntValue RENDER_RANGE;
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> WORK_SLOTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("build");
        REACH = b.comment("Reach: max distance from your eyes to the nearest point of a block. Vanilla servers allow 4.5+1=5.5, so keep a margin")
                .defineInRange("reach", 4.8, 1.0, 5.4);
        ACTION_DELAY = b.comment("Ticks between actions (place / break / interact). 0 = every tick")
                .defineInRange("actionDelay", 1, 0, 20);
        ACTIONS_PER_TICK = b.comment("Max blocks placed per action")
                .defineInRange("actionsPerTick", 1, 1, 8);
        PLACE_MODE = b.comment("AIRPLACE: place directly at the target cell, no supporting face needed. ADJACENT: click the face of a neighbouring block (more human-like; use it on servers with anti-cheat)")
                .defineEnum("placeMode", PlaceMode.AIRPLACE);
        SILENT_ROTATION = b.comment("When placing directional blocks, only send rotation packets; your camera doesn't move")
                .define("silentRotation", true);
        BREAK_WRONG = b.comment("Break wrong blocks at blueprint positions")
                .define("breakWrongBlocks", true);
        CLEAR_AIR = b.comment("Break blocks where the blueprint has air (e.g. dirt inside a house)")
                .define("clearAirBlocks", true);
        b.pop();

        b.push("movement");
        ALLOW_SCAFFOLD = b.comment("Allow pillaring up and bridging with blocks")
                .define("allowScaffold", true);
        REMOVE_SCAFFOLD = b.comment("Remove your own scaffolding when finished")
                .define("removeScaffold", true);
        SCAFFOLD_BLOCKS = b.comment("Blocks that may be used as scaffolding")
                .defineListAllowEmpty("scaffoldBlocks",
                        List.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
                                "minecraft:netherrack", "minecraft:andesite", "minecraft:diorite",
                                "minecraft:granite", "minecraft:tuff", "minecraft:stone"),
                        () -> "minecraft:dirt", o -> o instanceof String);
        ALLOW_DIG = b.comment("Allow digging through terrain while pathfinding")
                .define("allowDig", true);
        MAX_FALL = b.comment("Max fall height while pathfinding (blocks)")
                .defineInRange("maxFall", 3, 1, 3);
        b.pop();

        b.push("safety");
        AUTO_EAT = b.comment("Eat food from your inventory when hungry")
                .define("autoEat", true);
        EAT_BELOW = b.comment("Eat when hunger is below this (full = 20)")
                .defineInRange("eatBelow", 14, 1, 19);
        STOP_BELOW_HEALTH = b.comment("Stop building when health is below this (full = 20)")
                .defineInRange("stopBelowHealth", 8, 0, 19);
        PAUSE_ON_INPUT = b.comment("Pause when you press a movement key (to take over manually)")
                .define("pauseOnInput", true);
        WORK_SLOTS = b.comment("Hotbar slots (0-8) used when swapping items in from the inventory")
                .defineListAllowEmpty("workSlots", List.of(6, 7, 8), () -> 8,
                        o -> o instanceof Integer i && i >= 0 && i <= 8);
        b.pop();

        b.push("render");
        RENDER_GHOSTS = b.comment("Render translucent blocks still to be placed")
                .define("renderGhosts", true);
        RENDER_RANGE = b.comment("Preview render distance (blocks)")
                .defineInRange("renderRange", 48, 8, 256);
        b.pop();

        SPEC = b.build();
    }

    private ABConfig() {}
}
