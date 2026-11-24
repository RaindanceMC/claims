package de.raindancer.modules.claims.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The horizontal footprint of a claim as a simple polygon of block columns plus a vertical range.
 * <p>
 * A rectangular claim is just a four vertex polygon, so the whole plugin only ever deals with one
 * shape type. Vertices are block coordinates and describe the <em>column</em> they sit in: the
 * polygon covers a vertex column entirely, which is what players expect when they click a block.
 * <p>
 * Immutable — resizing produces a new instance.
 */
public final class ClaimShape {

    private final List<ClaimPoint> vertices;
    private final int minY;
    private final int maxY;

    // Cached bounding box, derived from the vertices.
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;

    private final boolean rectangle;

    /**
     * For every row from {@link #minZ} to {@link #maxZ}, the columns inside as sorted, disjoint
     * {@code from,to} pairs (inclusive). Null for a rectangle, which needs none.
     *
     * <p>Built once from the edge walk so a lookup is a binary search in one row instead of a walk over every
     * edge: a road held as one claim has a thousand corners and is asked about several times a tick.
     */
    private final int[][] rows;

    private final long area;

    public ClaimShape(List<ClaimPoint> vertices, int minY, int maxY) {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException("A claim shape needs at least 3 vertices, got "
                    + (vertices == null ? 0 : vertices.size()));
        }
        this.vertices = List.copyOf(vertices);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);

        int loX = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
        for (ClaimPoint point : this.vertices) {
            loX = Math.min(loX, point.x());
            loZ = Math.min(loZ, point.z());
            hiX = Math.max(hiX, point.x());
            hiZ = Math.max(hiZ, point.z());
        }
        this.minX = loX;
        this.minZ = loZ;
        this.maxX = hiX;
        this.maxZ = hiZ;
        this.rectangle = computeIsRectangle();
        this.rows = rectangle ? null : buildRows();
        this.area = computeArea();
    }

    /** Builds a rectangular shape from two opposite corner columns. */
    public static ClaimShape rectangle(int x1, int z1, int x2, int z2, int minY, int maxY) {
        int loX = Math.min(x1, x2);
        int hiX = Math.max(x1, x2);
        int loZ = Math.min(z1, z2);
        int hiZ = Math.max(z1, z2);
        return new ClaimShape(List.of(
                new ClaimPoint(loX, loZ),
                new ClaimPoint(hiX, loZ),
                new ClaimPoint(hiX, hiZ),
                new ClaimPoint(loX, hiZ)), minY, maxY);
    }

    /**
     * A round shape: every block column whose centre lies within {@code radius} of the centre column.
     *
     * <p>Kept as an outline, so everything that reads a shape — storage, borders, fences, maps, overlap checks —
     * reads this one too. Its corners sit on the radius, close enough together that an edge never bows in by
     * more than a tenth of a block: nothing within the radius is left out, and nothing more than two blocks
     * beyond it is taken in (corners sit on whole blocks, and the rim columns an edge passes through count as
     * inside, as on any claim).
     */
    public static ClaimShape circle(int centreX, int centreZ, int radius, int minY, int maxY) {
        if (radius < 1) {
            throw new IllegalArgumentException("A round claim needs a radius of at least 1, got " + radius);
        }
        double outline = radius;
        // As many corners as it takes for a straight edge to bow in by no more than a tenth of a block.
        int corners = Math.max(8, (int) Math.ceil(Math.PI / Math.acos(1.0D - 0.1D / outline)));
        List<ClaimPoint> points = new ArrayList<>(corners);
        for (int at = 0; at < corners; at++) {
            double angle = 2.0D * Math.PI * at / corners;
            ClaimPoint point = new ClaimPoint(centreX + (int) Math.round(outline * Math.cos(angle)),
                    centreZ + (int) Math.round(outline * Math.sin(angle)));
            if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
                points.add(point);
            }
        }
        if (points.size() > 1 && points.get(0).equals(points.get(points.size() - 1))) {
            points.remove(points.size() - 1);
        }
        return new ClaimShape(points, minY, maxY);
    }

    public List<ClaimPoint> vertices() {
        return vertices;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public boolean isRectangle() {
        return rectangle;
    }

    private boolean computeIsRectangle() {
        if (vertices.size() != 4) {
            return false;
        }
        for (ClaimPoint point : vertices) {
            boolean onEdge = (point.x() == minX || point.x() == maxX) && (point.z() == minZ || point.z() == maxZ);
            if (!onEdge) {
                return false;
            }
        }
        return true;
    }

    public ClaimShape withVerticalRange(int newMinY, int newMaxY) {
        return new ClaimShape(vertices, newMinY, newMaxY);
    }

    public boolean containsY(int y) {
        return y >= minY && y <= maxY;
    }

    public boolean containsColumn(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        if (rectangle) {
            return true;
        }
        int[] spans = rows[z - minZ];
        // The last span starting at or before x is the only one that can hold it.
        int low = 0;
        int high = spans.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (spans[middle * 2] <= x) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return high >= 0 && x <= spans[high * 2 + 1];
    }

    /** The columns inside on row {@code z} as sorted inclusive {@code from,to} pairs; empty off the shape. */
    private int[] spans(int z) {
        if (z < minZ || z > maxZ) {
            return NO_SPANS;
        }
        return rectangle ? new int[]{minX, maxX} : rows[z - minZ];
    }

    private static final int[] NO_SPANS = new int[0];

    /**
     * Every row's spans, worked out from the same edge walk the lookups used to do column by column.
     *
     * <p>Two parts, exactly as before: the columns whose centre a crossing count puts inside, and the columns an
     * edge passes through (with half a block of slack, so diagonal edges leave no gaps). The arithmetic of the
     * crossing count is kept in the same doubles and the same order, so no column changes sides.
     */
    private int[][] buildRows() {
        int size = vertices.size();
        int[][] built = new int[maxZ - minZ + 1][];
        long[] found = new long[16];
        double[] crossings = new double[8];
        for (int z = minZ; z <= maxZ; z++) {
            int spans = 0;
            int crossed = 0;
            double pz = z + 0.5D;
            for (int i = 0, j = size - 1; i < size; j = i++) {
                ClaimPoint a = vertices.get(j);
                ClaimPoint b = vertices.get(i);
                double xi = b.x() + 0.5D;
                double zi = b.z() + 0.5D;
                double xj = a.x() + 0.5D;
                double zj = a.z() + 0.5D;
                if ((zi > pz) != (zj > pz)) {
                    if (crossed == crossings.length) {
                        crossings = Arrays.copyOf(crossings, crossed * 2);
                    }
                    crossings[crossed++] = (xj - xi) * (pz - zi) / (zj - zi) + xi;
                }
                if (z < Math.min(a.z(), b.z()) || z > Math.max(a.z(), b.z())) {
                    continue;
                }
                long[] onEdge = edgeColumns(a, b, z);
                if (onEdge != null) {
                    if (spans == found.length) {
                        found = Arrays.copyOf(found, spans * 2);
                    }
                    found[spans++] = pack(onEdge[0], onEdge[1]);
                }
            }
            Arrays.sort(crossings, 0, crossed);
            // A column is inside when an odd number of crossings lie beyond its centre: between the first and
            // second crossing, the third and fourth, and so on.
            for (int at = 0; at + 1 < crossed; at += 2) {
                long from = firstCentreAtOrAfter(crossings[at]);
                long to = lastCentreBefore(crossings[at + 1]);
                if (from <= to) {
                    if (spans == found.length) {
                        found = Arrays.copyOf(found, spans * 2);
                    }
                    found[spans++] = pack(Math.max(from, minX), Math.min(to, maxX));
                }
            }
            built[z - minZ] = merge(found, spans);
        }
        return built;
    }

    /** The columns on row z that the edge from a to b passes through, as {@code from,to}; null for none. */
    private static long[] edgeColumns(ClaimPoint a, ClaimPoint b, int z) {
        long loX = Math.min(a.x(), b.x());
        long hiX = Math.max(a.x(), b.x());
        long dx = (long) b.x() - a.x();
        long dz = (long) b.z() - a.z();
        if (dz == 0) {
            return new long[]{loX, hiX};
        }
        long slack = Math.abs(dx) + Math.abs(dz);
        long k = dx * ((long) z - a.z());
        // |k - dz * (x - a.x)| <= slack, solved for x.
        long low = dz > 0 ? ceilDiv(k - slack, dz) : ceilDiv(k + slack, dz);
        long high = dz > 0 ? Math.floorDiv(k + slack, dz) : Math.floorDiv(k - slack, dz);
        long from = Math.max(loX, a.x() + low);
        long to = Math.min(hiX, a.x() + high);
        return from <= to ? new long[]{from, to} : null;
    }

    private static long ceilDiv(long dividend, long divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }

    /** The smallest x whose centre x + 0.5 is not below the crossing, by the same comparison as the walk. */
    private static long firstCentreAtOrAfter(double crossing) {
        long x = (long) Math.ceil(crossing - 0.5D);
        while (x - 1 + 0.5D >= crossing) {
            x--;
        }
        while (x + 0.5D < crossing) {
            x++;
        }
        return x;
    }

    /** The largest x whose centre x + 0.5 is still below the crossing. */
    private static long lastCentreBefore(double crossing) {
        long x = (long) Math.floor(crossing - 0.5D);
        while (x + 0.5D >= crossing) {
            x--;
        }
        while (x + 1 + 0.5D < crossing) {
            x++;
        }
        return x;
    }

    private static long pack(long from, long to) {
        return (from << 32) | (to & 0xFFFFFFFFL);
    }

    /** Sorts the packed spans by start and joins those that overlap or touch. */
    private static int[] merge(long[] packed, int count) {
        if (count == 0) {
            return NO_SPANS;
        }
        Arrays.sort(packed, 0, count);
        int[] out = new int[count * 2];
        int written = 0;
        int from = (int) (packed[0] >> 32);
        int to = (int) packed[0];
        for (int at = 1; at < count; at++) {
            int nextFrom = (int) (packed[at] >> 32);
            int nextTo = (int) packed[at];
            if ((long) nextFrom <= (long) to + 1L) {
                to = Math.max(to, nextTo);
            } else {
                out[written++] = from;
                out[written++] = to;
                from = nextFrom;
                to = nextTo;
            }
        }
        out[written++] = from;
        out[written++] = to;
        return written == out.length ? out : Arrays.copyOf(out, written);
    }

    /**
     * Where an outline runs into itself — two edges that cross or touch, or one that doubles back along the
     * last — as a corner of the first offending edge. Empty for a simple polygon.
     *
     * <p>Every pair of edges is compared, so a few thousand corners take a moment; it is asked once, when the
     * shape is made, never on a lookup.
     */
    public static Optional<ClaimPoint> firstCrossing(List<ClaimPoint> outline) {
        int size = outline.size();
        for (int i = 0; i < size; i++) {
            ClaimPoint a = outline.get(i);
            ClaimPoint b = outline.get((i + 1) % size);
            int loX = Math.min(a.x(), b.x());
            int hiX = Math.max(a.x(), b.x());
            int loZ = Math.min(a.z(), b.z());
            int hiZ = Math.max(a.z(), b.z());
            for (int j = i + 1; j < size; j++) {
                ClaimPoint c = outline.get(j);
                ClaimPoint d = outline.get((j + 1) % size);
                boolean follows = j == i + 1;
                boolean closes = i == 0 && j == size - 1;
                if (follows || closes) {
                    // Neighbours share a corner; they only collide by folding back onto each other.
                    ClaimPoint shared = follows ? b : a;
                    ClaimPoint mine = follows ? a : b;
                    ClaimPoint theirs = follows ? d : c;
                    long ux = (long) mine.x() - shared.x();
                    long uz = (long) mine.z() - shared.z();
                    long vx = (long) theirs.x() - shared.x();
                    long vz = (long) theirs.z() - shared.z();
                    if (ux * vz - uz * vx == 0 && ux * vx + uz * vz > 0) {
                        return Optional.of(shared);
                    }
                    continue;
                }
                if (Math.max(c.x(), d.x()) < loX || Math.min(c.x(), d.x()) > hiX
                        || Math.max(c.z(), d.z()) < loZ || Math.min(c.z(), d.z()) > hiZ) {
                    continue;
                }
                if (segmentsMeet(a, b, c, d)) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    /** Whether an outline runs into itself; see {@link #firstCrossing}. */
    public static boolean crossesItself(List<ClaimPoint> outline) {
        return firstCrossing(outline).isPresent();
    }

    private static boolean segmentsMeet(ClaimPoint a, ClaimPoint b, ClaimPoint c, ClaimPoint d) {
        int o1 = orientation(a, b, c);
        int o2 = orientation(a, b, d);
        int o3 = orientation(c, d, a);
        int o4 = orientation(c, d, b);
        if (o1 != o2 && o3 != o4) {
            return true;
        }
        return (o1 == 0 && onSegment(a, c, b)) || (o2 == 0 && onSegment(a, d, b))
                || (o3 == 0 && onSegment(c, a, d)) || (o4 == 0 && onSegment(c, b, d));
    }

    private static int orientation(ClaimPoint p, ClaimPoint q, ClaimPoint r) {
        long value = ((long) q.z() - p.z()) * ((long) r.x() - q.x()) - ((long) q.x() - p.x()) * ((long) r.z() - q.z());
        return Long.signum(value);
    }

    private static boolean onSegment(ClaimPoint p, ClaimPoint q, ClaimPoint r) {
        return q.x() <= Math.max(p.x(), r.x()) && q.x() >= Math.min(p.x(), r.x())
                && q.z() <= Math.max(p.z(), r.z()) && q.z() >= Math.min(p.z(), r.z());
    }

    public boolean containsBlock(int x, int y, int z) {
        return containsY(y) && containsColumn(x, z);
    }

    /** Horizontal footprint in blocks, via the shoelace formula on the expanded column outline. */
    public long areaBlocks() {
        return area;
    }

    private long computeArea() {
        if (rectangle) {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
        long twiceArea = 0L;
        int size = vertices.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            ClaimPoint current = vertices.get(i);
            ClaimPoint previous = vertices.get(j);
            twiceArea += (long) previous.x() * current.z() - (long) current.x() * previous.z();
        }
        long polygonArea = Math.abs(twiceArea) / 2L;
        // Pick's theorem correction: the lattice polygon area under-counts the blocks whose columns
        // the outline passes through. Adding half the boundary length plus one approximates them.
        long boundary = 0L;
        for (int i = 0, j = size - 1; i < size; j = i++) {
            ClaimPoint current = vertices.get(i);
            ClaimPoint previous = vertices.get(j);
            boundary += Math.max(Math.abs(current.x() - previous.x()), Math.abs(current.z() - previous.z()));
        }
        return polygonArea + boundary / 2L + 1L;
    }

    public long volumeBlocks() {
        return areaBlocks() * height();
    }

    /** Bounding box overlap — cheap pre-filter before the exact test. */
    public boolean boundsIntersect(ClaimShape other) {
        return minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ
                && minY <= other.maxY && maxY >= other.minY;
    }

    /**
     * True when the two shapes share at least one block. Vertical ranges must overlap, which is what
     * makes stacked claims (one underground, one on the surface) possible.
     */
    public boolean intersects(ClaimShape other) {
        if (!boundsIntersect(other)) {
            return false;
        }
        int loX = Math.max(minX, other.minX);
        int hiX = Math.min(maxX, other.maxX);
        int loZ = Math.max(minZ, other.minZ);
        int hiZ = Math.min(maxZ, other.maxZ);
        for (int z = loZ; z <= hiZ; z++) {
            int[] mine = spans(z);
            int[] theirs = other.spans(z);
            int i = 0;
            int j = 0;
            while (i < mine.length && j < theirs.length) {
                int from = Math.max(Math.max(mine[i], theirs[j]), loX);
                int to = Math.min(Math.min(mine[i + 1], theirs[j + 1]), hiX);
                if (from <= to) {
                    return true;
                }
                if (mine[i + 1] < theirs[j + 1]) {
                    i += 2;
                } else {
                    j += 2;
                }
            }
        }
        return false;
    }

    /** True when every column of this shape lies inside {@code other} and the Y range fits too. */
    public boolean isContainedIn(ClaimShape other) {
        if (minY < other.minY || maxY > other.maxY) {
            return false;
        }
        for (int z = minZ; z <= maxZ; z++) {
            int[] mine = spans(z);
            int[] theirs = other.spans(z);
            for (int i = 0; i < mine.length; i += 2) {
                boolean covered = false;
                for (int j = 0; j < theirs.length && !covered; j += 2) {
                    covered = theirs[j] <= mine[i] && theirs[j + 1] >= mine[i + 1];
                }
                if (!covered) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Chunk keys (as produced by {@link org.bukkit.Chunk#getChunkKey()}) of the chunks holding at least one
     * column of the shape. For anything but a rectangle that is fewer than the bounding box: a diagonal road
     * would otherwise sit in the lookup bucket of every chunk of the square it spans.
     */
    public List<Long> coveredChunkKeys() {
        if (rectangle) {
            List<Long> keys = new ArrayList<>();
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    keys.add(chunkKey(cx, cz));
                }
            }
            return keys;
        }
        Set<Long> keys = new LinkedHashSet<>();
        for (int z = minZ; z <= maxZ; z++) {
            int[] spans = rows[z - minZ];
            for (int at = 0; at < spans.length; at += 2) {
                for (int cx = spans[at] >> 4; cx <= spans[at + 1] >> 4; cx++) {
                    keys.add(chunkKey(cx, z >> 4));
                }
            }
        }
        return new ArrayList<>(keys);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public ClaimPoint centre() {
        return new ClaimPoint((minX + maxX) / 2, (minZ + maxZ) / 2);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClaimShape shape)) {
            return false;
        }
        return minY == shape.minY && maxY == shape.maxY && vertices.equals(shape.vertices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertices, minY, maxY);
    }

    @Override
    public String toString() {
        return "ClaimShape[vertices=" + vertices.size() + ", x=" + minX + ".." + maxX
                + ", z=" + minZ + ".." + maxZ + ", y=" + minY + ".." + maxY + "]";
    }
}
