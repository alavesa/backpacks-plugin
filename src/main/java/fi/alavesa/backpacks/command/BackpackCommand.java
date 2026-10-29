package fi.alavesa.backpacks.command;

import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Settings;
import fi.alavesa.backpacks.Tier;
import fi.alavesa.backpacks.UpgradeDef;
import fi.alavesa.backpacks.data.BackpackData;
import fi.alavesa.backpacks.model.Issue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /backpack ... */
public final class BackpackCommand implements TabExecutor {

    private final BackpacksPlugin plugin;

    public BackpackCommand(BackpacksPlugin plugin) { this.plugin = plugin; }

    private static final String[] TYPE_KEYS = {"name", "slots", "upgrade-slots", "model", "material", "color", "order"};

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) return help(s);
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> { return open(s, a); }
            case "upgrades" -> { return upgrades(s, a); }
            case "info" -> { return info(s); }
            case "models" -> { return models(s); }
        }
        if (!s.hasPermission("backpacks.admin")) { s.sendMessage(plugin.settings().msg("no-permission")); return true; }
        switch (sub) {
            case "give" -> { return give(s, a); }
            case "giveupgrade" -> { return giveUpgrade(s, a); }
            case "settier" -> { return setTier(s, a); }
            case "type" -> { return type(s, a); }
            case "pack" -> { return pack(s); }
            case "reload" -> {
                plugin.reload();
                s.sendMessage(ok("Reloaded config.yml and recipes.yml (" + plugin.settings().tiers.size() + " tiers, " + plugin.recipes().keys().size() + " recipes)."));
                for (String p : plugin.settings().problems) s.sendMessage(warn(p));
                return true;
            }
            case "list" -> { return typeList(s); }
            default -> { return help(s); }
        }
    }

    private boolean help(CommandSender s) {
        s.sendMessage(Component.text("Backpacks " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
        s.sendMessage(gray("/backpack open [player]        open the backpack you hold or wear"));
        s.sendMessage(gray("/backpack upgrades             open its upgrade screen"));
        s.sendMessage(gray("/backpack info                 details of the backpack in hand"));
        s.sendMessage(gray("/backpack models               models found in backpack_models/"));
        if (s.hasPermission("backpacks.admin")) {
            s.sendMessage(gray("/backpack give <player> <tier> [amount]"));
            s.sendMessage(gray("/backpack giveupgrade <player> <upgrade|upgrade_base> [amount]"));
            s.sendMessage(gray("/backpack settier <player> <tier>      re-tier the backpack in hand (keeps contents)"));
            s.sendMessage(gray("/backpack type create <id> <slots> <upgradeSlots> [model] [name...]"));
            s.sendMessage(gray("/backpack type edit <id> <name|slots|upgrade-slots|model|material|color|order> <value>"));
            s.sendMessage(gray("/backpack type remove <id>   |   /backpack type list"));
            s.sendMessage(gray("/backpack pack                 rebuild the resource pack from backpack_models/"));
            s.sendMessage(gray("/backpack reload"));
        }
        return true;
    }

    // ------------------------------------------------------------------ player

    private ItemStack heldOrWorn(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (Items.isBackpack(h)) return h;
        h = p.getInventory().getItemInOffHand();
        if (Items.isBackpack(h)) return h;
        h = p.getInventory().getChestplate();
        return Items.isBackpack(h) ? h : null;
    }

    private boolean open(CommandSender s, String[] a) {
        Player target;
        if (a.length >= 2) {
            if (!s.hasPermission("backpacks.admin")) { s.sendMessage(plugin.settings().msg("no-permission")); return true; }
            target = Bukkit.getPlayerExact(a[1]);
            if (target == null) { s.sendMessage(warn("Player not found.")); return true; }
        } else if (s instanceof Player p) target = p; else { s.sendMessage(warn("Players only.")); return true; }
        if (!(s instanceof Player viewer)) { s.sendMessage(warn("Players only.")); return true; }
        ItemStack bp = heldOrWorn(target);
        if (bp == null) { s.sendMessage(warn(target == viewer ? "Hold or wear a backpack first." : target.getName() + " holds no backpack.")); return true; }
        Tier tier = plugin.items().tierOf(bp);
        if (tier == null) { s.sendMessage(warn("Unknown tier '" + Items.tierIdOf(bp) + "' - check config.yml.")); return true; }
        plugin.manager().openStorage(viewer, Items.ensureId(bp), tier);
        return true;
    }

    private boolean upgrades(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage(warn("Players only.")); return true; }
        ItemStack bp = heldOrWorn(p);
        if (bp == null) { s.sendMessage(warn("Hold or wear a backpack first.")); return true; }
        Tier tier = plugin.items().tierOf(bp);
        if (tier == null) { s.sendMessage(warn("Unknown tier.")); return true; }
        plugin.manager().openUpgrades(p, Items.ensureId(bp), tier);
        return true;
    }

    private boolean info(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage(warn("Players only.")); return true; }
        ItemStack bp = heldOrWorn(p);
        if (bp == null) { s.sendMessage(warn("Hold or wear a backpack first.")); return true; }
        Tier tier = plugin.items().tierOf(bp);
        UUID id = Items.ensureId(bp);
        s.sendMessage(ok("Backpack " + id));
        s.sendMessage(gray("tier: " + Items.tierIdOf(bp) + (tier == null ? " (unknown!)" : "  slots: " + tier.slots() + "  upgrade slots: " + tier.upgradeSlots() + "  model: " + tier.itemModel(plugin.settings().packNamespace))));
        if (tier != null) {
            BackpackData d = plugin.manager().data(id, tier);
            int used = 0; for (ItemStack it : plugin.manager().contents(id, tier)) if (it != null && !it.isEmpty()) used++;
            s.sendMessage(gray("used slots: " + used + "/" + tier.slots() + "   upgrades: " + String.join(", ", plugin.manager().upgradeNames(id, tier)) + (d.dirty ? "  (unsaved)" : "")));
        }
        return true;
    }

    private boolean models(CommandSender s) {
        List<String> names = plugin.exporter().modelNames();
        s.sendMessage(ok("Models in backpack_models/: " + (names.isEmpty() ? "none" : String.join(", ", names))));
        List<String> exported = plugin.exporter().lastExported();
        s.sendMessage(gray("in the last pack build: " + (exported.isEmpty() ? "none" : String.join(", ", exported))));
        for (Tier t : plugin.settings().orderedTiers())
            if (!plugin.exporter().modelExists(t.model())) s.sendMessage(warn("tier " + t.id() + " wants model '" + t.model() + "' which is not in backpack_models/"));
        return true;
    }

    // ------------------------------------------------------------------ admin

    private boolean give(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(warn("/backpack give <player> <tier> [amount]")); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage(warn("Player not found.")); return true; }
        Tier tier = plugin.settings().tier(a[2]);
        if (tier == null) { s.sendMessage(warn("Unknown tier. Tiers: " + String.join(", ", plugin.settings().tiers.keySet()))); return true; }
        int n = a.length >= 4 ? parseInt(a[3], 1) : 1;
        for (int i = 0; i < Math.max(1, Math.min(36, n)); i++) {
            var left = t.getInventory().addItem(plugin.items().backpack(tier));
            for (ItemStack l : left.values()) t.getWorld().dropItemNaturally(t.getLocation(), l);
        }
        s.sendMessage(ok("Gave " + t.getName() + " " + n + " x " + tier.name() + "."));
        return true;
    }

    private boolean giveUpgrade(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(warn("/backpack giveupgrade <player> <upgrade|upgrade_base> [amount]")); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage(warn("Player not found.")); return true; }
        ItemStack it = plugin.items().custom(a[2]);
        if (it == null || Items.isBackpack(it)) { s.sendMessage(warn("Unknown upgrade. Upgrades: upgrade_base, " + String.join(", ", plugin.settings().upgrades.keySet()))); return true; }
        it.setAmount(Math.max(1, Math.min(64, a.length >= 4 ? parseInt(a[3], 1) : 1)));
        var left = t.getInventory().addItem(it);
        for (ItemStack l : left.values()) t.getWorld().dropItemNaturally(t.getLocation(), l);
        s.sendMessage(ok("Gave " + t.getName() + " " + it.getAmount() + " x " + a[2] + "."));
        return true;
    }

    private boolean setTier(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(warn("/backpack settier <player> <tier>")); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage(warn("Player not found.")); return true; }
        Tier tier = plugin.settings().tier(a[2]);
        if (tier == null) { s.sendMessage(warn("Unknown tier.")); return true; }
        ItemStack bp = t.getInventory().getItemInMainHand();
        if (!Items.isBackpack(bp)) { s.sendMessage(warn(t.getName() + " must hold the backpack in the main hand.")); return true; }
        UUID id = Items.ensureId(bp);
        ItemStack n = plugin.items().backpack(tier, id);
        t.getInventory().setItemInMainHand(n);
        BackpackData d = plugin.manager().data(id, tier);
        plugin.manager().storage().save(d);
        plugin.manager().refreshItems(t, id);
        s.sendMessage(ok("Backpack " + id + " is now a " + tier.name() + "."));
        return true;
    }

    private boolean type(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(warn("/backpack type create|edit|remove|list")); return true; }
        String op = a[1].toLowerCase(Locale.ROOT);
        var cfg = plugin.getConfig();
        switch (op) {
            case "list" -> { return typeList(s); }
            case "create" -> {
                if (a.length < 5) { s.sendMessage(warn("/backpack type create <id> <slots> <upgradeSlots> [model] [name...]")); return true; }
                String id = a[2].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                if (plugin.settings().tier(id) != null) { s.sendMessage(warn("Tier '" + id + "' already exists - use edit.")); return true; }
                int slots = parseInt(a[3], -1), ups = parseInt(a[4], -1);
                if (slots < 1 || slots > 54 || ups < 0 || ups > 18) { s.sendMessage(warn("slots 1..54, upgradeSlots 0..18.")); return true; }
                String model = a.length >= 6 ? a[5] : id + "_backpack";
                String name = a.length >= 7 ? String.join(" ", java.util.Arrays.copyOfRange(a, 6, a.length)) : Settings.prettify(id) + " Backpack";
                String base = "tiers." + id;
                cfg.set(base + ".name", name); cfg.set(base + ".slots", slots); cfg.set(base + ".upgrade-slots", ups);
                cfg.set(base + ".model", model); cfg.set(base + ".material", "PAPER"); cfg.set(base + ".color", "#FFFFFF");
                cfg.set(base + ".order", plugin.settings().tiers.size() + 1);
                plugin.saveConfig(); plugin.reload();
                s.sendMessage(ok("Created tier '" + id + "' (" + slots + " slots, " + ups + " upgrade slots, model " + model + ")."));
                if (!plugin.exporter().modelExists(model)) s.sendMessage(warn("No backpack_models/" + model + ".bbmodel yet - add it and run /backpack pack."));
                return true;
            }
            case "edit" -> {
                if (a.length < 5) { s.sendMessage(warn("/backpack type edit <id> <" + String.join("|", TYPE_KEYS) + "> <value>")); return true; }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (plugin.settings().tier(id) == null) { s.sendMessage(warn("Unknown tier '" + id + "'.")); return true; }
                String key = a[3].toLowerCase(Locale.ROOT);
                String value = String.join(" ", java.util.Arrays.copyOfRange(a, 4, a.length));
                Object v;
                switch (key) {
                    case "slots" -> { int n = parseInt(value, -1); if (n < 1 || n > 54) { s.sendMessage(warn("slots 1..54")); return true; } v = n; }
                    case "upgrade-slots" -> { int n = parseInt(value, -1); if (n < 0 || n > 18) { s.sendMessage(warn("upgrade-slots 0..18")); return true; } v = n; }
                    case "order" -> v = parseInt(value, 0);
                    case "material" -> { Material m = Material.matchMaterial(value); if (m == null || !m.isItem()) { s.sendMessage(warn("Unknown item.")); return true; } v = m.name(); }
                    case "name", "model", "color" -> v = value;
                    default -> { s.sendMessage(warn("Keys: " + String.join(", ", TYPE_KEYS))); return true; }
                }
                cfg.set("tiers." + id + "." + key, v);
                plugin.saveConfig(); plugin.reload();
                s.sendMessage(ok("Tier '" + id + "': " + key + " = " + v + ". Existing items update when next opened."));
                return true;
            }
            case "remove" -> {
                if (a.length < 3) { s.sendMessage(warn("/backpack type remove <id>")); return true; }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (plugin.settings().tier(id) == null) { s.sendMessage(warn("Unknown tier '" + id + "'.")); return true; }
                cfg.set("tiers." + id, null);
                plugin.saveConfig(); plugin.reload();
                s.sendMessage(ok("Removed tier '" + id + "'. Existing items of that tier cannot be opened until a tier with this id exists again."));
                return true;
            }
            default -> { s.sendMessage(warn("/backpack type create|edit|remove|list")); return true; }
        }
    }

    private boolean typeList(CommandSender s) {
        s.sendMessage(ok("Tiers:"));
        for (Tier t : plugin.settings().orderedTiers())
            s.sendMessage(gray("  " + t.id() + "  '" + t.name() + "'  slots " + t.slots() + "  upgrades " + t.upgradeSlots() + "  model " + t.model() + (plugin.exporter().modelExists(t.model()) ? "" : " (missing!)") + "  base " + t.material().name()));
        s.sendMessage(ok("Upgrades: upgrade_base, " + String.join(", ", plugin.settings().upgrades.keySet())));
        return true;
    }

    private boolean pack(CommandSender s) {
        if (plugin.exporter().isRunning()) { s.sendMessage(warn("A pack build is already running.")); return true; }
        s.sendMessage(ok("Building the resource pack..."));
        plugin.buildPack(r -> {
            if (r == null) { s.sendMessage(warn("Pack build failed - see console.")); return; }
            s.sendMessage(ok("Pack built: " + r.models().size() + " model(s), " + r.errors() + " error(s), " + r.warnings() + " warning(s) in " + r.millis() + " ms."));
            s.sendMessage(gray("plugins/backpacks/resourcepack/ (folder) and Backpacks-resourcepack.zip  sha1 " + r.sha1()));
            for (Issue i : r.issues()) s.sendMessage(i.isError() ? warn(i.model() + ": " + i.message()) : gray("warn " + i.model() + ": " + i.message()));
        });
        return true;
    }

    // ------------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("backpacks.admin");
        if (a.length == 1) {
            out.addAll(List.of("open", "upgrades", "info", "models", "help"));
            if (admin) out.addAll(List.of("give", "giveupgrade", "settier", "type", "pack", "reload", "list"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give", "giveupgrade", "settier", "open" -> { if (admin) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
                case "type" -> out.addAll(List.of("create", "edit", "remove", "list"));
                default -> { }
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give", "settier" -> out.addAll(plugin.settings().tiers.keySet());
                case "giveupgrade" -> { out.add("upgrade_base"); out.addAll(plugin.settings().upgrades.keySet()); }
                case "type" -> { if (!a[1].equalsIgnoreCase("create")) out.addAll(plugin.settings().tiers.keySet()); }
                default -> { }
            }
        } else if (a.length == 4 && a[0].equalsIgnoreCase("type") && a[1].equalsIgnoreCase("edit")) {
            out.addAll(List.of(TYPE_KEYS));
        }
        String pre = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }
    private static Component ok(String s) { return Component.text(s, NamedTextColor.GREEN); }
    private static Component warn(String s) { return Component.text(s, NamedTextColor.YELLOW); }
    private static Component gray(String s) { return Component.text(s, NamedTextColor.GRAY); }
}
