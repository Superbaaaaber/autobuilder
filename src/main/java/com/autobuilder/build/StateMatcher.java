package com.autobuilder.build;

import java.util.Set;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 判断世界里的方块和蓝图要求是否一致，以及需要做什么。
 * 只比较“放置时决定”的属性（朝向、轴向、上下半等）；
 * 楼梯拐角、栅栏连接、含水、充能这类由周围环境自动决定的属性不比较。
 */
public final class StateMatcher {
    /**
     * TILL：先放了泥土，还要用锄头/铲子加工成耕地/土径；
     * DRAIN：蓝图是空气、世界里是液体源头，要先填掉（抽水）；
     * UNWATERLOG：方块对了但泡着水（放进水里时自动含水），要用空桶把水舀走。
     */
    public enum Status { IGNORE, OK, PLACE, BREAK, INTERACT, WAIT, WRONG, UNSUPPORTED, FLUID, WATERLOG, TILL, DRAIN, UNWATERLOG }

    /** 放置时决定、必须一致的属性 */
    private static final Set<Property<?>> PLACEMENT = Set.of(
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.VERTICAL_DIRECTION, BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.HALF, BlockStateProperties.SLAB_TYPE, BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.DOOR_HINGE, BlockStateProperties.ROTATION_16, BlockStateProperties.ORIENTATION,
            BlockStateProperties.HANGING, BlockStateProperties.BELL_ATTACHMENT, BlockStateProperties.DOUBLE_BLOCK_HALF,
            BlockStateProperties.BED_PART);

    /** 可以重复放置来增加数量的属性（蜡烛、海泡菜、海龟蛋、雪层、粉红色花簇） */
    private static final Set<Property<?>> COUNT = Set.of(
            BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS,
            BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT);

    private StateMatcher() {}

    /** 放置时需要手持的物品；拿不到（比如水、火、传送门）返回 null */
    public static Item itemFor(BlockState target) {
        // 草方块、耕地、土径生存里拿不到：放泥土（耕地/土径放完再用锄头/铲子加工）
        if (isDirtFamily(target)) return Items.DIRT;
        Item item = target.getBlock().asItem();
        if (item == Items.AIR || !(item instanceof BlockItem)) return null;
        return item;
    }

