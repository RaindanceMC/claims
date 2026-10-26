package de.raindancer.modules.claims.rules;

import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.modules.claims.WaystoneSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimWaystone;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Who may travel between a claim's waystones, how many it may have, and where one may stand.
 *
 * <h2>Both ends</h2>
 * A waystone is a door that ignores walls. So a trip needs the traveller to be allowed at the stone they
 * leave from <em>and</em> the one they arrive at: a visitor at the public gate is offered nothing behind the
 * owner's walls, and somebody who reached the owners-only stone on foot does not thereby get a way out
 * through it.
 */
public final class WaystoneRule implements IClaimRule {

    private static final String PERK = "rainsclaims.waystones.";

    public enum Placement { OK, OUTSIDE, BLOCKED, TOO_CLOSE, FULL }

    @Override
    public String describe() {
        return "who may use a claim's waystones, and how many it may have";
    }

    /** Where this person stands on the claim; barred people stand nowhere. */
    public LandAudience standing(Claim claim, UUID who) {
        if (claim == null || who == null || claim.activeBan(who).isPresent()) {
            return null;
        }
        return claim.area().audienceOf(who);
    }

    /**
     * Whether {@code who} may go from {@code from} to {@code to}.
     *
     * @param from null for travelling from a menu rather than from a stone; then only the destination counts
     */
    public boolean mayUse(Claim claim, UUID who, ClaimWaystone from, ClaimWaystone to) {
        LandAudience standing = standing(claim, who);
        if (standing == null || to == null || (from != null && from.id().equals(to.id()))) {
            return false;
        }
        return to.admits(standing) && (from == null || from.admits(standing));
    }

    /** Every stone {@code who} may go to from {@code from}, by name. */
    public List<ClaimWaystone> destinations(Claim claim, UUID who, ClaimWaystone from) {
        return claim.waystones().all().stream()
                .filter(to -> mayUse(claim, who, from, to))
                .sorted(Comparator.comparing(stone -> stone.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * How many stones a claim may have.
     *
     * @param perk   the highest {@code rainsclaims.waystones.<n>} among its owners, -1 for none
     * @param bought how many extra its primary owner has bought
     */
    public int limit(WaystoneSettings settings, long areaBlocks, int perk, int bought) {
        long total = Math.max(settings.perClaim(), perk);
        if (perk == Integer.MAX_VALUE) {
            total = Integer.MAX_VALUE;
        } else {
            if (settings.blocksPerExtra() > 0) {
                total += Math.max(0, areaBlocks) / settings.blocksPerExtra();
            }
            total += Math.max(0, bought);
        }
        if (settings.mostPerClaim() > 0) {
            total = Math.min(total, settings.mostPerClaim());
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** The highest waystone count granted by permission nodes, {@link Integer#MAX_VALUE} for unlimited, -1 for none. */
    public static int perkFrom(Iterable<String> grantedNodes) {
        int highest = -1;
        for (String raw : grantedNodes) {
            String node = raw.toLowerCase(Locale.ROOT);
            if (!node.startsWith(PERK)) {
                continue;
            }
            String rest = node.substring(PERK.length());
            if (rest.equals("unlimited")) {
                return Integer.MAX_VALUE;
            }
            try {
                highest = Math.max(highest, Integer.parseInt(rest));
            } catch (NumberFormatException notANumber) {
                // somebody's typo, not a limit
            }
        }
        return highest;
    }

    /**
     * Whether a stone may go here. Rights and the feature switch are the caller's; this is the ground.
     *
     * @param roomAbove whether the block above is free — a stone is two tall
     */
    public Placement placement(WaystoneSettings settings, Claim claim, int x, int y, int z,
                               boolean roomAbove, int limit) {
        if (!claim.shape().containsBlock(x, y, z) || !claim.shape().containsBlock(x, y + 1, z)) {
            return Placement.OUTSIDE;
        }
        if (!roomAbove) {
            return Placement.BLOCKED;
        }
        for (ClaimWaystone other : claim.waystones().all()) {
            if (other.distanceTo(x, y, z) < Math.max(1, settings.minDistance())) {
                return Placement.TOO_CLOSE;
            }
        }
        if (claim.waystones().size() >= limit) {
            return Placement.FULL;
        }
        return Placement.OK;
    }
}
