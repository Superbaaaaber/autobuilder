package com.autobuilder.build;

import com.autobuilder.Lang;
import com.autobuilder.ABConfig;
import com.autobuilder.AutoBuilder;
import com.autobuilder.path.PathExecutor;
import com.autobuilder.path.PathFinder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 自动建造总控：每个客户端 tick 决定做什么 */
public final class BuildController {
    public enum State { IDLE, RUNNING, PAUSED, DONE }

    public enum Phase { BUILD, CLEANUP }

    private static final Minecraft MC = Minecraft.getInstance();

    private static Placement placement;
    private static State state = State.IDLE;
    private static Phase phase = Phase.BUILD;
    private static final Scanner SCANNER = new Scanner();
    private static final Breaker BREAKER = new Breaker();
    private static final PathExecutor WALKER = new PathExecutor();
    /** 自己垫的脚手架（完工后拆掉） */
    private static final Set<BlockPos> SCAFFOLD = new HashSet<>();
    private static final Map<BlockPos, Integer> PENDING = new HashMap<>();
    private static final Map<BlockPos, Integer> BLACKLIST = new HashMap<>();
    /** 同一格反复操作的次数：服务端一直拒绝（出生点保护、领地插件等）时别无限重试 */
    private static final Map<BlockPos, Integer> ATTEMPTS = new HashMap<>();
    private static boolean warnedRejected;
    private static int tick;
    private static int cooldown;
    private static int goalFails;
    private static int noTargetTicks;
    private static int cleanupRounds;
    private static int cleanupRetries;
    private static int pickupCooldownUntil;
    private static int refillReadyTick;
    private static Vec3 lastSafe;
    private static boolean observerLoose;
    private static boolean restoreChecked;
    private static String placementKey;
    /** 会把自己封在里面的方块 → 放它时人不能待在的区域（封闭区里的格子） */
    private static final Map<BlockPos, Set<Long>> SEALS = new HashMap<>();
    private static final Map<BlockPos, Integer> SEAL_TICK = new HashMap<>();
    /** 当前允许施工的最高层（0 普通方块 … 4 液体） */
    private static int allowedTier = 0;
    /** 液体屏障：还有液体没倒的最低 y，比它高的方块先不放（倒液体时上面要空着才看得见） */
    private static int fluidBarrierY = Integer.MAX_VALUE;
    /** 还没放好的方块的最低 y：液体所在层及以下都建完了才倒 */
    private static int minPendingSolidY = Integer.MAX_VALUE;
    private static Scanner.Snapshot barrierFor;
    /** 怎么都倒不进去的液体位置（试了几次放弃），完工时报告 */
    private static final Map<BlockPos, Integer> FLUID_FAILS = new HashMap<>();
    private static String activity = "";
    private static final Map<Item, Integer> MISSING = new LinkedHashMap<>();
    /** 开发测试时打开，记录每次挖/放 */
    public static boolean debug;

    private BuildController() {}

    // ---------------- 对外接口 ----------------

    public static Placement placement() { return placement; }
    public static State state() { return state; }
    public static Phase phase() { return phase; }
    public static String activity() { return activity; }
    public static Scanner.Snapshot snapshot() { return SCANNER.snapshot(); }
    public static Breaker breaker() { return BREAKER; }
    public static PathExecutor walker() { return WALKER; }
    public static Set<BlockPos> scaffold() { return SCAFFOLD; }
    public static Map<Item, Integer> missing() { return MISSING; }

    public static double reach() {
        return ABConfig.REACH.get();
    }

    public static void setPlacement(Placement p) {
        stop();
        placement = p;
        SCAFFOLD.clear();
        SCANNER.reset();
        state = State.IDLE;
        restoreChecked = true;
        placementKey = SupplyManager.worldKey();
        SessionStore.save(placementKey, p, SCAFFOLD);
    }

    public static void unload() {
        stop();
        if (placement != null) SessionStore.remove(placementKey);
        placement = null;
        SCANNER.reset();
        SCAFFOLD.clear();
    }

    public static void start() {
        if (placement == null) {
            msg(Lang.t("msg.no_blueprint"), ChatFormatting.RED);
            return;
        }
        if (!ServerPolicy.allowed()) {
            msg(Lang.t("msg.server_disabled_token", ServerPolicy.TOKEN), ChatFormatting.RED);
            return;
        }
        state = State.RUNNING;
        phase = Phase.BUILD;
        goalFails = 0;
        cleanupRounds = 0;
        cleanupRetries = 0;
        noTargetTicks = 0;
        allowedTier = 0;
        barrierFor = null;
        FLUID_FAILS.clear();
        observerLoose = false;
        SEALS.clear();
        SEAL_TICK.clear();
        BLACKLIST.clear();
        ATTEMPTS.clear();
        warnedRejected = false;
        MISSING.clear();
        SCANNER.restart();
        activity = Lang.t("act.start");
        msg(Lang.t("msg.start", placement.bp.name), ChatFormatting.GREEN);
    }

    public static void pause(String why) {
        if (state != State.RUNNING) return;
        state = State.PAUSED;
        halt();
        activity = why;
        msg(Lang.t("msg.paused", why), ChatFormatting.YELLOW);
    }

    public static void stop() {
        if (state == State.RUNNING || state == State.PAUSED) activity = Lang.t("act.stopped");
        state = State.IDLE;
        halt();
    }

    public static void toggle() {
        if (state == State.RUNNING) pause(Lang.t("reason.manual"));
        else start();
    }

    private static void halt() {
        Restocker.abort(MC);
        BREAKER.cancel(MC);
        WALKER.clear();
        AutoEat.release(MC);
    }

    /** 退出世界：保存断点，清掉内存里的状态（不删存档） */
    public static void detach() {
        if (placement != null && state != State.DONE) SessionStore.save(placementKey, placement, SCAFFOLD);
        stop();
        placement = null;
        SCANNER.reset();
        SCAFFOLD.clear();
        restoreChecked = false;
    }

    private static void finish(String text) {
        state = State.DONE;
        SessionStore.remove(placementKey);
        halt();
        activity = text;
        msg(text, ChatFormatting.GREEN);
    }

