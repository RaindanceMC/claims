package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.rules.ClaimAreaRule;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Claims the server itself holds — spawn, a market square — rather than a player.
 *
 * <p>Owned by nobody: no uuid stands in for "the server", so nothing that counts, bills or names a claim's
 * owner can mistake one for a player's. What makes it the server's is a marker on the claim itself.
 */
@DisplayName("Server claims")
class ServerClaimTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID BUILDER = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    private static Claim spawn() {
        return Claim.server(UUID.randomUUID(), "Spawn", WORLD, "world",
                ClaimShape.rectangle(-60, 540, 123, 706, -64, 319));
    }

    @Nested
    @DisplayName("the model")
    class Model {

        @Test
        @DisplayName("has no owner at all, and says it is the server's")
        void ownedByNobody() {
            Claim claim = spawn();

            assertThat(claim.isServerClaim()).isTrue();
            assertThat(claim.owners()).isEmpty();
            assertThat(claim.primaryOwner()).isNull();
            assertThat(claim.isOwner(STRANGER)).isFalse();
        }

        @Test
        @DisplayName("an ordinary claim is not the server's")
        void playerClaimsAreNot() {
            Claim claim = new Claim(UUID.randomUUID(), "home", WORLD, "world",
                    ClaimShape.rectangle(0, 0, 15, 15, 0, 64), PLAYER);

            assertThat(claim.isServerClaim()).isFalse();
        }

        @Test
        @DisplayName("nobody can be added as an owner — a server claim is handed over, not shared")
        void noCoOwners() {
            Claim claim = spawn();

            assertThat(claim.addOwner(PLAYER)).isFalse();
            assertThat(claim.owners()).isEmpty();
        }

        @Test
        @DisplayName("handing it to a player makes it an ordinary claim of theirs")
        void transferMakesItAPlayerClaim() {
            Claim claim = spawn();

            claim.transferTo(PLAYER);

            assertThat(claim.isServerClaim()).isFalse();
            assertThat(claim.owners()).containsExactly(PLAYER);
        }

        @Test
        @DisplayName("never lapses: there is no upkeep for it to fall behind on")
        void neverLapses() {
            Claim claim = spawn();

            claim.lapsed(true);

            assertThat(claim.lapsed()).isFalse();
        }

        @Test
        @DisplayName("refuses an entry fee — walking onto spawn is free")
        void noEntryFee() {
            Claim claim = spawn();

            claim.entryFee().enabled(true);
            claim.entryFee().type(de.raindancer.modules.claims.model.CostType.XP_LEVELS);
            claim.entryFee().amount(3);

            assertThat(claim.entryFee().enabled()).isTrue();
            assertThat(claim.chargesEntry()).isFalse();
        }
    }

    @Nested
    @DisplayName("fully protected by default")
    class Protection {

        @Test
        @DisplayName("a visitor may walk in, and not build, break, open, switch or use doors")
        void visitorsOnlyLook() {
            ClaimAreaRule rule = spawn().area();

            assertThat(rule.may(STRANGER, LandAction.ENTER)).isTrue();
            for (LandAction denied : new LandAction[]{LandAction.BUILD, LandAction.BREAK, LandAction.CONTAINERS,
                    LandAction.DOORS, LandAction.REDSTONE, LandAction.BUCKETS, LandAction.IGNITE,
                    LandAction.ITEM_FRAMES, LandAction.DAMAGE_ANIMALS, LandAction.FARMLAND}) {
                assertThat(rule.may(STRANGER, denied)).as(denied.name()).isFalse();
            }
        }

        @Test
        @DisplayName("PvP, fire, explosions, mob griefing and hostile spawning are switched off for everybody")
        void environmentIsOff() {
            Claim claim = spawn();

            for (LandFlag flag : Claim.SERVER_CLAIM_DENIED_FLAGS) {
                for (LandAudience audience : LandAudience.values()) {
                    assertThat(claim.flagOverride(flag, audience)).as(flag + " for " + audience).contains(false);
                }
            }
            assertThat(Claim.SERVER_CLAIM_DENIED_FLAGS).contains(LandFlag.PVP, LandFlag.FIRE_SPREAD,
                    LandFlag.EXPLOSIONS, LandFlag.EXPLOSION_DAMAGE, LandFlag.MOB_GRIEF, LandFlag.ENDERMAN_GRIEF,
                    LandFlag.MONSTER_SPAWNING);
        }

        @Test
        @DisplayName("a trusted builder builds, with exactly what they were given")
        void trustedBuilders() {
            Claim claim = spawn();
            claim.memberOrCreate(BUILDER).applyDefaultTrust();

            assertThat(claim.area().may(BUILDER, LandAction.BUILD)).isTrue();
            assertThat(claim.area().audienceOf(BUILDER)).isEqualTo(LandAudience.TRUSTED);
            assertThat(claim.area().may(STRANGER, LandAction.BUILD)).isFalse();
        }

        @Test
        @DisplayName("a role trusted on it — a permission node — counts as trusted while it holds")
        void trustedRoles() {
            Claim claim = spawn();
            ClaimAreaRule.roleTrust((who, trusted) -> who.equals(BUILDER) && trusted.name().equals("Spawn"));
            try {
                assertThat(claim.area().may(BUILDER, LandAction.BUILD)).isTrue();
                assertThat(claim.area().audienceOf(BUILDER)).isEqualTo(LandAudience.TRUSTED);
                assertThat(claim.area().may(STRANGER, LandAction.BUILD)).isFalse();
            } finally {
                ClaimAreaRule.roleTrust(null);
            }
        }

        @Test
        @DisplayName("role trust means nothing on a player's claim")
        void rolesAreServerClaimsOnly() {
            Claim home = new Claim(UUID.randomUUID(), "Spawn", WORLD, "world",
                    ClaimShape.rectangle(0, 0, 15, 15, 0, 64), PLAYER);
            ClaimAreaRule.roleTrust((who, trusted) -> true);
            try {
                assertThat(home.area().may(BUILDER, LandAction.BUILD)).isFalse();
            } finally {
                ClaimAreaRule.roleTrust(null);
            }
        }

        @Test
        @DisplayName("the permission a role is trusted by is named after the claim")
        void rolePermission() {
            assertThat(ClaimAreaRule.rolePermission(spawn())).isEqualTo("rainsclaims.server.trust.spawn");
        }
    }

    @Nested
    @DisplayName("who runs it")
    class Rights {

        private final de.raindancer.core.world.protection.Land land =
                mock(de.raindancer.core.world.protection.Land.class);
        private final ClaimRightsRule rights = new ClaimRightsRule(land);

        private org.bukkit.entity.Player staff(boolean serverClaims) {
            org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.hasPermission(de.raindancer.modules.claims.util.ClaimPermissions.SERVER_CLAIMS))
                    .thenReturn(serverClaims);
            return player;
        }

        @Test
        @DisplayName("whoever looks after the server's claims stands in for its owner — there, and only there")
        void serverClaimStaff() {
            org.bukkit.entity.Player keeper = staff(true);
            Claim home = new Claim(UUID.randomUUID(), "home", WORLD, "world",
                    ClaimShape.rectangle(0, 0, 15, 15, 0, 64), PLAYER);

            assertThat(rights.canManage(spawn(), keeper,
                    de.raindancer.modules.claims.model.ClaimAdminPermission.MANAGE_FLAGS)).isTrue();
            assertThat(rights.isOwnerOrServerAdmin(spawn(), keeper)).isTrue();
            assertThat(rights.canManage(home, keeper,
                    de.raindancer.modules.claims.model.ClaimAdminPermission.MANAGE_FLAGS)).isFalse();
        }

        @Test
        @DisplayName("an ordinary player does not, and is not let in by the claim having no owner")
        void nobodyElse() {
            org.bukkit.entity.Player player = staff(false);

            assertThat(rights.canManage(spawn(), player,
                    de.raindancer.modules.claims.model.ClaimAdminPermission.MANAGE_FLAGS)).isFalse();
            assertThat(rights.isOwnerOrServerAdmin(spawn(), player)).isFalse();
        }
    }

    @Nested
    @DisplayName("on disk")
    class Persistence {

        @Test
        @DisplayName("comes back as the server's, with its rules")
        void roundTrip() {
            Claim claim = spawn();
            claim.memberOrCreate(BUILDER).applyDefaultTrust();

            Claim loaded = ClaimStorage.fromYaml(ClaimStorage.describe(claim), "test");

            assertThat(loaded.isServerClaim()).isTrue();
            assertThat(loaded.owners()).isEmpty();
            assertThat(loaded.flagOverride(LandFlag.PVP, LandAudience.VISITOR)).contains(false);
            assertThat(loaded.area().may(BUILDER, LandAction.BUILD)).isTrue();
        }

        @Test
        @DisplayName("a file written before server claims existed is a player's claim, and says nothing new")
        void oldFilesAreUnchanged() {
            Claim home = new Claim(UUID.randomUUID(), "home", WORLD, "world",
                    ClaimShape.rectangle(0, 0, 15, 15, 0, 64), PLAYER);
            YamlConfiguration yaml = ClaimStorage.describe(home);

            assertThat(yaml.contains("server")).isFalse();
            Claim loaded = ClaimStorage.fromYaml(yaml, "test");
            assertThat(loaded.isServerClaim()).isFalse();
            assertThat(loaded.owners()).containsExactly(PLAYER);
        }
    }

    @Nested
    @DisplayName("by name")
    class Names {

        private ClaimRegistry registry;
        private ClaimNames names;
        private Claim spawn;

        @BeforeEach
        void setUp() {
            registry = new ClaimRegistry();
            names = new ClaimNames(registry, who -> who.equals(PLAYER) ? "Linus" : null);
            spawn = spawn();
            registry.add(spawn);
        }

        @Test
        @DisplayName("is the server's, never 'nobody' or 'somebody'")
        void readsAsServer() {
            assertThat(names.allOwners(spawn)).isEqualTo("Server");
            assertThat(names.primaryOwner(spawn)).isEqualTo("Server");
            assertThat(names.qualified(spawn)).isEqualTo("Server/Spawn");
            assertThat(names.possessive(spawn)).isEqualTo("Spawn");
        }

        @Test
        @DisplayName("Server/Spawn finds it, even with a player's claim of the same name")
        void resolvesQualified() {
            registry.add(new Claim(UUID.randomUUID(), "Spawn", WORLD, "world",
                    ClaimShape.rectangle(1000, 1000, 1015, 1015, 0, 64), PLAYER));

            assertThat(names.resolve("server/spawn", null).claim()).contains(spawn);
            assertThat(registry.serverClaim("SPAWN")).contains(spawn);
            assertThat(registry.serverClaims()).containsExactly(spawn);
        }

        @Test
        @DisplayName("is not counted against anybody's claim limit")
        void notCounted() {
            assertThat(registry.countOwned(PLAYER)).isZero();
            assertThat(registry.ownedBy(PLAYER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("made and redrawn by staff")
    class Service {

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
            service = new ClaimService(plugin, registry, mock(ZoneRegistry.class), new ClaimStorage(folder),
                    ClaimSettings.DEFAULTS, new CostService(), mock(ClaimRightsRule.class));
            world = mock(World.class);
            when(world.getUID()).thenReturn(WORLD);
            when(world.getName()).thenReturn("world");
        }

        private Claim playerHome() {
            Claim home = new Claim(UUID.randomUUID(), "home", WORLD, "world",
                    ClaimShape.rectangle(0, 600, 15, 615, -64, 319), PLAYER);
            registry.add(home);
            return home;
        }

        @Test
        @DisplayName("makes a fully protected server claim")
        void creates() {
            ClaimService.Result result = service.createServerClaim(world,
                    ClaimShape.rectangle(-60, 540, 123, 706, -64, 319), "Spawn", false);

            assertThat(result.success()).isTrue();
            assertThat(result.claim().isServerClaim()).isTrue();
            assertThat(result.claim().flagOverride(LandFlag.PVP, LandAudience.VISITOR)).contains(false);
            assertThat(registry.serverClaim("Spawn")).contains(result.claim());
            assertThat(result.claim().hasRecordedPayment()).isFalse();
        }

        @Test
        @DisplayName("refuses to overlap a player's claim unless staff force it")
        void overlapNeedsForce() {
            playerHome();

            ClaimService.Result refused = service.createServerClaim(world,
                    ClaimShape.rectangle(-60, 540, 123, 706, -64, 319), "Spawn", false);
            assertThat(refused.success()).isFalse();
            assertThat(refused.failure()).isEqualTo(ClaimService.Failure.OVERLAPS_CLAIM);
            assertThat(registry.serverClaims()).isEmpty();

            ClaimService.Result forced = service.createServerClaim(world,
                    ClaimShape.rectangle(-60, 540, 123, 706, -64, 319), "Spawn", true);
            assertThat(forced.success()).isTrue();
        }

        @Test
        @DisplayName("one server claim per name, and only usable names")
        void names() {
            service.createServerClaim(world, ClaimShape.rectangle(-60, 540, 123, 706, -64, 319), "Spawn", false);

            assertThat(service.createServerClaim(world, ClaimShape.rectangle(500, 500, 520, 520, -64, 319),
                    "spawn", false).failure()).isEqualTo(ClaimService.Failure.NAME_TAKEN);
            assertThat(service.createServerClaim(world, ClaimShape.rectangle(500, 500, 520, 520, -64, 319),
                    "not a name!", false).failure()).isEqualTo(ClaimService.Failure.NAME_INVALID);
        }

        @Test
        @DisplayName("a player's claim of the same name is no obstacle")
        void playerNamesDoNotCollide() {
            Claim home = new Claim(UUID.randomUUID(), "Spawn", WORLD, "world",
                    ClaimShape.rectangle(5000, 5000, 5015, 5015, 0, 64), PLAYER);
            registry.add(home);

            assertThat(service.createServerClaim(world, ClaimShape.rectangle(-60, 540, 123, 706, -64, 319),
                    "Spawn", false).success()).isTrue();
        }

        @Test
        @DisplayName("redrawing refuses an overlap unless forced, and keeps the claim the server's")
        void reshape() {
            Claim spawn = service.createServerClaim(world,
                    ClaimShape.rectangle(-60, 540, -10, 590, -64, 319), "Spawn", false).claim();
            playerHome();
            ClaimShape bigger = ClaimShape.rectangle(-60, 540, 123, 706, -64, 319);

            assertThat(service.reshapeServerClaim(spawn, bigger, false)).contains(ClaimService.Failure.OVERLAPS_CLAIM);
            assertThat(spawn.shape().areaBlocks()).isEqualTo(51L * 51L);

            assertThat(service.reshapeServerClaim(spawn, bigger, true)).isEmpty();
            assertThat(spawn.shape().areaBlocks()).isEqualTo(184L * 167L);
            assertThat(spawn.isServerClaim()).isTrue();
        }

        @Test
        @DisplayName("renaming keeps names unique among server claims")
        void rename() {
            Claim spawn = service.createServerClaim(world,
                    ClaimShape.rectangle(-60, 540, -10, 590, -64, 319), "Spawn", false).claim();
            service.createServerClaim(world, ClaimShape.rectangle(500, 500, 520, 520, -64, 319), "Market", false);

            assertThat(service.renameServerClaim(spawn, "market")).contains(ClaimService.Failure.NAME_TAKEN);
            assertThat(service.renameServerClaim(spawn, "Hub")).isEmpty();
            assertThat(registry.serverClaim("hub")).contains(spawn);
        }
    }
}
