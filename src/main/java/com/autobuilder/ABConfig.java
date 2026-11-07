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
    public static final ModConfigSpec.BooleanValue DRAIN_WATER;
    public static final ModConfigSpec.BooleanValue SUB_WOOD;
    public static final ModConfigSpec.BooleanValue SUB_COLOR;
    public static final ModConfigSpec.BooleanValue SEARCH_CONTAINERS;
    public static final ModConfigSpec.IntValue SEARCH_RADIUS;
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
    public static final ModConfigSpec.BooleanValue HIDE_WHILE_BUILDING;
    public static final ModConfigSpec.BooleanValue SELECTION_WAND;
    public static final ModConfigSpec.ConfigValue<String> WAND_ITEM;
    public static final ModConfigSpec.BooleanValue DROP_OUTER_WATER;
    public static final ModConfigSpec.BooleanValue REPLANT;
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
        DRAIN_WATER = b.comment("Drain water: fill water/lava sources where the blueprint has air (the filler is removed again like scaffolding), and scoop water out of blocks that got waterlogged with an empty bucket")
                .define("drainWater", true);
        SUB_WOOD = b.comment("When a wood type runs out, use another wood of the same shape (oak planks for birch planks, spruce stairs for oak stairs...). Blocks of another wood already in place count as correct")
                .define("substituteWood", true);
        SUB_COLOR = b.comment("When a colour runs out, use another colour of the same block (beds, wool, carpet, concrete, stained glass and panes, terracotta, candles; plain glass counts too)")
                .define("substituteColor", true);
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
        SEARCH_CONTAINERS = b.comment("When materials run out, also open nearby chests, barrels and shulker boxes (not just registered supply chests) and take what's missing. Nothing is put into them. On multiplayer this opens other players' chests too")
                .define("searchNearbyContainers", true);
        SEARCH_RADIUS = b.comment("How far to look for nearby containers (blocks)")
                .defineInRange("searchRadius", 24, 4, 64);
        WORK_SLOTS = b.comment("Hotbar slots (0-8) used when swapping items in from the inventory")
                .defineListAllowEmpty("workSlots", List.of(6, 7, 8), () -> 8,
                        o -> o instanceof Integer i && i >= 0 && i <= 8);
        b.pop();

        b.push("render");
        RENDER_GHOSTS = b.comment("Render translucent blocks still to be placed")
                .define("renderGhosts", true);
        RENDER_RANGE = b.comment("Preview render distance (blocks)")
                .defineInRange("renderRange", 48, 8, 256);
        HIDE_WHILE_BUILDING = b.comment("Hide the blueprint preview while building (it still works on its own). The hide-preview key toggles it any time")
                .define("hideWhileBuilding", false);
        b.pop();

        b.push("tools");
        SELECTION_WAND = b.comment("Selection wand: hold the wand item, left-click a block to set corner 1, right-click to set corner 2 (up to 128 blocks away)")
                .define("selectionWand", true);
        WAND_ITEM = b.comment("Item used as the selection wand")
                .define("wandItem", "minecraft:stick", o -> o instanceof String str && net.minecraft.resources.ResourceLocation.tryParse(str) != null);
        DROP_OUTER_WATER = b.comment("When saving, leave out water that continues outside the selection (lakes, rivers, sea). Water fully inside the selection, like a farm's water streams, is kept")
                .define("dropOuterWater", true);
        b.pop();

        b.push("gather");
        REPLANT = b.comment("Tree chopping: replant a sapling where a single-trunk tree stood, if you have one")
                .define("replantSaplings", true);
        b.pop();

        SPEC = b.build();
    }

    private ABConfig() {}
}
