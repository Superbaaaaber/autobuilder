package com.autobuilder.build;

import com.autobuilder.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** 保存建筑用的选区：两个对角 */
public final class Selection {
    private static BlockPos pos1, pos2;

    private Selection() {}

    public static BlockPos pos1() { return pos1; }
    public static BlockPos pos2() { return pos2; }

    /** 准星对着的方块；没对着方块就用脚下的方块 */
    public static BlockPos crosshairOrFeet() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) return bh.getBlockPos();
        return mc.player.blockPosition();
    }

    public static void set(int which, BlockPos p) {
        if (which == 1) pos1 = p.immutable();
        else pos2 = p.immutable();
    }

    public static boolean complete() {
        return pos1 != null && pos2 != null;
    }

    public static AABB box() {
        if (!complete()) return null;
        return new AABB(Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()) + 1, Math.max(pos1.getY(), pos2.getY()) + 1, Math.max(pos1.getZ(), pos2.getZ()) + 1);
    }

    public static String describe() {
        if (pos1 == null && pos2 == null) return Lang.t("sel.none");
        String a = pos1 == null ? "?" : pos1.toShortString();
        String b = pos2 == null ? "?" : pos2.toShortString();
        if (!complete()) return Lang.t("sel.partial", a, b);
        AABB box = box();
        return Lang.t("sel.full", a, b, (int) box.getXsize(), (int) box.getYsize(), (int) box.getZsize());
    }

    public static void clear() {
        pos1 = pos2 = null;
    }
}
