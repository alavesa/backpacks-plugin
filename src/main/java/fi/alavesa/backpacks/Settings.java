package fi.alavesa.backpacks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Validated view of config.yml. Rebuilt on /backpack reload. */
public final class Settings {

    public final Map<String, Tier> tiers = new LinkedHashMap<>();
    public final Map<String, UpgradeDef> upgrades = new LinkedHashMap<>();
    public final List<String> problems = new ArrayList<>();

    public boolean upgradesInInventory, upgradesInOffhand;
    public int upgradeTickInterval;

    public boolean wornEnabled, wornOnMobs;
    public double[] wornTranslation = {0, -1.05, -0.3};
    public double wornScale, wornYawOffset; public float wornViewRange;

    public boolean placedEnabled, placedSolid, placedBreakLeftClick, placedAutomation;
    public double placedSnap, placedYawOffset, placedScale; public float placedInteractionSize;

    public boolean lootChestsEnabled; public double lootBackpackChance, lootUpgradeChance;
    public Map<String, Double> lootTierWeights = new LinkedHashMap<>(), lootUpgradeWeights = new LinkedHashMap<>();
    public List<String> lootTables = new ArrayList<>();
    public boolean mobsEnabled; public double mobChance, mobLootChance; public List<EntityType> mobTypes = new ArrayList<>();
    public Map<String, Double> mobTierWeights = new LinkedHashMap<>(); public String mobLootTable; public int mobLootMaxStacks; public float mobDropChance;

    public boolean recipesEnabled, recipesAutoDiscover;
    public int autosaveSeconds, cacheMinutes;
    public String storageTitle, upgradesTitle, filterTitle;

