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
    private static String presetName = "builtin:cabin";
    private static boolean flat = true;
    private static long seed = 12345L;
    /** at=X/Z：开局传送到这一列的地面（默认世界出生点） */
    private static int[] at;
    private static int timeout = 20 * 60 * 8;
    private static String extra = "";
    private static boolean adjacent;
    private static boolean supply;
    private static boolean resume;
    /** ui：只测界面（帮助、设置、主界面）并截图，不建造 */
    private static boolean ui;
    /** 寻路压测：不建造，只在当前地形里跑一批寻路，量每毫秒展开多少节点、路线有没有变 */
    private static boolean pathBench;
    /** showcase：大窗口、清掉聊天和提示，拍项目页展示图（建造前预览、建造中、完工无 HUD） */
    private static boolean showcase;
    /** video：录宣传片素材（大窗口、电影镜头、ffmpeg 录像）；menu：小木屋从主界面选蓝图开始，最后演示帮助和设置 */
    private static boolean video, menu;
    /** pool：把建筑放进一个 2 格深的水池里，测抽水（里面要抽干，外面的水不动） */
    private static boolean pool;
    /** savewater：开工前在旁边搭一个泡在湖里的小塔，测“保存时去掉外面的水”和选区木棍 */
    private static boolean savewater;
    private static BlockPos sceneBase;
    /** chop / mine：测自动砍树、自动挖矿（不建蓝图） */
    private static boolean chopTest, mineTest, oreTest;
    /**
     * excavate：测挖空选区。平坦世界搭一个 8×5×8 的土石堆（下两层石头、中间两层泥土、顶上一层沙砾），选区再往地下多两层；
     * 堆顶上（选区外）立两格沙子（挖开后掉进选区，也要挖掉），石头里封一格水（挨着水的不挖、水不能漏出来），角上一个装了钻石的箱子（不挖）。
     * normal 世界不搭东西，直接挖出生点旁边 8×8×8 的天然地形。
     */
    private static boolean excavateTest;
    /** replace：小木屋用 /ab replace 换材料再建（木板→砖块、橡木楼梯→石砖楼梯、原木→不建），配 resume 顺便测断点记住替换表 */
    private static boolean replaceTest;
    /** dump（配 excavate,flat）：出生点北边放一个箱子登记成存放箱，背包先塞 27 组砂岩（原来带的，不该存走），逼它挖到一半回去存 */
    private static boolean dumpTest;
    /** stream：建筑西边两格放一排水源，流动水漫过半个地基和建筑外面（测流动水里放方块、站在浅水里会不会卡住） */
    private static boolean stream;
    /** highsrc（配 pond）：东边界外一条两格深的水渠，中间隔着一排蓝图要挖掉的土；平台中间高处一个水源往下流。
     *  原来水渠挨着土也算“外面灌得进来”，高处水源一直不填 */
    private static boolean highsrc;
    /** swamp：终极测试。找离出生点最近、放得下整座建筑（54×40）的沼泽，人站在南边的干地上，在沼泽里建（配 swampfarm.litematic） */
    private static boolean swamp;
    private static BlockPos dumpChest;
    private static final Map<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.Block> REPL = Map.of(
            net.minecraft.world.level.block.Blocks.OAK_PLANKS, net.minecraft.world.level.block.Blocks.BRICKS,
            net.minecraft.world.level.block.Blocks.OAK_STAIRS, net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS,
            net.minecraft.world.level.block.Blocks.OAK_LOG, net.minecraft.world.level.block.Blocks.AIR);
    private static BlockPos exA, exB;
    /** subst：材料全换成同类别的（橡木→云杉、白色→红色），测同类材料替换；nearby：材料放进没登记的木桶，测自动翻附近容器 */
    private static boolean subst, nearby;
    /** dungeon（含 nearby）：脚下深处再埋一个封死的箱子（像地牢），测补货会不会跳过走不到的容器 */
    private static boolean dungeon;
    private static BlockPos dungeonPos;
    private static BlockPos gatherBase;
    private static int gatherEnd = -1;
    /** 耗时统计：每种动作占了多少 tick */
    private static final Map<String, Integer> PROFILE = new HashMap<>();

    private static String category(String act) {
        if (act == null || act.isEmpty()) return "(空)";
        String[] parts = act.trim().split("\\s+");
        if (act.startsWith("寻路")) return "寻路 " + parts[parts.length - 1];
        return parts[0].replaceAll("[0-9,./-]+$", "");
    }

    private static void logProfile() {
        int total = PROFILE.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder sb = new StringBuilder("耗时分布（共 " + total + " tick）：");
        PROFILE.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append(String.format(" %s=%d(%.0f%%)", e.getKey(), e.getValue(), e.getValue() * 100.0 / Math.max(1, total))));
        log(sb.toString());
        log(String.format("寻路：%d 次，共 %.0f ms，展开 %d 个节点，找不到 %d 次", com.autobuilder.path.PathFinder.statCalls,
                com.autobuilder.path.PathFinder.statNanos / 1e6, com.autobuilder.path.PathFinder.statNodes, com.autobuilder.path.PathFinder.statFails));
    }
    private static int vt, vStage, vMark;
    private static boolean buildStarted, restockSeen;
    private static int restockDoneAt = -1;
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
            else if (p.startsWith("at=")) {
                String[] xz = p.substring(3).split("/");
                at = new int[] {Integer.parseInt(xz[0]), Integer.parseInt(xz[1])};
            }
            else if (p.startsWith("timeout=")) timeout = Integer.parseInt(p.substring(8));
            else if (p.startsWith("extra=")) extra = p.substring(6);
            else if (p.equals("adjacent")) adjacent = true;
            else if (p.equals("supply")) supply = true;
            else if (p.equals("resume")) resume = true;
            else if (p.equals("ui")) ui = true;
            else if (p.equals("pathbench")) pathBench = true;
            else if (p.equals("showcase")) showcase = true;
            else if (p.equals("video")) video = true;
            else if (p.equals("menu")) menu = true;
            else if (p.equals("pool")) pool = true;
            else if (p.equals("savewater")) savewater = true;
            else if (p.equals("chop")) chopTest = true;
            else if (p.equals("ore")) oreTest = mineTest = true;
            else if (p.equals("subst")) subst = true;
            else if (p.equals("nearby")) nearby = true;
            else if (p.equals("dungeon")) dungeon = nearby = true;
            else if (p.equals("mine")) mineTest = true;
            else if (p.equals("excavate")) excavateTest = mineTest = true;
            else if (p.equals("replace")) replaceTest = true;
            else if (p.equals("dump")) dumpTest = true;
            else if (p.equals("stream")) stream = true;
            else if (p.equals("highsrc")) highsrc = true;
            else if (p.equals("swamp")) swamp = true;
            else presetName = switch (p) {
                case "cabin" -> "builtin:cabin";
                case "tower" -> "builtin:tower";
                case "yard" -> "builtin:yard";
                default -> p;
            };
        }
    }

    /** 测试用蓝图：sealbox = 全封闭石盒（没有门窗），人从盒子正中间开始 */
    private static Blueprint testBlueprint() throws IOException {
        if (presetName.equals("farm")) return farmBlueprint();
        if (presetName.equals("pond")) {
            // 池塘平台：9×3×9，底层圆石地板，四角立两格高的柱子，上面全是空气（边上挡不住水，测挡水）
            Blueprint b = new Blueprint("pond", 9, 3, 9);
            BlockState stone = net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
            BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int y = 0; y < 3; y++)
                for (int z = 0; z < 9; z++)
                    for (int x = 0; x < 9; x++) {
                        boolean corner = (x == 0 || x == 8) && (z == 0 || z == 8);
                        b.set(x, y, z, y == 0 || corner ? stone : air);
                    }
            return b;
        }
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

    /**
     * farm：7×2×7 的小田。外圈草方块（一角是泥土），北边一排土径，中间耕地围着一格水，耕地上种小麦。
     * 测：草方块用泥土代替、泥土和草互相算对、先放泥土再锄地/铲土径、倒水、种子要等耕地。
     */
    private static Blueprint farmBlueprint() {
        Blueprint b = new Blueprint("farm", 7, 2, 7);
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int z = 0; z < 7; z++)
            for (int x = 0; x < 7; x++) {
                boolean edge = x == 0 || x == 6 || z == 0 || z == 6;
                BlockState g;
                if (x == 3 && z == 3) g = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
                else if (z == 0 && x >= 1 && x <= 5) g = net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState();
                else if (x == 0 && z == 6) g = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
                else if (edge) g = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
                else g = net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState();
                b.set(x, 0, z, g);
                boolean crop = !edge && !(x == 3 && z == 3) && (x + z) % 2 == 0;
                b.set(x, 1, z, crop ? net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState() : air);
            }
        return b;
    }

    /**
     * 砍树测试：出生点周围长三棵树（橡树、白桦、云杉），再立一根挨着木板的原木当“房子”（不该砍）。
     * 挖矿测试：面前（北边）垒一块 11×4×28 的石头，里面放矿：一块贴着主巷顶上（露出来，要挖）、它上面再一块（矿脉，要挖）、
     * 一块离巷道两格（藏着，不该挖）。
     */
    private static void gatherStep(Minecraft mc) {
        BlockPos P = gatherBase;
        if (ticks == 40) {
            gatherBase = P = mc.player.blockPosition();
            cmd(mc, "give @s minecraft:diamond_axe");
            cmd(mc, "give @s minecraft:diamond_pickaxe");
            cmd(mc, "give @s minecraft:diamond_shovel");
            cmd(mc, "give @s minecraft:bread 16");
            cmd(mc, "give @s minecraft:dirt 64");
            if (chopTest) {
                cmd(mc, "give @s minecraft:oak_sapling 2");
                cmd(mc, "give @s minecraft:birch_sapling 1");
                cmd(mc, "place feature minecraft:oak " + at(P, 6, 0, 0));
                cmd(mc, "place feature minecraft:birch " + at(P, 0, 0, 8));
                cmd(mc, "place feature minecraft:spruce " + at(P, -7, 0, 3));
                cmd(mc, "setblock " + at(P, 9, 0, -7) + " minecraft:oak_log");
                cmd(mc, "setblock " + at(P, 9, 1, -7) + " minecraft:oak_log");
                cmd(mc, "setblock " + at(P, 10, 0, -7) + " minecraft:oak_planks");
                cmd(mc, "setblock " + at(P, 10, 1, -7) + " minecraft:oak_planks");
            } else if (excavateTest) {
                if (flat) {
                    exA = P.offset(3, -2, 3);
                    exB = P.offset(10, 4, 10);
                    cmd(mc, "fill " + at(P, 3, 0, 3) + " " + at(P, 10, 1, 10) + " minecraft:stone");
                    cmd(mc, "fill " + at(P, 3, 2, 3) + " " + at(P, 10, 3, 10) + " minecraft:dirt");
                    cmd(mc, "fill " + at(P, 3, 4, 3) + " " + at(P, 10, 4, 10) + " minecraft:gravel");
                    cmd(mc, "setblock " + at(P, 6, 5, 6) + " minecraft:sand");
                    cmd(mc, "setblock " + at(P, 6, 6, 6) + " minecraft:sand");
                    cmd(mc, "setblock " + at(P, 4, 1, 4) + " minecraft:water");
                    cmd(mc, "setblock " + at(P, 9, 0, 9) + " minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}");
                    if (dumpTest) {
                        dumpChest = P.offset(0, 0, -3);
                        cmd(mc, "setblock " + at(P, 0, 0, -3) + " minecraft:chest");
                        for (int i = 0; i < 27; i++) cmd(mc, "give @s minecraft:sandstone 64");
                    }
                } else {
                    exA = P.offset(2, -4, 2);
                    exB = P.offset(9, 3, 9);
                }
            } else {
                cmd(mc, "fill " + at(P, -5, 0, -1) + " " + at(P, 5, 3, -28) + " minecraft:stone");
                cmd(mc, "setblock " + at(P, 0, 2, -6) + " minecraft:iron_ore");
                cmd(mc, "setblock " + at(P, 0, 3, -6) + " minecraft:iron_ore");
                cmd(mc, "setblock " + at(P, 3, 0, -10) + " minecraft:diamond_ore");
            }
            log("砍树/挖矿测试场地 基点 " + P.toShortString());
            return;
        }
        if (ticks == 100) {
            if (chopTest) {
                com.autobuilder.build.Gather.startChop(16);
            } else if (excavateTest) {
                // 走命令这条路（顺便测命令注册和选区）
                com.autobuilder.build.Selection.set(1, exA);
                com.autobuilder.build.Selection.set(2, exB);
                cmd(mc, "ab excavate");
            } else {
                mc.player.setYRot(180f);
                mc.player.setXRot(0f);
                mc.player.yRotO = 180f;
                mc.player.xRotO = 0f;
                if (oreTest) com.autobuilder.build.Gather.startOre("all", 24);
                else com.autobuilder.build.Gather.startMine(20, 3);
            }
            return;
        }
        if (dumpTest && ticks == 70) cmd(mc, "ab dump add " + at(dumpChest, 0, 0, 0));
        if (ticks < 100) return;
        if (gatherEnd < 0) {
            if (BuildController.state() == BuildController.State.RUNNING) PROFILE.merge(category(BuildController.activity()), 1, Integer::sum);
            if (ticks % 100 == 0) {
                var pl = BuildController.placement();
                log(String.format("状态 %s %s 位置 %s | %s", BuildController.state(), pl == null ? "-" : pl.bp.name,
                        mc.player.blockPosition().toShortString(), BuildController.activity()));
            }
            if (ticks % 600 == 0) shot(mc, "gather");
            if (!com.autobuilder.build.Gather.active() || ticks > timeout) {
                gatherEnd = ticks;
                log("结束：" + (com.autobuilder.build.Gather.active() ? "超时 " : "") + BuildController.state() + " " + BuildController.activity());
                if (excavateTest) {
                    excavateCheck(mc);
                } else if (chopTest) {
                    int logs = 0, saplings = 0;
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    for (int y = -1; y <= 20; y++)
                        for (int z = -14; z <= 14; z++)
                            for (int x = -14; x <= 14; x++) {
                                m.set(P.getX() + x, P.getY() + y, P.getZ() + z);
                                BlockState s = mc.level.getBlockState(m);
                                if (s.is(net.minecraft.tags.BlockTags.LOGS)) logs++;
                                if (s.is(net.minecraft.tags.BlockTags.SAPLINGS)) saplings++;
                            }
                    boolean houseOk = mc.level.getBlockState(P.offset(9, 0, -7)).is(net.minecraft.world.level.block.Blocks.OAK_LOG)
                            && mc.level.getBlockState(P.offset(9, 1, -7)).is(net.minecraft.world.level.block.Blocks.OAK_LOG);
                    log("砍树结果：剩原木 " + logs + "（房子那 2 根应该还在：" + (houseOk ? "在" : "没了！") + "），补种树苗 " + saplings
                            + ((logs == 2 && houseOk) ? " → 通过" : " → 失败"));
                } else {
                    boolean a = mc.level.getBlockState(P.offset(0, 2, -6)).isAir();
                    boolean b = mc.level.getBlockState(P.offset(0, 3, -6)).isAir();
                    boolean hidden = mc.level.getBlockState(P.offset(3, 0, -10)).is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE);
                    if (oreTest) {
                        log("找矿结果：铁矿 " + (a && b ? "两块都挖了" : "没挖完！") + "，藏着的钻石 " + (hidden ? "没挖到！" : "挖到了")
                                + ((a && b && !hidden) ? " → 通过" : " → 失败"));
                    } else {
                        log("挖矿结果：露出来的铁矿 " + (a ? "挖了" : "没挖！") + "，矿脉上面那块 " + (b ? "挖了" : "没挖！") + "，藏着的钻石 "
                                + (hidden ? "没动" : "被挖了！") + ((a && b && hidden) ? " → 通过" : " → 失败"));
                    }
                }
                StringBuilder inv = new StringBuilder();
                var pinv = mc.player.getInventory();
                for (int i = 0; i < 36; i++) {
                    var it = pinv.getItem(i);
                    if (!it.isEmpty()) inv.append(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath()).append('x').append(it.getCount()).append(' ');
                }
                log("背包 " + inv);
                shot(mc, "gather_final");
            }
            return;
        }
        if (ticks == gatherEnd + 40) {
            log("退出");
            mc.stop();
        }
    }

    /** 挖空结果：剩下的方块必须都是挨着水/岩浆不能挖的；水不能漏出来；箱子还在 */
    private static void excavateCheck(Minecraft mc) {
        var level = mc.level;
        int left = 0, leftBad = 0, fluids = 0, chests = 0;
        BlockPos firstBad = null;
        for (BlockPos b : BlockPos.betweenClosed(exA, exB)) {
            BlockState s = level.getBlockState(b);
            if (!s.getFluidState().isEmpty()) fluids++;
            if (s.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                chests++;
                continue;
            }
            if (s.isAir() || s.canBeReplaced() || s.getDestroySpeed(level, b) < 0 || !s.getFluidState().isEmpty()) continue;
            left++;
            boolean nearFluid = false;
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue;
                if (!level.getFluidState(b.relative(d)).isEmpty()) nearFluid = true;
            }
            // 存放箱场景：收工后去存东西时垫的出坑台阶是故意留的
            boolean exitStep = dumpTest && com.autobuilder.build.ScaffoldItems.isScaffold(s) && b.getY() <= exA.getY() + 1;
            if (!level.getFluidState(b.below()).is(net.minecraft.tags.FluidTags.LAVA) && !nearFluid && !exitStep) {
                leftBad++;
                if (firstBad == null) firstBad = b.immutable();
            }
        }
        boolean pass;
        if (flat) {
            BlockPos P = gatherBase;
            boolean water = level.getFluidState(P.offset(4, 1, 4)).isSource();
            boolean chest = level.getBlockState(P.offset(9, 0, 9)).is(net.minecraft.world.level.block.Blocks.CHEST);
            boolean bottom = level.getBlockState(P.offset(6, -3, 6)).is(net.minecraft.world.level.block.Blocks.DIRT);
            pass = leftBad == 0 && water && fluids == 1 && chest && bottom && mc.player.isAlive();
            if (dumpTest) pass &= dumpCheck(mc);
            log("挖空结果：剩 " + left + " 块（不该剩的 " + leftBad + (firstBad == null ? "" : " 比如 " + firstBad.toShortString() + " " + level.getBlockState(firstBad)) + "），"
                    + "封住的水 " + (water ? "还在" : "没了！") + "，选区里液体 " + fluids + " 格（应该 1 格，多了就是漏水了），箱子 " + (chest ? "还在" : "被挖了！")
                    + "，选区底下那层 " + (bottom ? "没动" : "被挖了！") + (pass ? " → 通过" : " → 失败"));
        } else {
            pass = leftBad == 0 && mc.player.isAlive();
            log("挖空结果：剩 " + left + " 块（不该剩的 " + leftBad + (firstBad == null ? "" : " 比如 " + firstBad.toShortString() + " " + level.getBlockState(firstBad)) + "），"
                    + "液体 " + fluids + " 格，箱子 " + chests + " 个" + (pass ? " → 通过" : " → 失败"));
        }
        logProfile();
    }

    /** 存放箱结果：箱子里存了东西、原来带的砂岩一组没少、背包腾出了地方、地上没剩多少掉落物 */
    private static boolean dumpCheck(Minecraft mc) {
        var srv = mc.getSingleplayerServer();
        Map<String, Integer> box = new java.util.TreeMap<>();
        // 服务端的方块实体只能在服务端线程上读（别的线程读直接返回 null）
        int[] groundBox = {0};
        if (srv != null) {
            srv.submit(() -> {
                if (srv.overworld().getBlockEntity(dumpChest) instanceof net.minecraft.world.Container c) {
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        var it = c.getItem(i);
                        if (!it.isEmpty()) box.merge(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath(), it.getCount(), Integer::sum);
                    }
                }
                var aabb = new net.minecraft.world.phys.AABB(exA).minmax(new net.minecraft.world.phys.AABB(exB)).inflate(4);
                groundBox[0] = srv.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, aabb).size();
            }).join();
        }
        int boxTotal = box.values().stream().mapToInt(Integer::intValue).sum();
        var inv = mc.player.getInventory();
        int sand = 0, free = 0;
        for (int i = 0; i < 36; i++) {
            var it = inv.getItem(i);
            if (it.isEmpty()) free++;
            else if (it.is(net.minecraft.world.item.Items.SANDSTONE)) sand += it.getCount();
        }
        int ground = groundBox[0];
        boolean ok = boxTotal >= 128 && sand == 27 * 64 && free >= 3 && !box.containsKey("sandstone") && !box.containsKey("diamond_pickaxe");
        log("存放箱结果：箱子里 " + boxTotal + " 个 " + box + "，背包砂岩 " + sand + "（应 " + 27 * 64 + "），背包空格 " + free + "，地上掉落物 " + ground
                + " 堆，存了几组 " + com.autobuilder.build.Restocker.lastDeposited() + (ok ? " → 通过" : " → 失败"));
        return ok;
    }

    /** 找沼泽：离出生点最近的沼泽附近螺旋往外找，人站的地方是干地，北边 54×40 的占地四角和中间都在沼泽里 */
    private static com.autobuilder.path.PathFinder.Ctx benchCtx(Minecraft mc) {
        return new com.autobuilder.path.PathFinder.Ctx(mc.level, null, java.util.Set.of(), true, true, 3, 64, s -> false);
    }

    private static int surfaceY(Minecraft mc, int x, int z) {
        int y = mc.level.getMaxBuildHeight() - 1;
        while (y > mc.level.getMinBuildHeight()) {
            BlockState st = mc.level.getBlockState(new BlockPos(x, y, z));
            if (!st.isAir() && !(st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) && st.blocksMotion()) break;
            y--;
        }
        return y + 1;
    }

    /** 8 个方向各找一次 32 格外的地面（能到的），再各跑一次永远到不了的（看满负荷时的速度） */
    private static void pathBench(Minecraft mc) {
        BlockPos start = mc.player.blockPosition();
        double stand = com.autobuilder.path.PathFinder.stand(benchCtx(mc), start.getX(), start.getY(), start.getZ());
        if (Double.isNaN(stand)) stand = mc.player.getY();
        int[][] dirs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        // 采样主线程调用栈：最顶上的方法、最近的本模组方法（带行号）各数一遍
        Thread main = Thread.currentThread();
        Map<String, Integer> top = new java.util.concurrent.ConcurrentHashMap<>(), own = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                StackTraceElement[] st = main.getStackTrace();
                if (st.length > 0) {
                    top.merge(st[0].getClassName() + "." + st[0].getMethodName(), 1, Integer::sum);
                    for (int i = 0; i < Math.min(st.length, 4); i++) {
                        if (st[i].getClassName().startsWith("com.autobuilder")) {
                            own.merge(st[i].getClassName().replace("com.autobuilder.", "") + "." + st[i].getMethodName() + ":" + st[i].getLineNumber(), 1, Integer::sum);
                            break;
                        }
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        }, "bench-sampler");
        sampler.setDaemon(true);
        sampler.start();
        for (int round = 0; round < 3; round++) {
            int found = 0, steps = 0;
            long hash = 17, nodes0 = com.autobuilder.path.PathFinder.statNodes, t0 = System.nanoTime();
            for (int[] d : dirs) {
                int tx = start.getX() + d[0] * 32, tz = start.getZ() + d[1] * 32, ty = surfaceY(mc, tx, tz);
                com.autobuilder.path.PathFinder.Goal g = new com.autobuilder.path.PathFinder.Goal() {
                    @Override
                    public boolean reached(int x, int y, int z, double standY) {
                        return Math.abs(x - tx) <= 2 && Math.abs(z - tz) <= 2 && Math.abs(y - ty) <= 3;
                    }

                    @Override
                    public double heuristic(int x, int y, int z, double standY) {
                        double dx = x - tx, dy = y - ty, dz = z - tz;
                        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 2);
                    }
                };
                var path = com.autobuilder.path.PathFinder.find(benchCtx(mc), start, stand, g, 200_000, 20_000_000_000L);
                if (path != null && !path.isEmpty() && g.reached(path.get(path.size() - 1).pos().getX(), path.get(path.size() - 1).pos().getY(),
                        path.get(path.size() - 1).pos().getZ(), 0)) found++;
                if (path != null) {
                    steps += path.size();
                    for (var s : path) hash = hash * 31 + s.pos().asLong() * 7 + s.move().ordinal();
                }
            }
            long reachNodes = com.autobuilder.path.PathFinder.statNodes - nodes0, reachNs = System.nanoTime() - t0;
            nodes0 = com.autobuilder.path.PathFinder.statNodes;
            t0 = System.nanoTime();
            for (int[] d : dirs) {
                int tx = start.getX() + d[0] * 80, tz = start.getZ() + d[1] * 80;
                com.autobuilder.path.PathFinder.Goal g = new com.autobuilder.path.PathFinder.Goal() {
                    @Override
                    public boolean reached(int x, int y, int z, double standY) {
                        return false;
                    }

                    @Override
                    public double heuristic(int x, int y, int z, double standY) {
                        double dx = x - tx, dz = z - tz;
                        return Math.sqrt(dx * dx + dz * dz);
                    }
                };
                com.autobuilder.path.PathFinder.find(benchCtx(mc), start, stand, g, 60_000, 20_000_000_000L);
            }
            long failNodes = com.autobuilder.path.PathFinder.statNodes - nodes0, failNs = System.nanoTime() - t0;
            log(String.format("寻路压测第 %d 轮：能到的 %d/8，共 %d 步，路线指纹 %016x，展开 %d 个 %.0f ms（%.0f 个/ms）；到不了的展开 %d 个 %.0f ms（%.0f 个/ms）",
                    round + 1, found, steps, hash, reachNodes, reachNs / 1e6, reachNodes / (reachNs / 1e6), failNodes, failNs / 1e6, failNodes / (failNs / 1e6)));
        }
        sampling.set(false);
        int total = top.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder sb = new StringBuilder("采样 " + total + " 次，最顶上的方法：");
        top.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                .forEach(e -> sb.append(String.format("%n    %5.1f%% %s", e.getValue() * 100.0 / total, e.getKey())));
        sb.append("\n  最近的本模组方法：");
        own.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                .forEach(e -> sb.append(String.format("%n    %5.1f%% %s", e.getValue() * 100.0 / total, e.getKey())));
        log(sb.toString());
    }

    private static boolean swampTeleport(Minecraft mc) {
        var srv = mc.getSingleplayerServer();
        if (srv == null) return false;
        BlockPos[] out = {null};
        BlockPos[] near = {null};
        srv.submit(() -> {
            var lvl = srv.overworld();
            var found = lvl.findClosestBiome3d(h -> h.is(net.minecraft.world.level.biome.Biomes.SWAMP), lvl.getSharedSpawnPos(), 6400, 32, 64);
            if (found == null) return;
            BlockPos c = found.getFirst();
            near[0] = c;
            int[][] probe = {{0, -20}, {-27, -40}, {26, -40}, {-27, -1}, {26, -1}, {0, 0}};
            for (int r = 0; r <= 192 && out[0] == null; r += 8)
                for (int dx = -r; dx <= r && out[0] == null; dx += 8)
                    for (int dz = -r; dz <= r && out[0] == null; dz += 8) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int x = c.getX() + dx, z = c.getZ() + dz;
                        boolean all = true;
                        for (int[] o : probe) {
                            if (!lvl.getBiome(new BlockPos(x + o[0], 63, z + o[1])).is(net.minecraft.world.level.biome.Biomes.SWAMP)) {
                                all = false;
                                break;
                            }
                        }
                        if (!all) continue;
                        int y = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        BlockState ground = lvl.getBlockState(new BlockPos(x, y - 1, z));
                        if (!ground.getFluidState().isEmpty() || !ground.blocksMotion()) continue;
                        out[0] = new BlockPos(x, y, z);
                    }
        }).join();
        if (out[0] == null) {
            log("没找到放得下整座建筑的沼泽（最近的沼泽 " + (near[0] == null ? "没有" : near[0].toShortString()) + "），改用出生点");
            return false;
        }
        cmd(mc, "tp @s " + out[0].getX() + ".5 " + out[0].getY() + " " + out[0].getZ() + ".5");
        log("传送到沼泽 " + out[0].toShortString() + "（最近的沼泽 " + near[0].toShortString() + "）");
        return true;
    }

    private static String at(BlockPos base, int x, int y, int z) {
        return (base.getX() + x) + " " + (base.getY() + y) + " " + (base.getZ() + z);
    }

    /** savewater 场景：湖（9×3×9 水）里一个空心石塔，塔里一格水，塔外一个泡水的台阶、水面一片睡莲、湖底一棵海草 */
    private static void buildWaterScene(Minecraft mc) {
        BlockPos P = mc.player.blockPosition();
        sceneBase = P.offset(4, -1, 4);
        BlockPos B = sceneBase;
        cmd(mc, "fill " + at(B, 0, 0, 0) + " " + at(B, 8, 2, 8) + " minecraft:water");
        cmd(mc, "fill " + at(B, 2, 0, 2) + " " + at(B, 6, 2, 6) + " minecraft:stone hollow");
        cmd(mc, "setblock " + at(B, 4, 1, 4) + " minecraft:water");
        cmd(mc, "setblock " + at(B, 1, 1, 4) + " minecraft:stone_slab[type=bottom,waterlogged=true]");
        cmd(mc, "setblock " + at(B, 1, 3, 1) + " minecraft:lily_pad");
        cmd(mc, "setblock " + at(B, 7, 0, 7) + " minecraft:seagrass");
        log("搭好湖中小塔 基点 " + B.toShortString());
    }

    /** 保存时去掉外面的水：选区 B+(1,0,1)~B+(7,3,7) */
    private static void checkWaterSave(Minecraft mc) {
        BlockPos B = sceneBase, a = B.offset(1, 0, 1), b = B.offset(7, 3, 7);
        try {
            var r = com.autobuilder.blueprint.BlueprintSaver.save(mc.level, a, b, "autotest_water", true, true);
            Blueprint bp = com.autobuilder.blueprint.BlueprintLoader.load(r.path());
            int bad = 0;
            StringBuilder why = new StringBuilder();
            for (int y = 0; y < bp.sy; y++)
                for (int z = 0; z < bp.sz; z++)
                    for (int x = 0; x < bp.sx; x++) {
                        BlockPos w = a.offset(x, y, z);
                        BlockState ws = mc.level.getBlockState(w);
                        BlockState t = bp.get(x, y, z);
                        int rx = w.getX() - B.getX(), ry = w.getY() - B.getY(), rz = w.getZ() - B.getZ();
                        boolean inTower = rx >= 2 && rx <= 6 && rz >= 2 && rz <= 6 && ry <= 2;
                        boolean wet = ws.is(net.minecraft.world.level.block.Blocks.WATER) || ws.is(net.minecraft.world.level.block.Blocks.SEAGRASS);
                        if (wet && !inTower && t != null) {
                            bad++;
                            if (why.length() < 300) why.append(" 外面的水没去掉@").append(rx).append(',').append(ry).append(',').append(rz);
                        }
                    }
            BlockState inner = bp.get(4 - 1, 1, 4 - 1);
            BlockState slab = bp.get(1 - 1, 1, 4 - 1);
            BlockState lily = bp.get(1 - 1, 3, 1 - 1);
            boolean innerOk = inner != null && inner.is(net.minecraft.world.level.block.Blocks.WATER);
            boolean slabOk = slab != null && slab.is(net.minecraft.world.level.block.Blocks.STONE_SLAB)
                    && !slab.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
            boolean lilyOk = lily == null;
            log("保存去湖水：去掉 " + r.droppedWater() + " 格，去含水 " + r.dried() + "，保留要放水 " + r.keptWater()
                    + " | 外面残留 " + bad + why + " | 塔内水 " + (innerOk ? "保留" : "丢了！") + " | 台阶 " + (slabOk ? "已去含水" : "不对：" + slab)
                    + " | 睡莲 " + (lilyOk ? "已去掉" : "还在！") + ((bad == 0 && innerOk && slabOk && lilyOk) ? " → 通过" : " → 失败"));
        } catch (Exception ex) {
            log("保存去湖水 出错 " + ex);
        }
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
        if (video && mc.player != null && mc.level != null) {
            vt++;
            try {
                videoTick(mc);
            } catch (Exception ex) {
                log("录像脚本异常 " + ex);
                AutoBuilder.LOG.error("video", ex);
            }
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

    // ---------------- 宣传片录制 ----------------

    private static Path videoDir(Minecraft mc) {
        Path d = mc.gameDirectory.toPath().resolve("video");
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
        }
        return d;
    }

    private static void rec(Minecraft mc, String name) {
        Recorder.start(videoDir(mc).resolve(name + ".mp4"));
        log("开始录制 " + name);
    }

    private static void stopRec() {
        if (Recorder.active()) {
            Recorder.stop();
            log("结束录制");
        }
    }

    private static void vNext(int stage) {
        vStage = stage;
        vMark = vt;
    }

    private static int since() {
        return vt - vMark;
    }

    private static String clipPrefix() {
        if (menu) return "a";
        if (supply) return "d";
        return switch (presetName) {
            case "builtin:cabin" -> "cabin";
            case "builtin:tower" -> "tower";
            case "builtin:yard" -> "yard";
            default -> presetName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        };
    }

    /** 绕蓝图转圈；speed 度/秒 */
    private static void orbitBuilding(double radiusExtra, double heightFactor, double startDeg, double speed) {
        Placement pl = BuildController.placement();
        if (pl == null) return;
        double cx = pl.origin().getX() + pl.sizeX() / 2.0, cz = pl.origin().getZ() + pl.sizeZ() / 2.0;
        double cy = pl.origin().getY() + pl.sizeY() * 0.4;
        double r = Math.max(pl.sizeX(), pl.sizeZ()) * 0.9 + radiusExtra;
        CameraRig.orbit(new net.minecraft.world.phys.Vec3(cx, cy, cz), r, pl.sizeY() * heightFactor + 2, startDeg, speed);
    }

    private static void exitVideo(Minecraft mc) {
        stopRec();
        log("录像结束，退出");
        mc.stop();
    }

    private static void videoTick(Minecraft mc) {
        mc.getToasts().clear();
        if (vt == 5) {
            mc.getWindow().setWindowed(1920, 1080);
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        }
        switch (vStage) {
            case 0 -> {
                if (ticks >= 88) {
                    log("窗口 " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
                    tidy(mc);
                    if (menu) vNext(10);
                    else if (supply) vNext(30);
                    else vNext(20);
                }
            }
            // 小木屋：从主界面选蓝图、加载
            case 10 -> {
                mc.player.setYRot(180);
                mc.player.setXRot(12);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = false;
                CameraRig.off();
                rec(mc, "a_menu");
                mc.setScreen(new com.autobuilder.gui.BuilderScreen());
                vNext(11);
            }
            case 11 -> {
                if (since() == 30 && mc.screen != null) mc.screen.mouseClicked(40, 41, 0);
                if (since() == 60 && mc.screen != null) {
                    var b = findButton(mc.screen, "加载到面前");
                    log("加载按钮 " + (b == null ? "没找到" : "已点击"));
                    if (b != null) b.onPress();
                }
                if (since() == 90) mc.setScreen(null);
                if (since() == 120) {
                    stopRec();
                    vNext(12);
                }
            }
            case 12 -> {
                mc.options.hideGui = true;
                orbitBuilding(5, 0.6, 15, 9);
                rec(mc, "a_preview");
                vNext(13);
            }
            case 13 -> {
                if (since() == 160) {
                    stopRec();
                    mc.options.hideGui = false;
                    orbitBuilding(6, 0.7, 15 + 9 * 8, 5);
                    BuildController.start();
                    buildStarted = true;
                    rec(mc, "a_build");
                    vNext(40);
                }
            }
            // 其它蓝图：建造全程转圈
            case 20 -> {
                if (BuildController.placement() == null) return;
                mc.options.hideGui = false;
                orbitBuilding(6, 0.7, 15, 5);
                rec(mc, clipPrefix() + "_build");
                vNext(40);
            }
            // 材料箱：固定机位对着木桶
            case 30 -> {
                if (BARRELS.isEmpty() || BuildController.placement() == null) return;
                BlockPos b0 = BARRELS.get(0);
                var me = mc.player.position();
                var look = new net.minecraft.world.phys.Vec3((me.x + b0.getX() + 0.5) / 2, b0.getY() + 1.0, (me.z + b0.getZ() + 0.5) / 2 - 1.5);
                CameraRig.fixed(look.add(-1.5, 7, 13), look);
                mc.options.hideGui = false;
                rec(mc, "d_restock");
                vNext(31);
            }
            case 31 -> {
                if (com.autobuilder.build.Restocker.active()) restockSeen = true;
                if (restockSeen && !com.autobuilder.build.Restocker.active() && restockDoneAt < 0) restockDoneAt = vt;
                if (restockDoneAt > 0 && vt - restockDoneAt == 140 || since() > 2400) exitVideo(mc);
            }
            // 等建完
            case 40 -> {
                if (endTick > 0) vNext(41);
            }
            case 41 -> {
                if (since() == 30) {
                    stopRec();
                    mc.options.hideGui = true;
                    orbitBuilding(4, 0.5, 200, 14);
                    rec(mc, clipPrefix() + "_done");
                    vNext(42);
                }
            }
            case 42 -> {
                if (since() == 220) {
                    stopRec();
                    if (menu) vNext(50);
                    else exitVideo(mc);
                }
            }
            // 小木屋最后：帮助和设置
            case 50 -> {
                CameraRig.off();
                mc.options.hideGui = false;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                Placement pl = BuildController.placement();
                BlockPos c = pl.origin().offset(pl.sizeX() / 2, 0, pl.sizeZ() / 2);
                cmd(mc, "tp @s " + (c.getX() + 6) + " " + (c.getY() + 1) + " " + (c.getZ() + 9)
                        + " facing " + c.getX() + " " + (c.getY() + 3) + " " + c.getZ());
                vNext(51);
            }
            case 51 -> {
                if (since() == 20) {
                    tidy(mc);
                    cmd(mc, "ab help");
                    mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
                    rec(mc, "a_help");
                    vNext(52);
                }
            }
            case 52 -> {
                if (since() == 120) {
                    mc.setScreen(null);
                    mc.gui.getChat().clearMessages(false);
                    cmd(mc, "ab help 2");
                    mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
                }
                if (since() == 230) {
                    mc.setScreen(null);
                    tidy(mc);
                    cmd(mc, "ab config");
                }
                if (since() == 280 && mc.screen != null) {
                    var b = findButton(mc.screen, "打开");
                    if (b != null) b.onPress();
                }
                if (since() == 370) {
                    mc.setScreen(null);
                    exitVideo(mc);
                }
            }
            default -> {}
        }
    }

    @SubscribeEvent
    public static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) {
        Recorder.onFrame();
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

    private static void collectButtons(net.minecraft.client.gui.components.events.ContainerEventHandler h, String text,
            java.util.List<net.minecraft.client.gui.components.AbstractButton> out) {
        if (h == null) return;
        for (var c : h.children()) {
            if (c instanceof net.minecraft.client.gui.components.AbstractButton b && b.getMessage().getString().contains(text)) out.add(b);
            if (c instanceof net.minecraft.client.gui.components.events.ContainerEventHandler sub) collectButtons(sub, text, out);
        }
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
            case 96 -> {
                // 回到设置首页，打开最后一个分组（存放箱），看新设置项能不能正常显示
                cmd(mc, "ab config");
                java.util.List<net.minecraft.client.gui.components.AbstractButton> all = new java.util.ArrayList<>();
                collectButtons(mc.screen, "打开", all);
                log("设置分组 " + all.size() + " 个");
                if (!all.isEmpty()) all.get(all.size() - 1).onPress();
            }
            case 99 -> {
                log("最后一个分组 " + (mc.screen == null ? "null" : mc.screen.getTitle().getString()));
                shot(mc, "config_storage");
            }
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
            case 130 -> mc.setScreen(new com.autobuilder.gui.ToolsScreen(new com.autobuilder.gui.BuilderScreen()));
            case 138 -> shot(mc, "tools");
            case 140 -> {
                mc.setScreen(null);
                try {
                    Placement p = Placement.inFrontOf(BlueprintManager.load("builtin:cabin"), mc.player.blockPosition(), Direction.NORTH);
                    BuildController.setPlacement(p);
                    log("ui：加载小木屋，用到 " + p.blockCounts().keySet());
                } catch (Exception ex) {
                    log("ui：加载蓝图失败 " + ex);
                }
                cmd(mc, "give @s minecraft:stone_bricks 64");
                cmd(mc, "give @s minecraft:spruce_planks 40");
                cmd(mc, "give @s minecraft:stone_brick_stairs 30");
            }
            case 160 -> mc.setScreen(new com.autobuilder.gui.ReplaceScreen(null, null));
            case 168 -> shot(mc, "replace");
            case 170 -> mc.setScreen(new com.autobuilder.gui.ReplaceScreen(null, net.minecraft.world.level.block.Blocks.OAK_STAIRS));
            case 178 -> shot(mc, "replace_stairs");
            case 180 -> {
                // 点网格第一格：橡木楼梯换成排在最前面的那种
                var s = mc.screen;
                int gx = 20 + Math.min(200, s.width * 2 / 5);
                s.mouseClicked(gx + 8, 54 + 8, 0);
                var p = BuildController.placement();
                log("ui：点了网格第一格，替换表 " + (p == null ? "无蓝图" : p.replacements()));
            }
            case 188 -> shot(mc, "replace_applied");
            case 190 -> mc.setScreen(new com.autobuilder.gui.MaterialsScreen(null));
            case 198 -> shot(mc, "materials");
            case 200 -> mc.setScreen(new com.autobuilder.gui.GatherScreen(null));
            case 208 -> shot(mc, "gather");
            case 210 -> mc.setScreen(new com.autobuilder.gui.BuilderScreen());
            case 218 -> shot(mc, "menu2");
            case 220 -> {
                mc.setScreen(null);
                cmd(mc, "ab replace");
            }
            case 225 -> log("ui：/ab replace 打开了 " + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
            case 228 -> {
                // 木棍快捷菜单：对着蓝图里的一格（脚下那层的角上）
                var p = BuildController.placement();
                BlockPos at = p == null ? mc.player.blockPosition() : p.origin();
                mc.setScreen(new com.autobuilder.gui.WandMenuScreen(at, at.above()));
            }
            case 236 -> shot(mc, "wand_menu");
            case 238 -> {
                mc.setScreen(null);
                var p = BuildController.placement();
                BlockPos before = p == null ? null : p.origin();
                boolean ok = com.autobuilder.build.SelectionWand.moveBlueprintTo(mc.player.blockPosition().offset(0, 0, -20));
                log("ui：蓝图挪过去 " + ok + " 原点 " + before + " → " + (p == null ? null : p.origin()));
            }
            case 245 -> {
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
        if ((chopTest || mineTest) && ticks > 30) {
            gatherStep(mc);
            return;
        }
        if (ticks == 5 && showcase) mc.getWindow().setWindowed(1600, 900);
        if (ticks == 10) {
            BuildController.debug = true;
            // 测试窗口里误按方向键不要暂停（只改内存，不写进配置文件）
            com.autobuilder.ABConfig.PAUSE_ON_INPUT.set(false);
            com.autobuilder.ABConfig.PLACE_MODE.set(adjacent ? com.autobuilder.ABConfig.PlaceMode.ADJACENT : com.autobuilder.ABConfig.PlaceMode.AIRPLACE);
            log("放置模式 " + com.autobuilder.ABConfig.PLACE_MODE.get());
            fileFormatTest();
        }
        if (ticks == 60 && !video) mc.setScreen(new com.autobuilder.gui.BuilderScreen());
        if (ticks == 66 && !video) shot(mc, "gui");
        if (ticks == 72 && !video) mc.setScreen(null);
        if (ticks == 20) {
            for (String c : new String[] {"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set 6000",
                    "weather clear", "difficulty peaceful", "gamemode survival", "clear @s", "gamerule doMobSpawning false",
                    "gamerule sendCommandFeedback false", "gamerule keepInventory true", "gamerule announceAdvancements false"}) {
                cmd(mc, c);
            }
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        if (ticks == 25 && swamp && swampTeleport(mc)) {
            // 已经传送到沼泽
        } else if (ticks == 25) {
            // 新世界出生点在世界出生点附近随机（半径 10），每次地形都不一样，耗时没法比。
            // 统一传送到世界出生点正上方的地面（跳过树叶），同一个种子每次场地都一样
            BlockPos sp = at != null ? new BlockPos(at[0], 0, at[1]) : mc.level.getSharedSpawnPos();
            int y = mc.level.getMaxBuildHeight() - 1;
            while (y > mc.level.getMinBuildHeight()) {
                BlockState st = mc.level.getBlockState(new BlockPos(sp.getX(), y, sp.getZ()));
                if (!st.isAir() && !(st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) && st.blocksMotion()) break;
                y--;
            }
            cmd(mc, "tp @s " + sp.getX() + ".5 " + (y + 1) + " " + sp.getZ() + ".5");
            log("传送到世界出生点 " + sp.getX() + ", " + (y + 1) + ", " + sp.getZ());
        }
        if (pathBench) {
            if (ticks == 300) pathBench(mc);
            if (ticks == 310) mc.stop();
            return;
        }
        if (ticks == 40) {
            Blueprint bp = testBlueprint();
            Map<Item, Integer> need = new HashMap<>();
            for (int y = 0; y < bp.sy; y++)
                for (int z = 0; z < bp.sz; z++)
                    for (int x = 0; x < bp.sx; x++) {
                        BlockState t = bp.get(x, y, z);
                        if (t == null || t.isAir() || StateMatcher.isSecondaryPart(t)) continue;
                        if (replaceTest && REPL.containsKey(t.getBlock())) {
                            t = Placement.replaced(t, REPL.get(t.getBlock()));
                            if (t == null) continue;
                        }
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
            if (subst) {
                // 每种能代替的材料都换成同一族里的另一种
                Map<Item, Integer> swapped = new HashMap<>();
                StringBuilder sb = new StringBuilder();
                for (var en : need.entrySet()) {
                    Item it = en.getKey();
                    net.minecraft.world.level.block.Block b = net.minecraft.world.level.block.Block.byItem(it);
                    var others = com.autobuilder.build.Substitutes.others(b);
                    Item to = it;
                    for (var o : others) {
                        if (o.asItem() != net.minecraft.world.item.Items.AIR) {
                            to = o.asItem();
                            break;
                        }
                    }
                    if (to != it) sb.append(BuiltInRegistries.ITEM.getKey(it).getPath()).append("→").append(BuiltInRegistries.ITEM.getKey(to).getPath()).append(' ');
                    swapped.merge(to, en.getValue(), Integer::sum);
                }
                need.clear();
                need.putAll(swapped);
                log("同类替换：" + sb);
            }
            // 工具和吃的先给，免得材料把背包塞满后工具掉在地上
            cmd(mc, "give @s minecraft:diamond_pickaxe");
            cmd(mc, "give @s minecraft:diamond_shovel");
            cmd(mc, "give @s minecraft:diamond_axe");
            cmd(mc, "give @s minecraft:diamond_hoe");
            cmd(mc, "give @s minecraft:bread 16");
            int stacks = 0;
            for (var en : need.entrySet()) stacks += (en.getValue() + 4 + en.getKey().getDefaultMaxStackSize() - 1) / en.getKey().getDefaultMaxStackSize();
            // 背包放不下（大建筑）：材料放进旁边一排木桶当材料箱
            boolean overflow = !supply && (stacks > 24 || nearby);
            if (supply || overflow) {
                // supply：材料全放进身后的木桶，背包塞满杂物，逼它先存杂物再取货
                BlockPos me = mc.player.blockPosition();
                int nb = Math.max(2, (stacks + 26) / 27);
                for (int i = 0; i < nb; i++) BARRELS.add(video ? me.offset(10 + i, 0, 4) : me.offset(3 + i, 0, 3));
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
                // 一百多格高的建筑要垫很多柱子：多给几组泥土（补货只补蓝图材料，不补垫脚方块）
                if (swamp) cmd(mc, "give @s minecraft:dirt 384");
                if (supply) {
                    for (int i = 0; i < 28; i++) cmd(mc, "give @s minecraft:sandstone 64");
                    log("材料放进木桶 " + slot + " 组，背包塞了 28 组砂岩");
                } else {
                    log("材料 " + stacks + " 组放进 " + nb + " 个木桶" + (nearby ? "（不登记，让它自己去附近找）" : ""));
                    if (dungeon) {
                        // 脚下 8 格埋一个 3×3×3 的封闭石头房间，中间放箱子（够不着，得挖下去才拿得到，像地牢）
                        BlockPos d = me.below(8);
                        cmd(mc, "fill " + (d.getX() - 1) + " " + (d.getY() - 1) + " " + (d.getZ() - 1) + " " + (d.getX() + 1) + " " + (d.getY() + 1) + " "
                                + (d.getZ() + 1) + " minecraft:cobblestone hollow");
                        cmd(mc, "setblock " + d.getX() + " " + d.getY() + " " + d.getZ() + " minecraft:chest");
                        cmd(mc, "item replace block " + d.getX() + " " + d.getY() + " " + d.getZ() + " container.0 with minecraft:oak_planks 64");
                        dungeonPos = d;
                        log("地牢箱子 " + d.toShortString());
                    }
                }
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
            if (!supply && !overflow) cmd(mc, "give @s minecraft:dirt 192");
            if (pool) cmd(mc, "give @s minecraft:bucket 2");
            if (savewater) buildWaterScene(mc);
            log("材料 " + need.size() + " 种，共 " + need.values().stream().mapToInt(i -> i).sum() + " 个");
            if (!extra.isEmpty()) for (String c : extra.split(";")) cmd(mc, c.replace('_', ' '));
        }
        if (ticks == 80 && !(video && menu)) {
            Blueprint bp = testBlueprint();
            Placement p = Placement.inFrontOf(bp, mc.player.blockPosition(), Direction.NORTH);
            if (presetName.equals("sealbox")) p.setOrigin(mc.player.blockPosition().offset(-3, -1, -3)); // 人在盒子正中间
            if (pool) {
                // 建筑整体下沉 2 格，四周多 3 格一起灌满水（泡在湖里）；人挪到水池南边岸上
                p.move(0, -2, 0);
                BlockPos o = p.origin(), mx = p.max();
                String box = (o.getX() - 3) + " " + o.getY() + " " + (o.getZ() - 3) + " " + (mx.getX() + 3) + " " + (o.getY() + 1) + " " + (mx.getZ() + 3);
                cmd(mc, "fill " + box + " minecraft:water");
                cmd(mc, "tp @s " + (o.getX() + p.sizeX() / 2) + " " + (o.getY() + 2) + " " + (mx.getZ() + 6));
                log("水池：" + box);
            }
            BuildController.setPlacement(p);
            log("加载 " + p.describe() + " 玩家 " + mc.player.blockPosition().toShortString());
            if (stream && presetName.equals("pond")) {
                // 西边一条和地板一样高的岸，三面有墙（水只能往平台流），岸上一排水源：水从边上漫进平台
                BlockPos o = p.origin();
                int z0 = o.getZ() - 1, z1 = o.getZ() + p.sizeZ();
                cmd(mc, "fill " + (o.getX() - 5) + " " + o.getY() + " " + z0 + " " + (o.getX() - 1) + " " + (o.getY() + 1) + " " + z1 + " minecraft:cobblestone");
                cmd(mc, "fill " + (o.getX() - 4) + " " + (o.getY() + 1) + " " + o.getZ() + " " + (o.getX() - 1) + " " + (o.getY() + 1) + " " + (z1 - 1) + " minecraft:air");
                for (int dz = 0; dz < p.sizeZ(); dz++) cmd(mc, "setblock " + (o.getX() - 4) + " " + (o.getY() + 1) + " " + (o.getZ() + dz) + " minecraft:water");
                log("流动水：西边岸上 x=" + (o.getX() - 4) + " 一排水源，从边上漫进平台");
            } else if (stream) {
                BlockPos o = p.origin();
                for (int dz = 0; dz < p.sizeZ(); dz++) cmd(mc, "setblock " + (o.getX() - 2) + " " + o.getY() + " " + (o.getZ() + dz) + " minecraft:water");
                // 屋里高处（蓝图是空气）再放一个水源，往下流
                cmd(mc, "setblock " + (o.getX() + 3) + " " + (o.getY() + 3) + " " + (o.getZ() + 3) + " minecraft:water");
                log("流动水：西边 x=" + (o.getX() - 2) + " 一排水源，漫过地基；屋里 " + (o.getX() + 3) + "," + (o.getY() + 3) + "," + (o.getZ() + 3) + " 一个高处水源");
            }
            if (highsrc && presetName.equals("pond")) {
                BlockPos o = p.origin(), mx = p.max();
                int ex = mx.getX() + 1, y0 = o.getY(), z0 = o.getZ() - 1, z1 = mx.getZ() + 1;
                // 水渠：底、东墙、两头封住，灌两格深的水源
                cmd(mc, "fill " + ex + " " + y0 + " " + z0 + " " + (ex + 1) + " " + (y0 + 3) + " " + z1 + " minecraft:cobblestone");
                cmd(mc, "fill " + ex + " " + (y0 + 1) + " " + o.getZ() + " " + ex + " " + (y0 + 3) + " " + mx.getZ() + " minecraft:air");
                // 蓝图东边一排土（蓝图那里是空气，要挖掉）隔着水渠
                cmd(mc, "fill " + mx.getX() + " " + (y0 + 1) + " " + (o.getZ() + 1) + " " + mx.getX() + " " + (y0 + 2) + " " + (mx.getZ() - 1) + " minecraft:dirt");
                // 中间两格换成基岩（挖不动，一直挡着）：这里的水渠永远不会流进来、也就永远不会垫挡水方块
                cmd(mc, "fill " + mx.getX() + " " + (y0 + 1) + " " + (o.getZ() + 4) + " " + mx.getX() + " " + (y0 + 2) + " " + (o.getZ() + 4) + " minecraft:bedrock");
                cmd(mc, "fill " + ex + " " + (y0 + 1) + " " + o.getZ() + " " + ex + " " + (y0 + 2) + " " + mx.getZ() + " minecraft:water");
                // 平台中间：一格土上面一个水源
                BlockPos c = o.offset(4, 1, 4);
                cmd(mc, "setblock " + c.getX() + " " + c.getY() + " " + c.getZ() + " minecraft:dirt");
                cmd(mc, "setblock " + c.getX() + " " + (c.getY() + 1) + " " + c.getZ() + " minecraft:water");
                log("高处水源：东边 x=" + ex + " 两格深的水渠，隔着一排土（中间两格基岩）；平台中间 " + c.above().toShortString() + " 一个水源");
            }
            if (replaceTest) {
                // 走命令这条路：带命名空间、不带命名空间、换成空气各一条，再列一次
                cmd(mc, "ab replace minecraft:oak_planks minecraft:bricks");
                cmd(mc, "ab replace oak_stairs stone_brick_stairs");
                cmd(mc, "ab replace oak_log air");
                cmd(mc, "ab replace");
                log("材料替换表 " + p.replacements());
            }
        }
        if (savewater && ticks == 70) checkWaterSave(mc);
        if (savewater && ticks == 72) {
            // 选区木棍：拿木棍对着塔角，左键=角1（用 give + 客户端换到手上，不覆盖别的物品）
            BlockPos corner = sceneBase.offset(2, 2, 2);
            cmd(mc, "give @s minecraft:stick");
        }
        if (savewater && ticks == 76) {
            com.autobuilder.build.InventoryHelper.selectItem(mc, net.minecraft.world.item.Items.STICK);
            // 镜头直接转向塔角中心（和录像脚本一样在客户端设朝向）
            net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
            net.minecraft.world.phys.Vec3 c = net.minecraft.world.phys.Vec3.atCenterOf(sceneBase.offset(2, 2, 2));
            double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
        }
        if (savewater && ticks == 79) {
            com.autobuilder.build.Selection.clear();
            var hit = mc.player.pick(com.autobuilder.build.SelectionWand.RANGE, 1f, false);
            boolean used = com.autobuilder.build.SelectionWand.click(true);
            BlockPos want = sceneBase.offset(2, 2, 2);
            BlockPos got = com.autobuilder.build.Selection.pos1();
            log("选区木棍：" + (used ? "拦下了点击" : "没拦下！") + " 角1=" + (got == null ? "null" : got.toShortString()) + " 期望 " + want.toShortString()
                    + (want.equals(got) ? " → 通过" : " → 失败") + String.format(" | 朝向 %.1f/%.1f 手上 %s 射线 %s %s", mc.player.getYRot(), mc.player.getXRot(),
                    mc.player.getMainHandItem().getItem(), hit.getType(), hit instanceof net.minecraft.world.phys.BlockHitResult bh ? bh.getBlockPos().toShortString() : ""));
        }
        if (savewater && ticks == 81) cmd(mc, "clear @s minecraft:stick");
        if (dungeon && ticks == 95 && dungeonPos != null && !BARRELS.isEmpty()) {
            // 直接问补货用的寻路：封死的地牢箱子应该算走不到，地面上的木桶应该走得到
            boolean d = BuildController.pathToContainer(dungeonPos) != null;
            boolean b = BuildController.pathToContainer(BARRELS.get(0)) != null;
            log("容器可达检查：地牢箱子 " + (d ? "可达" : "走不到") + "，木桶 " + (b ? "可达" : "走不到") + " → " + (!d && b ? "通过" : "失败"));
        }
        if (ticks == 90 && !BARRELS.isEmpty() && !nearby) {
            com.autobuilder.build.SupplyManager.clear();
            for (BlockPos bpos : BARRELS) com.autobuilder.build.SupplyManager.add(bpos);
            log("登记材料箱 " + BARRELS);
        }
        if (supply && ticks == 91) {
            // 像玩家一样右键第一个木桶（走 useItemOn，会触发右键方块事件、弹出箱子界面）
            BlockPos b0 = BARRELS.get(0);
            net.minecraft.world.phys.Vec3 c = net.minecraft.world.phys.Vec3.atCenterOf(b0).add(0, 0.5, 0);
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(c, Direction.UP, b0, false));
        }
        if (supply && ticks == 97) {
            log("手动打开木桶后界面 " + (mc.screen == null ? "没打开" : mc.screen.getClass().getSimpleName()));
            mc.player.closeContainer();
        }
        if (supply && ticks == 99) {
            for (String l : BuildController.materialLines()) log("材料清单 " + l.replaceAll("§.", ""));
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
        if (ticks == 100 && !(video && menu)) {
            if (!video) shot(mc, "before");
            BuildController.start();
            buildStarted = true;
        }
        if (ticks > 100 && endTick < 0 && buildStarted && !(resume && ticks >= 400 && ticks <= 470)) {
            if (BuildController.state() == BuildController.State.RUNNING) PROFILE.merge(category(BuildController.activity()), 1, Integer::sum);
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
                logProfile();
                Placement p = BuildController.placement();
                BlockPos c = p.origin().offset(p.sizeX() / 2, 0, p.sizeZ() / 2);
                int d = Math.max(p.sizeX(), Math.max(p.sizeY(), p.sizeZ())) + (showcase ? 2 : 6);
                int up = showcase ? 2 : 4;
                if (!video) {
                    cmd(mc, "tp @s " + (c.getX() + d) + " " + (c.getY() + p.sizeY() / 2 + up) + " " + (c.getZ() + d)
                            + " facing " + c.getX() + " " + (c.getY() + p.sizeY() / 2) + " " + c.getZ());
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                }
                BuildController.stop();
                endTick = ticks;
            }
        }
        if (showcase && endTick > 0 && ticks == endTick + 50) {
            tidy(mc);
            mc.options.hideGui = true;
        }
        if (endTick > 0 && ticks == endTick + 60 && !video) shot(mc, "final");
        if (endTick > 0 && ticks == endTick + 90 && !video) {
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
        log("校验结果 " + cnt + " 剩余脚手架 " + BuildController.scaffold().size() + " 没抽干 " + BuildController.drainLeft());
        if (stream) {
            int wet = 0, dam = 0;
            for (BlockPos b : BlockPos.betweenClosed(p.origin(), p.max())) {
                BlockState t = p.targetAt(b);
                if (t != null && !(t.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) && !mc.level.getFluidState(b).isEmpty()) wet++;
            }
            for (int y = 0; y < p.sizeY(); y++)
                for (int z = 0; z < p.sizeZ(); z++) {
                    if (y == 0 && presetName.equals("pond")) continue; // 岸本身
                    BlockPos b = p.origin().offset(-1, y, z);
                    if (com.autobuilder.build.ScaffoldItems.isScaffold(mc.level.getBlockState(b))) dam++;
                }
            boolean ok = wet == 0 && (dam > 0 || !presetName.equals("pond")) && cnt.getOrDefault(StateMatcher.Status.OK, 0) == cnt.values().stream().mapToInt(Integer::intValue).sum();
            log("流动水结果：蓝图里多余的水 " + wet + " 格（应 0），西边挡水方块 " + dam + " 块" + (ok ? " → 通过" : " → 失败"));
        }
        if (highsrc) {
            int wet = 0, dam = 0;
            for (BlockPos b : BlockPos.betweenClosed(p.origin(), p.max())) {
                BlockState t = p.targetAt(b);
                if (t != null && !(t.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) && !mc.level.getFluidState(b).isEmpty()) wet++;
            }
            for (int y = 1; y < p.sizeY(); y++)
                for (int z = 0; z < p.sizeZ(); z++) {
                    BlockPos b = p.origin().offset(p.sizeX(), y, z);
                    if (com.autobuilder.build.ScaffoldItems.isScaffold(mc.level.getBlockState(b))) dam++;
                }
            // 两格基岩挖不动，校验时算对
            boolean ok = wet == 0 && dam > 0 && BuildController.drainLeft() == 0
                    && cnt.getOrDefault(StateMatcher.Status.OK, 0) == cnt.values().stream().mapToInt(Integer::intValue).sum();
            log("高处水源结果：蓝图里多余的水 " + wet + " 格（应 0），东边挡水方块 " + dam + " 块" + (ok ? " → 通过" : " → 失败"));
        }
        if (replaceTest) {
            Map<net.minecraft.world.level.block.Block, Integer> n = new HashMap<>();
            for (BlockPos b : BlockPos.betweenClosed(p.origin(), p.max())) n.merge(mc.level.getBlockState(b).getBlock(), 1, Integer::sum);
            var B = new Object() {
                int c(net.minecraft.world.level.block.Block b) { return n.getOrDefault(b, 0); }
            };
            int planks = B.c(net.minecraft.world.level.block.Blocks.OAK_PLANKS), bricks = B.c(net.minecraft.world.level.block.Blocks.BRICKS);
            int oakStairs = B.c(net.minecraft.world.level.block.Blocks.OAK_STAIRS), stoneStairs = B.c(net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS);
            int logs = B.c(net.minecraft.world.level.block.Blocks.OAK_LOG);
            boolean kept = p.replacements().size() == 3;
            boolean pass = planks == 0 && bricks > 60 && oakStairs == 0 && stoneStairs == 42 && logs == 0 && kept
                    && cnt.getOrDefault(StateMatcher.Status.OK, 0) == cnt.values().stream().mapToInt(Integer::intValue).sum();
            log("材料替换结果：橡木板 " + planks + "（应 0）砖块 " + bricks + " 橡木楼梯 " + oakStairs + "（应 0）石砖楼梯 " + stoneStairs
                    + "（应 42，朝向由校验检查）原木 " + logs + "（应 0，不建）替换表 " + (kept ? "还在" : "丢了！" + p.replacements()) + (pass ? " → 通过" : " → 失败"));
        }
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
        try {
            Blueprint legacy = BlueprintManager.load("[内置]小木屋");
            log("旧名字 [内置]小木屋 → " + legacy.source + " / " + legacy.name);
        } catch (Exception ex) {
            log("旧名字加载失败 " + ex);
        }
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
