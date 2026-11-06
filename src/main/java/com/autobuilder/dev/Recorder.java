package com.autobuilder.dev;

import com.autobuilder.AutoBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * 开发用录像：每帧从主画面缓冲读像素，按真实时间 30fps 交给 ffmpeg 编码（NVENC）。
 * 渲染慢于 30fps 时重复上一帧，保证视频时长等于真实时长。
 */
public final class Recorder {
    private static final double FPS = 30.0;
    private static final String FFMPEG = System.getenv().getOrDefault("AB_FFMPEG",
            "D:/Python310/lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v9.0.2.exe");

    private record Frame(ByteBuffer buf, int copies) {}

    private static final Frame END = new Frame(null, 0);

    private static Process proc;
    private static Thread writer;
    private static BlockingQueue<Frame> queue;
    private static BlockingQueue<ByteBuffer> free;
    private static int w, h;
    private static long startNanos, frames, dropped;
    private static String name;

    private Recorder() {}

    public static boolean active() {
        return proc != null;
    }

    public static void start(Path file) {
        if (proc != null) stop();
        RenderTarget t = Minecraft.getInstance().getMainRenderTarget();
        w = t.width;
        h = t.height;
        name = file.getFileName().toString();
        List<String> cmd = List.of(FFMPEG, "-y", "-loglevel", "warning",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-framerate", "30", "-i", "-",
                "-vf", "vflip,scale=1920:1080:flags=lanczos",
                "-c:v", "h264_nvenc", "-preset", "p6", "-rc", "vbr", "-cq", "17", "-b:v", "0",
                "-pix_fmt", "yuv420p", file.toString());
        try {
            File log = file.resolveSibling(name + ".ffmpeg.log").toFile();
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        } catch (IOException e) {
            AutoBuilder.LOG.error("启动 ffmpeg 失败", e);
            proc = null;
            return;
        }
        queue = new ArrayBlockingQueue<>(6);
        free = new ArrayBlockingQueue<>(6);
        for (int i = 0; i < 6; i++) free.add(BufferUtils.createByteBuffer(w * h * 4));
        frames = 0;
        dropped = 0;
        startNanos = System.nanoTime();
        Process p = proc;
        writer = new Thread(() -> {
            try (WritableByteChannel ch = Channels.newChannel(p.getOutputStream())) {
                while (true) {
                    Frame f = queue.take();
                    if (f == END) break;
                    for (int i = 0; i < f.copies(); i++) {
                        f.buf().rewind();
                        while (f.buf().hasRemaining()) ch.write(f.buf());
                    }
                    free.put(f.buf());
                }
            } catch (Exception e) {
                AutoBuilder.LOG.error("录像写入失败", e);
            }
        }, "AB-Recorder");
        writer.setDaemon(true);
        writer.start();
        AutoBuilder.LOG.info("[Recorder] 开始录制 {} {}x{}", name, w, h);
    }

    /** 每帧画完（RenderFrameEvent.Post）时调用 */
    public static void onFrame() {
        if (proc == null) return;
        long due = (long) ((System.nanoTime() - startNanos) / 1e9 * FPS) + 1;
        if (frames >= due) return;
        ByteBuffer buf = free.poll();
        if (buf == null) {
            dropped++;
            return; // 编码跟不上：这一帧留到下次补
        }
        buf.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        buf.rewind();
        int copies = (int) (due - frames);
        frames = due;
        queue.add(new Frame(buf, copies));
    }

    public static void stop() {
        if (proc == null) return;
        try {
            queue.put(END);
            writer.join(30000);
            proc.getOutputStream().close();
            proc.waitFor();
        } catch (Exception e) {
            AutoBuilder.LOG.error("结束录像失败", e);
        }
        AutoBuilder.LOG.info("[Recorder] 结束 {}：{} 帧（{} 秒），丢帧补齐 {} 次", name, frames, String.format("%.1f", frames / FPS), dropped);
        proc = null;
    }
}
