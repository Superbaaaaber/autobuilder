package com.autobuilder.build;

import com.autobuilder.AutoBuilder;
import com.autobuilder.blueprint.BlueprintManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;

/**
 * 材料箱登记：玩家指定的箱子/木桶/潜影盒，缺料时自动去取。
 * 按“服务器地址或单人存档名 + 维度”分别保存在 autobuild/supply.json。
 */
public final class SupplyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<BlockPos> CONTAINERS = new LinkedHashSet<>();
    /** 存放箱：砍树/挖矿/挖空时背包满了把挖到的东西存进去（和材料箱分开登记，存在“世界键|dump”下） */
    private static final Set<BlockPos> DUMPS = new LinkedHashSet<>();
    /** 上次打开时看到的内容 */
    private static final Map<BlockPos, Map<Item, Integer>> SEEN = new HashMap<>();
    private static String loadedKey;

    private SupplyManager() {}

    public static String worldKey() {
        return key();
    }

    private static String key() {
        Minecraft mc = Minecraft.getInstance();
        String world;
        if (mc.getSingleplayerServer() != null) world = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) world = "mp:" + mc.getCurrentServer().ip;
        else world = "unknown";
        String dim = mc.level == null ? "?" : mc.level.dimension().location().toString();
        return world + "|" + dim;
    }

    private static Path file() {
        return BlueprintManager.dir().resolve("supply.json");
    }

    private static void ensureLoaded() {
        String k = key();
        if (k.equals(loadedKey)) return;
        loadedKey = k;
        CONTAINERS.clear();
        DUMPS.clear();
        SEEN.clear();
        try {
            if (!Files.exists(file())) return;
            JsonObject root = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            readList(root, k, CONTAINERS);
            readList(root, k + "|dump", DUMPS);
        } catch (Exception ex) {
            AutoBuilder.LOG.warn("读取材料箱列表失败", ex);
        }
    }

    private static void save() {
        try {
            JsonObject root = Files.exists(file())
                    ? GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class) : new JsonObject();
            if (root == null) root = new JsonObject();
            root.add(key(), toJson(CONTAINERS));
            if (DUMPS.isEmpty()) root.remove(key() + "|dump");
            else root.add(key() + "|dump", toJson(DUMPS));
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            AutoBuilder.LOG.warn("保存材料箱列表失败", ex);
        }
    }

    private static void readList(JsonObject root, String k, Set<BlockPos> out) {
        if (!root.has(k)) return;
        for (var e : root.getAsJsonArray(k)) {
            JsonArray a = e.getAsJsonArray();
            out.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
        }
    }

    private static JsonArray toJson(Set<BlockPos> set) {
        JsonArray arr = new JsonArray();
        for (BlockPos p : set) {
            JsonArray a = new JsonArray();
            a.add(p.getX());
            a.add(p.getY());
            a.add(p.getZ());
            arr.add(a);
        }
        return arr;
    }

    // ---------------- 存放箱 ----------------

    public static boolean addDump(BlockPos pos) {
        ensureLoaded();
        boolean added = DUMPS.add(pos.immutable());
        save();
        return added;
    }

    public static boolean removeDump(BlockPos pos) {
        ensureLoaded();
        boolean r = DUMPS.remove(pos);
        save();
        return r;
    }

    public static void clearDumps() {
        ensureLoaded();
        DUMPS.clear();
        save();
    }

    public static List<BlockPos> dumps() {
        ensureLoaded();
        return new ArrayList<>(DUMPS);
    }

    public static boolean anyDump() {
        ensureLoaded();
        return !DUMPS.isEmpty();
    }

    public static boolean isDump(BlockPos pos) {
        ensureLoaded();
        return DUMPS.contains(pos);
    }

    public static boolean isContainer(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof Container;
    }

    public static boolean add(BlockPos pos) {
        ensureLoaded();
        boolean added = CONTAINERS.add(pos.immutable());
        save();
        return added;
    }

    public static boolean remove(BlockPos pos) {
        ensureLoaded();
        boolean r = CONTAINERS.remove(pos);
        SEEN.remove(pos);
        save();
        return r;
    }

    public static void clear() {
        ensureLoaded();
        CONTAINERS.clear();
        SEEN.clear();
        save();
    }

    public static List<BlockPos> list() {
        ensureLoaded();
        return new ArrayList<>(CONTAINERS);
    }

    public static boolean isRegistered(BlockPos pos) {
        ensureLoaded();
        return CONTAINERS.contains(pos);
    }

    /**
     * 附近的箱子、木桶、潜影盒（没登记的也算），用来找缺的材料。
     * 陷阱箱不碰（打开会发红石信号）；大箱子只算一半；蓝图里本来就要放的容器不算。
     */
    public static List<BlockPos> nearby(BlockPos center, int r) {
        Minecraft mc = Minecraft.getInstance();
        List<BlockPos> out = new ArrayList<>();
        if (mc.level == null) return out;
        Placement pl = BuildController.placement();
        for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
            for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
                var ch = mc.level.getChunkSource().getChunk(cx, cz, false);
                if (ch == null) continue;
                for (var be : ch.getBlockEntities().values()) {
                    boolean ok = be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                            && !(be instanceof net.minecraft.world.level.block.entity.TrappedChestBlockEntity)
                            || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
                            || be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
                    if (!ok) continue;
                    BlockPos p = be.getBlockPos();
                    if (p.distSqr(center) > (double) r * r) continue;
                    var st = be.getBlockState();
                    if (st.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                            && st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) continue;
                    if (pl != null && pl.targetAt(p) != null && !pl.targetAt(p).isAir()) continue;
                    out.add(p.immutable());
                }
            }
        }
        return out;
    }

    /** 登记的材料箱里上次看到的这种物品总数（没打开过的不算） */
    public static int supplyCount(Item item) {
        ensureLoaded();
        int n = 0;
        for (BlockPos c : CONTAINERS) {
            Map<Item, Integer> m = SEEN.get(c);
            if (m != null) n += m.getOrDefault(item, 0);
        }
        return n;
    }

    /** 登记了但还不知道里面有什么的材料箱个数 */
    public static int unknownSupplies() {
        ensureLoaded();
        int n = 0;
        for (BlockPos c : CONTAINERS) if (!SEEN.containsKey(c)) n++;
        return n;
    }

    /** 玩家最近右键的方块：打开容器时靠它知道开的是哪个箱子 */
    private static BlockPos lastClicked;
    private static int lastClickedTick;
    private static int boundMenu = -1;
    private static BlockPos boundPos;

    public static void clicked(BlockPos pos) {
        lastClicked = pos.immutable();
        lastClickedTick = BuildController.now();
    }

    /** 每 tick：开着登记过的材料箱/存放箱时记下里面有什么（玩家自己打开的也算，/ab materials 要用） */
    public static void watchOpen(Minecraft mc) {
        var p = mc.player;
        if (p == null) return;
        if (p.containerMenu == p.inventoryMenu) {
            boundMenu = -1;
            return;
        }
        int id = p.containerMenu.containerId;
        if (id != boundMenu) {
            boundMenu = id;
            boundPos = lastClicked != null && BuildController.now() - lastClickedTick <= 20 ? lastClicked : null;
        }
        if (boundPos == null || BuildController.now() % 5 != 0) return;
        if (!isRegistered(boundPos) && !isDump(boundPos)) return;
        Map<Item, Integer> m = new HashMap<>();
        for (var s : p.containerMenu.slots) {
            if (s.container == p.getInventory() || !s.hasItem()) continue;
            m.merge(s.getItem().getItem(), s.getItem().getCount(), Integer::sum);
        }
        SEEN.put(boundPos, m);
    }

    /** 上次看到时有多少这种物品或它的同类代替品；没打开过返回 -1 */
    public static int knownCountLike(BlockPos pos, Item item) {
        Map<Item, Integer> m = SEEN.get(pos);
        if (m == null) return -1;
        int n = 0;
        for (var e : m.entrySet()) if (Substitutes.sameItem(item, e.getKey())) n += e.getValue();
        return n;
    }

    public static boolean any() {
        ensureLoaded();
        return !CONTAINERS.isEmpty();
    }

    public static void remember(BlockPos pos, Map<Item, Integer> contents) {
        SEEN.put(pos.immutable(), contents);
    }

    /** 这个箱子上次看到时有没有这些物品；没打开过返回 -1 */
    public static int knownCount(BlockPos pos, Item item) {
        Map<Item, Integer> m = SEEN.get(pos);
        if (m == null) return -1;
        return m.getOrDefault(item, 0);
    }
}
