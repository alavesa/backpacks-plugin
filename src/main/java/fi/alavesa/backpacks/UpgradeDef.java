package fi.alavesa.backpacks;

import org.bukkit.Material;

/** One upgrade type as configured in config.yml (built-in ids, configurable name/material/filter slots). */
public record UpgradeDef(String id, String name, Material material, int filterSlots, String model, double radius, boolean requireReversible) {

    public static final String PICKUP = "pickup", ADVANCED_PICKUP = "advanced_pickup", MAGNET = "magnet", ADVANCED_MAGNET = "advanced_magnet",
        FEEDING = "feeding", ADVANCED_FEEDING = "advanced_feeding", VOID = "void", REFILL = "refill", COMPACTING = "compacting",
        INCEPTION = "inception", EVERLASTING = "everlasting", DEPOSIT = "deposit", RESTOCK = "restock";

    public static final String[] ALL = {PICKUP, ADVANCED_PICKUP, MAGNET, ADVANCED_MAGNET, FEEDING, ADVANCED_FEEDING, VOID, REFILL,
        COMPACTING, INCEPTION, EVERLASTING, DEPOSIT, RESTOCK};

    public boolean isPickup() { return id.equals(PICKUP) || id.equals(ADVANCED_PICKUP); }
    public boolean isMagnet() { return id.equals(MAGNET) || id.equals(ADVANCED_MAGNET); }
    public boolean isFeeding() { return id.equals(FEEDING) || id.equals(ADVANCED_FEEDING); }
    public boolean advanced() { return id.startsWith("advanced_"); }
    public boolean hasFilter() { return filterSlots > 0; }

    public String description() {
        return switch (id) {
            case PICKUP -> "Picked-up items go straight into the backpack.";
            case ADVANCED_PICKUP -> "Picked-up items go straight into the backpack. More filters.";
            case MAGNET -> "Pulls nearby items into the backpack.";
            case ADVANCED_MAGNET -> "Pulls items from further away into the backpack.";
            case FEEDING -> "Eats food from the backpack when you are hungry.";
            case ADVANCED_FEEDING -> "Keeps you fed, prefers the most filling food.";
            case VOID -> "Destroys filtered items as they are picked up.";
            case REFILL -> "Keeps your hotbar topped up from the backpack.";
            case COMPACTING -> "Crafts 2x2 / 3x3 blocks from items in the backpack.";
            case INCEPTION -> "Allows backpacks inside this backpack.";
            case EVERLASTING -> "The dropped backpack never despawns or burns.";
            case DEPOSIT -> "Sneak-right-click a container to deposit items into it.";
            case RESTOCK -> "Sneak-right-click a container to take items from it.";
            default -> "";
        };
    }
}
