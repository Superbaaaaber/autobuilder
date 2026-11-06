package com.autobuilder.blueprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蓝图：一个长方体范围内每个格子应该是什么方块。
 * 坐标是蓝图内部的相对坐标（0..sx-1, 0..sy-1, 0..sz-1）。
 * 某格为 null 表示“不关心”（结构空位），为空气表示“这里应该是空的”。
 */
public final class Blueprint {
    public final String name;
    /** 从哪儿加载的（蓝图列表里的名字），断点续建时用它重新加载；代码临时生成的为 null */
    public String source;
    public final int sx, sy, sz;
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
    private final int[] data;

    public Blueprint(String name, int sx, int sy, int sz) {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("蓝图尺寸无效: " + sx + "x" + sy + "x" + sz);
        long vol = (long) sx * sy * sz;
        if (vol > 16_000_000L) throw new IllegalArgumentException("蓝图太大: " + sx + "x" + sy + "x" + sz);
        this.name = name;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.data = new int[(int) vol];
        Arrays.fill(data, -1);
    }

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    public void set(int x, int y, int z, BlockState state) {
        if (!inside(x, y, z)) return;
        if (state == null) {
            data[index(x, y, z)] = -1;
            return;
        }
        Integer id = paletteIndex.get(state);
        if (id == null) {
            id = palette.size();
            palette.add(state);
            paletteIndex.put(state, id);
        }
        data[index(x, y, z)] = id;
    }

    public BlockState get(int x, int y, int z) {
        if (!inside(x, y, z)) return null;
        int id = data[index(x, y, z)];
        return id < 0 ? null : palette.get(id);
    }

    /** 按索引取调色板编号，-1 表示不关心 */
    public int rawAt(int index) {
        return data[index];
    }

    public List<BlockState> palette() {
        return palette;
    }

    public int volume() {
        return data.length;
    }

    /** 非空气方块数 */
    public int solidCount() {
        int n = 0;
        for (int id : data) {
            if (id >= 0 && !palette.get(id).isAir()) n++;
        }
        return n;
    }

    public String sizeText() {
        return sx + "×" + sy + "×" + sz;
    }
}
