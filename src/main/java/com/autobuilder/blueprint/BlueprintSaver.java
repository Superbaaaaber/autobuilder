package com.autobuilder.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 把世界里一块区域存成原版结构 .nbt（和结构方块导出的格式一样） */
public final class BlueprintSaver {
    private BlueprintSaver() {}

    public static Path save(Level level, BlockPos a, BlockPos b, String name) throws IOException {
        return save(level, a, b, name, true);
    }

    /** includeAir=false 时空格不写进蓝图（建造时不会去清理那些位置） */
    public static Path save(Level level, BlockPos a, BlockPos b, String name, boolean includeAir) throws IOException {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
        if ((long) sx * sy * sz > 4_000_000L) throw new IOException("区域太大（超过 400 万格）");
        Map<BlockState, Integer> palette = new HashMap<>();
        ListTag paletteTag = new ListTag();
        ListTag blocks = new ListTag();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockPos p = min.offset(x, y, z);
                    if (!level.isLoaded(p)) throw new IOException("区域里有未加载的区块: " + p.toShortString());
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.STRUCTURE_VOID)) continue;
                    if (st.is(Blocks.CAVE_AIR) || st.is(Blocks.VOID_AIR)) st = Blocks.AIR.defaultBlockState();
                    if (!includeAir && st.isAir()) continue;
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
        return out;
    }
}
