package com.autobuilder.blueprint;

import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 读取三种蓝图格式：原版结构 .nbt、Litematica .litematic、Sponge/WorldEdit .schem */
public final class BlueprintLoader {
    private BlueprintLoader() {}

    public static boolean isSupported(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".nbt") || n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic");
    }

    public static Blueprint load(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        if (lower.endsWith(".litematic")) return fromLitematic(name, root);
        if (lower.endsWith(".nbt")) return fromStructure(name, root);
        // .schem / .schematic（新版 WorldEdit 也用 .schem）
        if (root.contains("Schematic", Tag.TAG_COMPOUND) || root.contains("Palette") || root.contains("BlockData")) {
            return fromSponge(name, root);
        }
        throw new IOException("不支持的旧版 MCEdit .schematic 格式，请用 WorldEdit 重新导出成 .schem");
    }

    private static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    private static BlockState readState(CompoundTag stateTag, int dataVersion) {
        CompoundTag tag = stateTag;
        int cur = currentDataVersion();
        if (dataVersion > 0 && dataVersion < cur) {
            try {
                tag = (CompoundTag) DataFixers.getDataFixer()
                        .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, stateTag), dataVersion, cur)
                        .getValue();
            } catch (Exception ignored) {
                tag = stateTag;
            }
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
    }

    // ---------- 原版结构方块 .nbt ----------
    public static Blueprint fromStructure(String name, CompoundTag tag) {
        int dv = NbtUtils.getDataVersion(tag, 500);
        if (dv < currentDataVersion()) {
            tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, dv);
        }
        ListTag size = tag.getList("size", Tag.TAG_INT);
        Blueprint bp = new Blueprint(name, size.getInt(0), size.getInt(1), size.getInt(2));
        ListTag paletteTag;
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            paletteTag = tag.getList("palettes", Tag.TAG_LIST).getList(0);
        } else {
            paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        }
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i));
        }
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int st = b.getInt("state");
            if (st < 0 || st >= palette.length) continue;
            BlockState state = palette[st];
            if (state.is(Blocks.STRUCTURE_VOID)) continue;
            bp.set(pos.getInt(0), pos.getInt(1), pos.getInt(2), state);
        }
        return bp;
    }

    // ---------- Litematica ----------
    public static Blueprint fromLitematic(String name, CompoundTag root) {
        int dv = root.getInt("MinecraftDataVersion");
        CompoundTag regions = root.getCompound("Regions");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regions.getAllKeys()) {
            CompoundTag r = regions.getCompound(key);
            int[] p = xyz(r.getCompound("Position"));
            int[] s = xyz(r.getCompound("Size"));
            for (int a = 0; a < 3; a++) {
                int lo = p[a] + (s[a] < 0 ? s[a] + 1 : 0);
                int hi = lo + Math.abs(s[a]) - 1;
                if (a == 0) { minX = Math.min(minX, lo); maxX = Math.max(maxX, hi); }
                if (a == 1) { minY = Math.min(minY, lo); maxY = Math.max(maxY, hi); }
                if (a == 2) { minZ = Math.min(minZ, lo); maxZ = Math.max(maxZ, hi); }
            }
        }
        if (minX == Integer.MAX_VALUE) throw new IllegalArgumentException("litematic 里没有区域");
        Blueprint bp = new Blueprint(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        for (String key : regions.getAllKeys()) {
            CompoundTag r = regions.getCompound(key);
            int[] p = xyz(r.getCompound("Position"));
            int[] s = xyz(r.getCompound("Size"));
            int ox = p[0] + (s[0] < 0 ? s[0] + 1 : 0) - minX;
            int oy = p[1] + (s[1] < 0 ? s[1] + 1 : 0) - minY;
            int oz = p[2] + (s[2] < 0 ? s[2] + 1 : 0) - minZ;
            int ax = Math.abs(s[0]), ay = Math.abs(s[1]), az = Math.abs(s[2]);
            ListTag palTag = r.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            BlockState[] pal = new BlockState[palTag.size()];
            for (int i = 0; i < pal.length; i++) pal[i] = readState(palTag.getCompound(i), dv);
            long[] arr = r.getLongArray("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, pal.length - 1)));
            long mask = (1L << bits) - 1L;
            long volume = (long) ax * ay * az;
            for (long i = 0; i < volume; i++) {
                long startOffset = i * bits;
                int startIdx = (int) (startOffset >> 6);
                int endIdx = (int) (((i + 1) * bits - 1) >> 6);
                int bitOff = (int) (startOffset & 63);
                if (endIdx >= arr.length) break;
                long v;
                if (startIdx == endIdx) {
                    v = (arr[startIdx] >>> bitOff) & mask;
                } else {
                    v = ((arr[startIdx] >>> bitOff) | (arr[endIdx] << (64 - bitOff))) & mask;
                }
                if (v >= pal.length) continue;
                int x = (int) (i % ax);
                int z = (int) ((i / ax) % az);
                int y = (int) (i / ((long) ax * az));
                BlockState st = pal[(int) v];
                if (st.is(Blocks.STRUCTURE_VOID)) continue;
                bp.set(ox + x, oy + y, oz + z, st);
            }
        }
        return bp;
    }

    private static int[] xyz(CompoundTag t) {
        return new int[] {t.getInt("x"), t.getInt("y"), t.getInt("z")};
    }

    // ---------- Sponge Schematic v2 / v3（WorldEdit .schem） ----------
    public static Blueprint fromSponge(String name, CompoundTag root) {
        CompoundTag s = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int version = s.getInt("Version");
        int w = s.getShort("Width") & 0xFFFF;
        int h = s.getShort("Height") & 0xFFFF;
        int l = s.getShort("Length") & 0xFFFF;
        int dv = s.getInt("DataVersion");
        CompoundTag paletteTag;
        byte[] data;
        if (version >= 3 || s.contains("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag blocks = s.getCompound("Blocks");
            paletteTag = blocks.getCompound("Palette");
            data = blocks.getByteArray("Data");
        } else {
            paletteTag = s.getCompound("Palette");
            data = s.getByteArray("BlockData");
        }
        Map<Integer, BlockState> pal = new HashMap<>();
        for (String key : paletteTag.getAllKeys()) {
            pal.put(paletteTag.getInt(key), parseStateString(key, dv));
        }
        Blueprint bp = new Blueprint(name, w, h, l);
        int index = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            while (true) {
                byte b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0 || i >= data.length) break;
            }
            BlockState st = pal.get(value);
            if (st != null && !st.is(Blocks.STRUCTURE_VOID)) {
                int x = index % w;
                int z = (index / w) % l;
                int y = index / (w * l);
                bp.set(x, y, z, st);
            }
            index++;
        }
        return bp;
    }

    /** 把 "minecraft:oak_stairs[facing=east,half=bottom]" 转成方块状态 */
    static BlockState parseStateString(String str, int dataVersion) {
        String blockName = str;
        CompoundTag props = new CompoundTag();
        int br = str.indexOf('[');
        if (br >= 0 && str.endsWith("]")) {
            blockName = str.substring(0, br);
            String inner = str.substring(br + 1, str.length() - 1);
            for (String kv : inner.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockName);
        if (!props.isEmpty()) tag.put("Properties", props);
        return readState(tag, dataVersion);
    }
}
