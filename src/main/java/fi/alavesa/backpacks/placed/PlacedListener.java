package fi.alavesa.backpacks.placed;

import fi.alavesa.backpacks.BackpacksPlugin;
import fi.alavesa.backpacks.Tier;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Clicks on placed backpacks, hopper / dropper automation, protection and chunk-load verification. */
public final class PlacedListener implements Listener {

    private final BackpacksPlugin plugin;
    private final PlacedManager placed;

    public PlacedListener(BackpacksPlugin plugin) { this.plugin = plugin; this.placed = plugin.placed(); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e instanceof PlayerInteractAtEntityEvent) return;
        if (!(e.getRightClicked() instanceof Interaction) && !(e.getRightClicked() instanceof ItemDisplay)) return;
        PlacedBackpack pb = placed.byEntity(e.getRightClicked());
        if (pb == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        var p = e.getPlayer();
        Tier tier = plugin.settings().tier(pb.tier);
        if (tier == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && hand.isEmpty()) { placed.pickupTo(p, pb); return; }
        plugin.manager().openStorage(p, pb.id, tier);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        PlacedBackpack pb = placed.byEntity(e.getAttacked());
        if (pb == null) return;
        e.setCancelled(true);
        if (!plugin.settings().placedBreakLeftClick) return;
        if (!e.getPlayer().hasPermission("backpacks.use")) return;
        placed.pickupTo(e.getPlayer(), pb);
    }

    /** Creative players can break the barrier: treat it as a pickup. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        PlacedBackpack pb = placed.at(e.getBlock());
        if (pb == null) return;
        e.setCancelled(true);
        placed.pickupTo(e.getPlayer(), pb);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (placed.at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (placed.at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> placed.at(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> placed.at(b) != null); }

    // ------------------------------------------------------------------ automation

    @EventHandler
    public void onHopperSearch(HopperInventorySearchEvent e) {
        if (!plugin.settings().placedAutomation || placed.isEmpty()) return;
        PlacedBackpack pb = placed.at(e.getSearchBlock());
        if (pb == null) return;
        Tier tier = plugin.settings().tier(pb.tier);
        if (tier == null) return;
        Inventory inv = plugin.manager().storageInventory(pb.id, tier);
        e.setInventory(inv);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockPreDispenseEvent e) {
        if (!plugin.settings().placedAutomation || placed.isEmpty()) return;
        Block b = e.getBlock();
        if (b.getType() != Material.DROPPER) return;
        if (!(b.getBlockData() instanceof Directional dir)) return;
        Block target = b.getRelative(dir.getFacing());
        PlacedBackpack pb = placed.at(target);
        if (pb == null) return;
        Tier tier = plugin.settings().tier(pb.tier);
        if (tier == null) return;
        e.setCancelled(true);
        if (!(b.getState() instanceof Container c)) return;
        ItemStack slot = c.getInventory().getItem(e.getSlot());
        if (slot == null || slot.isEmpty()) return;
        ItemStack one = slot.clone(); one.setAmount(1);
        if (!plugin.manager().canInsert(pb.id, tier, one)) return;
        ItemStack left = plugin.manager().insert(pb.id, tier, one);
        if (left != null) return;
        slot.setAmount(slot.getAmount() - 1);
        c.getInventory().setItem(e.getSlot(), slot.getAmount() <= 0 ? null : slot);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (placed.isEmpty()) return;
        placed.verify(e.getWorld(), e.getEntities());
    }
}
