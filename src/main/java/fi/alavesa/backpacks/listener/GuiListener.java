package fi.alavesa.backpacks.listener;

import fi.alavesa.backpacks.BackpackManager;
import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Tier;
import fi.alavesa.backpacks.UpgradeDef;
import fi.alavesa.backpacks.data.BackpackData;
import fi.alavesa.backpacks.gui.BackpackHolder;
import fi.alavesa.backpacks.gui.FilterHolder;
import fi.alavesa.backpacks.gui.UpgradesHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Clicks inside the storage / upgrades / filter screens, plus "right-click a backpack in your inventory". */
public final class GuiListener implements Listener {

    private final BackpacksPlugin plugin;
    private final BackpackManager mgr;

    public GuiListener(BackpacksPlugin plugin) { this.plugin = plugin; this.mgr = plugin.manager(); }

    // ------------------------------------------------------------------ open-from-inventory

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryRightClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != p.getInventory()) return;
        if (e.getClick() != ClickType.RIGHT && e.getClick() != ClickType.SHIFT_RIGHT) return;
        ItemStack cursor = e.getCursor();
        if (cursor != null && !cursor.isEmpty()) return;
        ItemStack it = e.getCurrentItem();
        if (!Items.isBackpack(it)) return;
        Tier tier = plugin.items().tierOf(it);
        if (tier == null) return;
        InventoryHolder top = e.getView().getTopInventory().getHolder();
        UUID id = Items.ensureId(it);
        if (top instanceof BackpackHolder bh && bh.id.equals(id) && e.getClick() == ClickType.RIGHT) { e.setCancelled(true); return; }
        e.setCancelled(true);
        boolean upgrades = e.getClick() == ClickType.SHIFT_RIGHT;
        Bukkit.getScheduler().runTask(plugin, () -> { if (upgrades) mgr.openUpgrades(p, id, tier); else mgr.openStorage(p, id, tier); });
    }

    // ------------------------------------------------------------------ storage screen

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onStorageClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BackpackHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        boolean clickedTop = e.getClickedInventory() == top;
        Tier tier = plugin.settings().tier(h.tier);
        if (tier == null) { e.setCancelled(true); return; }
        if (clickedTop && Items.isGui(e.getCurrentItem())) { e.setCancelled(true); return; }
        if (clickedTop && e.getSlot() >= h.slots) { e.setCancelled(true); return; }

        ItemStack incoming = null;
        switch (e.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> { if (clickedTop) incoming = e.getCursor(); }
            case MOVE_TO_OTHER_INVENTORY -> { if (!clickedTop) incoming = e.getCurrentItem(); }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (clickedTop) incoming = e.getClick() == ClickType.SWAP_OFFHAND ? p.getInventory().getItemInOffHand() : (e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null);
            }
            case COLLECT_TO_CURSOR -> { if (Items.isBackpack(e.getCursor())) { e.setCancelled(true); return; } }
            default -> { }
        }
        if (incoming != null && !incoming.isEmpty() && !mgr.canInsert(h.id, tier, incoming)) {
            e.setCancelled(true);
            if (Items.isBackpack(incoming)) p.sendMessage(plugin.settings().msg("no-nesting"));
            return;
        }
        mgr.markDirty(h.id);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onStorageDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BackpackHolder h)) return;
        int size = e.getView().getTopInventory().getSize();
        boolean touchesTop = false;
        for (int raw : e.getRawSlots()) if (raw < size) { touchesTop = true; if (raw >= h.slots) { e.setCancelled(true); return; } }
        if (!touchesTop) return;
        Tier tier = plugin.settings().tier(h.tier);
        if (tier == null || !mgr.canInsert(h.id, tier, e.getOldCursor())) { e.setCancelled(true); return; }
        mgr.markDirty(h.id);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        if (e.getDestination().getHolder() instanceof BackpackHolder h) {
            Tier tier = plugin.settings().tier(h.tier);
            if (tier == null || !mgr.canInsert(h.id, tier, e.getItem())) { e.setCancelled(true); return; }
            mgr.markDirty(h.id);
        } else if (e.getSource().getHolder() instanceof BackpackHolder h) {
            mgr.markDirty(h.id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof BackpackHolder h) {
            mgr.releaseIfUnused(h.id, e.getPlayer());
            if (e.getPlayer() instanceof Player p) mgr.refreshItems(p, h.id);
        } else if (holder instanceof UpgradesHolder h) {
            if (e.getPlayer() instanceof Player p) mgr.refreshItems(p, h.id);
        }
    }

    // ------------------------------------------------------------------ upgrades screen

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUpgradesClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof UpgradesHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Tier tier = plugin.settings().tier(h.tier);
        if (tier == null) { e.setCancelled(true); return; }
        BackpackData d = mgr.data(h.id, tier);
        Inventory top = e.getView().getTopInventory();
        boolean clickedTop = e.getClickedInventory() == top;

        if (!clickedTop) {
            // bottom: shift-click an upgrade in; otherwise vanilla behaviour
            if (e.isShiftClick() && Items.isUpgradeItem(e.getCurrentItem())) {
                e.setCancelled(true);
                ItemStack up = e.getCurrentItem();
                int free = -1;
                for (int i = 0; i < Math.min(18, h.upgradeSlots); i++) if (d.upgrades[i] == null || d.upgrades[i].isEmpty()) { free = i; break; }
                if (free < 0) return;
                if (d.hasUpgrade(Items.upgradeIdOf(up))) { p.sendMessage(plugin.settings().msg("upgrade-duplicate")); return; }
                ItemStack one = up.clone(); one.setAmount(1);
                up.setAmount(up.getAmount() - 1);
                e.setCurrentItem(up.getAmount() <= 0 ? null : up);
                d.upgrades[free] = one; d.dirty = true;
                top.setItem(free, one.clone());
            } else if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
            }
            return;
        }

        e.setCancelled(true);
        int slot = e.getSlot();
        if (slot == UpgradesHolder.SLOT_STORAGE) { Bukkit.getScheduler().runTask(plugin, () -> mgr.openStorage(p, h.id, tier)); return; }
        if (!h.isUpgradeSlot(slot) || slot >= 18) return;
        ItemStack cursor = e.getCursor();
        ItemStack cur = d.upgrades[slot];
        boolean curEmpty = cur == null || cur.isEmpty();
        boolean cursorEmpty = cursor == null || cursor.isEmpty();

        if (cursorEmpty) {
            if (curEmpty) return;
            if (e.getClick() == ClickType.RIGHT) {
                UpgradeDef def = plugin.items().upgradeOf(cur);
                if (def != null && def.hasFilter()) { Bukkit.getScheduler().runTask(plugin, () -> mgr.openFilter(p, h.id, tier, slot)); return; }
            }
            if (e.isShiftClick()) {
                var left = p.getInventory().addItem(cur.clone());
                if (!left.isEmpty()) return;
            } else {
                e.getView().setCursor(cur.clone());
            }
            d.upgrades[slot] = null; d.dirty = true;
            top.setItem(slot, null);
            return;
        }
        if (!Items.isUpgradeItem(cursor)) { p.sendMessage(plugin.settings().msg("upgrade-only")); return; }
        String incomingId = Items.upgradeIdOf(cursor);
        for (int i = 0; i < d.upgrades.length; i++) {
            if (i == slot || d.upgrades[i] == null) continue;
            if (incomingId.equals(Items.upgradeIdOf(d.upgrades[i]))) { p.sendMessage(plugin.settings().msg("upgrade-duplicate")); return; }
        }
        ItemStack one = cursor.clone(); one.setAmount(1);
        ItemStack rest = cursor.clone(); rest.setAmount(cursor.getAmount() - 1);
        if (!curEmpty) {
            if (cursor.getAmount() > 1) return; // cannot swap a stack
            e.getView().setCursor(cur.clone());
        } else {
            e.getView().setCursor(rest.getAmount() <= 0 ? null : rest);
        }
        d.upgrades[slot] = one; d.dirty = true;
        top.setItem(slot, one.clone());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUpgradesDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof UpgradesHolder)) return;
        int size = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < size) { e.setCancelled(true); return; }
    }

    // ------------------------------------------------------------------ filter screen

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFilterClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof FilterHolder h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Tier tier = plugin.settings().tier(h.tier);
        if (tier == null) { e.setCancelled(true); return; }
        BackpackData d = mgr.data(h.id, tier);
        ItemStack up = h.upgradeSlot < d.upgrades.length ? d.upgrades[h.upgradeSlot] : null;
        if (up == null || !h.upgradeId.equals(Items.upgradeIdOf(up))) { e.setCancelled(true); p.closeInventory(); return; }
        UpgradeDef def = plugin.items().upgradeOf(up);
        Inventory top = e.getView().getTopInventory();
        boolean clickedTop = e.getClickedInventory() == top;

        if (!clickedTop) {
            if (e.isShiftClick() && e.getCurrentItem() != null && !e.getCurrentItem().isEmpty()) {
                e.setCancelled(true);
                for (int i = 0; i < h.filterSlots; i++) {
                    ItemStack f = top.getItem(i);
                    if (f == null || f.isEmpty()) { ItemStack c = e.getCurrentItem().clone(); c.setAmount(1); top.setItem(i, c); break; }
                }
                saveFilter(h, top, d, up, def);
            } else if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);
        int slot = e.getSlot();
        if (h.isFilterSlot(slot)) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.isEmpty()) { ItemStack c = cursor.clone(); c.setAmount(1); top.setItem(slot, c); }
            else top.setItem(slot, null);
            saveFilter(h, top, d, up, def);
        } else if (slot == h.modeSlot()) {
            Items.FilterMode m = Items.filterMode(up).next(BackpackManager.allowsExisting(def));
            Items.setFilterMode(up, m);
            plugin.items().decorateUpgrade(up, def);
            d.dirty = true;
            top.setItem(h.modeSlot(), mgr.modeButton(m, def));
        } else if (slot == h.clearSlot()) {
            for (int i = 0; i < h.filterSlots; i++) top.setItem(i, null);
            saveFilter(h, top, d, up, def);
        } else if (slot == h.backSlot()) {
            Bukkit.getScheduler().runTask(plugin, () -> mgr.openUpgrades(p, h.id, tier));
        }
    }

    private void saveFilter(FilterHolder h, Inventory top, BackpackData d, ItemStack up, UpgradeDef def) {
        List<ItemStack> filter = new ArrayList<>();
        for (int i = 0; i < h.filterSlots; i++) { ItemStack f = top.getItem(i); if (f != null && !f.isEmpty()) filter.add(f); }
        plugin.items().setFilter(up, filter);
        d.dirty = true;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFilterDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof FilterHolder)) return;
        int size = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < size) { e.setCancelled(true); return; }
    }
}
