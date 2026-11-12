package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.store.ClaimLandProvider;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Another plugin (a teleport point, say) asking Core for ground, answered with a server claim. */
class HoldForServerTest {

    private static final UUID WORLD = UUID.randomUUID();

    @TempDir
    Path folder;

    private ClaimRegistry registry;
    private ClaimLandProvider provider;
    private World world;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        registry = new ClaimRegistry();
        ClaimService service = new ClaimService(plugin, registry, mock(ZoneRegistry.class),
                new ClaimStorage(folder), ClaimSettings.DEFAULTS, new CostService(), mock(ClaimRightsRule.class));
        provider = new ClaimLandProvider(registry);
        provider.serverClaims(service);
        world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
    }

    @Test
    void holdingMakesAFullHeightServerClaim() {
        assertThat(provider.holdForServer(world, "rtp-north", 10, 20, 16, 26)).isEmpty();

        Claim claim = registry.serverClaim("rtp-north").orElseThrow();
        assertThat(claim.isServerClaim()).isTrue();
        assertThat(claim.shape().containsBlock(13, 0, 23)).isTrue();
        assertThat(claim.shape().containsBlock(17, 0, 23)).isFalse();
        assertThat(claim.shape().minY()).isEqualTo(-64);
        assertThat(claim.shape().maxY()).isEqualTo(319);
    }

    @Test
    void holdingTheSameNameAgainMovesIt() {
        provider.holdForServer(world, "rtp-north", 10, 20, 16, 26);
        assertThat(provider.holdForServer(world, "rtp-north", 100, 200, 106, 206)).isEmpty();

        assertThat(registry.serverClaims()).hasSize(1);
        Claim claim = registry.serverClaim("rtp-north").orElseThrow();
        assertThat(claim.shape().containsBlock(103, 0, 203)).isTrue();
        assertThat(claim.shape().containsBlock(13, 0, 23)).isFalse();
    }

    @Test
    void aPlayersClaimInTheWayIsNamedAndNotOverwritten() {
        registry.add(new Claim(UUID.randomUUID(), "home", WORLD, "world",
                ClaimShape.rectangle(0, 0, 12, 22, -64, 319), UUID.randomUUID()));

        assertThat(provider.holdForServer(world, "rtp-north", 10, 20, 16, 26))
                .hasValueSatisfying(why -> assertThat(why).contains("home"));
        assertThat(registry.serverClaim("rtp-north")).isEmpty();
    }

    @Test
    void anUnusableNameIsRefused() {
        assertThat(provider.holdForServer(world, "rtp point!", 10, 20, 16, 26)).isPresent();
    }

    @Test
    void releasingRemovesIt() {
        provider.holdForServer(world, "rtp-north", 10, 20, 16, 26);

        assertThat(provider.releaseForServer("rtp-north")).isTrue();
        assertThat(registry.serverClaim("rtp-north")).isEmpty();
        assertThat(provider.releaseForServer("rtp-north")).isFalse();
    }

    @Test
    void beforeTheServiceIsReadyItSaysSo() {
        ClaimLandProvider early = new ClaimLandProvider(registry);
        assertThat(early.holdForServer(world, "rtp-north", 10, 20, 16, 26)).isPresent();
        assertThat(early.releaseForServer("rtp-north")).isFalse();
    }
}
