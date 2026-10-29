package fi.alavesa.backpacks.loot;

import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.InvUtil;
import fi.alavesa.backpacks.Settings;
import fi.alavesa.backpacks.Tier;
import fi.alavesa.backpacks.UpgradeDef;
import fi.alavesa.backpacks.data.BackpackData;
import fi.alavesa.backpacks.Items;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Backpacks and upgrades in loot chests; mobs that spawn wearing a backpack. */
public final class LootListener implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> REASONS = EnumSet.of(CreatureSpawnEvent.SpawnReason.NATURAL,
        CreatureSpawnEvent.SpawnReason.PATROL, CreatureSpawnEvent.SpawnReason.RAID, CreatureSpawnEvent.SpawnReason.REINFORCEMENTS);

    private final BackpacksPlugin plugin;
    private final Random random = new Random();

    public LootListener(BackpacksPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        Settings s = plugin.settings();
        if (!s.lootChestsEnabled || e.isPlugin()) return;
        NamespacedKey key = e.getLootTable().getKey();
        String path = key.getKey();
        if (s.lootTables.isEmpty()) { if (!path.startsWith("chests/")) return; }
        else { boolean ok = false; for (String t : s.lootTables) if (key.toString().contains(t)) { ok = true; break; } if (!ok) return; }
        if (random.nextDouble() < s.lootBackpackChance) {
            String tid = pick(s.lootTierWeights);
            Tier tier = plugin.settings().tier(tid);
            if (tier != null) e.getLoot().add(plugin.items().backpack(tier));
        }
        if (random.nextDouble() < s.lootUpgradeChance) {
            String uid = pick(s.lootUpgradeWeights);
            UpgradeDef def = plugin.settings().upgrade(uid);
            if (def != null) e.getLoot().add(plugin.items().upgrade(def));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        Settings s = plugin.settings();
        if (!s.mobsEnabled || !REASONS.contains(e.getSpawnReason())) return;
        if (!(e.getEntity() instanceof Mob mob) || !s.mobTypes.contains(mob.getType())) return;
        if (random.nextDouble() >= s.mobChance) return;
        if (mob.getEquipment() == null) return;
        ItemStack chest = mob.getEquipment().getChestplate();
        if (chest != null && !chest.isEmpty()) return;
        Tier tier = plugin.settings().tier(pick(s.mobTierWeights));
        if (tier == null) return;
        ItemStack bp = plugin.items().backpack(tier);
        UUID id = Items.idOf(bp);
        if (random.nextDouble() < s.mobLootChance) fillLoot(id, tier, mob);
        mob.getEquipment().setChestplate(bp);
        mob.getEquipment().setChestplateDropChance(s.mobDropChance);
        plugin.worn().markMob(mob);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.worn().update(mob));
    }

    private void fillLoot(UUID id, Tier tier, LivingEntity mob) {
        Settings s = plugin.settings();
        NamespacedKey key = NamespacedKey.fromString(s.mobLootTable);
        LootTable table = key == null ? null : Bukkit.getLootTable(key);
        if (table == null) return;
        BackpackData d = plugin.manager().data(id, tier);
        Collection<ItemStack> loot;
        try { loot = table.populateLoot(random, new LootContext.Builder(mob.getLocation()).lootedEntity(mob).build()); } catch (Exception ex) { return; }
        int n = 0;
        for (ItemStack it : loot) {
            if (it == null || it.isEmpty()) continue;
            if (n++ >= s.mobLootMaxStacks) break;
            InvUtil.add(d.contents, it);
        }
        d.dirty = true;
        plugin.manager().storage().save(d);
    }

    private String pick(Map<String, Double> weights) {
        double total = 0;
        for (double w : weights.values()) total += w;
        if (total <= 0) return null;
        double r = random.nextDouble() * total;
        for (var en : weights.entrySet()) { r -= en.getValue(); if (r <= 0) return en.getKey(); }
        return weights.keySet().iterator().next();
    }
}
