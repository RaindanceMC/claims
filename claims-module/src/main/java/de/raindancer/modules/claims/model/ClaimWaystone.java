package de.raindancer.modules.claims.model;

import de.raindancer.core.world.protection.LandAudience;

import java.util.UUID;

/**
 * One waystone: a point inside a claim that the claim's other waystones can send people to.
 *
 * <p>It stands two blocks tall from {@code (x, y, z)} up. {@code yaw} is the way it faces — towards whoever
 * put it down — and where people arriving are set down, on the block in front of it.
 *
 * @param audience the lowest standing that may use it: {@link LandAudience#VISITOR} is everybody on the
 *                 claim, {@link LandAudience#OWNER} its owners only
 */
public record ClaimWaystone(UUID id, String name, int x, int y, int z, float yaw, LandAudience audience) {

    public ClaimWaystone {
        audience = audience == null ? LandAudience.TRUSTED : audience;
    }

    /** Where somebody arriving stands, and which way they look. */
    public record Arrival(double x, double y, double z, float yaw) {
    }

    public static ClaimWaystone at(String name, int x, int y, int z, float yaw, LandAudience audience) {
        return new ClaimWaystone(UUID.randomUUID(), name, x, y, z, yaw, audience);
    }

    public ClaimWaystone named(String newName) {
        return new ClaimWaystone(id, newName, x, y, z, yaw, audience);
    }

    public ClaimWaystone openTo(LandAudience newAudience) {
        return new ClaimWaystone(id, name, x, y, z, yaw, newAudience);
    }

    /** Whether this block is the stone or the block above it. */
    public boolean occupies(int bx, int by, int bz) {
        return bx == x && bz == z && (by == y || by == y + 1);
    }

    /**
     * Whether somebody with this standing on the claim may use it.
     *
     * <p>{@link LandAudience} runs owner, trusted, visitor — so a stone open to the trusted is open to
     * the owners as well, and one open to visitors to everybody.
     */
    public boolean admits(LandAudience standing) {
        return standing != null && standing.ordinal() <= audience.ordinal();
    }

    /** The block in front of it, rounded to the nearest of the four directions. */
    public Arrival arrival() {
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        int dx = switch (quarter) {
            case 1 -> -1;   // west
            case 3 -> 1;    // east
            default -> 0;
        };
        int dz = switch (quarter) {
            case 0 -> 1;    // south
            case 2 -> -1;   // north
            default -> 0;
        };
        float facing = switch (quarter) {
            case 1 -> 90f;
            case 2 -> 180f;
            case 3 -> -90f;
            default -> 0f;
        };
        return new Arrival(x + dx + 0.5, y, z + dz + 0.5, facing);
    }

    /** Distance along the ground, for keeping two stones from standing shoulder to shoulder. */
    public double distanceTo(int bx, int by, int bz) {
        double dx = bx - x;
        double dy = by - y;
        double dz = bz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
