package com.autobuilder.blueprint;

import com.autobuilder.Lang;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 内置示例建筑。坐标约定：x 向东、z 向南、y 向上；
 * 建筑的“正面”（门）朝 +z（南），加载时会自动转向让正面对着玩家。
 */
public final class BuiltinPresets {
    /** 内置建筑的名字是 builtin:cabin 这样的英文 id，显示名走翻译 */
    public static final String PREFIX = "builtin:";
    private static final Map<String, Supplier<Blueprint>> PRESETS = new LinkedHashMap<>();
    /** 1.2.1 及以前用中文名，断点存档里可能还是旧名字 */
    private static final Map<String, String> LEGACY = Map.of(
            "[内置]小木屋", "builtin:cabin", "[内置]瞭望塔", "builtin:tower", "[内置]围栏小院", "builtin:yard");

    static {
        PRESETS.put("cabin", BuiltinPresets::cabin);
        PRESETS.put("tower", BuiltinPresets::tower);
        PRESETS.put("yard", BuiltinPresets::yard);
    }

    /** 旧的中文名换成现在的 id，其它原样返回 */
    public static String canonical(String name) {
        return LEGACY.getOrDefault(name, name);
    }

    /** builtin:cabin → [内置]小木屋 / [Built-in] Cabin */
    public static String displayName(String name) {
        return Lang.t("preset." + name.substring(PREFIX.length()));
    }

    private BuiltinPresets() {}

    public static Map<String, Supplier<Blueprint>> all() {
        return PRESETS;
    }

    private static BlockState s(String str) {
        return BlueprintLoader.parseStateString(str, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
    }

    private static void fill(Blueprint b, int x0, int y0, int z0, int x1, int y1, int z1, BlockState st) {
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) b.set(x, y, z, st);
    }

    /** 7×7×7 小木屋：圆石地板、原木四角、木板墙、玻璃窗、木门、楼梯双坡屋顶 */
    static Blueprint cabin() {
        Blueprint b = new Blueprint(Lang.t("preset.cabin"), 7, 7, 7);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState planks = s("minecraft:oak_planks");
        BlockState log = s("minecraft:oak_log[axis=y]");
        fill(b, 0, 0, 0, 6, 0, 6, s("minecraft:cobblestone"));
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z <= 6; z++) {
                for (int x = 0; x <= 6; x++) {
                    boolean edgeX = x == 0 || x == 6, edgeZ = z == 0 || z == 6;
                    if (edgeX && edgeZ) b.set(x, y, z, log);
                    else if (edgeX || edgeZ) b.set(x, y, z, planks);
                    else b.set(x, y, z, air);
                }
            }
        }
        BlockState pane = s("minecraft:glass_pane");
        b.set(3, 2, 0, pane);
        b.set(0, 2, 3, pane);
        b.set(6, 2, 3, pane);
        b.set(3, 1, 6, s("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"));
        b.set(3, 2, 6, s("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"));
        b.set(3, 3, 1, s("minecraft:wall_torch[facing=south]"));
        b.set(1, 1, 1, s("minecraft:crafting_table"));
        b.set(5, 1, 1, s("minecraft:furnace[facing=south]"));
        BlockState stairS = s("minecraft:oak_stairs[facing=south,half=bottom]");
        BlockState stairN = s("minecraft:oak_stairs[facing=north,half=bottom]");
        // 屋顶三层，从两侧往中间收
        for (int layer = 0; layer < 3; layer++) {
            int y = 4 + layer;
            for (int x = 0; x <= 6; x++) {
                b.set(x, y, layer, stairS);
                b.set(x, y, 6 - layer, stairN);
            }
            for (int z = layer + 1; z <= 5 - layer; z++) {
                b.set(0, y, z, planks);
                b.set(6, y, z, planks);
                for (int x = 1; x <= 5; x++) b.set(x, y, z, air);
            }
        }
        for (int x = 0; x <= 6; x++) b.set(x, 6, 3, s("minecraft:oak_slab[type=bottom]"));
        return b;
    }

    /** 5×12×5 石砖瞭望塔：内部梯子，顶层平台带栅栏和灯笼（考验爬高和脚手架） */
    static Blueprint tower() {
        Blueprint b = new Blueprint(Lang.t("preset.tower"), 5, 12, 5);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bricks = s("minecraft:stone_bricks");
        fill(b, 0, 0, 0, 4, 0, 4, bricks);
        for (int y = 1; y <= 8; y++) {
            for (int z = 0; z <= 4; z++) {
                for (int x = 0; x <= 4; x++) {
                    boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                    b.set(x, y, z, edge ? bricks : air);
                }
            }
        }
        b.set(2, 1, 4, air);
        b.set(2, 2, 4, air);
        b.set(2, 5, 4, air);
        b.set(0, 5, 2, air);
        b.set(4, 5, 2, air);
        fill(b, 0, 9, 0, 4, 9, 4, bricks);
        BlockState ladder = s("minecraft:ladder[facing=south]");
        for (int y = 1; y <= 9; y++) b.set(2, y, 1, ladder);
        BlockState fence = s("minecraft:oak_fence");
        for (int z = 0; z <= 4; z++) {
            for (int x = 0; x <= 4; x++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                b.set(x, 10, z, edge ? fence : air);
                b.set(x, 11, z, air);
            }
        }
        BlockState lantern = s("minecraft:lantern[hanging=false]");
        b.set(0, 11, 0, lantern);
        b.set(4, 11, 0, lantern);
        b.set(0, 11, 4, lantern);
        b.set(4, 11, 4, lantern);
        return b;
    }

    /** 9×2×9 围栏小院：一圈橡木栅栏、南侧栅栏门、四角火把 */
    static Blueprint yard() {
        Blueprint b = new Blueprint(Lang.t("preset.yard"), 9, 2, 9);
        BlockState fence = s("minecraft:oak_fence");
        for (int z = 0; z <= 8; z++) {
            for (int x = 0; x <= 8; x++) {
                if (x == 0 || x == 8 || z == 0 || z == 8) b.set(x, 0, z, fence);
            }
        }
        b.set(4, 0, 8, s("minecraft:oak_fence_gate[facing=south,open=false,in_wall=false,powered=false]"));
        BlockState torch = s("minecraft:torch");
        b.set(0, 1, 0, torch);
        b.set(8, 1, 0, torch);
        b.set(0, 1, 8, torch);
        b.set(8, 1, 8, torch);
        return b;
    }
}
