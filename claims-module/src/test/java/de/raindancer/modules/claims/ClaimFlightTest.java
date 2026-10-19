package de.raindancer.modules.claims;

import de.raindancer.core.world.movement.Flight;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.FeaturePolicy;
import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.modules.claims.service.ClaimFlight;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.FeaturePolicies;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Flight as a claim perk: off until the owner switches it on, for the household unless they widen it, and given
 * and taken through Core's {@link Flight} so it never ends a {@code /fly} somebody had anyway.
 */
class ClaimFlightTest {

    private final UUID owner = UUID.randomUUID();
    private final FeaturePolicies policies = FeaturePolicies.builtIn();
    private final FeatureRules features = new FeatureRules(policies);
    private final Flight core = mock(Flight.class);
    private final Land land = mock(Land.class);
    private final Player player = mock(Player.class);
    private ClaimFlight flight;
    private Claim claim;

    @BeforeEach
    void setUp() {
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0), new ClaimPoint(0, 4),
                new ClaimPoint(4, 4), new ClaimPoint(4, 0)), 0, 128);
        claim = new Claim(UUID.randomUUID(), "test", UUID.randomUUID(), "world", shape, owner);
        when(player.getUniqueId()).thenReturn(owner);
        when(land.has(any(), eq(player), eq(LandAction.ENTER))).thenReturn(true);
        flight = new ClaimFlight(core, features, land);
    }

    @Test
    @DisplayName("offered by default, but every claim starts with it switched off")
    void offButOffered() {
        assertThat(features.isOffered(ClaimFeature.FLIGHT)).isTrue();
        assertThat(ClaimFeature.FLIGHT.ownerSwitchable()).isTrue();
        assertThat(features.isEnabled(claim, ClaimFeature.FLIGHT)).isFalse();

        claim.flightEnabled(true);
        assertThat(features.isEnabled(claim, ClaimFeature.FLIGHT)).isTrue();
    }

    @Test
    @DisplayName("serves owners and trusted players until the owner lets visitors fly too")
    void householdByDefault() {
        assertThat(claim.featureServes(ClaimFeature.FLIGHT, LandAudience.OWNER)).isTrue();
        assertThat(claim.featureServes(ClaimFeature.FLIGHT, LandAudience.TRUSTED)).isTrue();
        assertThat(claim.featureServes(ClaimFeature.FLIGHT, LandAudience.VISITOR)).isFalse();
        // Everything else still serves everybody when nobody narrowed it.
        assertThat(claim.featureServes(ClaimFeature.PANTRY, LandAudience.VISITOR)).isTrue();

        claim.setFeatureAudience(ClaimFeature.FLIGHT, LandAudience.VISITOR, true);
        assertThat(claim.featureServes(ClaimFeature.FLIGHT, LandAudience.VISITOR)).isTrue();
    }

    @Test
    @DisplayName("the switch and a widened audience survive a save and a load")
    void roundTrip() {
        claim.flightEnabled(true);
        claim.setFeatureAudience(ClaimFeature.FLIGHT, LandAudience.VISITOR, true);

        YamlConfiguration yaml = ClaimStorage.describe(claim);
        Claim loaded = ClaimStorage.fromYaml(yaml, "test");

        assertThat(loaded.flightEnabled()).isTrue();
        assertThat(loaded.featureServes(ClaimFeature.FLIGHT, LandAudience.VISITOR)).isTrue();
    }

    @Test
    @DisplayName("a claim saved before flight existed loads with it off and for the household")
    void oldClaims() {
        YamlConfiguration yaml = ClaimStorage.describe(claim);
        yaml.set("flight-enabled", null);
        yaml.set("feature-audiences.flight", null);
        Claim loaded = ClaimStorage.fromYaml(yaml, "test");

        assertThat(loaded.flightEnabled()).isFalse();
        assertThat(loaded.featureServes(ClaimFeature.FLIGHT, LandAudience.VISITOR)).isFalse();
    }

    @Test
    @DisplayName("standing in a claim that lets them fly takes a lease")
    void grants() {
        claim.flightEnabled(true);
        flight.update(player, claim);
        verify(core).grant(player, ClaimFlight.SOURCE);
    }

    @Test
    @DisplayName("switched off, out of the audience, barred or outside any claim: the lease goes")
    void revokes() {
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);

        flight.update(player, claim); // switched off
        flight.update(player, null);  // in the wild
        claim.flightEnabled(true);
        claim.setFeatureAudience(ClaimFeature.FLIGHT, LandAudience.OWNER, false);
        flight.update(player, claim); // owner took themselves out

        verify(core, never()).grant(any(), any());
        verify(core, org.mockito.Mockito.times(3)).revoke(player, ClaimFlight.SOURCE);
    }

    @Test
    @DisplayName("somebody who may not even be here does not fly here")
    void barred() {
        claim.flightEnabled(true);
        when(land.has(any(), eq(player), eq(LandAction.ENTER))).thenReturn(false);
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        flight.update(player, claim);
        verify(core, never()).grant(any(), any());
        verify(core).revoke(player, ClaimFlight.SOURCE);
    }

    @Test
    @DisplayName("without a lease there is nothing to give back, so Core is not asked")
    void quietWithoutLease() {
        flight.update(player, null);
        verify(core, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("the server forcing it off ends flight even where the owner switched it on")
    void forcedOff() {
        policies.policy(ClaimFeature.FLIGHT, FeaturePolicy.FORCED_OFF);
        claim.flightEnabled(true);
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        flight.update(player, claim);
        verify(core).revoke(player, ClaimFlight.SOURCE);
    }
}
