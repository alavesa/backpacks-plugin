package fi.alavesa.backpacks;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds and recognises backpack / upgrade / GUI items. */
public final class Items {

    private final BackpacksPlugin plugin;

    public Items(BackpacksPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ backpacks

    /** A brand-new backpack of this tier with a fresh storage id. */
    public ItemStack backpack(Tier tier) { return backpack(tier, UUID.randomUUID()); }

    public ItemStack backpack(Tier tier, UUID id) {
        ItemStack it = new ItemStack(tier.material());
        it.editPersistentDataContainer(pdc -> {
            pdc.set(Keys.TIER, PersistentDataType.STRING, tier.id());
            pdc.set(Keys.ID, PersistentDataType.STRING, id.toString());
        });
        decorateBackpack(it, tier, null);
        return it;
    }

    /** (Re)applies name, model, equippable, lore to an existing backpack item. */
    public void decorateBackpack(ItemStack it, Tier tier, List<String> upgradeNames) {
        Settings s = plugin.settings();
        TextColor color = TextColor.fromHexString(tier.color());
        if (color == null) color = NamedTextColor.WHITE;
        it.setData(DataComponentTypes.ITEM_NAME, Component.text(tier.name(), color).decoration(TextDecoration.ITALIC, false));
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(tier.itemModel(s.packNamespace)));
        it.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.CHEST)
            .swappable(false).dispensable(false).damageOnHurt(false).equipOnInteract(false).build());
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Storage: " + tier.slots() + " slots  |  Upgrades: " + tier.upgradeSlots()));
        if (upgradeNames != null && !upgradeNames.isEmpty()) lore.add(gray("Installed: " + String.join(", ", upgradeNames)));
        lore.add(Component.empty());
        lore.add(dark("Right-click: open   |   Sneak + right-click air: upgrades"));
        lore.add(dark("Sneak + right-click block: place   |   Wear in the chest slot"));
        lore.add(dark("In inventory: right-click opens, shift + right-click upgrades"));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }

    public static boolean isBackpack(ItemStack it) {
        return it != null && !it.isEmpty() && it.getPersistentDataContainer().has(Keys.TIER, PersistentDataType.STRING);
    }

    public Tier tierOf(ItemStack it) {
        if (!isBackpack(it)) return null;
        return plugin.settings().tier(it.getPersistentDataContainer().get(Keys.TIER, PersistentDataType.STRING));
    }

    public static String tierIdOf(ItemStack it) {
        return isBackpack(it) ? it.getPersistentDataContainer().get(Keys.TIER, PersistentDataType.STRING) : null;
    }

    /** The storage id, or null if the item has none yet (see {@link #ensureId}). */
    public static UUID idOf(ItemStack it) {
        if (!isBackpack(it)) return null;
        String s = it.getPersistentDataContainer().get(Keys.ID, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** Gives an id-less backpack a storage id (in place). */
    public static UUID ensureId(ItemStack it) {
        UUID id = idOf(it);
        if (id != null) return id;
        UUID n = UUID.randomUUID();
        it.editPersistentDataContainer(pdc -> pdc.set(Keys.ID, PersistentDataType.STRING, n.toString()));
        return n;
    }

    /** Re-tiers a backpack item in place, keeping its id. */
    public void setTier(ItemStack it, Tier tier) {
        UUID id = ensureId(it);
        if (it.getType() != tier.material()) it = it.withType(tier.material());
        it.editPersistentDataContainer(pdc -> {
            pdc.set(Keys.TIER, PersistentDataType.STRING, tier.id());
            pdc.set(Keys.ID, PersistentDataType.STRING, id.toString());
        });
        decorateBackpack(it, tier, null);
    }

    // ------------------------------------------------------------------ upgrades

    public ItemStack upgrade(UpgradeDef def) {
        ItemStack it = new ItemStack(def.material());
        it.editPersistentDataContainer(pdc -> pdc.set(Keys.UPGRADE, PersistentDataType.STRING, def.id()));
        decorateUpgrade(it, def);
        return it;
    }

    public ItemStack upgradeBase() {
        ItemStack it = new ItemStack(Material.LEATHER);
        it.editPersistentDataContainer(pdc -> pdc.set(Keys.UPGRADE, PersistentDataType.STRING, "upgrade_base"));
        it.setData(DataComponentTypes.ITEM_NAME, Component.text("Upgrade Base", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(gray("Crafting ingredient for backpack upgrades."))));
        return it;
    }

    public void decorateUpgrade(ItemStack it, UpgradeDef def) {
        it.setData(DataComponentTypes.ITEM_NAME, Component.text(def.name(), def.advanced() ? NamedTextColor.AQUA : NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        if (def.model() != null && !def.model().isBlank()) {
            try { it.setData(DataComponentTypes.ITEM_MODEL, Key.key(def.model())); } catch (Exception ignored) { }
        }
        it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE).build());
        List<Component> lore = new ArrayList<>();
        lore.add(gray(def.description()));
        if (def.hasFilter()) {
            int n = 0;
            for (ItemStack f : filterOf(it)) if (f != null && !f.isEmpty()) n++;
            lore.add(gray("Filter: " + def.filterSlots() + " slots, " + filterMode(it).label() + (n > 0 ? ", " + n + " set" : "")));
        }
        lore.add(Component.empty());
        lore.add(dark("Put it in a backpack's upgrade slot."));
        if (def.hasFilter()) lore.add(dark("Right-click it there to set the filter."));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }

    public static boolean isUpgradeItem(ItemStack it) {
        if (it == null || it.isEmpty()) return false;
        String u = it.getPersistentDataContainer().get(Keys.UPGRADE, PersistentDataType.STRING);
        return u != null && !u.equals("upgrade_base");
    }

    public static String upgradeIdOf(ItemStack it) {
        if (it == null || it.isEmpty()) return null;
        return it.getPersistentDataContainer().get(Keys.UPGRADE, PersistentDataType.STRING);
    }

    public UpgradeDef upgradeOf(ItemStack it) {
        String id = upgradeIdOf(it);
        return id == null ? null : plugin.settings().upgrade(id);
    }

    /** Custom item key used by recipes: "backpacks:<tier>" / "backpacks:<upgrade>" / "backpacks:upgrade_base", or null. */
    public static String customKey(ItemStack it) {
        if (it == null || it.isEmpty()) return null;
        io.papermc.paper.persistence.PersistentDataContainerView pdc = it.getPersistentDataContainer();
        String t = pdc.get(Keys.TIER, PersistentDataType.STRING);
        if (t != null) return Keys.NS + ":" + t;
        String u = pdc.get(Keys.UPGRADE, PersistentDataType.STRING);
        return u == null ? null : Keys.NS + ":" + u;
    }

    /** Builds the custom item for a recipe key ("backpacks:leather", "backpacks:pickup", "backpacks:upgrade_base") or null. */
    public ItemStack custom(String key) {
        if (key == null) return null;
        String id = key.toLowerCase(Locale.ROOT);
        if (id.startsWith(Keys.NS + ":")) id = id.substring(Keys.NS.length() + 1);
        if (id.equals("upgrade_base")) return upgradeBase();
        Tier t = plugin.settings().tier(id);
        if (t != null) return backpack(t);
        UpgradeDef u = plugin.settings().upgrade(id);
        return u == null ? null : upgrade(u);
    }

    // ------------------------------------------------------------------ filters (stored on the upgrade item)

    public enum FilterMode {
        WHITELIST("whitelist"), BLACKLIST("blacklist"), EXISTING("match existing");
        public final String label;
        FilterMode(String l) { label = l; }
        public String label() { return label; }
        public FilterMode next(boolean allowExisting) {
            return switch (this) { case WHITELIST -> BLACKLIST; case BLACKLIST -> allowExisting ? EXISTING : WHITELIST; default -> WHITELIST; };
        }
    }

    public static FilterMode filterMode(ItemStack upgrade) {
        String m = upgrade.getPersistentDataContainer().get(Keys.FILTER_MODE, PersistentDataType.STRING);
        if (m == null) return FilterMode.WHITELIST;
        try { return FilterMode.valueOf(m); } catch (IllegalArgumentException e) { return FilterMode.WHITELIST; }
    }

    public static void setFilterMode(ItemStack upgrade, FilterMode mode) {
        upgrade.editPersistentDataContainer(pdc -> pdc.set(Keys.FILTER_MODE, PersistentDataType.STRING, mode.name()));
    }

    public static List<ItemStack> filterOf(ItemStack upgrade) {
        byte[] b = upgrade.getPersistentDataContainer().get(Keys.FILTER, PersistentDataType.BYTE_ARRAY);
        List<ItemStack> out = new ArrayList<>();
        if (b == null || b.length == 0) return out;
        try {
            for (ItemStack s : ItemStack.deserializeItemsFromBytes(b)) if (s != null && !s.isEmpty()) out.add(s);
        } catch (Exception ignored) { }
        return out;
    }

    public void setFilter(ItemStack upgrade, List<ItemStack> filter) {
        List<ItemStack> clean = new ArrayList<>();
        for (ItemStack f : filter) if (f != null && !f.isEmpty()) { ItemStack c = f.clone(); c.setAmount(1); clean.add(c); }
        byte[] bytes = clean.isEmpty() ? new byte[0] : ItemStack.serializeItemsAsBytes(clean);
        upgrade.editPersistentDataContainer(pdc -> pdc.set(Keys.FILTER, PersistentDataType.BYTE_ARRAY, bytes));
        UpgradeDef d = upgradeOf(upgrade);
        if (d != null) decorateUpgrade(upgrade, d);
    }

    /**
     * Does this item pass the upgrade's filter? Empty whitelist / blacklist = everything passes.
     * {@code existing} = items present in the target (for EXISTING mode); null means EXISTING acts like whitelist.
     */
    public static boolean passes(ItemStack upgrade, ItemStack item, List<ItemStack> existing) {
        FilterMode mode = filterMode(upgrade);
        if (mode == FilterMode.EXISTING) {
            if (existing == null) return true;
            for (ItemStack e : existing) if (e != null && !e.isEmpty() && e.isSimilar(item)) return true;
            return false;
        }
        List<ItemStack> filter = filterOf(upgrade);
        if (filter.isEmpty()) return true;
        boolean match = false;
        for (ItemStack f : filter) if (f.getType() == item.getType()) { match = true; break; }
        return mode == FilterMode.WHITELIST ? match : !match;
    }

    /** Only when the filter actually names something (used by the void upgrade: an empty filter voids nothing). */
    public static boolean passesStrict(ItemStack upgrade, ItemStack item) {
        if (filterOf(upgrade).isEmpty()) return false;
        return passes(upgrade, item, null);
    }

    // ------------------------------------------------------------------ GUI items

    public static ItemStack gui(Material m, String tag, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        it.editPersistentDataContainer(pdc -> pdc.set(Keys.GUI, PersistentDataType.STRING, tag));
        it.setData(DataComponentTypes.ITEM_NAME, name.decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) it.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(name.equals(Component.empty())).build());
        return it;
    }

    public static String guiTag(ItemStack it) {
        return it == null || it.isEmpty() ? null : it.getPersistentDataContainer().get(Keys.GUI, PersistentDataType.STRING);
    }

    public static boolean isGui(ItemStack it) { return guiTag(it) != null; }

    public static Component gray(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }
    public static Component dark(String s) { return Component.text(s, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false); }
}
