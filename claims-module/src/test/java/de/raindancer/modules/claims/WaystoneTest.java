package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimMember;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.ClaimWaystone;
import de.raindancer.modules.claims.rules.WaystoneRule;
import de.raindancer.modules.claims.rules.WaystoneRule.Placement;
import de.raindancer.modules.claims.store.ClaimStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waystones: travelling between points inside one claim, and only there.
 *
 * <p>The part worth testing hard is who may go where. A waystone is a door that ignores walls, so a visitor
 * allowed into the courtyard must not be able to step from it into the vault.
 */
class WaystoneTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final WaystoneRule rule = new WaystoneRule();
    private Claim keep;

    @BeforeEach
    void setUp() {
        keep = new Claim(UUID.randomUUID(), "Keep", WORLD, "world",
                ClaimShape.rectangle(0, 0, 63, 63, 0, 128), OWNER);
        keep.putMember(new ClaimMember(FRIEND));
    }

    private ClaimWaystone stone(String name, int x, int z, LandAudience openTo) {
        return ClaimWaystone.at(name, x, 64, z, 0f, openTo);
    }

    @Nested
    @DisplayName("a waystone")
    class TheStone {

        @Test
        @DisplayName("stands two blocks tall: its own block and the one above")
        void occupies() {
            ClaimWaystone gate = stone("Gate", 5, 5, LandAudience.VISITOR);
            assertThat(gate.occupies(5, 64, 5)).isTrue();
            assertThat(gate.occupies(5, 65, 5)).isTrue();
            assertThat(gate.occupies(5, 66, 5)).isFalse();
            assertThat(gate.occupies(6, 64, 5)).isFalse();
        }

        @Test
        @DisplayName("lets through its own audience and everybody standing above it, nobody below")
        void admits() {
            ClaimWaystone vault = stone("Vault", 5, 5, LandAudience.TRUSTED);
            assertThat(vault.admits(LandAudience.OWNER)).isTrue();
            assertThat(vault.admits(LandAudience.TRUSTED)).isTrue();
            assertThat(vault.admits(LandAudience.VISITOR)).isFalse();
            assertThat(stone("Den", 1, 1, LandAudience.OWNER).admits(LandAudience.TRUSTED)).isFalse();
        }

        @Test
        @DisplayName("sets people down on the block in front of it, looking the way it faces")
        void arrival() {
            // facing south (yaw 0) → the block at z + 1
            ClaimWaystone south = ClaimWaystone.at("S", 10, 64, 10, 0f, LandAudience.VISITOR);
            assertThat(south.arrival().x()).isEqualTo(10.5);
            assertThat(south.arrival().z()).isEqualTo(11.5);
            assertThat(south.arrival().y()).isEqualTo(64.0);
            // facing east (yaw -90) → x + 1; a yaw a little off a quarter is rounded to it
            ClaimWaystone east = ClaimWaystone.at("E", 10, 64, 10, -80f, LandAudience.VISITOR);
            assertThat(east.arrival().x()).isEqualTo(11.5);
            assertThat(east.arrival().z()).isEqualTo(10.5);
            assertThat(east.arrival().yaw()).isEqualTo(-90f);
        }

        @Test
        @DisplayName("renaming and opening keep its id and place")
        void copies() {
            ClaimWaystone gate = stone("Gate", 5, 5, LandAudience.VISITOR);
            ClaimWaystone renamed = gate.named("Front gate").openTo(LandAudience.OWNER);
            assertThat(renamed.id()).isEqualTo(gate.id());
            assertThat(renamed.x()).isEqualTo(5);
            assertThat(renamed.name()).isEqualTo("Front gate");
            assertThat(renamed.audience()).isEqualTo(LandAudience.OWNER);
        }
    }

    @Nested
    @DisplayName("a claim's waystones")
    class TheSet {

        @Test
        @DisplayName("have names of their own, ignoring case, and never share a block")
        void unique() {
            assertThat(keep.waystones().add(stone("Gate", 5, 5, LandAudience.VISITOR))).isTrue();
            assertThat(keep.waystones().add(stone("gate", 9, 9, LandAudience.VISITOR))).isFalse();
            assertThat(keep.waystones().add(stone("Tower", 5, 5, LandAudience.VISITOR))).isFalse();
            assertThat(keep.waystones().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("suggest a free name for the next one")
        void freeName() {
            assertThat(keep.waystones().freeName("Waystone")).isEqualTo("Waystone");
            keep.waystones().add(stone("Waystone", 1, 1, LandAudience.VISITOR));
            keep.waystones().add(stone("Waystone 2", 2, 2, LandAudience.VISITOR));
            assertThat(keep.waystones().freeName("Waystone")).isEqualTo("Waystone 3");
        }

        @Test
        @DisplayName("are found by block, either of the two, and lost by id")
        void lookup() {
            ClaimWaystone gate = stone("Gate", 5, 5, LandAudience.VISITOR);
            keep.waystones().add(gate);
            assertThat(keep.waystones().at(5, 65, 5)).contains(gate);
            assertThat(keep.waystones().remove(gate.id())).isTrue();
            assertThat(keep.waystones().at(5, 64, 5)).isEmpty();
            assertThat(keep.waystones().remove(gate.id())).isFalse();
        }

        @Test
        @DisplayName("change only through replace, and replace keeps names unique")
        void replace() {
            ClaimWaystone gate = stone("Gate", 5, 5, LandAudience.VISITOR);
            ClaimWaystone tower = stone("Tower", 9, 9, LandAudience.VISITOR);
            keep.waystones().add(gate);
            keep.waystones().add(tower);
            assertThat(keep.waystones().replace(tower.named("GATE"))).isFalse();
            assertThat(keep.waystones().replace(tower.named("Tower top"))).isTrue();
            assertThat(keep.waystones().byId(tower.id()).orElseThrow().name()).isEqualTo("Tower top");
        }
    }

    @Nested
    @DisplayName("who may travel where")
    class Travel {

        private ClaimWaystone gate;
        private ClaimWaystone hall;
        private ClaimWaystone vault;

        @BeforeEach
        void stones() {
            gate = stone("Gate", 2, 2, LandAudience.VISITOR);
            hall = stone("Hall", 20, 20, LandAudience.TRUSTED);
            vault = stone("Vault", 40, 40, LandAudience.OWNER);
            keep.waystones().add(gate);
            keep.waystones().add(hall);
            keep.waystones().add(vault);
        }

        @Test
        @DisplayName("a stranger at the public gate is offered nothing past it")
        void strangers() {
            assertThat(rule.destinations(keep, STRANGER, gate)).isEmpty();
            assertThat(rule.mayUse(keep, STRANGER, gate, hall)).isFalse();
        }

        @Test
        @DisplayName("somebody trusted goes between the gate and the hall, never into the vault")
        void trusted() {
            assertThat(rule.destinations(keep, FRIEND, gate)).containsExactly(hall);
            assertThat(rule.destinations(keep, FRIEND, hall)).containsExactly(gate);
            assertThat(rule.mayUse(keep, FRIEND, gate, vault)).isFalse();
        }

        @Test
        @DisplayName("a stone you may not use yourself takes you nowhere, even to an open one")
        void bothEnds() {
            assertThat(rule.destinations(keep, FRIEND, vault)).isEmpty();
            assertThat(rule.mayUse(keep, FRIEND, vault, gate)).isFalse();
        }

        @Test
        @DisplayName("the owner goes anywhere, the list sorted by name and never the stone itself")
        void owner() {
            assertThat(rule.destinations(keep, OWNER, gate)).containsExactly(hall, vault);
            assertThat(rule.destinations(keep, OWNER, null)).containsExactly(gate, hall, vault);
        }

        @Test
        @DisplayName("somebody barred from the claim is offered nothing at all")
        void barred() {
            keep.ban(de.raindancer.modules.claims.model.ClaimBan.permanent(FRIEND, OWNER, "go"));
            assertThat(rule.destinations(keep, FRIEND, gate)).isEmpty();
        }
    }

    @Nested
    @DisplayName("how many a claim may have")
    class Limits {

        private final WaystoneSettings settings = WaystoneSettings.DEFAULTS;

        @Test
        @DisplayName("the base, one more per so many blocks of ground, and what was bought")
        void growsWithTheClaim() {
            int base = settings.perClaim();
            assertThat(rule.limit(settings, 100, -1, 0)).isEqualTo(base);
            assertThat(rule.limit(settings, settings.blocksPerExtra() * 2L, -1, 0)).isEqualTo(base + 2);
            assertThat(rule.limit(settings, 100, -1, 3)).isEqualTo(base + 3);
        }

        @Test
        @DisplayName("a permission replaces the base when it is higher, never when it is lower")
        void perk() {
            assertThat(rule.limit(settings, 100, settings.perClaim() + 4, 0)).isEqualTo(settings.perClaim() + 4);
            assertThat(rule.limit(settings, 100, 0, 0)).isEqualTo(settings.perClaim());
        }

        @Test
        @DisplayName("never above the server's ceiling, unlimited perk included")
        void ceiling() {
            assertThat(rule.limit(settings, Long.MAX_VALUE, Integer.MAX_VALUE, 50))
                    .isEqualTo(settings.mostPerClaim());
        }

        @Test
        @DisplayName("reads rainsclaims.waystones.<n> and .unlimited, the highest winning")
        void permissions() {
            assertThat(WaystoneRule.perkFrom(List.of("rainsclaims.waystones.4", "rainsclaims.waystones.9",
                    "other.node", "rainsclaims.waystones.typo"))).isEqualTo(9);
            assertThat(WaystoneRule.perkFrom(List.of("rainsclaims.waystones.unlimited")))
                    .isEqualTo(Integer.MAX_VALUE);
            assertThat(WaystoneRule.perkFrom(List.of())).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("where one may be put down")
    class Placing {

        private final WaystoneSettings settings = WaystoneSettings.DEFAULTS;

        @Test
        @DisplayName("inside the claim with room above it")
        void fine() {
            assertThat(rule.placement(settings, keep, 10, 64, 10, true, 3)).isEqualTo(Placement.OK);
        }

        @Test
        @DisplayName("not where its top would stick out of the claim, nor outside it")
        void outside() {
            Claim shallow = new Claim(UUID.randomUUID(), "Shallow", WORLD, "world",
                    ClaimShape.rectangle(0, 0, 20, 20, 60, 64), OWNER);
            assertThat(rule.placement(settings, shallow, 5, 64, 5, true, 3)).isEqualTo(Placement.OUTSIDE);
            assertThat(rule.placement(settings, keep, 100, 64, 10, true, 3)).isEqualTo(Placement.OUTSIDE);
        }

        @Test
        @DisplayName("not under a block")
        void blocked() {
            assertThat(rule.placement(settings, keep, 10, 64, 10, false, 3)).isEqualTo(Placement.BLOCKED);
        }

        @Test
        @DisplayName("not right beside another, and not past the limit")
        void crowdAndCount() {
            keep.waystones().add(stone("Gate", 10, 10, LandAudience.VISITOR));
            assertThat(rule.placement(settings, keep, 11, 64, 10, true, 3)).isEqualTo(Placement.TOO_CLOSE);
            assertThat(rule.placement(settings, keep, 40, 64, 40, true, 1)).isEqualTo(Placement.FULL);
        }
    }

    @Test
    @DisplayName("they are written with the claim and read back exactly")
    void storage() {
        ClaimWaystone gate = ClaimWaystone.at("Front gate", 5, 70, 6, -90f, LandAudience.VISITOR);
        ClaimWaystone vault = ClaimWaystone.at("Vault", 30, 12, 31, 180f, LandAudience.OWNER);
        keep.waystones().add(gate);
        keep.waystones().add(vault);

        YamlConfiguration yaml = ClaimStorage.describe(keep);
        Claim loaded = ClaimStorage.fromYaml(yaml, "test");

        assertThat(loaded.waystones().all()).containsExactly(gate, vault);
    }

    @Test
    @DisplayName("a claim file from before waystones has none and loads fine")
    void olderFiles() {
        YamlConfiguration yaml = ClaimStorage.describe(keep);
        yaml.set("waystones", null);
        assertThat(ClaimStorage.fromYaml(yaml, "test").waystones().all()).isEmpty();
    }
}
