package com.autobuilder.build;

import com.autobuilder.ABConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 放置规划与执行。
 * 思路：方块放下后是什么状态，由“点了哪个面、点在面上哪里、玩家朝向”决定。
 * 这里在客户端用 BlockItem 自己的放置逻辑把这些组合逐个模拟一遍，
 * 找到一个能放出目标状态的组合，再按这个组合发包。
 */
public final class Placer {
    /** rotFree：换任何朝向放出来都一样（木板、石头等），执行时不用转头 */
    public record PlaceAction(BlockPos pos, BlockHitResult hit, float yaw, float pitch, boolean sneak, Item item, boolean rotFree) {}

    private record Rot(float yaw, float pitch) {}

    private record Hit(BlockPos clicked, Direction face, Vec3 loc) {}

    private static final float[] YAWS = {0f, 90f, 180f, -90f};
    private static final float[] PITCHES = {0f, 85f, -85f};
    private static final double[] OFFS = {0.0, -0.3, 0.3};

    /** (目标状态, 当前状态) → 上次成功的 (旋转序号, 点击序号)，大部分方块第一次模拟就命中 */
    private static final Map<Long, int[]> CACHE = new HashMap<>();

    private Placer() {}

    public enum Result { PLACED, DEFERRED, FAILED }

    // ---- 服务端当前朝向跟踪 ----
    // 服务端算“玩家看向哪边”（梯子、墙上火把、按钮、活塞等）用的是头部朝向 yHeadRot，
    // 它要等服务端下一个 tick 才会跟上转头包；所以换朝向后要等两 tick 再放。
    private static float srvYaw, srvPitch;
    private static int srvTick = -100;
    private static float camYaw = Float.NaN, camPitch = Float.NaN;
    private static int nowTick;

    /** 每 tick 开头调用：检测镜头是否被玩家转动（原版会把镜头朝向同步给服务端） */
    public static void tickRotation(LocalPlayer p, int tick) {
        nowTick = tick;
        if (p.getYRot() != camYaw || p.getXRot() != camPitch) {
            camYaw = p.getYRot();
            camPitch = p.getXRot();
            srvYaw = camYaw;
            srvPitch = camPitch;
            srvTick = tick;
        }
    }

