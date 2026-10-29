package fi.alavesa.backpacks;

import org.bukkit.inventory.ItemStack;

/** Array-based inventory helpers (merge-aware). */
public final class InvUtil {
    private InvUtil() { }

    /** Adds as much as fits; returns the leftover (null when everything fit). Mutates the array. */
    public static ItemStack add(ItemStack[] arr, ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        ItemStack rest = item.clone();
        for (int i = 0; i < arr.length && rest.getAmount() > 0; i++) {
            ItemStack s = arr[i];
            if (s == null || s.isEmpty() || !s.isSimilar(rest)) continue;
            int max = s.getMaxStackSize(), room = max - s.getAmount();
            if (room <= 0) continue;
            int mv = Math.min(room, rest.getAmount());
            s.setAmount(s.getAmount() + mv);
            rest.setAmount(rest.getAmount() - mv);
        }
        for (int i = 0; i < arr.length && rest.getAmount() > 0; i++) {
            if (arr[i] != null && !arr[i].isEmpty()) continue;
            int mv = Math.min(rest.getMaxStackSize(), rest.getAmount());
            ItemStack put = rest.clone(); put.setAmount(mv);
            arr[i] = put;
            rest.setAmount(rest.getAmount() - mv);
        }
        return rest.getAmount() <= 0 ? null : rest;
    }

    /** How many of this item (isSimilar) the array could still take. */
    public static int room(ItemStack[] arr, ItemStack item) {
        int room = 0;
        for (ItemStack s : arr) {
            if (s == null || s.isEmpty()) room += item.getMaxStackSize();
            else if (s.isSimilar(item)) room += Math.max(0, s.getMaxStackSize() - s.getAmount());
        }
        return room;
    }

    /** Removes up to {@code amount} items similar to {@code like}; returns how many were removed. */
    public static int remove(ItemStack[] arr, ItemStack like, int amount) {
        int removed = 0;
        for (int i = 0; i < arr.length && removed < amount; i++) {
            ItemStack s = arr[i];
            if (s == null || s.isEmpty() || !s.isSimilar(like)) continue;
            int mv = Math.min(s.getAmount(), amount - removed);
            s.setAmount(s.getAmount() - mv);
            removed += mv;
            if (s.getAmount() <= 0) arr[i] = null;
        }
        return removed;
    }

    public static int count(ItemStack[] arr, ItemStack like) {
        int n = 0;
        for (ItemStack s : arr) if (s != null && !s.isEmpty() && s.isSimilar(like)) n += s.getAmount();
        return n;
    }
}
