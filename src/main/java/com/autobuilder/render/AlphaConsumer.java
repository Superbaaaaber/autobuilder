package com.autobuilder.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** 把顶点颜色的透明度乘一个系数，用来画半透明的“幽灵方块” */
final class AlphaConsumer implements VertexConsumer {
    private final VertexConsumer d;
    private final float alpha;

    AlphaConsumer(VertexConsumer d, float alpha) {
        this.d = d;
        this.alpha = alpha;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        d.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        d.setColor(r, g, b, (int) (a * alpha));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        d.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        d.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        d.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        d.setNormal(x, y, z);
        return this;
    }
}
