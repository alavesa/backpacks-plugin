package fi.alavesa.backpacks;

import fi.alavesa.backpacks.command.BackpackCommand;
import fi.alavesa.backpacks.data.Storage;
import fi.alavesa.backpacks.listener.GuiListener;
import fi.alavesa.backpacks.listener.ItemListener;
import fi.alavesa.backpacks.loot.LootListener;
import fi.alavesa.backpacks.model.Issue;
import fi.alavesa.backpacks.model.PackBuilder;
import fi.alavesa.backpacks.model.PackExporter;
import fi.alavesa.backpacks.model.ParseOptions;
import fi.alavesa.backpacks.placed.PlacedListener;
import fi.alavesa.backpacks.placed.PlacedManager;
import fi.alavesa.backpacks.recipe.RecipeManager;
import fi.alavesa.backpacks.upgrade.UpgradeEngine;
import fi.alavesa.backpacks.worn.WornRenderer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.Consumer;

public final class BackpacksPlugin extends JavaPlugin {

    private static final String[] DEFAULT_MODELS = {"leather_backpack", "copper_backpack", "iron_backpack", "gold_backpack", "diamond_backpack", "netherite_backpack"};

    private Settings settings;
    private Items items;
    private Storage storage;
    private BackpackManager manager;
    private PlacedManager placed;
    private WornRenderer worn;
    private UpgradeEngine upgrades;
    private RecipeManager recipes;
    private PackExporter exporter;

    public Settings settings() { return settings; }
    public Items items() { return items; }
    public BackpackManager manager() { return manager; }
    public PlacedManager placed() { return placed; }
    public WornRenderer worn() { return worn; }
    public UpgradeEngine upgrades() { return upgrades; }
    public RecipeManager recipes() { return recipes; }
    public PackExporter exporter() { return exporter; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "recipes.yml").isFile()) saveResource("recipes.yml", false);
        settings = Settings.load(getConfig(), getLogger());
        items = new Items(this);
        storage = new Storage(this);
        manager = new BackpackManager(this, storage);
        placed = new PlacedManager(this);
        worn = new WornRenderer(this);
        upgrades = new UpgradeEngine(this);
        recipes = new RecipeManager(this);
        exporter = new PackExporter(this);

        placed.load();
        recipes.load();

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new GuiListener(this), this);
        pm.registerEvents(new ItemListener(this), this);
        pm.registerEvents(new PlacedListener(this), this);
        pm.registerEvents(worn, this);
        pm.registerEvents(upgrades, this);
        pm.registerEvents(new LootListener(this), this);
        pm.registerEvents(recipes, this);

        BackpackCommand cmd = new BackpackCommand(this);
        var c = getCommand("backpack");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }

        worn.start();
        upgrades.start();
        Bukkit.getScheduler().runTaskTimer(this, () -> manager.flush(true), settings.autosaveSeconds * 20L, settings.autosaveSeconds * 20L);
        Bukkit.getScheduler().runTask(this, () -> {
            placed.verifyAll();
            for (var p : Bukkit.getOnlinePlayers()) worn.update(p);
        });

        extractDefaultModels();
        if (settings.packBuildOnStart) buildPack(r -> {
            if (r == null) return;
            getLogger().info("Resource pack built: " + r.models().size() + " model(s), " + r.errors() + " error(s), " + r.warnings() + " warning(s) -> " + r.zip().getName() + " (sha1 " + r.sha1() + ")");
            for (Issue i : r.issues()) if (i.isError()) getLogger().warning(i.toString());
            for (Tier t : settings.orderedTiers()) if (!r.models().contains(t.model())) getLogger().warning("Tier '" + t.id() + "' uses model '" + t.model() + "' which is not in the pack - put backpack_models/" + t.model() + ".bbmodel in place and run /backpack pack.");
        });
        getLogger().info("Backpacks " + getPluginMeta().getVersion() + " enabled: " + settings.tiers.size() + " tiers, " + settings.upgrades.size() + " upgrade types, " + placed.all().size() + " placed backpack(s).");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.closeAll();
        if (worn != null) worn.detachAll();
        if (placed != null) placed.save();
        if (storage != null) storage.saveAll();
        if (recipes != null) recipes.unregister();
    }

    /** Re-reads config.yml + recipes.yml. */
    public void reload() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        recipes.load();
        upgrades.clearCaches();
        worn.refreshAll();
        for (var p : Bukkit.getOnlinePlayers()) if (settings.recipesEnabled && settings.recipesAutoDiscover) recipes.discover(p);
    }

    public void buildPack(Consumer<PackExporter.Result> done) {
        PackBuilder.Options o = new PackBuilder.Options(settings.packNamespace, settings.packDescription, settings.packMin, settings.packMax,
            ParseOptions.of(settings.packMesh, settings.packMissingTexture));
        exporter.export(o, done);
    }

    /** Copies the bundled example models into backpack_models/ when the folder has no .bbmodel yet. */
    private void extractDefaultModels() {
        File dir = exporter.modelsDir();
        dir.mkdirs();
        if (!settings.packExtractDefaults || !exporter.modelNames().isEmpty()) return;
        int n = 0;
        for (String name : DEFAULT_MODELS) {
            try (InputStream in = getResource("backpack_models/" + name + ".bbmodel")) {
                if (in == null) continue;
                Files.copy(in, new File(dir, name + ".bbmodel").toPath());
                n++;
            } catch (IOException e) {
                getLogger().warning("Cannot extract example model " + name + ": " + e.getMessage());
            }
        }
        if (n > 0) getLogger().info("Extracted " + n + " example model(s) into backpack_models/ - replace them with your own .bbmodel files.");
    }
}
