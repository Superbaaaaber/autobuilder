package com.autobuilder.build;

import com.autobuilder.ABConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 饿了自动吃东西：选中食物后按住“使用”键，吃完松开 */
public final class AutoEat {
    private static boolean eating;
    private static int ticks;

    private AutoEat() {}

    private static boolean edible(ItemStack s) {
        if (s.isEmpty() || !s.has(DataComponents.FOOD)) return false;
        // 有负面效果或会乱传送的不吃
        return !(s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
                || s.is(Items.PUFFERFISH) || s.is(Items.CHORUS_FRUIT) || s.is(Items.SUSPICIOUS_STEW));
    }

    /** @return true 表示正在吃，这一 tick 不要做别的 */
    public static boolean tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (eating) {
            ticks++;
            if (!p.isUsingItem() || ticks > 80) {
                release(mc);
                return false;
            }
            return true;
        }
        if (!ABConfig.AUTO_EAT.get()) return false;
        if (p.getFoodData().getFoodLevel() >= ABConfig.EAT_BELOW.get()) return false;
        if (!InventoryHelper.select(mc, AutoEat::edible)) return false;
        if (mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction() && p.isUsingItem()) {
            mc.options.keyUse.setDown(true);
            eating = true;
            ticks = 0;
            return true;
        }
        return false;
    }

    public static void release(Minecraft mc) {
        if (eating) {
            mc.options.keyUse.setDown(false);
            eating = false;
        }
    }

    public static boolean eating() {
        return eating;
    }
}
