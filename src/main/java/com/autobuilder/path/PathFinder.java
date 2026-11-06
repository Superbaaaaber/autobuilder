package com.autobuilder.path;

import com.autobuilder.build.Placement;
import com.autobuilder.build.StateMatcher;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 简化版 A* 寻路。节点 = 玩家脚所在的方块坐标 + 实际站立高度。
 * 支持：平走/斜走、跳上 1 格、下落 ≤maxFall 格、原地搭柱子、向前搭桥、挖开挡路方块、向下挖。
 */
public final class PathFinder {
    public enum Move { START, WALK, ASCEND, FALL, PILLAR, BRIDGE, STEP_DOWN, MINE, DIG_DOWN }

    public record Step(BlockPos pos, double standY, Move move, BlockPos place, List<BlockPos> mine) {}

    public interface Goal {
        boolean reached(int x, int y, int z, double standY);

        double heuristic(int x, int y, int z, double standY);
    }

    /** 寻路需要的世界信息和开关 */
    public static final class Ctx {
        final ClientLevel level;
        final Placement placement;
        final Set<BlockPos> scaffold;
        final boolean allowScaffold;
        final boolean allowDig;
        final int maxFall;
        final int scaffoldBlocks;
        final java.util.function.Predicate<BlockState> haveTargetItem;
        private final Long2ObjectOpenHashMap<BlockState> cache = new Long2ObjectOpenHashMap<>();
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        public Ctx(ClientLevel level, Placement placement, Set<BlockPos> scaffold, boolean allowScaffold, boolean allowDig,
                   int maxFall, int scaffoldBlocks, java.util.function.Predicate<BlockState> haveTargetItem) {
            this.level = level;
            this.placement = placement;
            this.scaffold = scaffold;
            this.allowScaffold = allowScaffold;
            this.allowDig = allowDig;
            this.maxFall = maxFall;
            this.scaffoldBlocks = scaffoldBlocks;
            this.haveTargetItem = haveTargetItem;
        }

        BlockState get(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            BlockState s = cache.get(k);
            if (s == null) {
                m.set(x, y, z);
                if (level.isOutsideBuildHeight(m) || !level.isLoaded(m)) s = Blocks.BEDROCK.defaultBlockState();
                else s = level.getBlockState(m);
                cache.put(k, s);
            }
            return s;
        }

        VoxelShape shape(BlockState s, int x, int y, int z) {
            m.set(x, y, z);
            return s.getCollisionShape(level, m);
        }

        float hardness(BlockState s, int x, int y, int z) {
            m.set(x, y, z);
            return s.getDestroySpeed(level, m);
        }
    }

    private static final class Node {
        final int x, y, z;
        final double stand;
        double g, f;
        Node parent;
        Move move;
        BlockPos place;
        List<BlockPos> mine;
        int scaffoldUsed;

