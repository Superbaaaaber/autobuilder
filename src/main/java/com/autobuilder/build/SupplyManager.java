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
        SEEN.clear();
        try {
            if (!Files.exists(file())) return;
            JsonObject root = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || !root.has(k)) return;
            for (var e : root.getAsJsonArray(k)) {
                JsonArray a = e.getAsJsonArray();
                CONTAINERS.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
            }
        } catch (Exception ex) {
            AutoBuilder.LOG.warn("读取材料箱列表失败", ex);
        }
    }

    private static void save() {
        try {
            JsonObject root = Files.exists(file())
                    ? GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class) : new JsonObject();
            if (root == null) root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (BlockPos p : CONTAINERS) {
                JsonArray a = new JsonArray();
                a.add(p.getX());
                a.add(p.getY());
                a.add(p.getZ());
                arr.add(a);
            }
            root.add(key(), arr);
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            AutoBuilder.LOG.warn("保存材料箱列表失败", ex);
        }
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
