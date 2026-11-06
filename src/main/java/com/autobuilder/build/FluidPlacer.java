package com.autobuilder.build;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 用桶放水/岩浆、给方块加水、从水源打水。
 * 桶和放方块不同：服务端会沿玩家视线做射线检测（4.5 格、不能被挡住），
 * 所以这里在客户端用同样的算法找一个看得见的瞄准点，再发带朝向的“使用物品”包。
 */
public final class FluidPlacer {
    public enum Kind { SOURCE, WATERLOG, REFILL }

    public record FluidAction(BlockPos target, Kind kind, Item bucket, float yaw, float pitch) {}

    private static final double[] OFFS = {0.0, -0.25, 0.25};

    private FluidPlacer() {}

    /** 和 Entity.calculateViewVector 完全一样的算法（用 Mth 的查表 sin/cos） */
    static Vec3 viewVector(float pitch, float yaw) {
        float f = pitch * (float) (Math.PI / 180.0);
        float f1 = -yaw * (float) (Math.PI / 180.0);
        float f2 = Mth.cos(f1), f3 = Mth.sin(f1), f4 = Mth.cos(f), f5 = Mth.sin(f);
        return new Vec3(f3 * f4, -f5, f2 * f4);
    }

    private static float[] aim(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI));
        float pitch = (float) (Mth.atan2(-dy, h) * (180.0 / Math.PI));
        return new float[] {yaw, pitch};
    }

    private static BlockHitResult ray(Minecraft mc, Vec3 eye, float yaw, float pitch, ClipContext.Fluid fluidMode) {
        LocalPlayer p = mc.player;
        Vec3 end = eye.add(viewVector(pitch, yaw).scale(p.blockInteractionRange()));
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluidMode, p));
    }

    private static boolean hitsExpected(Minecraft mc, Vec3 eye, Vec3 point, BlockPos block, Direction face, ClipContext.Fluid mode) {
        float[] r = aim(eye, point);
        BlockHitResult h = ray(mc, eye, r[0], r[1], mode);
        return h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(block) && (face == null || h.getDirection() == face);
    }

    /** 能装水的方块（树叶、台阶、活板门等），水桶点到它会变成含水而不是在旁边放水 */
    private static boolean canContainWater(ClientLevel level, BlockPos pos, BlockState s, LocalPlayer p) {
        return s.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(p, level, pos, s, net.minecraft.world.level.material.Fluids.WATER);
    }

    /** 从 eye 位置出发，找一个能把液体放到 target 的瞄准方案；找不到返回 null */
    public static FluidAction plan(Minecraft mc, Vec3 eye, BlockPos target, Kind kind, Item bucket) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        double reach = Math.min(p.blockInteractionRange(), 4.5) - 0.25;
        List<Object[]> cands = new ArrayList<>(); // {点, 方块, 面, 射线模式}
        if (kind == Kind.WATERLOG) {
            for (Direction face : Direction.values()) addFace(cands, target, face, target, ClipContext.Fluid.NONE);
        } else if (kind == Kind.REFILL) {
            Vec3 c = Vec3.atCenterOf(target);
            for (double ox : OFFS) for (double oz : OFFS) {
                cands.add(new Object[] {new Vec3(c.x + ox, target.getY() + 0.8, c.z + oz), target, null, ClipContext.Fluid.SOURCE_ONLY});
            }
        } else {
            for (Direction d : Direction.values()) {
                BlockPos n = target.relative(d);
                BlockState ns = level.getBlockState(n);
                if (ns.getShape(level, n).isEmpty()) continue; // 射线停不下来
                if (bucket == Items.WATER_BUCKET && canContainWater(level, n, ns, p)) continue;
                addFace(cands, n, d.getOpposite(), n, ClipContext.Fluid.NONE);
            }
        }
        for (Object[] c : cands) {
            Vec3 pt = (Vec3) c[0];
            BlockPos blk = (BlockPos) c[1];
            Direction face = (Direction) c[2];
            ClipContext.Fluid mode = (ClipContext.Fluid) c[3];
            if (eye.distanceTo(pt) > reach) continue;
            if (!hitsExpected(mc, eye, pt, blk, face, mode)) continue;
            // 稍微偏一点也要打中同一个面，避免客户端和服务端坐标差一点点就打偏
            boolean robust = true;
            for (double[] o : new double[][] {{0.04, 0, 0}, {-0.04, 0, 0}, {0, 0.04, 0}, {0, -0.04, 0}, {0, 0, 0.04}, {0, 0, -0.04}}) {
                if (!hitsExpected(mc, eye.add(o[0], o[1], o[2]), pt, blk, face, mode)) {
                    robust = false;
                    break;
                }
            }
            if (!robust) continue;
            float[] r = aim(eye, pt);
            return new FluidAction(target, kind, bucket, r[0], r[1]);
        }
        return null;
    }

    private static void addFace(List<Object[]> out, BlockPos block, Direction face, BlockPos expect, ClipContext.Fluid mode) {
        Vec3 c = Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        for (double u : OFFS) {
            for (double v : OFFS) {
                double x = c.x, y = c.y, z = c.z;
                switch (face.getAxis()) {
                    case X -> { y += u; z += v; }
                    case Y -> { x += u; z += v; }
                    case Z -> { x += u; y += v; }
                }
                // 往面里缩一点点，避免正好打在棱上
                out.add(new Object[] {new Vec3(x - face.getStepX() * 0.01, y - face.getStepY() * 0.01, z - face.getStepZ() * 0.01),
                        expect, face, mode});
            }
        }
    }

    /** 执行：手上必须已拿着对应的桶，而且人要站稳（服务端用它记录的位置做射线） */
    public static Placer.Result execute(Minecraft mc, FluidAction a) {
        LocalPlayer p = mc.player;
        if (!p.getMainHandItem().is(a.bucket)) return Placer.Result.FAILED;
        if (!p.onGround() || p.getDeltaMovement().horizontalDistanceSqr() > 1e-5) return Placer.Result.DEFERRED;
        // 重新从当前眼睛位置核对一遍
        float yaw0 = p.getYRot(), pitch0 = p.getXRot(), yawO = p.yRotO, pitchO = p.xRotO;
        p.setYRot(a.yaw);
        p.setXRot(a.pitch);
        p.yRotO = a.yaw;
        p.xRotO = a.pitch;
        InteractionResult r;
        try {
            // 使用物品包里带着朝向，服务端会先转过去再做射线
            r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        } finally {
            p.setYRot(yaw0);
            p.setXRot(pitch0);
            p.yRotO = yawO;
            p.xRotO = pitchO;
        }
        Placer.noteServerRotation(a.yaw, a.pitch);
        if (r.shouldSwing()) p.swing(InteractionHand.MAIN_HAND);
        return r.consumesAction() ? Placer.Result.PLACED : Placer.Result.FAILED;
    }
}
