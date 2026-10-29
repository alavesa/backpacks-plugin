package fi.alavesa.backpacks.data;

import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Tier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * plugins/backpacks/data/&lt;uuid&gt;.yml, one file per backpack, cached in memory. Items are stored per slot as
 * base64 of Paper's versioned item bytes (survive Minecraft updates via DataFixers).
 */
public final class Storage {

    private final BackpacksPlugin plugin;
    private final File dir;
    private final Map<UUID, BackpackData> cache = new HashMap<>();

    public Storage(BackpacksPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "data");
        dir.mkdirs();
    }

    public boolean exists(UUID id) { return cache.containsKey(id) || new File(dir, id + ".yml").isFile(); }

    /** Loads (or creates for the tier) the data of a backpack and fits it to the tier's sizes. */
    public BackpackData get(UUID id, Tier tier) {
        BackpackData d = cache.get(id);
        if (d == null) {
            d = load(id, tier);
            cache.put(id, d);
        }
        d.touch();
        if (tier != null && (d.contents.length != tier.slots() || d.upgrades.length != tier.upgradeSlots() || !tier.id().equals(d.tier))) {
            d.tier = tier.id();
            List<ItemStack> overflow = d.resize(tier.slots(), tier.upgradeSlots());
            if (!overflow.isEmpty()) plugin.getLogger().warning("Backpack " + id + " shrank; " + overflow.size() + " stack(s) did not fit and were lost.");
        }
        return d;
    }

    /** Cached only (no disk read), or null. */
    public BackpackData cached(UUID id) { return cache.get(id); }

    private BackpackData load(UUID id, Tier tier) {
        File f = new File(dir, id + ".yml");
        int slots = tier == null ? 27 : tier.slots(), ups = tier == null ? 1 : tier.upgradeSlots();
        if (!f.isFile()) return new BackpackData(id, tier == null ? "" : tier.id(), slots, ups);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String t = y.getString("tier", tier == null ? "" : tier.id());
        int cs = Math.max(slots, y.getInt("slots", slots)), us = Math.max(ups, y.getInt("upgrade-slots", ups));
        BackpackData d = new BackpackData(id, t, cs, us);
        read(y, "contents", d.contents);
        read(y, "upgrades", d.upgrades);
        try { d.lastSaved = y.saveToString().getBytes(StandardCharsets.UTF_8); } catch (Exception ignored) { }
        return d;
    }

    private static void read(YamlConfiguration y, String key, ItemStack[] into) {
        var sec = y.getConfigurationSection(key);
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                int i = Integer.parseInt(k);
                if (i < 0 || i >= into.length) continue;
                into[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(sec.getString(k, "")));
            } catch (Exception ignored) { }
        }
    }

    private static YamlConfiguration toYaml(BackpackData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("tier", d.tier);
        y.set("slots", d.contents.length);
        y.set("upgrade-slots", d.upgrades.length);
        y.set("saved", System.currentTimeMillis());
        write(y, "contents", d.contents);
        write(y, "upgrades", d.upgrades);
        return y;
    }

    private static void write(YamlConfiguration y, String key, ItemStack[] items) {
        y.createSection(key);
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null || items[i].isEmpty()) continue;
            y.set(key + "." + i, Base64.getEncoder().encodeToString(items[i].serializeAsBytes()));
        }
    }

    /** Writes a backpack if its serialized form changed. */
    public void save(BackpackData d) {
        try {
            YamlConfiguration y = toYaml(d);
            String s = y.saveToString();
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            d.dirty = false;
            if (d.lastSaved != null && java.util.Arrays.equals(d.lastSaved, bytes)) return;
            File f = new File(dir, d.id + ".yml");
            File tmp = new File(dir, d.id + ".yml.tmp");
            java.nio.file.Files.write(tmp.toPath(), bytes);
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            d.lastSaved = bytes;
        } catch (IOException e) {
            plugin.getLogger().severe("Cannot save backpack " + d.id + ": " + e.getMessage());
        }
    }

    /** Saves dirty entries; evicts entries idle longer than the cache window (unless pinned). */
    public void flush(java.util.Set<UUID> pinned, boolean evict) {
        long cutoff = System.currentTimeMillis() - plugin.settings().cacheMinutes * 60_000L;
        Iterator<Map.Entry<UUID, BackpackData>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BackpackData> e = it.next();
            BackpackData d = e.getValue();
            if (d.dirty) save(d);
            if (evict && !pinned.contains(e.getKey()) && d.lastAccess < cutoff) it.remove();
        }
    }

    public void saveAll() { for (BackpackData d : new ArrayList<>(cache.values())) save(d); }

    public void delete(UUID id) {
        cache.remove(id);
        new File(dir, id + ".yml").delete();
    }

    public int cachedCount() { return cache.size(); }
}
