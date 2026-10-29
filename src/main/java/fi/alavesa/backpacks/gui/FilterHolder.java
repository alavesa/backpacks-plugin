package fi.alavesa.backpacks.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Holder of one upgrade's filter screen. */
public final class FilterHolder implements InventoryHolder {
    public final UUID id;
    public final String tier;
    public final int upgradeSlot;
    public final String upgradeId;
    public final int filterSlots;
    Inventory inventory;

    public FilterHolder(UUID id, String tier, int upgradeSlot, String upgradeId, int filterSlots) {
        this.id = id; this.tier = tier; this.upgradeSlot = upgradeSlot; this.upgradeId = upgradeId; this.filterSlots = filterSlots;
    }

    @Override public Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inv) { this.inventory = inv; }
    public boolean isFilterSlot(int raw) { return raw >= 0 && raw < filterSlots; }
    public int size() { return filterSlots > 9 ? 36 : 27; }
    public int modeSlot() { return size() - 9 + 2; }
    public int clearSlot() { return size() - 9 + 4; }
    public int backSlot() { return size() - 9 + 6; }
}
