package de.raindancer.modules.claims.rules;

import de.raindancer.modules.claims.model.Claim;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** What the claims' screens count, rank and colour. */
public final class ScreenLineRule implements IClaimRule {

    /** What somebody holds: their claims, counted to the first owner, and the ground under them. */
    public record Holding(UUID owner, int claims, long blocks) {
    }

    public List<Claim> biggest(Collection<Claim> claims, int limit) {
        return claims.stream()
                .sorted(Comparator.comparingLong((Claim claim) -> claim.shape().areaBlocks()).reversed())
                .limit(limit).toList();
    }

    public List<Holding> landowners(Collection<Claim> claims, int limit) {
        Map<UUID, long[]> held = new LinkedHashMap<>();
        for (Claim claim : claims) {
            UUID owner = claim.primaryOwner();
            if (owner == null) {
                continue;
            }
            long[] total = held.computeIfAbsent(owner, key -> new long[2]);
            total[0]++;
            total[1] += claim.shape().areaBlocks();
        }
        return held.entrySet().stream()
                .map(entry -> new Holding(entry.getKey(), (int) entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(Holding::blocks).reversed())
                .limit(limit).toList();
    }

    public long blocks(Collection<Claim> claims) {
        return claims.stream().mapToLong(claim -> claim.shape().areaBlocks()).sum();
    }

    public int trustedPeople(UUID owner, Collection<Claim> claims) {
        Set<UUID> people = new HashSet<>();
        claims.forEach(claim -> people.addAll(claim.members().keySet()));
        people.remove(owner);
        return people.size();
    }

    public String place(int place) {
        return switch (place) {
            case 1 -> "<gold>";
            case 2 -> "<gray>";
            case 3 -> "<#cd7f32>";
            default -> "<white>";
        };
    }

    @Override
    public String describe() {
        return "what the claims' screens count, rank and colour";
    }
}
