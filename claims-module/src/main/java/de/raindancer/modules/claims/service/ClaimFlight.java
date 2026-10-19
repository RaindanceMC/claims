package de.raindancer.modules.claims.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.movement.Flight;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.rules.FeatureRules;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The flight perk: a lease on Core's {@link Flight} while somebody stands in a claim that lets them fly.
 *
 * <p>Through Core rather than {@code setAllowFlight} so walking out of a claim never ends a {@code /fly} the
 * player had anyway, and so a landing after flight ends mid-air is soft. Updated on every border crossing — a
 * player flying out must not wait for the next tick to start falling, nor fly on outside — and on the ambience
 * tick as a safety net for switches flipped while they stand still.
 */
public final class ClaimFlight implements IClaimService {

    /** This module's lease name on Core's flight. */
    public static final String SOURCE = "claims";

    private final Flight flight;
    private final FeatureRules features;
    private final Land protection;

    public ClaimFlight(Flight flight, FeatureRules features, Land protection) {
        this.flight = flight;
        this.features = features;
        this.protection = protection;
    }

    @Override
    public void settings(ClaimSettings settings) {
        // Nothing read from the file: the policy lives in FeatureRules.
    }

    @Override
    public String describe() {
        return "flight inside claims that offer it";
    }

    /** On the player's own thread. {@code claim} is where they now count as standing, or null. */
    public void update(Player player, Claim claim) {
        boolean wanted = claim != null
                && features.appliesTo(claim, ClaimFeature.FLIGHT, player)
                && protection.has(claim.area(), player, LandAction.ENTER);
        if (wanted) {
            flight.grant(player, SOURCE);
        } else if (flight.holds(player.getUniqueId(), SOURCE)) {
            flight.revoke(player, SOURCE);
        }
    }

    /** The module is stopping: nobody keeps flight it can no longer take back. */
    public void releaseAll(Plugin plugin, Iterable<? extends Player> online) {
        for (Player player : online) {
            if (flight.holds(player.getUniqueId(), SOURCE)) {
                Scheduling.onOwner(plugin, player, () -> flight.revoke(player, SOURCE));
            }
        }
    }
}
