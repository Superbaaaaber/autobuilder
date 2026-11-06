package com.autobuilder.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class Geometry {
    private Geometry() {}

    /** 点到方块（单位立方体）最近点的距离，和服务端 canInteractWithBlock 的算法一致 */
    public static double distToBlock(Vec3 p, BlockPos b) {
        return Math.sqrt(distSqToBlock(p.x, p.y, p.z, b));
    }

    public static double distSqToBlock(double x, double y, double z, BlockPos b) {
        double dx = Math.max(Math.max(b.getX() - x, x - (b.getX() + 1)), 0);
        double dy = Math.max(Math.max(b.getY() - y, y - (b.getY() + 1)), 0);
        double dz = Math.max(Math.max(b.getZ() - z, z - (b.getZ() + 1)), 0);
        return dx * dx + dy * dy + dz * dz;
    }

    public static double distSq(BlockPos a, double x, double y, double z) {
        double dx = a.getX() + 0.5 - x, dy = a.getY() + 0.5 - y, dz = a.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
