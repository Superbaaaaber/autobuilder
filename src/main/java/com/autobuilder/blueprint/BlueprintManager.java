package com.autobuilder.blueprint;

import com.autobuilder.Lang;
import com.autobuilder.AutoBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.neoforged.fml.loading.FMLPaths;

/** 蓝图目录管理：游戏目录下的 autobuild/ 文件夹 + 内置预设 */
public final class BlueprintManager {
    public record Entry(String name, Path file) {
        public boolean builtin() {
            return file == null;
        }

        /** 列表里显示的名字：内置建筑显示翻译后的名字 */
        public String display() {
            return builtin() ? BuiltinPresets.displayName(name) : name;
        }
    }

    private BlueprintManager() {}

    public static Path dir() {
        Path d = FMLPaths.GAMEDIR.get().resolve("autobuild");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            AutoBuilder.LOG.warn("无法创建蓝图目录 {}", d, e);
        }
        return d;
    }

    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (String n : BuiltinPresets.all().keySet()) out.add(new Entry(BuiltinPresets.PREFIX + n, null));
        Path d = dir();
        try (Stream<Path> files = Files.walk(d, 3)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> BlueprintLoader.isSupported(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> d.relativize(p).toString().toLowerCase()))
                    .forEach(p -> out.add(new Entry(d.relativize(p).toString().replace('\\', '/'), p)));
        } catch (IOException e) {
            AutoBuilder.LOG.warn("读取蓝图目录失败", e);
        }
        return out;
    }

    public static Blueprint load(String name) throws IOException {
        name = BuiltinPresets.canonical(name.trim());
        Blueprint bp = load0(name);
        bp.source = name;
        return bp;
    }

    private static Blueprint load0(String name) throws IOException {
        if (name.startsWith(BuiltinPresets.PREFIX)) {
            Supplier<Blueprint> sup = BuiltinPresets.all().get(name.substring(BuiltinPresets.PREFIX.length()));
            if (sup == null) throw new IOException(Lang.t("err.no_builtin", name));
            return sup.get();
        }
        for (Entry e : list()) {
            if (e.name().equalsIgnoreCase(name) && e.file() != null) return BlueprintLoader.load(e.file());
        }
        // 允许省略扩展名
        for (Entry e : list()) {
            if (e.file() == null) continue;
            String n = e.name();
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.substring(0, dot).equalsIgnoreCase(name)) return BlueprintLoader.load(e.file());
        }
        throw new IOException(Lang.t("err.not_found", name));
    }

    public static Map<String, Supplier<Blueprint>> builtins() {
        return BuiltinPresets.all();
    }
}
