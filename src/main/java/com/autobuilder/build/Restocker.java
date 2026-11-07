package com.autobuilder.build;

import com.autobuilder.Lang;
import com.autobuilder.AutoBuilder;
import com.autobuilder.path.PathFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 去材料箱补货：走过去 → 右键打开（不弹界面）→ 背包满就先存杂物 → Shift 点击取需要的 → 关箱。
 * 一轮里每个箱子最多去一次；一轮下来什么都没拿到就交给上层暂停报缺料。
 * 另一种模式是去存放箱存东西（砍树/挖矿/挖空时背包满了）：走法、开箱一样，箱子里改成 Shift 点击背包里的东西放进去。
 */
public final class Restocker {
    private enum Stage { GO, OPEN, WAIT_OPEN, TRANSFER }

    private static Stage stage;
    private static BlockPos target;
    private static int stageTicks;
    private static int totalTicks;
    private static final Set<BlockPos> TRIED = new HashSet<>();
    /** 走不到的容器（地下地牢之类）：位置 → 到期时间。五分钟内不再去试，免得每次补货都重新寻路 */
    private static final Map<BlockPos, Integer> UNREACHABLE = new HashMap<>();
    private static final Map<Item, Integer> WANT = new HashMap<>();
    private static boolean gotSomething;
    /** 上一轮一无所获的时间，之后一段时间内不再白跑 */
    private static int idleUntil;
    /** 存东西模式：每种物品至少留在背包里多少（Integer.MAX_VALUE = 一个都不存），这一轮存了几组 */
    private static boolean depositing;
    private static final Map<Item, Integer> KEEP = new HashMap<>();
    private static int deposited;
    /** 存放箱都满了或者走不到：到这个时间之前不再去存（改回扔杂物） */
    private static int depositBlockedUntil;

    /** 用 BuildController 的 tick 计时（原来自己的计数只在补货时走，过期时间实际上永远到不了） */
    private static int clock() {
        return BuildController.now();
    }

    private Restocker() {}

    public static boolean active() {
        return stage != null;
    }

    /** 打开箱子时不弹界面 */
    public static boolean suppressScreen() {
        return stage == Stage.WAIT_OPEN || stage == Stage.TRANSFER;
    }

    public static String describe() {
        if (stage == null) return "";
        if (depositing) return switch (stage) {
            case GO -> Lang.t("act.deposit_go", target.toShortString());
            case OPEN, WAIT_OPEN -> Lang.t("act.deposit_open");
            case TRANSFER -> Lang.t("act.deposit_put");
        };
        return switch (stage) {
            case GO -> Lang.t("act.restock_go", target.toShortString());
            case OPEN, WAIT_OPEN -> Lang.t("act.restock_open");
            case TRANSFER -> Lang.t("act.restock_take");
        };
    }

    public static void abort(Minecraft mc) {
        if (stage == Stage.TRANSFER || stage == Stage.WAIT_OPEN) {
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        }
        stage = null;
        depositing = false;
        TRIED.clear();
    }

    // ---------------- 存东西 ----------------

    /** 现在能不能去存放箱存东西（登记了存放箱，而且最近没有因为都满了/走不到而放弃） */
    public static boolean depositUsable() {
        return SupplyManager.anyDump() && clock() >= depositBlockedUntil;
    }

    /** 存放箱都满了或者走不到：五分钟内不再去 */
    public static void blockDeposit() {
        depositBlockedUntil = clock() + 6000;
    }

    public static boolean depositing() {
        return stage != null && depositing;
    }

    /** 上一轮存了几组 */
    public static int lastDeposited() {
        return deposited;
    }

    private static Map<Item, Integer> counts(LocalPlayer p) {
        Map<Item, Integer> m = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return m;
    }

