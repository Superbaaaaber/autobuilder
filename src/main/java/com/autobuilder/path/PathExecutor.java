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

    // 本 tick 要施加的输入
    private boolean control;
    private float forward, strafe;
    private boolean jump;

    public void setPath(List<PathFinder.Step> p, BlockPos goal) {
        this.path = p;
        this.goal = goal;
        this.idx = 0;
        this.stepTicks = 0;
        this.stuckTicks = 0;
        this.failed = false;
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

    public BlockPos goal() {
        return goal;
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
    }

    public void release() {
        control = false;
    }

    public String describe() {
        if (!hasPath()) return "";
        return Lang.t("act.path", idx + 1, path.size(), path.get(idx).move());
    }

    private void fail() {
        path = null;
        failed = true;
    }

    private void advance() {
        idx++;
        stepTicks = 0;
        stuckTicks = 0;
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
            fail();
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
                    if (!BuildController.breaker().active()) BuildController.breaker().start(m);
                    stepTicks = Math.min(stepTicks, 150);
                    return;
                }
            }
        }
        if ((s.move() == PathFinder.Move.BRIDGE || s.move() == PathFinder.Move.STEP_DOWN)
                && s.place() != null && StateMatcher.isSoft(mc.level.getBlockState(s.place()))) {
            if (!BuildController.placeSupport(s.place())) {
                if (stepTicks > 20) fail();
            }
            return;
        }
        if (s.move() == PathFinder.Move.PILLAR && s.place() != null) {
            BlockPos pl = s.place();
            if (StateMatcher.isSoft(mc.level.getBlockState(pl))) {
                double cx = pl.getX() + 0.5 - p.getX(), cz = pl.getZ() + 0.5 - p.getZ();
                if (cx * cx + cz * cz > 0.04 && p.onGround()) {
                    moveToward(p, cx, cz, 0.5f);
                    return;
                }
                if (p.onGround()) {
                    jump = true;
                    return;
                }
                if (p.getY() >= pl.getY() + 1.0) {
                    if (!BuildController.placeSupport(pl) && stepTicks > 40) fail();
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
        if (dist > 3.5 || p.getY() < s.standY() - 4) {
            fail();
            return;
        }
        float speed = dist < 0.6 ? 0.4f : 1.0f;
        moveToward(p, dx, dz, speed);
        if ((s.move() == PathFinder.Move.ASCEND || s.standY() - p.getY() > 0.6) && p.onGround() && dist < 1.5) {
            jump = true;
        }
        // 卡住检测
        if (p.position().distanceToSqr(lastPos) < 0.0004) {
            stuckTicks++;
            if (stuckTicks > 15 && p.onGround()) jump = true;
            if (stuckTicks > 40) fail();
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
    }
}
