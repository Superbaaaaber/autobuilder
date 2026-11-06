package com.autobuilder;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

/** 客户端配置，文件在 config/autobuilder-client.toml，也可以在模组列表里点“配置”修改。 */
public final class ABConfig {
    public enum PlaceMode implements TranslatableEnum {
        AIRPLACE("直接放（不需要依附面）"),
        ADJACENT("点相邻方块的面（更像真人）");

        private final String label;

        PlaceMode(String label) {
            this.label = label;
        }

        @Override
        public Component getTranslatedName() {
            return Component.literal(label);
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
        REACH = b.comment("触及距离：眼睛到方块碰撞箱最近点的最大距离。原版服务端上限是 4.5+1=5.5，留点余量")
                .defineInRange("reach", 4.8, 1.0, 5.4);
        ACTION_DELAY = b.comment("两次操作（放/挖/交互）之间间隔的 tick 数，0 表示每 tick 都操作")
                .defineInRange("actionDelay", 1, 0, 20);
        ACTIONS_PER_TICK = b.comment("每次操作时最多连续放几个方块")
                .defineInRange("actionsPerTick", 1, 1, 8);
        PLACE_MODE = b.comment("AIRPLACE：直接对目标格发包放置，不需要依附面；ADJACENT：点击相邻方块的面（更像真人，有反作弊的服用这个）")
                .defineEnum("placeMode", PlaceMode.AIRPLACE);
        SILENT_ROTATION = b.comment("放有朝向的方块时只发转头包，本地镜头不动")
                .define("silentRotation", true);
        BREAK_WRONG = b.comment("挖掉蓝图位置上放错的方块")
                .define("breakWrongBlocks", true);
        CLEAR_AIR = b.comment("挖掉蓝图里本该是空气的位置上的方块（比如房子里的泥土）")
                .define("clearAirBlocks", true);
        b.pop();

        b.push("movement");
        ALLOW_SCAFFOLD = b.comment("允许垫方块搭柱子/搭桥")
                .define("allowScaffold", true);
        REMOVE_SCAFFOLD = b.comment("完工后拆掉自己搭的脚手架")
                .define("removeScaffold", true);
        SCAFFOLD_BLOCKS = b.comment("可以拿来当脚手架的方块")
                .defineListAllowEmpty("scaffoldBlocks",
                        List.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
                                "minecraft:netherrack", "minecraft:andesite", "minecraft:diorite",
                                "minecraft:granite", "minecraft:tuff", "minecraft:stone"),
                        () -> "minecraft:dirt", o -> o instanceof String);
        ALLOW_DIG = b.comment("寻路时允许挖开挡路的地形")
                .define("allowDig", true);
        MAX_FALL = b.comment("寻路允许的最大下落高度（格）")
                .defineInRange("maxFall", 3, 1, 3);
        b.pop();

        b.push("safety");
        AUTO_EAT = b.comment("饿了自动吃背包里的食物")
                .define("autoEat", true);
        EAT_BELOW = b.comment("饱食度低于多少时吃东西（满值 20）")
                .defineInRange("eatBelow", 14, 1, 19);
        STOP_BELOW_HEALTH = b.comment("血量低于多少时停止建造（满血 20）")
                .defineInRange("stopBelowHealth", 8, 0, 19);
        PAUSE_ON_INPUT = b.comment("按下移动键时自动暂停（方便手动接管）")
                .define("pauseOnInput", true);
        WORK_SLOTS = b.comment("从背包换材料到快捷栏时使用的快捷栏格子（0~8）")
                .defineListAllowEmpty("workSlots", List.of(6, 7, 8), () -> 8,
                        o -> o instanceof Integer i && i >= 0 && i <= 8);
        b.pop();

        b.push("render");
        RENDER_GHOSTS = b.comment("渲染半透明的待放方块")
                .define("renderGhosts", true);
        RENDER_RANGE = b.comment("预览渲染距离（格）")
                .defineInRange("renderRange", 48, 8, 256);
        b.pop();

        SPEC = b.build();
    }

    private ABConfig() {}
}
