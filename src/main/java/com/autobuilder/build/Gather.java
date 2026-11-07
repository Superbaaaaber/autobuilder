package com.autobuilder.build;

import com.autobuilder.Lang;
import com.autobuilder.blueprint.Blueprint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * 自动砍树、自动挖矿。
 * 做法：把“要清掉的格子”生成一个临时蓝图（这些格子是空气，其余不管），交给 BuildController 去“建”，
 * 寻路、垫柱子、拆脚手架、溺水自救、回血等全部沿用建造的逻辑。
 * 支巷挖矿（/ab mine）只挖巷道挖开后露出来的矿；找矿（/ab ore）直接按客户端已加载的区块找矿脉挖过去（相当于矿物透视）。
 * 挖空选区（/ab excavate）把选区里能挖的方块全挖掉，从上往下一层层挖。
 */
public final class Gather {
    public enum Kind { CHOP, MINE, ORE, EXCAVATE }



    /** 找矿能用的矿种关键字（方块 id 里包含它就算） */
    public static final List<String> ORE_KINDS = List.of("all", "diamond", "iron", "gold", "redstone", "lapis", "emerald", "coal", "copper", "debris", "quartz");

    private static final Minecraft MC = Minecraft.getInstance();
    private static Kind kind;
    private static boolean active;
    /** 砍树的中心（开始时站的位置）和半径 */
    private static BlockPos home;
    private static int radius;
    private static int trees;
    /** 砍过或砍不了的树根，别再找它 */
    private static final Set<Long> SKIP = new HashSet<>();
    private static BlockPos currentBase;
    private static int waitUntil;
    private static int ticks;
    /** 挖矿：巷道格子 + 已加进去的矿（世界坐标），用来找新露出来的矿 */
    private static final List<BlockPos> OPEN = new ArrayList<>();
    private static final Set<Long> ORES = new HashSet<>();
    private static int tunnelCells;
    /** 找矿：矿种、当前这条矿脉、挖到的块数 */
    private static String oreKind = "all";
    private static List<BlockPos> currentVein;
    private static int oreMined;
    private static boolean warnedMp;
    /** 存放箱：开工时背包里每种物品有多少（原来带的不存，只存这次挖到的）；收工前去存东西时先记下结束语 */
    private static final java.util.Map<Item, Integer> BASELINE = new java.util.HashMap<>();
    private static String pendingEnd;
    private static boolean dumpTipShown, wasDepositing;
    /** 挖空：开始时要挖的块数、选区里有没有要镐子才挖得动的方块 */
    private static int digTotal;
    private static boolean needPick;

    private Gather() {}

    public static boolean active() {
        return active;
    }

    public static Kind kind() {
        return kind;
    }

    // ---------------- 开始 / 结束 ----------------

    public static void startChop(int r) {
        LocalPlayer p = MC.player;
        if (p == null) return;
        cancel();
        BuildController.parkForGather();
        beginJob(p);
        kind = Kind.CHOP;
        active = true;
        home = p.blockPosition();
        radius = r;
        trees = 0;
        SKIP.clear();
        BuildController.msg(Lang.t("gather.chop_start", r), ChatFormatting.GREEN);
        nextTree();
    }

    public static void startMine(int length, int branch) {
        LocalPlayer p = MC.player;
        if (p == null) return;
        if (!hasPickaxe(p)) {
            BuildController.msg(Lang.t("gather.no_pickaxe"), ChatFormatting.RED);
            return;
        }
        cancel();
        BuildController.parkForGather();
        beginJob(p);
        kind = Kind.MINE;
        active = true;
        ORES.clear();
        OPEN.clear();
        Placement pl = tunnelPlacement(p.blockPosition(), p.getDirection(), length, branch);
        BuildController.msg(Lang.t("gather.mine_start", Lang.t("dir." + p.getDirection().getName()), length, branch, p.blockPosition().getY()), ChatFormatting.GREEN);
        run(pl);
    }

