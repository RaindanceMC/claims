package de.raindancer.modules.claims.service;

import de.raindancer.core.ui.hologram.ScreenKind;
import de.raindancer.core.ui.hologram.Screens;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.rules.ScreenLineRule;
import de.raindancer.modules.claims.store.ClaimRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

/** The claims' screens for Core's {@code /screen}: everybody's own land, the biggest claims and landowners. */
public final class ClaimScreens {

    public static final List<String> KINDS = List.of("claims-you", "claims-biggest", "claims-landowners");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int SHOWN = 5;

    private final ClaimRegistry claims;
    private final ClaimNames names;
    private final ScreenLineRule rule = new ScreenLineRule();

    public ClaimScreens(ClaimRegistry claims, ClaimNames names) {
        this.claims = claims;
        this.names = names;
    }

    public void register(Screens screens, Plugin owner) {
        screens.register(owner, ScreenKind.personal("claims-you", "Your land",
                "Everybody's own claims: how many, how much ground, who is trusted.", Material.GRASS_BLOCK, this::you));
        screens.register(owner, ScreenKind.everyone("claims-biggest", "Biggest claims",
                "The biggest claims on the server and whose they are.", Material.FILLED_MAP, this::biggest));
        screens.register(owner, ScreenKind.everyone("claims-landowners", "Landowners",
                "Who holds the most ground.", Material.OAK_FENCE, this::landowners));
    }

    private Component you(ScreenKind.Viewer viewer) {
        List<Claim> mine = claims.ownedBy(viewer.id());
        StringBuilder text = new StringBuilder("<gold><bold>Your land</bold></gold>");
        if (mine.isEmpty()) {
            return MINI.deserialize(text + "\n<gray>No claims yet — <white>/claim");
        }
        text.append("\n<gray>Claims <white>").append(mine.size());
        text.append("\n<gray>Ground <white>").append(number(rule.blocks(mine))).append(" blocks");
        text.append("\n<gray>Trusted <white>").append(rule.trustedPeople(viewer.id(), mine)).append(" people");
        return MINI.deserialize(text.toString());
    }

    private Component biggest() {
        StringBuilder text = new StringBuilder("<gold><bold>Biggest claims</bold></gold>");
        List<Claim> top = rule.biggest(claims.all(), SHOWN);
        if (top.isEmpty()) {
            return MINI.deserialize(text + "\n<gray>No claims yet");
        }
        int place = 1;
        for (Claim claim : top) {
            text.append('\n').append(rule.place(place)).append(place).append(". ")
                    .append(MINI.escapeTags(claim.name()))
                    .append(" <dark_gray>· <gray>").append(MINI.escapeTags(names.primaryOwner(claim)))
                    .append(" <dark_gray>· <white>").append(number(claim.shape().areaBlocks()));
            place++;
        }
        return MINI.deserialize(text.toString());
    }

    private Component landowners() {
        StringBuilder text = new StringBuilder("<gold><bold>Landowners</bold></gold>");
        List<ScreenLineRule.Holding> top = rule.landowners(claims.all(), SHOWN);
        if (top.isEmpty()) {
            return MINI.deserialize(text + "\n<gray>Nobody owns land yet");
        }
        int place = 1;
        for (ScreenLineRule.Holding holding : top) {
            text.append('\n').append(rule.place(place)).append(place).append(". ")
                    .append(MINI.escapeTags(names.nameOfOwner(holding.owner())))
                    .append(" <dark_gray>· <gray>").append(holding.claims())
                    .append(holding.claims() == 1 ? " claim" : " claims")
                    .append(" <dark_gray>· <white>").append(number(holding.blocks()));
            place++;
        }
        return MINI.deserialize(text.toString());
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
