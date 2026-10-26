package de.raindancer.modules.claims.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A claim's waystones, in the order they were put down.
 *
 * <p>Two invariants live here rather than in whichever screen changes them: names are unique within the claim
 * (ignoring case, because "Gate" and "gate" in one list is a list nobody can pick from), and no two stones
 * share a block. Changes are atomic per call, so two region threads cannot both slip a stone into one spot.
 */
public final class ClaimWaystones {

    private final List<ClaimWaystone> stones = new CopyOnWriteArrayList<>();

    public List<ClaimWaystone> all() {
        return List.copyOf(stones);
    }

    public int size() {
        return stones.size();
    }

    public boolean isEmpty() {
        return stones.isEmpty();
    }

    public Optional<ClaimWaystone> byId(UUID id) {
        return stones.stream().filter(stone -> stone.id().equals(id)).findFirst();
    }

    public Optional<ClaimWaystone> at(int x, int y, int z) {
        return stones.stream().filter(stone -> stone.occupies(x, y, z)).findFirst();
    }

    /** @return false when the name is taken or the blocks are already a stone's */
    public synchronized boolean add(ClaimWaystone stone) {
        if (stone == null || nameTaken(stone.name(), null) || clashes(stone, null)) {
            return false;
        }
        stones.add(stone);
        return true;
    }

    public synchronized boolean remove(UUID id) {
        return stones.removeIf(stone -> stone.id().equals(id));
    }

    /** Swaps in a changed copy of a stone already here. @return false when it is not here or the name is taken */
    public synchronized boolean replace(ClaimWaystone changed) {
        for (int i = 0; i < stones.size(); i++) {
            if (stones.get(i).id().equals(changed.id())) {
                if (nameTaken(changed.name(), changed.id()) || clashes(changed, changed.id())) {
                    return false;
                }
                stones.set(i, changed);
                return true;
            }
        }
        return false;
    }

    /** Whether another stone than {@code except} is already called this. */
    public boolean nameTaken(String name, UUID except) {
        String wanted = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return stones.stream().anyMatch(stone -> !stone.id().equals(except)
                && stone.name().strip().toLowerCase(Locale.ROOT).equals(wanted));
    }

    /** {@code base}, or the first of "base 2", "base 3"… nobody has taken. */
    public String freeName(String base) {
        if (!nameTaken(base, null)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + " " + n;
            if (!nameTaken(candidate, null)) {
                return candidate;
            }
        }
    }

    /** Puts back what was read from disk, skipping anything that breaks the invariants. */
    public synchronized void restore(List<ClaimWaystone> loaded) {
        stones.clear();
        for (ClaimWaystone stone : loaded) {
            add(stone);
        }
    }

    private boolean clashes(ClaimWaystone stone, UUID except) {
        return stones.stream().anyMatch(other -> !other.id().equals(except)
                && (other.occupies(stone.x(), stone.y(), stone.z())
                || other.occupies(stone.x(), stone.y() + 1, stone.z())));
    }
}
