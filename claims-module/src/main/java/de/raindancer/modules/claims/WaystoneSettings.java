package de.raindancer.modules.claims;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.core.social.economy.BuyableSlots;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.world.protection.LandAudience;
import org.bukkit.Material;

/**
 * Waystones: how many a claim may have and how travelling between them feels.
 *
 * <p>A record of its own rather than more components on {@link ClaimSettings}, whose positional constructor is
 * already long enough that every added value has to be threaded through four copy methods. Whether claims may
 * have waystones at all is not here: that is the {@code waystones} feature policy, beside every other perk.
 */
@Settings(id = "claims-waystones", topics = {
        @Topic(path = "waystones", title = "Waystones", icon = Material.LODESTONE),
})
public record WaystoneSettings(

        @In("waystones") @Title("Waystones per claim") @Range(min = 0, max = 1000)
        @Describe("How many any claim may have. rainsclaims.waystones.<n> on an owner raises it for their claims.")
        @Key("waystones.per-claim")
        int perClaim,

        @In("waystones") @Title("One more per so many blocks") @Range(min = 0, max = 10_000_000)
        @Describe("A claim gets an extra waystone for every this many blocks of ground. 0 switches it off.")
        @Key("waystones.blocks-per-extra")
        int blocksPerExtra,

        @In("waystones") @Title("Most in one claim") @Range(min = 0, max = 1000)
        @Describe("A ceiling over everything else, permissions and bought ones included. 0 means none.")
        @Key("waystones.most-per-claim")
        int mostPerClaim,

        @In("waystones") @Title("Closest two may stand") @Range(min = 0, max = 64)
        @Describe("In blocks. Keeps a claim from being a wall of waystones.")
        @Key("waystones.min-distance")
        int minDistance,

        @In("waystones") @Title("Warm-up") @Range(min = 0, max = 30)
        @Describe("Seconds to stand still before being taken. Moving or being hurt calls it off.")
        @Key("waystones.warmup-seconds")
        int warmupSeconds,

        @In("waystones") @Title("Who may use a new one")
        @Describe("Until its owner changes it: OWNER, TRUSTED or VISITOR (everybody on the claim).")
        @Key("waystones.default-open-to")
        LandAudience defaultAudience,

        @In("waystones") @Title("Fire and particles")
        @Describe("The flame on top and its flare when used. Off leaves the stones standing, unlit.")
        @Key("waystones.animated")
        boolean animated,

        @In("waystones") @Title("Owners may buy more")
        @Describe("Extra waystones for every claim the buyer owns, for the price below. Needs an economy.")
        @Key("waystones.buying")
        boolean buying,

        @In("waystones") @Title("Price of one more")
        @Describe("Money, written like 500 or 1.5k. Kept for good.")
        @Key("waystones.price")
        String price,

        @In("waystones") @Title("Each further one costs more (percent)") @Range(min = 0, max = 1000)
        @Describe("0 keeps the price flat.")
        @Key("waystones.price-growth-percent")
        int priceGrowthPercent,

        @In("waystones") @Title("Most one player may buy") @Range(min = 0, max = 1000)
        @Describe("0 means no ceiling (the per-claim ceiling still holds).")
        @Key("waystones.most-bought")
        int mostBought
) {

    public static final WaystoneSettings DEFAULTS = new WaystoneSettings(
            2, 4096, 24, 4, 2, LandAudience.TRUSTED, true,
            false, "0", 0, 0);

    public WaystoneSettings {
        defaultAudience = defaultAudience == null ? LandAudience.TRUSTED : defaultAudience;
    }

    /** What buying one more costs, as Core's {@link BuyableSlots} reads it. */
    public BuyableSlots.Terms slotTerms() {
        return new BuyableSlots.Terms(buying, Fees.amount(price), priceGrowthPercent, mostBought);
    }
}
