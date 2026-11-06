package com.autobuilder.dev;

import com.autobuilder.AutoBuilder;
import com.autobuilder.blueprint.Blueprint;
import com.autobuilder.blueprint.BlueprintManager;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.StateMatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 开发环境自动化测试（只有加了 -Dautobuilder.autotest=... 才启用）。
 * 格式：预设[,flat|normal][,seed=数字][,timeout=tick][,extra=额外脚本]
 * 预设可写 cabin / tower / yard，或蓝图文件名。
 */
public final class AutoTest {
    public static final String SPEC = System.getProperty("autobuilder.autotest", "");
    public static final boolean ENABLED = !SPEC.isEmpty();

    private static boolean created;
    private static int ticks;
    private static int endTick = -1;
    private static int shots;
    private static String presetName = "[内置]小木屋";
    private static boolean flat = true;
    private static long seed = 12345L;
    private static int timeout = 20 * 60 * 8;
    private static String extra = "";
    private static boolean adjacent;
    private static boolean supply;
    private static boolean resume;
    /** ui：只测界面（帮助、设置、主界面）并截图，不建造 */
    private static boolean ui;
    /** showcase：大窗口、清掉聊天和提示，拍项目页展示图（建造前预览、建造中、完工无 HUD） */
    private static boolean showcase;
    private static boolean midShot;
    private static int midShotTick = -1;
    private static int uiTicks;
    private static final java.util.List<BlockPos> BARRELS = new java.util.ArrayList<>();
    private static String lastSig = "";
    private static int lastChange;
    private static Path log;

    private AutoTest() {}

