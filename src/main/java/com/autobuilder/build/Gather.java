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
 * 挖矿只挖巷道挖开后露出来的矿（顺着矿脉挖），不去挖藏在石头里看不见的矿——那等于透视，服务器一般算作弊。
 */
public final class Gather {
    public enum Kind { CHOP, MINE }

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
    /** 背包满了也不扔的杂物数量（留着垫脚） */
    private static final int KEEP_SCAFFOLD = 128;

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
        kind = Kind.MINE;
        active = true;
        ORES.clear();
        OPEN.clear();
        Placement pl = tunnelPlacement(p.blockPosition(), p.getDirection(), length, branch);
        BuildController.msg(Lang.t("gather.mine_start", Lang.t("dir." + p.getDirection().getName()), length, branch, p.blockPosition().getY()), ChatFormatting.GREEN);
        run(pl);
    }

    /** 用户停止/卸载时调用：不再安排下一个，临时蓝图交给调用者处理 */
    public static void cancel() {
        active = false;
        currentBase = null;
    }

    private static void end(String text) {
        active = false;
        BuildController.endTemporary();
        BuildController.msg(text, ChatFormatting.GREEN);
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
        if (st == BuildController.State.IDLE) {
            // 用户 /ab stop
            end(kind == Kind.CHOP ? Lang.t("gather.chop_stopped", trees) : Lang.t("gather.mine_stopped", minedOres()));
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
            } else {
                end(Lang.t("gather.mine_done", tunnelCells, minedOres()));
            }
            return;
        }
        // RUNNING
        if (ticks % 20 == 0 && !makeRoom(p)) {
            BuildController.pause(Lang.t("reason.inv_full"));
            return;
        }
        if (kind == Kind.MINE) {
            if (!hasPickaxe(p)) {
                BuildController.pause(Lang.t("reason.no_pickaxe"));
                return;
            }
            if (ticks % 10 == 0) exposeOres(pl);
        }
    }

    // ---------------- 砍树 ----------------

    private static void nextTree() {
        LocalPlayer p = MC.player;
        ClientLevel level = MC.level;
        List<BlockPos> tree = findTree(level, p);
        if (tree == null) {
            end(Lang.t("gather.chop_done", radius, trees));
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
                if (buildingBlock(n)) return List.of();
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
        return it == Items.COBBLESTONE || it == Items.COBBLED_DEEPSLATE || it == Items.DIRT || it == Items.GRAVEL
                || it == Items.ANDESITE || it == Items.DIORITE || it == Items.GRANITE || it == Items.TUFF || it == Items.CALCITE
                || it == Items.NETHERRACK || it == Items.DRIPSTONE_BLOCK || it == Items.POINTED_DRIPSTONE || it == Items.COARSE_DIRT
                || it == Items.ROOTED_DIRT || it == Items.STONE || it == Items.DEEPSLATE;
    }

    private static int scaffoldCount(LocalPlayer p) {
        int n = 0;
        for (Item it : ScaffoldItems.items()) n += InventoryHelper.count(p, it);
        return n;
    }

    /** 砍树/挖矿时地上的东西捡不捡：杂物只在垫脚方块不够时捡 */
    public static boolean wanted(LocalPlayer p, Item it) {
        if (!junk(it)) return true;
        return ScaffoldItems.items().contains(it) && scaffoldCount(p) < KEEP_SCAFFOLD;
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
        int keep = KEEP_SCAFFOLD;
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
