package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;


/** What this claim is called, what it looks like in a list, and what it says to somebody arriving. */
public final class ClaimIdentityMenu extends ClaimScreen {


    public ClaimIdentityMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Name and icon");
    }

    @Override
    protected void render() {
        Claim claim = claim();
        boolean mayRename = may(ClaimAdminPermission.MANAGE_TITLES)
                && services().features().isOffered(ClaimFeature.CLAIM_RENAME);
        boolean mayIcon = may(ClaimAdminPermission.MANAGE_TITLES)
                && services().features().isOffered(ClaimFeature.CLAIM_ICON);

        band(MenuLayout.WHO, 2, mayRename,
                Icons.of(Material.NAME_TAG, "<green>Called <white>" + claim.name(),
                        "<gray>Type a new name in chat.",
                        "<dark_gray>names are unique per owner, not per server"),
                services().features().isOffered(ClaimFeature.CLAIM_RENAME)
                        ? "The owner's to change" : "The server has switched renaming off",
                click -> askForName());

        band(MenuLayout.WHO, 4, mayIcon,
                Icons.of(claim.iconMaterial(true), "<green>Shown as",
                        "<gray>What this claim looks like in a list.",
                        "<dark_gray>click, then click an item in your inventory"),
                services().features().isOffered(ClaimFeature.CLAIM_ICON)
                        ? "The owner's to change" : "The server has switched icons off",
                click -> new de.raindancer.core.ui.choose.ItemChooser(viewer, services().brand(), this,
                        "Claim icon",
                        chosen -> {
                            // Core's chooser rather than "hold the item you want": holding it means having
                            // it, so an icon you do not own was unreachable, and the failure mode was a
                            // message about your hand on a screen about your claim.
                            claim.icon(new org.bukkit.inventory.ItemStack(chosen));
                            services().claimService().saveAsync(claim);
                            tell("claim.icon-changed", "claim", claim.name());
                            new ClaimIdentityMenu(services(), viewer, claim, parent()).open();
                        }).open());

        band(MenuLayout.WHO, 6, may(ClaimAdminPermission.MANAGE_TITLES)
                        && services().features().isOffered(ClaimFeature.TITLES),
                Icons.of(Material.PAINTING, "<green>What arrivals see",
                        "<gray>The words across the screen on entering and leaving.",
                        "<dark_gray>" + (claim.titles().hasEnterTitle() ? "set" : "not set")),
                "The owner's to change",
                click -> new TitlesMenu(services(), viewer, claim, this).open());
    }

    /**
     * Asks for a new name in chat.
     *
     * <p>In the game's own dialog, so the player stays in the menus. The refusal cases — taken, invalid — come back
     * from the same validation the command uses, so a name refused here is refused there for the same reason.
     */
    private void askForName() {
        de.raindancer.core.ui.prompt.AnvilInput.open(viewer, "A new name for " + claim().name(), claim().name(),
                de.raindancer.core.ui.prompt.Parsers.text(48),
                typed -> {
                    if (!services().claimService().isValidName(typed)) {
                        tell("error.name-invalid");
                    } else if (!services().names().available(typed, claim().primaryOwner())) {
                        tell("error.name-taken");
                    } else {
                        String was = claim().name();
                        services().claims().rename(claim(), typed);
                        services().claimService().saveAsync(claim());
                        tell("claim.renamed", "old", was, "claim", typed);
                    }
                    open();
                },
                this::open);
    }
}
