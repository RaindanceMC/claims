package de.raindancer.modules.claims.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Waystones in the world: putting one down, clicking one, and their chunks coming and going.
 *
 * <p>Putting one down is a lodestone placement this listener always cancels — whether a stone is then built
 * is the service's decision, and the lodestone never reaches the world either way. Highest priority and
 * regardless of who cancelled first: claim protection refusing a stranger's block must not let the item fall
 * through to vanilla, and an owner's placement is decided by the stone's own rights, not the build flag.
 */
public final class WaystoneListener implements IClaimListener {

    private final ClaimServices services;

    public WaystoneListener(ClaimServices services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "waystones";
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (!services.waystones().isWaystone(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block target = event.getBlockPlaced();
        EquipmentSlot hand = event.getHand();
        // A tick later: a cancelled placement puts the old block back after every handler has run, which
        // would replace the barrier just set here with air again.
        Scheduling.regionLater(services.plugin(), target.getLocation(), 1L, () -> {
            ItemStack held = player.getInventory().getItem(hand);
            if (player.isOnline() && services.waystones().isWaystone(held)) {
                services.waystones().place(player, target, held);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.BARRIER) {
            return;
        }
        services.waystones().at(clicked).ifPresent(found -> {
            event.setCancelled(true);
            services.screens().waystone(event.getPlayer(), found.claim(), found.stone());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        services.waystones().chunkLoaded(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        services.waystones().chunkUnloaded(event.getChunk());
    }
}
