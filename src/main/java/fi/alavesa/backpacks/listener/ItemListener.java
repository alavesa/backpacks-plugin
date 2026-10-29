package fi.alavesa.backpacks.listener;

import fi.alavesa.backpacks.BackpackManager;
import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Items;
import fi.alavesa.backpacks.Tier;
import fi.alavesa.backpacks.UpgradeDef;
import fi.alavesa.backpacks.data.BackpackData;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Right-click with a backpack in hand: open / upgrades / place / deposit-restock. */
public final class ItemListener implements Listener {

    private final BackpacksPlugin plugin;
    private final BackpackManager mgr;

    public ItemListener(BackpacksPlugin plugin) { this.plugin = plugin; this.mgr = plugin.manager(); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() == null) return;
        ItemStack it = e.getItem();
        if (!Items.isBackpack(it)) return;
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        Player p = e.getPlayer();
        Tier tier = plugin.items().tierOf(it);
        if (tier == null) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND && Items.isBackpack(p.getInventory().getItemInMainHand())) return;
        Block block = e.getClickedBlock();

        if (!p.isSneaking()) {
            // let chests, doors, buttons... work normally when clicked with a backpack
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && block.getType().isInteractable() && e.useInteractedBlock() != org.bukkit.event.Event.Result.DENY) return;
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
            mgr.openStorage(p, it);
            return;
        }

        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (e.getAction() == Action.RIGHT_CLICK_AIR || block == null) {
            e.setCancelled(true);
            mgr.openUpgrades(p, Items.ensureId(it), tier);
            return;
        }
        e.setCancelled(true);
        UUID id = Items.ensureId(it);
        BackpackData d = mgr.data(id, tier);
        if (block.getState() instanceof Container c && (d.hasUpgrade(UpgradeDef.DEPOSIT) || d.hasUpgrade(UpgradeDef.RESTOCK))) {
            if (e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
            plugin.upgrades().depositRestock(p, id, tier, d, c);
            return;
        }
        if (!plugin.settings().placedEnabled) return;
        if (!p.hasPermission("backpacks.place")) { p.sendMessage(plugin.settings().msg("no-permission")); return; }
        if (e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        Block target = block.isReplaceable() ? block : block.getRelative(e.getBlockFace());
        if (!plugin.placed().canPlace(target)) { p.sendMessage(plugin.settings().msg("cannot-place")); return; }
        if (plugin.placed().place(p, target, it) == null) return;
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            it.setAmount(it.getAmount() - 1);
            if (e.getHand() == EquipmentSlot.HAND) p.getInventory().setItemInMainHand(it.getAmount() <= 0 ? null : it);
            else p.getInventory().setItemInOffHand(it.getAmount() <= 0 ? null : it);
        }
        p.getWorld().playSound(target.getLocation(), org.bukkit.Sound.BLOCK_WOOL_PLACE, 1f, 0.9f);
        p.sendMessage(plugin.settings().msg("placed"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (plugin.settings().recipesEnabled && plugin.settings().recipesAutoDiscover) plugin.recipes().discover(e.getPlayer());
    }
}
