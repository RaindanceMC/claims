package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.MovementListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Player;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Riding across a claim border a player's own feet could not cross.
 *
 * <h2>The bug this closes</h2>
 * {@link org.bukkit.event.player.PlayerMoveEvent} does not fire for a passenger — only the vehicle under
 * them moves, reported by {@link VehicleMoveEvent}. {@code onMove} and {@code onTeleport} were the only
 * places running the ban/entry gate, so a player banned from a claim — or one that simply refuses them
 * entry — only had to bring a Happy Ghast, a boat or a horse along to sit inside it undisturbed.
 */
@ExtendWith(MockitoExtension.class)
class VehicleBorderTest {

    private final World world = FakeServices.world();
    private final ClaimRegistry registry = new ClaimRegistry();
    private final Land land = mock(Land.class);
    private final UUID banned = UUID.randomUUID();
    private final UUID welcome = UUID.randomUUID();

    private Claim claim() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 9, 9, 0, 20);
        Claim claim = new Claim(UUID.randomUUID(), "manor", FakeServices.WORLD, "world", shape,
                UUID.randomUUID());
        claim.ban(ClaimBan.permanent(banned, UUID.randomUUID(), "trouble"));
        return claim;
    }

    private MovementListener listener(Claim claim) {
        registry.add(claim);
        ClaimServices services = FakeServices.builder().claims(registry).land(land).build();
        return new MovementListener(services);
    }

    private Vehicle vehicleCarrying(Player... riders) {
        Vehicle vehicle = mock(Vehicle.class);
        // Lenient: the sub-block-movement test below never reaches the passenger lookup at all, which
        // is exactly the behaviour it is asserting.
        org.mockito.Mockito.lenient().when(vehicle.getPassengers())
                .thenReturn(List.<org.bukkit.entity.Entity>of(riders));
        return vehicle;
    }

    @Test
    @DisplayName("a boat/Happy Ghast carrying a banned rider is put straight back at the border")
    void aBannedRiderIsTurnedBackWithTheVehicle() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        Player rider = FakeServices.player(banned);
        Vehicle vehicle = vehicleCarrying(rider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle).teleport(from);
        verify(vehicle).setVelocity(new Vector(0, 0, 0));
        assertThat(listener.claimOf(rider))
                .as("a reverted crossing must not be recorded as an arrival")
                .isEmpty();
    }

    @Test
    @DisplayName("one banned passenger among several turns the whole vehicle back, not just them")
    void oneBannedPassengerStopsTheWholeVehicle() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        // The welcome rider alone would be let in — stubbed so the revert below is provably about the
        // banned passenger, not a false pass from an unrelated denial.
        when(land.has(any(), any(), any(LandAction.class))).thenReturn(true);
        Player welcomeRider = FakeServices.player(welcome);
        Player bannedRider = FakeServices.player(banned);
        Vehicle vehicle = vehicleCarrying(welcomeRider, bannedRider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle).teleport(from);
        assertThat(listener.claimOf(welcomeRider))
                .as("letting the unbanned passenger through would just move the loophole to whoever "
                        + "is sitting in the front seat")
                .isEmpty();
    }

    @Test
    @DisplayName("a rider free to enter is carried across, and the border tracker follows them")
    void aWelcomeRiderCrossesAndIsTracked() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        when(land.has(any(), any(), any(LandAction.class))).thenReturn(true);
        Player rider = FakeServices.player(welcome);
        Vehicle vehicle = vehicleCarrying(rider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle, never()).teleport(any(Location.class));
        assertThat(listener.claimOf(rider)).contains(claim);
    }

    @Test
    @DisplayName("a sub-block wobble that does not cross a block boundary is not even looked up")
    void subBlockMovementIsIgnored() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        Player rider = FakeServices.player(banned);
        Vehicle vehicle = vehicleCarrying(rider);

        Location from = FakeServices.at(world, 5, 5, 5);
        Location to = from.clone().add(0.2, 0, 0.1);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle, never()).teleport(any(Location.class));
    }

    // ------------------------------------------------------------ living mounts

    /*
     * Paper fires VehicleMoveEvent only for boats and minecarts. A camel, horse, pig, strider or Happy
     * Ghast is a living entity and reports its moves through EntityMoveEvent alone, so the handler above
     * never saw them: a banned player only had to sit on a camel to ride straight into the claim.
     */

    private Camel camelCarrying(Player... riders) {
        Camel camel = mock(Camel.class);
        org.mockito.Mockito.lenient().when(camel.getPassengers())
                .thenReturn(List.<org.bukkit.entity.Entity>of(riders));
        return camel;
    }

    @Test
    @DisplayName("a camel carrying a banned rider is stopped at the border, rider and all")
    void aBannedCamelRiderIsTurnedBack() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        Player rider = FakeServices.player(banned);
        when(rider.isOnline()).thenReturn(true);
        Camel camel = mock(Camel.class);
        when(camel.isValid()).thenReturn(true);
        // Seated while crossing, unseated once the teleport has run — as on a real server.
        when(camel.getPassengers()).thenReturn(List.<org.bukkit.entity.Entity>of(rider),
                List.<org.bukkit.entity.Entity>of(rider), List.of());

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        EntityMoveEvent event = new EntityMoveEvent(camel, from, to);
        listener.onMountMove(event);

        assertThat(event.isCancelled()).isTrue();
        verify(camel).teleport(from);
        // The rider steers the camel from their own client, which never hears about a server-side snap of
        // the camel alone; only a teleport of the player is guaranteed to reach them.
        verify(rider).teleport(any(Location.class));
        verify(camel).addPassenger(rider);
        assertThat(listener.claimOf(rider)).isEmpty();
    }

    @Test
    @DisplayName("a camel with a welcome rider walks in, and the tracker follows the rider")
    void aWelcomeCamelRiderCrosses() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        when(land.has(any(), any(), any(LandAction.class))).thenReturn(true);
        Player rider = FakeServices.player(welcome);
        Camel camel = camelCarrying(rider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        EntityMoveEvent event = new EntityMoveEvent(camel, from, to);
        listener.onMountMove(event);

        assertThat(event.isCancelled()).isFalse();
        verify(camel, never()).teleport(any(Location.class));
        assertThat(listener.claimOf(rider)).contains(claim);
    }

    @Test
    @DisplayName("a riderless camel wandering in is none of the claim gate's business")
    void aRiderlessCamelIsIgnored() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        Camel camel = camelCarrying();

        EntityMoveEvent event = new EntityMoveEvent(camel,
                FakeServices.at(world, -1, 5, 5), FakeServices.at(world, 1, 5, 5));
        listener.onMountMove(event);

        assertThat(event.isCancelled()).isFalse();
        verify(camel, never()).teleport(any(Location.class));
    }
}
