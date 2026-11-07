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
    public static final ModConfigSpec.BooleanValue BLOCK_INFLOW;
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
    public static final ModConfigSpec.IntValue CHOP_RADIUS;
    public static final ModConfigSpec.BooleanValue PROTECT_BUILDINGS;
    public static final ModConfigSpec.IntValue ORE_RADIUS;
    public static final ModConfigSpec.IntValue MINE_LENGTH;
    public static final ModConfigSpec.IntValue MINE_BRANCH;
    public static final ModConfigSpec.IntValue EXCAVATE_LAYERS;
    public static final ModConfigSpec.BooleanValue EXCAVATE_KEEP_CONTAINERS;
    public static final ModConfigSpec.IntValue MAX_EXCAVATE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> JUNK_ITEMS;
    public static final ModConfigSpec.IntValue KEEP_SCAFFOLD_DROP;
    public static final ModConfigSpec.IntValue DEPOSIT_FREE_SLOTS;
    public static final ModConfigSpec.BooleanValue DEPOSIT_AT_END;
    public static final ModConfigSpec.IntValue KEEP_SCAFFOLD_STORE;
    public static final ModConfigSpec.BooleanValue PICKUP_JUNK;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NEVER_STORE;
    public static final ModConfigSpec.IntValue GHOST_OPACITY;
    public static final ModConfigSpec.IntValue MAX_GHOSTS;
    public static final ModConfigSpec.BooleanValue SHOW_BREAK_OUTLINES;
    public static final ModConfigSpec.BooleanValue SHOW_HUD;
    public static final ModConfigSpec.BooleanValue DIAG_LOG;
    public static final ModConfigSpec.IntValue DIAG_KEEP;
    public static final ModConfigSpec.IntValue DIAG_STUCK_SECONDS;
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
        BLOCK_INFLOW = b.comment("When water from outside (a lake, a stream, a source next to the build) flows into the blueprint where it wants no water, block it with a block just outside the blueprint's edge, so the water inside can be drained. These blocks stay after the build (removing them lets the water back in)")
                .define("blockInflow", true);
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
        GHOST_OPACITY = b.comment("Opacity of the translucent preview blocks (percent)")
                .defineInRange("ghostOpacity", 45, 5, 100);
        MAX_GHOSTS = b.comment("Max translucent blocks and outlines drawn; lower it if the preview makes the game lag")
                .defineInRange("maxGhosts", 4000, 100, 50000);
        SHOW_BREAK_OUTLINES = b.comment("Outline the blocks that are going to be broken (red boxes)")
                .define("showBreakOutlines", true);
        SHOW_HUD = b.comment("Show the status panel (state, progress, current action) in the top-right corner")
                .define("showHud", true);
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
        CHOP_RADIUS = b.comment("Default radius for /ab chop (blocks)")
                .defineInRange("chopRadius", 24, 4, 64);
        PROTECT_BUILDINGS = b.comment("Tree chopping: leave logs touching planks, glass, doors and other building blocks alone, so log houses are safe")
                .define("protectBuildings", true);
        ORE_RADIUS = b.comment("Default radius for /ab ore (blocks)")
                .defineInRange("oreRadius", 32, 4, 64);
        MINE_LENGTH = b.comment("Default main tunnel length for /ab mine")
                .defineInRange("mineLength", 48, 4, 256);
        MINE_BRANCH = b.comment("Default side tunnel length for /ab mine")
                .defineInRange("mineBranch", 12, 0, 64);
        EXCAVATE_LAYERS = b.comment("Excavate: how many layers it digs at a time from the top. More is faster but leaves deeper holes around its feet")
                .defineInRange("excavateLayers", 2, 1, 4);
        EXCAVATE_KEEP_CONTAINERS = b.comment("Excavate: leave chests, barrels, furnaces, ender chests and other containers in place")
                .define("excavateKeepContainers", true);
        MAX_EXCAVATE = b.comment("Excavate: largest selection allowed (blocks); a full scan of a huge one takes seconds")
                .defineInRange("maxExcavate", 1_000_000, 1000, 8_000_000);
        JUNK_ITEMS = b.comment("Items that may be thrown away when the inventory is full while chopping, mining or excavating (and no dump chest can take them)")
                .defineListAllowEmpty("junkItems",
                        List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:gravel",
                                "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:calcite",
                                "minecraft:netherrack", "minecraft:dripstone_block", "minecraft:pointed_dripstone", "minecraft:coarse_dirt",
                                "minecraft:rooted_dirt", "minecraft:stone", "minecraft:deepslate"),
                        () -> "minecraft:cobblestone", o -> o instanceof String);
        KEEP_SCAFFOLD_DROP = b.comment("Scaffold blocks kept when throwing junk away; without a dump chest, junk on the ground is only picked up while you have fewer than this")
                .defineInRange("keepScaffoldWhenDropping", 128, 0, 1024);
        b.pop();

        b.push("storage");
        DEPOSIT_FREE_SLOTS = b.comment("While chopping, mining or excavating, go to a dump chest (/ab dump add) when fewer than this many inventory slots are free")
                .defineInRange("depositWhenFreeSlotsBelow", 2, 1, 18);
        DEPOSIT_AT_END = b.comment("When the whole job is done, store what it gathered in a dump chest")
                .define("depositAtEnd", true);
        KEEP_SCAFFOLD_STORE = b.comment("Scaffold blocks kept when storing (of the kind you have most of)")
                .defineInRange("keepScaffoldWhenStoring", 64, 0, 1024);
        PICKUP_JUNK = b.comment("With a dump chest, also pick up cobblestone, dirt and other junk so it gets stored")
                .define("pickUpJunk", true);
        NEVER_STORE = b.comment("Items never put into a dump chest (tools, armour, food, buckets and shulker boxes always stay with you)")
                .defineListAllowEmpty("neverStore", List.of("minecraft:torch", "minecraft:soul_torch"),
                        () -> "minecraft:torch", o -> o instanceof String);
        b.pop();

        b.push("diagnostics");
        DIAG_LOG = b.comment("Write a diagnostic log to autobuild/logs/ while building, chopping, mining or excavating: every action, why paths failed, a status line per second, and a snapshot of the surroundings when it looks stuck. Use /ab log mark <note> to mark the moment something goes wrong")
                .define("diagnosticLog", true);
        DIAG_KEEP = b.comment("How many log files to keep (oldest are deleted)")
                .defineInRange("logFilesKept", 10, 1, 100);
        DIAG_STUCK_SECONDS = b.comment("Take a snapshot when position and action haven't changed for this many seconds")
                .defineInRange("stuckSnapshotSeconds", 15, 5, 300);
        b.pop();

        SPEC = b.build();
    }

    private ABConfig() {}

    private static final java.util.Map<Object, Object[]> ITEM_CACHE = new java.util.HashMap<>();

    /** 物品 id 列表设置 → 物品集合（写错的 id 跳过）；列表没变就用缓存 */
    @SuppressWarnings("unchecked")
    public static java.util.Set<net.minecraft.world.item.Item> items(ModConfigSpec.ConfigValue<List<? extends String>> v) {
        List<? extends String> l = v.get();
        Object[] c = ITEM_CACHE.get(v);
        if (c != null && c[0] == l) return (java.util.Set<net.minecraft.world.item.Item>) c[1];
        java.util.Set<net.minecraft.world.item.Item> out = new java.util.HashSet<>();
        for (String s : l) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(s.trim());
            if (id != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).ifPresent(out::add);
        }
        ITEM_CACHE.put(v, new Object[] {l, out});
        return out;
    }
}
