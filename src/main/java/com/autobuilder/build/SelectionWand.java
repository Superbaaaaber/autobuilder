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
 * 点击在客户端就被拦下，不会真的挖方块、打开箱子或开门。
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
}
