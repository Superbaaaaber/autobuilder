package com.autobuilder.path;

import com.autobuilder.build.Placement;
import com.autobuilder.build.StateMatcher;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
        /** 实际走不通的路段（PathFinder.moveKey），寻路时跳过 */
        public java.util.Set<Long> badMoves = java.util.Set.of();
        /** 能不能蹚水：脚那一格是水（流动的浅水、一格深的水）、头那一格没水，就能站、能走 */
        public boolean wade = true;
        /** 能不能往水里垫方块（拆脚手架时不行：拆一块水流回来、再垫一块，会来回折腾） */
        public boolean waterSupport = true;
        /** 不许再垫方块的位置（拆脚手架时同一格垫过两次了，再垫就是来回折腾） */
        public java.util.Set<Long> noSupport = java.util.Set.of();
        /** 不许再挖的建筑方块（寻路已经挖开过两次又被补上了，再挖就是来回折腾） */
        public java.util.Set<Long> noDig = java.util.Set.of();
        /** 估价的权重：越大越“直奔目标”，搜得快但路线不一定最短 */
        public double hWeight = 1.3;
        // 寻路期间世界当它不变：按 16×16×16 一块存方块、站高、能不能过人、挖开代价、能不能垫、寻路节点，
        // 每样只算一次，查一格只是数组下标。原来每样一张哈希表（节点也是），一格被周围八个点、几个高度反复问，
        // 时间大半花在算哈希、找表上
        private final Long2ObjectOpenHashMap<Sec> secs = new Long2ObjectOpenHashMap<>();
        private Sec lastSec;
        private long lastSecKey = Long.MIN_VALUE;
        private int search;
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

        Sec sec(int x, int y, int z) {
            long k = BlockPos.asLong(x >> 4, y >> 4, z >> 4);
            if (k == lastSecKey) return lastSec;
            Sec s = secs.get(k);
            if (s == null) {
                s = new Sec();
                secs.put(k, s);
            }
            lastSec = s;
            lastSecKey = k;
            return s;
        }

        BlockState get(int x, int y, int z) {
            Sec s = sec(x, y, z);
            int i = idx(x, y, z);
            BlockState b = s.st[i];
            if (b == null) {
                m.set(x, y, z);
                if (level.isOutsideBuildHeight(m) || !level.isLoaded(m)) b = Blocks.BEDROCK.defaultBlockState();
                else b = level.getBlockState(m);
                s.st[i] = b;
            }
            return b;
        }

        /** 这次搜索里这一格的节点（没有返回 null） */
        Node node(int x, int y, int z) {
            Sec s = sec(x, y, z);
            if (s.nodes == null) return null;
            Node n = s.nodes[idx(x, y, z)];
            return n != null && n.search == search ? n : null;
        }

        void putNode(Node n) {
            Sec s = sec(n.x, n.y, n.z);
            if (s.nodes == null) s.nodes = new Node[4096];
            n.search = search;
            s.nodes[idx(n.x, n.y, n.z)] = n;
        }

        /** 从 from 花 cost 走到这一格，比已知的路更近吗 */
        boolean improves(Node from, int x, int y, int z, double cost) {
            Node o = node(x, y, z);
            return o == null || o.g > from.g + cost;
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

    static final double UNKNOWN = Double.NEGATIVE_INFINITY;

    static int idx(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    /** 16×16×16 一块的缓存；UNKNOWN / 0 表示还没算 */
    static final class Sec {
        final BlockState[] st = new BlockState[4096];
        final double[] stand = new double[4096];
        final double[] mine = new double[4096];
        /** 1 能过人 2 不能 */
        final byte[] pass = new byte[4096];
        /** 1 能垫方块 2 不能 */
        final byte[] support = new byte[4096];
        /** 1 是能站的整方块地面（挖过去时脚下要有） 2 不是 */
        final byte[] floor = new byte[4096];
        Node[] nodes;

        Sec() {
            Arrays.fill(stand, UNKNOWN);
            Arrays.fill(mine, UNKNOWN);
        }
    }

    private static final class Node {
        final int x, y, z;
        double stand;
        double g, f, h;
        Node parent;
        Move move;
        BlockPos place;
        List<BlockPos> mine;
        int scaffoldUsed;
        /** 在堆里的位置（从 1 起），0 = 不在堆里 */
        int heap;
        int search;

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
                || b instanceof net.minecraft.world.level.block.ScaffoldingBlock
                // 天然树叶：树干砍掉后会自己消失，站上去会掉下来
                || b instanceof net.minecraft.world.level.block.LeavesBlock && !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
    }

    /** 脚下 3 格以内有岩浆：站在这里一旦脚下方块没了（被推走、被挖、活板门打开）就会掉进去 */
    static boolean lavaBelow(Ctx c, int x, int y, int z) {
        for (int d = 1; d <= 3; d++) {
            if (c.get(x, y - d, z).getFluidState().is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** 能蹚过去的水：水方块（流动的或水源），不是岩浆 */
    static boolean wadeable(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && s.getFluidState().is(FluidTags.WATER);
    }

    /** 脚这一格能不能待（头那一格用 passable）：蹚水时脚可以在水里 */
    static boolean passableFeet(Ctx c, int x, int y, int z) {
        return passable(c, x, y, z) || c.wade && wadeable(c.get(x, y, z));
    }

    /** 人能不能在这一格里（脚或头） */
    public static boolean passable(Ctx c, int x, int y, int z) {
        Sec s = c.sec(x, y, z);
        int i = idx(x, y, z);
        byte v = s.pass[i];
        if (v != 0) return v == 1;
        boolean r = passable0(c, x, y, z);
        s.pass[i] = r ? (byte) 1 : (byte) 2;
        return r;
    }

    private static boolean passable0(Ctx c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        if (s.isAir()) return true;
        if (dangerous(s)) return false;
        if (!s.getFluidState().isEmpty()) return false; // 水里不走
        return c.shape(s, x, y, z).isEmpty();
    }

    /** 站在 (x,y,z) 这一格时脚底的绝对高度；站不住返回 NaN（含头顶空间检查） */
    public static double stand(Ctx c, int x, int y, int z) {
        Sec s = c.sec(x, y, z);
        int i = idx(x, y, z);
        double v = s.stand[i];
        if (v != UNKNOWN) return v;
        v = stand0(c, x, y, z);
        s.stand[i] = v;
        return v;
    }

    private static double stand0(Ctx c, int x, int y, int z) {
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
            // 原来脚下有水一律站不住：流动水里的方块够不着，只能在外面一格格垫过去，人站进流动水里更是连路都找不出来
            if (dangerous(feet) || !feet.getFluidState().isEmpty() && !(c.wade && wadeable(feet))) return Double.NaN;
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
            // 最下面一格（脚）可以是浅水，往上不行（头进水会憋气）
            if (!(yy == yStart ? passableFeet(c, x, yy, z) : passable(c, x, yy, z))) return false;
        }
        return true;
    }

    private static boolean columnPassable(Ctx c, int x, int z, int y0, int y1) {
        for (int yy = y0; yy <= y1; yy++) {
            if (!passable(c, x, yy, z)) return false;
        }
        return true;
    }

    /** 路段的键：从哪个落脚点走到哪个落脚点 */
    public static long moveKey(BlockPos from, BlockPos to) {
        return from.asLong() * 0x9E3779B97F4A7C15L ^ to.asLong();
    }

    /** 这一格能不能垫一个方块（脚手架或者蓝图本来就要的整方块）；水面上也能垫（方块直接把水顶掉） */
    static boolean canPutSupport(Ctx c, int x, int y, int z) {
        Sec sec = c.sec(x, y, z);
        int i = idx(x, y, z);
        byte v = sec.support[i];
        if (v != 0) return v == 1;
        boolean r = canPutSupport0(c, x, y, z);
        sec.support[i] = r ? (byte) 1 : (byte) 2;
        return r;
    }

    /** 能站的整方块地面（挖过去、往下挖时脚下要有） */
    static boolean solidFloor(Ctx c, int x, int y, int z) {
        Sec sec = c.sec(x, y, z);
        int i = idx(x, y, z);
        byte v = sec.floor[i];
        if (v != 0) return v == 1;
        BlockState floor = c.get(x, y, z);
        VoxelShape fs = c.shape(floor, x, y, z);
        boolean r = !fs.isEmpty() && !dangerous(floor) && fs.max(Direction.Axis.Y) >= 0.99;
        sec.floor[i] = r ? (byte) 1 : (byte) 2;
        return r;
    }

    private static boolean canPutSupport0(Ctx c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        boolean water = c.waterSupport && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && s.getFluidState().is(FluidTags.WATER);
        if (!s.isAir() && !water && !(StateMatcher.isSoft(s) && s.getFluidState().isEmpty())) return false;
        if (dangerous(s) || lavaBelow(c, x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        if (!c.noSupport.isEmpty() && c.noSupport.contains(p.asLong())) return false;
        BlockState target = c.placement == null ? null : c.placement.targetAt(p);
        if (target == null || target.isAir()) return c.scaffoldBlocks > 0;
        // 蓝图在这里本来就要一个整方块：直接放它
        return target.isCollisionShapeFullBlock(c.level, p) && c.haveTargetItem.test(target);
    }

    /** 挖这个方块的代价；不能挖返回 -1 */
    static double mineCost(Ctx c, int x, int y, int z) {
        Sec s = c.sec(x, y, z);
        int i = idx(x, y, z);
        double v = s.mine[i];
        if (v != UNKNOWN) return v;
        v = mineCost0(c, x, y, z);
        s.mine[i] = v;
        return v;
    }

    private static double mineCost0(Ctx c, int x, int y, int z) {
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
        // 建筑正下方的地基别挖：挖空了容易把自己困在地板底下
        if (c.placement != null) {
            BlockPos o = c.placement.origin();
            if (x >= o.getX() && z >= o.getZ() && x < o.getX() + c.placement.sizeX() && z < o.getZ() + c.placement.sizeZ()
                    && y < o.getY() && y >= o.getY() - 4) cost += 40;
        }
        if (c.scaffold.contains(p)) return cost;
        if (!c.noDig.isEmpty() && c.noDig.contains(p.asLong())) return -1;
        if (c.placement != null && builtPart(c.placement.targetAt(p), s)) {
            cost += 1000; // 拆自己已经建好的部分：只在被困住、实在没别的路时才用
        }
        return cost;
    }

    /** 这格是不是已经建好的建筑方块（方块对了，可能还差右键调整、锄地、舀水） */
    public static boolean builtPart(BlockState target, BlockState world) {
        if (target == null || target.isAir() || world.isAir()) return false;
        StateMatcher.Status st = StateMatcher.classify(target, world, true, true);
        return st == StateMatcher.Status.OK || st == StateMatcher.Status.INTERACT || st == StateMatcher.Status.WAIT
                || st == StateMatcher.Status.TILL || st == StateMatcher.Status.UNWATERLOG || st == StateMatcher.Status.WATERLOG;
    }

    // ---------------- A* ----------------

    /** 统计（测试用）：调用次数、总耗时、展开的节点数、找不到路的次数 */
    public static long statCalls, statNanos, statNodes, statFails;
    /** 上一次没找到路，是因为时间/节点数用完了（不一定真没路），而不是能走的地方都搜完了 */
    public static boolean lastTimedOut;

    public static List<Step> find(Ctx c, BlockPos start, double startStand, Goal goal, int maxNodes, long maxNanos) {
        long t0 = System.nanoTime();
        statCalls++;
        lastTimedOut = false;
        long n0 = statNodes;
        List<Step> r = find0(c, start, startStand, goal, maxNodes, maxNanos);
        statNanos += System.nanoTime() - t0;
        if (r == null) {
            statFails++;
            com.autobuilder.Diag.log("找不到路 起点 {}（站高 {}）展开 {} 用时 {}ms{}", start, String.format("%.2f", startStand), statNodes - n0,
                    (System.nanoTime() - t0) / 1_000_000, lastTimedOut ? "（时间/节点数用完了，不一定真没路）" : "（能走的地方都搜完了）");
        } else {
            com.autobuilder.Diag.note("寻路 起点 " + start.toShortString() + " → " + r.size() + " 步 展开 " + (statNodes - n0) + " 用时 "
                    + (System.nanoTime() - t0) / 1_000_000 + "ms" + (r.isEmpty() ? "" : " 终点 " + r.get(r.size() - 1).pos().toShortString()));
        }
        return r;
    }

    /** 路线摘要：每种走法几步，比如 WALK×5 PILLAR×2 */
    public static String summary(List<Step> path) {
        java.util.Map<Move, Integer> m = new java.util.EnumMap<>(Move.class);
        for (Step s : path) m.merge(s.move(), 1, Integer::sum);
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> b.append(k).append('×').append(v).append(' '));
        return path.size() + " 步 " + b.toString().trim();
    }

    private static List<Step> find0(Ctx c, BlockPos start, double startStand, Goal goal, int maxNodes, long maxNanos) {
        long deadline = System.nanoTime() + maxNanos;
        c.search++;
        Heap open = new Heap();
        Node s0 = new Node(start.getX(), start.getY(), start.getZ(), startStand);
        s0.move = Move.START;
        s0.h = s0.f = goal.heuristic(s0.x, s0.y, s0.z, s0.stand);
        c.putNode(s0);
        open.add(s0);
        Node closest = s0;
        double closestH = s0.f;
        int expanded = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (goal.reached(n.x, n.y, n.z, n.stand)) return build(n);
            double h = n.h;
            if (h < closestH) {
                closestH = h;
                closest = n;
            }
            statNodes++;
            if (++expanded > maxNodes || (expanded % 256 == 0 && System.nanoTime() > deadline)) {
                lastTimedOut = true;
                break;
            }
            expand(c, n, goal, open);
        }
        // 没找到完整路径：如果能明显靠近目标，先走过去再重新找
        if (closest != s0 && closestH < s0.h - 3) return build(closest);
        return null;
    }

    private static void expand(Ctx c, Node n, Goal goal, Heap open) {
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
                    // 跳上去或掉下去会把耕地踩回泥土
                    if (Math.abs(dh) > 0.6 && c.get(nx, (int) Math.floor(hd - 0.01), nz).is(Blocks.FARMLAND)) break;
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
                            // 斜着走要擦过两边的格子：脚那层可以是浅水
                            if (!passableFeet(c, x + dx, lo, z) || !passableFeet(c, x, lo, z + dz)
                                    || !columnPassable(c, x + dx, z, lo + 1, hi) || !columnPassable(c, x, z + dz, lo + 1, hi)) break;
                        }
                        cost = diag ? 1.414 : 1.0;
                        mv = Move.WALK;
                    }
                    // 蹚水比走旱路慢（水流还会推人）：多算点代价，有旱路就走旱路
                    if (c.wade && wadeable(c.get(nx, ny, nz))) cost += 2.0;
                    push(c, n, nx, ny, nz, hd, mv, cost, null, null, goal, open);
                    walked = true;
                    break;
                }
                if (diag || walked) continue;
                // 搭桥：前方脚下是空的，垫一块再走过去
                if (c.allowScaffold && onFullBlock && n.scaffoldUsed < c.scaffoldBlocks
                        && passable(c, nx, y, nz) && passable(c, nx, y + 1, nz)
                        && canPutSupport(c, nx, y - 1, nz)) {
                    Node m = c.improves(n, nx, y, nz, 4.0) ? push(c, n, nx, y, nz, y, Move.BRIDGE, 4.0, new BlockPos(nx, y - 1, nz), null, goal, open) : null;
                    if (m != null) m.scaffoldUsed = n.scaffoldUsed + 1;
                }
                // 往下搭台阶：旁边低一格处垫一块，然后走下去（从高处下来时用）
                if (c.allowScaffold && onFullBlock && n.scaffoldUsed < c.scaffoldBlocks
                        && passable(c, nx, y + 1, nz) && passable(c, nx, y, nz) && passable(c, nx, y - 1, nz)
                        && canPutSupport(c, nx, y - 2, nz)) {
                    Node m = c.improves(n, nx, y - 1, nz, 4.5) ? push(c, n, nx, y - 1, nz, y - 1, Move.STEP_DOWN, 4.5, new BlockPos(nx, y - 2, nz), null, goal, open) : null;
                    if (m != null) m.scaffoldUsed = n.scaffoldUsed + 1;
                }
                // 挖开前方（同层或往上一格）
                if (c.allowDig && onFullBlock) {
                    for (int dy = 0; dy <= 1; dy++) {
                        int ny = y + dy;
                        if (!solidFloor(c, nx, ny - 1, nz)) continue;
                        double cost = dy == 1 ? 2.0 : 1.0;
                        double m0 = 0;
                        if (dy == 1) {
                            m0 = mineCost(c, x, y + 2, z);
                            if (m0 < 0) continue;
                        }
                        double m1 = mineCost(c, nx, ny, nz);
                        if (m1 < 0) continue;
                        double m2 = mineCost(c, nx, ny + 1, nz);
                        if (m2 < 0 || m0 == 0 && m1 == 0 && m2 == 0) continue;
                        if (m0 > 0) cost += m0;
                        if (m1 > 0) cost += m1;
                        if (m2 > 0) cost += m2;
                        // 大多数挖过去的走法都比已知的路远：先比一下，近了才建挖哪些格的列表
                        if (!c.improves(n, nx, ny, nz, cost)) continue;
                        List<BlockPos> mine = new ArrayList<>(3);
                        if (m0 > 0) mine.add(new BlockPos(x, y + 2, z));
                        if (m1 > 0) mine.add(new BlockPos(nx, ny, nz));
                        if (m2 > 0) mine.add(new BlockPos(nx, ny + 1, nz));
                        push(c, n, nx, ny, nz, ny, Move.MINE, cost, null, mine, goal, open);
                    }
                }
            }
        }
        if (!onFullBlock) return;
        // 原地搭柱子：跳起来往脚下垫一块（站在水里不行：水里按空格只是慢慢浮，跳不到一格高，垫不进去）
        if (c.allowScaffold && n.scaffoldUsed < c.scaffoldBlocks && passable(c, x, y + 2, z) && canPutSupport(c, x, y, z)
                && !(c.wade && wadeable(c.get(x, y, z)))) {
            Node m = c.improves(n, x, y + 1, z, 5.0) ? push(c, n, x, y + 1, z, y + 1, Move.PILLAR, 5.0, new BlockPos(x, y, z), null, goal, open) : null;
            if (m != null) m.scaffoldUsed = n.scaffoldUsed + 1;
        }
        // 向下挖一格
        if (c.allowDig) {
            double mc = mineCost(c, x, y - 1, z);
            if (mc > 0 && solidFloor(c, x, y - 2, z) && c.improves(n, x, y - 1, z, 1.0 + mc)) {
                push(c, n, x, y - 1, z, y - 1, Move.DIG_DOWN, 1.0 + mc, null, List.of(new BlockPos(x, y - 1, z)), goal, open);
            }
        }
    }

    private static Node push(Ctx c, Node from, int x, int y, int z, double stand, Move mv, double cost, BlockPos place,
                             List<BlockPos> mine, Goal goal, Heap open) {
        if (!c.badMoves.isEmpty() && c.badMoves.contains(moveKey(new BlockPos(from.x, from.y, from.z), new BlockPos(x, y, z)))) return null;
        double g = from.g + cost;
        Node n = c.node(x, y, z);
        if (n != null && n.g <= g) return null;
        if (n == null) {
            n = new Node(x, y, z, stand);
            n.h = goal.heuristic(x, y, z, stand);
            c.putNode(n);
        } else if (n.stand != stand) {
            n.stand = stand;
            n.h = goal.heuristic(x, y, z, stand);
        }
        // 找到更近的路：就地改，在堆里就上浮，已经展开过的重新放回堆里
        n.g = g;
        n.f = g + n.h * c.hWeight;
        n.parent = from;
        n.move = mv;
        n.place = place;
        n.mine = mine;
        n.scaffoldUsed = from.scaffoldUsed;
        if (n.heap > 0) open.update(n);
        else open.add(n);
        return n;
    }

    /** 二叉堆，按 f 排（一样时离目标近的先）。节点找到更近的路时就地上浮，不重复入堆（原来 PriorityQueue 里同一点有好几份） */
    private static final class Heap {
        private Node[] a = new Node[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void add(Node n) {
            if (++size == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size] = n;
            up(size);
        }

        void update(Node n) {
            up(n.heap);
        }

        Node poll() {
            Node r = a[1];
            Node last = a[size];
            a[size--] = null;
            r.heap = 0;
            if (size > 0) {
                a[1] = last;
                down(1);
            }
            return r;
        }

        private static boolean less(Node p, Node q) {
            return p.f < q.f || p.f == q.f && p.h < q.h;
        }

        private void up(int i) {
            Node n = a[i];
            while (i > 1) {
                int pi = i >>> 1;
                Node p = a[pi];
                if (!less(n, p)) break;
                a[i] = p;
                p.heap = i;
                i = pi;
            }
            a[i] = n;
            n.heap = i;
        }

        private void down(int i) {
            Node n = a[i];
            int half = size >>> 1;
            while (i <= half) {
                int ci = i << 1;
                Node cn = a[ci];
                if (ci < size && less(a[ci + 1], cn)) cn = a[++ci];
                if (!less(cn, n)) break;
                a[i] = cn;
                cn.heap = i;
                i = ci;
            }
            a[i] = n;
            n.heap = i;
        }
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
