package com.autobuilder.build;

import com.autobuilder.AutoBuilder;
import com.autobuilder.blueprint.Blueprint;
import com.autobuilder.blueprint.BlueprintManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * 断点续建：把当前蓝图、摆放位置、旋转/镜像和自己搭的脚手架按“世界 + 维度”存到 autobuild/session.json。
 * 退出/掉线/重启后回到同一个世界会自动恢复（不会自动开工）。
 */
public final class SessionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static String lastSaved = "";

    private SessionStore() {}

    private static Path file() {
        return BlueprintManager.dir().resolve("session.json");
    }

    private static JsonObject readRoot() {
        try {
            if (Files.exists(file())) {
                JsonObject o = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
                if (o != null) return o;
            }
        } catch (Exception e) {
            AutoBuilder.LOG.warn("读取断点存档失败", e);
        }
        return new JsonObject();
    }

    private static void writeRoot(JsonObject root) {
        try {
            String json = GSON.toJson(root);
            if (json.equals(lastSaved)) return;
            Files.writeString(file(), json, StandardCharsets.UTF_8);
            lastSaved = json;
        } catch (Exception e) {
            AutoBuilder.LOG.warn("写入断点存档失败", e);
        }
    }

    public static void save(String key, Placement pl, Set<BlockPos> scaffold) {
        if (pl == null || pl.bp.source == null) return;
        JsonObject root = readRoot();
        JsonObject o = new JsonObject();
        o.addProperty("source", pl.bp.source);
        JsonArray origin = new JsonArray();
        origin.add(pl.origin().getX());
        origin.add(pl.origin().getY());
        origin.add(pl.origin().getZ());
        o.add("origin", origin);
        o.addProperty("rotation", pl.rotation().name());
        o.addProperty("mirror", pl.mirror().name());
        JsonArray sc = new JsonArray();
        for (BlockPos p : scaffold) sc.add(p.asLong());
        o.add("scaffold", sc);
        if (!pl.replacements().isEmpty()) {
            JsonObject rep = new JsonObject();
            for (var e : pl.replacements().entrySet()) {
                rep.addProperty(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getKey()).toString(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getValue()).toString());
            }
            o.add("replace", rep);
        }
        root.add(key, o);
        writeRoot(root);
    }

    public static void remove(String key) {
        if (key == null) return;
        JsonObject root = readRoot();
        if (root.remove(key) != null) writeRoot(root);
    }

    /** 恢复上次的进度；成功返回摆放，脚手架位置写进 scaffoldOut */
    public static Placement restore(Set<BlockPos> scaffoldOut) {
        JsonObject root = readRoot();
        String key = SupplyManager.worldKey();
        if (!root.has(key)) return null;
        try {
            JsonObject o = root.getAsJsonObject(key);
            Blueprint bp = BlueprintManager.load(o.get("source").getAsString());
            JsonArray a = o.getAsJsonArray("origin");
            Placement pl = new Placement(bp, new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
            pl.setRotation(Rotation.valueOf(o.get("rotation").getAsString()));
            pl.setMirror(Mirror.valueOf(o.get("mirror").getAsString()));
            pl.setOrigin(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
            if (o.has("scaffold")) for (var e : o.getAsJsonArray("scaffold")) scaffoldOut.add(BlockPos.of(e.getAsLong()));
            if (o.has("replace")) {
                for (var e : o.getAsJsonObject("replace").entrySet()) {
                    var from = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(e.getKey()));
                    var to = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(e.getValue().getAsString()));
                    if (from.isPresent() && to.isPresent()) pl.setReplace(from.get(), to.get());
                }
            }
            return pl;
        } catch (Exception e) {
            AutoBuilder.LOG.warn("恢复断点失败", e);
            return null;
        }
    }
}
