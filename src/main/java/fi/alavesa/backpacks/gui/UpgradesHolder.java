package fi.alavesa.backpacks.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Holder of the per-viewer upgrades screen. */
public final class UpgradesHolder implements InventoryHolder {
    public final UUID id;
    public final String tier;
    public final int upgradeSlots;
    Inventory inventory;

    public UpgradesHolder(UUID id, String tier, int upgradeSlots) { this.id = id; this.tier = tier; this.upgradeSlots = upgradeSlots; }

    @Override public Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inv) { this.inventory = inv; }
    public boolean isUpgradeSlot(int raw) { return raw >= 0 && raw < upgradeSlots; }
    public static final int SLOT_INFO = 18, SLOT_STORAGE = 22;
}
