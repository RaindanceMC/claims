package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.rules.ScreenLineRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("What the claims' screens say")
class ScreenLineRuleTest {

    private final ScreenLineRule rule = new ScreenLineRule();
    private final UUID ann = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    /** A square of the given side, so its area is side*side. */
    private Claim claim(String name, UUID owner, int side) {
        ClaimShape shape = ClaimShape.rectangle(0, 0, side - 1, side - 1, 0, 64);
        return new Claim(UUID.randomUUID(), name, UUID.randomUUID(), "world", shape, owner);
    }

    @Test
    @DisplayName("the biggest claims come first, and only as many as asked for")
    void biggest() {
        Claim small = claim("small", ann, 10);
        Claim big = claim("big", bob, 40);
        Claim middle = claim("middle", ann, 20);

        assertThat(rule.biggest(List.of(small, big, middle), 2)).containsExactly(big, middle);
        assertThat(rule.biggest(List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("landowners are ranked by the ground they hold, counting each claim to its first owner")
    void landowners() {
        List<ScreenLineRule.Holding> ranked = rule.landowners(List.of(
                claim("a", ann, 10), claim("b", ann, 10), claim("c", bob, 12)), 5);

        assertThat(ranked).extracting(ScreenLineRule.Holding::owner).containsExactly(ann, bob);
        assertThat(ranked.get(0).claims()).isEqualTo(2);
        assertThat(ranked.get(0).blocks()).isEqualTo(200);
        assertThat(ranked.get(1).blocks()).isEqualTo(144);
        assertThat(rule.landowners(List.of(claim("a", ann, 10), claim("c", bob, 12)), 1)).hasSize(1);
    }

    @Test
    @DisplayName("blocks add up over a player's claims")
    void blocks() {
        assertThat(rule.blocks(List.of(claim("a", ann, 10), claim("b", ann, 5)))).isEqualTo(125);
        assertThat(rule.blocks(List.of())).isZero();
    }

    @Test
    @DisplayName("people trusted are counted once, however many of your claims they are on, and never yourself")
    void trusted() {
        UUID cy = UUID.randomUUID();
        Claim first = claim("a", ann, 10);
        Claim second = claim("b", ann, 10);
        first.memberOrCreate(bob);
        first.memberOrCreate(cy);
        second.memberOrCreate(bob);
        second.memberOrCreate(ann);

        assertThat(rule.trustedPeople(ann, List.of(first, second))).isEqualTo(2);
        assertThat(rule.trustedPeople(ann, List.of())).isZero();
    }

    @Test
    @DisplayName("places are coloured gold, grey, bronze, then white")
    void colour() {
        assertThat(rule.place(1)).isEqualTo("<gold>");
        assertThat(rule.place(2)).isEqualTo("<gray>");
        assertThat(rule.place(3)).isEqualTo("<#cd7f32>");
        assertThat(rule.place(4)).isEqualTo("<white>");
    }
}
