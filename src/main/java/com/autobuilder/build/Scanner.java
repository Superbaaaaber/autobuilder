package com.autobuilder.build;

import com.autobuilder.ABConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 分批扫描整个蓝图范围，统计每格状态。扫完一遍生成一个快照，
 * 供 HUD、预览渲染和“下一步去哪干活”使用。
 */
public final class Scanner {
    public static final class Snapshot {
        public final List<BlockPos> place = new ArrayList<>();
        public final List<BlockPos> breaks = new ArrayList<>();
        public final List<BlockPos> interact = new ArrayList<>();
        public final List<BlockPos> wrong = new ArrayList<>();
        /** 要倒液体的位置（液体源 + 含水方块） */
        public final List<BlockPos> fluids = new ArrayList<>();
        public final Map<Item, Integer> need = new HashMap<>();
        public int total; // 需要关心的格子数
        public int ok;
        public int unsupported;
        public int unloaded;

        public int percent() {
            return total == 0 ? 100 : (int) (ok * 100L / total);
        }
    }

    private Snapshot building = new Snapshot();
    private Snapshot current;
    private int cursor;
    private int version = -1;
    private Placement placement;

    public Snapshot snapshot() {
        return current;
    }

    public void reset() {
        building = new Snapshot();
        current = null;
        cursor = 0;
        version = -1;
        placement = null;
    }

    /** 立刻作废当前进度，从头扫（比如刚开始建造时） */
    public void restart() {
        building = new Snapshot();
        cursor = 0;
    }

    public void tick(Placement pl, ClientLevel level, int budget, java.util.Set<BlockPos> skipBreak) {
        if (pl != placement || pl.version() != version) {
            placement = pl;
            version = pl.version();
            building = new Snapshot();
            current = null;
            cursor = 0;
        }
        boolean breakWrong = ABConfig.BREAK_WRONG.get();
        boolean clearAir = ABConfig.CLEAR_AIR.get();
        int sx = pl.sizeX(), sy = pl.sizeY(), sz = pl.sizeZ();
        int volume = sx * sy * sz;
        BlockPos o = pl.origin();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int end = Math.min(volume, cursor + budget);
        Snapshot s = building;
        for (int i = cursor; i < end; i++) {
            int x = i % sx;
            int z = (i / sx) % sz;
            int y = i / (sx * sz);
            int wx = o.getX() + x, wy = o.getY() + y, wz = o.getZ() + z;
            BlockState target = pl.targetAt(wx, wy, wz);
            if (target == null) continue;
            m.set(wx, wy, wz);
            if (!level.isLoaded(m)) {
                s.unloaded++;
                if (!target.isAir()) s.total++;
                continue;
            }
            BlockState world = level.getBlockState(m);
            StateMatcher.Status st = StateMatcher.classify(target, world, breakWrong, clearAir);
            if (st == StateMatcher.Status.IGNORE) continue;
            if (!target.isAir() || st != StateMatcher.Status.OK) s.total++;
            switch (st) {
                case OK, WAIT -> {
                    if (!target.isAir()) s.ok += st == StateMatcher.Status.OK ? 1 : 0;
                }
                case PLACE -> {
                    BlockPos p = m.immutable();
                    s.place.add(p);
                    Item it = StateMatcher.itemFor(target);
                    if (it != null) s.need.merge(it, StateMatcher.itemsNeeded(target, world), Integer::sum);
                }
                case BREAK -> {
                    // 自己搭的脚手架不算“待挖”，完工后统一拆
                    if (!skipBreak.contains(m)) s.breaks.add(m.immutable());
                }
                case INTERACT -> s.interact.add(m.immutable());
                case FLUID -> {
                    s.fluids.add(m.immutable());
                    s.need.merge(StateMatcher.bucketFor(target), 1, Integer::sum);
                }
                case WATERLOG -> {
                    s.fluids.add(m.immutable());
                    s.need.merge(net.minecraft.world.item.Items.WATER_BUCKET, 1, Integer::sum);
                }
                case WRONG -> s.wrong.add(m.immutable());
                case UNSUPPORTED -> s.unsupported++;
                default -> {}
            }
        }
        cursor = end;
        if (cursor >= volume) {
            current = s;
            building = new Snapshot();
            cursor = 0;
        }
    }
}
