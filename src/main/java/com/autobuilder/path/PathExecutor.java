package com.autobuilder.path;

import com.autobuilder.Lang;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Geometry;
import com.autobuilder.build.StateMatcher;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 沿路径行走。不转镜头：根据当前视角把“想往哪个方向走”换算成前后/左右按键量，
 * 在 MovementInputUpdateEvent 里覆盖玩家输入。
 */
public final class PathExecutor {
    private List<PathFinder.Step> path;
    private int idx;
    private int stepTicks;
    private int stuckTicks;
    private Vec3 lastPos = Vec3.ZERO;
    private BlockPos goal;
    private boolean failed;
    private int invalidations;
    /** 开始这条路时脚下的格子（第一步的起点） */
    private BlockPos startPos;
    /** 上一次失败卡在哪一步（PathFinder.moveKey），0 表示没有 */
    private long failedMove;

    // 本 tick 要施加的输入
    private boolean control;
    private float forward, strafe;
    private boolean jump;
    /** 这一步走到现在离节点最近的“还差多少”，长时间没变小就是原地打转（比如原地反复跳） */
    private double bestProg = Double.MAX_VALUE;
    private int walkTicks, progTick;
    /** 平路上冲刺 */
    private boolean sprint;

    public void setPath(List<PathFinder.Step> p, BlockPos goal) {
        this.path = p;
        this.goal = goal;
        var pl = net.minecraft.client.Minecraft.getInstance().player;
        this.startPos = pl == null ? null : BlockPos.containing(pl.getX(), pl.getY() + 0.01, pl.getZ());
        this.idx = 0;
        this.stepTicks = 0;
        this.stuckTicks = 0;
        this.failed = false;
        this.bestProg = Double.MAX_VALUE;
        this.walkTicks = 0;
        this.progTick = 0;
    }

    /** 剩下还没走的步骤（诊断日志用） */
    public List<PathFinder.Step> remainingSteps() {
        if (path == null || idx >= path.size()) return List.of();
        return List.copyOf(path.subList(idx, path.size()));
    }

    public boolean hasPath() {
        return path != null && idx < path.size();
    }

    /** 当前路线还要用到的方块：要垫的、以及每个落脚点脚下的（拆脚手架时不能动） */
    public java.util.Set<BlockPos> reserved() {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        if (!hasPath()) return out;
        for (int i = Math.max(0, idx - 1); i < path.size(); i++) {
            PathFinder.Step s = path.get(i);
            if (s.place() != null) out.add(s.place());
            out.add(BlockPos.containing(s.pos().getX(), s.standY() - 0.01, s.pos().getZ()));
        }
        return out;
    }

    /**
     * 当前路线接下来要穿过的格子：要挖开的，和每个落脚点人身占的两格。
     * 建造时不能往这些格子里放方块：挖开的建筑方块马上又被填回去，路又被堵上，会一直挖了填、填了挖。
     */
    public java.util.Set<BlockPos> passage() {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        if (!hasPath()) return out;
        for (int i = idx; i < path.size(); i++) {
            PathFinder.Step s = path.get(i);
            if (s.mine() != null) out.addAll(s.mine());
            int y0 = (int) Math.floor(s.standY() + 0.01), y1 = (int) Math.floor(s.standY() + 1.79);
            for (int y = y0; y <= y1; y++) out.add(new BlockPos(s.pos().getX(), y, s.pos().getZ()));
        }
        return out;
    }

    public BlockPos goal() {
        return goal;
    }

    /** 上一次失败的路段（读取后清零） */
    public long failedMove() {
        long m = failedMove;
        failedMove = 0;
        return m;
    }

    /** 当前这一步的起点：上一个落脚点，第一步就是出发的位置 */
    private BlockPos stepFrom() {
        return idx > 0 ? path.get(idx - 1).pos() : startPos;
    }

    /** 上一条路径是不是因为卡住/走不通而放弃的（读取后清零） */
    public boolean consumeFailed() {
        boolean f = failed;
        failed = false;
        return f;
    }

    public void clear() {
        path = null;
        goal = null;
        control = false;
    }

    /** 这一 tick 站着别动（但仍然接管输入，防止惯性乱走） */
    public void hold() {
        control = true;
        forward = strafe = 0;
        jump = false;
        sprint = false;
    }

    public void release() {
        control = false;
    }

    public String describe() {
        if (!hasPath()) return "";
        return Lang.t("act.path", idx + 1, path.size(), path.get(idx).move());
    }

