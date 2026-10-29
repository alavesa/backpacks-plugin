package fi.alavesa.backpacks.model;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The server side of the pipeline: reads plugins/backpacks/backpack_models/*.bbmodel, runs {@link PackBuilder}
 * on an async thread and writes plugins/backpacks/resourcepack/ (unzipped pack) plus
 * plugins/backpacks/Backpacks-resourcepack.zip, .sha1 and pack-report.txt. Source models are never modified.
 */
public final class PackExporter {

    public record Result(List<String> models, List<Issue> issues, File zip, String sha1, long bytes, long millis) {
        public long errors() { return issues.stream().filter(Issue::isError).count(); }
        public long warnings() { return issues.stream().filter(i -> !i.isError()).count(); }
    }

    private final JavaPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile List<String> lastExported = List.of();

    public PackExporter(JavaPlugin plugin) { this.plugin = plugin; }

    public File modelsDir() { return new File(plugin.getDataFolder(), "backpack_models"); }
    public File packDir() { return new File(plugin.getDataFolder(), "resourcepack"); }
    public File zipFile() { return new File(plugin.getDataFolder(), "Backpacks-resourcepack.zip"); }

    /** Model names found on disk right now (also before any export). */
    public List<String> modelNames() {
        List<String> names = new ArrayList<>();
        File[] list = modelsDir().listFiles();
        if (list != null) for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
                names.add(f.getName().substring(0, f.getName().length() - ".bbmodel".length()));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean modelExists(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String n : modelNames()) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    public List<String> lastExported() { return lastExported; }

    public boolean isRunning() { return running.get(); }

    /** Export asynchronously; {@code done} runs on the main thread (null result = already running / failed). */
    public void export(PackBuilder.Options options, Consumer<Result> done) {
        if (!running.compareAndSet(false, true)) { if (done != null) done.accept(null); return; }
        File dir = modelsDir();
        dir.mkdirs();
        List<PackBuilder.Source> sources = new ArrayList<>();
        File[] list = dir.listFiles();
        if (list != null) for (File f : list) {
            if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) continue;
            String name = f.getName().substring(0, f.getName().length() - ".bbmodel".length());
            try {
                String json = Files.readString(f.toPath());
                sources.add(new PackBuilder.Source(name, json, rel -> {
                    File t = new File(dir, new File(rel).getName());
                    if (!t.isFile()) t = new File(dir, name + ".png");
                    if (!t.isFile()) return null;
                    try { return Files.readAllBytes(t.toPath()); } catch (IOException e) { return null; }
                }));
            } catch (IOException e) {
                plugin.getLogger().warning("Cannot read " + f.getName() + ": " + e.getMessage());
            }
        }
        long t0 = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = null;
            try {
                PackBuilder.Output out = PackBuilder.build(sources, options);
                PackBuilder.writeFolder(out, packDir());
                String sha1 = PackBuilder.writeZip(out, zipFile());
                Files.writeString(new File(plugin.getDataFolder(), "Backpacks-resourcepack.sha1").toPath(), sha1 + "\n");
                Files.writeString(new File(plugin.getDataFolder(), "pack-report.txt").toPath(), out.report());
                lastExported = List.copyOf(out.exportedModels());
                result = new Result(out.exportedModels(), out.issues(), zipFile(), sha1, zipFile().length(), System.currentTimeMillis() - t0);
            } catch (Exception e) {
                plugin.getLogger().severe("Resource pack export failed: " + e);
            } finally {
                running.set(false);
            }
            Result r = result;
            if (done != null) Bukkit.getScheduler().runTask(plugin, () -> done.accept(r));
        });
    }
}
