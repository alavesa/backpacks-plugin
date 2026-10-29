package fi.alavesa.backpacks.placed;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One backpack placed in the world. */
public final class PlacedBackpack {
    public final String world;
    public final int x, y, z;
    public final UUID id;
    public String tier;
    public float yaw;
    /** The backpack item as it was placed (returned on pickup, with the current tier/lore re-applied). */
    public ItemStack item;
    public UUID displayUuid, interactionUuid;
    public boolean barrier;

    public PlacedBackpack(String world, int x, int y, int z, UUID id, String tier, float yaw, ItemStack item) {
        this.world = world; this.x = x; this.y = y; this.z = z; this.id = id; this.tier = tier; this.yaw = yaw; this.item = item;
    }

    public static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL); }
    public long key() { return key(x, y, z); }
}