    private void fail(String why) {
        if (path != null && idx < path.size()) {
            PathFinder.Step s = path.get(idx);
            BlockPos from = stepFrom();
            if (from != null) failedMove = PathFinder.moveKey(from, s.pos());
            var p = net.minecraft.client.Minecraft.getInstance().player;
            com.autobuilder.Diag.log("走不通（{}）第 {}/{} 步 {} {}→{} 人在 {}{}", why, idx + 1, path.size(), s.move(),
                    from == null ? "?" : from.toShortString(), s.pos().toShortString(),
                    p == null ? "?" : String.format("%.2f,%.2f,%.2f", p.getX(), p.getY(), p.getZ()),
                    p == null ? "" : (p.onGround() ? " 着地" : " 悬空") + (p.isInWater() ? " 在水里" : ""));
        }
        path = null;
        failed = true;
    }

    private void advance() {
        idx++;
        stepTicks = 0;
        stuckTicks = 0;
        bestProg = Double.MAX_VALUE;
        walkTicks = 0;
        progTick = 0;
    }

    /** 路线上接下来几个节点是否还走得通（可能刚被自己放的方块堵住了） */
    private boolean stillValid(Minecraft mc) {
        for (int i = idx; i < Math.min(path.size(), idx + 3); i++) {
            PathFinder.Step s = path.get(i);
            if (s.move() == PathFinder.Move.MINE || s.move() == PathFinder.Move.DIG_DOWN) return true;
            // 用玩家碰撞箱精确判断（站在半砖、楼梯上时脚下那格本身有碰撞，不能算挡路）
            BlockPos f = s.pos();
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(f.getX() + 0.2, s.standY() + 0.05, f.getZ() + 0.2,
                    f.getX() + 0.8, s.standY() + 1.75, f.getZ() + 0.8);
            if (mc.level.getBlockCollisions(null, box).iterator().hasNext()) return false;
        }
        return true;
    }

