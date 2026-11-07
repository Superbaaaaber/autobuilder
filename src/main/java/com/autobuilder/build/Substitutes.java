package com.autobuilder.build;

import com.autobuilder.ABConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同类材料互相代替：
 * 木头一族（橡木、云杉、白桦……同形状的木板、原木、楼梯、台阶、栅栏、门、告示牌等）；
 * 颜色一族（16 色的床、羊毛、地毯、混凝土、染色玻璃/玻璃板、陶瓦、蜡烛等；普通玻璃、陶瓦、蜡烛也算同一组）。
 * 缺蓝图要的那种时才用代替品，而且一次建造里一直用同一种代替品，免得花色乱跳；已经放好的同类方块也算对。
 */
public final class Substitutes {
    public enum Kind { WOOD, COLOR }

    private record Fam(String key, Kind kind) {}

    /** 长的放前面，免得 dark_oak 被当成 oak、light_blue 被当成 blue */
    private static final String[] WOODS = {"dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped"};
    private static final String[] COLORS = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black"};
    /** 没颜色的版本：和染色的算一组 */
    private static final Map<String, String> PLAIN = Map.of("glass", "*_stained_glass", "glass_pane", "*_stained_glass_pane",
            "terracotta", "*_terracotta", "candle", "*_candle");

    private static Map<String, List<Block>> groups;
    private static final Map<Block, Fam> FAM = new HashMap<>();
    /** 这次建造里每种缺的物品选定的代替品（一直用它） */
    private static final Map<Item, Item> CHOSEN = new HashMap<>();

    private Substitutes() {}

    private static String token(String path, String t) {
        if (path.startsWith(t + "_")) return "*" + path.substring(t.length());
        if (path.startsWith("stripped_" + t + "_")) return "stripped_*" + path.substring(("stripped_" + t).length());
        return null;
    }

    private static Fam compute(Block b) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        if (!id.getNamespace().equals("minecraft")) return null;
        String p = id.getPath();
        String plain = PLAIN.get(p);
        if (plain != null) return new Fam(plain, Kind.COLOR);
        for (String w : WOODS) {
            String k = token(p, w);
            if (k != null) return new Fam(k, Kind.WOOD);
        }
        for (String c : COLORS) {
            String k = token(p, c);
            if (k != null) return new Fam(k, Kind.COLOR);
        }
        return null;
    }

    private static Fam fam(Block b) {
        ensure();
        return FAM.get(b);
    }

    private static void ensure() {
        if (groups != null) return;
        groups = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            Fam f = compute(b);
            if (f == null) continue;
            FAM.put(b, f);
            groups.computeIfAbsent(f.key(), k -> new ArrayList<>()).add(b);
        }
    }

    private static boolean enabled(Kind k) {
        return k == Kind.WOOD ? ABConfig.SUB_WOOD.get() : ABConfig.SUB_COLOR.get();
    }

    /** 两种方块是同一族、可以互相代替（而且这一类开着） */
    public static boolean same(Block a, Block b) {
        if (a == b) return true;
        Fam fa = fam(a), fb = fam(b);
        return fa != null && fb != null && fa.key().equals(fb.key()) && enabled(fa.kind());
    }

    /** 同一族的其他方块（这一类没开就是空的） */
    public static List<Block> others(Block b) {
        Fam f = fam(b);
        if (f == null || !enabled(f.kind())) return List.of();
        List<Block> out = new ArrayList<>(groups.get(f.key()));
        out.remove(b);
        return out;
    }

    /** 物品版本：代替品里有没有 it */
    public static boolean sameItem(Item a, Item b) {
        if (a == b) return true;
        Block ba = Block.byItem(a), bb = Block.byItem(b);
        return ba != net.minecraft.world.level.block.Blocks.AIR && bb != net.minecraft.world.level.block.Blocks.AIR && same(ba, bb);
    }

    /** 换成另一种方块，朝向、上下半等属性照抄 */
    public static BlockState swap(BlockState s, Block to) {
        return to.withPropertiesOf(s);
    }

    public static void reset() {
        CHOSEN.clear();
    }

    /**
     * 放这个目标该拿什么物品：有蓝图要的就用它；没有就用背包里的同类代替品（选定一种后一直用）；都没有返回原物品（算缺料）。
     */
    public static Item pick(LocalPlayer p, BlockState target) {
        Item exact = StateMatcher.itemFor(target);
        if (exact == null || InventoryHelper.count(p, exact) > 0) return exact;
        if (StateMatcher.isDirtFamily(target)) return exact;
        Item chosen = CHOSEN.get(exact);
        if (chosen != null && InventoryHelper.count(p, chosen) > 0) return chosen;
        Item best = null;
        int bestN = 0;
        for (Block o : others(target.getBlock())) {
            Item it = o.asItem();
            if (it == Items.AIR) continue;
            int n = InventoryHelper.count(p, it);
            if (n > bestN) {
                bestN = n;
                best = it;
            }
        }
        if (best == null) return exact;
        CHOSEN.put(exact, best);
        return best;
    }

    /** 用 item 放 target 时实际要放的状态（代替品就换方块） */
    public static BlockState placeTarget(BlockState target, Item item) {
        if (item == null || item == StateMatcher.itemFor(target)) return target;
        Block b = Block.byItem(item);
        return b == net.minecraft.world.level.block.Blocks.AIR ? target : swap(target, b);
    }
}
