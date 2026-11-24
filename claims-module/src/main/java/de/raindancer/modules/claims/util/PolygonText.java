package de.raindancer.modules.claims.util;

import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An outline written out as text: {@code x,z;x,z;…} on one line, or a file with one corner per line
 * ({@code x,z} or {@code x z}, blank lines and {@code #} comments skipped).
 */
public final class PolygonText {

    private PolygonText() {
    }

    /** The corners read, or {@code problem} saying why there is no usable outline. */
    public record Outline(List<ClaimPoint> points, String problem) {

        static Outline refused(String problem) {
            return new Outline(List.of(), problem);
        }
    }

    public static Outline parse(String text) {
        List<ClaimPoint> points = new ArrayList<>();
        int number = 0;
        for (String line : (text == null ? "" : text).split("\\R")) {
            int comment = line.indexOf('#');
            String content = comment >= 0 ? line.substring(0, comment) : line;
            for (String entry : content.split(";")) {
                String corner = entry.trim();
                if (corner.isEmpty()) {
                    continue;
                }
                number++;
                Optional<ClaimPoint> point = corner(corner);
                if (point.isEmpty()) {
                    return Outline.refused("corner " + number + " (" + corner + ") is not two whole numbers x,z");
                }
                ClaimPoint read = point.get();
                if (points.isEmpty() || !points.get(points.size() - 1).equals(read)) {
                    points.add(read);
                }
            }
        }
        if (points.size() > 1 && points.get(0).equals(points.get(points.size() - 1))) {
            points.remove(points.size() - 1);
        }
        if (points.size() < 3) {
            return Outline.refused("an outline needs at least 3 corners, got " + points.size());
        }
        Optional<ClaimPoint> crossing = ClaimShape.firstCrossing(points);
        if (crossing.isPresent()) {
            return Outline.refused("the outline crosses itself near " + crossing.get().x() + "," + crossing.get().z());
        }
        return new Outline(List.copyOf(points), null);
    }

    private static Optional<ClaimPoint> corner(String text) {
        String[] parts = text.split("[,\\s]+");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ClaimPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        } catch (NumberFormatException notWhole) {
            return Optional.empty();
        }
    }
}
