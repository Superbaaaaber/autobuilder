package com.autobuilder;

import com.autobuilder.blueprint.BlueprintManager;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Restocker;
import com.autobuilder.build.ScaffoldItems;
import com.autobuilder.build.StateMatcher;
import com.autobuilder.build.SupplyManager;
import com.autobuilder.path.PathFinder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 诊断日志：每次开始建造、砍树挖矿时在 autobuild/logs/ 下新开一个文件，记下当时的设置、蓝图，
 * 之后每一步做了什么（放、挖、寻路结果、走不通的原因、补货存货、暂停、出错），每秒一行状态。
 * 人和动作十几秒没变、或者一分钟没有进展时，自动记一份现场快照（周围方块简图、剩下的路线、黑名单等），
 * 玩家也可以用 /ab log mark 打标记。用来排查玩家实际游戏里遇到的问题。
 */
public final class Diag {
    private static final Minecraft MC = Minecraft.getInstance();
    /** 单个文件最多写这么多，超了只记快照和消息，不再记每一步 */
    private static final long MAX_BYTES = 40L << 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 快照里方块简图的半径 */
    private static final int R = 6;

    private static BufferedWriter out;
    private static Path file;
    private static Object sessionKey;
    private static long bytes;
    private static boolean capped;
    private static int ticks;
    private static String lastStatus = "";
    private static int lastStatusTick;
    private static String stuckSig = "";
    private static int stuckSince;
    private static boolean stuckDumped;
    private static String progSig = "";
    private static int progSince;
    private static boolean progDumped;

    private Diag() {}

    public static boolean enabled() {
        return ABConfig.DIAG_LOG.get();
    }

    public static Path dir() {
        return BlueprintManager.dir().resolve("logs");
    }

    /** 当前正在写的文件；没在写返回 null */
    public static Path file() {
        return out == null ? null : file;
    }

    // ---------------- 开关文件 ----------------

    /** 开始一段活（一次建造、一次砍树挖矿）：key 和上次一样（同一个蓝图暂停后继续）就接着写原来的文件 */
    public static void session(Object key, String what) {
        if (!enabled()) {
            close();
            return;
        }
        if (out != null && key == sessionKey) {
            note("继续：" + what);
            return;
        }
        open(what);
        sessionKey = key;
    }

    private static void open(String what) {
        close();
        try {
            Path d = dir();
            Files.createDirectories(d);
            prune(d, Math.max(1, ABConfig.DIAG_KEEP.get()) - 1);
            file = d.resolve("autobuilder-" + LocalDateTime.now().format(NAME) + ".log");
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            bytes = 0;
            capped = false;
            lastStatus = "";
            stuckSig = "";
            progSig = "";
            header(what);
        } catch (IOException e) {
            AutoBuilder.LOG.warn("打不开诊断日志", e);
            out = null;
        }
    }

