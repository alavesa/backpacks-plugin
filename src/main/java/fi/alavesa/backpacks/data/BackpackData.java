package fi.alavesa.backpacks.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Server-side state of one backpack (keyed by the id stored on the item). */
public final class BackpackData {
    public final UUID id;
    public String tier;
    public ItemStack[] contents;
    public ItemStack[] upgrades;
    public boolean dirty;
    public long lastAccess = System.currentTimeMillis();
    /** Serialized form last written to disk (skip identical writes). */
    public byte[] lastSaved;

    public BackpackData(UUID id, String tier, int slots, int upgradeSlots) {
        this.id = id;
        this.tier = tier;
        this.contents = new ItemStack[slots];
        this.upgrades = new ItemStack[upgradeSlots];
    }

    public void touch() { lastAccess = System.currentTimeMillis(); }

    /** Resize to the tier's sizes; returns items that no longer fit (admin shrink). */
    public java.util.List<ItemStack> resize(int slots, int upgradeSlots) {
        java.util.List<ItemStack> overflow = new java.util.ArrayList<>();
        contents = resize(contents, slots, overflow);
        upgrades = resize(upgrades, upgradeSlots, overflow);
        dirty = true;
        return overflow;
    }

    private static ItemStack[] resize(ItemStack[] src, int n, java.util.List<ItemStack> overflow) {
        if (src.length == n) return src;
        ItemStack[] out = new ItemStack[n];
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null || src[i].isEmpty()) continue;
            if (i < n) out[i] = src[i];
            else {
                boolean placed = false;
                for (int j = 0; j < n && !placed; j++) if (out[j] == null) { out[j] = src[i]; placed = true; }
                if (!placed) overflow.add(src[i]);
            }
        }
        return out;
    }

    public boolean hasUpgrade(String id) {
        for (ItemStack u : upgrades) if (u != null && id.equals(fi.alavesa.backpacks.Items.upgradeIdOf(u))) return true;
        return false;
    }

    public ItemStack upgrade(String id) {
        for (ItemStack u : upgrades) if (u != null && id.equals(fi.alavesa.backpacks.Items.upgradeIdOf(u))) return u;
        return null;
    }
}
