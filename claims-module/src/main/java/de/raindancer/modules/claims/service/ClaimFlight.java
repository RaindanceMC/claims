package de.raindancer.modules.claims.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.movement.Flight;
import de.raindancer.core.world.movement.Footing;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.rules.FeatureRules;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The flight perk: a lease on Core's {@link Flight} while somebody stands in a claim that lets them fly.
 *
 * <p>Through Core rather than {@code setAllowFlight} so walking out of a claim never ends a {@code /fly} the
 * player had anyway, and so somebody dropped mid-air floats down on Slow Falling. Updated on every border crossing
 * and on the ambience tick as a safety net for switches flipped while they stand still.
 *
 * <p>Ending it in the air waits {@link #GRACE_SECONDS}, with a notice: overshooting a border by a block is the
 * common case, and dropping somebody for it is the thing that makes a perk feel like a trap. Back in a flying
 * claim within the grace, nothing happens at all. On the ground there is nothing to soften, so it ends at once.
 */
public final class ClaimFlight implements IClaimService {

    /** This module's lease name on Core's flight. */
    public static final String SOURCE = "claims";
    public static final int GRACE_SECONDS = 2;
    private static final long GRACE_TICKS = GRACE_SECONDS * 20L;

    private final Plugin plugin;
    private final Flight flight;
    private final FeatureRules features;
    private final Land protection;
    private final Messages messages;
    /** Players in their grace, each with the token of the timer that may end it — a later grace gets a new one. */
    private final Map<UUID, Object> ending = new ConcurrentHashMap<>();
    private volatile Function<Player, Claim> where = player -> null;

    public ClaimFlight(Plugin plugin, Flight flight, FeatureRules features, Land protection, Messages messages) {
        this.plugin = plugin;
        this.flight = flight;
        this.features = features;
        this.protection = protection;
        this.messages = messages;
    }

    /** Where a player counts as standing when their grace runs out. */
    public void where(Function<Player, Claim> where) {
        this.where = where;
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
        UUID id = player.getUniqueId();
        if (wanted(player, claim)) {
            ending.remove(id);
            flight.grant(player, SOURCE);
            return;
        }
        if (!flight.holds(id, SOURCE)) {
            ending.remove(id);
            return;
        }
        if (Footing.grounded(player)) {
            ending.remove(id);
            flight.revoke(player, SOURCE);
            return;
        }
        Object token = new Object();
        if (ending.putIfAbsent(id, token) != null) {
            return; // already counting down
        }
        player.sendActionBar(messages.get("flight.ending", "seconds", GRACE_SECONDS));
        Scheduling.entityLater(plugin, player, GRACE_TICKS, () -> {
            if (!ending.remove(id, token)) {
                return; // back in since, or a newer grace owns it
            }
            if (wanted(player, where.apply(player))) {
                flight.grant(player, SOURCE);
            } else {
                flight.revoke(player, SOURCE);
            }
        });
    }

    private boolean wanted(Player player, Claim claim) {
        return claim != null
                && features.appliesTo(claim, ClaimFeature.FLIGHT, player)
                && protection.has(claim.area(), player, LandAction.ENTER);
    }

    public void forget(UUID player) {
        ending.remove(player);
    }

    /** The module is stopping: nobody keeps flight it can no longer take back, and no grace is waited out. */
    public void releaseAll(Iterable<? extends Player> online) {
        ending.clear();
        for (Player player : online) {
            if (flight.holds(player.getUniqueId(), SOURCE)) {
                Scheduling.onOwner(plugin, player, () -> flight.revoke(player, SOURCE));
            }
        }
    }
}
