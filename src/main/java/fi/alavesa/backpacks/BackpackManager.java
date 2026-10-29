package fi.alavesa.backpacks;

import fi.alavesa.backpacks.data.BackpackData;
import fi.alavesa.backpacks.data.Storage;
import fi.alavesa.backpacks.gui.BackpackHolder;
import fi.alavesa.backpacks.gui.FilterHolder;
import fi.alavesa.backpacks.gui.UpgradesHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything that touches a backpack's contents goes through here: the shared storage inventory (one per
 * backpack, chest-like - several players and hoppers see the same one), the upgrade and filter screens,
 * and insert/remove helpers used by upgrades that work on the live inventory when one is open and on the
 * stored data otherwise.
 */
public final class BackpackManager {

    private final BackpacksPlugin plugin;
    private final Storage storage;
    private final Map<UUID, Inventory> open = new HashMap<>();
    /** Backpacks whose inventory must stay alive even without viewers (placed in the world). */
    private final Set<UUID> pinned = new HashSet<>();

    public BackpackManager(BackpacksPlugin plugin, Storage storage) { this.plugin = plugin; this.storage = storage; }

    public Storage storage() { return storage; }
    public Set<UUID> pinned() { return pinned; }

    public BackpackData data(UUID id, Tier tier) { return storage.get(id, tier); }

    // ------------------------------------------------------------------ storage inventory

    /** The live shared storage inventory (created from data when needed). */
    public Inventory storageInventory(UUID id, Tier tier) {
        Inventory inv = open.get(id);
        if (inv != null) {
            BackpackHolder h = (BackpackHolder) inv.getHolder();
            if (h != null && h.slots == tier.slots() && h.tier.equals(tier.id())) return inv;
            syncFromInventory(id);          // tier changed while open: rebuild
            for (HumanEntity v : new ArrayList<>(inv.getViewers())) v.closeInventory();
            open.remove(id);
        }
        BackpackData d = data(id, tier);
        BackpackHolder holder = new BackpackHolder(id, tier.id(), tier.slots());
        inv = Bukkit.createInventory(holder, tier.inventorySize(), Settings.mm(plugin.settings().storageTitle, Settings.ph("name", tier.name())));
        holder.setInventory(inv);
        for (int i = 0; i < tier.slots(); i++) if (d.contents[i] != null && !d.contents[i].isEmpty()) inv.setItem(i, d.contents[i].clone());
        for (int i = tier.slots(); i < tier.inventorySize(); i++) inv.setItem(i, locked());
        open.put(id, inv);
        return inv;
    }

    public Inventory openInventory(UUID id) { return open.get(id); }

    /** Copies the live inventory back into the data (if one is open). */
    public void syncFromInventory(UUID id) {
        Inventory inv = open.get(id);
        if (inv == null) return;
        BackpackHolder h = (BackpackHolder) inv.getHolder();
        BackpackData d = storage.cached(id);
        if (h == null || d == null) return;
        if (d.contents.length < h.slots) d.contents = java.util.Arrays.copyOf(d.contents, h.slots);
        for (int i = 0; i < h.slots; i++) {
            ItemStack s = inv.getItem(i);
            d.contents[i] = s == null || s.isEmpty() || Items.isGui(s) ? null : s.clone();
        }
        d.dirty = true;
        d.touch();
    }

    /** Called after a viewer closed: drops the shared inventory when nobody else uses it. */
    public void releaseIfUnused(UUID id, HumanEntity closing) {
        Inventory inv = open.get(id);
        if (inv == null) return;
        for (HumanEntity v : inv.getViewers()) if (v != closing) return;
        syncFromInventory(id);
        BackpackData d = storage.cached(id);
        if (d != null) storage.save(d);
        if (!pinned.contains(id)) open.remove(id);
    }

    public void pin(UUID id, Tier tier) { pinned.add(id); storageInventory(id, tier); }

    public void unpin(UUID id) {
        pinned.remove(id);
        Inventory inv = open.get(id);
        if (inv != null && inv.getViewers().isEmpty()) {
            syncFromInventory(id);
            BackpackData d = storage.cached(id);
            if (d != null) storage.save(d);
            open.remove(id);
        }
    }

