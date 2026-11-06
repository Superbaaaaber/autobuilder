package com.autobuilder.build;

import com.autobuilder.ABConfig;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** 背包操作：数物品、把需要的东西换到手上（和玩家手动按数字键交换一样走原版点击包） */
public final class InventoryHelper {
    private static int rr;

    private InventoryHelper() {}

    public static int count(LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    public static boolean canSwap(LocalPlayer p) {
        return p.containerMenu == p.inventoryMenu;
    }

    /** 把满足条件的物品放到主手。返回 false 表示背包里没有或现在没法换。 */
    public static boolean select(Minecraft mc, Predicate<ItemStack> pred) {
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();
        if (pred.test(inv.getSelected())) return true;
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.getItem(i))) {
                setSelected(mc, i);
                return true;
            }
        }
        if (!canSwap(p)) return false;
        for (int i = 9; i < 36; i++) {
            if (pred.test(inv.getItem(i))) {
                int h = pickWorkSlot(inv);
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, i, h, ClickType.SWAP, p);
                setSelected(mc, h);
                return true;
            }
        }
        return false;
    }

    public static boolean selectItem(Minecraft mc, Item item) {
        return select(mc, s -> s.is(item));
    }

    /** 切换快捷栏格子，并立刻告诉服务器（挖方块是自己发包，等不到原版下一 tick 再同步） */
    public static void setSelected(Minecraft mc, int slot) {
        Inventory inv = mc.player.getInventory();
        if (inv.selected != slot) {
            inv.selected = slot;
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static int pickWorkSlot(Inventory inv) {
        List<? extends Integer> slots = ABConfig.WORK_SLOTS.get();
        if (slots.isEmpty()) return 8;
        for (Integer s : slots) {
            if (inv.getItem(s).isEmpty()) return s;
        }
        // 尽量不把工具/食物换走：优先换方块类物品所在的格子
        for (Integer s : slots) {
            if (inv.getItem(s).getItem() instanceof BlockItem) return s;
        }
        rr = (rr + 1) % slots.size();
        return slots.get(rr);
    }

    /** 选挖这个方块最快的工具；没有比空手好的就不换 */
    public static void selectBestTool(Minecraft mc, BlockState state) {
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();
        int best = -1;
        float bestScore = score(inv.getSelected(), state);
        float hand = score(ItemStack.EMPTY, state);
        for (int i = 0; i < 36; i++) {
            float sc = score(inv.getItem(i), state);
            if (sc > bestScore + 1e-3f) {
                bestScore = sc;
                best = i;
            }
        }
        if (best < 0 || bestScore <= hand + 1e-3f) return;
        if (best < 9) {
            setSelected(mc, best);
        } else if (canSwap(p)) {
            int h = pickWorkSlot(inv);
            mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, best, h, ClickType.SWAP, p);
            setSelected(mc, h);
        }
    }

    private static float score(ItemStack s, BlockState state) {
        if (!s.isEmpty() && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2) return 0f;
        float speed = s.isEmpty() ? 1f : s.getDestroySpeed(state);
        if (state.requiresCorrectToolForDrops() && !s.isCorrectToolForDrops(state)) speed *= 0.3f;
        return speed;
    }

    /** 交互（调中继器之类）时手里最好不是方块，免得意外放出去 */
    public static void selectNonBlock(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (!(inv.getSelected().getItem() instanceof BlockItem)) return;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                setSelected(mc, i);
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (!(inv.getItem(i).getItem() instanceof BlockItem)) {
                setSelected(mc, i);
                return;
            }
        }
    }
}