    public String packNamespace, packDescription, packMesh, packMissingTexture; public int packMin, packMax; public boolean packExtractDefaults, packBuildOnStart;
    public final Map<String, String> messages = new LinkedHashMap<>();

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static Settings load(FileConfiguration c, Logger log) {
        Settings s = new Settings();
        ConfigurationSection ts = c.getConfigurationSection("tiers");
        if (ts != null) for (String id : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(id);
            if (t == null) continue;
            Tier tier = readTier(id, t, s.problems);
            if (tier != null) s.tiers.put(tier.id(), tier);
        }
        if (s.tiers.isEmpty()) s.problems.add("no valid tiers configured - /backpack give will not work");

        ConfigurationSection us = c.getConfigurationSection("upgrades");
        s.upgradesInInventory = us == null || us.getBoolean("active-in-inventory", true);
        s.upgradesInOffhand = us == null || us.getBoolean("active-in-offhand", true);
        s.upgradeTickInterval = Math.max(1, us == null ? 5 : us.getInt("tick-interval", 5));
        for (String id : UpgradeDef.ALL) {
            ConfigurationSection u = us == null ? null : us.getConfigurationSection(id);
            String name = u == null ? prettify(id) : u.getString("name", prettify(id));
            Material mat = mat(u == null ? null : u.getString("material"), defaultUpgradeMaterial(id), "upgrades." + id + ".material", s.problems);
            int filters = u == null ? defaultFilterSlots(id) : Math.max(0, Math.min(18, u.getInt("filter-slots", defaultFilterSlots(id))));
            double radius = u == null ? (id.equals(UpgradeDef.ADVANCED_MAGNET) ? 6 : 3.5) : u.getDouble("radius", id.equals(UpgradeDef.ADVANCED_MAGNET) ? 6 : 3.5);
            boolean rev = u == null || u.getBoolean("require-reversible", true);
            s.upgrades.put(id, new UpgradeDef(id, name, mat, filters, u == null ? "" : u.getString("model", ""), radius, rev));
        }

        s.wornEnabled = c.getBoolean("worn.enabled", true);
        s.wornOnMobs = c.getBoolean("worn.render-on-mobs", true);
        List<Double> tr = c.getDoubleList("worn.translation");
        if (tr.size() == 3) s.wornTranslation = new double[] {tr.get(0), tr.get(1), tr.get(2)};
        s.wornScale = c.getDouble("worn.scale", 0.55);
        s.wornYawOffset = c.getDouble("worn.yaw-offset", 0);
        s.wornViewRange = (float) c.getDouble("worn.view-range", 0.6);

        s.placedEnabled = c.getBoolean("placed.enabled", true);
        s.placedSolid = c.getBoolean("placed.solid-block", true);
        s.placedSnap = c.getDouble("placed.snap-rotation", 90);
        s.placedYawOffset = c.getDouble("placed.yaw-offset", 0);
        s.placedScale = c.getDouble("placed.scale", 1.0);
        s.placedInteractionSize = (float) c.getDouble("placed.interaction-size", 1.02);
        s.placedBreakLeftClick = c.getBoolean("placed.break-with-left-click", true);
        s.placedAutomation = c.getBoolean("placed.automation", true);

        s.lootChestsEnabled = c.getBoolean("loot.chests.enabled", true);
        s.lootBackpackChance = c.getDouble("loot.chests.backpack-chance", 0.06);
        s.lootUpgradeChance = c.getDouble("loot.chests.upgrade-chance", 0.08);
        s.lootTierWeights = weights(c.getConfigurationSection("loot.chests.tier-weights"));
        s.lootUpgradeWeights = weights(c.getConfigurationSection("loot.chests.upgrade-weights"));
        s.lootTables = c.getStringList("loot.chests.tables");
        s.mobsEnabled = c.getBoolean("loot.mobs.enabled", true);
        s.mobChance = c.getDouble("loot.mobs.chance", 0.012);
        for (String n : c.getStringList("loot.mobs.entity-types")) {
            try { s.mobTypes.add(EntityType.valueOf(n.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException e) { s.problems.add("loot.mobs.entity-types: unknown entity type '" + n + "'"); }
        }
        s.mobTierWeights = weights(c.getConfigurationSection("loot.mobs.tier-weights"));
        s.mobLootChance = c.getDouble("loot.mobs.loot-chance", 0.6);
        s.mobLootTable = c.getString("loot.mobs.loot-table", "minecraft:chests/simple_dungeon");
        s.mobLootMaxStacks = c.getInt("loot.mobs.loot-max-stacks", 6);
        s.mobDropChance = (float) c.getDouble("loot.mobs.drop-chance", 0.85);

        s.recipesEnabled = c.getBoolean("recipes.enabled", true);
        s.recipesAutoDiscover = c.getBoolean("recipes.auto-discover", true);
        s.autosaveSeconds = Math.max(5, c.getInt("storage.autosave-seconds", 30));
        s.cacheMinutes = Math.max(1, c.getInt("storage.cache-minutes", 15));
        s.storageTitle = c.getString("gui.storage-title", "<name>");
        s.upgradesTitle = c.getString("gui.upgrades-title", "<name> - Upgrades");
        s.filterTitle = c.getString("gui.filter-title", "<upgrade> - Filter");

        s.packNamespace = c.getString("pack.namespace", "backpacks").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        s.packDescription = c.getString("pack.description", "Backpacks models");
        s.packMin = c.getInt("pack.min-format", 46);
        s.packMax = c.getInt("pack.max-format", 999);
        s.packMesh = c.getString("pack.mesh-policy", "bounding-box");
        s.packMissingTexture = c.getString("pack.missing-texture", "warn");
        s.packExtractDefaults = c.getBoolean("pack.extract-default-models", true);
        s.packBuildOnStart = c.getBoolean("pack.build-on-start", true);

        ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) for (String k : ms.getKeys(false)) s.messages.put(k, ms.getString(k, ""));
        for (String p : s.problems) log.warning("config.yml: " + p);
        return s;
    }

    public static Tier readTier(String id, ConfigurationSection t, List<String> problems) {
        String tid = id.toLowerCase(Locale.ROOT);
        int slots = t.getInt("slots", 9);
        if (slots < 1 || slots > 54) { problems.add("tiers." + id + ".slots must be 1..54 (was " + slots + ") - clamped"); slots = Math.max(1, Math.min(54, slots)); }
        int up = t.getInt("upgrade-slots", 1);
        if (up < 0 || up > 18) { problems.add("tiers." + id + ".upgrade-slots must be 0..18 - clamped"); up = Math.max(0, Math.min(18, up)); }
        Material mat = mat(t.getString("material"), Material.PAPER, "tiers." + id + ".material", problems);
        String model = t.getString("model", tid + "_backpack");
        return new Tier(tid, t.getString("name", prettify(tid) + " Backpack"), slots, up, model, mat, t.getString("color", "#FFFFFF"), t.getInt("order", 0));
    }

    private static Map<String, Double> weights(ConfigurationSection sec) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (sec != null) for (String k : sec.getKeys(false)) m.put(k.toLowerCase(Locale.ROOT), Math.max(0, sec.getDouble(k, 0)));
        return m;
    }

