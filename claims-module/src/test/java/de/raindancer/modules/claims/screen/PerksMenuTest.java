package de.raindancer.modules.claims.screen;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second click a perk got after auto-equip shipped with no way to keep it from feeding every
 * visitor — see {@link PerksMenu#opensAudiencePage} and its own javadoc for why this is a second click
 * rather than folded into the toggle.
 */
class PerksMenuTest {

    @ParameterizedTest
    @EnumSource(ClickType.class)
    void neverOpensForAFeatureWithNoAudienceToNarrow(ClickType click) {
        assertThat(PerksMenu.opensAudiencePage(click, false)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"RIGHT", "SHIFT_RIGHT"})
    void rightClickOpensItForAnAudienceAwareFeature(ClickType click) {
        assertThat(PerksMenu.opensAudiencePage(click, true)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"RIGHT", "SHIFT_RIGHT"}, mode = EnumSource.Mode.EXCLUDE)
    void everyOtherClickLeavesItAlone(ClickType click) {
        assertThat(PerksMenu.opensAudiencePage(click, true)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void theTimeStepsReadAsClockTime() {
        assertThat(java.util.Arrays.stream(PerksMenu.TIME_STEPS).mapToObj(PerksMenu::timeStep).toList())
                .containsExactly("+1 tick", "+1 min", "+10 min", "+1 hour");
        assertThat(PerksMenu.timeStep(-167)).isEqualTo("-10 min");
    }

    @org.junit.jupiter.api.Test
    void whoAPerkReachesReadsAsWords() {
        assertThat(PerksMenu.audienceWords(java.util.EnumSet.of(
                de.raindancer.core.world.protection.LandAudience.OWNER,
                de.raindancer.core.world.protection.LandAudience.TRUSTED))).isEqualTo("owners, trusted");
        assertThat(PerksMenu.audienceWords(java.util.EnumSet.allOf(
                de.raindancer.core.world.protection.LandAudience.class))).isEqualTo("everybody");
        assertThat(PerksMenu.audienceWords(java.util.EnumSet.noneOf(
                de.raindancer.core.world.protection.LandAudience.class))).isEqualTo("nobody");
    }
}