    /** 草方块、泥土、耕地、土径：都用泥土放 */
    public static boolean isDirtFamily(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH);
    }

    /** 实际要放下去的方块：泥土类都是先放泥土 */
    public static BlockState placeForm(BlockState target) {
        return isDirtFamily(target) ? Blocks.DIRT.defaultBlockState() : target;
    }

    /** 耕地要锄头、土径要铲子；不需要工具返回 null */
    public static java.util.function.Predicate<net.minecraft.world.item.ItemStack> toolFor(BlockState target) {
        if (target.is(Blocks.FARMLAND)) return s -> s.getItem() instanceof net.minecraft.world.item.HoeItem && usable(s);
        if (target.is(Blocks.DIRT_PATH)) return s -> s.getItem() instanceof net.minecraft.world.item.ShovelItem && usable(s);
        return null;
    }

    /** 缺工具时提示用的物品 */
    public static Item toolItem(BlockState target) {
        return target.is(Blocks.FARMLAND) ? Items.WOODEN_HOE : Items.WOODEN_SHOVEL;
    }

    private static boolean usable(net.minecraft.world.item.ItemStack s) {
        return !s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 1;
    }

    /** 泥土类方块的特殊判断；不属于这些情况返回 null，按普通方块处理 */
    private static Status classifyDirt(BlockState target, BlockState world) {
        if (!isDirtFamily(target)) return null;
        boolean dirtOrGrass = world.is(Blocks.DIRT) || world.is(Blocks.GRASS_BLOCK);
        if (target.is(Blocks.GRASS_BLOCK) || target.is(Blocks.DIRT)) {
            // 泥土挨着草会自己长草，草方块挖了掉泥土：两者互相算对，免得反复挖了放、放了挖
            return dirtOrGrass ? Status.OK : null;
        }
        if (world.getBlock() == target.getBlock()) return Status.OK; // 耕地湿度不比较
        return dirtOrGrass ? Status.TILL : null;
    }

    /** 建好后挡得住水的方块：整块、含水类（台阶/楼梯/栅栏等，流水进不去）、门、告示牌、梯子 */
    public static boolean watertight(BlockState t) {
        if (t == null || t.isAir() || t.getBlock() instanceof LiquidBlock) return false;
        var b = t.getBlock();
        if (b instanceof net.minecraft.world.level.block.DoorBlock || t.is(net.minecraft.tags.BlockTags.SIGNS) || t.is(Blocks.LADDER)) return true;
        if (t.hasProperty(BlockStateProperties.WATERLOGGED)) return true;
        return t.blocksMotion();
    }

    /** 抽水开关：蓝图是空气的地方有液体源头就填掉 */
    private static boolean drainOn() {
        return !drainBlocked && com.autobuilder.ABConfig.DRAIN_WATER.get();
    }

    /** 砍树/挖矿时不抽水（由 BuildController 每 tick 设置） */
    public static boolean drainBlocked;

    public static boolean isUnsupported(BlockState target) {
        if (target.getBlock() instanceof LiquidBlock) return bucketFor(target) == null;
        return itemFor(target) == null;
    }

    /** 液体源要用的桶（只支持水和岩浆） */
    public static Item bucketFor(BlockState target) {
        if (target.is(Blocks.WATER)) return Items.WATER_BUCKET;
        if (target.is(Blocks.LAVA)) return Items.LAVA_BUCKET;
        return null;
    }

    public static boolean isFluidSource(BlockState s) {
        return s.getBlock() instanceof LiquidBlock && s.getValue(LiquidBlock.LEVEL) == 0;
    }

    /** 放置顺序分层：0 普通方块，1 红石元件和 TNT，2 侦测器，4 液体/含水 */
    public static int tier(BlockState s) {
        var b = s.getBlock();
        if (b instanceof LiquidBlock) return 4;
        // TNT 常被侦测器盯着、被活塞推，要在侦测器之前放好
        if (b instanceof net.minecraft.world.level.block.TntBlock) return 1;
        if (b instanceof net.minecraft.world.level.block.ObserverBlock) return 2;
        if (s.isSignalSource() || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || b instanceof net.minecraft.world.level.block.NoteBlock || b instanceof net.minecraft.world.level.block.DispenserBlock
                || b instanceof net.minecraft.world.level.block.RedstoneLampBlock || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.RedStoneWireBlock) return 1;
        return 0;
    }

    /** 某个任务属于哪一层（含水任务算液体层） */
    public static int taskTier(BlockState target, Status st) {
        return st == Status.WATERLOG ? 4 : tier(target);
    }

    /** 不碍事的“软空气”：空气、草、水等可直接被替换的东西 */
    public static boolean isSoft(BlockState world) {
        return world.isAir() || world.canBeReplaced();
    }

    private static boolean isInteractProp(BlockState s, Property<?> p) {
        if (p == BlockStateProperties.NOTE || p == BlockStateProperties.DELAY
                || p == BlockStateProperties.MODE_COMPARATOR || p == BlockStateProperties.INVERTED) return true;
        if (p == BlockStateProperties.OPEN) {
            return !s.is(Blocks.IRON_DOOR) && !s.is(Blocks.IRON_TRAPDOOR);
        }
        return p == BlockStateProperties.POWERED && s.getBlock() instanceof LeverBlock;
    }

    /** 上半部分（门上半、高花上半、床头）由下半部分连带放置 */
    public static boolean isSecondaryPart(BlockState s) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        return s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    public static Status classify(BlockState target, BlockState world, boolean breakWrong, boolean clearAir) {
        if (target == null) return Status.IGNORE;
        if (target.getBlock() instanceof LiquidBlock) {
            if (!isFluidSource(target)) {
                // 流动的液体由源头自己流出来，只要别被实心方块挡住
                if (isSoft(world) || !world.getFluidState().isEmpty()) return Status.IGNORE;
                return clearAir && breakable(world) ? Status.BREAK : Status.IGNORE;
            }
            if (world.getFluidState().isSource() && world.getFluidState().getType().isSame(target.getFluidState().getType())
                    && world.getBlock() instanceof LiquidBlock) return Status.OK;
            if (bucketFor(target) == null) return Status.UNSUPPORTED;
            if (isSoft(world)) return Status.FLUID;
            return breakWrong && breakable(world) ? Status.BREAK : Status.WRONG;
        }
        if (target.isAir()) {
            // 抽水：空气位置上的水/岩浆源头先用方块填掉（流动的不用管，源头没了自己会干）
            if (clearAir && world.getBlock() instanceof LiquidBlock && world.getFluidState().isSource() && drainOn()) return Status.DRAIN;
            if (isSoft(world)) return Status.OK;
            if (!clearAir) return Status.OK;
            return breakable(world) ? Status.BREAK : Status.OK;
        }
        Status dirt = classifyDirt(target, world);
        if (dirt != null) return dirt;
        // 同类材料（别的木头、别的颜色）：当成同一种方块比属性
        if (world.getBlock() != target.getBlock() && Substitutes.same(target.getBlock(), world.getBlock())) {
            target = Substitutes.swap(target, world.getBlock());
        }
        if (world.getBlock() == target.getBlock()) {
            if (isSecondaryPart(target)) return Status.OK;
            return compareSameBlock(target, world, breakWrong);
        }
        if (isSecondaryPart(target)) {
            if (isSoft(world)) return Status.WAIT;
            return breakWrong && breakable(world) ? Status.BREAK : Status.WRONG;
        }
        if (isUnsupported(target)) return Status.UNSUPPORTED;
        if (isSoft(world)) return Status.PLACE;
        return breakWrong && breakable(world) ? Status.BREAK : Status.WRONG;
    }

    private static Status compareSameBlock(BlockState target, BlockState world, boolean breakWrong) {
        boolean needInteract = false;
        boolean needMore = false;
        for (Property<?> p : target.getProperties()) {
            if (!world.hasProperty(p)) continue;
            Object a = target.getValue(p), b = world.getValue(p);
            if (a.equals(b)) continue;
            if (PLACEMENT.contains(p)) {
                if (p == BlockStateProperties.SLAB_TYPE && a == SlabType.DOUBLE) {
                    needMore = true;
                    continue;
                }
                return breakWrong ? Status.BREAK : Status.WRONG;
            }
            if (COUNT.contains(p)) {
                IntegerProperty ip = (IntegerProperty) p;
                if (target.getValue(ip) > world.getValue(ip)) {
                    needMore = true;
                    continue;
                }
                return breakWrong ? Status.BREAK : Status.WRONG;
            }
            if (isInteractProp(target, p)) needInteract = true;
        }
        if (needMore) return Status.PLACE;
        if (needInteract) return Status.INTERACT;
        if (target.hasProperty(BlockStateProperties.WATERLOGGED) && world.hasProperty(BlockStateProperties.WATERLOGGED)) {
            boolean tw = target.getValue(BlockStateProperties.WATERLOGGED), ww = world.getValue(BlockStateProperties.WATERLOGGED);
            if (tw && !ww) return Status.WATERLOG;
            // 放进水里自动泡了水，蓝图里是干的：用空桶舀掉
            if (!tw && ww && drainOn()) return Status.UNWATERLOG;
        }
        return Status.OK;
    }

    /** 模拟放置的结果能否接受：完全符合、只差交互属性、或者数量往目标方向加了 1 */
    public static boolean acceptable(BlockState target, BlockState before, BlockState result) {
        target = placeForm(target);
        if (result == null || result.getBlock() != target.getBlock()) return false;
        Status st = classify(target, result, true, true);
        if (st == Status.OK || st == Status.INTERACT || st == Status.WATERLOG || st == Status.UNWATERLOG) return true;
        if (st == Status.PLACE && before.getBlock() == target.getBlock()) {
            // 叠加类：结果要比放之前更接近目标
            for (Property<?> p : target.getProperties()) {
                if (COUNT.contains(p) && before.hasProperty(p)) {
                    IntegerProperty ip = (IntegerProperty) p;
                    if (result.getValue(ip) > before.getValue(ip)) return true;
                }
            }
        }
        if (st == Status.PLACE && before.getBlock() != target.getBlock()) {
            // 第一次放：只要放置属性对、数量从 1 开始即可（例如目标 3 根蜡烛，先放 1 根）
            for (Property<?> p : target.getProperties()) {
                if (PLACEMENT.contains(p) && p != BlockStateProperties.SLAB_TYPE
                        && !target.getValue(p).equals(result.getValue(p))) return false;
            }
            if (target.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                return target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                        && result.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
            }
            return true;
        }
        return false;
    }

    public static boolean breakable(BlockState s) {
        if (s.isAir() || s.getBlock() instanceof LiquidBlock) return false;
        return s.getDestroySpeed(null, null) >= 0;
    }

    /** 这一格还需要放几个物品（双层台阶 2 个、蜡烛按差值） */
    public static int itemsNeeded(BlockState target, BlockState world) {
        int n = 1;
        if (target.hasProperty(BlockStateProperties.SLAB_TYPE) && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            n = world.getBlock() == target.getBlock() ? 1 : 2;
        }
        for (Property<?> p : target.getProperties()) {
            if (COUNT.contains(p) && p != BlockStateProperties.LAYERS) {
                IntegerProperty ip = (IntegerProperty) p;
                int have = world.getBlock() == target.getBlock() ? world.getValue(ip) : 0;
                n = Math.max(1, target.getValue(ip) - have);
            }
        }
        return n;
    }
}