    private static Material mat(String name, Material def, String where, List<String> problems) {
        if (name == null || name.isBlank()) return def;
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isItem()) { problems.add(where + ": unknown item '" + name + "', using " + def); return def; }
        return m;
    }

    public static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) { if (w.isEmpty()) continue; if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return sb.toString();
    }

    static Material defaultUpgradeMaterial(String id) {
        return switch (id) {
            case UpgradeDef.PICKUP, UpgradeDef.ADVANCED_PICKUP -> Material.HOPPER;
            case UpgradeDef.MAGNET -> Material.COMPASS;
            case UpgradeDef.ADVANCED_MAGNET -> Material.RECOVERY_COMPASS;
            case UpgradeDef.FEEDING -> Material.APPLE;
            case UpgradeDef.ADVANCED_FEEDING -> Material.GOLDEN_APPLE;
            case UpgradeDef.VOID -> Material.OBSIDIAN;
            case UpgradeDef.REFILL -> Material.REDSTONE;
            case UpgradeDef.COMPACTING -> Material.PISTON;
            case UpgradeDef.INCEPTION -> Material.ENDER_EYE;
            case UpgradeDef.EVERLASTING -> Material.NETHER_STAR;
            case UpgradeDef.DEPOSIT -> Material.DROPPER;
            case UpgradeDef.RESTOCK -> Material.DISPENSER;
            default -> Material.PAPER;
        };
    }

    static int defaultFilterSlots(String id) {
        return switch (id) {
            case UpgradeDef.ADVANCED_PICKUP, UpgradeDef.ADVANCED_MAGNET -> 16;
            case UpgradeDef.FEEDING -> 4;
            case UpgradeDef.REFILL -> 6;
            case UpgradeDef.INCEPTION, UpgradeDef.EVERLASTING -> 0;
            default -> 9;
        };
    }

    /** Tiers in ascending order. */
    public List<Tier> orderedTiers() {
        List<Tier> l = new ArrayList<>(tiers.values());
        l.sort((a, b) -> a.order() != b.order() ? Integer.compare(a.order(), b.order()) : Integer.compare(a.slots(), b.slots()));
        return Collections.unmodifiableList(l);
    }

    public Tier tier(String id) { return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT)); }
    public UpgradeDef upgrade(String id) { return id == null ? null : upgrades.get(id.toLowerCase(Locale.ROOT)); }

    public Component msg(String key, TagResolver... resolvers) {
        String raw = messages.getOrDefault(key, key);
        String prefix = key.equals("prefix") ? "" : messages.getOrDefault("prefix", "");
        return MM.deserialize(prefix + raw, resolvers);
    }

    public static Component mm(String s, TagResolver... r) { return MM.deserialize(s, r); }
    public static TagResolver ph(String k, String v) { return Placeholder.parsed(k, v); }
}
