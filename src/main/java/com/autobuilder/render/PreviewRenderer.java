package com.autobuilder.render;

import com.autobuilder.ABConfig;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Scanner;
import com.autobuilder.build.StateMatcher;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/** 世界里的预览：包围盒、半透明待放方块、红框（要挖）、紫框（放错且不挖）、黄框（要调整）、橙框（脚手架） */
public final class PreviewRenderer {
    private static final MultiBufferSource.BufferSource GHOST_BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
    private static final int MAX_GHOSTS = 4000;

    /** 手动隐藏投影（按 H 或主界面按钮切换） */
    private static boolean hidden;

    private PreviewRenderer() {}

    /** 现在要不要隐藏投影：手动隐藏了，或者设置了“建造时隐藏”且正在建造 */
    public static boolean hidden() {
        return hidden || ABConfig.HIDE_WHILE_BUILDING.get() && BuildController.state() == BuildController.State.RUNNING;
    }

    public static boolean manuallyHidden() {
        return hidden;
    }

    public static void toggleHidden() {
        hidden = !hidden;
    }

    private static void renderSelection(RenderLevelStageEvent e, Minecraft mc, Vec3 cam) {
        BlockPos a = com.autobuilder.build.Selection.pos1(), b = com.autobuilder.build.Selection.pos2();
        if (a == null && b == null) return;
        PoseStack ps = e.getPoseStack() != null ? e.getPoseStack() : new PoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        if (a != null) LevelRenderer.renderLineBox(ps, lines, new AABB(a).inflate(0.02), 1f, 0.3f, 0.3f, 1f);
        if (b != null) LevelRenderer.renderLineBox(ps, lines, new AABB(b).inflate(0.02), 0.3f, 0.5f, 1f, 1f);
        AABB box = com.autobuilder.build.Selection.box();
        if (box != null) LevelRenderer.renderLineBox(ps, lines, box.inflate(0.01), 1f, 0.9f, 0.2f, 1f);
        buffers.endBatch(RenderType.lines());
        ps.popPose();
    }

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Placement pl = BuildController.placement();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = e.getCamera().getPosition();
        renderSelection(e, mc, cam);
        if (pl == null || hidden()) return;
        PoseStack ps = e.getPoseStack() != null ? e.getPoseStack() : new PoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(ps, lines, pl.box().inflate(0.01), 0.3f, 0.85f, 1f, 1f);
        Scanner.Snapshot s = BuildController.snapshot();
        double range = ABConfig.RENDER_RANGE.get();
        double r2 = range * range;
        if (s != null) {
            for (BlockPos b : s.breaks) {
                if (b.distToCenterSqr(cam) > r2) continue;
                LevelRenderer.renderLineBox(ps, lines, new AABB(b).inflate(0.004), 1f, 0.25f, 0.25f, 1f);
            }
            for (BlockPos b : s.wrong) {
                if (b.distToCenterSqr(cam) > r2) continue;
                LevelRenderer.renderLineBox(ps, lines, new AABB(b).inflate(0.004), 1f, 0.1f, 0.8f, 1f);
            }
            for (BlockPos b : s.interact) {
                if (b.distToCenterSqr(cam) > r2) continue;
                LevelRenderer.renderLineBox(ps, lines, new AABB(b).inflate(0.004), 1f, 0.9f, 0.2f, 1f);
            }
            // 要抽水的格子：青色框
            for (BlockPos b : s.drain) {
                if (b.distToCenterSqr(cam) > r2) continue;
                LevelRenderer.renderLineBox(ps, lines, new AABB(b).deflate(0.15), 0.2f, 0.9f, 0.9f, 1f);
            }
        }
        for (BlockPos b : BuildController.scaffold()) {
            if (b.distToCenterSqr(cam) > r2) continue;
            LevelRenderer.renderLineBox(ps, lines, new AABB(b).inflate(0.006), 1f, 0.55f, 0.1f, 1f);
        }
        buffers.endBatch(RenderType.lines());

        if (ABConfig.RENDER_GHOSTS.get() && s != null) {
            BlockRenderDispatcher br = mc.getBlockRenderer();
            MultiBufferSource ghost = type -> new AlphaConsumer(GHOST_BUFFERS.getBuffer(type), 0.45f);
            RenderType rt = Sheets.translucentCullBlockSheet();
            int n = 0;
            VertexConsumer outline = null;
            for (BlockPos b : s.place) {
                if (b.distToCenterSqr(cam) > r2) continue;
                if (n++ > MAX_GHOSTS) break;
                BlockState t = pl.targetAt(b);
                if (t == null) continue;
                BlockState w = mc.level.getBlockState(b);
                if (w.getBlock() == t.getBlock() || !StateMatcher.isSoft(w)) continue;
                if (t.getRenderShape() != RenderShape.MODEL) {
                    if (outline == null) outline = buffers.getBuffer(RenderType.lines());
                    LevelRenderer.renderLineBox(ps, outline, new AABB(b).deflate(0.1), 0.4f, 0.8f, 1f, 1f);
                    continue;
                }
                ps.pushPose();
                ps.translate(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                ps.scale(0.98f, 0.98f, 0.98f);
                ps.translate(-0.5, -0.5, -0.5);
                br.renderSingleBlock(t, ps, ghost, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, rt);
                ps.popPose();
            }
            GHOST_BUFFERS.endBatch();
            if (outline != null) buffers.endBatch(RenderType.lines());
        }
        ps.popPose();
    }
}
