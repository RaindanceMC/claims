package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round claims: a centre and a radius, kept as an outline — anything within the radius is in, nothing more than
 * two blocks beyond it is (corners sit on whole blocks, and the rim columns an edge passes through count as
 * inside, as on every drawn claim). An outline rather than a second kind of shape, so storage, borders, fences
 * and maps need nothing new.
 */
@DisplayName("Round claims")
class RoundClaimTest {

    private static double distance(int x, int z, int cx, int cz) {
        return Math.hypot(x - cx, z - cz);
    }

    @ParameterizedTest(name = "radius {0}")
    @ValueSource(ints = {5, 16, 40, 250, 600})
    @DisplayName("every column within the radius is in, every column more than two blocks outside is out")
    void decidedByDistance(int radius) {
        int cx = 31;
        int cz = 631;
        ClaimShape circle = ClaimShape.circle(cx, cz, radius, -64, 319);

        for (int x = cx - radius - 3; x <= cx + radius + 3; x++) {
            for (int z = cz - radius - 3; z <= cz + radius + 3; z++) {
                double d = distance(x, z, cx, cz);
                if (d <= radius) {
                    assertThat(circle.containsColumn(x, z)).as("(%d, %d) at %.2f", x, z, d).isTrue();
                } else if (d > radius + 2) {
                    assertThat(circle.containsColumn(x, z)).as("(%d, %d) at %.2f", x, z, d).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("it is round, not a square: the corners of the bounding box are outside")
    void notASquare() {
        ClaimShape circle = ClaimShape.circle(31, 631, 250, -64, 319);

        assertThat(circle.isRectangle()).isFalse();
        assertThat(circle.minX()).isEqualTo(31 - 250);
        assertThat(circle.maxX()).isEqualTo(31 + 250);
        assertThat(circle.minZ()).isEqualTo(631 - 250);
        assertThat(circle.maxZ()).isEqualTo(631 + 250);
        assertThat(circle.containsColumn(31 + 200, 631 + 200)).isFalse();
        assertThat(circle.containsColumn(31 + 176, 631 + 176)).isTrue();
    }

    @Test
    @DisplayName("its area is the circle's, give or take its rim")
    void area() {
        ClaimShape circle = ClaimShape.circle(0, 0, 250, -64, 319);
        double exact = Math.PI * 250 * 250;

        assertThat((double) circle.areaBlocks()).isBetween(exact * 0.99, exact * 1.01);
    }

    @Test
    @DisplayName("a small circle is still a shape, a nonsensical one is refused")
    void bounds() {
        assertThat(ClaimShape.circle(0, 0, 1, 0, 10).vertices().size()).isGreaterThanOrEqualTo(3);
        assertThatThrownBy(() -> ClaimShape.circle(0, 0, 0, 0, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a round server claim survives being stored, outline and all")
    void storedAsItsOutline() {
        Claim spawn = Claim.server(UUID.randomUUID(), "Spawn", UUID.randomUUID(), "world",
                ClaimShape.circle(31, 631, 250, -64, 319));

        Claim loaded = ClaimStorage.fromYaml(ClaimStorage.describe(spawn), "test");

        assertThat(loaded.shape()).isEqualTo(spawn.shape());
        assertThat(loaded.shape().containsColumn(31, 631 + 249)).isTrue();
        assertThat(loaded.shape().containsColumn(31 + 200, 631 + 200)).isFalse();
    }
}
