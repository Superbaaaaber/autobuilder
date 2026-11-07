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
 */
public final class Restocker {
    private enum Stage { GO, OPEN, WAIT_OPEN, TRANSFER }

    private static Stage stage;
    private static BlockPos target;
    private static int stageTicks;
    private static int totalTicks;
    private static final Set<BlockPos> TRIED = new HashSet<>();
    private static final Map<Item, Integer> WANT = new HashMap<>();
    private static boolean gotSomething;
    /** 上一轮一无所获的时间，之后一段时间内不再白跑 */
    private static int idleUntil;
    private static int clock;

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
        TRIED.clear();
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
        if (!SupplyManager.any() && !com.autobuilder.ABConfig.SEARCH_CONTAINERS.get() || clock < idleUntil) return false;
        if (stage == null) {
            TRIED.clear();
            gotSomething = false;
            totalTicks = 0;
        }
        computeWant(mc.player);
        if (WANT.isEmpty()) return false;
        return goNext(mc);
    }

    private static boolean goNext(Minecraft mc) {
        LocalPlayer p = mc.player;
        List<BlockPos> order = new ArrayList<>();
        for (BlockPos c : SupplyManager.list()) {
            if (TRIED.contains(c) || !SupplyManager.isContainer(c)) continue;
            order.add(c);
        }
        // 没登记的附近容器也去翻一翻（登记的优先）
        if (com.autobuilder.ABConfig.SEARCH_CONTAINERS.get()) {
            for (BlockPos c : SupplyManager.nearby(p.blockPosition(), com.autobuilder.ABConfig.SEARCH_RADIUS.get())) {
                if (TRIED.contains(c) || order.contains(c)) continue;
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
            List<PathFinder.Step> path = BuildController.pathToReach(c);
            if (path == null) {
                TRIED.add(c);
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
        if (!gotSomething) idleUntil = clock + 1200;
        return false;
    }

    private static int knownScore(BlockPos c) {
        int n = 0;
        for (Item it : WANT.keySet()) n += Math.max(0, SupplyManager.knownCountLike(c, it));
        return n;
    }

    /** 每 tick 调用；补货结束后 active() 变 false */
    public static void tick(Minecraft mc) {
        clock++;
        if (stage == null) return;
        LocalPlayer p = mc.player;
        stageTicks++;
        if (++totalTicks > 2400) {
            AutoBuilder.LOG.info("[AutoBuilder] 补货超时，放弃");
            abort(mc);
            return;
        }
        switch (stage) {
            case GO -> {
                if (Geometry.distToBlock(p.getEyePosition(), target) <= BuildController.reach() - 0.2 && p.onGround()) {
                    BuildController.walker().clear();
                    stage = Stage.OPEN;
                    stageTicks = 0;
                    BuildController.walker().hold();
                    return;
                }
                if (!BuildController.walker().hasPath() || stageTicks > 600) {
                    List<PathFinder.Step> path = BuildController.pathToReach(target);
                    if (path == null || path.isEmpty() && stageTicks > 40) {
                        TRIED.add(target);
                        if (!goNext(mc)) stage = null;
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
                    if (!goNext(mc)) stage = null;
                }
            }
            case TRANSFER -> {
                BuildController.walker().hold();
                if (p.containerMenu == p.inventoryMenu) { // 被别的东西关掉了
                    TRIED.add(target);
                    if (!goNext(mc)) stage = null;
                    return;
                }
                if (stageTicks < 4) return; // 等箱子内容同步过来
                if (transferStep(mc) && stageTicks < 200) return;
                remember(p);
                p.closeContainer();
                TRIED.add(target);
                computeWant(p);
                if (WANT.isEmpty() || !goNext(mc)) {
                    stage = null;
                    if (gotSomething) BuildController.msg(Lang.t("msg.restocked"), net.minecraft.ChatFormatting.AQUA);
                }
            }
        }
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
