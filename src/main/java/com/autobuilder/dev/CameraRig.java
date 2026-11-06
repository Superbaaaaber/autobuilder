package com.autobuilder.dev;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 开发用电影镜头：绕建筑旋转，或固定在某处看着某点。
 * 只改渲染用的相机，玩家本身照常移动建造。依赖第三人称：
 * 先在 ComputeCameraAngles 里定朝向，再在第三人称拉远距离的事件里把相机放到指定位置、距离设为 0。
 */
public final class CameraRig {
    private enum Mode { OFF, ORBIT, FIXED }

    private static Mode mode = Mode.OFF;
    private static Vec3 center = Vec3.ZERO, fixedPos = Vec3.ZERO;
    private static double radius, height, startDeg, degPerSec;
    private static long t0;

    private CameraRig() {}

    /** 绕 center 水平转圈，相机高出 center height 格，始终看向 center */
    public static void orbit(Vec3 center, double radius, double height, double startDeg, double degPerSec) {
        CameraRig.center = center;
        CameraRig.radius = radius;
        CameraRig.height = height;
        CameraRig.startDeg = startDeg;
        CameraRig.degPerSec = degPerSec;
        t0 = System.nanoTime();
        mode = Mode.ORBIT;
        Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    public static void fixed(Vec3 pos, Vec3 lookAt) {
        fixedPos = pos;
        center = lookAt;
        mode = Mode.FIXED;
        Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    public static void off() {
        mode = Mode.OFF;
    }

    public static boolean on() {
        return mode != Mode.OFF;
    }

    private static Vec3 position() {
        if (mode == Mode.FIXED) return fixedPos;
        double a = Math.toRadians(startDeg + degPerSec * (System.nanoTime() - t0) / 1e9);
        return new Vec3(center.x + Math.sin(a) * radius, center.y + height, center.z + Math.cos(a) * radius);
    }

    @SubscribeEvent
    public static void onAngles(ViewportEvent.ComputeCameraAngles e) {
        if (mode == Mode.OFF) return;
        Vec3 p = position();
        double dx = center.x - p.x, dy = center.y - p.y, dz = center.z - p.z;
        e.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        e.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        e.setRoll(0);
    }

    @SubscribeEvent
    public static void onDistance(CalculateDetachedCameraDistanceEvent e) {
        if (mode == Mode.OFF) return;
        e.getCamera().setPosition(position());
        e.setDistance(0);
    }
}
