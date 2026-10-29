package fi.alavesa.backpacks.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Holder of a backpack's shared storage inventory. */
public final class BackpackHolder implements InventoryHolder {
    public final UUID id;
    public final String tier;
    public final int slots;
    Inventory inventory;

    public BackpackHolder(UUID id, String tier, int slots) { this.id = id; this.tier = tier; this.slots = slots; }

    @Override public Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inv) { this.inventory = inv; }
}
