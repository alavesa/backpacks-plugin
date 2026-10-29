package fi.alavesa.backpacks.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs parser + converter over a set of model sources and assembles a complete resource pack:
 * pack.mcmeta, the models/textures of every model that converted cleanly, and one item definition per
 * model (assets/&lt;ns&gt;/items/&lt;model&gt;.json) so an item with {@code item_model = "<ns>:<model>"} shows it.
 * Pure: no Bukkit, safe to run off the main thread.
 */
public final class PackBuilder {

    public record Options(String namespace, String description, int minFormat, int maxFormat, ParseOptions parse) { }

    public record Source(String name, String json, Function<String, byte[]> textureResolver) { }

    public record Output(Map<String, byte[]> files, List<String> exportedModels, List<Issue> issues, String report) {
        public List<Issue> errors() { List<Issue> l = new ArrayList<>(); for (Issue i : issues) if (i.isError()) l.add(i); return l; }
        public List<Issue> warnings() { List<Issue> l = new ArrayList<>(); for (Issue i : issues) if (!i.isError()) l.add(i); return l; }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PackBuilder() { }

    public static Output build(List<Source> sources, Options o) {
        List<Issue> issues = new ArrayList<>();
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        List<ConvertedModel> converted = new ArrayList<>();
        Set<String> usedPaths = new LinkedHashSet<>();
        Set<String> usedNames = new LinkedHashSet<>();
        ModelConverter converter = new ModelConverter(o.namespace());
        for (Source s : sources) {
            if (!usedNames.add(s.name().toLowerCase(Locale.ROOT))) { issues.add(Issue.error(s.name(), "duplicate model name (names are case-insensitive) - rename one of the files")); continue; }
            String p = BbModel.safePath(s.name());
            if (!usedPaths.add(p)) { issues.add(Issue.error(s.name(), "another model already uses the pack path '" + p + "' - rename one of the files")); continue; }
            BbModelParser.Result r;
            try {
                r = BbModelParser.parse(s.name(), s.json(), s.textureResolver(), o.parse());
            } catch (Exception e) {
                issues.add(Issue.error(s.name(), "could not parse: " + e));
                continue;
            }
            issues.addAll(r.issues());
            if (!r.ok()) continue;
            try {
                ConvertedModel cm = converter.convert(r.model());
                issues.addAll(cm.issues());
                converted.add(cm);
                files.putAll(cm.files());
            } catch (Exception e) {
                issues.add(Issue.error(s.name(), "could not convert: " + e));
            }
        }

        // one item definition per model
        for (ConvertedModel cm : converted) {
            JsonObject modelDef;
            if (cm.wrappers().size() == 1) {
                modelDef = new JsonObject();
                modelDef.addProperty("type", "minecraft:model");
                modelDef.addProperty("model", o.namespace() + ":" + cm.wrappers().get(0));
            } else {
                modelDef = new JsonObject();
                modelDef.addProperty("type", "minecraft:composite");
                JsonArray models = new JsonArray();
                for (String w : cm.wrappers()) {
                    JsonObject mm = new JsonObject();
                    mm.addProperty("type", "minecraft:model");
                    mm.addProperty("model", o.namespace() + ":" + w);
                    models.add(mm);
                }
                modelDef.add("models", models);
            }
            JsonObject itemsRoot = new JsonObject();
            itemsRoot.add("model", modelDef);
            files.put("assets/" + o.namespace() + "/items/" + cm.path() + ".json", GSON.toJson(itemsRoot).getBytes(StandardCharsets.UTF_8));
        }

        // pack.mcmeta
        JsonObject pack = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("description", o.description());
        p.addProperty("pack_format", o.minFormat());
        p.addProperty("min_format", o.minFormat());
        p.addProperty("max_format", o.maxFormat());
        pack.add("pack", p);
        files.put("pack.mcmeta", GSON.toJson(pack).getBytes(StandardCharsets.UTF_8));

        // report
        report.add("Backpacks pack report - " + new Date());
        report.add("namespace: " + o.namespace() + "   pack_format " + o.minFormat() + ".." + o.maxFormat());
        report.add("");
        report.add("exported models (use the NAME as 'model' in config.yml tiers; items get item_model = " + o.namespace() + ":<name>):");
        List<String> names = new ArrayList<>();
        for (ConvertedModel cm : converted) {
            names.add(cm.name());
            double[] b = cm.boundsPx();
            report.add(String.format(Locale.ROOT, "  %-24s %3d cubes, %2d part(s), size %.0fx%.0fx%.0f px, x %.0f..%.0f  y %.0f..%.0f  z %.0f..%.0f  -> %s:%s",
                cm.name(), cm.cubes(), cm.parts(), b[3] - b[0], b[4] - b[1], b[5] - b[2], b[0], b[3], b[1], b[4], b[2], b[5], o.namespace(), cm.path()));
        }
        if (converted.isEmpty()) report.add("  none");
        report.add("");
        List<Issue> errors = new ArrayList<>(), warnings = new ArrayList<>();
        for (Issue i : issues) (i.isError() ? errors : warnings).add(i);
        report.add("errors (" + errors.size() + ") - these models are NOT in the pack:");
        if (errors.isEmpty()) report.add("  none"); else for (Issue i : errors) report.add("  ! " + i.model() + ": " + i.message());
        report.add("");
        report.add("warnings (" + warnings.size() + "):");
        if (warnings.isEmpty()) report.add("  none"); else for (Issue i : warnings) report.add("  - " + i.model() + ": " + i.message());
        String reportText = String.join("\n", report) + "\n";
        files.put("pack-report.txt", reportText.getBytes(StandardCharsets.UTF_8));
        return new Output(files, names, issues, reportText);
    }

    /** Write the zip atomically (tmp + move) and return its sha1. */
    public static String writeZip(Output out, File zip) throws IOException {
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp");
        zip.getParentFile().mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            for (var e : out.files().entrySet()) {
                if (e.getKey().equals("pack-report.txt")) continue;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return sha1(zip);
    }

    /** Write the pack as a plain folder (an unzipped pack the client also accepts). Old assets/ are wiped first. */
    public static void writeFolder(Output out, File dir) throws IOException {
        File assets = new File(dir, "assets");
        if (assets.isDirectory()) deleteTree(assets);
        for (var e : out.files().entrySet()) {
            File f = new File(dir, e.getKey());
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), e.getValue());
        }
    }

    private static void deleteTree(File f) throws IOException {
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteTree(k); }
        Files.deleteIfExists(f.toPath());
    }

    public static String sha1(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}
