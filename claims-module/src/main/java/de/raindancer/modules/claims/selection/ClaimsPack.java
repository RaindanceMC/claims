package de.raindancer.modules.claims.selection;

import de.raindancer.core.RainsCore;
import de.raindancer.core.content.pack.JarPack;
import de.raindancer.core.platform.log.Log;
import net.kyori.adventure.key.Key;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** The selection sticks' own looks, offered to Core's resource packs through {@code JarPack}. Sources in {@code art/}. */
public final class ClaimsPack {

    public static final Key CLAIM_STICK = Key.key("rainsclaims", "claim_stick");
    public static final Key NO_CLAIM_STICK = Key.key("rainsclaims", "no_claim_stick");
    static final String ROOT = "/de/raindancer/modules/claims/pack/";
    static final List<String> FILES = List.of(
            "pack.mcmeta",
            "assets/rainsclaims/items/claim_stick.json",
            "assets/rainsclaims/items/no_claim_stick.json",
            "assets/rainsclaims/models/item/claim_stick.json",
            "assets/rainsclaims/models/item/no_claim_stick.json",
            "assets/rainsclaims/textures/item/claim_stick.png",
            "assets/rainsclaims/textures/item/no_claim_stick.png");
    private static final String OWNER = "Claims";
    private static final String NAME = "items";

    private ClaimsPack() {
    }

    public static void contribute(Plugin plugin, RainsCore core) {
        try {
            JarPack.offer(plugin, core.resourcePacks(), ClaimsPack.class, ROOT, FILES, OWNER, NAME,
                    "The claim and no-claim selection sticks");
        } catch (IOException failure) {
            Log.of("claims").warn("The selection sticks' looks could not be written ({}); "
                    + "they work, but look like a plain stick and blaze rod.", failure.getMessage());
        }
    }

    public static void withdraw(RainsCore core) {
        core.resourcePacks().withdraw(OWNER.toLowerCase(Locale.ROOT) + ":" + NAME);
    }
}
