package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Claims are written when they changed, never lost and never stale")
class ClaimPersistenceTest {

    @TempDir
    Path folder;

    private ClaimRegistry registry;
    private ClaimService service;

    @BeforeEach
    void setUp() {
        // A plugin that is not enabled runs "async" work in place, which keeps the writes deterministic.
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        registry = new ClaimRegistry();
        service = new ClaimService(plugin, registry, mock(ZoneRegistry.class), new ClaimStorage(folder),
                ClaimSettings.DEFAULTS, new CostService(), mock(ClaimRightsRule.class));
    }

    private Claim claim(String name) {
        Claim claim = new Claim(UUID.randomUUID(), name, UUID.randomUUID(), "world",
                ClaimShape.rectangle(0, 0, 15, 15, 0, 64), UUID.randomUUID());
        registry.add(claim);
        return claim;
    }

    private Path file(Claim claim) {
        return folder.resolve("claims").resolve(claim.id() + ".yml");
    }

    @Test
    @DisplayName("saveDirty writes only what changed and then leaves nothing dirty")
    void saveDirtyWritesOnlyChanged() {
        Claim changed = claim("changed");
        Claim untouched = claim("untouched");
        untouched.clearDirty();

        assertThat(service.saveDirty()).isEqualTo(1);

        assertThat(file(changed)).exists();
        assertThat(file(untouched)).doesNotExist();
        assertThat(changed.dirty()).isFalse();
        assertThat(service.saveDirty()).isZero();
    }

    @Test
    @DisplayName("flushDirty writes before it returns, so a shutdown or a quit cannot lose it")
    void flushDirtyIsSynchronous() throws Exception {
        Claim claim = claim("pantry");
        claim.clearDirty();
        claim.name("renamed");
        claim.markDirty();

        assertThat(service.flushDirty()).isEqualTo(1);

        assertThat(Files.readString(file(claim))).contains("renamed");
        assertThat(claim.dirty()).isFalse();
    }

    @Test
    @DisplayName("a claim changed again while it was being saved stays dirty for the next pass")
    void changeDuringSaveIsNotForgotten() {
        Claim claim = claim("busy");
        service.saveAsync(claim);
        claim.markDirty();
        assertThat(service.saveDirty()).isEqualTo(1);
    }

    @Test
    @DisplayName("a delete is final: a save that was still queued does not bring the file back")
    void deleteBeatsAStaleSave() {
        Claim claim = claim("gone");
        service.saveAsync(claim);
        assertThat(file(claim)).exists();

        service.delete(claim, null);
        service.saveAsync(claim);

        assertThat(file(claim)).doesNotExist();
    }
}
