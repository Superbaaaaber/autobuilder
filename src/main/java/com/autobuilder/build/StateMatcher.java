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
    public enum Status { IGNORE, OK, PLACE, BREAK, INTERACT, WAIT, WRONG, UNSUPPORTED, FLUID, WATERLOG }

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
        Item item = target.getBlock().asItem();
        if (item == Items.AIR || !(item instanceof BlockItem)) return null;
        return item;
    }

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
            if (isSoft(world)) return Status.OK;
            if (!clearAir) return Status.OK;
            return breakable(world) ? Status.BREAK : Status.OK;
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
        if (target.hasProperty(BlockStateProperties.WATERLOGGED) && target.getValue(BlockStateProperties.WATERLOGGED)
                && world.hasProperty(BlockStateProperties.WATERLOGGED) && !world.getValue(BlockStateProperties.WATERLOGGED)) {
            return Status.WATERLOG;
        }
        return Status.OK;
    }

    /** 模拟放置的结果能否接受：完全符合、只差交互属性、或者数量往目标方向加了 1 */
    public static boolean acceptable(BlockState target, BlockState before, BlockState result) {
        if (result == null || result.getBlock() != target.getBlock()) return false;
        Status st = classify(target, result, true, true);
        if (st == Status.OK || st == Status.INTERACT || st == Status.WATERLOG) return true;
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