    /**
     * 找矿：在已加载的区块里找 r 格内的矿（矿种 kind，all 表示所有矿），挖到最近的一条矿脉，挖完再找下一条。
     */
    public static void startOre(String kindArg, int r) {
        LocalPlayer p = MC.player;
        if (p == null) return;
        if (!hasPickaxe(p)) {
            BuildController.msg(Lang.t("gather.no_pickaxe"), ChatFormatting.RED);
            return;
        }
        cancel();
        BuildController.parkForGather();
        beginJob(p);
        kind = Kind.ORE;
        active = true;
        oreKind = kindArg;
        home = p.blockPosition();
        radius = r;
        oreMined = 0;
        SKIP.clear();
        BuildController.msg(Lang.t("gather.ore_start", r, oreLabel()), ChatFormatting.GREEN);
        if (MC.getSingleplayerServer() == null && !warnedMp) {
            warnedMp = true;
            BuildController.msg(Lang.t("gather.ore_mp_note"), ChatFormatting.YELLOW);
        }
        nextVein();
    }

    /**
     * 挖空选区：两个角之间所有能挖的方块都挖掉（空气、水、岩浆不管），从上往下一层层挖，挖出来的石头泥土背包满了就扔。
     * 箱子、木桶、熔炉这类装东西的方块不挖（免得东西撒一地）；挨着水和岩浆的不挖（沿用砍树挖矿的规则，不把水放进坑里）。
     */
    public static void startExcavate(BlockPos a, BlockPos b) {
        LocalPlayer p = MC.player;
        ClientLevel level = MC.level;
        if (p == null || level == null) return;
        int minX = Math.min(a.getX(), b.getX()), minY = Math.max(level.getMinBuildHeight(), Math.min(a.getY(), b.getY()));
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()), maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(a.getY(), b.getY()));
        int maxZ = Math.max(a.getZ(), b.getZ());
        if (maxY < minY) {
            BuildController.msg(Lang.t("gather.excavate_empty"), ChatFormatting.YELLOW);
            return;
        }
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        long vol = (long) sx * sy * sz;
        int max = com.autobuilder.ABConfig.MAX_EXCAVATE.get();
        if (vol > max) {
            BuildController.msg(Lang.t("gather.excavate_too_big", sx, sy, sz, vol, max), ChatFormatting.RED);
            return;
        }
        Blueprint bp = new Blueprint(Lang.t("gather.excavate_name"), sx, sy, sz);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int count = 0, containers = 0;
        boolean pick = false;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    m.set(minX + x, minY + y, minZ + z);
                    if (!level.isLoaded(m)) {
                        BuildController.msg(Lang.t("gather.excavate_unloaded"), ChatFormatting.RED);
                        return;
                    }
                    BlockState s = level.getBlockState(m);
                    // 末影箱也不挖：没有精准采集只掉黑曜石，箱子就没了
                    var be = level.getBlockEntity(m);
                    if (com.autobuilder.ABConfig.EXCAVATE_KEEP_CONTAINERS.get()
                            && (be instanceof net.minecraft.world.Container || be instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity)) {
                        containers++;
                        continue; // 不设目标 = 不管它
                    }
                    bp.set(x, y, z, air);
                    if (!StateMatcher.breakable(s) || StateMatcher.isSoft(s)) continue;
                    count++;
                    if (s.requiresCorrectToolForDrops() && s.is(BlockTags.MINEABLE_WITH_PICKAXE)) pick = true;
                }
        if (count == 0) {
            BuildController.msg(Lang.t("gather.excavate_empty"), ChatFormatting.YELLOW);
            return;
        }
        if (pick && !hasPickaxe(p)) {
            BuildController.msg(Lang.t("gather.excavate_no_pickaxe"), ChatFormatting.RED);
            return;
        }
        cancel();
        BuildController.parkForGather();
        beginJob(p);
        kind = Kind.EXCAVATE;
        active = true;
        digTotal = count;
        needPick = pick;
        String box = new BlockPos(minX, minY, minZ).toShortString() + " ~ " + new BlockPos(maxX, maxY, maxZ).toShortString();
        BuildController.msg(Lang.t("gather.excavate_start", box, sx, sy, sz, count)
                + (containers > 0 ? Lang.t("gather.excavate_containers", containers) : ""), ChatFormatting.GREEN);
        Placement pl = new Placement(bp, new BlockPos(minX, minY, minZ));
        pl.layered = true;
        run(pl);
    }

    /** 挖空：选区里还剩几块能挖的（容器不算） */
    private static int excavateLeft(Placement pl) {
        ClientLevel level = MC.level;
        if (level == null || pl == null) return 0;
        BlockPos o = pl.origin();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int y = 0; y < pl.sizeY(); y++)
            for (int z = 0; z < pl.sizeZ(); z++)
                for (int x = 0; x < pl.sizeX(); x++) {
                    m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                    if (pl.targetAt(m) == null) continue;
                    BlockState s = level.getBlockState(m);
                    if (StateMatcher.breakable(s) && !StateMatcher.isSoft(s)) n++;
                }
        return n;
    }

    private static void endExcavate(Placement pl, boolean stopped) {
        int left = excavateLeft(pl);
        int dug = Math.max(0, digTotal - left);
        if (stopped) end(Lang.t("gather.excavate_stopped", dug, left), false);
        else if (left == 0) end(Lang.t("gather.excavate_done", dug), true);
        else end(Lang.t("gather.excavate_left", dug, left), true);
    }

    private static String oreLabel() {
        return Lang.t("ore.kind." + oreKind);
    }

    /** 用户停止/卸载时调用：不再安排下一个，临时蓝图交给调用者处理 */
    public static void cancel() {
        active = false;
        currentBase = null;
        pendingEnd = null;
    }

    /**
     * 收工。deposit=true（正常做完，不是用户停的）而且登记了存放箱：先回去把挖到的东西存了，存完再收工。
     * 去存东西时 BuildController 照常运行（只做存货），存完变成 DONE，tick 里再走到这里真正收工。
     */
    private static void end(String text, boolean deposit) {
        LocalPlayer p = MC.player;
        if (deposit && com.autobuilder.ABConfig.DEPOSIT_AT_END.get() && pendingEnd == null && p != null && Restocker.depositUsable()) {
            var keep = keepAmounts(p);
            BuildController.start();
            if (BuildController.state() == BuildController.State.RUNNING && Restocker.startDeposit(MC, keep)) {
                BuildController.depositOnly = true;
                pendingEnd = text;
                return;
            }
        }
        String extra = pendingEnd != null && Restocker.lastDeposited() > 0 ? Lang.t("deposit.summary", Restocker.lastDeposited()) : "";
        pendingEnd = null;
        active = false;
        BuildController.endTemporary();
        BuildController.msg(text + extra, ChatFormatting.GREEN);
    }

    /** 开工：记下背包里原来有什么（存东西时不动这些），没登记存放箱的话提示一次 */
    private static void beginJob(LocalPlayer p) {
        com.autobuilder.Diag.session(new Object(), "砍树/挖矿/找矿/挖空");
        BASELINE.clear();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) BASELINE.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        pendingEnd = null;
        wasDepositing = false;
        if (!SupplyManager.anyDump() && !dumpTipShown) {
            dumpTipShown = true;
            BuildController.msg(Lang.t("deposit.tip"), ChatFormatting.GRAY);
        }
    }

    /** 这种东西一个都不存：工具武器盔甲（有耐久）、食物、桶、火把、选区木棍、潜影盒；砍树时的树苗（要补种） */
    private static boolean keepAll(ItemStack s) {
        Item it = s.getItem();
        if (s.isDamageableItem() || s.has(net.minecraft.core.component.DataComponents.FOOD)
                || s.has(net.minecraft.core.component.DataComponents.CONTAINER)) return true;
        if (it instanceof net.minecraft.world.item.BucketItem || it == Items.MILK_BUCKET) return true;
        if (it == SelectionWand.item() || com.autobuilder.ABConfig.items(com.autobuilder.ABConfig.NEVER_STORE).contains(it)) return true;
        return kind == Kind.CHOP && s.is(net.minecraft.tags.ItemTags.SAPLINGS);
    }

    /**
     * 存东西时每种物品至少留多少：上面那些全留；其余留开工时的数量。
     * 垫脚方块总共只多留一组（背包里最多的那种），每种都留一组会占掉好几格，没挖几块又满了
     */
    private static java.util.Map<Item, Integer> keepAmounts(LocalPlayer p) {
        java.util.Map<Item, Integer> keep = new java.util.HashMap<>();
        Item scaffold = null;
        int most = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || keep.containsKey(s.getItem())) continue;
            keep.put(s.getItem(), keepAll(s) ? Integer.MAX_VALUE : BASELINE.getOrDefault(s.getItem(), 0));
            if (ScaffoldItems.items().contains(s.getItem()) && s.getItem() instanceof net.minecraft.world.item.BlockItem) {
                int n = InventoryHelper.count(p, s.getItem());
                if (n > most) {
                    most = n;
                    scaffold = s.getItem();
                }
            }
        }
        if (scaffold != null && keep.get(scaffold) != Integer.MAX_VALUE) keep.merge(scaffold, com.autobuilder.ABConfig.KEEP_SCAFFOLD_STORE.get(), Math::max);
        return keep;
    }

    private static void run(Placement pl) {
        pl.temporary = true;
        BuildController.quiet = true;
        BuildController.setPlacement(pl);
        BuildController.start();
    }

    // ---------------- 每 tick ----------------

    public static void tick() {
        if (!active) return;
        ticks++;
        LocalPlayer p = MC.player;
        Placement pl = BuildController.placement();
        if (p == null || pl == null || !pl.temporary) {
            active = false; // 退出世界、换了蓝图
            BuildController.quiet = false;
            return;
        }
        var st = BuildController.state();
        if (pendingEnd != null) {
            // 活已经干完，正在回去存东西：存完（DONE）或者被用户停下（IDLE）就收工
            if (st == BuildController.State.DONE || st == BuildController.State.IDLE) end(pendingEnd, false);
            return;
        }
        boolean dep = Restocker.depositing();
        if (wasDepositing && !dep && Restocker.lastDeposited() > 0) {
            BuildController.msg(Lang.t("deposit.done", Restocker.lastDeposited()), ChatFormatting.AQUA);
        }
        wasDepositing = dep;
        if (st == BuildController.State.IDLE) {
            // 用户 /ab stop
            if (kind == Kind.EXCAVATE) {
                endExcavate(pl, true);
                return;
            }
            if (kind == Kind.ORE) countVein();
            end(switch (kind) {
                case CHOP -> Lang.t("gather.chop_stopped", trees);
                case MINE -> Lang.t("gather.mine_stopped", minedOres());
                case ORE -> Lang.t("gather.ore_stopped", oreMined);
                case EXCAVATE -> "";
            }, false);
            return;
        }
        if (st == BuildController.State.PAUSED) return; // 等用户按 N 继续
        if (st == BuildController.State.DONE) {
            if (kind == Kind.CHOP) {
                if (currentBase != null) {
                    SKIP.add(currentBase.asLong());
                    trees++;
                    currentBase = null;
                    waitUntil = ticks + 20; // 等掉落物落地，顺便捡
                }
                if (ticks >= waitUntil) nextTree();
            } else if (kind == Kind.ORE) {
                countVein();
                nextVein();
            } else if (kind == Kind.EXCAVATE) {
                endExcavate(pl, false);
            } else {
                end(Lang.t("gather.mine_done", tunnelCells, minedOres()), true);
            }
            return;
        }
        // RUNNING
        if (dep) return; // 正在去存放箱，存完 BuildController 自己接着干
        if (ticks % 20 == 0 && freeSlots(p) < com.autobuilder.ABConfig.DEPOSIT_FREE_SLOTS.get() && Restocker.depositUsable()) {
            // 背包快满了：有存放箱就回去存，存不了（没东西可存、箱子都去不了）先改回扔杂物
            if (Restocker.startDeposit(MC, keepAmounts(p))) return;
            Restocker.blockDeposit();
        }
        if (ticks % 20 == 0 && !makeRoom(p)) {
            BuildController.pause(Lang.t("reason.inv_full"));
            return;
        }
        if (kind == Kind.MINE || kind == Kind.ORE || kind == Kind.EXCAVATE && needPick) {
            if (!hasPickaxe(p)) {
                BuildController.pause(Lang.t("reason.no_pickaxe"));
                return;
            }
            if (kind == Kind.MINE && ticks % 10 == 0) exposeOres(pl);
        }
    }

    // ---------------- 砍树 ----------------

    private static void nextTree() {
        LocalPlayer p = MC.player;
        ClientLevel level = MC.level;
        List<BlockPos> tree = findTree(level, p);
        if (tree == null) {
            end(Lang.t("gather.chop_done", radius, trees), true);
            return;
        }
        BlockPos base = tree.get(0);
        currentBase = base;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos b : tree) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }
        Blueprint bp = new Blueprint(Lang.t("gather.tree_name", trees + 1), maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos b : tree) bp.set(b.getX() - minX, b.getY() - minY, b.getZ() - minZ, air);
        // 补种：单根树干、背包里有对应树苗，就把树根那格的目标设成树苗（挖掉原木后种上）
        Block sapling = saplingFor(level.getBlockState(base).getBlock());
        long trunkAtBase = tree.stream().filter(b -> b.getY() == base.getY()).count();
        if (sapling != null && trunkAtBase == 1 && InventoryHelper.count(p, sapling.asItem()) > 0
                && com.autobuilder.ABConfig.REPLANT.get()) {
            bp.set(base.getX() - minX, base.getY() - minY, base.getZ() - minZ, sapling.defaultBlockState());
        }
        Placement pl = new Placement(bp, new BlockPos(minX, minY, minZ));
        run(pl);
    }

    /** 只认天然的原木/菌柄（不认去皮的、六面树皮的） */
    private static boolean isLog(BlockState s) {
        if (!s.is(BlockTags.LOGS)) return false;
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return !path.startsWith("stripped_") && (path.endsWith("_log") || path.endsWith("_stem"));
    }

    private static boolean naturalLeaf(BlockState s) {
        if (s.getBlock() instanceof LeavesBlock) return !s.getValue(LeavesBlock.PERSISTENT);
        return s.is(Blocks.NETHER_WART_BLOCK) || s.is(Blocks.WARPED_WART_BLOCK) || s.is(Blocks.SHROOMLIGHT);
    }

    /** 像是玩家建筑的方块：原木挨着这些就不砍（防止把木屋拆了） */
    private static boolean buildingBlock(BlockState s) {
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.DOORS)
                || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.BEDS)
                || s.is(BlockTags.WOOL) || s.is(BlockTags.ALL_SIGNS) || s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES)
                || s.is(Tags.Blocks.CHESTS) || s.is(Tags.Blocks.BARRELS) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.TORCH)
                || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN) || s.is(Blocks.FURNACE);
    }

    /** 找离人最近的一棵天然树：返回它的所有原木，第一个是树根 */
    private static List<BlockPos> findTree(ClientLevel level, LocalPlayer p) {
        List<BlockPos> bases = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -8; dy <= 12; dy++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dx = -radius; dx <= radius; dx++) {
                    m.set(home.getX() + dx, home.getY() + dy, home.getZ() + dz);
                    if (!level.isLoaded(m)) continue;
                    BlockState s = level.getBlockState(m);
                    if (!isLog(s) || SKIP.contains(m.asLong())) continue;
                    BlockState below = level.getBlockState(m.below());
                    if (!below.is(BlockTags.DIRT) && !below.is(BlockTags.NYLIUM)) continue;
                    bases.add(m.immutable());
                }
        Set<Long> seen = new HashSet<>();
        bases.sort(Comparator.comparingDouble(b -> b.distToCenterSqr(p.position())));
        for (BlockPos base : bases) {
            if (seen.contains(base.asLong())) continue;
            List<BlockPos> tree = collectTree(level, base);
            for (BlockPos b : tree) seen.add(b.asLong());
            if (tree.isEmpty()) {
                SKIP.add(base.asLong());
                continue;
            }
            return tree;
        }
        return null;
    }

    /** 从树根顺着同种原木找整棵树（含斜着连的枝杈）；不像天然树就返回空 */
    private static List<BlockPos> collectTree(ClientLevel level, BlockPos base) {
        Block wood = level.getBlockState(base).getBlock();
        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(base);
        seen.add(base.asLong());
        int natural = 0, persistent = 0;
        while (!q.isEmpty()) {
            BlockPos c = q.poll();
            out.add(c);
            if (out.size() > 400) return List.of(); // 太大，不像一棵树
            for (Direction d : Direction.values()) {
                BlockState n = level.getBlockState(c.relative(d));
                if (naturalLeaf(n)) natural++;
                else if (n.getBlock() instanceof LeavesBlock) persistent++;
                if (com.autobuilder.ABConfig.PROTECT_BUILDINGS.get() && buildingBlock(n)) return List.of();
            }
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        BlockPos n = c.offset(dx, dy, dz);
                        if (seen.contains(n.asLong())) continue;
                        if (n.getY() < base.getY() || Math.abs(n.getX() - base.getX()) > 6 || Math.abs(n.getZ() - base.getZ()) > 6) continue;
                        if (level.getBlockState(n).getBlock() != wood) continue;
                        seen.add(n.asLong());
                        q.add(n);
                    }
        }
        if (natural < 3 || persistent > natural) return List.of();
        return out;
    }

    /** 原木对应的树苗（红树、竹子之类的不补种） */
    private static Block saplingFor(Block log) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(log);
        String path = id.getPath();
        String sp = path.endsWith("_log") ? path.substring(0, path.length() - 4) + "_sapling"
                : path.endsWith("_stem") ? path.substring(0, path.length() - 5) + "_fungus" : null;
        if (sp == null || path.startsWith("mangrove")) return null;
        ResourceLocation sid = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), sp);
        return BuiltInRegistries.BLOCK.containsKey(sid) ? BuiltInRegistries.BLOCK.get(sid) : null;
    }

    // ---------------- 找矿（按已加载区块里的矿找过去） ----------------

    private static boolean wantOre(BlockState s) {
        if (!isOre(s)) return false;
        if (oreKind.equals("all")) return true;
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().contains(oreKind);
    }

    /** 上一条矿脉挖完了：数一数挖掉了几块，挖不到的记下来不再找 */
    private static void countVein() {
        if (currentVein == null) return;
        ClientLevel level = MC.level;
        for (BlockPos b : currentVein) {
            if (level != null && !wantOre(level.getBlockState(b))) oreMined++;
            else SKIP.add(b.asLong());
        }
        currentVein = null;
    }

    private static void nextVein() {
        LocalPlayer p = MC.player;
        ClientLevel level = MC.level;
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y0 = Math.max(level.getMinBuildHeight(), home.getY() - radius), y1 = Math.min(level.getMaxBuildHeight() - 1, home.getY() + radius);
        for (int y = y0; y <= y1; y++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dx = -radius; dx <= radius; dx++) {
                    m.set(home.getX() + dx, y, home.getZ() + dz);
                    if (!level.isLoaded(m) || SKIP.contains(m.asLong())) continue;
                    if (wantOre(level.getBlockState(m))) found.add(m.immutable());
                }
        if (found.isEmpty()) {
            end(Lang.t("gather.ore_done", radius, oreLabel(), oreMined), true);
            return;
        }
        BlockPos start = found.stream().min(Comparator.comparingDouble(b -> b.distToCenterSqr(p.position()))).get();
        // 顺着相连的同类矿找整条矿脉
        List<BlockPos> vein = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start.asLong());
        while (!q.isEmpty() && vein.size() < 64) {
            BlockPos c = q.poll();
            vein.add(c);
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (seen.add(n.asLong()) && !SKIP.contains(n.asLong()) && wantOre(level.getBlockState(n))) q.add(n);
            }
        }
        currentVein = vein;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos b : vein) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }
        Blueprint bp = new Blueprint(Lang.t("gather.ore_name", oreLabel(), oreMined), maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos b : vein) bp.set(b.getX() - minX, b.getY() - minY, b.getZ() - minZ, air);
        run(new Placement(bp, new BlockPos(minX, minY, minZ)));
    }

    // ---------------- 挖矿 ----------------

    /**
     * 支巷挖矿：从脚下往面朝方向挖 1×2 的主巷，每隔 3 格向左右各挖一条支巷。
     * 蓝图范围四周多留 4 格，露出来的矿可以顺着矿脉加进去。
     */
    private static Placement tunnelPlacement(BlockPos feet, Direction f, int length, int branch) {
        Direction r = f.getClockWise();
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 1; i <= length; i++) {
            BlockPos c = feet.relative(f, i);
            cells.add(c);
            cells.add(c.above());
            if (i % 4 == 0) {
                for (Direction side : new Direction[] {r, r.getOpposite()}) {
                    for (int j = 1; j <= branch; j++) {
                        BlockPos b = c.relative(side, j);
                        cells.add(b);
                        cells.add(b.above());
                    }
                }
            }
        }
        int margin = 4;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos b : cells) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }
        BlockPos o = new BlockPos(minX - margin, minY - margin, minZ - margin);
        Blueprint bp = new Blueprint(Lang.t("gather.mine_name"), maxX - minX + 1 + 2 * margin, maxY - minY + 1 + 2 * margin, maxZ - minZ + 1 + 2 * margin);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos b : cells) bp.set(b.getX() - o.getX(), b.getY() - o.getY(), b.getZ() - o.getZ(), air);
        OPEN.addAll(cells);
        tunnelCells = cells.size();
        return new Placement(bp, o);
    }

    private static boolean isOre(BlockState s) {
        return s.is(Tags.Blocks.ORES);
    }

    /** 巷道（和挖掉的矿）已经挖开的格子旁边露出来的矿：加进蓝图，顺着矿脉一块块挖 */
    private static void exposeOres(Placement pl) {
        ClientLevel level = MC.level;
        List<BlockPos> add = new ArrayList<>();
        for (BlockPos c : OPEN) {
            if (!level.getBlockState(c).isAir()) continue; // 还没挖开，看不见旁边
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (ORES.contains(n.asLong()) || !pl.contains(n) || pl.targetAt(n) != null) continue;
                if (isOre(level.getBlockState(n))) add.add(n);
            }
        }
        if (add.isEmpty()) return;
        BlockPos o = pl.origin();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos n : add) {
            if (!ORES.add(n.asLong())) continue;
            pl.bp.set(n.getX() - o.getX(), n.getY() - o.getY(), n.getZ() - o.getZ(), air);
            OPEN.add(n);
        }
        pl.touch();
    }

    private static int minedOres() {
        ClientLevel level = MC.level;
        if (level == null) return ORES.size();
        int n = 0;
        for (long l : ORES) if (level.getBlockState(BlockPos.of(l)).isAir()) n++;
        return n;
    }

    private static boolean hasPickaxe(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(net.minecraft.tags.ItemTags.PICKAXES) && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 2)) return true;
        }
        return false;
    }

    // ---------------- 背包 ----------------

    /** 挖矿挖出来一大堆没用的石头：这些算杂物，背包满了就扔（留两组垫脚） */
    private static boolean junk(Item it) {
        return com.autobuilder.ABConfig.items(com.autobuilder.ABConfig.JUNK_ITEMS).contains(it);
    }

    private static int scaffoldCount(LocalPlayer p) {
        int n = 0;
        for (Item it : ScaffoldItems.items()) n += InventoryHelper.count(p, it);
        return n;
    }

    /** 砍树/挖矿时地上的东西捡不捡：杂物只在垫脚方块不够时捡 */
    public static boolean wanted(LocalPlayer p, Item it) {
        if (!junk(it)) return true;
        if (com.autobuilder.ABConfig.PICKUP_JUNK.get() && Restocker.depositUsable()) return true; // 有存放箱：杂物也捡，回头存进去
        return ScaffoldItems.items().contains(it) && scaffoldCount(p) < com.autobuilder.ABConfig.KEEP_SCAFFOLD_DROP.get();
    }

    private static int freeSlots(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    /** 背包快满了：扔掉多余的杂物，至少空出 2 格；扔完还是满的返回 false */
    private static boolean makeRoom(LocalPlayer p) {
        if (freeSlots(p) >= 2) return true;
        if (!InventoryHelper.canSwap(p)) return true;
        int keep = com.autobuilder.ABConfig.KEEP_SCAFFOLD_DROP.get();
        // 先扔不能垫脚的杂物，再扔多出来的垫脚方块；从背包里面往外扔，快捷栏最后
        for (int pass = 0; pass < 2 && freeSlots(p) < 3; pass++) {
            for (int i = 35; i >= 0 && freeSlots(p) < 3; i--) {
                ItemStack s = p.getInventory().getItem(i);
                if (s.isEmpty() || !junk(s.getItem())) continue;
                boolean sc = ScaffoldItems.items().contains(s.getItem()) && s.getItem() instanceof BlockItem;
                if (pass == 0 && sc) continue;
                if (pass == 1 && scaffoldCount(p) - s.getCount() < keep) continue;
                int menuSlot = i < 9 ? 36 + i : i;
                MC.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot, 1, ClickType.THROW, p);
            }
        }
        return freeSlots(p) >= 1;
    }
}
