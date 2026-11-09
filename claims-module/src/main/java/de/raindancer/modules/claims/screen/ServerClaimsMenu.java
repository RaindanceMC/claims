package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.rules.ClaimAreaRule;
import de.raindancer.modules.claims.selection.Selection;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's own claims — spawn, a market square — and the door to making another.
 *
 * <p>A click opens the same staff page as the claim browser; making one asks for its name in the game's dialog
 * and then hands out the marking tool, the full height of the world.
 */
public final class ServerClaimsMenu extends PaginatedMenu<Claim> implements IClaimScreen {

    private final ClaimServices services;

    public ServerClaimsMenu(ClaimServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Server claims");
    }

    @Override
    public String breadcrumb() {
        return "the server's claims";
    }

    @Override
    protected List<Claim> entries() {
        return services.claims().serverClaims();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.STRUCTURE_VOID, "<gray>The server holds no land yet",
                "<dark_gray>make one with the button below");
    }

    @Override
    protected ItemStack icon(Claim claim) {
        ItemStack icon = claim.iconOr(true);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + claim.shape().areaBlocks() + " blocks in <white>" + claim.worldName());
        lore.add("<dark_gray>x " + claim.shape().minX() + " to " + claim.shape().maxX()
                + ", z " + claim.shape().minZ() + " to " + claim.shape().maxZ());
        lore.add("<dark_gray>" + claim.members().size() + " trusted by name");
        lore.add("<dark_gray>roles: " + ClaimAreaRule.rolePermission(claim));
        lore.add("");
        lore.add("<yellow>click <dark_gray>manage it");
        lore.add("<yellow>right click <dark_gray>show the outline");
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Icons.name("<white>" + claim.name()));
            meta.lore(lore.stream().map(Icons::loreLine).toList());
            icon.setItemMeta(meta);
        }
        return icon;
    }

    @Override
    protected void onClick(Claim claim, InventoryClickEvent event) {
        if (!services.rights().isServerClaimStaff(viewer)) {
            services.messages().send(viewer, "error.no-permission");
            return;
        }
        if (event.isRightClick()) {
            viewer.closeInventory();
            services.visualizer().showClaim(viewer, claim, services.config().visualDurationSeconds());
            return;
        }
        new AdminClaimBrowserMenu.AdminClaimMenu(services, viewer, this, claim).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.BEACON, "<green>Claim land for the server",
                        "<gray>Name it, then mark it out with the tool.",
                        "<gray>Fully protected, the full height of the world;",
                        "<gray>refuses to overlap players' claims.",
                        "<dark_gray>/serverclaim create <name> force overlaps anyway"),
                event -> askForName());
    }

    private void askForName() {
        de.raindancer.core.ui.prompt.AnvilInput.open(viewer, "Name the server's claim", "Spawn",
                de.raindancer.core.ui.prompt.Parsers.text(48),
                typed -> {
                    if (!services.claimService().isValidName(typed)) {
                        services.messages().send(viewer, "error.name-invalid");
                        open();
                    } else if (services.claims().serverClaim(typed).isPresent()) {
                        services.messages().send(viewer, "server.name-taken", "claim", typed);
                        open();
                    } else {
                        viewer.closeInventory();
                        services.selectionFlow().beginServer(viewer, Selection.Purpose.SERVER_CLAIM, typed,
                                null, false);
                        services.messages().send(viewer, "server.mark-out", "claim", typed);
                    }
                },
                this::open);
    }
}
