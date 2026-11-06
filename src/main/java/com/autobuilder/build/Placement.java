package com.autobuilder.build;

import com.autobuilder.Lang;
import com.autobuilder.blueprint.Blueprint;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 蓝图在世界中的摆放：原点（包围盒最小角）+ 旋转 + 镜像。
 * 变换顺序与原版结构一致：先镜像再旋转，旋转后包围盒仍以 origin 为最小角。
 */
public final class Placement {
    public final Blueprint bp;
    private BlockPos origin;
    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;
    private BlockState[] palette; // 变换后的调色板缓存
    private int version; // 每次变换 +1，扫描器据此重置

    public Placement(Blueprint bp, BlockPos origin) {
        this.bp = bp;
        this.origin = origin.immutable();
        rebuildPalette();
    }

    public BlockPos origin() { return origin; }
    public Rotation rotation() { return rotation; }
    public Mirror mirror() { return mirror; }
    public int version() { return version; }

    private boolean swapped() {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    /** 世界里 x 方向的长度 */
    public int sizeX() { return swapped() ? bp.sz : bp.sx; }
    public int sizeY() { return bp.sy; }
    public int sizeZ() { return swapped() ? bp.sx : bp.sz; }

    public BlockPos max() {
        return origin.offset(sizeX() - 1, sizeY() - 1, sizeZ() - 1);
    }

    public AABB box() {
        return new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sizeX(), origin.getY() + sizeY(), origin.getZ() + sizeZ());
    }

    public void setOrigin(BlockPos p) {
        origin = p.immutable();
        version++;
    }

    public void move(int dx, int dy, int dz) {
        setOrigin(origin.offset(dx, dy, dz));
    }

    /** 顺时针转 90°，保持包围盒中心大致不动 */
    public void rotateCW() {
        int w0 = sizeX(), d0 = sizeZ();
        rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        int w1 = sizeX(), d1 = sizeZ();
        origin = origin.offset((w0 - w1) / 2, 0, (d0 - d1) / 2);
        rebuildPalette();
    }

    public void setRotation(Rotation r) {
        rotation = r;
        rebuildPalette();
    }

    public void setMirror(Mirror m) {
        mirror = m;
        rebuildPalette();
    }

    /** 在 NONE → 左右(LEFT_RIGHT, 翻转 z) → 前后(FRONT_BACK, 翻转 x) 之间切换 */
    public void cycleMirror() {
        mirror = switch (mirror) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.NONE;
        };
        rebuildPalette();
    }

    private void rebuildPalette() {
        List<BlockState> src = bp.palette();
        palette = new BlockState[src.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = src.get(i).mirror(mirror).rotate(rotation);
        }
        version++;
    }

    /** 蓝图内部坐标 → 世界坐标 */
    public BlockPos toWorld(int x, int y, int z) {
        int mx = x, mz = z;
        if (mirror == Mirror.LEFT_RIGHT) mz = bp.sz - 1 - z;
        else if (mirror == Mirror.FRONT_BACK) mx = bp.sx - 1 - x;
        int rx, rz;
        switch (rotation) {
            case CLOCKWISE_90 -> { rx = bp.sz - 1 - mz; rz = mx; }
            case CLOCKWISE_180 -> { rx = bp.sx - 1 - mx; rz = bp.sz - 1 - mz; }
            case COUNTERCLOCKWISE_90 -> { rx = mz; rz = bp.sx - 1 - mx; }
            default -> { rx = mx; rz = mz; }
        }
        return new BlockPos(origin.getX() + rx, origin.getY() + y, origin.getZ() + rz);
    }

    /** 世界坐标处应有的方块（已旋转/镜像）；范围外或“不关心”返回 null */
    public BlockState targetAt(int wx, int wy, int wz) {
        int rx = wx - origin.getX(), y = wy - origin.getY(), rz = wz - origin.getZ();
        if (rx < 0 || y < 0 || rz < 0 || rx >= sizeX() || y >= sizeY() || rz >= sizeZ()) return null;
        int mx, mz;
        switch (rotation) {
            case CLOCKWISE_90 -> { mz = bp.sz - 1 - rx; mx = rz; }
            case CLOCKWISE_180 -> { mx = bp.sx - 1 - rx; mz = bp.sz - 1 - rz; }
            case COUNTERCLOCKWISE_90 -> { mz = rx; mx = bp.sx - 1 - rz; }
            default -> { mx = rx; mz = rz; }
        }
        int x = mirror == Mirror.FRONT_BACK ? bp.sx - 1 - mx : mx;
        int z = mirror == Mirror.LEFT_RIGHT ? bp.sz - 1 - mz : mz;
        int id = bp.rawAt(bp.index(x, y, z));
        return id < 0 ? null : palette[id];
    }

    public BlockState targetAt(BlockPos p) {
        return targetAt(p.getX(), p.getY(), p.getZ());
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= origin.getX() && p.getY() >= origin.getY() && p.getZ() >= origin.getZ()
                && p.getX() < origin.getX() + sizeX() && p.getY() < origin.getY() + sizeY() && p.getZ() < origin.getZ() + sizeZ();
    }

    /** 让建筑正面（蓝图 +z 面）朝向玩家，放在玩家面前 2 格处 */
    public static Placement inFrontOf(Blueprint bp, BlockPos feet, Direction facing) {
        Placement pl = new Placement(bp, feet);
        Rotation r = switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        pl.setRotation(r);
        int w = pl.sizeX(), d = pl.sizeZ();
        int px = feet.getX(), py = feet.getY(), pz = feet.getZ();
        BlockPos o = switch (facing) {
            case SOUTH -> new BlockPos(px - w / 2, py, pz + 2);
            case EAST -> new BlockPos(px + 2, py, pz - d / 2);
            case WEST -> new BlockPos(px - 1 - w, py, pz - d / 2);
            default -> new BlockPos(px - w / 2, py, pz - 1 - d);
        };
        pl.setOrigin(o);
        return pl;
    }

    public String describe() {
        String rot = switch (rotation) {
            case NONE -> "0°";
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
        };
        String mir = switch (mirror) {
            case NONE -> Lang.t("mirror.none");
            case LEFT_RIGHT -> Lang.t("mirror.lr");
            case FRONT_BACK -> Lang.t("mirror.fb");
        };
        return Lang.t("placement.describe", bp.name, origin.toShortString(), rot, mir, sizeX(), sizeY(), sizeZ());
    }
}