    /** 这一组放进去以后，背包里这种物品还不少于要留的数量 */
    private static boolean canDeposit(Map<Item, Integer> have, ItemStack s) {
        int keep = KEEP.getOrDefault(s.getItem(), 0);
        if (keep == Integer.MAX_VALUE) return false;
        return have.getOrDefault(s.getItem(), 0) - s.getCount() >= keep;
    }

    private static boolean hasDeposit(LocalPlayer p) {
        Map<Item, Integer> have = counts(p);
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && canDeposit(have, s)) return true;
        }
        return false;
    }

    /**
     * 去存放箱存东西。keep：每种物品至少留多少（不在表里的全存，Integer.MAX_VALUE 表示不存）。
     * 出发了返回 true；没有存放箱、没东西可存、箱子都去不了返回 false。
     */
    public static boolean startDeposit(Minecraft mc, Map<Item, Integer> keep) {
        if (stage != null || !SupplyManager.anyDump() || mc.player == null) return false;
        KEEP.clear();
        KEEP.putAll(keep);
        if (!hasDeposit(mc.player)) return false;
        depositing = true;
        TRIED.clear();
        deposited = 0;
        com.autobuilder.Diag.note("背包快满了 / 收工，去存放箱存东西");
        totalTicks = 0;
        BuildController.breaker().cancel(mc);
        if (goNextDump(mc)) return true;
        depositing = false;
        return false;
    }

    /** 去最近的、这一轮还没去过的存放箱 */
    private static boolean goNextDump(Minecraft mc) {
        LocalPlayer p = mc.player;
        UNREACHABLE.entrySet().removeIf(e -> e.getValue() <= clock());
        List<BlockPos> order = new ArrayList<>();
        for (BlockPos c : SupplyManager.dumps()) {
            if (TRIED.contains(c) || UNREACHABLE.containsKey(c) || !SupplyManager.isContainer(c)) continue;
            order.add(c);
        }
        order.sort((a, b) -> Double.compare(a.distToCenterSqr(p.position()), b.distToCenterSqr(p.position())));
        for (BlockPos c : order) {
            if (Geometry.distToBlock(p.getEyePosition(), c) <= BuildController.reach() - 0.2) {
                target = c;
                stage = Stage.OPEN;
                stageTicks = 0;
                return true;
            }
            List<PathFinder.Step> path = pathTo(c);
            if (path == null) {
                unreachable(c);
                continue;
            }
            target = c;
            stage = Stage.GO;
            stageTicks = 0;
            BuildController.walker().setPath(path, c);
            return true;
        }
        stage = null;
        if (deposited == 0) {
            // 一组都没存进去：箱子都满了或者都走不到，先改回扔杂物
            blockDeposit();
            BuildController.msg(Lang.t("deposit.blocked"), net.minecraft.ChatFormatting.YELLOW);
        }
        return false;
    }

    /**
     * 去容器的路：补货（会翻没登记的附近容器）只走不挖、最多垫 4 块，免得为了地牢箱子挖地；
     * 存放箱是玩家自己登记的，可以多垫（挖空的坑底要垫柱子才上得来），但还是不挖。
     */
    private static List<PathFinder.Step> pathTo(BlockPos c) {
        return depositing ? BuildController.pathToContainer(c, false, Integer.MAX_VALUE) : BuildController.pathToContainer(c);
    }

    /** 往箱子里放几组：1 还有要放的，0 放完了，-1 箱子满了 */
    private static int depositStep(Minecraft mc) {
        LocalPlayer p = mc.player;
        AbstractContainerMenu menu = p.containerMenu;
        Map<Item, Integer> have = counts(p);
        int clicks = 0;
        for (Slot s : menu.slots) {
            if (s.container != p.getInventory() || !s.hasItem()) continue;
            ItemStack st = s.getItem();
            if (!canDeposit(have, st)) continue;
            if (clicks >= 4) return 1;
            Item it = st.getItem();
            int before = st.getCount();
            mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, ClickType.QUICK_MOVE, p);
            int after = s.hasItem() && s.getItem().is(it) ? s.getItem().getCount() : 0;
            if (after == before) return -1; // 一个都放不进去：满了
            have.merge(it, after - before, Integer::sum);
            deposited++;
            clicks++;
            if (after > 0) return -1; // 只放进去一部分：满了
        }
        return clicks > 0 ? 1 : 0;
    }

    /** 背包里需要补的：蓝图剩余用量 - 已有 */
    private static void computeWant(LocalPlayer p) {
        WANT.clear();
        Scanner.Snapshot s = BuildController.snapshot();
        if (s == null) return;
        for (var e : s.need.entrySet()) {
            int lack = e.getValue() - InventoryHelper.count(p, e.getKey());
            if (lack > 0) WANT.put(e.getKey(), lack);
        }
    }

    /** 缺料时调用。能出发去补货返回 true；没有可去的箱子返回 false */
    public static boolean start(Minecraft mc) {
        if (!SupplyManager.any() && !com.autobuilder.ABConfig.SEARCH_CONTAINERS.get() || clock() < idleUntil) return false;
        if (stage != null && depositing) return true; // 正在去存东西，存完再说
        if (stage == null) {
            TRIED.clear();
            gotSomething = false;
            totalTicks = 0;
        }
        computeWant(mc.player);
        if (WANT.isEmpty()) return false;
        com.autobuilder.Diag.note("缺料，去补货：" + WANT);
        return goNext(mc);
    }

    private static boolean goNext(Minecraft mc) {
        LocalPlayer p = mc.player;
        UNREACHABLE.entrySet().removeIf(e -> e.getValue() <= clock());
        List<BlockPos> order = new ArrayList<>();
        for (BlockPos c : SupplyManager.list()) {
            if (TRIED.contains(c) || UNREACHABLE.containsKey(c) || !SupplyManager.isContainer(c)) continue;
            order.add(c);
        }
        // 没登记的附近容器也去翻一翻（登记的优先）
        if (com.autobuilder.ABConfig.SEARCH_CONTAINERS.get()) {
            for (BlockPos c : SupplyManager.nearby(p.blockPosition(), com.autobuilder.ABConfig.SEARCH_RADIUS.get())) {
                if (TRIED.contains(c) || UNREACHABLE.containsKey(c) || order.contains(c)) continue;
                order.add(c);
            }
        }
        // 已知有货的优先，其次没打开过的；已知没货的不去
        order.removeIf(c -> {
            boolean unknown = false, has = false;
            for (Item it : WANT.keySet()) {
                int k = SupplyManager.knownCountLike(c, it);
                if (k < 0) unknown = true;
                if (k > 0) has = true;
            }
            return !unknown && !has;
        });
        order.sort((a, b) -> {
            int ka = knownScore(a), kb = knownScore(b);
            if (ka != kb) return Integer.compare(kb, ka);
            boolean ra = SupplyManager.isRegistered(a), rb = SupplyManager.isRegistered(b);
            if (ra != rb) return ra ? -1 : 1;
            return Double.compare(a.distToCenterSqr(p.position()), b.distToCenterSqr(p.position()));
        });
        for (BlockPos c : order) {
            if (Geometry.distToBlock(p.getEyePosition(), c) <= BuildController.reach() - 0.2) {
                target = c;
                stage = Stage.OPEN;
                stageTicks = 0;
                return true;
            }
            List<PathFinder.Step> path = BuildController.pathToContainer(c);
            if (path == null) {
                unreachable(c);
                continue;
            }
            target = c;
            stage = Stage.GO;
            stageTicks = 0;
            BuildController.walker().setPath(path, c);
            return true;
        }
        // 这一轮跑完了
        stage = null;
        if (!gotSomething) idleUntil = clock() + 1200;
        return false;
    }

    private static void unreachable(BlockPos c) {
        TRIED.add(c);
        UNREACHABLE.put(c, clock() + 6000);
        com.autobuilder.Diag.log("容器 {} 走不到（要挖进去或者路被堵死），五分钟内不再去", c);
    }

    private static int knownScore(BlockPos c) {
        int n = 0;
        for (Item it : WANT.keySet()) n += Math.max(0, SupplyManager.knownCountLike(c, it));
        return n;
    }

    /** 每 tick 调用；补货结束后 active() 变 false */
    public static void tick(Minecraft mc) {
        if (stage == null) return;
        LocalPlayer p = mc.player;
        stageTicks++;
        if (++totalTicks > 2400) {
            AutoBuilder.LOG.info(depositing ? "[AutoBuilder] 存东西超时，放弃" : "[AutoBuilder] 补货超时，放弃");
            com.autobuilder.Diag.note(depositing ? "存东西超时，放弃" : "补货超时，放弃");
            abort(mc);
            return;
        }
        switch (stage) {
            case GO -> {
                // 路上要挖的方块（正常不会有：去容器的路不挖）也得有人挖，不然会一直站着等
                if (BuildController.breaker().active()) {
                    BuildController.breaker().tick(mc, BuildController.reach());
                    BuildController.walker().hold();
                    return;
                }
                if (Geometry.distToBlock(p.getEyePosition(), target) <= BuildController.reach() - 0.2 && p.onGround()) {
                    BuildController.walker().clear();
                    stage = Stage.OPEN;
                    stageTicks = 0;
                    BuildController.walker().hold();
                    return;
                }
                if (!BuildController.walker().hasPath() || stageTicks > 600) {
                    List<PathFinder.Step> path = pathTo(target);
                    if (path == null || path.isEmpty() && stageTicks > 40) {
                        unreachable(target);
                        if (!next(mc)) stage = null;
                        return;
                    }
                    BuildController.walker().setPath(path, target);
                }
                BuildController.walker().tick(mc);
            }
            case OPEN -> {
                BuildController.walker().hold();
                if (!p.onGround()) return;
                InventoryHelper.selectNonBlock(mc);
                Vec3 eye = p.getEyePosition();
                Vec3 c = Vec3.atCenterOf(target);
                Direction face = Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
                BlockHitResult hit = new BlockHitResult(c.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5), face, target, false);
                if (p.isShiftKeyDown()) return;
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                p.swing(InteractionHand.MAIN_HAND);
                stage = Stage.WAIT_OPEN;
                stageTicks = 0;
            }
            case WAIT_OPEN -> {
                BuildController.walker().hold();
                if (p.containerMenu != p.inventoryMenu) {
                    stage = Stage.TRANSFER;
                    stageTicks = 0;
                } else if (stageTicks > 40) {
                    TRIED.add(target); // 打不开（被挡住、被锁）
                    if (!next(mc)) stage = null;
                }
            }
            case TRANSFER -> {
                BuildController.walker().hold();
                if (p.containerMenu == p.inventoryMenu) { // 被别的东西关掉了
                    TRIED.add(target);
                    if (!next(mc)) stage = null;
                    return;
                }
                if (stageTicks < 4) return; // 等箱子内容同步过来
                if (depositing) {
                    int r = depositStep(mc);
                    if (r == 1 && stageTicks < 200) return;
                    remember(p);
                    p.closeContainer();
                    TRIED.add(target);
                    com.autobuilder.Diag.note("存放箱 " + target.toShortString() + " 存了 " + deposited + " 组" + (r == -1 ? "（箱子满了）" : ""));
                    // 这个箱子满了、背包里还有要存的：换下一个存放箱
                    if (r != 0 && hasDeposit(p) && goNextDump(mc)) return;
                    stage = null;
                    depositing = false;
                    return;
                }
                if (transferStep(mc) && stageTicks < 200) return;
                remember(p);
                p.closeContainer();
                TRIED.add(target);
                computeWant(p);
                com.autobuilder.Diag.note("材料箱 " + target.toShortString() + " 看完了，还缺 " + WANT);
                if (WANT.isEmpty() || !goNext(mc)) {
                    stage = null;
                    if (gotSomething) BuildController.msg(Lang.t("msg.restocked"), net.minecraft.ChatFormatting.AQUA);
                }
            }
        }
    }

    /** 当前模式的下一个箱子 */
    private static boolean next(Minecraft mc) {
        if (!depositing) return goNext(mc);
        if (goNextDump(mc)) return true;
        depositing = false;
        return false;
    }

    private static void remember(LocalPlayer p) {
        Map<Item, Integer> m = new HashMap<>();
        for (Slot s : p.containerMenu.slots) {
            if (s.container == p.getInventory() || !s.hasItem()) continue;
            m.merge(s.getItem().getItem(), s.getItem().getCount(), Integer::sum);
        }
        SupplyManager.remember(target, m);
    }

    /** 蓝图用不到的方块（挖出来的泥土、多余材料），可以存进箱子腾地方。工具、食物、桶、潜影盒等一律不动 */
    private static boolean isJunk(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) return false;
        if (s.has(DataComponents.CONTAINER) || s.has(DataComponents.BLOCK_ENTITY_DATA)) return false;
        Scanner.Snapshot snap = BuildController.snapshot();
        if (snap != null && snap.need.keySet().stream().anyMatch(n -> Substitutes.sameItem(n, s.getItem()))) return false;
        return !ScaffoldItems.items().contains(s.getItem());
    }

    private static boolean playerHasRoomFor(LocalPlayer p, Item item) {
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) return true;
            if (st.is(item) && st.getCount() < st.getMaxStackSize()) return true;
        }
        return false;
    }

    /** 箱子里这个物品顶哪一项需求：pass 0 只认一模一样的，pass 1 也认同类代替品（别的木头/颜色） */
    private static Item wantKey(Item it, int pass) {
        if (WANT.getOrDefault(it, 0) > 0) return pass == 0 ? it : null;
        if (pass == 0) return null;
        for (var e : WANT.entrySet()) {
            if (e.getValue() > 0 && Substitutes.sameItem(e.getKey(), it)) return e.getKey();
        }
        return null;
    }

    /** 做几次点击；还有事可做返回 true */
    private static boolean transferStep(Minecraft mc) {
        LocalPlayer p = mc.player;
        AbstractContainerMenu menu = p.containerMenu;
        int clicks = 0;
        boolean more = false;
        // 先拿蓝图要的那种，不够再拿同类代替品
        for (int pass = 0; pass < 2; pass++) {
            for (Slot s : menu.slots) {
                if (clicks >= 4) return true;
                if (s.container == p.getInventory() || !s.hasItem()) continue;
                Item it = s.getItem().getItem();
                Item key = wantKey(it, pass);
                if (key == null) continue;
                int want = WANT.get(key);
                if (!playerHasRoomFor(p, it)) {
                    // 背包满：先往登记的材料箱存一件杂物（自己翻到的别人的箱子不往里塞东西）
                    if (SupplyManager.isRegistered(target) && depositOneJunk(mc, menu)) {
                        clicks++;
                        more = true;
                        continue;
                    }
                    return false; // 装不下了
                }
                int n = s.getItem().getCount();
                mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, ClickType.QUICK_MOVE, p);
                WANT.put(key, want - n);
                gotSomething = true;
                clicks++;
                more = true;
            }
        }
        return more && clicks > 0;
    }

    private static boolean depositOneJunk(Minecraft mc, AbstractContainerMenu menu) {
        LocalPlayer p = mc.player;
        for (Slot s : menu.slots) {
            if (s.container != p.getInventory() || !s.hasItem()) continue;
            if (!isJunk(s.getItem())) continue;
            mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, ClickType.QUICK_MOVE, p);
            return true;
        }
        return false;
    }
}
