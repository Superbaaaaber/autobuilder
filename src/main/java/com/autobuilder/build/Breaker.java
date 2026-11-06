package com.autobuilder.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 发包挖方块，不需要准星对着方块。
 * 原版客户端在没按左键时每 tick 都会调用 stopDestroyBlock()，
 * 所以这里不用 MultiPlayerGameMode 的挖掘状态，自己累计进度、自己发 START/STOP。
 */
public final class Breaker {
    private BlockPos pos;
    private Direction face = Direction.UP;
    private float progress;
    private boolean started;
    private int ticks;

    public boolean active() {
        return pos != null;
    }

    public BlockPos pos() {
        return pos;
    }

    public void start(BlockPos p) {
        pos = p.immutable();
        progress = 0;
        started = false;
        ticks = 0;
    }

    public void cancel(Minecraft mc) {
        if (pos != null && started && mc.player != null) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, face));
            mc.level.destroyBlockProgress(mc.player.getId(), pos, -1);
        }
        pos = null;
    }

    private void finish(Minecraft mc) {
        if (pos != null && mc.level != null && mc.player != null) mc.level.destroyBlockProgress(mc.player.getId(), pos, -1);
        pos = null;
    }

    /** 每 tick 调用一次；挖完或放弃后 active() 变为 false */
    public void tick(Minecraft mc, double reach) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (pos == null || p == null) return;
        BlockState st = level.getBlockState(pos);
        if (StateMatcher.isSoft(st)) {
            finish(mc);
            return;
        }
        Vec3 eye = p.getEyePosition();
        if (Geometry.distToBlock(eye, pos) > reach + 0.4 || ++ticks > 600) {
            cancel(mc);
            return;
        }
        if (!started) {
            InventoryHelper.selectBestTool(mc, st);
            Vec3 c = Vec3.atCenterOf(pos);
            face = Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
            float f = st.getDestroyProgress(p, level, pos);
            started = true;
            boolean insta = f >= 1.0f;
            send(mc, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, insta);
            p.swing(InteractionHand.MAIN_HAND);
            if (insta) {
                finish(mc);
                return;
            }
            progress = f;
        } else {
            progress += st.getDestroyProgress(p, level, pos);
            if (ticks % 4 == 0) p.swing(InteractionHand.MAIN_HAND);
            if (progress >= 1.0f) {
                send(mc, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, true);
                finish(mc);
                return;
            }
        }
        level.destroyBlockProgress(p.getId(), pos, (int) (Math.min(progress, 1f) * 10f) - 1);
    }

    private void send(Minecraft mc, ServerboundPlayerActionPacket.Action action, boolean destroyNow) {
        ClientLevel level = mc.level;
        try (BlockStatePredictionHandler h = level.getBlockStatePredictionHandler().startPredicting()) {
            int seq = h.currentSequence();
            if (destroyNow) mc.gameMode.destroyBlock(pos);
            mc.player.connection.send(new ServerboundPlayerActionPacket(action, pos, face, seq));
        }
    }
}
