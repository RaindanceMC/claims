package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.modules.claims.util.PolygonText;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code /serverclaim polygon}: a server claim of any outline, typed in or read from a file, from the console. */
@DisplayName("Polygon server claims")
class ServerPolygonTest {

    private static final UUID WORLD = UUID.randomUUID();

    @TempDir
    Path folder;

    private ClaimRegistry registry;
    private ClaimService service;
    private World world;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        registry = new ClaimRegistry();
        service = new ClaimService(plugin, registry, mock(ZoneRegistry.class),
                new ClaimStorage(folder), ClaimSettings.DEFAULTS, new CostService(), mock(ClaimRightsRule.class));
        world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
    }

    private static ClaimShape strip(int fromX, int toX) {
        return new ClaimShape(List.of(new ClaimPoint(fromX, 0), new ClaimPoint(toX, 10),
                new ClaimPoint(toX, 18), new ClaimPoint(fromX, 8)), -64, 319);
    }

    @Nested
    @DisplayName("reading the outline")
    class Reading {

        @Test
        @DisplayName("inline as x,z;x,z;… with spaces allowed")
        void inline() {
            PolygonText.Outline outline = PolygonText.parse("0,0; 10,0 ;10,-5;0,-5");
            assertThat(outline.problem()).isNull();
            assertThat(outline.points()).containsExactly(new ClaimPoint(0, 0), new ClaimPoint(10, 0),
                    new ClaimPoint(10, -5), new ClaimPoint(0, -5));
        }

        @Test
        @DisplayName("from a file: one corner per line, blank lines and # comments skipped, spaces or commas")
        void lines() {
            PolygonText.Outline outline = PolygonText.parse("# highway-1\n0,0\n\n10 0\n 10, 10 \r\n0,10\n");
            assertThat(outline.problem()).isNull();
            assertThat(outline.points()).hasSize(4);
        }

        @Test
        @DisplayName("a repeated corner and a closing corner equal to the first are dropped")
        void repeats() {
            PolygonText.Outline outline = PolygonText.parse("0,0;10,0;10,0;10,10;0,10;0,0");
            assertThat(outline.points()).containsExactly(new ClaimPoint(0, 0), new ClaimPoint(10, 0),
                    new ClaimPoint(10, 10), new ClaimPoint(0, 10));
        }

        @Test
        @DisplayName("names the line it could not read")
        void garbage() {
            assertThat(PolygonText.parse("0,0\n10,x\n5,5").problem()).contains("2").contains("10,x");
            assertThat(PolygonText.parse("0,0;1.5,2;3,3").problem()).contains("1.5,2");
        }

        @Test
        @DisplayName("fewer than three corners is not a shape")
        void tooFew() {
            assertThat(PolygonText.parse("0,0;10,10").problem()).contains("3");
            assertThat(PolygonText.parse("").problem()).isNotNull();
        }

        @Test
        @DisplayName("an outline crossing itself is refused")
        void crossing() {
            assertThat(PolygonText.parse("0,0;10,10;10,0;0,10").problem()).contains("crosses itself");
        }
    }

    @Nested
    @DisplayName("placing it")
    class Placing {

        @Test
        @DisplayName("makes a full-height server claim of exactly that outline")
        void creates() {
            ClaimService.Result result = service.placeServerPolygon(world, strip(0, 100), "highway-1", false, false);

            assertThat(result.success()).isTrue();
            Claim claim = registry.serverClaim("highway-1").orElseThrow();
            assertThat(claim.isServerClaim()).isTrue();
            assertThat(claim.shape().vertices()).hasSize(4);
            assertThat(registry.at(world, 50, 0, 10)).contains(claim);
            assertThat(registry.at(world, 50, 0, 0)).isEmpty();
        }

        @Test
        @DisplayName("the same name again redraws it instead of refusing")
        void replaces() {
            service.placeServerPolygon(world, strip(0, 100), "highway-1", false, false);
            ClaimService.Result again = service.placeServerPolygon(world, strip(200, 300), "highway-1", false, false);

            assertThat(again.success()).isTrue();
            assertThat(registry.serverClaims()).hasSize(1);
            assertThat(registry.at(world, 250, 0, 10)).isPresent();
            assertThat(registry.at(world, 50, 0, 10)).isEmpty();
        }

        @Test
        @DisplayName("a player's claim in the way is named and refused, unless forced")
        void playersClaim() {
            registry.add(new Claim(UUID.randomUUID(), "farm", WORLD, "world",
                    ClaimShape.rectangle(40, 0, 60, 30, -64, 319), UUID.randomUUID()));

            ClaimService.Result refused = service.placeServerPolygon(world, strip(0, 100), "highway-1", false, false);
            assertThat(refused.success()).isFalse();
            assertThat(refused.failure()).isEqualTo(ClaimService.Failure.OVERLAPS_CLAIM);
            assertThat(refused.detail()).contains("farm");
            assertThat(registry.serverClaim("highway-1")).isEmpty();

            assertThat(service.placeServerPolygon(world, strip(0, 100), "highway-1", false, true).success()).isTrue();
        }

        @Test
        @DisplayName("touching the next section or spawn is fine: only players' claims are in the way")
        void serverClaimsDoNotBlock() {
            service.placeServerPolygon(world, strip(0, 100), "highway-1", false, false);
            ClaimService.Result next = service.placeServerPolygon(world, strip(100, 200), "highway-2", false, false);

            assertThat(next.success()).isTrue();
            assertThat(next.detail()).contains("highway-1");
        }

        @Test
        @DisplayName("an unusable name is refused")
        void name() {
            assertThat(service.placeServerPolygon(world, strip(0, 100), "high way!", false, false).failure())
                    .isEqualTo(ClaimService.Failure.NAME_INVALID);
        }

        @Test
        @DisplayName("a name held by a player's claim is not touched")
        void playerName() {
            registry.add(new Claim(UUID.randomUUID(), "highway-1", WORLD, "world",
                    ClaimShape.rectangle(900, 900, 910, 910, -64, 319), UUID.randomUUID()));

            assertThat(service.placeServerPolygon(world, strip(0, 100), "highway-1", false, false).success()).isTrue();
            assertThat(registry.serverClaim("highway-1").orElseThrow().isServerClaim()).isTrue();
        }
    }

    @Nested
    @DisplayName("as a road")
    class Road {

        @Test
        @DisplayName("everybody may enter, ride, lead animals and use carts and boats, nobody build or break")
        void openForTraffic() {
            Claim claim = service.placeServerPolygon(world, strip(0, 100), "highway-1", true, false).claim();

            for (LandAction allowed : new LandAction[]{LandAction.ENTER, LandAction.ANIMALS, LandAction.VEHICLES,
                    LandAction.ITEM_PICKUP}) {
                assertThat(claim.area().may(UUID.randomUUID(), allowed)).as(allowed.name()).isTrue();
            }
            for (LandAction denied : new LandAction[]{LandAction.BUILD, LandAction.BREAK, LandAction.CONTAINERS,
                    LandAction.BUCKETS, LandAction.IGNITE, LandAction.DAMAGE_ANIMALS, LandAction.FARMLAND}) {
                assertThat(claim.area().may(UUID.randomUUID(), denied)).as(denied.name()).isFalse();
            }
        }

        @Test
        @DisplayName("is quiet: riding from one section into the next says nothing")
        void quiet() {
            Claim road = service.placeServerPolygon(world, strip(0, 100), "highway-1", true, false).claim();
            Claim plain = service.placeServerPolygon(world, strip(500, 600), "plaza", false, false).claim();

            assertThat(road.quiet()).isTrue();
            assertThat(plain.quiet()).isFalse();
        }

        @Test
        @DisplayName("quiet survives a restart")
        void quietIsSaved() {
            Claim road = service.placeServerPolygon(world, strip(0, 100), "highway-1", true, false).claim();

            Claim loaded = ClaimStorage.fromYaml(ClaimStorage.describe(road), "test");
            assertThat(loaded.quiet()).isTrue();
            assertThat(loaded.publicHas(LandAction.ANIMALS)).isTrue();
            assertThat(ClaimStorage.fromYaml(ClaimStorage.describe(
                    service.placeServerPolygon(world, strip(500, 600), "plaza", false, false).claim()), "test")
                    .quiet()).isFalse();
        }
    }

    @Nested
    @DisplayName("where claims overlap")
    class Overlap {

        @Test
        @DisplayName("of two claims of the same height the smaller one applies, whichever came first")
        void smallerWins() {
            Claim spawn = Claim.server(UUID.randomUUID(), "Spawn", WORLD, "world",
                    ClaimShape.rectangle(-200, -200, 200, 200, -64, 319));
            registry.add(spawn);
            Claim road = service.placeServerPolygon(world, strip(0, 100), "highway-1", true, false).claim();

            assertThat(registry.at(world, 50, 0, 10)).contains(road);
            assertThat(registry.at(world, 50, 0, 100)).contains(spawn);
        }
    }
}
