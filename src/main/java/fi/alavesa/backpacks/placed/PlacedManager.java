package fi.alavesa.backpacks.placed;

import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Keys;
import fi.alavesa.backpacks.Settings;
import fi.alavesa.backpacks.Tier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Placed backpacks: an Interaction entity (clicks) + an ItemDisplay (the model) + optionally a BARRIER
 * block (solid). Persisted in placed.yml; entities are re-created if a chunk comes back without them.
 */
public final class PlacedManager {

    public static final String ROLE_DISPLAY = "placed_display", ROLE_INTERACTION = "placed_interaction";

    private final BackpacksPlugin plugin;
    private final Map<String, Map<Long, PlacedBackpack>> byWorld = new HashMap<>();
    private final Map<UUID, PlacedBackpack> byEntity = new HashMap<>();
    /** backpack storage id -> placed backpack (a backpack can only be placed once at a time). */
    private final Map<UUID, PlacedBackpack> byId = new HashMap<>();
    private final File file;

    public PlacedManager(BackpacksPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "placed.yml");
    }

    public boolean isEmpty() { return byEntity.isEmpty() && byWorld.values().stream().allMatch(Map::isEmpty); }

    public PlacedBackpack at(World w, int x, int y, int z) {
        Map<Long, PlacedBackpack> m = byWorld.get(w.getName());
        return m == null ? null : m.get(PlacedBackpack.key(x, y, z));
    }

    public PlacedBackpack at(Block b) { return at(b.getWorld(), b.getX(), b.getY(), b.getZ()); }

    public PlacedBackpack byEntity(Entity e) {
        PlacedBackpack p = byEntity.get(e.getUniqueId());
        if (p != null) return p;
        String role = e.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING);
        if (role == null || !role.startsWith("placed_")) return null;
        String sid = e.getPersistentDataContainer().get(Keys.ENTITY_ID, PersistentDataType.STRING);
        if (sid != null) { try { PlacedBackpack q = byId.get(UUID.fromString(sid)); if (q != null) return q; } catch (IllegalArgumentException ignored) { } }
        Location l = e.getLocation();
        return at(e.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public PlacedBackpack byId(UUID backpackId) { return byId.get(backpackId); }

    public List<PlacedBackpack> all() {
        List<PlacedBackpack> l = new ArrayList<>();
        for (Map<Long, PlacedBackpack> m : byWorld.values()) l.addAll(m.values());
        return l;
    }

    // ------------------------------------------------------------------ place / pickup

    /** Can a backpack be placed into this block? */
    public boolean canPlace(Block target) {
        if (!(target.getType().isAir() || target.isReplaceable() || target.isLiquid())) return false;
        if (at(target) != null) return false;
        if (plugin.settings().placedSolid) {
            var box = org.bukkit.util.BoundingBox.of(target);
            for (Entity e : target.getWorld().getNearbyEntities(box)) if (e instanceof LivingEntity) return false;
        }
        return true;
    }

    public PlacedBackpack place(Player p, Block target, ItemStack backpack) {
        Tier tier = plugin.items().tierOf(backpack);
        if (tier == null) return null;
        UUID id = Items.ensureId(backpack);
        Settings s = plugin.settings();
        float yaw = p.getLocation().getYaw() + 180f;
        if (s.placedSnap > 0) yaw = (float) (Math.round(yaw / s.placedSnap) * s.placedSnap);
        yaw += (float) s.placedYawOffset;
        ItemStack stored = backpack.clone(); stored.setAmount(1);
        PlacedBackpack pb = new PlacedBackpack(target.getWorld().getName(), target.getX(), target.getY(), target.getZ(), id, tier.id(), yaw, stored);
        spawnEntities(pb, target.getWorld());
        if (s.placedSolid) { target.setType(Material.BARRIER, false); pb.barrier = true; }
        byWorld.computeIfAbsent(pb.world, k -> new HashMap<>()).put(pb.key(), pb);
        byId.put(id, pb);
        plugin.manager().pin(id, tier);
        save();
        return pb;
    }

    /** Removes the placed backpack and returns its item (contents stay in storage under the same id). */
    public ItemStack pickup(PlacedBackpack pb) {
        World w = Bukkit.getWorld(pb.world);
        removeEntities(pb, w);
        if (w != null && pb.barrier) { Block b = w.getBlockAt(pb.x, pb.y, pb.z); if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false); }
        Map<Long, PlacedBackpack> m = byWorld.get(pb.world);
        if (m != null) m.remove(pb.key());
        byId.remove(pb.id);
        plugin.manager().unpin(pb.id);
        save();
        ItemStack it = pb.item.clone();
        Tier tier = plugin.settings().tier(pb.tier);
        if (tier != null) plugin.items().decorateBackpack(it, tier, plugin.manager().upgradeNames(pb.id, tier));
        return it;
    }

    /** Gives the item to the player or drops it; sends the message. */
    public void pickupTo(Player p, PlacedBackpack pb) {
        ItemStack it = pickup(pb);
        var left = p.getInventory().addItem(it);
        if (!left.isEmpty()) {
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
            p.sendMessage(plugin.settings().msg("inventory-full"));
        } else p.sendMessage(plugin.settings().msg("picked-up"));
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1f);
    }

    // ------------------------------------------------------------------ entities

    private void spawnEntities(PlacedBackpack pb, World w) {
        Settings s = plugin.settings();
        Location base = new Location(w, pb.x + 0.5, pb.y, pb.z + 0.5, pb.yaw, 0);
        ItemDisplay d = w.spawn(base, ItemDisplay.class, e -> {
            ItemStack shown = pb.item.clone();
            e.setItemStack(shown);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setBillboard(Display.Billboard.FIXED);
            float sc = (float) s.placedScale;
            e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 1, 0), new Vector3f(sc, sc, sc), new AxisAngle4f(0, 0, 1, 0)));
            e.setPersistent(true);
            e.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, ROLE_DISPLAY);
            e.getPersistentDataContainer().set(Keys.ENTITY_ID, PersistentDataType.STRING, pb.id.toString());
        });
        Interaction in = w.spawn(new Location(w, pb.x + 0.5, pb.y - (s.placedInteractionSize - 1) / 2.0, pb.z + 0.5), Interaction.class, e -> {
            e.setInteractionWidth(s.placedInteractionSize);
            e.setInteractionHeight(s.placedInteractionSize);
            e.setResponsive(true);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, ROLE_INTERACTION);
            e.getPersistentDataContainer().set(Keys.ENTITY_ID, PersistentDataType.STRING, pb.id.toString());
        });
        pb.displayUuid = d.getUniqueId();
        pb.interactionUuid = in.getUniqueId();
        byEntity.put(d.getUniqueId(), pb);
        byEntity.put(in.getUniqueId(), pb);
    }

    private void removeEntities(PlacedBackpack pb, World w) {
        if (pb.displayUuid != null) { byEntity.remove(pb.displayUuid); Entity e = Bukkit.getEntity(pb.displayUuid); if (e != null) e.remove(); }
        if (pb.interactionUuid != null) { byEntity.remove(pb.interactionUuid); Entity e = Bukkit.getEntity(pb.interactionUuid); if (e != null) e.remove(); }
        if (w == null) return;
        // stray tagged entities in that block
        for (Entity e : w.getNearbyEntities(new Location(w, pb.x + 0.5, pb.y + 0.5, pb.z + 0.5), 0.6, 0.6, 0.6)) {
            String role = e.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING);
            if (role != null && role.startsWith("placed_")) e.remove();
        }
    }

    /** Ensures the entities of every placed backpack in loaded chunks exist (re-spawns missing ones); removes orphans. */
    public void verify(World w, java.util.Collection<Entity> loaded) {
        for (Entity e : loaded) {
            String role = e.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING);
            if (role == null || !role.startsWith("placed_")) continue;
            PlacedBackpack pb = byEntity(e);
            if (pb == null) { e.remove(); continue; }
            if (role.equals(ROLE_DISPLAY)) { pb.displayUuid = e.getUniqueId(); byEntity.put(e.getUniqueId(), pb); }
            else { pb.interactionUuid = e.getUniqueId(); byEntity.put(e.getUniqueId(), pb); }
        }
        Map<Long, PlacedBackpack> m = byWorld.get(w.getName());
        if (m == null) return;
        for (PlacedBackpack pb : m.values()) {
            if (!w.isChunkLoaded(pb.x >> 4, pb.z >> 4)) continue;
            Entity d = pb.displayUuid == null ? null : Bukkit.getEntity(pb.displayUuid);
            Entity i = pb.interactionUuid == null ? null : Bukkit.getEntity(pb.interactionUuid);
            if (d == null || i == null || !d.isValid() || !i.isValid()) {
                removeEntities(pb, w);
                spawnEntities(pb, w);
            }
            Tier tier = plugin.settings().tier(pb.tier);
            if (tier != null) plugin.manager().pin(pb.id, tier);
        }
    }

    public void verifyAll() {
        for (World w : Bukkit.getWorlds()) verify(w, w.getEntities());
    }

    // ------------------------------------------------------------------ persistence

    public void load() {
        byWorld.clear(); byEntity.clear(); byId.clear();
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection list = y.getConfigurationSection("placed");
        if (list == null) return;
        for (String k : list.getKeys(false)) {
            ConfigurationSection c = list.getConfigurationSection(k);
            if (c == null) continue;
            try {
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(c.getString("item", "")));
                PlacedBackpack pb = new PlacedBackpack(c.getString("world"), c.getInt("x"), c.getInt("y"), c.getInt("z"),
                    UUID.fromString(c.getString("id")), c.getString("tier"), (float) c.getDouble("yaw"), item);
                pb.barrier = c.getBoolean("barrier");
                if (c.isString("display")) pb.displayUuid = UUID.fromString(c.getString("display"));
                if (c.isString("interaction")) pb.interactionUuid = UUID.fromString(c.getString("interaction"));
                byWorld.computeIfAbsent(pb.world, x -> new HashMap<>()).put(pb.key(), pb);
                byId.put(pb.id, pb);
                if (pb.displayUuid != null) byEntity.put(pb.displayUuid, pb);
                if (pb.interactionUuid != null) byEntity.put(pb.interactionUuid, pb);
            } catch (Exception e) {
                plugin.getLogger().warning("placed.yml entry " + k + " is broken and was skipped: " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        int n = 0;
        for (PlacedBackpack pb : all()) {
            String k = "placed.p" + (n++);
            y.set(k + ".world", pb.world); y.set(k + ".x", pb.x); y.set(k + ".y", pb.y); y.set(k + ".z", pb.z);
            y.set(k + ".id", pb.id.toString()); y.set(k + ".tier", pb.tier); y.set(k + ".yaw", (double) pb.yaw);
            y.set(k + ".barrier", pb.barrier);
            y.set(k + ".item", Base64.getEncoder().encodeToString(pb.item.serializeAsBytes()));
            if (pb.displayUuid != null) y.set(k + ".display", pb.displayUuid.toString());
            if (pb.interactionUuid != null) y.set(k + ".interaction", pb.interactionUuid.toString());
        }
        try { y.save(file); } catch (IOException e) { plugin.getLogger().severe("Cannot save placed.yml: " + e.getMessage()); }
    }
}
