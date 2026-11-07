package com.autobuilder.build;

import com.autobuilder.ABConfig;
import com.autobuilder.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 选区木棍：主手拿着指定物品（默认木棍），左键点方块设角 1，右键设角 2，最远 128 格。
 * Shift+右键打开快捷菜单（按对着的东西：材料箱、替换这种方块、蓝图和选区操作），Shift+左键把蓝图挪到对着的地方，
 * Shift+滚轮沿面朝方向平移蓝图。点击在客户端就被拦下，不会真的挖方块、打开箱子或开门。
 */
public final class SelectionWand {
    public static final double RANGE = 128;

    private SelectionWand() {}

    public static Item item() {
        ResourceLocation id = ResourceLocation.tryParse(ABConfig.WAND_ITEM.get());
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    public static boolean holding(LocalPlayer p) {
        if (!ABConfig.SELECTION_WAND.get()) return false;
        Item it = item();
        return it != Items.AIR && p.getMainHandItem().is(it);
    }

    /** 处理一次左/右键；返回 true 表示被木棍用掉了，原版的挖/用要取消 */
    public static boolean click(boolean left) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null || !holding(p)) return false;
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            BlockHitResult real = realHit(mc, p);
            if (!left) {
                // 快捷菜单：投影里还没放的方块也能对着点
                mc.setScreen(new com.autobuilder.gui.WandMenuScreen(aimed(mc, p), real == null ? null : real.getBlockPos().relative(real.getDirection())));
            } else if (real == null) {
                mc.gui.setOverlayMessage(Component.literal(Lang.t("wand.no_block", (int) RANGE)).withStyle(ChatFormatting.RED), false);
            } else {
                moveBlueprintTo(real.getBlockPos().relative(real.getDirection()));
            }
            return true;
        }
        HitResult hit = p.pick(RANGE, mc.getTimer().getGameTimeDeltaPartialTick(true), false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
            mc.gui.setOverlayMessage(Component.literal(Lang.t("wand.no_block", (int) RANGE)).withStyle(ChatFormatting.RED), false);
            return true;
        }
        int which = left ? 1 : 2;
        BlockPos pos = bh.getBlockPos();
        BlockPos old = which == 1 ? Selection.pos1() : Selection.pos2();
        Selection.set(which, pos);
        String text = Lang.t("msg.corner", which, pos.toShortString(), Selection.describe());
        mc.gui.setOverlayMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW), false);
        // 按住不放会连续触发：只有角变了才往聊天栏记一条
        if (!pos.equals(old)) BuildController.msg(text, ChatFormatting.YELLOW);
        return true;
    }

    private static BlockHitResult realHit(Minecraft mc, LocalPlayer p) {
        HitResult hit = p.pick(RANGE, mc.getTimer().getGameTimeDeltaPartialTick(true), false);
        return hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK ? bh : null;
    }

    /** 准星对着的格子：真实方块，或者更近的蓝图格子（投影里的半透明方块） */
    public static BlockPos aimed(Minecraft mc, LocalPlayer p) {
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(true);
        BlockHitResult real = realHit(mc, p);
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition(pt), look = p.getViewVector(pt);
        double maxD = real == null ? RANGE : real.getLocation().distanceTo(eye) + 0.01;
        Placement pl = BuildController.placement();
        if (pl != null) {
            BlockPos last = null;
            for (double d = 0; d < maxD; d += 0.05) {
                BlockPos c = BlockPos.containing(eye.add(look.scale(d)));
                if (c.equals(last)) continue;
                last = c;
                if (!pl.contains(c)) continue;
                var o = pl.originalAt(c);
                if (o != null && !o.isAir()) return c;
            }
        }
        return real == null ? null : real.getBlockPos();
    }

    /** 把蓝图挪到 at（底面中心对准这格）；正在建造时不挪 */
    public static boolean moveBlueprintTo(BlockPos at) {
        Minecraft mc = Minecraft.getInstance();
        Placement pl = BuildController.placement();
        if (pl == null) {
            mc.gui.setOverlayMessage(Component.literal(Lang.t("msg.no_blueprint")).withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (BuildController.state() == BuildController.State.RUNNING) {
            mc.gui.setOverlayMessage(Component.literal(Lang.t("wand.busy")).withStyle(ChatFormatting.RED), false);
            return false;
        }
        pl.setOrigin(at.offset(-pl.sizeX() / 2, 0, -pl.sizeZ() / 2));
        mc.gui.setOverlayMessage(Component.literal(Lang.t("wand.moved", pl.origin().toShortString())).withStyle(ChatFormatting.AQUA), false);
        return true;
    }

    /** Shift+滚轮：往上滚沿视线推远一格（平视时水平，抬头/低头时上下），往下滚拉近；返回 true 表示用掉了这次滚轮 */
    public static boolean scroll(double delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || delta == 0 || !holding(p) || !net.minecraft.client.gui.screens.Screen.hasShiftDown()) return false;
        Placement pl = BuildController.placement();
        if (pl == null) return false;
        if (BuildController.state() == BuildController.State.RUNNING) {
            mc.gui.setOverlayMessage(Component.literal(Lang.t("wand.busy")).withStyle(ChatFormatting.RED), true);
            return true;
        }
        int s = delta > 0 ? 1 : -1;
        float pitch = p.getXRot();
        if (pitch < -45) pl.move(0, s, 0);
        else if (pitch > 45) pl.move(0, -s, 0);
        else {
            net.minecraft.core.Direction d = p.getDirection();
            pl.move(d.getStepX() * s, 0, d.getStepZ() * s);
        }
        mc.gui.setOverlayMessage(Component.literal(Lang.t("wand.nudged", pl.origin().toShortString())).withStyle(ChatFormatting.AQUA), false);
        return true;
    }
}
