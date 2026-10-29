package fi.alavesa.backpacks.upgrade;

import fi.alavesa.backpacks.BackpackManager;
import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.InvUtil;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Settings;
import fi.alavesa.backpacks.Tier;
import fi.alavesa.backpacks.UpgradeDef;
import fi.alavesa.backpacks.data.BackpackData;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.UseRemainder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runs every upgrade: event-driven (pickup, void, everlasting, deposit/restock) and ticking (magnet, feeding, refill, compacting). */
public final class UpgradeEngine implements Listener {

    /** A backpack whose upgrades are currently in effect for a player. */
    public record Active(UUID id, Tier tier, BackpackData data, boolean worn) { }

    private record CompactRule(int count, ItemStack result) { }

    private final BackpacksPlugin plugin;
    private final BackpackManager mgr;
    private final Map<Material, CompactRule> compact9 = new HashMap<>(), compact4 = new HashMap<>();
    private int tickCounter;

    public UpgradeEngine(BackpacksPlugin plugin) { this.plugin = plugin; this.mgr = plugin.manager(); }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, plugin.settings().upgradeTickInterval);
    }

    public void clearCaches() { compact9.clear(); compact4.clear(); }

    // ------------------------------------------------------------------ active backpacks

    /** The backpacks whose upgrades apply to this player, worn first, then inventory order; inception nests one level. */
    public List<Active> active(Player p) {
        Settings s = plugin.settings();
        List<Active> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        add(out, inv.getChestplate(), true);
        if (s.upgradesInInventory) for (int i = 0; i < 36; i++) add(out, inv.getItem(i), false);
        if (s.upgradesInOffhand) add(out, inv.getItemInOffHand(), false);
        // inception: backpacks inside active backpacks
        for (Active a : new ArrayList<>(out)) {
            if (!a.data().hasUpgrade(UpgradeDef.INCEPTION)) continue;
            for (ItemStack it : mgr.contents(a.id(), a.tier())) add(out, it, false);
        }
        return out;
    }

    private void add(List<Active> out, ItemStack it, boolean worn) {
        if (!Items.isBackpack(it)) return;
        Tier tier = plugin.items().tierOf(it);
        UUID id = Items.idOf(it);
        if (tier == null || id == null) return;
        for (Active a : out) if (a.id().equals(id)) return;
        BackpackData d = mgr.data(id, tier);
        boolean any = false;
        for (ItemStack u : d.upgrades) if (u != null && !u.isEmpty()) { any = true; break; }
        if (!any) return;
        out.add(new Active(id, tier, d, worn));
    }

    private List<ItemStack> upgradesOf(Active a, java.util.function.Predicate<UpgradeDef> which) {
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack u : a.data().upgrades) { UpgradeDef def = plugin.items().upgradeOf(u); if (def != null && which.test(def)) l.add(u); }
        return l;
    }

    // ------------------------------------------------------------------ pickup + void

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack stack = e.getItem().getItemStack();
        if (stack.isEmpty()) return;
        List<Active> actives = active(p);
        if (actives.isEmpty()) return;
        // void first
        for (Active a : actives) for (ItemStack v : upgradesOf(a, d -> d.id().equals(UpgradeDef.VOID))) {
            if (Items.passesStrict(v, stack)) { e.setCancelled(true); e.getItem().remove(); return; }
        }
        ItemStack rest = stack.clone();
        boolean any = false;
        for (Active a : actives) {
            if (rest.getAmount() <= 0) break;
            for (ItemStack u : upgradesOf(a, UpgradeDef::isPickup)) {
                if (!Items.passes(u, rest, null) || !mgr.canInsert(a.id(), a.tier(), rest)) continue;
                ItemStack left = mgr.insert(a.id(), a.tier(), rest);
                any = true;
                rest = left == null ? new ItemStack(Material.AIR) : left;
                break;
            }
        }
        if (!any) return;
        e.setCancelled(true);
        if (rest.getAmount() <= 0) {
            e.getItem().remove();
        } else {
            e.getItem().setItemStack(rest);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.5f + (float) Math.random() * 0.5f);
    }

    // ------------------------------------------------------------------ everlasting

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (!Items.isBackpack(it)) return;
        Tier tier = plugin.items().tierOf(it);
        UUID id = Items.idOf(it);
        if (tier == null || id == null) return;
        if (!mgr.data(id, tier).hasUpgrade(UpgradeDef.EVERLASTING)) return;
        Item item = e.getEntity();
        item.setInvulnerable(true);
        item.setUnlimitedLifetime(true);
        item.setWillAge(false);
        item.setPersistent(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item item) || e.getCause() != EntityDamageEvent.DamageCause.VOID) return;
        if (!item.isInvulnerable() || !Items.isBackpack(item.getItemStack())) return;
        e.setCancelled(true);
        Location spawn = item.getWorld().getSpawnLocation().add(0.5, 1, 0.5);
        item.teleport(spawn);
        item.setVelocity(new org.bukkit.util.Vector());
    }

    // ------------------------------------------------------------------ ticking upgrades

    private void tick() {
        tickCounter += plugin.settings().upgradeTickInterval;
        boolean second = tickCounter % 20 < plugin.settings().upgradeTickInterval;
        boolean twoSeconds = tickCounter % 40 < plugin.settings().upgradeTickInterval;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            List<Active> actives;
            try { actives = active(p); } catch (Exception ex) { continue; }
            if (actives.isEmpty()) continue;
            for (Active a : actives) {
                try {
                    magnet(p, a);
                    if (second) { feeding(p, a); refill(p, a); }
                    if (twoSeconds) compacting(p, a);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Upgrade tick failed for " + p.getName() + " / " + a.id() + ": " + ex);
                }
            }
        }
    }

    private void magnet(Player p, Active a) {
        List<ItemStack> mags = upgradesOf(a, UpgradeDef::isMagnet);
        if (mags.isEmpty()) return;
        double r = 0;
        for (ItemStack m : mags) { UpgradeDef d = plugin.items().upgradeOf(m); if (d != null) r = Math.max(r, d.radius()); }
        if (r <= 0) return;
        for (Item item : p.getWorld().getNearbyEntitiesByType(Item.class, p.getLocation(), r, r, r)) {
            if (!item.isValid() || item.getPickupDelay() > 0 || !item.canPlayerPickup()) continue;
            if (p.getUniqueId().equals(item.getThrower()) && item.getTicksLived() < 60) continue;
            ItemStack stack = item.getItemStack();
            if (stack.isEmpty()) continue;
            for (ItemStack m : mags) {
                if (!Items.passes(m, stack, null) || !mgr.canInsert(a.id(), a.tier(), stack)) continue;
                ItemStack left = mgr.insert(a.id(), a.tier(), stack);
                if (left == null) item.remove(); else item.setItemStack(left);
                p.getWorld().playSound(item.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.25f, 1.6f + (float) Math.random() * 0.4f);
                break;
            }
        }
    }

    private void feeding(Player p, Active a) {
        if (p.getFoodLevel() >= 20) return;
        List<ItemStack> feeds = upgradesOf(a, UpgradeDef::isFeeding);
        if (feeds.isEmpty()) return;
        boolean advanced = false;
        for (ItemStack f : feeds) { UpgradeDef d = plugin.items().upgradeOf(f); if (d != null && d.advanced()) advanced = true; }
        ItemStack[] contents = mgr.contents(a.id(), a.tier());
        int bestIdx = -1, bestNut = -1;
        FoodProperties bestFood = null;
        int missing = 20 - p.getFoodLevel();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.isEmpty() || !s.hasData(DataComponentTypes.FOOD)) continue;
            FoodProperties food = s.getData(DataComponentTypes.FOOD);
            if (food == null) continue;
            boolean ok = false;
            for (ItemStack f : feeds) if (Items.passes(f, s, null)) { ok = true; break; }
            if (!ok) continue;
            int nut = food.nutrition();
            if (!advanced && nut > missing && p.getFoodLevel() > 6) continue;   // don't waste food
            boolean better = advanced ? nut > bestNut : (bestNut < 0 || nut > bestNut);
            if (better) { bestIdx = i; bestNut = nut; bestFood = food; }
        }
        if (bestIdx < 0) return;
        ItemStack eaten = contents[bestIdx];
        ItemStack one = eaten.clone(); one.setAmount(1);
        eaten.setAmount(eaten.getAmount() - 1);
        if (eaten.getAmount() <= 0) contents[bestIdx] = null;
        p.setFoodLevel(Math.min(20, p.getFoodLevel() + bestFood.nutrition()));
        p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + bestFood.saturation()));
        if (one.hasData(DataComponentTypes.USE_REMAINDER)) {
            UseRemainder rem = one.getData(DataComponentTypes.USE_REMAINDER);
            if (rem != null && !rem.transformInto().isEmpty()) {
                ItemStack left = InvUtil.add(contents, rem.transformInto().clone());
                if (left != null) p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        mgr.setContents(a.id(), a.tier(), contents);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1f);
    }

    private void refill(Player p, Active a) {
        List<ItemStack> refills = upgradesOf(a, d -> d.id().equals(UpgradeDef.REFILL));
        if (refills.isEmpty()) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = null;
        boolean changed = false;
        for (int slot = 0; slot <= 9; slot++) {
            ItemStack h = slot == 9 ? inv.getItemInOffHand() : inv.getItem(slot);
            if (h == null || h.isEmpty() || h.getAmount() >= h.getMaxStackSize() || Items.isBackpack(h)) continue;
            boolean ok = false;
            for (ItemStack r : refills) if (Items.passes(r, h, null)) { ok = true; break; }
            if (!ok) continue;
            if (contents == null) contents = mgr.contents(a.id(), a.tier());
            int need = h.getMaxStackSize() - h.getAmount();
            int got = InvUtil.remove(contents, h, need);
            if (got <= 0) continue;
            h.setAmount(h.getAmount() + got);
            if (slot == 9) inv.setItemInOffHand(h); else inv.setItem(slot, h);
            changed = true;
        }
        if (changed) mgr.setContents(a.id(), a.tier(), contents);
    }

    private void compacting(Player p, Active a) {
        List<ItemStack> comps = upgradesOf(a, d -> d.id().equals(UpgradeDef.COMPACTING));
        if (comps.isEmpty()) return;
        boolean reversible = true;
        for (ItemStack c : comps) { UpgradeDef d = plugin.items().upgradeOf(c); if (d != null && !d.requireReversible()) reversible = false; }
        ItemStack[] contents = mgr.contents(a.id(), a.tier());
        Map<Material, ItemStack> types = new LinkedHashMap<>();
        for (ItemStack s : contents) if (s != null && !s.isEmpty() && !Items.isBackpack(s) && !Items.isUpgradeItem(s) && !s.hasItemMeta()) types.putIfAbsent(s.getType(), s);
        boolean changed = false;
        for (ItemStack sample : types.values()) {
            boolean ok = false;
            for (ItemStack c : comps) if (Items.passes(c, sample, null)) { ok = true; break; }
            if (!ok) continue;
            CompactRule rule = rule(sample, p, reversible);
            if (rule == null) continue;
            while (InvUtil.count(contents, sample) >= rule.count()) {
                ItemStack[] trial = clone(contents);
                InvUtil.remove(trial, sample, rule.count());
                if (InvUtil.add(trial, rule.result().clone()) != null) break;
                contents = trial;
                changed = true;
            }
        }
        if (changed) mgr.setContents(a.id(), a.tier(), contents);
    }

    private static ItemStack[] clone(ItemStack[] arr) {
        ItemStack[] c = new ItemStack[arr.length];
        for (int i = 0; i < arr.length; i++) c[i] = arr[i] == null ? null : arr[i].clone();
        return c;
    }

    private CompactRule rule(ItemStack sample, Player p, boolean reversible) {
        Material m = sample.getType();
        if (compact9.containsKey(m)) { CompactRule r = compact9.get(m); if (r != null) return r; }
        else {
            CompactRule r = tryRule(sample, 9, p, reversible);
            compact9.put(m, r);
            if (r != null) return r;
        }
        if (compact4.containsKey(m)) return compact4.get(m);
        CompactRule r = tryRule(sample, 4, p, reversible);
        compact4.put(m, r);
        return r;
    }

    private CompactRule tryRule(ItemStack sample, int n, Player p, boolean reversible) {
        ItemStack one = sample.clone(); one.setAmount(1);
        ItemStack[] matrix = new ItemStack[9];
        if (n == 9) for (int i = 0; i < 9; i++) matrix[i] = one.clone();
        else { matrix[0] = one.clone(); matrix[1] = one.clone(); matrix[3] = one.clone(); matrix[4] = one.clone(); }
        Recipe r = Bukkit.getCraftingRecipe(matrix, p.getWorld());
        if (r == null) return null;
        ItemStack result = r.getResult();
        if (result == null || result.isEmpty() || result.getAmount() != 1 || result.getType() == sample.getType()) return null;
        if (reversible) {
            ItemStack[] back = new ItemStack[9];
            back[0] = result.clone();
            Recipe rr = Bukkit.getCraftingRecipe(back, p.getWorld());
            if (rr == null) return null;
            ItemStack out = rr.getResult();
            if (out == null || out.getType() != sample.getType() || out.getAmount() != n) return null;
        }
        return new CompactRule(n, result.clone());
    }

    // ------------------------------------------------------------------ deposit / restock

    public void depositRestock(Player p, UUID id, Tier tier, BackpackData d, Container c) {
        ItemStack[] contents = mgr.contents(id, tier);
        org.bukkit.inventory.Inventory target = c.getInventory();
        int moved = 0;
        List<ItemStack> targetItems = new ArrayList<>();
        for (ItemStack t : target.getContents()) if (t != null && !t.isEmpty()) targetItems.add(t);
        ItemStack dep = d.upgrade(UpgradeDef.DEPOSIT);
        if (dep != null) {
            for (int i = 0; i < contents.length; i++) {
                ItemStack s = contents[i];
                if (s == null || s.isEmpty() || Items.isUpgradeItem(s)) continue;
                if (!Items.passes(dep, s, targetItems)) continue;
                var left = target.addItem(s.clone());
                int put = s.getAmount() - (left.isEmpty() ? 0 : left.values().iterator().next().getAmount());
                if (put <= 0) continue;
                moved += put;
                s.setAmount(s.getAmount() - put);
                contents[i] = s.getAmount() <= 0 ? null : s;
            }
            mgr.setContents(id, tier, contents);
            p.sendMessage(plugin.settings().msg("deposit-done", Settings.ph("count", String.valueOf(moved))));
        }
        ItemStack res = d.upgrade(UpgradeDef.RESTOCK);
        if (res != null) {
            int got = 0;
            List<ItemStack> mine = new ArrayList<>();
            for (ItemStack s : contents) if (s != null && !s.isEmpty()) mine.add(s.clone());
            for (int i = 0; i < target.getSize(); i++) {
                ItemStack t = target.getItem(i);
                if (t == null || t.isEmpty()) continue;
                if (!Items.passes(res, t, mine) || !mgr.canInsert(id, tier, t)) continue;
                ItemStack left = InvUtil.add(contents, t.clone());
                int put = t.getAmount() - (left == null ? 0 : left.getAmount());
                if (put <= 0) continue;
                got += put;
                t.setAmount(t.getAmount() - put);
                target.setItem(i, t.getAmount() <= 0 ? null : t);
            }
            mgr.setContents(id, tier, contents);
            p.sendMessage(plugin.settings().msg("restock-done", Settings.ph("count", String.valueOf(got))));
        }
        if (dep != null || res != null) p.getWorld().playSound(p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.2f);
    }
}
