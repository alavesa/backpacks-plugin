package fi.alavesa.backpacks.recipe;

import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Keys;
import fi.alavesa.backpacks.Tier;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * recipes.yml -> shaped recipes. Custom ingredients ("backpacks:leather", "backpacks:pickup", ...) are registered
 * as their base material and verified in PrepareItemCraftEvent (so a plain piece of paper is not a backpack, and a
 * backpack is not a plain piece of paper in vanilla recipes). A backpack ingredient passes its storage id to the
 * crafted backpack, so tier upgrades keep contents and upgrades.
 */
public final class RecipeManager implements Listener {

    private record Def(NamespacedKey key, String resultKey, Map<String, Integer> customCounts, Map<Material, Integer> plainCounts) { }

    private final BackpacksPlugin plugin;
    private final Map<NamespacedKey, Def> defs = new HashMap<>();
    private final List<NamespacedKey> registered = new ArrayList<>();

    public RecipeManager(BackpacksPlugin plugin) { this.plugin = plugin; }

    public List<NamespacedKey> keys() { return registered; }

    public void unregister() {
        for (NamespacedKey k : registered) Bukkit.removeRecipe(k, true);
        registered.clear();
        defs.clear();
    }

    public void load() {
        unregister();
        if (!plugin.settings().recipesEnabled) return;
        File f = new File(plugin.getDataFolder(), "recipes.yml");
        if (!f.isFile()) plugin.saveResource("recipes.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("recipes");
        if (root == null) { plugin.getLogger().warning("recipes.yml has no 'recipes' section"); return; }
        int n = 0;
        for (String name : root.getKeys(false)) {
            ConfigurationSection r = root.getConfigurationSection(name);
            if (r == null) continue;
            try {
                if (register(name, r)) n++;
            } catch (Exception e) {
                plugin.getLogger().warning("recipes.yml: recipe '" + name + "' skipped: " + e.getMessage());
            }
        }
        plugin.getLogger().info("Registered " + n + " recipe(s).");
    }

    private boolean register(String name, ConfigurationSection r) {
        List<String> shape = r.getStringList("shape");
        if (shape.isEmpty() || shape.size() > 3) throw new IllegalArgumentException("shape must have 1..3 rows");
        int width = shape.get(0).length();
        for (String row : shape) if (row.length() != width || width < 1 || width > 3) throw new IllegalArgumentException("every shape row must have the same width (1..3)");
        String resultKey = r.getString("result", "");
        int amount = Math.max(1, r.getInt("amount", 1));
        ItemStack result = item(resultKey);
        if (result == null) throw new IllegalArgumentException("unknown result '" + resultKey + "'");
        if (!Items.isBackpack(result)) result.setAmount(Math.min(amount, result.getMaxStackSize()));
        NamespacedKey key = new NamespacedKey(Keys.NS, "recipe_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape.toArray(new String[0]));
        ConfigurationSection keys = r.getConfigurationSection("key");
        Map<String, Integer> customCounts = new HashMap<>();
        Map<Material, Integer> plainCounts = new HashMap<>();
        Map<Character, String> customByChar = new HashMap<>();
        Map<Character, Material> plainByChar = new HashMap<>();
        if (keys != null) for (String ch : keys.getKeys(false)) {
            if (ch.length() != 1) throw new IllegalArgumentException("key '" + ch + "' must be one character");
            String val = keys.getString(ch, "");
            char c = ch.charAt(0);
            if (isCustom(val)) {
                ItemStack custom = item(val);
                if (custom == null) throw new IllegalArgumentException("unknown custom ingredient '" + val + "'");
                recipe.setIngredient(c, new RecipeChoice.MaterialChoice(custom.getType()));
                customByChar.put(c, normalize(val));
            } else {
                Material m = Material.matchMaterial(val);
                if (m == null || !m.isItem()) throw new IllegalArgumentException("unknown item '" + val + "'");
                recipe.setIngredient(c, m);
                plainByChar.put(c, m);
            }
        }
        for (String row : shape) for (char c : row.toCharArray()) {
            if (c == ' ') continue;
            if (customByChar.containsKey(c)) customCounts.merge(customByChar.get(c), 1, Integer::sum);
            else if (plainByChar.containsKey(c)) plainCounts.merge(plainByChar.get(c), 1, Integer::sum);
            else throw new IllegalArgumentException("shape uses '" + c + "' which is not in key");
        }
        Bukkit.addRecipe(recipe);
        registered.add(key);
        defs.put(key, new Def(key, normalize(resultKey), customCounts, plainCounts));
        return true;
    }

    private static boolean isCustom(String v) { return v != null && v.toLowerCase(Locale.ROOT).startsWith(Keys.NS + ":"); }

    private static String normalize(String v) { return v.toLowerCase(Locale.ROOT).trim(); }

    private ItemStack item(String key) {
        if (isCustom(key)) return plugin.items().custom(key);
        Material m = Material.matchMaterial(key);
        return m == null || !m.isItem() ? null : new ItemStack(m);
    }

    public void discover(Player p) {
        if (!registered.isEmpty()) p.discoverRecipes(registered);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        Recipe recipe = e.getRecipe();
        ItemStack[] matrix = e.getInventory().getMatrix();
        boolean anyCustom = false;
        for (ItemStack it : matrix) if (Items.customKey(it) != null) { anyCustom = true; break; }
        if (recipe == null) return;
        Def def = recipe instanceof Keyed k ? defs.get(k.getKey()) : null;
        if (def == null) {
            if (anyCustom) e.getInventory().setResult(null);   // never use a backpack as paper etc.
            return;
        }
        Map<String, Integer> customSeen = new HashMap<>();
        Map<Material, Integer> plainSeen = new HashMap<>();
        UUID backpackId = null;
        for (ItemStack it : matrix) {
            if (it == null || it.isEmpty()) continue;
            String ck = Items.customKey(it);
            if (ck != null) {
                customSeen.merge(ck, 1, Integer::sum);
                if (Items.isBackpack(it)) backpackId = Items.ensureId(it);
            } else plainSeen.merge(it.getType(), 1, Integer::sum);
        }
        if (!customSeen.equals(def.customCounts()) || !plainSeen.equals(def.plainCounts())) { e.getInventory().setResult(null); return; }
        if (backpackId != null && def.resultKey().startsWith(Keys.NS + ":")) {
            Tier tier = plugin.settings().tier(def.resultKey().substring(Keys.NS.length() + 1));
            if (tier != null) e.getInventory().setResult(plugin.items().backpack(tier, backpackId));
        }
    }
}
