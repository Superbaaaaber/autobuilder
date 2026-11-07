package com.autobuilder.command;

import com.autobuilder.AutoBuilder;
import com.autobuilder.Lang;
import com.autobuilder.blueprint.Blueprint;
import com.autobuilder.blueprint.BlueprintManager;
import com.autobuilder.blueprint.BlueprintSaver;
import com.autobuilder.build.BuildController;
import com.autobuilder.build.Placement;
import com.autobuilder.build.Scanner;
import com.autobuilder.build.Selection;
import com.autobuilder.build.SupplyManager;
import com.autobuilder.gui.BuilderScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * 客户端命令 /autobuild（别名 /ab）。只在本地执行，不会发给服务器。
 * 输入 /ab 或 /ab help 会列出所有指令和中文说明，点击指令可填进聊天栏。
 */
public final class ABCommands {
    private ABCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("autobuild")
                // /ab：不带参数时显示帮助
                .executes(c -> help())
                // /ab help [页码]：显示所有指令和中文说明（共 2 页）
                .then(Commands.literal("help").executes(c -> help())
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 3))
                                .executes(c -> help(IntegerArgumentType.getInteger(c, "page")))))

                // ===== 蓝图 =====
                // /ab list：列出内置预设和 autobuild 文件夹里的蓝图
                .then(Commands.literal("list").executes(c -> list()))
                // /ab load <名字>：加载蓝图，摆在玩家面前、正面朝向玩家（名字可以按 Tab 补全，可省略扩展名）
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        BlueprintManager.list().stream().map(BlueprintManager.Entry::name), b))
                                .executes(c -> load(StringArgumentType.getString(c, "name")))))
                // /ab unload：卸载当前蓝图并删掉它的断点进度（已经建好的方块不会拆）
                .then(Commands.literal("unload").executes(c -> {
                    BuildController.unload();
                    return ok(Lang.t("cmd.unloaded"));
                }))

                // ===== 调整摆放位置 =====
                // /ab rotate：绕包围盒中心顺时针转 90°
                .then(Commands.literal("rotate").executes(c -> withP(p -> p.rotateCW(), Lang.t("cmd.rotated"))))
                // /ab mirror：镜像在 无 → 南北翻转 → 东西翻转 之间切换
                .then(Commands.literal("mirror").executes(c -> withP(Placement::cycleMirror, Lang.t("cmd.mirrored"))))
                // /ab move <dx> <dy> <dz>：整体平移，单位是格，正数朝东/上/南
                .then(Commands.literal("move")
                        .then(Commands.argument("dx", IntegerArgumentType.integer())
                                .then(Commands.argument("dy", IntegerArgumentType.integer())
                                        .then(Commands.argument("dz", IntegerArgumentType.integer())
                                                .executes(c -> withP(p -> p.move(IntegerArgumentType.getInteger(c, "dx"),
                                                        IntegerArgumentType.getInteger(c, "dy"),
                                                        IntegerArgumentType.getInteger(c, "dz")), Lang.t("cmd.moved")))))))
                // /ab origin [坐标]：把蓝图原点（包围盒西北下角）移到脚下，或移到指定坐标
                .then(Commands.literal("origin")
                        .executes(c -> withP(p -> p.setOrigin(Minecraft.getInstance().player.blockPosition()), Lang.t("cmd.origin_feet")))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> withP(p -> p.setOrigin(pos(c, "pos")), Lang.t("cmd.origin_set")))))

                // ===== 建造控制 =====
                // /ab start：开始或继续建造（同 N 键）
                .then(Commands.literal("start").executes(c -> {
                    BuildController.start();
                    return 1;
                }))
                // /ab pause：暂停，进度保留，start 继续
                .then(Commands.literal("pause").executes(c -> {
                    BuildController.pause(Lang.t("reason.manual"));
                    return 1;
                }))
                // /ab stop：停下来，蓝图和断点进度都保留
                .then(Commands.literal("stop").executes(c -> {
                    BuildController.stop();
                    return ok(Lang.t("cmd.stopped"));
                }))
                // /ab status：显示摆放信息、进度和当前在做的事
                .then(Commands.literal("status").executes(c -> status()))
                // /ab materials：材料清单，需要多少、背包里有多少、还缺多少
                .then(Commands.literal("materials").executes(c -> materials()))

                // ===== 砍树挖矿 =====
                // /ab chop [半径]：以站的位置为中心砍附近的天然树
                .then(Commands.literal("chop")
                        .executes(c -> chop(24))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 64))
                                .executes(c -> chop(IntegerArgumentType.getInteger(c, "radius")))))
                // /ab mine [主巷长度] [支巷长度]：在脚下高度往面朝方向挖支巷，挖露出来的矿
                .then(Commands.literal("mine")
                        .executes(c -> mine(48, 12))
                        .then(Commands.argument("length", IntegerArgumentType.integer(4, 256))
                                .executes(c -> mine(IntegerArgumentType.getInteger(c, "length"), 12))
                                .then(Commands.argument("branch", IntegerArgumentType.integer(0, 64))
                                        .executes(c -> mine(IntegerArgumentType.getInteger(c, "length"), IntegerArgumentType.getInteger(c, "branch"))))))

                // ===== 保存建筑 =====
                // /ab pos1 [坐标]、/ab pos2 [坐标]：设置选区的两个角，不写坐标就用准星对着的方块（没对着方块就用脚下）
                .then(Commands.literal("pos1")
                        .executes(c -> setPos(1, Selection.crosshairOrFeet()))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> setPos(1, pos(c, "pos")))))
                .then(Commands.literal("pos2")
                        .executes(c -> setPos(2, Selection.crosshairOrFeet()))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> setPos(2, pos(c, "pos")))))
                // /ab save <名字>：把选区存成蓝图，空气也存（照着建时会挖掉多余的方块）
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> saveSelection(StringArgumentType.getString(c, "name"), true))))
                // /ab savesolid <名字>：把选区存成蓝图，不存空气（照着建时不动原有方块）
                .then(Commands.literal("savesolid")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> saveSelection(StringArgumentType.getString(c, "name"), false))))
                // /ab saveregion <角1> <角2> <名字>：不用选区，直接按两个坐标保存（含空气）
                .then(Commands.literal("saveregion")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(c -> save(pos(c, "from"), pos(c, "to"), StringArgumentType.getString(c, "name"), true))))))

                // ===== 材料箱（缺料时自动去取） =====
                .then(Commands.literal("supply")
                        // /ab supply add [坐标]：登记材料箱，不写坐标就用准星对着的箱子/木桶/潜影盒
                        .then(Commands.literal("add")
                                .executes(c -> supplyAdd(Selection.crosshairOrFeet()))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> supplyAdd(pos(c, "pos")))))
                        // /ab supply remove [坐标]：取消登记
                        .then(Commands.literal("remove")
                                .executes(c -> supplyRemove(Selection.crosshairOrFeet()))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> supplyRemove(pos(c, "pos")))))
                        // /ab supply list：列出当前世界、当前维度登记的材料箱
                        .then(Commands.literal("list").executes(c -> supplyList()))
                        // /ab supply clear：清空当前世界、当前维度的材料箱
                        .then(Commands.literal("clear").executes(c -> {
                            SupplyManager.clear();
                            return ok(Lang.t("cmd.supply_cleared"));
                        })))

                // ===== 界面 =====
                // /ab menu：打开主界面（同 B 键）
                .then(Commands.literal("menu").executes(c -> {
                    Minecraft.getInstance().setScreen(new BuilderScreen());
                    return 1;
                }))
                // /ab config：打开设置界面
                .then(Commands.literal("config").executes(c -> {
                    AutoBuilder.openConfig(null);
                    return 1;
                }))
                // /ab hide：隐藏/显示投影（隐藏时照常建造），同 H 键
                .then(Commands.literal("hide").executes(c -> {
                    com.autobuilder.ClientEvents.togglePreview();
                    return 1;
                }))
                // /ab wand [on|off]：选区木棍说明，或者开关
                .then(Commands.literal("wand")
                        .executes(c -> wand(null))
                        .then(Commands.literal("on").executes(c -> wand(true)))
                        .then(Commands.literal("off").executes(c -> wand(false))));
        var node = d.register(root);
        // /ab 是 /autobuild 的简写
        d.register(Commands.literal("ab").redirect(node).executes(c -> help()));
    }

    // ---------------- 帮助 ----------------

    /**
     * 一条帮助：key 对应 help.<key>.usage（写法）、help.<key>（一句话说明）、help.<key>.detail（悬停时的详细说明）；
     * fill 是点击后填进聊天栏的文字。
     */
    private record Help(String key, String fill) {}

    /** 三页帮助，每页不超过聊天栏展开时能看到的 20 行；字符串是分类（help.sec.xxx） */
    private static final List<List<Object>> PAGES = List.of(List.of(
            "blueprint",
            new Help("list", "/ab list"),
            new Help("load", "/ab load "),
            new Help("unload", "/ab unload"),
            "place",
            new Help("rotate", "/ab rotate"),
            new Help("mirror", "/ab mirror"),
            new Help("move", "/ab move "),
            new Help("origin", "/ab origin"),
            "build",
            new Help("start", "/ab start"),
            new Help("pause", "/ab pause"),
            new Help("stop", "/ab stop"),
            new Help("status", "/ab status"),
            new Help("materials", "/ab materials")
    ), List.of(
            "save",
            new Help("pos", "/ab pos1"),
            new Help("wand", "/ab wand"),
            new Help("save", "/ab save "),
            new Help("savesolid", "/ab savesolid "),
            new Help("saveregion", "/ab saveregion "),
            "supply",
            new Help("supplyadd", "/ab supply add"),
            new Help("supplyremove", "/ab supply remove"),
            new Help("supplylist", "/ab supply list"),
            new Help("supplyclear", "/ab supply clear")
    ), List.of(
            "gather",
            new Help("chop", "/ab chop "),
            new Help("mine", "/ab mine "),
            "ui",
            new Help("menu", "/ab menu"),
            new Help("config", "/ab config"),
            new Help("hide", "/ab hide")
    ));

    private static int help() {
        return help(1);
    }

    private static int help(int page) {
        var p = Minecraft.getInstance().player;
        int total = PAGES.size();
        p.displayClientMessage(Component.literal(Lang.t("help.header", page, total))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        if (page == 1) {
            p.displayClientMessage(Component.literal(Lang.t("help.intro")).withStyle(ChatFormatting.GRAY), false);
        }
        for (Object o : PAGES.get(page - 1)) {
            if (o instanceof String section) {
                p.displayClientMessage(Component.literal(Lang.t("help.sec." + section)).withStyle(ChatFormatting.GOLD), false);
                continue;
            }
            Help h = (Help) o;
            String usage = Lang.t("help." + h.key + ".usage");
            String brief = Lang.t("help." + h.key);
            MutableComponent hover = Component.literal(usage).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("\n" + brief).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("\n" + Lang.t("help." + h.key + ".detail")).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("\n" + Lang.t("help.click")).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            Style click = Style.EMPTY.withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, h.fill))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
            p.displayClientMessage(Component.literal(" " + usage).withStyle(click)
                    .append(Component.literal("  " + brief).withStyle(ChatFormatting.GRAY)), false);
        }
        if (page == total) {
            p.displayClientMessage(Component.literal(Lang.t("help.keys")).withStyle(ChatFormatting.DARK_AQUA), false);
        }
        // 翻页按钮
        MutableComponent nav = Component.empty();
        if (page > 1) nav.append(pageLink(Lang.t("help.prev"), page - 1)).append(Component.literal("  "));
        if (page < total) nav.append(pageLink(Lang.t("help.next"), page + 1));
        if (!nav.getSiblings().isEmpty()) p.displayClientMessage(nav, false);
        return 1;
    }

    private static MutableComponent pageLink(String text, int page) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ab help " + page))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Lang.t("help.page", page)))));
    }

    // ---------------- 工具方法 ----------------

    private static BlockPos pos(CommandContext<CommandSourceStack> c, String name) {
        return c.getArgument(name, Coordinates.class).getBlockPos(c.getSource());
    }

    private static void say(String s) {
        Minecraft.getInstance().player.displayClientMessage(Component.literal(s), false);
    }

    private static int ok(String s) {
        BuildController.msg(s, ChatFormatting.GREEN);
        return 1;
    }

    private static int err(String s) {
        BuildController.msg(s, ChatFormatting.RED);
        return 0;
    }

    // ---------------- 各指令的实现 ----------------

    private static int list() {
        var l = BlueprintManager.list();
        say("§6" + Lang.t("cmd.list_title", l.size()));
        for (var e : l) say(e.builtin() ? " §e" + e.name() + " §7" + e.display() : " §f" + e.name());
        return 1;
    }

    private static int load(String name) {
        try {
            Blueprint bp = BlueprintManager.load(name.trim());
            var p = Minecraft.getInstance().player;
            BuildController.setPlacement(Placement.inFrontOf(bp, p.blockPosition(), p.getDirection()));
            return ok(Lang.t("cmd.loaded", bp.name, bp.sizeText(), bp.solidCount()));
        } catch (Exception e) {
            return err(Lang.t("msg.load_failed", e.getMessage()));
        }
    }

    /** 对当前摆放做修改；没加载蓝图时报错 */
    private static int withP(java.util.function.Consumer<Placement> c, String done) {
        Placement p = BuildController.placement();
        if (p == null) return err(Lang.t("msg.no_blueprint"));
        c.accept(p);
        return ok(Lang.t("cmd.done_describe", done, p.describe()));
    }

    private static int status() {
        Placement p = BuildController.placement();
        if (p == null) return err(Lang.t("msg.no_blueprint"));
        say("§b" + p.describe());
        Scanner.Snapshot s = BuildController.snapshot();
        if (s != null) {
            say("§f" + Lang.t("cmd.status_progress", s.percent(), s.ok, s.total, s.place.size(), s.breaks.size(),
                    s.interact.size(), s.wrong.size(), s.unsupported));
        }
        say("§7" + Lang.t("cmd.status_state", BuildController.state(), BuildController.phase(), BuildController.activity(),
                BuildController.scaffold().size()));
        return 1;
    }

    private static int materials() {
        var lines = BuildController.materialLines();
        if (lines.isEmpty()) return err(Lang.t("cmd.no_scan"));
        say("§6" + Lang.t("msg.materials_title"));
        for (String l : lines) say("  " + l);
        return 1;
    }

    private static int setPos(int which, BlockPos p) {
        Selection.set(which, p);
        return ok(Lang.t("msg.corner", which, p.toShortString(), Selection.describe()));
    }

    private static int chop(int radius) {
        if (!com.autobuilder.build.ServerPolicy.allowed()) return err(Lang.t("msg.server_disabled_token", com.autobuilder.build.ServerPolicy.TOKEN));
        com.autobuilder.build.Gather.startChop(radius);
        return 1;
    }

    private static int mine(int length, int branch) {
        if (!com.autobuilder.build.ServerPolicy.allowed()) return err(Lang.t("msg.server_disabled_token", com.autobuilder.build.ServerPolicy.TOKEN));
        com.autobuilder.build.Gather.startMine(length, branch);
        return 1;
    }

    private static int wand(Boolean on) {
        if (on != null) {
            com.autobuilder.ABConfig.SELECTION_WAND.set(on);
            com.autobuilder.ABConfig.SPEC.save();
        }
        String item = new net.minecraft.world.item.ItemStack(com.autobuilder.build.SelectionWand.item()).getHoverName().getString();
        return ok(Lang.t(com.autobuilder.ABConfig.SELECTION_WAND.get() ? "cmd.wand_on" : "cmd.wand_off", item));
    }

    private static int saveSelection(String name, boolean includeAir) {
        if (!Selection.complete()) return err(Lang.t("cmd.need_corners"));
        return save(Selection.pos1(), Selection.pos2(), name, includeAir);
    }

    private static int supplyAdd(BlockPos p) {
        if (!SupplyManager.isContainer(p)) return err(Lang.t("cmd.not_container", p.toShortString()));
        SupplyManager.add(p);
        return ok(Lang.t("cmd.supply_added", p.toShortString(), SupplyManager.list().size()));
    }

    private static int supplyRemove(BlockPos p) {
        return SupplyManager.remove(p) ? ok(Lang.t("cmd.supply_removed", p.toShortString())) : err(Lang.t("cmd.not_supply"));
    }

    private static int supplyList() {
        var l = SupplyManager.list();
        say("§6" + Lang.t("cmd.supply_title", l.size()));
        for (var p : l) say(" §f" + p.toShortString());
        return 1;
    }

    private static int save(BlockPos a, BlockPos b, String name, boolean includeAir) {
        try {
            // 从选区外面连进来的水（湖、河、海）不存，选区里面的水流照常存
            BlueprintSaver.Result r = BlueprintSaver.save(Minecraft.getInstance().level, a, b, name.trim(), includeAir,
                    com.autobuilder.ABConfig.DROP_OUTER_WATER.get());
            if (r.droppedWater() > 0 || r.dried() > 0) say("\u00a77" + Lang.t("cmd.saved_water", r.droppedWater(), r.dried()));
            if (r.keptWater() > 0) say("\u00a77" + Lang.t("cmd.saved_kept_water", r.keptWater()));
            return ok(Lang.t("cmd.saved", r.path()));
        } catch (Exception e) {
            return err(Lang.t("msg.save_failed", e.getMessage()));
        }
    }
}
