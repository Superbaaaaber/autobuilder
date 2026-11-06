package com.autobuilder.command;

import com.autobuilder.AutoBuilder;
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
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 2))
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
                    return ok("已卸载蓝图");
                }))

                // ===== 调整摆放位置 =====
                // /ab rotate：绕包围盒中心顺时针转 90°
                .then(Commands.literal("rotate").executes(c -> withP(p -> p.rotateCW(), "已顺时针旋转 90°")))
                // /ab mirror：镜像在 无 → 南北翻转 → 东西翻转 之间切换
                .then(Commands.literal("mirror").executes(c -> withP(Placement::cycleMirror, "已切换镜像")))
                // /ab move <dx> <dy> <dz>：整体平移，单位是格，正数朝东/上/南
                .then(Commands.literal("move")
                        .then(Commands.argument("dx", IntegerArgumentType.integer())
                                .then(Commands.argument("dy", IntegerArgumentType.integer())
                                        .then(Commands.argument("dz", IntegerArgumentType.integer())
                                                .executes(c -> withP(p -> p.move(IntegerArgumentType.getInteger(c, "dx"),
                                                        IntegerArgumentType.getInteger(c, "dy"),
                                                        IntegerArgumentType.getInteger(c, "dz")), "已移动"))))))
                // /ab origin [坐标]：把蓝图原点（包围盒西北下角）移到脚下，或移到指定坐标
                .then(Commands.literal("origin")
                        .executes(c -> withP(p -> p.setOrigin(Minecraft.getInstance().player.blockPosition()), "原点已移到脚下"))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> withP(p -> p.setOrigin(pos(c, "pos")), "原点已设置"))))

                // ===== 建造控制 =====
                // /ab start：开始或继续建造（同 N 键）
                .then(Commands.literal("start").executes(c -> {
                    BuildController.start();
                    return 1;
                }))
                // /ab pause：暂停，进度保留，start 继续
                .then(Commands.literal("pause").executes(c -> {
                    BuildController.pause("手动暂停");
                    return 1;
                }))
                // /ab stop：停下来，蓝图和断点进度都保留
                .then(Commands.literal("stop").executes(c -> {
                    BuildController.stop();
                    return ok("已停止");
                }))
                // /ab status：显示摆放信息、进度和当前在做的事
                .then(Commands.literal("status").executes(c -> status()))
                // /ab materials：材料清单，需要多少、背包里有多少、还缺多少
                .then(Commands.literal("materials").executes(c -> materials()))

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
                            return ok("已清空材料箱列表");
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
                }));
        var node = d.register(root);
        // /ab 是 /autobuild 的简写
        d.register(Commands.literal("ab").redirect(node).executes(c -> help()));
    }

    // ---------------- 帮助 ----------------

    /** 一条帮助：显示的写法、点击后填进聊天栏的文字、一句话说明、鼠标悬停时的详细说明 */
    private record Help(String usage, String fill, String brief, String detail) {}

    /** 两页帮助，每页不超过聊天栏展开时能看到的 20 行；字符串是分类标题 */
    private static final List<List<Object>> PAGES = List.of(List.of(
            "蓝图",
            new Help("/ab list", "/ab list", "列出所有蓝图",
                    "内置预设（黄色）和 autobuild 文件夹里的 .litematic / .schem / .nbt 文件"),
            new Help("/ab load <名字>", "/ab load ", "加载蓝图，摆在你面前",
                    "正面朝向你，放在你面前 2 格处。名字可以按 Tab 补全，扩展名可以不写"),
            new Help("/ab unload", "/ab unload", "卸载当前蓝图",
                    "同时删掉这个蓝图的断点进度；已经建好的方块不会拆"),
            "调整位置",
            new Help("/ab rotate", "/ab rotate", "顺时针转 90°", "绕包围盒中心转"),
            new Help("/ab mirror", "/ab mirror", "切换镜像", "无 → 南北翻转 → 东西翻转 → 无"),
            new Help("/ab move <x> <y> <z>", "/ab move ", "平移若干格",
                    "正数朝东 / 上 / 南。例：/ab move 0 -1 0 整体下移一格"),
            new Help("/ab origin [坐标]", "/ab origin", "原点移到脚下",
                    "原点是包围盒的西北下角；也可以写坐标，如 /ab origin 100 64 -20"),
            "建造",
            new Help("/ab start", "/ab start", "开始 / 继续建造（N 键）",
                    "按移动键会自动暂停，方便手动接管；按 N 或再执行一次继续"),
            new Help("/ab pause", "/ab pause", "暂停", "进度保留，/ab start 继续"),
            new Help("/ab stop", "/ab stop", "停止",
                    "蓝图和断点进度都保留；退出世界再进来也能接着建"),
            new Help("/ab status", "/ab status", "查看进度和状态", "进度、待放 / 待挖数量、正在做的事"),
            new Help("/ab materials", "/ab materials", "材料清单", "每种材料需要多少、背包里有多少、还缺多少")
    ), List.of(
            "保存建筑",
            new Help("/ab pos1 [坐标]  /ab pos2 [坐标]", "/ab pos1", "选区的两个角",
                    "不写坐标就用准星对着的方块。也可以直接按 [ 和 ] 键"),
            new Help("/ab save <名字>", "/ab save ", "保存选区（含空气）",
                    "照着建时会把蓝图里是空气的位置清空。存到 autobuild 文件夹，格式 .nbt"),
            new Help("/ab savesolid <名字>", "/ab savesolid ", "保存选区（不含空气）",
                    "照着建时不动原有方块，适合在别人地形上叠加"),
            new Help("/ab saveregion <角1> <角2> <名字>", "/ab saveregion ", "按坐标直接保存",
                    "不用先选区，例：/ab saveregion 0 64 0 10 70 10 我的房子"),
            "材料箱（缺料时自动去取）",
            new Help("/ab supply add [坐标]", "/ab supply add", "登记材料箱",
                    "准星对着箱子 / 木桶 / 潜影盒执行。按世界和维度分别保存"),
            new Help("/ab supply remove [坐标]", "/ab supply remove", "取消登记", "准星对着要取消的箱子执行"),
            new Help("/ab supply list", "/ab supply list", "列出材料箱", "当前世界、当前维度的"),
            new Help("/ab supply clear", "/ab supply clear", "清空材料箱", "只清当前世界、当前维度的"),
            "界面",
            new Help("/ab menu", "/ab menu", "打开主界面（B 键）", "选蓝图、调位置、开始建造、保存建筑"),
            new Help("/ab config", "/ab config", "打开设置", "触及距离、放置方式、脚手架、自动进食等")
    ));

    private static int help() {
        return help(1);
    }

    private static int help(int page) {
        var p = Minecraft.getInstance().player;
        int total = PAGES.size();
        p.displayClientMessage(Component.literal("===== AutoBuilder 指令（第 " + page + "/" + total + " 页）=====")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        if (page == 1) {
            p.displayClientMessage(Component.literal("/ab 等同 /autobuild；点击指令填进聊天栏，鼠标悬停看详细说明")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        for (Object o : PAGES.get(page - 1)) {
            if (o instanceof String section) {
                p.displayClientMessage(Component.literal("【" + section + "】").withStyle(ChatFormatting.GOLD), false);
                continue;
            }
            Help h = (Help) o;
            MutableComponent hover = Component.literal(h.usage).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("\n" + h.brief).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("\n" + h.detail).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("\n点击填入聊天栏").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            Style click = Style.EMPTY.withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, h.fill))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
            p.displayClientMessage(Component.literal(" " + h.usage).withStyle(click)
                    .append(Component.literal("  " + h.brief).withStyle(ChatFormatting.GRAY)), false);
        }
        if (page == total) {
            p.displayClientMessage(Component.literal("快捷键：B 主界面  N 开始/暂停  [ ] 选区角（可在按键绑定里改）")
                    .withStyle(ChatFormatting.DARK_AQUA), false);
        }
        // 翻页按钮
        MutableComponent nav = Component.empty();
        if (page > 1) nav.append(pageLink("[← 上一页]", page - 1)).append(Component.literal("  "));
        if (page < total) nav.append(pageLink("[下一页 →]", page + 1));
        if (!nav.getSiblings().isEmpty()) p.displayClientMessage(nav, false);
        return 1;
    }

    private static MutableComponent pageLink(String text, int page) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ab help " + page))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("显示第 " + page + " 页"))));
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
        say("§6蓝图（" + l.size() + "）：");
        for (var e : l) say((e.builtin() ? " §e" : " §f") + e.name());
        return 1;
    }

    private static int load(String name) {
        try {
            Blueprint bp = BlueprintManager.load(name.trim());
            var p = Minecraft.getInstance().player;
            BuildController.setPlacement(Placement.inFrontOf(bp, p.blockPosition(), p.getDirection()));
            return ok("已加载 " + bp.name + "（" + bp.sizeText() + "，" + bp.solidCount() + " 个方块），用 /ab start 开始");
        } catch (Exception e) {
            return err("加载失败：" + e.getMessage());
        }
    }

    /** 对当前摆放做修改；没加载蓝图时报错 */
    private static int withP(java.util.function.Consumer<Placement> c, String done) {
        Placement p = BuildController.placement();
        if (p == null) return err("还没有加载蓝图");
        c.accept(p);
        return ok(done + "：" + p.describe());
    }

    private static int status() {
        Placement p = BuildController.placement();
        if (p == null) return err("还没有加载蓝图");
        say("§b" + p.describe());
        Scanner.Snapshot s = BuildController.snapshot();
        if (s != null) {
            say("§f进度 " + s.percent() + "%（" + s.ok + "/" + s.total + "） 待放 " + s.place.size() + " 待挖 " + s.breaks.size()
                    + " 待调整 " + s.interact.size() + " 不对 " + s.wrong.size() + " 无法放置 " + s.unsupported);
        }
        say("§7状态 " + BuildController.state() + " / " + BuildController.phase() + "：" + BuildController.activity()
                + "  脚手架 " + BuildController.scaffold().size());
        return 1;
    }

    private static int materials() {
        var lines = BuildController.materialLines();
        if (lines.isEmpty()) return err("还没有扫描结果（先加载蓝图）");
        say("§6材料清单：");
        for (String l : lines) say("  " + l);
        return 1;
    }

    private static int setPos(int which, BlockPos p) {
        Selection.set(which, p);
        return ok("选区角" + which + " = " + p.toShortString() + "  " + Selection.describe());
    }

    private static int saveSelection(String name, boolean includeAir) {
        if (!Selection.complete()) return err("先用 /ab pos1、/ab pos2（或 [ ] 键）选好两个角");
        return save(Selection.pos1(), Selection.pos2(), name, includeAir);
    }

    private static int supplyAdd(BlockPos p) {
        if (!SupplyManager.isContainer(p)) return err("这里不是箱子/木桶/潜影盒：" + p.toShortString());
        SupplyManager.add(p);
        return ok("已添加材料箱 " + p.toShortString() + "（共 " + SupplyManager.list().size() + " 个）");
    }

    private static int supplyRemove(BlockPos p) {
        return SupplyManager.remove(p) ? ok("已移除材料箱 " + p.toShortString()) : err("这里不是材料箱");
    }

    private static int supplyList() {
        var l = SupplyManager.list();
        say("§6材料箱（" + l.size() + "）：");
        for (var p : l) say(" §f" + p.toShortString());
        return 1;
    }

    private static int save(BlockPos a, BlockPos b, String name, boolean includeAir) {
        try {
            Path out = BlueprintSaver.save(Minecraft.getInstance().level, a, b, name.trim(), includeAir);
            return ok("已保存到 " + out);
        } catch (Exception e) {
            return err("保存失败：" + e.getMessage());
        }
    }
}
