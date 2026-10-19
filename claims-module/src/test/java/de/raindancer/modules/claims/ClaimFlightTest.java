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
    private final org.bukkit.plugin.Plugin plugin = mock(org.bukkit.plugin.Plugin.class);
    private final org.bukkit.World world = mock(org.bukkit.World.class);
    private final de.raindancer.core.ui.messages.Messages messages = mock(de.raindancer.core.ui.messages.Messages.class);
    private final java.util.List<java.util.function.Consumer<Object>> queued = new java.util.ArrayList<>();
    /** Where the player counts as standing when the grace runs out. */
    private Claim standingIn;
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
        when(plugin.isEnabled()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0, 64, 0, 0.6, 65.8, 0.6));
        var scheduler = mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.runDelayed(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            queued.add(call.getArgument(1));
            return null;
        });
        airborne(false);
        flight = new ClaimFlight(plugin, core, features, land, messages);
        flight.where(who -> standingIn);
    }

    private void airborne(boolean inTheAir) {
        when(world.hasCollisionsIn(any())).thenReturn(!inTheAir);
    }

    /** The grace runs out. */
    private void ticks() {
        var due = new java.util.ArrayList<>(queued);
        queued.clear();
        due.forEach(task -> task.accept(null));
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

    @Test
    @DisplayName("flying out, flight lasts two more seconds and they are told so")
    void graceOnLeaving() {
        claim.flightEnabled(true);
        flight.update(player, claim);
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        airborne(true);

        flight.update(player, null);
        verify(core, never()).revoke(any(), any());
        verify(player).sendActionBar(org.mockito.ArgumentMatchers.<net.kyori.adventure.text.Component>any());
        verify(messages).get("flight.ending", "seconds", ClaimFlight.GRACE_SECONDS);

        flight.update(player, null); // the ambience tick inside the grace changes nothing
        verify(core, never()).revoke(any(), any());

        ticks();
        verify(core).revoke(player, ClaimFlight.SOURCE);
        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("back in before the two seconds are up, they never stop flying")
    void backInTime() {
        claim.flightEnabled(true);
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        airborne(true);

        flight.update(player, null);
        flight.update(player, claim);
        standingIn = claim;
        ticks();
        verify(core, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("an earlier grace that ran out of date does not cut a later one short")
    void staleGraceIgnored() {
        claim.flightEnabled(true);
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        airborne(true);

        flight.update(player, null);  // out: grace one
        flight.update(player, claim); // back in
        var first = new java.util.ArrayList<>(queued);
        queued.clear();
        flight.update(player, null);  // out again: grace two
        first.forEach(task -> task.accept(null));
        verify(core, never()).revoke(any(), any());

        ticks();
        verify(core).revoke(player, ClaimFlight.SOURCE);
    }

    @Test
    @DisplayName("walking out on the ground ends it at once, with nothing to warn about")
    void groundedEndsAtOnce() {
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        flight.update(player, null);
        verify(core).revoke(player, ClaimFlight.SOURCE);
        verify(player, never()).sendActionBar(org.mockito.ArgumentMatchers.<net.kyori.adventure.text.Component>any());
        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("the module stopping ends it without waiting out a grace")
    void releaseAllIsImmediate() {
        when(core.holds(owner, ClaimFlight.SOURCE)).thenReturn(true);
        airborne(true);
        flight.update(player, null);
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            flight.releaseAll(List.of(player));
        }
        verify(core).revoke(player, ClaimFlight.SOURCE);
        ticks(); // the abandoned grace does nothing more
        verify(core).revoke(player, ClaimFlight.SOURCE);
    }
}
