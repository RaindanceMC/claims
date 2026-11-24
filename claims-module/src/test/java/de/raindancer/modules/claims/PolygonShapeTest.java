package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long, many-cornered shapes — a road as one claim — answered from a per-row index instead of walking every
 * edge. The index must say exactly what the edge walk says, column for column.
 */
@DisplayName("Polygon shapes with many corners")
class PolygonShapeTest {

    /** The edge walk the index replaces, kept here as the reference it must agree with. */
    private static boolean reference(List<ClaimPoint> v, int x, int z) {
        double px = x + 0.5D;
        double pz = z + 0.5D;
        boolean inside = false;
        int size = v.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            double xi = v.get(i).x() + 0.5D;
            double zi = v.get(i).z() + 0.5D;
            double xj = v.get(j).x() + 0.5D;
            double zj = v.get(j).z() + 0.5D;
            if (((zi > pz) != (zj > pz)) && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        if (inside) {
            return true;
        }
        for (int i = 0, j = size - 1; i < size; j = i++) {
            ClaimPoint a = v.get(j);
            ClaimPoint b = v.get(i);
            if (x < Math.min(a.x(), b.x()) || x > Math.max(a.x(), b.x())
                    || z < Math.min(a.z(), b.z()) || z > Math.max(a.z(), b.z())) {
                continue;
            }
            long cross = (long) (b.x() - a.x()) * (z - a.z()) - (long) (b.z() - a.z()) * (x - a.x());
            long slack = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
            if (Math.abs(cross) <= slack) {
                return true;
            }
        }
        return false;
    }

    /** A 9-wide road along a wobbly centre line, as an outline: down one side and back up the other. */
    static List<ClaimPoint> road(long seed, int length, int halfWidth) {
        Random random = new Random(seed);
        List<double[]> centre = new ArrayList<>();
        double x = random.nextInt(200) - 100;
        double z = random.nextInt(200) - 100;
        double heading = random.nextDouble() * Math.PI * 2;
        for (int step = 0; step <= length; step += 12) {
            centre.add(new double[]{x, z});
            heading += (random.nextDouble() - 0.5D) * 0.5D;
            x += Math.cos(heading) * 12;
            z += Math.sin(heading) * 12;
        }
        List<ClaimPoint> left = new ArrayList<>();
        List<ClaimPoint> right = new ArrayList<>();
        for (int at = 0; at < centre.size(); at++) {
            double[] here = centre.get(at);
            double[] next = centre.get(Math.min(at + 1, centre.size() - 1));
            double[] previous = centre.get(Math.max(at - 1, 0));
            double dx = next[0] - previous[0];
            double dz = next[1] - previous[1];
            double norm = Math.hypot(dx, dz);
            double nx = -dz / norm * halfWidth;
            double nz = dx / norm * halfWidth;
            left.add(new ClaimPoint((int) Math.round(here[0] + nx), (int) Math.round(here[1] + nz)));
            right.add(new ClaimPoint((int) Math.round(here[0] - nx), (int) Math.round(here[1] - nz)));
        }
        List<ClaimPoint> outline = new ArrayList<>(left);
        for (int at = right.size() - 1; at >= 0; at--) {
            outline.add(right.get(at));
        }
        return outline;
    }

    private static List<ClaimPoint> randomStar(long seed) {
        Random random = new Random(seed);
        int corners = 3 + random.nextInt(40);
        List<ClaimPoint> points = new ArrayList<>();
        for (int at = 0; at < corners; at++) {
            double angle = 2 * Math.PI * at / corners;
            int radius = 2 + random.nextInt(40);
            points.add(new ClaimPoint((int) Math.round(Math.cos(angle) * radius),
                    (int) Math.round(Math.sin(angle) * radius)));
        }
        return points;
    }

    private static void agreesWithTheEdgeWalk(List<ClaimPoint> outline) {
        ClaimShape shape = new ClaimShape(outline, 0, 10);
        for (int x = shape.minX() - 2; x <= shape.maxX() + 2; x++) {
            for (int z = shape.minZ() - 2; z <= shape.maxZ() + 2; z++) {
                assertThat(shape.containsColumn(x, z))
                        .as("column %d %d of %s", x, z, outline)
                        .isEqualTo(reference(outline, x, z));
            }
        }
    }

    @Test
    @DisplayName("agrees with the edge walk on every column of random stars, including degenerate ones")
    void starsAgree() {
        for (long seed = 0; seed < 300; seed++) {
            agreesWithTheEdgeWalk(randomStar(seed));
        }
    }

    @Test
    @DisplayName("agrees with the edge walk on every column of winding roads")
    void roadsAgree() {
        for (long seed = 0; seed < 12; seed++) {
            agreesWithTheEdgeWalk(road(seed, 600, 6));
        }
    }

    @Test
    @DisplayName("the round and L-shaped claims players already have read the same as before")
    void existingShapesAgree() {
        agreesWithTheEdgeWalk(ClaimShape.circle(5, -7, 37, 0, 10).vertices());
        agreesWithTheEdgeWalk(List.of(new ClaimPoint(0, 0), new ClaimPoint(9, 0), new ClaimPoint(9, 4),
                new ClaimPoint(4, 4), new ClaimPoint(4, 9), new ClaimPoint(0, 9)));
    }

    @Test
    @DisplayName("a long road is looked up quickly, however many corners it has")
    void longRoadIsQuick() {
        ClaimShape shape = new ClaimShape(road(7, 6000, 6), -64, 319);
        assertThat(shape.vertices().size()).isGreaterThan(900);
        shape.containsColumn(0, 0); // builds the index once
        Random random = new Random(1);
        long started = System.nanoTime();
        int hits = 0;
        for (int round = 0; round < 2_000_000; round++) {
            int x = shape.minX() + random.nextInt(shape.maxX() - shape.minX() + 1);
            int z = shape.minZ() + random.nextInt(shape.maxZ() - shape.minZ() + 1);
            if (shape.containsColumn(x, z)) {
                hits++;
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        assertThat(hits).isPositive();
        // The edge walk takes minutes here (a thousand edges per question); the index well under a second.
        assertThat(millis).isLessThan(3_000L);
    }

    @Test
    @DisplayName("is filed only under the chunks it really covers, not its whole bounding box")
    void coveredChunksAreTheRealOnes() {
        ClaimShape shape = new ClaimShape(road(3, 1500, 6), 0, 10);
        Set<Long> expected = new HashSet<>();
        for (int x = shape.minX(); x <= shape.maxX(); x++) {
            for (int z = shape.minZ(); z <= shape.maxZ(); z++) {
                if (shape.containsColumn(x, z)) {
                    expected.add(ClaimShape.chunkKey(x >> 4, z >> 4));
                }
            }
        }
        assertThat(new HashSet<>(shape.coveredChunkKeys())).isEqualTo(expected);
        long box = (long) ((shape.maxX() >> 4) - (shape.minX() >> 4) + 1) * ((shape.maxZ() >> 4) - (shape.minZ() >> 4) + 1);
        assertThat((long) expected.size()).isLessThan(box);
    }

    @Test
    @DisplayName("overlap and containment read the same as column by column")
    void overlapAgrees() {
        for (long seed = 0; seed < 60; seed++) {
            ClaimShape a = new ClaimShape(randomStar(seed), 0, 10);
            int shiftX = (int) (seed % 50) - 25;
            int shiftZ = (int) (seed % 30) - 15;
            ClaimShape b = new ClaimShape(randomStar(seed + 1000).stream()
                    .map(p -> new ClaimPoint(p.x() + shiftX, p.z() + shiftZ))
                    .toList(), 0, 10);
            boolean shared = false;
            boolean aInB = true;
            for (int x = Math.min(a.minX(), b.minX()); x <= Math.max(a.maxX(), b.maxX()); x++) {
                for (int z = Math.min(a.minZ(), b.minZ()); z <= Math.max(a.maxZ(), b.maxZ()); z++) {
                    boolean inA = reference(a.vertices(), x, z);
                    boolean inB = reference(b.vertices(), x, z);
                    shared |= inA && inB;
                    aInB &= !inA || inB;
                }
            }
            assertThat(a.intersects(b)).as("seed %d", seed).isEqualTo(shared);
            assertThat(a.isContainedIn(b)).as("seed %d", seed).isEqualTo(aInB);
        }
    }

    @Test
    @DisplayName("a rectangle is still answered from its corners")
    void rectanglesUnchanged() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 4, 4, 0, 10);
        assertThat(shape.coveredChunkKeys()).containsExactly(ClaimShape.chunkKey(0, 0));
        assertThat(shape.containsColumn(4, 4)).isTrue();
        assertThat(shape.containsColumn(5, 4)).isFalse();
    }

    @Test
    @DisplayName("an outline that crosses itself is not simple; a road outline is")
    void simplicity() {
        assertThat(ClaimShape.crossesItself(road(5, 2000, 6))).isFalse();
        assertThat(ClaimShape.crossesItself(List.of(new ClaimPoint(0, 0), new ClaimPoint(10, 10),
                new ClaimPoint(10, 0), new ClaimPoint(0, 10)))).isTrue();
        assertThat(ClaimShape.crossesItself(List.of(new ClaimPoint(0, 0), new ClaimPoint(10, 0),
                new ClaimPoint(10, 10), new ClaimPoint(0, 10)))).isFalse();
        // Touching itself at a corner further along also counts: two loops joined at a point.
        assertThat(ClaimShape.crossesItself(List.of(new ClaimPoint(0, 0), new ClaimPoint(10, 0),
                new ClaimPoint(5, 5), new ClaimPoint(10, 10), new ClaimPoint(0, 10), new ClaimPoint(5, 5))))
                .isTrue();
        // Doubling back along its own edge.
        assertThat(ClaimShape.crossesItself(List.of(new ClaimPoint(0, 0), new ClaimPoint(10, 0),
                new ClaimPoint(5, 0)))).isTrue();
    }
}
