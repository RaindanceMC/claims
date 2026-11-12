package de.raindancer.modules.claims.model;

import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A server claim is safe ground: monsters neither walk in nor go for anybody inside. */
class ServerClaimMonstersTest {

    @Test
    void monstersStayOutAndLeaveEverybodyAlone() {
        Claim claim = Claim.server(UUID.randomUUID(), "spawn", UUID.randomUUID(), "world",
                ClaimShape.rectangle(0, 0, 10, 10, -64, 319));
        for (LandAudience who : LandAudience.values()) {
            assertThat(claim.flagOverride(LandFlag.MONSTER_ENTRY, who)).as(who.name()).isEqualTo(Optional.of(false));
            assertThat(claim.flagOverride(LandFlag.MONSTER_TARGETING, who)).as(who.name()).isEqualTo(Optional.of(false));
        }
    }
}
