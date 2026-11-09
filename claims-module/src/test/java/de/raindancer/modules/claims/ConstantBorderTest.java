package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.visual.OutlineGeometry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A claim whose border stays drawn for everybody near it — spawn, so nobody has to ask where it ends. */
@DisplayName("Always shown borders")
class ConstantBorderTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static Claim spawn() {
        return Claim.server(UUID.randomUUID(), "spawn", WORLD, "world", ClaimShape.circle(31, 631, 200, -64, 319));
    }

    @Test
    @DisplayName("are off unless somebody switches them on")
    void offByDefault() {
        assertThat(spawn().borderAlwaysShown()).isFalse();
    }

    @Test
    @DisplayName("switching one on is a change to save")
    void switchingMarksDirty() {
        Claim claim = spawn();
        claim.clearDirty();

        claim.borderAlwaysShown(true);

        assertThat(claim.borderAlwaysShown()).isTrue();
        assertThat(claim.dirty()).isTrue();
    }

    @Test
    @DisplayName("survive a restart, and a claim without one writes nothing new")
    void onDisk() {
        Claim shown = spawn();
        shown.borderAlwaysShown(true);
        Claim plain = spawn();

        assertThat(ClaimStorage.fromYaml(ClaimStorage.describe(shown), "test").borderAlwaysShown()).isTrue();
        YamlConfiguration yaml = ClaimStorage.describe(plain);
        assertThat(yaml.contains("border-always")).isFalse();
        assertThat(ClaimStorage.fromYaml(yaml, "test").borderAlwaysShown()).isFalse();
    }

    @Test
    @DisplayName("only the stretch of border near the player is drawn")
    void onlyNearby() {
        List<ClaimPoint> outline = OutlineGeometry.outlineColumns(spawn().shape(), 2);

        // Standing on the border's east edge: some of it is in reach, the far side is not.
        List<ClaimPoint> near = OutlineGeometry.within(outline, 231, 631, 32);

        assertThat(near).isNotEmpty();
        assertThat(near).allMatch(column -> column.distanceSquared(231, 631) <= 32 * 32);
        assertThat(near.size()).isLessThan(outline.size() / 4);
        // In the middle of spawn, 200 blocks from every edge, nothing is drawn.
        assertThat(OutlineGeometry.within(outline, 31, 631, 32)).isEmpty();
    }
}
