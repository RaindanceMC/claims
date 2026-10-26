package de.raindancer.modules.claims.selection;

import de.raindancer.core.RainsCore;
import de.raindancer.core.content.pack.JarPack;
import de.raindancer.core.platform.log.Log;
import net.kyori.adventure.key.Key;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** The selection sticks' and the waystones' own looks, offered to Core's resource packs through {@code JarPack}. Sources in {@code art/}. */
public final class ClaimsPack {

    public static final Key CLAIM_STICK = Key.key("rainsclaims", "claim_stick");
    public static final Key NO_CLAIM_STICK = Key.key("rainsclaims", "no_claim_stick");
    /** The waystone as an item: the whole thing, a small fire burning in the brazier. */
    public static final Key WAYSTONE = Key.key("rainsclaims", "claim_waystone");
    /** A placed waystone's stone, without the fire — the fire is a display of its own so it can flare. */
    public static final Key WAYSTONE_BASE = Key.key("rainsclaims", "claim_waystone_base");
    /** Vanilla's floor fire and soul fire, as items a display can show. */
    public static final Key WAYSTONE_FIRE = Key.key("rainsclaims", "claim_waystone_fire");
    public static final Key WAYSTONE_SOUL_FIRE = Key.key("rainsclaims", "claim_waystone_soul_fire");
    public static final String ROOT = "/de/raindancer/modules/claims/pack/";
    public static final List<String> FILES = List.of(
            "pack.mcmeta",
            "assets/rainsclaims/items/claim_stick.json",
            "assets/rainsclaims/items/no_claim_stick.json",
            "assets/rainsclaims/models/item/claim_stick.json",
            "assets/rainsclaims/models/item/no_claim_stick.json",
            "assets/rainsclaims/textures/item/claim_stick.png",
            "assets/rainsclaims/textures/item/no_claim_stick.png",
            "assets/rainsclaims/items/claim_waystone.json",
            "assets/rainsclaims/items/claim_waystone_base.json",
            "assets/rainsclaims/items/claim_waystone_fire.json",
            "assets/rainsclaims/items/claim_waystone_soul_fire.json",
            "assets/rainsclaims/models/block/claim_waystone.json",
            "assets/rainsclaims/models/block/claim_waystone_base.json",
            "assets/rainsclaims/textures/block/claim_waystone.png",
            "assets/rainsclaims/textures/block/claim_waystone_rune.png",
            "assets/rainsclaims/textures/block/claim_waystone_rune.png.mcmeta");
    private static final String OWNER = "Claims";
    private static final String NAME = "items";

    private ClaimsPack() {
    }

    public static void contribute(Plugin plugin, RainsCore core) {
        try {
            JarPack.offer(plugin, core.resourcePacks(), ClaimsPack.class, ROOT, FILES, OWNER, NAME,
                    "The claim selection sticks and the waystones");
        } catch (IOException failure) {
            Log.of("claims").warn("The selection sticks' looks could not be written ({}); "
                    + "they work, but look like a plain stick and blaze rod.", failure.getMessage());
        }
    }

    public static void withdraw(RainsCore core) {
        core.resourcePacks().withdraw(OWNER.toLowerCase(Locale.ROOT) + ":" + NAME);
    }
}