    /** 只留最近 keep 个日志文件（文件名带时间，按名字排就是按时间排） */
    private static void prune(Path d, int keep) throws IOException {
        List<Path> logs;
        try (var s = Files.list(d)) {
            logs = s.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith("autobuilder-") && n.endsWith(".log");
            }).sorted().toList();
        }
        for (int i = 0; i < logs.size() - keep; i++) Files.deleteIfExists(logs.get(i));
    }

    public static void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
        sessionKey = null;
    }

    // ---------------- 写 ----------------

    /** 记一条（{} 依次换成参数）；自动测试打开调试时也打到游戏日志里 */
    public static void log(String fmt, Object... args) {
        String text = format(fmt, args);
        if (BuildController.debug) AutoBuilder.LOG.info("[AB] {}", text);
        line(text, false);
    }

    /** 只记进诊断日志（游戏日志里已经有了的，比如聊天栏消息） */
    public static void note(String text) {
        line(text, false);
    }

    private static String format(String fmt, Object... args) {
        if (args.length == 0) return fmt;
        StringBuilder b = new StringBuilder(fmt.length() + 32);
        int i = 0, a = 0;
        while (i < fmt.length()) {
            int j = fmt.indexOf("{}", i);
            if (j < 0 || a >= args.length) {
                b.append(fmt, i, fmt.length());
                break;
            }
            b.append(fmt, i, j).append(str(args[a++]));
            i = j + 2;
        }
        return b.toString();
    }

    private static String str(Object o) {
        if (o instanceof BlockPos p) return p.toShortString();
        return String.valueOf(o);
    }

    /** important：快照、消息这些超了大小也照样写 */
    private static void line(String text, boolean important) {
        if (out == null) return;
        if (capped && !important) return;
        try {
            String l = LocalDateTime.now().format(TIME) + " t" + BuildController.now() + " " + text + "\n";
            out.write(l);
            bytes += l.length() * 2L;
            if (!capped && bytes > MAX_BYTES) {
                capped = true;
                out.write("（日志太大了，后面只记消息和现场快照）\n");
            }
        } catch (IOException e) {
            close();
        }
    }

    private static void raw(String text) {
        if (out == null) return;
        try {
            out.write(text);
            out.write('\n');
            bytes += text.length() * 2L;
        } catch (IOException e) {
            close();
        }
    }

    private static void flush() {
        if (out == null) return;
        try {
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    // ---------------- 内容 ----------------

    private static void header(String what) {
        raw("==== AutoBuilder 诊断日志 ====");
        raw("开始时间 " + LocalDateTime.now() + "，内容：" + what);
        String ver = net.neoforged.fml.ModList.get().getModContainerById(AutoBuilder.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        raw("模组 " + ver + "，游戏 " + net.minecraft.SharedConstants.getCurrentVersion().getName()
                + "，" + (MC.getSingleplayerServer() != null ? "单人" : "多人") + "，世界 " + SupplyManager.worldKey());
        LocalPlayer p = MC.player;
        if (p != null && MC.level != null) raw("玩家 " + pos(p) + "，难度 " + MC.level.getDifficulty() + "，游戏模式 "
                + (MC.gameMode == null ? "?" : MC.gameMode.getPlayerMode()));
        Placement pl = BuildController.placement();
        if (pl != null) {
            raw("蓝图 " + pl.describe() + (pl.temporary ? "（砍树挖矿挖空的临时蓝图）" : "")
                    + (pl.replacements().isEmpty() ? "" : "，材料替换 " + pl.replacements()));
        }
        raw("材料箱 " + SupplyManager.list().stream().map(BlockPos::toShortString).toList()
                + "，存放箱 " + SupplyManager.dumps().stream().map(BlockPos::toShortString).toList());
        if (p != null) raw("背包 " + inventory(p));
        raw("设置：");
        dumpConfig(ABConfig.SPEC.getValues(), "");
        raw("快照里方块简图的符号：@ 人  o 路线落脚点  . 空气  ~ 水源  - 流动水  L 岩浆  # 实心  h 半格/不完整  , 花草火把这类没碰撞的"
                + "  S 自己垫的方块  + 蓝图要放还没放  x 蓝图要挖  = 蓝图已经放对  d 要抽的水  w 要倒液体");
        raw("==============================");
        flush();
    }

    private static void dumpConfig(com.electronwill.nightconfig.core.UnmodifiableConfig cfg, String prefix) {
        for (var e : cfg.valueMap().entrySet()) {
            Object v = e.getValue();
            if (v instanceof com.electronwill.nightconfig.core.UnmodifiableConfig sub) dumpConfig(sub, prefix + e.getKey() + ".");
            else if (v instanceof ModConfigSpec.ConfigValue<?> cv) raw("  " + prefix + e.getKey() + " = " + cv.get());
        }
    }

    private static String pos(LocalPlayer p) {
        return String.format("%.2f, %.2f, %.2f", p.getX(), p.getY(), p.getZ());
    }

    private static String inventory(LocalPlayer p) {
        StringBuilder b = new StringBuilder();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) {
                free++;
                continue;
            }
            b.append(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()).append('×').append(s.getCount()).append(' ');
        }
        return "空格 " + free + "：" + b;
    }

    private static String status(LocalPlayer p) {
        StringBuilder b = new StringBuilder();
        b.append(BuildController.state()).append('/').append(BuildController.phase()).append(" | ").append(BuildController.activity());
        b.append(" | 人 ").append(pos(p)).append(p.onGround() ? " 着地" : " 悬空");
        if (p.isInWater()) b.append(p.isUnderWater() ? " 头在水里" : " 在水里");
        if (p.isInLava()) b.append(" 在岩浆里");
        b.append(" 血").append((int) Math.ceil(p.getHealth())).append(" 饥").append(p.getFoodData().getFoodLevel());
        var s = BuildController.snapshot();
        if (s != null) b.append(" | ").append(s.percent()).append("% 待放 ").append(s.place.size()).append(" 待挖 ").append(s.breaks.size());
        b.append(" 脚手架 ").append(BuildController.scaffold().size());
        var w = BuildController.walker();
        if (w.hasPath()) b.append(" | ").append(w.describe()).append(" 去 ").append(w.goal() == null ? "-" : w.goal().toShortString());
        if (BuildController.breaker().active()) b.append(" | 正在挖 ").append(BuildController.breaker().pos().toShortString());
        if (Restocker.active()) b.append(" | ").append(Restocker.describe());
        return b.toString();
    }

    /** 每 tick：每秒一行状态（没变化就十秒一行）、卡住和没进展检测、定时落盘 */
    public static void tick() {
        if (out == null) return;
        if (!enabled()) {
            close();
            return;
        }
        ticks++;
        LocalPlayer p = MC.player;
        if (p == null || MC.level == null) {
            if (ticks % 20 == 0) flush();
            return;
        }
        var st = BuildController.state();
        boolean working = st == BuildController.State.RUNNING;
        if (ticks % 20 == 0) {
            if (working || st == BuildController.State.PAUSED) {
                String s = status(p);
                if (!s.equals(lastStatus) || ticks - lastStatusTick >= 200) {
                    line("状态 " + s, false);
                    lastStatus = s;
                    lastStatusTick = ticks;
                }
            }
            flush();
        }
        if (!working) {
            stuckSig = "";
            progSig = "";
            return;
        }
        // 卡住：所在格子、当前动作、路线走到第几步都没变
        String sig = p.blockPosition().toShortString() + "|" + BuildController.activity();
        if (!sig.equals(stuckSig)) {
            stuckSig = sig;
            stuckSince = ticks;
            stuckDumped = false;
        } else if (!stuckDumped && ticks - stuckSince >= ABConfig.DIAG_STUCK_SECONDS.get() * 20) {
            stuckDumped = true;
            snapshot("疑似卡住：" + ABConfig.DIAG_STUCK_SECONDS.get() + " 秒里位置和动作都没变");
        }
        // 没进展：待放、待挖、已完成的数一分钟都没变（一直在垫方块、来回走也算）；补货存货时不算
        var s = BuildController.snapshot();
        if (s != null) {
            String ps = BuildController.phase() == BuildController.Phase.CLEANUP ? "c" + BuildController.scaffold().size()
                    : s.ok + "|" + s.place.size() + "|" + s.breaks.size() + "|" + s.drain.size();
            if (!ps.equals(progSig) || Restocker.active()) {
                progSig = ps;
                progSince = ticks;
                progDumped = false;
            } else if (!progDumped && ticks - progSince >= 1200) {
                progDumped = true;
                snapshot("一分钟没有进展（待放、待挖的数都没变）");
            }
        }
    }

    /** 现场快照：状态、内部计数、剩下的路线、黑名单、背包、周围方块简图 */
    public static void snapshot(String why) {
        if (out == null) return;
        line("######## 现场快照：" + why + " ########", true);
        LocalPlayer p = MC.player;
        ClientLevel level = MC.level;
        if (p == null || level == null) {
            flush();
            return;
        }
        raw("状态 " + status(p));
        raw(String.format("朝向 yaw %.1f pitch %.1f，速度 %.3f, %.3f, %.3f，氧气 %d，手持 %s", p.getYRot(), p.getXRot(),
                p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z, p.getAirSupply(),
                BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath()));
        StringBuilder sb = new StringBuilder();
        BuildController.diagState(sb);
        raw(sb.toString());
        raw("背包 " + inventory(p));
        blockMap(p, level);
        raw("########################################");
        flush();
    }

    private static void blockMap(LocalPlayer p, ClientLevel level) {
        BlockPos f = p.blockPosition();
        raw("周围方块（每层：从北往南一行，从西往东一列；左上角是 " + (f.getX() - R) + ", " + (f.getZ() - R) + "）");
        layers(level, f, f, 4, -3, null);
    }

    /** 从 center 往上 up 层、往下 down 层，每层画一张 (2R+1)×(2R+1) 的简图；mark 标成 * */
    private static void layers(ClientLevel level, BlockPos center, BlockPos feet, int up, int down, BlockPos mark) {
        Placement pl = BuildController.placement();
        Set<BlockPos> sc = BuildController.scaffold();
        Set<BlockPos> path = new HashSet<>();
        for (PathFinder.Step s : BuildController.walker().remainingSteps()) path.add(s.pos());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = up; dy >= down; dy--) {
            int y = center.getY() + dy;
            raw("y=" + y + (y == feet.getY() ? "（人的脚）" : y == feet.getY() + 1 ? "（人的头）" : ""));
            for (int dz = -R; dz <= R; dz++) {
                StringBuilder row = new StringBuilder("  ");
                for (int dx = -R; dx <= R; dx++) {
                    m.set(center.getX() + dx, y, center.getZ() + dz);
                    row.append(mark != null && m.equals(mark) ? '*' : cell(level, pl, sc, path, feet, m));
                }
                raw(row.toString());
            }
        }
    }

    private static final java.util.Map<Long, Integer> MAPPED = new java.util.HashMap<>();

    /** 某个位置（比如找不到路的目标）周围的方块简图；同一片地方一分钟最多记一次 */
    public static void mapAround(BlockPos center, String title) {
        if (out == null || capped || MC.level == null || MC.player == null) return;
        long key = BlockPos.asLong(center.getX() >> 3, center.getY() >> 3, center.getZ() >> 3);
        Integer last = MAPPED.get(key);
        if (last != null && ticks - last < 1200) return;
        MAPPED.put(key, ticks);
        line("—— " + title + "（* 是目标，@ 是人；左上角 " + (center.getX() - R) + ", " + (center.getZ() - R) + "）——", true);
        layers(MC.level, center, MC.player.blockPosition(), 3, -2, center);
        flush();
    }

    private static char cell(ClientLevel level, Placement pl, Set<BlockPos> sc, Set<BlockPos> path, BlockPos feet, BlockPos.MutableBlockPos m) {
        if (m.getX() == feet.getX() && m.getZ() == feet.getZ() && (m.getY() == feet.getY() || m.getY() == feet.getY() + 1)) return '@';
        BlockState w = level.getBlockState(m);
        if (path.contains(m) && w.getCollisionShape(level, m).isEmpty()) return 'o';
        if (sc.contains(m) && ScaffoldItems.isScaffold(w)) return 'S';
        BlockState t = pl == null ? null : pl.targetAt(m);
        if (t != null) {
            switch (StateMatcher.classify(t, w, true, true)) {
                case PLACE -> { return '+'; }
                case BREAK -> { return 'x'; }
                case DRAIN -> { return 'd'; }
                case FLUID, WATERLOG -> { return 'w'; }
                case OK -> {
                    if (!t.isAir()) return '=';
                }
                default -> {}
            }
        }
        if (w.getFluidState().is(FluidTags.LAVA)) return 'L';
        if (w.getFluidState().is(FluidTags.WATER)) return w.getFluidState().isSource() ? '~' : '-';
        if (w.isAir()) return '.';
        var shape = w.getCollisionShape(level, m);
        if (shape.isEmpty()) return ',';
        return w.isCollisionShapeFullBlock(level, m) ? '#' : 'h';
    }

    /** /ab log mark：玩家打的标记，顺便拍一份快照 */
    public static void mark(String text) {
        if (out == null) open("手动标记");
        line("======== 玩家标记：" + (text == null || text.isBlank() ? "（没写说明）" : text) + " ========", true);
        snapshot("玩家标记");
    }

    /** 出错：记下完整的异常堆栈 */
    public static void error(String what, Throwable t) {
        if (out == null) return;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        line("!!!!!!!! 出错：" + what + "\n" + sw, true);
        snapshot("出错之后");
    }
}