        Node(int x, int y, int z, double stand) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.stand = stand;
        }
    }

    private PathFinder() {}

    // ---------------- 世界判断 ----------------

    static boolean dangerous(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.CACTUS) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.COBWEB)
                || s.is(Blocks.POINTED_DRIPSTONE) || s.getFluidState().is(FluidTags.LAVA);
    }

    /** 不能当落脚点的方块：会被红石打开或推走（活板门、门、栅栏门、活塞），或者会弹人 */
    static boolean unsafeFloor(BlockState s) {
        var b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.TrapDoorBlock || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || b instanceof net.minecraft.world.level.block.piston.PistonHeadBlock
                || b instanceof net.minecraft.world.level.block.piston.MovingPistonBlock
                || b instanceof net.minecraft.world.level.block.SlimeBlock || b instanceof net.minecraft.world.level.block.HoneyBlock
                || b instanceof net.minecraft.world.level.block.ScaffoldingBlock;
    }

    /** 脚下 3 格以内有岩浆：站在这里一旦脚下方块没了（被推走、被挖、活板门打开）就会掉进去 */
    static boolean lavaBelow(Ctx c, int x, int y, int z) {
        for (int d = 1; d <= 3; d++) {
            if (c.get(x, y - d, z).getFluidState().is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** 人能不能在这一格里（脚或头） */
    public static boolean passable(Ctx c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        if (s.isAir()) return true;
        if (dangerous(s)) return false;
        if (!s.getFluidState().isEmpty()) return false; // 水里不走
        return c.shape(s, x, y, z).isEmpty();
    }

    /** 站在 (x,y,z) 这一格时脚底的绝对高度；站不住返回 NaN（含头顶空间检查） */
    public static double stand(Ctx c, int x, int y, int z) {
        BlockState feet = c.get(x, y, z);
        if (!feet.isAir()) {
            VoxelShape sh = c.shape(feet, x, y, z);
            if (!sh.isEmpty()) {
                double top = sh.max(Direction.Axis.Y);
                if (top > 0.5001 || dangerous(feet) || !feet.getFluidState().isEmpty() || unsafeFloor(feet)) return Double.NaN;
                double h = y + top;
                if (lavaBelow(c, x, y, z)) return Double.NaN;
                return clear(c, x, y + 1, z, h) ? h : Double.NaN;
            }
            if (dangerous(feet) || !feet.getFluidState().isEmpty()) return Double.NaN;
        }
        BlockState below = c.get(x, y - 1, z);
        if (dangerous(below) || unsafeFloor(below)) return Double.NaN;
        VoxelShape bs = c.shape(below, x, y - 1, z);
        if (bs.isEmpty()) return Double.NaN;
        double top = bs.max(Direction.Axis.Y);
        if (top < 0.5001 || top > 1.0001) return Double.NaN;
        double h = y - 1 + top;
        if (lavaBelow(c, x, y - 1, z)) return Double.NaN;
        return clear(c, x, y, z, h) ? h : Double.NaN;
    }

    private static boolean clear(Ctx c, int x, int yStart, int z, double h) {
        int yEnd = (int) Math.floor(h + 1.79);
        for (int yy = yStart; yy <= yEnd; yy++) {
            if (!passable(c, x, yy, z)) return false;
        }
        return true;
    }

    private static boolean columnPassable(Ctx c, int x, int z, int y0, int y1) {
        for (int yy = y0; yy <= y1; yy++) {
            if (!passable(c, x, yy, z)) return false;
        }
        return true;
    }

    /** 这一格能不能垫一个方块（脚手架或者蓝图本来就要的整方块） */
    static boolean canPutSupport(Ctx c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        if (!s.isAir() && !(StateMatcher.isSoft(s) && s.getFluidState().isEmpty())) return false;
        if (dangerous(s) || lavaBelow(c, x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState target = c.placement == null ? null : c.placement.targetAt(p);
        if (target == null || target.isAir()) return c.scaffoldBlocks > 0;
        // 蓝图在这里本来就要一个整方块：直接放它
        return target.isCollisionShapeFullBlock(c.level, p) && c.haveTargetItem.test(target);
    }

    /** 挖这个方块的代价；不能挖返回 -1 */
    static double mineCost(Ctx c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        if (s.isAir() || passable(c, x, y, z)) return 0;
        if (!s.getFluidState().isEmpty() || dangerous(s)) return -1;
        float hard = c.hardness(s, x, y, z);
        if (hard < 0 || hard >= 40f) return -1;
        // 挖开后旁边有岩浆/水会流进来
        for (Direction d : Direction.values()) {
            BlockState n = c.get(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            if (!n.getFluidState().isEmpty()) return -1;
        }
        // 上面压着沙子砂砾
        if (c.get(x, y + 1, z).getBlock() instanceof FallingBlock) return -1;
        double cost = 2 + hard * 1.5;
        BlockPos p = new BlockPos(x, y, z);
        if (c.scaffold.contains(p)) return cost;
        if (c.placement != null) {
            BlockState target = c.placement.targetAt(p);
            if (target != null && !target.isAir()
                    && StateMatcher.classify(target, s, true, true) == StateMatcher.Status.OK) {
                cost += 1000; // 拆自己已经建好的部分：只在被困住、实在没别的路时才用
            }
        }
        return cost;
    }

    // ---------------- A* ----------------

    public static List<Step> find(Ctx c, BlockPos start, double startStand, Goal goal, int maxNodes, long maxNanos) {
        long deadline = System.nanoTime() + maxNanos;
        Long2ObjectOpenHashMap<Node> best = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Node s0 = new Node(start.getX(), start.getY(), start.getZ(), startStand);
        s0.move = Move.START;
        s0.f = goal.heuristic(s0.x, s0.y, s0.z, s0.stand);
        best.put(BlockPos.asLong(s0.x, s0.y, s0.z), s0);
        open.add(s0);
        Node closest = s0;
        double closestH = s0.f;
        int expanded = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            Node bn = best.get(BlockPos.asLong(n.x, n.y, n.z));
            if (bn != n) continue;
            if (goal.reached(n.x, n.y, n.z, n.stand)) return build(n);
            double h = goal.heuristic(n.x, n.y, n.z, n.stand);
            if (h < closestH) {
                closestH = h;
                closest = n;
            }
            if (++expanded > maxNodes || (expanded % 256 == 0 && System.nanoTime() > deadline)) break;
            expand(c, n, goal, best, open);
        }
        // 没找到完整路径：如果能明显靠近目标，先走过去再重新找
        if (closest != s0 && closestH < s0.f - 3) return build(closest);
        return null;
    }

    private static void expand(Ctx c, Node n, Goal goal, Long2ObjectOpenHashMap<Node> best, PriorityQueue<Node> open) {
        int x = n.x, y = n.y, z = n.z;
        double h = n.stand;
        boolean onFullBlock = Math.abs(h - y) < 0.01;
        int headTop = (int) Math.floor(h + 1.79);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diag = dx != 0 && dz != 0;
                int nx = x + dx, nz = z + dz;
                boolean walked = false;
                for (int dy = 1; dy >= -(c.maxFall + 1); dy--) {
                    int ny = y + dy;
                    double hd = stand(c, nx, ny, nz);
                    if (Double.isNaN(hd)) continue;
                    double dh = hd - h;
                    if (dh > 1.2) continue;
                    int destTop = (int) Math.floor(hd + 1.79);
                    double cost;
                    Move mv;
                    if (dh > 0.6) {
                        if (diag) break;
                        // 起跳时头顶要有空间
                        if (!columnPassable(c, x, z, headTop + 1, destTop)) break;
                        cost = 2.0;
                        mv = Move.ASCEND;
                    } else if (dh < -0.6) {
                        if (diag || -dh > c.maxFall + 0.01) break;
                        if (!columnPassable(c, nx, nz, destTop + 1, headTop)) break;
                        cost = 1.0 + 0.4 * -dh;
                        mv = Move.FALL;
                    } else {
                        if (dh > 0 && !columnPassable(c, x, z, headTop + 1, destTop)) break;
                        if (dh < 0 && !columnPassable(c, nx, nz, destTop + 1, headTop)) break;
                        if (diag) {
                            int lo = (int) Math.floor(Math.min(h, hd) + 0.01);
                            int hi = Math.max(headTop, destTop);
                            if (!columnPassable(c, x + dx, z, lo, hi) || !columnPassable(c, x, z + dz, lo, hi)) break;
                        }
                        cost = diag ? 1.414 : 1.0;
                        mv = Move.WALK;
                    }
                    push(c, n, nx, ny, nz, hd, mv, cost, null, null, goal, best, open);
                    walked = true;
                    break;
                }
                if (diag || walked) continue;
                // 搭桥：前方脚下是空的，垫一块再走过去
                if (c.allowScaffold && onFullBlock && n.scaffoldUsed < c.scaffoldBlocks
                        && passable(c, nx, y, nz) && passable(c, nx, y + 1, nz)
                        && canPutSupport(c, nx, y - 1, nz)) {
                    Node m = push(c, n, nx, y, nz, y, Move.BRIDGE, 4.0, new BlockPos(nx, y - 1, nz), null, goal, best, open);
                    if (m != null) m.scaffoldUsed = n.scaffoldUsed + 1;
                }
                // 往下搭台阶：旁边低一格处垫一块，然后走下去（从高处下来时用）
                if (c.allowScaffold && onFullBlock && n.scaffoldUsed < c.scaffoldBlocks
                        && passable(c, nx, y + 1, nz) && passable(c, nx, y, nz) && passable(c, nx, y - 1, nz)
                        && canPutSupport(c, nx, y - 2, nz)) {
                    Node m = push(c, n, nx, y - 1, nz, y - 1, Move.STEP_DOWN, 4.5, new BlockPos(nx, y - 2, nz), null, goal, best, open);
                    if (m != null) m.scaffoldUsed = n.scaffoldUsed + 1;
                }
                // 挖开前方（同层或往上一格）
                if (c.allowDig && onFullBlock) {
                    for (int dy = 0; dy <= 1; dy++) {
                        int ny = y + dy;
                        BlockState floor = c.get(nx, ny - 1, nz);
                        VoxelShape fs = c.shape(floor, nx, ny - 1, nz);
                        if (fs.isEmpty() || dangerous(floor) || fs.max(Direction.Axis.Y) < 0.99) continue;
                        List<BlockPos> mine = new ArrayList<>(3);
                        double cost = dy == 1 ? 2.0 : 1.0;
                        boolean ok = true;
                        if (dy == 1) {
                            double mc = mineCost(c, x, y + 2, z);
                            if (mc < 0) ok = false;
                            else if (mc > 0) { cost += mc; mine.add(new BlockPos(x, y + 2, z)); }
                        }
                        for (int yy = ny; ok && yy <= ny + 1; yy++) {
                            double mc = mineCost(c, nx, yy, nz);
                            if (mc < 0) ok = false;
                            else if (mc > 0) { cost += mc; mine.add(new BlockPos(nx, yy, nz)); }
                        }
                        if (!ok || mine.isEmpty()) continue;
                        push(c, n, nx, ny, nz, ny, Move.MINE, cost, null, mine, goal, best, open);
                    }
                }
            }
        }
        if (!onFullBlock) return;
        // 原地搭柱子：跳起来往脚下垫一块
        if (c.allowScaffold && n.scaffoldUsed < c.scaffoldBlocks && passable(c, x, y + 2, z) && canPutSupport(c, x, y, z)) {
            Node m = push(c, n, x, y + 1, z, y + 1, Move.PILLAR, 5.0, new BlockPos(x, y, z), null, goal, best, open);
            if (m != null) m.scaffoldUsed = n.scaffoldUsed + 1;
        }
        // 向下挖一格
        if (c.allowDig) {
            double mc = mineCost(c, x, y - 1, z);
            if (mc > 0) {
                BlockState floor = c.get(x, y - 2, z);
                VoxelShape fs = c.shape(floor, x, y - 2, z);
                if (!fs.isEmpty() && !dangerous(floor) && fs.max(Direction.Axis.Y) >= 0.99) {
                    push(c, n, x, y - 1, z, y - 1, Move.DIG_DOWN, 1.0 + mc, null,
                            List.of(new BlockPos(x, y - 1, z)), goal, best, open);
                }
            }
        }
    }

    private static Node push(Ctx c, Node from, int x, int y, int z, double stand, Move mv, double cost, BlockPos place,
                             List<BlockPos> mine, Goal goal, Long2ObjectOpenHashMap<Node> best, PriorityQueue<Node> open) {
        long k = BlockPos.asLong(x, y, z);
        double g = from.g + cost;
        Node old = best.get(k);
        if (old != null && old.g <= g) return null;
        Node n = new Node(x, y, z, stand);
        n.g = g;
        n.f = g + goal.heuristic(x, y, z, stand) * 1.3;
        n.parent = from;
        n.move = mv;
        n.place = place;
        n.mine = mine;
        n.scaffoldUsed = from.scaffoldUsed;
        best.put(k, n);
        open.add(n);
        return n;
    }

    private static List<Step> build(Node end) {
        List<Step> out = new ArrayList<>();
        for (Node n = end; n != null && n.move != Move.START; n = n.parent) {
            out.add(new Step(new BlockPos(n.x, n.y, n.z), n.stand, n.move, n.place, n.mine));
        }
        Collections.reverse(out);
        return out;
    }
}
