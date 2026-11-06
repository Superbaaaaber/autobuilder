package com.autobuilder.build;

import com.autobuilder.ABConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/** 脚手架用的方块：配置里列出的杂物方块，扣掉蓝图本身还要用的数量 */
public final class ScaffoldItems {
    private static List<? extends String> cachedList;
    private static Set<Item> cached = Set.of();

    private ScaffoldItems() {}

    public static Set<Item> items() {
        List<? extends String> l = ABConfig.SCAFFOLD_BLOCKS.get();
        if (l != cachedList) {
            Set<Item> out = new LinkedHashSet<>();
            for (String id : l) {
                ResourceLocation rl = ResourceLocation.tryParse(id.trim());
                if (rl == null) continue;
                Item it = BuiltInRegistries.ITEM.get(rl);
                if (it instanceof BlockItem) out.add(it);
            }
            cached = out;
            cachedList = l;
        }
        return cached;
    }

    public static boolean isScaffold(BlockState s) {
        if (s.isAir()) return false;
        // 泥土垫久了会长草变成草方块，也算自己的脚手架
        if (s.is(net.minecraft.tags.BlockTags.DIRT) && items().contains(net.minecraft.world.item.Items.DIRT)) return true;
        return items().contains(s.getBlock().asItem());
    }

    private static int surplus(LocalPlayer p, Item it, Scanner.Snapshot snap) {
        int have = InventoryHelper.count(p, it);
        int need = snap == null ? 0 : snap.need.getOrDefault(it, 0);
        return have - need;
    }

    /** 还能拿来垫脚的方块总数 */
    public static int available(LocalPlayer p, Placement pl, Scanner.Snapshot snap) {
        int n = 0;
        for (Item it : items()) n += Math.max(0, surplus(p, it, snap));
        return n;
    }

    /** 挑一种富余最多的脚手架方块 */
    public static Item pick(LocalPlayer p, Placement pl, Scanner.Snapshot snap) {
        Item best = null;
        int bestN = 0;
        for (Item it : items()) {
            int n = surplus(p, it, snap);
            if (n > bestN) {
                bestN = n;
                best = it;
            }
        }
        return best;
    }
}