    /** Flushes every open inventory into data and saves (autosave / shutdown). */
    public void flush(boolean evict) {
        for (UUID id : new ArrayList<>(open.keySet())) syncFromInventory(id);
        Set<UUID> keep = new HashSet<>(pinned);
        keep.addAll(open.keySet());
        storage.flush(keep, evict);
    }

    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            var h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof BackpackHolder || h instanceof UpgradesHolder || h instanceof FilterHolder) p.closeInventory();
        }
        flush(false);
        open.clear();
    }

    // ------------------------------------------------------------------ opening screens

    /** Opens the storage of the backpack item (assigning an id if it has none). */
    public boolean openStorage(Player p, ItemStack item) {
        Tier tier = plugin.items().tierOf(item);
        if (tier == null) return false;
        UUID id = Items.ensureId(item);
        return openStorage(p, id, tier);
    }

    public boolean openStorage(Player p, UUID id, Tier tier) {
        if (!p.hasPermission("backpacks.use")) { p.sendMessage(plugin.settings().msg("no-permission")); return false; }
        Inventory inv = storageInventory(id, tier);
        p.openInventory(inv);
        return true;
    }

    public void openUpgrades(Player p, UUID id, Tier tier) {
        if (!p.hasPermission("backpacks.use")) { p.sendMessage(plugin.settings().msg("no-permission")); return; }
        BackpackData d = data(id, tier);
        UpgradesHolder holder = new UpgradesHolder(id, tier.id(), tier.upgradeSlots());
        Inventory inv = Bukkit.createInventory(holder, 27, Settings.mm(plugin.settings().upgradesTitle, Settings.ph("name", tier.name())));
        holder.setInventory(inv);
        for (int i = 0; i < 27; i++) inv.setItem(i, i < 18 ? (i < tier.upgradeSlots() ? null : locked()) : filler());
        for (int i = 0; i < tier.upgradeSlots() && i < 18; i++) if (d.upgrades[i] != null && !d.upgrades[i].isEmpty()) inv.setItem(i, d.upgrades[i].clone());
        List<Component> info = new ArrayList<>();
        info.add(Items.gray("Upgrade slots: " + tier.upgradeSlots() + "   Storage: " + tier.slots()));
        info.add(Items.dark("Drop upgrade items into the top row."));
        info.add(Items.dark("Right-click an installed upgrade to set its filter."));
        ItemStack infoItem = Items.gui(tier.material(), "info", Component.text(tier.name(), NamedTextColor.GOLD), info);
        try { infoItem.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(tier.itemModel(plugin.settings().packNamespace))); } catch (Exception ignored) { }
        inv.setItem(UpgradesHolder.SLOT_INFO, infoItem);
        inv.setItem(UpgradesHolder.SLOT_STORAGE, Items.gui(Material.CHEST, "storage", Component.text("Open storage", NamedTextColor.GREEN), List.of(Items.dark("Click to open the backpack."))));
        p.openInventory(inv);
    }

    public void openFilter(Player p, UUID id, Tier tier, int upgradeSlot) {
        BackpackData d = data(id, tier);
        if (upgradeSlot < 0 || upgradeSlot >= d.upgrades.length) return;
        ItemStack up = d.upgrades[upgradeSlot];
        UpgradeDef def = plugin.items().upgradeOf(up);
        if (def == null || !def.hasFilter()) return;
        FilterHolder holder = new FilterHolder(id, tier.id(), upgradeSlot, def.id(), def.filterSlots());
        Inventory inv = Bukkit.createInventory(holder, holder.size(), Settings.mm(plugin.settings().filterTitle, Settings.ph("upgrade", def.name()), Settings.ph("name", tier.name())));
        holder.setInventory(inv);
        for (int i = 0; i < holder.size(); i++) inv.setItem(i, i < 18 ? (i < def.filterSlots() ? null : locked()) : filler());
        List<ItemStack> filter = Items.filterOf(up);
        for (int i = 0; i < filter.size() && i < def.filterSlots(); i++) inv.setItem(i, filter.get(i));
        inv.setItem(holder.modeSlot(), modeButton(Items.filterMode(up), def));
        inv.setItem(holder.clearSlot(), Items.gui(Material.BARRIER, "clear", Component.text("Clear filter", NamedTextColor.RED), List.of(Items.dark("Removes every filter entry."))));
        inv.setItem(holder.backSlot(), Items.gui(Material.ARROW, "back", Component.text("Back to upgrades", NamedTextColor.YELLOW), null));
        p.openInventory(inv);
    }

    public ItemStack modeButton(Items.FilterMode mode, UpgradeDef def) {
        List<Component> lore = new ArrayList<>();
        lore.add(Items.gray("Whitelist: only listed items.   Blacklist: everything but listed items."));
        if (allowsExisting(def)) lore.add(Items.gray("Match existing: only items already in the target."));
        lore.add(Items.dark("Click items from your inventory onto the filter slots (they are not consumed)."));
        lore.add(Items.dark("Click to change the mode."));
        Material m = switch (mode) { case WHITELIST -> Material.LIME_DYE; case BLACKLIST -> Material.RED_DYE; default -> Material.YELLOW_DYE; };
        return Items.gui(m, "mode", Component.text("Mode: " + mode.label(), NamedTextColor.AQUA), lore);
    }

    public static boolean allowsExisting(UpgradeDef def) { return def.id().equals(UpgradeDef.DEPOSIT) || def.id().equals(UpgradeDef.RESTOCK); }

    // ------------------------------------------------------------------ content operations (live or stored)

    /** Live contents snapshot (the open inventory if any). Length = storage slots. */
    public ItemStack[] contents(UUID id, Tier tier) {
        Inventory inv = open.get(id);
        if (inv != null) {
            BackpackHolder h = (BackpackHolder) inv.getHolder();
            ItemStack[] out = new ItemStack[h == null ? tier.slots() : h.slots];
            for (int i = 0; i < out.length; i++) { ItemStack s = inv.getItem(i); out[i] = s == null || s.isEmpty() || Items.isGui(s) ? null : s; }
            return out;
        }
        return data(id, tier).contents;
    }

    /** Writes contents produced by an upgrade pass back (live inventory or data). */
    public void setContents(UUID id, Tier tier, ItemStack[] contents) {
        Inventory inv = open.get(id);
        if (inv != null) {
            BackpackHolder h = (BackpackHolder) inv.getHolder();
            int n = h == null ? tier.slots() : h.slots;
            for (int i = 0; i < n && i < contents.length; i++) {
                ItemStack cur = inv.getItem(i), want = contents[i];
                boolean same = (cur == null || cur.isEmpty()) ? (want == null || want.isEmpty()) : cur.equals(want);
                if (!same) inv.setItem(i, want == null || want.isEmpty() ? null : want);
            }
            BackpackData d = storage.cached(id);
            if (d != null) { d.dirty = true; d.touch(); }
            return;
        }
        BackpackData d = data(id, tier);
        d.contents = contents;
        d.dirty = true;
    }

    /** Inserts; returns what did not fit (null = all in). */
    public ItemStack insert(UUID id, Tier tier, ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        Inventory inv = open.get(id);
        if (inv != null) {
            ItemStack[] arr = contents(id, tier);
            ItemStack left = InvUtil.add(arr, item);
            setContents(id, tier, arr);
            return left;
        }
        BackpackData d = data(id, tier);
        ItemStack left = InvUtil.add(d.contents, item);
        d.dirty = true;
        return left;
    }

    public void markDirty(UUID id) {
        BackpackData d = storage.cached(id);
        if (d != null) { d.dirty = true; d.touch(); }
    }

    /** Can this item go inside the backpack {@code id}? Backpacks need the inception upgrade and never nest into themselves. */
    public boolean canInsert(UUID id, Tier tier, ItemStack item) {
        if (item == null || item.isEmpty()) return true;
        if (Items.isGui(item)) return false;
        if (!Items.isBackpack(item)) return true;
        UUID other = Items.idOf(item);
        if (other != null && other.equals(id)) return false;
        BackpackData d = data(id, tier);
        if (!d.hasUpgrade(UpgradeDef.INCEPTION)) return false;
        // the item must not (transitively) contain us
        return !contains(other, id, 0);
    }

    private boolean contains(UUID holder, UUID target, int depth) {
        if (holder == null || depth > 8) return false;
        if (!storage.exists(holder)) return false;
        BackpackData d = storage.get(holder, null);
        for (ItemStack s : contents(holder, plugin.settings().tier(d.tier) == null ? fallbackTier(d) : plugin.settings().tier(d.tier))) {
            if (!Items.isBackpack(s)) continue;
            UUID sid = Items.idOf(s);
            if (sid == null) continue;
            if (sid.equals(target) || contains(sid, target, depth + 1)) return true;
        }
        return false;
    }

    private Tier fallbackTier(BackpackData d) {
        return new Tier(d.tier, d.tier, d.contents.length, d.upgrades.length, "", Material.PAPER, "#FFFFFF", 0);
    }

    /** Installed upgrade names (for lore). */
    public List<String> upgradeNames(UUID id, Tier tier) {
        List<String> names = new ArrayList<>();
        for (ItemStack u : data(id, tier).upgrades) { UpgradeDef def = plugin.items().upgradeOf(u); if (def != null) names.add(def.name()); }
        return names;
    }

    /** Refreshes the lore of every copy of this backpack the player carries. */
    public void refreshItems(Player p, UUID id) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) refreshItem(inv.getItem(i), id);
        refreshItem(inv.getItemInOffHand(), id);
        refreshItem(inv.getChestplate(), id);
    }

    private void refreshItem(ItemStack it, UUID id) {
        if (!Items.isBackpack(it) || !id.equals(Items.idOf(it))) return;
        Tier tier = plugin.items().tierOf(it);
        if (tier == null) return;
        plugin.items().decorateBackpack(it, tier, upgradeNames(id, tier));
    }

    public static ItemStack locked() { return Items.gui(Material.BLACK_STAINED_GLASS_PANE, "locked", Component.empty(), null); }
    public static ItemStack filler() { return Items.gui(Material.GRAY_STAINED_GLASS_PANE, "filler", Component.empty(), null); }
}