    /** 发转头包，并让原版认为镜头朝向“已经发过”，不会在本 tick 末尾覆盖 */
    public static void sendRotation(LocalPlayer p, float yaw, float pitch, boolean silent) {
        p.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, p.onGround()));
        srvYaw = yaw;
        srvPitch = pitch;
        srvTick = nowTick;
        if (!silent) {
            p.setYRot(yaw);
            p.setXRot(pitch);
            camYaw = yaw;
            camPitch = pitch;
        }
        p.yRotLast = p.getYRot();
        p.xRotLast = p.getXRot();
    }

    /** 别的途径（用桶）让服务端转了朝向 */
    public static void noteServerRotation(float yaw, float pitch) {
        srvYaw = yaw;
        srvPitch = pitch;
        srvTick = nowTick;
    }

    private static boolean sameRot(float yaw, float pitch) {
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - srvYaw)) < 0.01f && Math.abs(pitch - srvPitch) < 0.01f;
    }

    private static List<Rot> rotations(BlockState target) {
        List<Rot> out = new ArrayList<>();
        // 优先用服务端已经有的朝向：大部分方块不在乎朝向，这样完全不用转头
        out.add(new Rot(srvYaw, srvPitch));
        if (target.hasProperty(BlockStateProperties.ROTATION_16)) {
            int r = target.getValue(BlockStateProperties.ROTATION_16);
            // 告示牌/旗帜/头颅：rotation = round((yaw+180)*16/360)，反推 yaw
            out.add(new Rot(r * 22.5f - 180f, 0f));
        }
        for (float pitch : PITCHES) {
            for (float yaw : YAWS) out.add(new Rot(yaw, pitch));
        }
        return out;
    }

    private static List<Hit> hits(ClientLevel level, BlockPos pos, BlockState current, boolean airplace) {
        List<Hit> out = new ArrayList<>();
        // 1) 点击目标格本身：空气处“凭空放置”，或者在已有半砖/蜡烛上叠加
        boolean clickSelf = airplace || !current.isAir();
        if (clickSelf) {
            for (Direction face : Direction.values()) addFacePoints(out, pos, face, pos);
        }
        // 2) 点击相邻方块的面
        if (!airplace || !current.isAir()) {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                BlockState ns = level.getBlockState(n);
                if (StateMatcher.isSoft(ns)) continue;
                addFacePoints(out, n, d.getOpposite(), n);
            }
        }
        return out;
    }

    private static void addFacePoints(List<Hit> out, BlockPos blockPos, Direction face, BlockPos clicked) {
        Vec3 c = Vec3.atCenterOf(blockPos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Direction.Axis ax = face.getAxis();
        for (double u : OFFS) {
            for (double v : OFFS) {
                double x = c.x, y = c.y, z = c.z;
                switch (ax) {
                    case X -> { y += u; z += v; }
                    case Y -> { x += u; z += v; }
                    case Z -> { x += u; y += v; }
                }
                out.add(new Hit(clicked, face, new Vec3(x, y, z)));
            }
        }
    }

    /** 为目标状态找一个放置方案；没有可行方案返回 null */
    public static PlaceAction plan(Minecraft mc, BlockPos pos, BlockState target, BlockState current, Item item) {
        return plan(mc, pos, current, item, target, r -> StateMatcher.acceptable(target, current, r),
                ((long) Block.getId(target) << 32) | (Block.getId(current) & 0xFFFFFFFFL));
    }

    /** 放脚手架：只要是这种方块就行，不在乎朝向 */
    public static PlaceAction planAny(Minecraft mc, BlockPos pos, Item item) {
        BlockState current = mc.level.getBlockState(pos);
        Block block = ((BlockItem) item).getBlock();
        return plan(mc, pos, current, item, block.defaultBlockState(), r -> r != null && r.getBlock() == block,
                ((long) Block.getId(block.defaultBlockState()) << 32) | 0x7FFFFFFFL);
    }

    private static PlaceAction plan(Minecraft mc, BlockPos pos, BlockState current, Item item, BlockState rotTarget,
                                    Predicate<BlockState> accept, long cacheKey) {
        if (!(item instanceof BlockItem bi)) return null;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        boolean airplace = ABConfig.PLACE_MODE.get() == ABConfig.PlaceMode.AIRPLACE;
        List<Rot> rots = rotations(rotTarget);
        List<Hit> hits = hits(level, pos, current, airplace);
        if (hits.isEmpty()) return null;
        ItemStack stack = new ItemStack(item, 64);
        boolean sneak = !airplace;

        float yaw0 = p.getYRot(), pitch0 = p.getXRot(), yawO = p.yRotO, pitchO = p.xRotO;
        boolean shift0 = p.isShiftKeyDown();
        try {
            p.setShiftKeyDown(sneak);
            int[] cached = CACHE.get(cacheKey);
            if (cached != null && cached[0] < rots.size() && cached[1] < hits.size()) {
                PlaceAction a = trySim(p, level, bi, stack, pos, rots.get(cached[0]), hits.get(cached[1]), accept, sneak, item);
                if (a != null) return a;
            }
            for (int ri = 0; ri < rots.size(); ri++) {
                Rot r = rots.get(ri);
                for (int hi = 0; hi < hits.size(); hi++) {
                    PlaceAction a = trySim(p, level, bi, stack, pos, r, hits.get(hi), accept, sneak, item);
                    if (a != null) {
                        CACHE.put(cacheKey, new int[] {ri, hi});
                        return a;
                    }
                }
            }
            return null;
        } finally {
            p.setYRot(yaw0);
            p.setXRot(pitch0);
            p.yRotO = yawO;
            p.xRotO = pitchO;
            p.setShiftKeyDown(shift0);
        }
    }

    private static PlaceAction trySim(LocalPlayer p, ClientLevel level, BlockItem bi, ItemStack stack, BlockPos pos,
                                      Rot r, Hit h, Predicate<BlockState> accept, boolean sneak, Item item) {
        p.setYRot(r.yaw);
        p.setXRot(r.pitch);
        p.yRotO = r.yaw;
        p.xRotO = r.pitch;
        BlockHitResult hit = new BlockHitResult(h.loc, h.face, h.clicked, false);
        BlockPlaceContext ctx = new BlockPlaceContext(level, p, InteractionHand.MAIN_HAND, stack, hit);
        if (!ctx.getClickedPos().equals(pos)) return null;
        if (!ctx.canPlace()) return null;
        BlockPlaceContext ctx2 = bi.updatePlacementContext(ctx);
        if (ctx2 == null || !ctx2.getClickedPos().equals(pos)) return null;
        BlockState res;
        try {
            res = bi.getPlacementState(ctx2);
        } catch (RuntimeException e) {
            return null;
        }
        if (res == null || !accept.test(res)) return null;
        // 沙子、砂砾、混凝土粉末下面是空的会掉下去
        if (res.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) return null;
        // 换几个差别很大的朝向再模拟一次，结果都一样就说明和朝向无关
        boolean rotFree = true;
        for (float[] alt : new float[][] {{r.yaw + 90f, 0f}, {r.yaw + 180f, 0f}, {r.yaw - 90f, 0f}, {r.yaw, 85f}, {r.yaw, -85f}}) {
            p.setYRot(alt[0]);
            p.setXRot(alt[1]);
            p.yRotO = alt[0];
            p.xRotO = alt[1];
            BlockState alt2;
            try {
                BlockPlaceContext c2 = bi.updatePlacementContext(new BlockPlaceContext(level, p, InteractionHand.MAIN_HAND, stack, hit));
                alt2 = c2 == null ? null : bi.getPlacementState(c2);
            } catch (RuntimeException e) {
                alt2 = null;
            }
            if (alt2 != res) {
                rotFree = false;
                break;
            }
        }
        return new PlaceAction(pos, hit, r.yaw, r.pitch, sneak, item, rotFree);
    }

    /** 执行放置。调用前手上必须已经拿着 a.item。需要换朝向时只发转头包并返回 DEFERRED，过两 tick 再来。 */
    public static Result execute(Minecraft mc, PlaceAction a) {
        LocalPlayer p = mc.player;
        if (!p.getMainHandItem().is(a.item)) return Result.FAILED;
        boolean silent = ABConfig.SILENT_ROTATION.get();
        if (!a.rotFree && !sameRot(a.yaw, a.pitch)) {
            sendRotation(p, a.yaw, a.pitch, silent);
            return Result.DEFERRED;
        }
        if (!a.rotFree && nowTick - srvTick < 2) return Result.DEFERRED;
        float yaw0 = p.getYRot(), pitch0 = p.getXRot(), yawO = p.yRotO, pitchO = p.xRotO;
        // 客户端用本地朝向做预测，执行时临时转过去，和服务端保持一致
        p.setYRot(a.yaw);
        p.setXRot(a.pitch);
        p.yRotO = a.yaw;
        p.xRotO = a.pitch;
        boolean shift0 = p.isShiftKeyDown();
        if (a.sneak && !shift0) {
            p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
            p.setShiftKeyDown(true);
        }
        InteractionResult r;
        try {
            // 客户端会用本地朝向做预测，所以执行时本地朝向也要临时转过去
            r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, a.hit);
        } finally {
            if (a.sneak && !shift0) {
                p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                p.setShiftKeyDown(false);
            }
        }
        if (silent) {
            p.setYRot(yaw0);
            p.setXRot(pitch0);
            p.yRotO = yawO;
            p.xRotO = pitchO;
        }
        if (r.shouldSwing()) p.swing(InteractionHand.MAIN_HAND);
        if (BuildController.debug) {
            com.autobuilder.AutoBuilder.LOG.info("[AB] 放 {} yaw={} pitch={} face={} hit={} clicked={} 结果={} r={}", a.pos.toShortString(),
                    a.yaw, a.pitch, a.hit.getDirection(), a.hit.getLocation(), a.hit.getBlockPos().toShortString(),
                    mc.level.getBlockState(a.pos), r);
            DEBUG_LOG.add(new Object[] {a.pos, mc.level.getBlockState(a.pos), a.yaw, a.pitch, 0});
        }
        return r.consumesAction() ? Result.PLACED : Result.FAILED;
    }

    /** 调试：最近的放置记录（位置、客户端结果、yaw、pitch、已过 tick） */
    public static final java.util.List<Object[]> DEBUG_LOG = new java.util.ArrayList<>();

    /** 右键已有方块（调中继器延迟、音符盒音高、开关门等） */
    public static boolean interact(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        InventoryHelper.selectNonBlock(mc);
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        Direction face = Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Vec3 loc = c.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(loc, face, pos, false);
        boolean shift0 = p.isShiftKeyDown();
        if (shift0) {
            p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            p.setShiftKeyDown(false);
        }
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if (r.shouldSwing()) p.swing(InteractionHand.MAIN_HAND);
        return r.consumesAction();
    }
}