    public static void msg(String text, ChatFormatting color) {
        if (MC.player != null) MC.player.displayClientMessage(Component.literal("[AutoBuilder] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color)), false);
        AutoBuilder.LOG.info("[AutoBuilder] {}", text);
    }

    // ---------------- 主循环 ----------------

    public static void tick() {
        tick++;
        LocalPlayer p = MC.player;
        ClientLevel level = MC.level;
        if (p == null || level == null) {
            if (state == State.RUNNING) stop();
            return;
        }
        if (placement == null) {
            // 刚进世界：看看有没有上次没建完的
            if (!restoreChecked && tick > 40) {
                restoreChecked = true;
                Placement pl = SessionStore.restore(SCAFFOLD);
                if (pl != null) {
                    placement = pl;
                    placementKey = SupplyManager.worldKey();
                    SCANNER.reset();
                    state = State.IDLE;
                    msg(Lang.t("msg.resume_found", pl.bp.name, pl.origin().toShortString()), ChatFormatting.AQUA);
                }
            }
            return;
        }
        // 换了维度/世界：蓝图不属于这里，先收起来（断点照样保存）
        if (!SupplyManager.worldKey().equals(placementKey)) {
            detach();
            restoreChecked = false;
            return;
        }
        // 定期保存断点（位置、旋转或脚手架有变化时）
        if (tick % 40 == 0 && state != State.DONE) SessionStore.save(placementKey, placement, SCAFFOLD); // 建完的不再存，免得下次进世界提示“没建完”
        Placer.tickRotation(p, tick);
        SCANNER.tick(placement, level, 30000, SCAFFOLD);
        if (state == State.RUNNING && !ServerPolicy.allowed()) {
            stop();
            msg(Lang.t("msg.server_disabled_stop"), ChatFormatting.RED);
        }
        if (state != State.RUNNING) {
            WALKER.release();
            return;
        }
        if (p.isDeadOrDying()) {
            stop();
            msg(Lang.t("msg.died"), ChatFormatting.RED);
            return;
        }
        // 掉进岩浆/着火：先逃命
        if (p.isInLava() || p.isOnFire() && !p.isInWater()) {
            BREAKER.cancel(MC);
            WALKER.escape(p, lastSafe);
            activity = Lang.t("act.escape");
            return;
        }
        if (p.onGround() && !p.isInWater() && p.hurtTime == 0) lastSafe = p.position();
        if (p.getHealth() < ABConfig.STOP_BELOW_HEALTH.get()) {
            pause(Lang.t("reason.low_health"));
            return;
        }
        if (ABConfig.PAUSE_ON_INPUT.get() && userMoving()) {
            pause(Lang.t("reason.manual_move"));
            return;
        }
        if (Restocker.active()) {
            activity = Restocker.describe();
            Restocker.tick(MC);
            return;
        }
        if (!InventoryHelper.canSwap(p)) {
            activity = Lang.t("act.wait_container");
            WALKER.hold();
            return;
        }
        PENDING.entrySet().removeIf(e -> e.getValue() <= tick);
        BLACKLIST.entrySet().removeIf(e -> e.getValue() <= tick);

        if (AutoEat.tick(MC)) {
            activity = Lang.t("act.eating");
            WALKER.hold();
            return;
        }
        if (BREAKER.active()) {
            activity = Lang.t("act.break", BREAKER.pos().toShortString());
            BREAKER.tick(MC, reach());
            WALKER.hold();
            return;
        }
        if (WALKER.consumeFailed()) {
            goalFails++;
            BlockPos g = WALKER.goal();
            if (g != null && goalFails >= 2) {
                BLACKLIST.put(g, tick + 400);
                goalFails = 0;
            }
        }
        if (cooldown > 0) {
            cooldown--;
            WALKER.hold();
            return;
        }
        updateBarrier(p, level);
        if (doReachableWork(p, level)) {
            cooldown = ABConfig.ACTION_DELAY.get();
            noTargetTicks = 0;
            // 身边有活干就先别走，免得走出够不着的范围
            WALKER.hold();
            return;
        }
        if (WALKER.hasPath()) {
            activity = WALKER.describe();
            WALKER.tick(MC);
            return;
        }
        chooseTarget(p, level);
        WALKER.tick(MC);
    }

    private static boolean userMoving() {
        var o = MC.options;
        return MC.screen == null && (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
                || o.keyJump.isDown() || o.keyShift.isDown());
    }

    // ---------------- 身边能做的事 ----------------

    private static boolean doReachableWork(LocalPlayer p, ClientLevel level) {
        Vec3 eye = p.getEyePosition();
        double reach = reach();
        int r = (int) Math.ceil(reach) + 1;
        BlockPos c = BlockPos.containing(eye);
        boolean breakWrong = ABConfig.BREAK_WRONG.get();
        boolean clearAir = ABConfig.CLEAR_AIR.get();
        List<BlockPos> breaks = new ArrayList<>();
        List<BlockPos> places = new ArrayList<>();
        List<BlockPos> interacts = new ArrayList<>();
        List<BlockPos> fluids = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // 拆脚手架必须从上往下：只拆当前最高一层，为了够高处临时垫的柱子要等上面拆完
        int topY = phase == Phase.CLEANUP ? scaffoldTopY(level) : Integer.MAX_VALUE;
        java.util.Set<BlockPos> reserved = phase == Phase.CLEANUP ? WALKER.reserved() : java.util.Set.of();
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (Geometry.distSqToBlock(eye.x, eye.y, eye.z, m) > reach * reach) continue;
                    if (PENDING.containsKey(m) || BLACKLIST.containsKey(m)) continue;
                    BlockState world = level.getBlockState(m);
                    if (SCAFFOLD.contains(m)) {
                        if (phase == Phase.CLEANUP && ScaffoldItems.isScaffold(world) && m.getY() >= topY && !reserved.contains(m)) breaks.add(m.immutable());
                        continue;
                    }
                    BlockState target = placement.targetAt(m);
                    if (target == null) continue;
                    StateMatcher.Status st = StateMatcher.classify(target, world, breakWrong, clearAir);
                    if (st == StateMatcher.Status.BREAK) {
                        breaks.add(m.immutable());
                        continue;
                    }
                    if (st == StateMatcher.Status.FLUID || st == StateMatcher.Status.WATERLOG) {
                        if (fluidReady(m)) fluids.add(m.immutable());
                        continue;
                    }
                    if (st == StateMatcher.Status.INTERACT) {
                        interacts.add(m.immutable());
                        continue;
                    }
                    // 液体还没倒的那层以上先不建；同一段内按红石分层
                    if (st == StateMatcher.Status.PLACE && m.getY() <= fluidBarrierY
                            && StateMatcher.tier(target) <= allowedTier && observerReady(level, m, target)) places.add(m.immutable());
                }
            }
        }
        // 挖：从上往下；放：从下往上
        breaks.sort(Comparator.<BlockPos>comparingInt(b -> -b.getY()).thenComparingDouble(b -> Geometry.distSq(b, eye.x, eye.y, eye.z)));
        for (BlockPos b : breaks) {
            if (!safeToBreak(p, level, b)) continue;
            // 脚下踩着的脚手架最后挖：还有别的同层或更高的脚手架就先留着
            if (phase == Phase.CLEANUP && supportsPlayer(p, b) && otherScaffoldAtOrAbove(level, b)) continue;
            if (!countAttempt(b)) continue;
            if (debug) AutoBuilder.LOG.info("[AB] 挖 {} {}", b.toShortString(), level.getBlockState(b));
            BREAKER.start(b);
            BREAKER.tick(MC, reach);
            activity = Lang.t("act.break", b.toShortString());
            return true;
        }
        for (BlockPos b : interacts) {
            Placer.interact(MC, b);
            PENDING.put(b, tick + 6);
            activity = Lang.t("act.interact", b.toShortString());
            return true;
        }
        // 就绪的液体要趁上面还空着赶紧倒
        if (doFluidWork(p, level, eye, fluids)) return true;
        places.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(b -> Geometry.distSq(b, eye.x, eye.y, eye.z)));
        int done = 0;
        int sims = 0;
        int max = ABConfig.ACTIONS_PER_TICK.get();
        for (BlockPos b : places) {
            if (sims > 48) break;
            BlockState target = placement.targetAt(b);
            BlockState world = level.getBlockState(b);
            Item item = StateMatcher.itemFor(target);
            if (item == null) continue;
            if (InventoryHelper.count(p, item) == 0) {
                MISSING.merge(item, 1, (a, bb) -> a);
                continue;
            }
            sims++;
            Placer.PlaceAction a = Placer.plan(MC, b, target, world, item);
            if (a == null) continue;
            if (wouldSealIn(p, level, b, target)) continue;
            if (!InventoryHelper.selectItem(MC, item)) continue;
            Placer.Result res = Placer.execute(MC, a);
            if (res == Placer.Result.DEFERRED) {
                activity = Lang.t("act.turn", b.toShortString());
                return true; // 等服务端头部朝向跟上
            }
            if (res == Placer.Result.PLACED) {
                PENDING.put(b, tick + 8);
                countAttempt(b);
                MISSING.remove(item);
                done++;
                activity = Lang.t("act.place", b.toShortString());
                if (done >= max) return true;
            } else {
                PENDING.put(b, tick + 20);
            }
        }
        if (done > 0) return true;
        return false;
    }

    private static boolean doFluidWork(LocalPlayer p, ClientLevel level, Vec3 eye, List<BlockPos> fluids) {
        // 只在站稳时瞄准：空中看得见、落地看不见会导致原地反复起跳
        if (!p.onGround()) return false;
        // 液体：岩浆先、水后；从下往上
        fluids.sort(Comparator.<BlockPos>comparingInt(b -> placement.targetAt(b).is(net.minecraft.world.level.block.Blocks.LAVA) ? 0 : 1)
                .thenComparingInt(BlockPos::getY));
        for (BlockPos b : fluids) {
            BlockState target = placement.targetAt(b);
            boolean waterlog = !(target.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
            Item bucket = waterlog ? net.minecraft.world.item.Items.WATER_BUCKET : StateMatcher.bucketFor(target);
            if (bucket == null) continue;
            if (InventoryHelper.count(p, bucket) == 0) {
                MISSING.merge(bucket, 1, (a, bb) -> a);
                continue;
            }
            FluidPlacer.FluidAction a = FluidPlacer.plan(MC, eye, b, waterlog ? FluidPlacer.Kind.WATERLOG : FluidPlacer.Kind.SOURCE, bucket);
            if (a == null) continue;
            if (!InventoryHelper.selectItem(MC, bucket)) continue;
            Placer.Result res = FluidPlacer.execute(MC, a);
            if (res == Placer.Result.DEFERRED) {
                activity = Lang.t("act.fluid_wait");
                return true;
            }
            if (debug) AutoBuilder.LOG.info("[AB] 倒 {} {} r={}", b.toShortString(), bucket, res);
            PENDING.put(b, tick + (res == Placer.Result.PLACED ? 10 : 30));
            if (res == Placer.Result.PLACED) {
                countAttempt(b);
                MISSING.remove(bucket);
                activity = Lang.t(waterlog ? "act.waterlog" : "act.pour", b.toShortString());
                return true;
            }
        }
        // 需要水但空桶还有：附近有不属于蓝图的水源就顺手打满
        Scanner.Snapshot s = SCANNER.snapshot();
        boolean needWater = s != null && s.fluids.stream().anyMatch(b -> !fluidGivenUp(b)
                && !placement.targetAt(b).is(net.minecraft.world.level.block.Blocks.LAVA));
        if (needWater && tick >= refillReadyTick && InventoryHelper.count(p, net.minecraft.world.item.Items.BUCKET) > 0
                && InventoryHelper.count(p, net.minecraft.world.item.Items.WATER_BUCKET) < 4) {
            BlockPos src = nearestWaterSource(level, BlockPos.containing(eye), 5);
            if (src != null) {
                FluidPlacer.FluidAction a = FluidPlacer.plan(MC, eye, src, FluidPlacer.Kind.REFILL, net.minecraft.world.item.Items.BUCKET);
                if (a != null && InventoryHelper.selectItem(MC, net.minecraft.world.item.Items.BUCKET)) {
                    Placer.Result res = FluidPlacer.execute(MC, a);
                    if (res == Placer.Result.PLACED) refillReadyTick = tick + 10; // 等水源再生，免得把无限水抽干
                    if (res != Placer.Result.FAILED) {
                        activity = Lang.t("act.refill");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 侦测器盯着的方块一变就会发脉冲。所以侦测器要等它盯着的那格已经是最终状态再放，
     * 侦测器链按“被盯的先放”的顺序，放下时就不会互相触发。
     * 如果出现互相盯的死循环，observerLoose 打开后不再等。
     */
    private static boolean observerReady(ClientLevel level, BlockPos pos, BlockState target) {
        if (observerLoose || !(target.getBlock() instanceof net.minecraft.world.level.block.ObserverBlock)) return true;
        BlockPos w = pos.relative(target.getValue(net.minecraft.world.level.block.ObserverBlock.FACING));
        BlockState wt = placement.targetAt(w);
        if (wt == null || wt.isAir()) return true;
        StateMatcher.Status st = StateMatcher.classify(wt, level.getBlockState(w), true, false);
        return st == StateMatcher.Status.OK || st == StateMatcher.Status.UNSUPPORTED || st == StateMatcher.Status.IGNORE
                || st == StateMatcher.Status.WATERLOG;
    }

    private static boolean fluidGivenUp(BlockPos b) {
        return FLUID_FAILS.getOrDefault(b, 0) >= 3;
    }

    /** 根据快照算液体屏障和最低未完成方块高度（快照变了才重算） */
    private static void updateBarrier(LocalPlayer p, ClientLevel level) {
        Scanner.Snapshot s = SCANNER.snapshot();
        if (s == null || s == barrierFor) return;
        barrierFor = s;
        boolean haveWater = InventoryHelper.count(p, net.minecraft.world.item.Items.WATER_BUCKET) > 0
                || InventoryHelper.count(p, net.minecraft.world.item.Items.BUCKET) > 0;
        int fy = Integer.MAX_VALUE;
        for (BlockPos b : s.fluids) {
            if (fluidGivenUp(b)) continue;
            BlockState t = placement.targetAt(b);
            boolean water = !t.is(net.minecraft.world.level.block.Blocks.LAVA);
            boolean have = water ? haveWater : InventoryHelper.count(p, net.minecraft.world.item.Items.LAVA_BUCKET) > 0;
            if (!have) continue; // 缺桶的不挡后面的施工
            fy = Math.min(fy, b.getY());
        }
        fluidBarrierY = fy;
        int sy = Integer.MAX_VALUE;
        for (BlockPos b : s.place) {
            if (BLACKLIST.containsKey(b)) continue;
            BlockState t = placement.targetAt(b);
            Item it = StateMatcher.itemFor(t);
            if (it == null || InventoryHelper.count(p, it) == 0) continue;
            sy = Math.min(sy, b.getY());
        }
        minPendingSolidY = sy;
    }

    /** 液体任务现在能不能做：它这一层及以下的方块都放完了 */
    private static boolean fluidReady(BlockPos b) {
        return b.getY() < minPendingSolidY && !fluidGivenUp(b);
    }

    /** 取走后能自己再生的水源：至少挨着两格水平水源，下面是实心方块或水 */
    static boolean isInfiniteSource(ClientLevel level, BlockPos pos) {
        int n = 0;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var f = level.getFluidState(pos.relative(d));
            if (f.isSource() && f.is(net.minecraft.tags.FluidTags.WATER)) n++;
        }
        if (n < 2) return false;
        BlockPos below = pos.below();
        var bf = level.getFluidState(below);
        return (bf.isSource() && bf.is(net.minecraft.tags.FluidTags.WATER)) || level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }

    /** 不属于蓝图的水源方块（湖、自己挖的无限水） */
    static BlockPos nearestWaterSource(ClientLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -radius; dy <= radius; dy++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dx = -radius; dx <= radius; dx++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState st = level.getBlockState(m);
                    if (!(st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) || !st.getFluidState().isSource()
                            || !st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
                    if (placement != null && placement.targetAt(m) != null) continue;
                    if (!isInfiniteSource(level, m)) continue;
                    double d = m.distSqr(center);
                    if (d < bestD) {
                        bestD = d;
                        best = m.immutable();
                    }
                }
        return best;
    }

    /** 现存（且没被拉黑）脚手架的最高 y */
    private static int scaffoldTopY(ClientLevel level) {
        int top = Integer.MIN_VALUE;
        for (BlockPos b : SCAFFOLD) {
            if (BLACKLIST.containsKey(b) || !ScaffoldItems.isScaffold(level.getBlockState(b))) continue;
            top = Math.max(top, b.getY());
        }
        return top;
    }

    private static boolean otherScaffoldAtOrAbove(ClientLevel level, BlockPos b) {
        for (BlockPos o : SCAFFOLD) {
            if (o.equals(b) || o.getY() < b.getY() || BLACKLIST.containsKey(o)) continue;
            if (ScaffoldItems.isScaffold(level.getBlockState(o))) return true;
        }
        return false;
    }

    private static boolean supportsPlayer(LocalPlayer p, BlockPos b) {
        AABB box = p.getBoundingBox();
        return new AABB(box.minX, box.minY - 0.2, box.minZ, box.maxX, box.minY, box.maxZ).intersects(new AABB(b));
    }

    /** 记一次对 pos 的操作；次数太多说明服务端一直不认，先跳过一分钟 */
    private static boolean countAttempt(BlockPos pos) {
        int n = ATTEMPTS.merge(pos.immutable(), 1, Integer::sum);
        if (n > 6) {
            ATTEMPTS.remove(pos);
            BLACKLIST.put(pos.immutable(), tick + 1200);
            if (!warnedRejected) {
                warnedRejected = true;
                msg(Lang.t("msg.skip_rejected", pos.toShortString()), ChatFormatting.YELLOW);
            }
            return false;
        }
        return true;
    }

    /**
     * 放了 b 之后，人还能不能走到建筑外面？不能就记下封闭区，返回 true（先别放）。
     * 只在人站在建筑范围里时才算（在外面不可能被关住）。
     */
    private static boolean wouldSealIn(LocalPlayer p, ClientLevel level, BlockPos b, BlockState target) {
        Integer st0 = SEAL_TICK.get(b);
        if (st0 != null && tick - st0 > 300) {
            SEALS.remove(b); // 过一阵子重算，期间世界可能变了
            SEAL_TICK.remove(b);
        }
        Set<Long> known = SEALS.get(b);
        BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
        if (known != null) return known.contains(feet.asLong());
        AABB area = placement.box().inflate(1);
        if (!area.contains(p.position())) return false;
        // 放上去之后没有碰撞的方块（花、地毯以外的薄东西）不会挡路
        if (target.getCollisionShape(level, b).isEmpty()) return false;
        AABB bound = placement.box().inflate(3);
        Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        q.add(feet);
        seen.add(feet.asLong());
        while (!q.isEmpty()) {
            BlockPos c = q.poll();
            if (!bound.contains(Vec3.atCenterOf(c)) || seen.size() > 6000) return false; // 通到外面了
            for (var d : net.minecraft.core.Direction.values()) {
                BlockPos n = c.relative(d);
                if (n.equals(b) || seen.contains(n.asLong())) continue;
                BlockState ns = level.getBlockState(n);
                if (!ns.getCollisionShape(level, n).isEmpty() && !ns.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
                if (ns.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                seen.add(n.asLong());
                q.add(n);
            }
        }
        SEALS.put(b.immutable(), seen);
        SEAL_TICK.put(b.immutable(), tick);
        if (debug) AutoBuilder.LOG.info("[AB] 封口 {} 封闭区 {} 格，先出去", b.toShortString(), seen.size());
        activity = Lang.t("act.leave_first", b.toShortString());
        return true;
    }

    /** 方块 b 下面有几格是空的（挖掉 b 后站在上面的人会掉几格） */
    static int dropBelow(ClientLevel level, BlockPos b) {
        int fall = 0;
        BlockPos q = b.below();
        while (fall <= 5 && StateMatcher.isSoft(level.getBlockState(q))) {
            fall++;
            q = q.below();
        }
        return fall;
    }

    /** 挖了不会出事：旁边没有岩浆，脚下的方块挖了不会摔太高 */
    private static boolean safeToBreak(LocalPlayer p, ClientLevel level, BlockPos b) {
        for (var d : net.minecraft.core.Direction.values()) {
            if (level.getFluidState(b.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return false;
        }
        AABB box = p.getBoundingBox();
        AABB below = new AABB(box.minX, box.minY - 0.2, box.minZ, box.maxX, box.minY, box.maxZ);
        if (below.intersects(new AABB(b))) {
            int fall = dropBelow(level, b);
            if (phase == Phase.BUILD && fall > 0) return false;
            if (fall > ABConfig.MAX_FALL.get()) return false;
        }
        return true;
    }

    // ---------------- 选下一个目标并寻路 ----------------

    private enum Kind { BREAK, PLACE, INTERACT, FLUID, REFILL }

    private record Target(BlockPos pos, Kind kind, Item item, FluidPlacer.Kind fluidKind) {
        Target(BlockPos pos, Kind kind) {
            this(pos, kind, null, null);
        }
    }

    private static void chooseTarget(LocalPlayer p, ClientLevel level) {
        Scanner.Snapshot s = SCANNER.snapshot();
        if (s == null) {
            activity = Lang.t("act.scanning");
            return;
        }
        if (tryPickup(p, level, s)) return;
        Vec3 pos = p.position();
        List<Target> cands = new ArrayList<>();
        boolean breakWrong = ABConfig.BREAK_WRONG.get();
        boolean clearAir = ABConfig.CLEAR_AIR.get();
        MISSING.clear();
        if (phase == Phase.BUILD) {
            List<BlockPos> br = new ArrayList<>();
            for (BlockPos b : s.breaks) {
                if (SCAFFOLD.contains(b) || BLACKLIST.containsKey(b)) continue;
                if (StateMatcher.classify(placement.targetAt(b), level.getBlockState(b), breakWrong, clearAir) == StateMatcher.Status.BREAK) br.add(b);
            }
            br.sort(Comparator.<BlockPos>comparingInt(b -> -b.getY()).thenComparingDouble(b -> b.distToCenterSqr(pos)));
            for (int i = 0; i < Math.min(br.size(), 6); i++) cands.add(new Target(br.get(i), Kind.BREAK));
            // 就绪的液体优先（上面还空着、看得见的时候倒）
            collectFluids(p, level, s, cands, pos);
            // 按层找目标：低层（普通方块）做完了才轮到红石、侦测器、TNT
            for (int t = 0; t <= 4 && cands.isEmpty(); t++) {
                collectPlaces(p, level, s, t, cands, pos);
                if (cands.isEmpty()) {
                    for (BlockPos b : s.interact) {
                        if (BLACKLIST.containsKey(b)) continue;
                        BlockState tg = placement.targetAt(b);
                        if (StateMatcher.tier(tg) != t) continue;
                        cands.add(new Target(b, Kind.INTERACT));
                        if (cands.size() >= 4) break;
                    }
                }
                if (!cands.isEmpty()) allowedTier = t;
            }
            if (cands.isEmpty()) {
                noTargetTicks++;
                if (noTargetTicks < 25) {
                    activity = Lang.t("act.checking");
                    return; // 等扫描器刷新一轮，避免刚放下的方块还没反映出来
                }
                if (!MISSING.isEmpty()) {
                    if (Restocker.start(MC)) {
                        activity = Restocker.describe();
                        return;
                    }
                    pause(Lang.t(SupplyManager.any() ? "reason.missing_supply" : "reason.missing", missingText(8)));
                    return;
                }
                // 只等还有活要干的黑名单位置；捡不到的掉落物也在黑名单里，但不该挡住收尾
                boolean blockedWork = BLACKLIST.keySet().stream().anyMatch(b -> s.place.contains(b) || s.breaks.contains(b)
                        || s.interact.contains(b) || s.fluids.contains(b));
                if (blockedWork) {
                    activity = Lang.t("act.unreachable");
                    if (noTargetTicks > 25 + 400) {
                        BLACKLIST.clear();
                        noTargetTicks = 0;
                    }
                    return;
                }
                if (!observerLoose && !s.place.isEmpty()) {
                    observerLoose = true; // 可能是侦测器互相等待，放宽后再试
                    noTargetTicks = 0;
                    return;
                }
                if (!s.place.isEmpty() || !s.fluids.isEmpty()) {
                    activity = Lang.t("act.stuck_place");
                    if (noTargetTicks > 200) finishBuild(s);
                    return;
                }
                finishBuild(s);
                return;
            }
        } else {
            List<BlockPos> sc = new ArrayList<>();
            for (BlockPos b : SCAFFOLD) {
                if (BLACKLIST.containsKey(b)) continue;
                if (ScaffoldItems.isScaffold(level.getBlockState(b))) sc.add(b);
            }
            if (sc.isEmpty()) {
                SCAFFOLD.removeIf(b -> !ScaffoldItems.isScaffold(level.getBlockState(b)));
                if (!SCAFFOLD.isEmpty() && cleanupRetries < 3) {
                    // 剩下的都在黑名单里（暂时够不着），清空黑名单再试几轮
                    cleanupRetries++;
                    BLACKLIST.clear();
                    activity = Lang.t("act.retry_scaffold");
                    return;
                }
                finish(SCAFFOLD.isEmpty() ? Lang.t("msg.done_clean") : Lang.t("msg.done_left", SCAFFOLD.size()));
                return;
            }
            if (++cleanupRounds > 400) {
                finish(Lang.t("msg.done_timeout", sc.size()));
                return;
            }
            sc.sort(Comparator.<BlockPos>comparingInt(b -> -b.getY()).thenComparingDouble(b -> b.distToCenterSqr(pos)));
            int top = sc.get(0).getY();
            for (BlockPos b : sc) {
                if (b.getY() < top || cands.size() >= 6) break;
                cands.add(new Target(b, Kind.BREAK));
            }
        }
        noTargetTicks = 0;
        // 依次尝试寻路，一 tick 最多找 3 次
        int tries = 0;
        for (Target t : cands) {
            if (tries++ >= 3) break;
            List<PathFinder.Step> path = findPathTo(p, level, t);
            if (path == null) {
                BLACKLIST.put(t.pos, tick + 300);
                if (t.kind == Kind.FLUID && FLUID_FAILS.merge(t.pos, 1, Integer::sum) >= 3) {
                    msg(Lang.t("msg.fluid_unreachable", t.pos.toShortString()), ChatFormatting.YELLOW);
                    barrierFor = null;
                }
                continue;
            }
            if (path.isEmpty()) {
                // 已经在能够到的位置却做不了（被自己挡着之类），暂时跳过
                BLACKLIST.put(t.pos, tick + 60);
                continue;
            }
            WALKER.setPath(path, t.pos);
            activity = Lang.t("act.goto", t.pos.toShortString());
            return;
        }
        activity = Lang.t("act.no_path");
    }

    private static void collectPlaces(LocalPlayer p, ClientLevel level, Scanner.Snapshot s, int tier, List<Target> cands, Vec3 pos) {
        boolean breakWrong = ABConfig.BREAK_WRONG.get();
        boolean clearAir = ABConfig.CLEAR_AIR.get();
        List<BlockPos> pl = new ArrayList<>();
        int minY = Integer.MAX_VALUE;
        for (BlockPos b : s.place) {
            if (BLACKLIST.containsKey(b)) continue;
            BlockState t = placement.targetAt(b);
            if (StateMatcher.tier(t) != tier || b.getY() > fluidBarrierY || !observerReady(level, b, t)) continue;
            BlockState w = level.getBlockState(b);
            if (StateMatcher.classify(t, w, breakWrong, clearAir) != StateMatcher.Status.PLACE) continue;
            Item it = StateMatcher.itemFor(t);
            if (it == null) continue;
            if (InventoryHelper.count(p, it) == 0) {
                MISSING.merge(it, StateMatcher.itemsNeeded(t, w), Integer::sum);
                continue;
            }
            pl.add(b);
            minY = Math.min(minY, b.getY());
        }
        final int lowY = minY;
        List<BlockPos> low = new ArrayList<>();
        for (BlockPos b : pl) if (b.getY() <= lowY + 1) low.add(b);
        low.sort(Comparator.comparingDouble(b -> b.distToCenterSqr(pos)));
        int checked = 0;
        for (BlockPos b : low) {
            if (checked++ > 40 || cands.size() >= 6) break;
            BlockState t = placement.targetAt(b);
            if (Placer.plan(MC, b, t, level.getBlockState(b), StateMatcher.itemFor(t)) == null) continue;
            cands.add(new Target(b, Kind.PLACE));
        }
        // 有封口方块时优先处理：先走出封闭区
        cands.sort(Comparator.comparingInt(tg -> SEALS.containsKey(tg.pos()) ? 0 : 1));
        // 最低一层暂时都放不了（比如等支撑），再看看高一点的
        if (cands.isEmpty()) {
            pl.sort(Comparator.comparingDouble(b -> b.distToCenterSqr(pos)));
            checked = 0;
            for (BlockPos b : pl) {
                if (checked++ > 60 || cands.size() >= 6) break;
                BlockState t = placement.targetAt(b);
                if (Placer.plan(MC, b, t, level.getBlockState(b), StateMatcher.itemFor(t)) == null) continue;
                cands.add(new Target(b, Kind.PLACE));
            }
        }
    }

    private static void collectFluids(LocalPlayer p, ClientLevel level, Scanner.Snapshot s, List<Target> cands, Vec3 pos) {
        boolean breakWrong = ABConfig.BREAK_WRONG.get();
        boolean clearAir = ABConfig.CLEAR_AIR.get();
        boolean needRefill = false;
        List<BlockPos> list = new ArrayList<>(s.fluids);
        list.sort(Comparator.<BlockPos>comparingInt(b -> placement.targetAt(b).is(net.minecraft.world.level.block.Blocks.LAVA) ? 0 : 1)
                .thenComparingInt(BlockPos::getY).thenComparingDouble(b -> b.distToCenterSqr(pos)));
        for (BlockPos b : list) {
            if (BLACKLIST.containsKey(b) || cands.size() >= 4 || !fluidReady(b)) continue;
            BlockState t = placement.targetAt(b);
            StateMatcher.Status st = StateMatcher.classify(t, level.getBlockState(b), breakWrong, clearAir);
            if (st != StateMatcher.Status.FLUID && st != StateMatcher.Status.WATERLOG) continue;
            boolean waterlog = st == StateMatcher.Status.WATERLOG;
            Item bucket = waterlog ? net.minecraft.world.item.Items.WATER_BUCKET : StateMatcher.bucketFor(t);
            if (InventoryHelper.count(p, bucket) > 0) {
                cands.add(new Target(b, Kind.FLUID, bucket, waterlog ? FluidPlacer.Kind.WATERLOG : FluidPlacer.Kind.SOURCE));
            } else if (bucket == net.minecraft.world.item.Items.WATER_BUCKET && InventoryHelper.count(p, net.minecraft.world.item.Items.BUCKET) > 0) {
                needRefill = true;
            } else {
                MISSING.merge(bucket, 1, Integer::sum);
            }
        }
        if (cands.isEmpty() && needRefill) {
            BlockPos src = nearestWaterSource(level, p.blockPosition(), 24);
            if (src != null) {
                cands.add(new Target(src, Kind.REFILL, net.minecraft.world.item.Items.BUCKET, FluidPlacer.Kind.REFILL));
            } else {
                MISSING.merge(net.minecraft.world.item.Items.WATER_BUCKET, 1, Integer::sum);
            }
        }
    }

    /** 附近地上有蓝图要用的物品或脚手架方块：先走过去捡起来 */
    private static boolean tryPickup(LocalPlayer p, ClientLevel level, Scanner.Snapshot s) {
        if (tick < pickupCooldownUntil) return false;
        AABB area = placement.box().inflate(10);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area, e -> {
            if (!e.isAlive() || e.hasPickUpDelay() && e.tickCount < 30) return false;
            if (BLACKLIST.containsKey(e.blockPosition())) return false;
            Item it = e.getItem().getItem();
            return s.need.containsKey(it) || ScaffoldItems.items().contains(it);
        });
        if (items.isEmpty()) return false;
        if (p.getInventory().getFreeSlot() < 0) {
            // 背包满了就不捡（同类物品能叠加的话原版也会自动捡）
            boolean stackable = false;
            for (ItemEntity e : items) {
                for (int i = 0; i < 36 && !stackable; i++) {
                    ItemStack st = p.getInventory().getItem(i);
                    if (ItemStack.isSameItemSameComponents(st, e.getItem()) && st.getCount() < st.getMaxStackSize()) stackable = true;
                }
            }
            if (!stackable) return false;
        }
        items.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        int tries = 0;
        for (ItemEntity e : items) {
            if (tries++ >= 2) break;
            Vec3 ip = e.position();
            if (p.getBoundingBox().inflate(1.0, 0.5, 1.0).contains(ip)) continue; // 下一 tick 就会自动捡到
            PathFinder.Goal goal = new PathFinder.Goal() {
                @Override
                public boolean reached(int x, int y, int z, double standY) {
                    return Math.abs(x + 0.5 - ip.x) < 1.2 && Math.abs(z + 0.5 - ip.z) < 1.2 && ip.y - standY > -0.5 && ip.y - standY < 1.5;
                }

                @Override
                public double heuristic(int x, int y, int z, double standY) {
                    double dx = x + 0.5 - ip.x, dy = standY - ip.y, dz = z + 0.5 - ip.z;
                    return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 1.2);
                }
            };
            List<PathFinder.Step> path = runFinder(p, level, goal);
            if (path == null || path.isEmpty()) {
                BLACKLIST.put(e.blockPosition(), tick + 600);
                continue;
            }
            WALKER.setPath(path, e.blockPosition());
            activity = Lang.t("act.pickup", e.getItem().getHoverName().getString());
            return true;
        }
        pickupCooldownUntil = tick + 40;
        return false;
    }

    private static void finishBuild(Scanner.Snapshot s) {
        String extra = "";
        if (s.unsupported > 0) extra += Lang.t("msg.extra_unsupported", s.unsupported);
        if (!s.wrong.isEmpty()) extra += Lang.t("msg.extra_wrong", s.wrong.size());
        if (!s.place.isEmpty()) extra += Lang.t("msg.extra_unfinished", s.place.size());
        if (!s.fluids.isEmpty()) extra += Lang.t("msg.extra_fluid", s.fluids.size());
        if (ABConfig.REMOVE_SCAFFOLD.get() && !SCAFFOLD.isEmpty()) {
            phase = Phase.CLEANUP;
            BLACKLIST.clear();
            msg(Lang.t("msg.main_done", extra, SCAFFOLD.size()), ChatFormatting.AQUA);
            return;
        }
        finish(Lang.t("msg.done_extra", extra));
    }

    private static List<PathFinder.Step> findPathTo(LocalPlayer p, ClientLevel level, Target t) {
        double reach = reach() - 0.35;
        BlockPos tp = t.pos;
        boolean isBreak = t.kind == Kind.BREAK;
        int maxFall = ABConfig.MAX_FALL.get();
        if (t.fluidKind != null) {
            PathFinder.Goal fg = new PathFinder.Goal() {
                @Override
                public boolean reached(int x, int y, int z, double standY) {
                    Vec3 eye = new Vec3(x + 0.5, standY + p.getEyeHeight(), z + 0.5);
                    if (eye.distanceTo(Vec3.atCenterOf(tp)) > 5.0) return false;
                    if (tp.getX() == x && tp.getZ() == z && tp.getY() >= Math.floor(standY) - 1 && tp.getY() <= standY + 1.8) return false;
                    return FluidPlacer.plan(MC, eye, tp, t.fluidKind, t.item) != null;
                }

                @Override
                public double heuristic(int x, int y, int z, double standY) {
                    double d = Math.sqrt(Geometry.distSq(tp, x + 0.5, standY + 1.62, z + 0.5));
                    return Math.max(0, d - 4.0);
                }
            };
            return runFinder(p, level, fg);
        }
        PathFinder.Goal goal = new PathFinder.Goal() {
            @Override
            public boolean reached(int x, int y, int z, double standY) {
                double ex = x + 0.5, ey = standY + 1.62, ez = z + 0.5;
                if (Geometry.distSqToBlock(ex, ey, ez, tp) > reach * reach) return false;
                // 不能站在要放方块的格子里，也不能站在要挖的方块上
                if (tp.getX() == x && tp.getZ() == z) {
                    int y0 = (int) Math.floor(standY + 0.01), y1 = (int) Math.floor(standY + 1.79);
                    if (tp.getY() >= y0 && tp.getY() <= y1) return false;
                    if (tp.getY() == y0 - 1) {
                        // 站在要挖的方块上：只有拆脚手架、而且挖掉后掉得不高时才行
                        if (!isBreak || phase == Phase.BUILD || dropBelow(level, tp) > maxFall) return false;
                    }
                }
                // 封口方块：要站在封闭区外面放
                Set<Long> seal = SEALS.get(tp);
                if (seal != null && seal.contains(BlockPos.asLong(x, (int) Math.floor(standY + 0.2), z))) return false;
                return true;
            }

            @Override
            public double heuristic(int x, int y, int z, double standY) {
                double d = Math.sqrt(Geometry.distSq(tp, x + 0.5, standY + 1.62, z + 0.5));
                return Math.max(0, d - reach);
            }
        };
        return runFinder(p, level, goal);
    }

    /** 走到能够着 pos 的地方（给补货用） */
    public static List<PathFinder.Step> pathToReach(BlockPos pos) {
        return findPathTo(MC.player, MC.level, new Target(pos, Kind.INTERACT));
    }

    private static List<PathFinder.Step> runFinder(LocalPlayer p, ClientLevel level, PathFinder.Goal goal) {
        PathFinder.Ctx ctx = new PathFinder.Ctx(level, placement, SCAFFOLD, ABConfig.ALLOW_SCAFFOLD.get(),
                ABConfig.ALLOW_DIG.get(), ABConfig.MAX_FALL.get(), ScaffoldItems.available(p, placement, SCANNER.snapshot()),
                st -> {
                    Item it = StateMatcher.itemFor(st);
                    return it != null && InventoryHelper.count(p, it) > 0;
                });
        BlockPos start = BlockPos.containing(p.getX(), p.getY() + 0.01, p.getZ());
        double stand = PathFinder.stand(ctx, start.getX(), start.getY(), start.getZ());
        if (Double.isNaN(stand)) {
            // 站在方块边缘：看看脚下覆盖到的其它格子
            AABB bb = p.getBoundingBox();
            outer:
            for (double ox : new double[] {bb.minX + 0.01, bb.maxX - 0.01}) {
                for (double oz : new double[] {bb.minZ + 0.01, bb.maxZ - 0.01}) {
                    BlockPos q = BlockPos.containing(ox, p.getY() + 0.01, oz);
                    double h = PathFinder.stand(ctx, q.getX(), q.getY(), q.getZ());
                    if (!Double.isNaN(h)) {
                        start = q;
                        stand = h;
                        break outer;
                    }
                }
            }
            if (Double.isNaN(stand)) stand = p.getY();
        }
        return PathFinder.find(ctx, start, stand, goal, 30000, 40_000_000L);
    }

    // ---------------- 寻路时垫方块 ----------------

    /** 在 pos 垫一个方块：蓝图本来要整方块就放蓝图的，否则放脚手架。返回 true 表示已发出放置 */
    public static boolean placeSupport(BlockPos pos) {
        LocalPlayer p = MC.player;
        if (PENDING.containsKey(pos)) return true;
        BlockState target = placement == null ? null : placement.targetAt(pos);
        BlockState world = MC.level.getBlockState(pos);
        if (target != null && !target.isAir()) {
            Item it = StateMatcher.itemFor(target);
            if (it != null && InventoryHelper.count(p, it) > 0) {
                Placer.PlaceAction a = Placer.plan(MC, pos, target, world, it);
                if (a != null && InventoryHelper.selectItem(MC, it)) {
                    Placer.Result res = Placer.execute(MC, a);
                    if (res == Placer.Result.PLACED) PENDING.put(pos, tick + 8);
                    if (res != Placer.Result.FAILED) return true;
                }
            }
            return false;
        }
        Item sc = ScaffoldItems.pick(p, placement, SCANNER.snapshot());
        if (sc == null) {
            pause(Lang.t("reason.no_scaffold"));
            return false;
        }
        Placer.PlaceAction a = Placer.planAny(MC, pos, sc);
        if (a == null) return false;
        if (!InventoryHelper.selectItem(MC, sc)) return false;
        Placer.Result res = Placer.execute(MC, a);
        if (res == Placer.Result.PLACED) {
            SCAFFOLD.add(pos.immutable());
            PENDING.put(pos, tick + 8);
        }
        return res != Placer.Result.FAILED;
    }

    // ---------------- 材料 ----------------

    public static String missingText(int limit) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<Item, Integer> e : MISSING.entrySet()) {
            if (i++ >= limit) {
                sb.append(Lang.t("misc.etc"));
                break;
            }
            if (sb.length() > 0) sb.append("、");
            sb.append(new ItemStack(e.getKey()).getHoverName().getString()).append("×").append(e.getValue());
        }
        return sb.toString();
    }

    /** 材料清单：每种物品还需要多少、背包里有多少 */
    public static List<String> materialLines() {
        List<String> out = new ArrayList<>();
        Scanner.Snapshot s = SCANNER.snapshot();
        if (s == null || MC.player == null) return out;
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(s.need.entrySet());
        list.sort(Comparator.comparing(e -> -e.getValue()));
        for (Map.Entry<Item, Integer> e : list) {
            int have = InventoryHelper.count(MC.player, e.getKey());
            int lack = Math.max(0, e.getValue() - have);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(e.getKey());
            String name = new ItemStack(e.getKey()).getHoverName().getString();
            out.add((lack > 0 ? "§c" : "§a") + name + " §7(" + id.getPath() + ")§r " + Lang.t("msg.material_line", e.getValue(), have)
                    + (lack > 0 ? Lang.t("msg.material_lack", lack) : ""));
        }
        return out;
    }
}
