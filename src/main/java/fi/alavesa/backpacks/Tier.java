package fi.alavesa.backpacks;

import org.bukkit.Material;

/** One backpack tier as configured in config.yml. */
public record Tier(String id, String name, int slots, int upgradeSlots, String model, Material material, String color, int order) {

    public int rows() { return Math.max(1, (int) Math.ceil(slots / 9.0)); }

    /** Size of the storage inventory (rows * 9). */
    public int inventorySize() { return rows() * 9; }

    public String itemModel(String namespace) { return namespace + ":" + fi.alavesa.backpacks.model.BbModel.safePath(model); }
}