    static {
        for (String part : SPEC.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.equals("flat")) flat = true;
            else if (p.equals("normal")) flat = false;
            else if (p.startsWith("seed=")) seed = Long.parseLong(p.substring(5));
            else if (p.startsWith("timeout=")) timeout = Integer.parseInt(p.substring(8));
            else if (p.startsWith("extra=")) extra = p.substring(6);
            else if (p.equals("adjacent")) adjacent = true;
            else if (p.equals("supply")) supply = true;
            else if (p.equals("resume")) resume = true;
            else if (p.equals("ui")) ui = true;
            else if (p.equals("showcase")) showcase = true;
            else presetName = switch (p) {
                case "cabin" -> "[内置]小木屋";
                case "tower" -> "[内置]瞭望塔";
                case "yard" -> "[内置]围栏小院";
                default -> p;
            };
        }
    }

    /** 测试用蓝图：sealbox = 全封闭石盒（没有门窗），人从盒子正中间开始 */
    private static Blueprint testBlueprint() throws IOException {
        if (!presetName.equals("sealbox")) return BlueprintManager.load(presetName);
        Blueprint b = new Blueprint("sealbox", 7, 5, 7);
        BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int y = 0; y < 5; y++)
            for (int z = 0; z < 7; z++)
                for (int x = 0; x < 7; x++) {
                    boolean shell = y == 0 || y == 4 || x == 0 || x == 6 || z == 0 || z == 6;
                    b.set(x, y, z, shell ? stone : air);
                }
        return b;
    }

    private static void log(String s) {
        AutoBuilder.LOG.info("[AutoTest] {}", s);
        try {
            if (log == null) {
                log = Minecraft.getInstance().gameDirectory.toPath().resolve("autotest.log");
                Files.writeString(log, "", StandardCharsets.UTF_8);
            }
            Files.writeString(log, "[t=" + ticks + "] " + s + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static void cmd(Minecraft mc, String c) {
        mc.player.connection.sendCommand(c);
    }

    private static void shot(Minecraft mc, String tag) {
        String name = "abtest_" + (shots++) + "_" + tag + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), m -> {});
        log("截图 " + name);
    }

    private static void createWorld(Minecraft mc) {
        String name = "abtest";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(name);
        try (Stream<Path> w = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
        LevelSettings ls = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(),
                WorldDataConfiguration.DEFAULT);
        WorldOptions wo = new WorldOptions(seed, false, false);
        ResourceKey<WorldPreset> preset = flat ? WorldPresets.FLAT : WorldPresets.NORMAL;
        log("创建世界 flat=" + flat + " seed=" + seed + " 预设=" + presetName);
        mc.createWorldOpenFlows().createFreshLevel(name, ls, wo,
                ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(preset).value().createWorldDimensions(),
                mc.screen);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!created && mc.screen instanceof TitleScreen) {
            created = true;
            createWorld(mc);
            return;
        }
        if (ui && mc.player != null && mc.level != null) {
            uiStep(mc, ++uiTicks);
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null && mc.screen.isPauseScreen()) return;
        ticks++;
        try {
            step(mc);
        } catch (Exception ex) {
            log("异常 " + ex);
            AutoBuilder.LOG.error("AutoTest", ex);
        }
    }

    /** 让玩家（镜头）朝向蓝图中心 */
    private static void faceBuilding(Minecraft mc) {
        Placement pl = BuildController.placement();
        if (pl == null) return;
        double cx = pl.origin().getX() + pl.sizeX() / 2.0, cy = pl.origin().getY() + pl.sizeY() / 2.0, cz = pl.origin().getZ() + pl.sizeZ() / 2.0;
        var eye = mc.player.getEyePosition();
        double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    /** 拍展示图前清掉聊天和右上角提示 */
    private static void tidy(Minecraft mc) {
        mc.gui.getChat().clearMessages(false);
        mc.getToasts().clear();
    }

    /** 递归找界面里文字包含 text 的按钮 */
    private static net.minecraft.client.gui.components.AbstractButton findButton(net.minecraft.client.gui.components.events.ContainerEventHandler h, String text) {
        for (var c : h.children()) {
            if (c instanceof net.minecraft.client.gui.components.AbstractButton b && b.getMessage().getString().contains(text)) return b;
            if (c instanceof net.minecraft.client.gui.components.events.ContainerEventHandler sub) {
                var r = findButton(sub, text);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void uiStep(Minecraft mc, int t) {
        switch (t) {
            case 40 -> {
                cmd(mc, "ab help");
                mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
            }
            case 50 -> shot(mc, "help1");
            case 55 -> {
                mc.setScreen(null);
                mc.gui.getChat().clearMessages(false);
                cmd(mc, "ab help 2");
                mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
            }
            case 65 -> shot(mc, "help2");
            case 70 -> {
                mc.setScreen(null);
                cmd(mc, "ab config");
                log("ab config 打开了 " + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName() + " " + mc.screen.getTitle().getString()));
            }
            case 80 -> shot(mc, "config");
            case 85 -> {
                var b = mc.screen == null ? null : findButton(mc.screen, "打开");
                log("建造分类按钮 " + (b == null ? "没找到" : b.getMessage().getString()));
                if (b != null) b.onPress();
            }
            case 95 -> shot(mc, "config_build");
            case 100 -> {
                mc.setScreen(null);
                cmd(mc, "ab menu");
                log("ab menu 打开了 " + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
            }
            case 110 -> shot(mc, "menu");
            case 115 -> {
                var b = mc.screen == null ? null : findButton(mc.screen, "设置");
                log("主界面设置按钮 " + (b == null ? "没找到" : "已点击"));
                if (b != null) b.onPress();
            }
            case 125 -> {
                log("设置按钮打开了 " + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName() + " " + mc.screen.getTitle().getString()));
                shot(mc, "menu_config");
            }
            case 135 -> {
                log("退出");
                mc.stop();
            }
            default -> {}
        }
    }

    /** 单机时内置服务端也在同一个 JVM：记录服务端处理右键那一刻的玩家朝向 */
    @SubscribeEvent
    public static void onServerUse(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide()) return;
        String item = e.getItemStack().getItem().toString();
        if (!item.contains("ladder")) return;
        var lv = e.getLevel();
        var cl = Minecraft.getInstance().level;
        BlockPos p = e.getPos();
        StringBuilder sb = new StringBuilder();
        for (Direction d : new Direction[] {null, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos q = d == null ? p : p.relative(d);
            String sv = lv.getBlockState(q).getBlock().getDescriptionId().replace("block.minecraft.", "");
            String cv = cl == null ? "?" : cl.getBlockState(q).getBlock().getDescriptionId().replace("block.minecraft.", "");
            sb.append(d == null ? "本格" : d.getName()).append("=").append(sv).append(sv.equals(cv) ? "" : "(客户端:" + cv + ")").append(' ');
        }
        AutoBuilder.LOG.info("[AB-SRV] 右键 {} face={} 玩家yaw={} pitch={} 服务端: {}", p.toShortString(), e.getFace(),
                e.getEntity().getYRot(), e.getEntity().getXRot(), sb);
    }

    @SubscribeEvent
    public static void onServerPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel().isClientSide() || !e.getPlacedBlock().is(net.minecraft.world.level.block.Blocks.LADDER)) return;
        AutoBuilder.LOG.info("[AB-SRV] 已放置 {} {} 实体={}", e.getPos().toShortString(), e.getPlacedBlock(),
                e.getEntity() == null ? "null" : e.getEntity().getYRot());
    }

    @SubscribeEvent
    public static void onServerBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        if (e.getLevel().isClientSide() || !e.getState().is(net.minecraft.world.level.block.Blocks.LADDER)) return;
        AutoBuilder.LOG.info("[AB-SRV] 被挖 {} {}", e.getPos().toShortString(), e.getState());
    }

    /** 对比客户端预测和服务端实际放出来的方块 */
    private static void checkServerStates(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        var it = com.autobuilder.build.Placer.DEBUG_LOG.iterator();
        while (it.hasNext()) {
            Object[] e = it.next();
            int age = (Integer) e[4] + 1;
            e[4] = age;
            if (age < 6) continue;
            it.remove();
            BlockPos pos = (BlockPos) e[0];
            BlockState srv = server.overworld().getBlockState(pos);
            if (!srv.equals(e[1])) {
                var sp = server.getPlayerList().getPlayers().get(0);
                log("服务端不一致 " + pos.toShortString() + " 客户端=" + e[1] + " 服务端=" + srv + " 发送yaw=" + e[2] + " pitch=" + e[3]
                        + " 服务端玩家yaw=" + sp.getYRot() + " pitch=" + sp.getXRot());
            }
        }
    }

    private static void step(Minecraft mc) throws IOException {
        checkServerStates(mc);
        if (ticks == 5 && showcase) mc.getWindow().setWindowed(1600, 900);
        if (ticks == 10) {
            BuildController.debug = true;
            com.autobuilder.ABConfig.PLACE_MODE.set(adjacent ? com.autobuilder.ABConfig.PlaceMode.ADJACENT : com.autobuilder.ABConfig.PlaceMode.AIRPLACE);
            log("放置模式 " + com.autobuilder.ABConfig.PLACE_MODE.get());
            fileFormatTest();
        }
        if (ticks == 60) mc.setScreen(new com.autobuilder.gui.BuilderScreen());
        if (ticks == 66) shot(mc, "gui");
        if (ticks == 72) mc.setScreen(null);
        if (ticks == 20) {
            for (String c : new String[] {"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set 6000",
                    "weather clear", "difficulty peaceful", "gamemode survival", "clear @s", "gamerule doMobSpawning false",
                    "gamerule sendCommandFeedback false", "gamerule keepInventory true"}) {
                cmd(mc, c);
            }
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        if (ticks == 40) {
            Blueprint bp = testBlueprint();
            Map<Item, Integer> need = new HashMap<>();
            for (int y = 0; y < bp.sy; y++)
                for (int z = 0; z < bp.sz; z++)
                    for (int x = 0; x < bp.sx; x++) {
                        BlockState t = bp.get(x, y, z);
                        if (t == null || t.isAir() || StateMatcher.isSecondaryPart(t)) continue;
                        if (t.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                            if (StateMatcher.isFluidSource(t) && StateMatcher.bucketFor(t) != null) need.merge(StateMatcher.bucketFor(t), 1, Integer::sum);
                            continue;
                        }
                        if (t.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                                && t.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
                            need.merge(net.minecraft.world.item.Items.WATER_BUCKET, 1, Integer::sum);
                        }
                        Item it = StateMatcher.itemFor(t);
                        if (it != null) need.merge(it, StateMatcher.itemsNeeded(t, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()), Integer::sum);
                    }
            Integer water = need.remove(net.minecraft.world.item.Items.WATER_BUCKET);
            if (supply) {
                // 材料全放进身后两个木桶，背包塞满杂物，逼它先存杂物再取货
                BlockPos me = mc.player.blockPosition();
                BARRELS.add(me.offset(3, 0, 3));
                BARRELS.add(me.offset(4, 0, 3));
                for (BlockPos bpos : BARRELS) cmd(mc, "setblock " + bpos.getX() + " " + bpos.getY() + " " + bpos.getZ() + " minecraft:barrel");
                int slot = 0;
                for (var en : need.entrySet()) {
                    int left = en.getValue() + 4;
                    while (left > 0) {
                        int n = Math.min(left, en.getKey().getDefaultMaxStackSize());
                        BlockPos bpos = BARRELS.get(slot / 27);
                        cmd(mc, "item replace block " + bpos.getX() + " " + bpos.getY() + " " + bpos.getZ() + " container." + (slot % 27)
                                + " with " + BuiltInRegistries.ITEM.getKey(en.getKey()) + " " + n);
                        slot++;
                        left -= n;
                    }
                }
                cmd(mc, "give @s minecraft:dirt 192");
                cmd(mc, "give @s minecraft:bread 16");
                cmd(mc, "give @s minecraft:diamond_pickaxe");
                cmd(mc, "give @s minecraft:diamond_shovel");
                cmd(mc, "give @s minecraft:diamond_axe");
                for (int i = 0; i < 28; i++) cmd(mc, "give @s minecraft:sandstone 64");
                log("材料放进木桶 " + slot + " 组，背包塞了 28 组砂岩");
                need.clear();
            }
            for (var en : need.entrySet()) {
                boolean bucket = en.getKey() == net.minecraft.world.item.Items.LAVA_BUCKET;
                cmd(mc, "give @s " + BuiltInRegistries.ITEM.getKey(en.getKey()) + " " + (en.getValue() + (bucket ? 0 : 4)));
            }
            if (water != null) {
                // 水桶不能叠加：只给 3 桶 + 2 个空桶，再在身后挖一个 2×2 无限水源，测试自动打水
                cmd(mc, "give @s minecraft:water_bucket 3");
                cmd(mc, "give @s minecraft:bucket 2");
                cmd(mc, "fill ~-1 ~-1 ~4 ~0 ~-1 ~5 minecraft:water");
                log("需要水 " + water + " 桶，已给 3 桶 + 2 空桶 + 无限水源");
            }
            if (!supply) {
                cmd(mc, "give @s minecraft:dirt 192");
                cmd(mc, "give @s minecraft:bread 16");
                cmd(mc, "give @s minecraft:diamond_pickaxe");
                cmd(mc, "give @s minecraft:diamond_shovel");
                cmd(mc, "give @s minecraft:diamond_axe");
            }
            log("材料 " + need.size() + " 种，共 " + need.values().stream().mapToInt(i -> i).sum() + " 个");
            if (!extra.isEmpty()) for (String c : extra.split(";")) cmd(mc, c.replace('_', ' '));
        }
        if (ticks == 80) {
            Blueprint bp = testBlueprint();
            Placement p = Placement.inFrontOf(bp, mc.player.blockPosition(), Direction.NORTH);
            if (presetName.equals("sealbox")) p.setOrigin(mc.player.blockPosition().offset(-3, -1, -3)); // 人在盒子正中间
            BuildController.setPlacement(p);
            log("加载 " + p.describe() + " 玩家 " + mc.player.blockPosition().toShortString());
        }
        if (ticks == 90 && supply) {
            com.autobuilder.build.SupplyManager.clear();
            for (BlockPos bpos : BARRELS) com.autobuilder.build.SupplyManager.add(bpos);
            log("登记材料箱 " + BARRELS);
        }
        if (resume && ticks == 400) {
            log("模拟退出世界（中途打断）");
            BuildController.detach();
        }
        if (resume && ticks == 460) {
            log("模拟重新进入后：" + (BuildController.placement() == null ? "没恢复！" : "已恢复 " + BuildController.placement().describe()));
            BuildController.start();
        }
        if (showcase && ticks == 90) {
            tidy(mc);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            faceBuilding(mc);
        }
        if (ticks == 100) {
            shot(mc, "before");
            BuildController.start();
        }
        if (ticks > 100 && endTick < 0 && !(resume && ticks >= 400 && ticks <= 470)) {
            if (ticks % 100 == 0) {
                var s = BuildController.snapshot();
                log(String.format("状态 %s/%s 进度 %s 待放 %s 待挖 %s 脚手架 %d 位置 %s 血 %.0f 饱食 %d | %s",
                        BuildController.state(), BuildController.phase(), s == null ? "-" : s.percent() + "%",
                        s == null ? "-" : s.place.size(), s == null ? "-" : s.breaks.size(), BuildController.scaffold().size(),
                        mc.player.blockPosition().toShortString(), mc.player.getHealth(), mc.player.getFoodData().getFoodLevel(),
                        BuildController.activity()));
            }
            if (ticks % 600 == 0 && !showcase) shot(mc, "progress");
            if (showcase && !midShot && BuildController.snapshot() != null && BuildController.snapshot().percent() >= 55) {
                midShot = true;
                midShotTick = ticks + 4;
                tidy(mc);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                faceBuilding(mc);
            }
            if (ticks == midShotTick) shot(mc, "mid");
            var snap = BuildController.snapshot();
            long scaffoldLeft = BuildController.scaffold().stream()
                    .filter(b -> com.autobuilder.build.ScaffoldItems.isScaffold(mc.level.getBlockState(b))).count();
            String sig = (snap == null ? "" : snap.ok + "/" + snap.place.size() + "/" + snap.breaks.size()) + "/" + scaffoldLeft + "/" + BuildController.phase();
            if (!sig.equals(lastSig)) {
                lastSig = sig;
                lastChange = ticks;
            }
            boolean stalled = ticks - lastChange > 1200;
            if (stalled) log("卡住：60 秒没有进展 " + sig + " | " + BuildController.activity());
            var st = BuildController.state();
            if (st == BuildController.State.DONE || st == BuildController.State.PAUSED || st == BuildController.State.IDLE
                    || ticks > timeout || stalled) {
                log("结束：" + st + " " + BuildController.activity());
                StringBuilder inv = new StringBuilder();
                var pinv = mc.player.getInventory();
                for (int i = 0; i < pinv.getContainerSize(); i++) {
                    var it = pinv.getItem(i);
                    if (!it.isEmpty()) inv.append(i).append(':').append(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath()).append('x').append(it.getCount()).append(' ');
                }
                log("背包 " + inv);
                verify(mc);
                Placement p = BuildController.placement();
                BlockPos c = p.origin().offset(p.sizeX() / 2, 0, p.sizeZ() / 2);
                int d = Math.max(p.sizeX(), Math.max(p.sizeY(), p.sizeZ())) + (showcase ? 2 : 6);
                int up = showcase ? 2 : 4;
                cmd(mc, "tp @s " + (c.getX() + d) + " " + (c.getY() + p.sizeY() / 2 + up) + " " + (c.getZ() + d)
                        + " facing " + c.getX() + " " + (c.getY() + p.sizeY() / 2) + " " + c.getZ());
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                BuildController.stop();
                endTick = ticks;
            }
        }
        if (showcase && endTick > 0 && ticks == endTick + 50) {
            tidy(mc);
            mc.options.hideGui = true;
        }
        if (endTick > 0 && ticks == endTick + 60) shot(mc, "final");
        if (endTick > 0 && ticks == endTick + 90) {
            log("退出");
            mc.stop();
        }
    }

    private static void verify(Minecraft mc) {
        Placement p = BuildController.placement();
        Map<StateMatcher.Status, Integer> cnt = new HashMap<>();
        int shown = 0;
        for (int y = 0; y < p.sizeY(); y++)
            for (int z = 0; z < p.sizeZ(); z++)
                for (int x = 0; x < p.sizeX(); x++) {
                    BlockPos b = p.origin().offset(x, y, z);
                    BlockState t = p.targetAt(b);
                    if (t == null) continue;
                    BlockState w = mc.level.getBlockState(b);
                    StateMatcher.Status s = StateMatcher.classify(t, w, true, true);
                    cnt.merge(s, 1, Integer::sum);
                    if (s != StateMatcher.Status.OK && shown++ < 30) {
                        log("  不符 " + b.toShortString() + " " + s + " 目标=" + t + " 实际=" + w);
                    }
                }
        log("校验结果 " + cnt + " 剩余脚手架 " + BuildController.scaffold().size());
        // 存成 .nbt 再读回来，比较方块数
        try {
            Path f = com.autobuilder.blueprint.BlueprintSaver.save(mc.level, p.origin(), p.max(), "autotest_saved");
            Blueprint back = com.autobuilder.blueprint.BlueprintLoader.load(f);
            int solid = 0;
            for (int y = 0; y < p.sizeY(); y++)
                for (int z = 0; z < p.sizeZ(); z++)
                    for (int x = 0; x < p.sizeX(); x++)
                        if (!mc.level.getBlockState(p.origin().offset(x, y, z)).isAir()) solid++;
            log("保存/读回 .nbt：世界非空气 " + solid + "，读回 " + back.solidCount() + "，尺寸 " + back.sizeText()
                    + (solid == back.solidCount() ? " 一致" : " 不一致！"));
        } catch (Exception ex) {
            log("保存/读回失败 " + ex);
        }
    }

    /** 读取 autobuild/ 目录下的所有蓝图文件，把内容打到日志里 */
    private static void fileFormatTest() {
        for (BlueprintManager.Entry e : BlueprintManager.list()) {
            if (e.builtin()) continue;
            try {
                Blueprint bp = com.autobuilder.blueprint.BlueprintLoader.load(e.file());
                StringBuilder sb = new StringBuilder();
                for (int y = 0; y < Math.min(bp.sy, 4); y++)
                    for (int z = 0; z < Math.min(bp.sz, 4); z++)
                        for (int x = 0; x < Math.min(bp.sx, 4); x++) {
                            BlockState st = bp.get(x, y, z);
                            if (st != null && !st.isAir()) sb.append(" (").append(x).append(',').append(y).append(',').append(z)
                                    .append(")=").append(st.toString().replace("Block{minecraft:", "").replace("}", ""));
                        }
                log("读取 " + e.name() + " 尺寸 " + bp.sizeText() + " 非空气 " + bp.solidCount() + " :" + sb);
            } catch (Exception ex) {
                log("读取失败 " + e.name() + " : " + ex);
            }
        }
    }
}