    public void tick(Minecraft mc) {
        hold();
        if (!hasPath()) return;
        LocalPlayer p = mc.player;
        if (!stillValid(mc)) {
            // 路被堵了：重新找路；连续好几次刚找好就被判无效，按失败处理，避免原地死循环
            path = null;
            if (++invalidations >= 3) {
                invalidations = 0;
                failed = true;
            }
            return;
        }
        if (stepTicks == 0 && idx > 0) invalidations = 0;
        PathFinder.Step s = path.get(idx);
        stepTicks++;
        if (stepTicks > 200) {
            fail("超时");
            return;
        }
        // ---- 走之前要先完成的动作 ----
        if (s.mine() != null) {
            for (BlockPos m : s.mine()) {
                if (!StateMatcher.isSoft(mc.level.getBlockState(m))) {
                    if (Geometry.distToBlock(p.getEyePosition(), m) > BuildController.reach() + 0.3) {
                        // 还没走到能挖的位置，先往节点方向靠
                        break;
                    }
                    if (!BuildController.breaker().active()) BuildController.pathDig(m);
                    stepTicks = Math.min(stepTicks, 150);
                    return;
                }
            }
        }
        if ((s.move() == PathFinder.Move.BRIDGE || s.move() == PathFinder.Move.STEP_DOWN)
                && s.place() != null && StateMatcher.isSoft(mc.level.getBlockState(s.place()))) {
            if (!BuildController.placeSupport(s.place())) {
                if (stepTicks > 20) fail("垫不上方块");
            }
            return;
        }
        if (s.move() == PathFinder.Move.PILLAR && s.place() != null) {
            BlockPos pl = s.place();
            if (StateMatcher.isSoft(mc.level.getBlockState(pl))) {
                double cx = pl.getX() + 0.5 - p.getX(), cz = pl.getZ() + 0.5 - p.getZ();
                // 人根本不在柱子那格附近（掉下去了、被推开了）：别耗着，马上重新找路
                if (p.onGround() && (cx * cx + cz * cz > 1.5 * 1.5 || p.getY() < pl.getY() - 0.5)) {
                    fail("不在柱子位置");
                    return;
                }
                if (cx * cx + cz * cz > 0.04 && p.onGround()) {
                    moveToward(p, cx, cz, 0.5f);
                    if (p.position().distanceToSqr(lastPos) < 0.0004) {
                        if (++stuckTicks > 20) fail("走不到柱子位置");
                    } else {
                        stuckTicks = 0;
                    }
                    lastPos = p.position();
                    return;
                }
                if (p.onGround()) {
                    jump = true;
                    return;
                }
                if (p.getY() >= pl.getY() + 1.0) {
                    if (!BuildController.placeSupport(pl) && stepTicks > 40) fail("柱子垫不上");
                }
                return;
            }
            if (p.onGround() && Math.abs(p.getY() - s.standY()) < 0.3) {
                advance();
            }
            return;
        }
        // ---- 走向节点 ----
        double tx = s.pos().getX() + 0.5, tz = s.pos().getZ() + 0.5;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean last = idx == path.size() - 1;
        // 终点站准一点：倒液体时的视线是按格子中心算的
        double tol = last ? 0.15 : 0.4;
        if (dist < tol && Math.abs(p.getY() - s.standY()) < 0.55 && p.onGround()) {
            advance();
            if (!hasPath()) {
                hold();
                return;
            }
            s = path.get(idx);
            tx = s.pos().getX() + 0.5;
            tz = s.pos().getZ() + 0.5;
            dx = tx - p.getX();
            dz = tz - p.getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        // 偏离路线太远（被推开、掉下去）就重新找路
        if (dist > 3.5 || p.getY() < s.standY() - 4 || p.onGround() && p.getY() < s.standY() - 2.5) {
            fail("偏离路线");
            return;
        }
        // 有没有在靠近节点：水平距离 + 还要往上爬的高度。原地跳的时候位置一直在变，但这个数不会变小
        walkTicks++;
        double prog = dist + Math.max(0, s.standY() - p.getY());
        if (prog < bestProg - 0.05) {
            bestProg = prog;
            progTick = walkTicks;
        } else if (walkTicks - progTick > 40) {
            fail("原地打转");
            return;
        }
        float speed = dist < 0.6 ? 0.4f : 1.0f;
        moveToward(p, dx, dz, speed);
        // 平路、下一步也是平路、方向和视角差不多（原版要“往前”才能冲刺）：冲刺，快三成
        boolean flat = s.move() == PathFinder.Move.WALK || s.move() == PathFinder.Move.FALL;
        if (flat && idx + 1 < path.size()) {
            PathFinder.Move nm = path.get(idx + 1).move();
            flat = nm == PathFinder.Move.WALK || nm == PathFinder.Move.FALL;
        }
        sprint = flat && !last && dist > 0.8 && forward > 0.5f && p.getFoodData().getFoodLevel() > 6;
        boolean up = s.move() == PathFinder.Move.ASCEND || s.standY() - p.getY() > 0.6;
        if (up && p.isInWater() && dist < 1.5) {
            // 水里上岸：原版在水里按空格是慢慢往上浮，不是起跳。要一直按着、同时顶着岸往前走，
            // 撞到岸边时才会被往上送一把。原来只在“站在地上”时按一下，一浮起来就松开，沉回去再按，永远上不去
            moveToward(p, dx, dz, 1.0f);
            sprint = false;
            jump = true;
        } else if (up && p.onGround() && dist < 1.5) {
            // 起跳前先站到正对台阶的位置：歪着跳，头会撞到旁边格子上方的方块，原地反复跳
            BlockPos from = stepFrom();
            double lat = 0, ux = 0, uz = 0;
            if (from != null) {
                ux = tx - (from.getX() + 0.5);
                uz = tz - (from.getZ() + 0.5);
                double ul = Math.sqrt(ux * ux + uz * uz);
                if (ul > 0.5) {
                    ux /= ul;
                    uz /= ul;
                    lat = (p.getX() - from.getX() - 0.5) * -uz + (p.getZ() - from.getZ() - 0.5) * ux;
                }
            }
            if (Math.abs(lat) > 0.18 && stuckTicks < 10) {
                moveToward(p, lat * uz, -lat * ux, 0.35f);
            } else {
                jump = true;
            }
        }
        // 卡住检测
        if (p.position().distanceToSqr(lastPos) < 0.0004) {
            stuckTicks++;
            if (stuckTicks > 15 && (p.onGround() || p.isInWater())) jump = true;
            if (stuckTicks > 40) fail("卡住");
        } else {
            stuckTicks = 0;
        }
        lastPos = p.position();
    }

    /** 应急：掉进岩浆/着火时往最近一次站稳的安全位置跳着走 */
    public void escape(LocalPlayer p, net.minecraft.world.phys.Vec3 safe) {
        control = true;
        forward = strafe = 0;
        jump = true;
        if (safe != null) moveToward(p, safe.x - p.getX(), safe.z - p.getZ(), 1.0f);
        path = null;
    }

    /** 把世界坐标方向换算成相对视角的前后/左右输入 */
    private void moveToward(LocalPlayer p, double dx, double dz, float speed) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return;
        dx /= len;
        dz /= len;
        float yaw = p.getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
        // 原版：worldX = strafe*cos - forward*sin；worldZ = forward*cos + strafe*sin
        double f = -dx * sin + dz * cos;
        double st = dx * cos + dz * sin;
        forward = (float) (f * speed);
        strafe = (float) (st * speed);
    }

    /** 在 MovementInputUpdateEvent 中调用 */
    public void apply(Input input) {
        if (!control) return;
        input.forwardImpulse = forward;
        input.leftImpulse = strafe;
        input.up = forward > 0.1f;
        input.down = forward < -0.1f;
        input.left = strafe > 0.1f;
        input.right = strafe < -0.1f;
        input.jumping = jump;
        input.shiftKeyDown = false;
        var pl = Minecraft.getInstance().player;
        if (pl != null && pl.isSprinting() != sprint) pl.setSprinting(sprint);
    }
}
