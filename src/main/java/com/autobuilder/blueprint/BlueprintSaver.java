package com.autobuilder.blueprint;

import com.autobuilder.Lang;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** 把世界里一块区域存成原版结构 .nbt（和结构方块导出的格式一样） */
public final class BlueprintSaver {
    /**
     * droppedWater：没存的“从选区外面连进来的水”（湖、河、海，连带水面上的睡莲/蛙卵）；
     * dried：贴着这些水、被去掉含水的方块数；keptWater：保留下来、建造时要用桶放的格子数。
     */
    public record Result(Path path, int droppedWater, int dried, int keptWater) {}

    private BlueprintSaver() {}

    public static Path save(Level level, BlockPos a, BlockPos b, String name) throws IOException {
        return save(level, a, b, name, true, false).path();
    }

    /**
     * includeAir=false 时空格不写进蓝图（建造时不会去清理那些位置）。
     * dropOuterWater=true 时，从选区外面连进来的水不写进蓝图（建造时不动那些位置），
     * 选区里面封闭的水（比如刷怪塔里的水流）照常保存。
     */
    public static Result save(Level level, BlockPos a, BlockPos b, String name, boolean includeAir, boolean dropOuterWater) throws IOException {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
        if ((long) sx * sy * sz > 4_000_000L) throw new IOException(Lang.t("err.region_too_big"));
        BlockState[] states = new BlockState[sx * sy * sz];
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    BlockPos p = min.offset(x, y, z);
                    if (!level.isLoaded(p)) throw new IOException(Lang.t("err.unloaded", p.toShortString()));
                    states[idx(x, y, z, sx, sz)] = level.getBlockState(p);
                }
        boolean[] outer = dropOuterWater ? outerWater(level, min, sx, sy, sz, states) : new boolean[states.length];
        int dropped = 0, dried = 0, kept = 0;
        Map<BlockState, Integer> palette = new HashMap<>();
        ListTag paletteTag = new ListTag();
        ListTag blocks = new ListTag();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = idx(x, y, z, sx, sz);
                    BlockState st = states[i];
                    if (st.is(Blocks.STRUCTURE_VOID)) continue;
                    if (outer[i]) {
                        dropped++;
                        continue;
                    }
                    if (dropOuterWater) {
                        // 漂在外面水面上的东西：水不存了，它们也放不上去
                        if ((st.is(Blocks.LILY_PAD) || st.is(Blocks.FROGSPAWN)) && y > 0 && outer[idx(x, y - 1, z, sx, sz)]) {
                            dropped++;
                            continue;
                        }
                        if (st.hasProperty(BlockStateProperties.WATERLOGGED) && st.getValue(BlockStateProperties.WATERLOGGED)
                                && touchesOuter(level, min, x, y, z, sx, sy, sz, outer)) {
                            st = st.setValue(BlockStateProperties.WATERLOGGED, false);
                            dried++;
                        }
                    }
                    if (st.is(Blocks.CAVE_AIR) || st.is(Blocks.VOID_AIR)) st = Blocks.AIR.defaultBlockState();
                    if (!includeAir && st.isAir()) continue;
                    if (needsBucket(st)) kept++;
                    Integer id = palette.get(st);
                    if (id == null) {
                        id = palette.size();
                        palette.put(st, id);
                        paletteTag.add(NbtUtils.writeBlockState(st));
                    }
                    CompoundTag bt = new CompoundTag();
                    ListTag pos = new ListTag();
                    pos.add(IntTag.valueOf(x));
                    pos.add(IntTag.valueOf(y));
                    pos.add(IntTag.valueOf(z));
                    bt.put("pos", pos);
                    bt.putInt("state", id);
                    blocks.add(bt);
                }
            }
        }
        CompoundTag root = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(sx));
        size.add(IntTag.valueOf(sy));
        size.add(IntTag.valueOf(sz));
        root.put("size", size);
        root.put("palette", paletteTag);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtUtils.addCurrentDataVersion(root);
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String file = safe.toLowerCase().endsWith(".nbt") ? safe : safe + ".nbt";
        Path out = BlueprintManager.dir().resolve(file);
        NbtIo.writeCompressed(root, out);
        return new Result(out, dropped, dried, kept);
    }

    private static int idx(int x, int y, int z, int sx, int sz) {
        return (y * sz + z) * sx + x;
    }

    private static boolean inside(int x, int y, int z, int sx, int sy, int sz) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    /** 湖里的水：水（含流动的）和长在水里的海草、海带 */
    private static boolean openWater(BlockState s) {
        return s.is(Blocks.WATER) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT);
    }

    /** 建造时要用桶放的格子：液体源头、含水方块 */
    private static boolean needsBucket(BlockState s) {
        if (s.getBlock() instanceof LiquidBlock) return s.getFluidState().isSource();
        return s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * 找出从选区外面连进来的水：贴着选区边、而且选区外紧挨着的也是水的格子作起点，沿着水往里扩。
     * 刷怪塔里被墙围住的水流碰不到外面的水，不会被算进来。
     */
    private static boolean[] outerWater(Level level, BlockPos min, int sx, int sy, int sz, BlockState[] states) {
        boolean[] outer = new boolean[states.length];
        int[] queue = new int[states.length];
        int head = 0, tail = 0;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    if (x != 0 && x != sx - 1 && y != 0 && y != sy - 1 && z != 0 && z != sz - 1) continue;
                    int i = idx(x, y, z, sx, sz);
                    if (!openWater(states[i]) || !wetOutside(level, min, x, y, z, sx, sy, sz)) continue;
                    outer[i] = true;
                    queue[tail++] = i;
                }
        while (head < tail) {
            int i = queue[head++];
            int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
            for (Direction d : Direction.values()) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                if (!inside(nx, ny, nz, sx, sy, sz)) continue;
                int j = idx(nx, ny, nz, sx, sz);
                if (outer[j] || !openWater(states[j])) continue;
                outer[j] = true;
                queue[tail++] = j;
            }
        }
        return outer;
    }

    /** 这一格贴着选区边，而且选区外紧挨着的那格有水 */
    private static boolean wetOutside(Level level, BlockPos min, int x, int y, int z, int sx, int sy, int sz) {
        for (Direction d : Direction.values()) {
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            if (inside(nx, ny, nz, sx, sy, sz)) continue;
            BlockPos q = min.offset(nx, ny, nz);
            if (level.isLoaded(q) && level.getBlockState(q).getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** 含水方块挨着外面连进来的水（选区里的或选区外的） */
    private static boolean touchesOuter(Level level, BlockPos min, int x, int y, int z, int sx, int sy, int sz, boolean[] outer) {
        for (Direction d : Direction.values()) {
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            if (inside(nx, ny, nz, sx, sy, sz) && outer[idx(nx, ny, nz, sx, sz)]) return true;
        }
        return wetOutside(level, min, x, y, z, sx, sy, sz);
    }
}
