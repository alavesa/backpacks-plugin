package fi.alavesa.backpacks.worn;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Keys;
import fi.alavesa.backpacks.Settings;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Renders the backpack in the chest slot as an ItemDisplay riding the wearer. The display is non-persistent
 * (never saved with chunks) and re-created from the equipment whenever the wearer (re)appears; its yaw
 * follows the wearer's body yaw every tick.
 */
public final class WornRenderer implements Listener {

    public static final String ROLE = "worn";

    private final BackpacksPlugin plugin;
    /** wearer uuid -> display uuid */
    private final Map<UUID, UUID> displays = new HashMap<>();
    private final Map<UUID, Float> lastYaw = new HashMap<>();

    public WornRenderer(BackpacksPlugin plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20L, 40L);
    }

    /** Brings the display of this wearer in line with its chest slot. */
    public void update(LivingEntity wearer) {
        if (!plugin.settings().wornEnabled) { detach(wearer.getUniqueId()); return; }
        ItemStack chest = wearer.getEquipment() == null ? null : wearer.getEquipment().getChestplate();
        boolean want = Items.isBackpack(chest) && wearer.isValid() && !wearer.isDead();
        if (!(wearer instanceof Player) && !plugin.settings().wornOnMobs) want = false;
        UUID du = displays.get(wearer.getUniqueId());
        Entity d = du == null ? null : Bukkit.getEntity(du);
        if (!want) { if (d != null) d.remove(); displays.remove(wearer.getUniqueId()); lastYaw.remove(wearer.getUniqueId()); return; }
        boolean healthy = d instanceof ItemDisplay && d.isValid() && d.getWorld().equals(wearer.getWorld()) && wearer.getPassengers().contains(d);
        if (healthy) {
            ItemDisplay id = (ItemDisplay) d;
            ItemStack shown = id.getItemStack();
            if (shown == null || !shown.isSimilar(chest)) id.setItemStack(chest.clone());
            return;
        }
        if (d != null) d.remove();
        attach(wearer, chest);
    }

    private void attach(LivingEntity wearer, ItemStack chest) {
        Settings s = plugin.settings();
        World w = wearer.getWorld();
        ItemDisplay d = w.spawn(wearer.getLocation(), ItemDisplay.class, e -> {
            e.setItemStack(chest.clone());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformation(transformation(s));
            e.setViewRange(s.wornViewRange);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, ROLE);
            e.getPersistentDataContainer().set(Keys.ENTITY_ID, PersistentDataType.STRING, wearer.getUniqueId().toString());
        });
        d.setRotation(wearer.getBodyYaw() + (float) s.wornYawOffset, 0);
        if (!wearer.addPassenger(d)) { d.remove(); return; }
        displays.put(wearer.getUniqueId(), d.getUniqueId());
    }

    public static Transformation transformation(Settings s) {
        float sc = (float) s.wornScale;
        return new Transformation(new Vector3f((float) s.wornTranslation[0], (float) s.wornTranslation[1], (float) s.wornTranslation[2]),
            new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf());
    }

    public void detach(UUID wearer) {
        UUID du = displays.remove(wearer);
        lastYaw.remove(wearer);
        if (du == null) return;
        Entity d = Bukkit.getEntity(du);
        if (d != null) d.remove();
    }

    public void detachAll() {
        for (UUID u : displays.keySet().toArray(new UUID[0])) detach(u);
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(ItemDisplay.class))
            if (ROLE.equals(e.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING))) e.remove();
    }

    /** Re-applies the configured transformation to every live display (after /backpack reload). */
    public void refreshAll() {
        for (UUID du : displays.values()) { Entity d = Bukkit.getEntity(du); if (d instanceof ItemDisplay id) id.setTransformation(transformation(plugin.settings())); }
    }

    private void tick() {
        if (displays.isEmpty()) return;
        float off = (float) plugin.settings().wornYawOffset;
        for (var it = displays.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Entity wearer = Bukkit.getEntity(en.getKey());
            Entity d = Bukkit.getEntity(en.getValue());
            if (!(wearer instanceof LivingEntity le) || d == null || !d.isValid()) { if (d != null) d.remove(); it.remove(); continue; }
            float yaw = le.getBodyYaw() + off;
            Float last = lastYaw.get(en.getKey());
            if (last == null || Math.abs(last - yaw) > 0.5f) { d.setRotation(yaw, 0); lastYaw.put(en.getKey(), yaw); }
        }
    }

    /** Periodic consistency pass over players (cheap) and tagged mobs. */
    private void sweep() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
        for (UUID u : displays.keySet().toArray(new UUID[0])) {
            Entity e = Bukkit.getEntity(u);
            if (e instanceof LivingEntity le && !(e instanceof Player)) update(le);
            else if (e == null) detach(u);
        }
    }

    public void markMob(LivingEntity mob) {
        mob.getPersistentDataContainer().set(Keys.MOB_BACKPACK, PersistentDataType.BYTE, (byte) 1);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(PlayerArmorChangeEvent e) {
        if (e.getSlot() != org.bukkit.inventory.EquipmentSlot.CHEST) return;
        Bukkit.getScheduler().runTask(plugin, () -> update(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 2L); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { detach(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        detach(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 2L); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 2L); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) { detach(e.getEntity().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (displays.containsKey(e.getEntity().getUniqueId())) detach(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof ItemDisplay && ROLE.equals(en.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING))) { en.remove(); continue; }
            if (en instanceof LivingEntity le && !(en instanceof Player) && le.getPersistentDataContainer().has(Keys.MOB_BACKPACK, PersistentDataType.BYTE))
                Bukkit.getScheduler().runTask(plugin, () -> update(le));
        }
    }
}
