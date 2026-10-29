package fi.alavesa.backpacks;

import org.bukkit.NamespacedKey;

/** All persistent-data keys. The namespace is the plugin name ("backpacks"). */
public final class Keys {
    public static final String NS = "backpacks";

    /** Backpack item: tier id. */
    public static final NamespacedKey TIER = new NamespacedKey(NS, "tier");
    /** Backpack item: storage UUID (string). */
    public static final NamespacedKey ID = new NamespacedKey(NS, "id");
    /** Upgrade item: upgrade type id. */
    public static final NamespacedKey UPGRADE = new NamespacedKey(NS, "upgrade");
    /** Upgrade item: filter items (bytes) and mode (string). */
    public static final NamespacedKey FILTER = new NamespacedKey(NS, "filter");
    public static final NamespacedKey FILTER_MODE = new NamespacedKey(NS, "filter_mode");
    /** Entity: role (worn / placed_display / placed_interaction) + the backpack id it belongs to. */
    public static final NamespacedKey ENTITY_ROLE = new NamespacedKey(NS, "role");
    public static final NamespacedKey ENTITY_ID = new NamespacedKey(NS, "backpack");
    /** Mob: spawned wearing a backpack (re-attach the display on load). */
    public static final NamespacedKey MOB_BACKPACK = new NamespacedKey(NS, "mob_backpack");
    /** GUI filler / button items. */
    public static final NamespacedKey GUI = new NamespacedKey(NS, "gui");

    private Keys() { }
}
