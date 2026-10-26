package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimWaystone;
import de.raindancer.modules.claims.selection.ClaimsPack;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/**
 * A waystone somebody clicked: the other stones of this claim they may go to, and — for whoever manages the
 * claim's stones — this one's name, who may use it, and taking it down.
 *
 * <p>Only stones this person may reach are listed. One they may not use is left out rather than shown
 * greyed: a list of the owner's private rooms is itself something a visitor should not be handed.
 */
public final class WaystoneMenu extends PaginatedMenu<ClaimWaystone> implements IClaimScreen {

    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final ClaimServices services;
    private final Claim claim;
    private final ClaimWaystone stone;

    public WaystoneMenu(ClaimServices services, Player viewer, Claim claim, ClaimWaystone stone, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
        this.stone = stone;
    }

    private ClaimWaystone current() {
        return claim.waystones().byId(stone.id()).orElse(stone);
    }

    @Override
    protected Component title() {
        return Component.text(current().name());
    }

    @Override
    protected List<ClaimWaystone> entries() {
        return services.waystones().rule().destinations(claim, viewer.getUniqueId(), current());
    }

    @Override
    protected ItemStack icon(ClaimWaystone to) {
        ItemStack icon = Icons.of(Material.LODESTONE, "<gold>" + to.name(),
                "<gray>" + distance(to) + " blocks away, at " + to.x() + " " + to.y() + " " + to.z(),
                "<dark_gray>" + who(to.audience()),
                "",
                "<yellow>Click<gray> to go there");
        icon.editMeta(meta -> meta.setItemModel(
                new NamespacedKey(ClaimsPack.WAYSTONE.namespace(), ClaimsPack.WAYSTONE.value())));
        return icon;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nowhere to go from here",
                "<gray>No other waystone in " + claim.name(),
                "<gray>is open to you.");
    }

    @Override
    protected void onClick(ClaimWaystone to, InventoryClickEvent event) {
        viewer.closeInventory();
        services.waystones().travel(viewer, claim, current(), to);
    }

    @Override
    protected void decorate() {
        super.decorate();
        if (!services.waystones().mayManage(claim, viewer)) {
            return;
        }
        ClaimWaystone here = current();
        toolbar(2, Icons.of(Material.NAME_TAG, "<white>Rename",
                        "<gray>Called <white>" + here.name() + "<gray> now.",
                        "<yellow>Click<gray> and type its new name"),
                click -> askForName());

        toolbar(4, Icons.of(here.audience().icon(), "<white>Who may use it",
                        "<gray>" + who(here.audience()) + ".",
                        "<dark_gray>Owners, then trusted, then everybody on the claim.",
                        "<yellow>Click<gray> to change"),
                click -> {
                    LandAudience next = switch (current().audience()) {
                        case OWNER -> LandAudience.TRUSTED;
                        case TRUSTED -> LandAudience.VISITOR;
                        case VISITOR -> LandAudience.OWNER;
                    };
                    services.waystones().openTo(viewer, claim, current(), next);
                    refresh();
                });

        toolbar(6, Icons.of(Material.IRON_PICKAXE, "<white>Take it down",
                        "<gray>You get the waystone back.",
                        "<dark_gray>asks first"),
                click -> new ConfirmScreen(services, viewer, claim, this,
                        "<red>Take " + current().name() + " down?",
                        List.of("<gray>Nobody can travel here any more.", "<gray>You get the waystone back."),
                        () -> {
                            services.waystones().takeDown(viewer, claim, current());
                            viewer.closeInventory();
                        }).open());
    }

    private void askForName() {
        viewer.closeInventory();
        services.messages().send(viewer, "waystone.ask-name", "name", current().name());
        boolean asked = services.prompts().ask(viewer.getUniqueId(), "Claims", TO_ANSWER,
                typed -> services.waystones().rename(viewer, claim, current(), typed),
                () -> services.messages().send(viewer, "waystone.rename-aborted"));
        if (!asked) {
            services.messages().send(viewer, "selection.already-being-asked");
        }
    }

    private int distance(ClaimWaystone to) {
        return (int) Math.round(to.distanceTo(viewer.getLocation().getBlockX(), viewer.getLocation().getBlockY(),
                viewer.getLocation().getBlockZ()));
    }

    static String who(LandAudience audience) {
        return switch (audience) {
            case OWNER -> "Owners only";
            case TRUSTED -> "Owners and trusted";
            case VISITOR -> "Everybody on the claim";
        };
    }
}
