package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimWaystone;
import de.raindancer.modules.claims.selection.ClaimsPack;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A claim's waystones, from its own menu or {@code /claim waystones}.
 *
 * <p>Somebody standing in the claim can travel from here to any stone open to them. From outside it the list
 * is only a list: these are a claim's own paths, not warps from anywhere. Whoever manages the stones also
 * gets one to put down, and the owner a way to buy more.
 */
public final class WaystonesMenu extends PaginatedMenu<ClaimWaystone> implements IClaimScreen {

    private final ClaimServices services;
    private final Claim claim;

    public WaystonesMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Waystones · " + claim.name());
    }

    private boolean manages() {
        return services.waystones().mayManage(claim, viewer);
    }

    private boolean inside() {
        Location at = viewer.getLocation();
        return at.getWorld() != null && at.getWorld().getUID().equals(claim.worldId())
                && claim.shape().containsBlock(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    @Override
    protected List<ClaimWaystone> entries() {
        // Managers see every stone, so they can find the one they are about to change. Everybody else sees
        // only where they may go.
        if (manages()) {
            List<ClaimWaystone> all = new ArrayList<>(claim.waystones().all());
            all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return all;
        }
        return services.waystones().rule().destinations(claim, viewer.getUniqueId(), null);
    }

    @Override
    protected ItemStack icon(ClaimWaystone stone) {
        boolean mayGo = services.waystones().rule().mayUse(claim, viewer.getUniqueId(), null, stone);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>At " + stone.x() + " " + stone.y() + " " + stone.z());
        lore.add("<dark_gray>" + WaystoneMenu.who(stone.audience()));
        lore.add("");
        if (!inside()) {
            lore.add("<gray>Stand in " + claim.name() + " to travel.");
        } else if (mayGo) {
            lore.add("<yellow>Click<gray> to go there");
        } else {
            lore.add("<gray>Not open to you.");
        }
        if (manages()) {
            lore.add("<yellow>Right click<gray> to rename or change it");
        }
        ItemStack icon = Icons.of(Material.LODESTONE, "<gold>" + stone.name(), lore.toArray(String[]::new));
        icon.editMeta(meta -> meta.setItemModel(
                new NamespacedKey(ClaimsPack.WAYSTONE.namespace(), ClaimsPack.WAYSTONE.value())));
        return icon;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No waystones here yet",
                manages() ? "<gray>Take one from below and put it down." : "<gray>None open to you.");
    }

    @Override
    protected void onClick(ClaimWaystone stone, InventoryClickEvent event) {
        if (event.isRightClick() && manages()) {
            new WaystoneMenu(services, viewer, claim, stone, this).open();
            return;
        }
        if (!inside()) {
            services.messages().send(viewer, "waystone.not-here", "claim", claim.name());
            return;
        }
        viewer.closeInventory();
        services.waystones().travel(viewer, claim, null, stone);
    }

    @Override
    protected void decorate() {
        super.decorate();
        int limit = services.waystones().limitFor(claim);
        int placed = claim.waystones().size();
        boolean offered = services.waystones().offered();

        toolbar(2, offered && manages(), Icons.of(Material.LODESTONE, "<white>Take a waystone",
                        "<gray>" + placed + " of " + limit + " put down in this claim.",
                        "<gray>Put it down anywhere inside it.",
                        "<yellow>Click<gray> to take one"),
                offered ? "The owner's to change" : "Waystones are switched off on this server.",
                click -> {
                    services.waystones().give(viewer, claim);
                    refresh();
                });

        UUID me = viewer.getUniqueId();
        if (claim.isOwner(me)) {
            Optional<String> whyNot = services.waystones().whyNotBuy(me);
            toolbar(6, whyNot.isEmpty(), Icons.of(Material.EMERALD, "<white>Buy one more",
                            whyNot.isEmpty() ? "<gray>One more in every claim you own, for <white>"
                                    + services.waystones().describeNextPrice(me) + "<gray>."
                                    : "<gray>One more in every claim you own.",
                            "<gray>You have bought <white>" + services.waystones().bought(me) + "<gray> so far.",
                            "<yellow>Click<gray> to buy one <dark_gray>· asks first"),
                    whyNot.orElse(""),
                    click -> new ConfirmScreen(services, viewer, claim, this,
                            "<yellow>Buy a waystone for " + services.waystones().describeNextPrice(me) + "?",
                            List.of("<gray>One more in every claim you own, for good.", "<gray>Not refunded."),
                            () -> {
                                services.waystones().buy(viewer);
                                viewer.closeInventory();
                            }).open());
        }
    }
}
