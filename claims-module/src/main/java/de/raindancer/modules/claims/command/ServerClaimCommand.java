package de.raindancer.modules.claims.command;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.rules.ClaimAreaRule;
import de.raindancer.modules.claims.selection.Selection;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.util.ClaimPermissions;
import de.raindancer.modules.claims.util.Subjects;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /serverclaim} — the server's own claims: spawn, a market square. Also {@code /claimadmin server}.
 *
 * <p>A command of its own as well as a word under {@code /claimadmin}, because the staff who look after spawn
 * hold {@code rainsclaims.admin.server} and not necessarily {@code rec.admin}, which gates all of that command.
 *
 * <p>Everything takes coordinates as well as the marking tool, so it works from the console — a server claim is
 * usually made by whoever sets the server up, before anybody is standing in it.
 */
public final class ServerClaimCommand implements IClaimCommand {

    @Override
    public String describe() {
        return "the server's own claims";
    }

    private static final List<String> WORDS = List.of("list", "create", "resize", "rename", "delete", "info",
            "flag", "public", "border", "trust", "untrust", "open");

    private final Supplier<ClaimServices> services;

    public ServerClaimCommand(Supplier<ClaimServices> services) {
        this.services = services;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(ClaimPermissions.SERVER_CLAIMS) || sender.hasPermission("rec.admin");
    }

    @Override
    public String permission() {
        return ClaimPermissions.SERVER_CLAIMS;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        run(source.getSender(), args);
    }

    /** The whole command, for {@code /claimadmin server} to hand its arguments to. */
    public void run(CommandSender sender, String[] args) {
        ClaimServices claims = services.get();
        if (sender instanceof Player player && !claims.rights().isServerClaimStaff(player)) {
            claims.messages().send(player, "error.not-allowed");
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                claims.screens().serverClaims(player);
            } else {
                list(claims, sender);
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(claims, sender);
            case "create" -> create(claims, sender, args);
            case "resize" -> resize(claims, sender, args);
            case "rename" -> rename(claims, sender, args);
            case "delete" -> delete(claims, sender, args);
            case "info" -> info(claims, sender, args);
            case "flag" -> flag(claims, sender, args);
            case "public" -> publicAction(claims, sender, args);
            case "border" -> border(claims, sender, args);
            case "trust" -> trust(claims, sender, args, true);
            case "untrust" -> trust(claims, sender, args, false);
            case "open", "menu" -> open(claims, sender, args);
            default -> claims.messages().send(sender, "server.usage");
        }
    }

    private void list(ClaimServices claims, CommandSender sender) {
        List<Claim> all = claims.claims().serverClaims();
        claims.messages().send(sender, "server.list-header", "count", String.valueOf(all.size()));
        for (Claim claim : all) {
            claims.messages().sendPlain(sender, "server.list-line",
                    "claim", claim.name(), "world", claim.worldName(),
                    "area", String.valueOf(claim.shape().areaBlocks()),
                    "x1", String.valueOf(claim.shape().minX()), "z1", String.valueOf(claim.shape().minZ()),
                    "x2", String.valueOf(claim.shape().maxX()), "z2", String.valueOf(claim.shape().maxZ()));
        }
    }

    /**
     * {@code create <name> [force]} hands a player the marking tool; {@code create <name> <world> <x1> <z1> <x2>
     * <z2> [force]} claims that rectangle outright, the full height of the world.
     */
    private void create(ClaimServices claims, CommandSender sender, String[] args) {
        if (args.length < 2) {
            claims.messages().send(sender, "claim.who", "usage", CREATE_USAGE);
            return;
        }
        String name = args[1];
        boolean force = endsWithForce(args);
        int given = args.length - (force ? 1 : 0);
        if (given == 2) {
            if (!(sender instanceof Player player)) {
                claims.messages().send(sender, "server.console-needs-coordinates");
                return;
            }
            if (claims.claims().serverClaim(name).isPresent()) {
                claims.messages().send(sender, "server.name-taken", "claim", name);
                return;
            }
            claims.selectionFlow().beginServer(player, Selection.Purpose.SERVER_CLAIM, name, null, force);
            claims.messages().send(player, "server.mark-out", "claim", name);
            return;
        }
        if (given != 7) {
            claims.messages().send(sender, "claim.who", "usage", CREATE_USAGE);
            return;
        }
        World world = claims.server().getWorld(args[2]);
        if (world == null) {
            claims.messages().send(sender, "error.world-missing", "world", args[2]);
            return;
        }
        Optional<ClaimShape> drawn = shapeFrom(claims, sender, Arrays.copyOfRange(args, 3, 7),
                world.getMinHeight(), world.getMaxHeight() - 1);
        if (drawn.isEmpty()) {
            return;
        }
        ClaimShape shape = drawn.get();
        ClaimService.Result result = claims.claimService().createServerClaim(world, shape, name, force);
        if (!result.success()) {
            claims.selectionFlow().reportServerFailure(sender, result.failure(), name, result.detail());
            return;
        }
        claims.messages().send(sender, "server.created",
                "claim", result.claim().name(),
                "area", String.valueOf(shape.areaBlocks()),
                "min-y", String.valueOf(shape.minY()),
                "max-y", String.valueOf(shape.maxY()));
    }

    /** {@code resize <name> [force]} with the tool, or {@code resize <name> <x1> <z1> <x2> <z2> [force]}. */
    private void resize(ClaimServices claims, CommandSender sender, String[] args) {
        Optional<Claim> found = serverClaim(claims, sender, args, RESIZE_USAGE);
        if (found.isEmpty()) {
            return;
        }
        Claim claim = found.get();
        boolean force = endsWithForce(args);
        int given = args.length - (force ? 1 : 0);
        if (given == 2) {
            if (!(sender instanceof Player player)) {
                claims.messages().send(sender, "server.console-needs-coordinates");
                return;
            }
            claims.selectionFlow().beginServer(player, Selection.Purpose.SERVER_RESHAPE, null, claim, force);
            claims.messages().send(player, "admin.reshape-started", "claim", claim.name());
            return;
        }
        if (given != 6) {
            claims.messages().send(sender, "claim.who", "usage", RESIZE_USAGE);
            return;
        }
        Optional<ClaimShape> drawn = shapeFrom(claims, sender, Arrays.copyOfRange(args, 2, 6),
                claim.shape().minY(), claim.shape().maxY());
        if (drawn.isEmpty()) {
            return;
        }
        ClaimShape shape = drawn.get();
        Optional<ClaimService.Failure> failure = claims.claimService().reshapeServerClaim(claim, shape, force);
        if (failure.isPresent()) {
            claims.selectionFlow().reportServerFailure(sender, failure.get(), claim.name(), "");
            return;
        }
        claims.messages().send(sender, "server.reshaped",
                "claim", claim.name(),
                "area", String.valueOf(shape.areaBlocks()),
                "min-y", String.valueOf(shape.minY()),
                "max-y", String.valueOf(shape.maxY()));
    }

    private void rename(ClaimServices claims, CommandSender sender, String[] args) {
        Optional<Claim> found = serverClaim(claims, sender, args, "/serverclaim rename <name> <new name>");
        if (found.isEmpty()) {
            return;
        }
        if (args.length < 3) {
            claims.messages().send(sender, "claim.who", "usage", "/serverclaim rename <name> <new name>");
            return;
        }
        String was = found.get().name();
        Optional<ClaimService.Failure> failure = claims.claimService().renameServerClaim(found.get(), args[2]);
        if (failure.isPresent()) {
            claims.selectionFlow().reportServerFailure(sender, failure.get(), args[2], "");
            return;
        }
        claims.messages().send(sender, "claim.renamed", "old", was, "claim", args[2]);
    }

    private void delete(ClaimServices claims, CommandSender sender, String[] args) {
        Optional<Claim> found = serverClaim(claims, sender, args, "/serverclaim delete <name>");
        if (found.isEmpty()) {
            return;
        }
        claims.claimService().delete(found.get(), null);
        claims.messages().send(sender, "admin.claim-deleted", "claim", found.get().name());
    }

    /** Where it is, who is trusted, and what every flag comes to for a visitor — the "is spawn safe" check. */
    private void info(ClaimServices claims, CommandSender sender, String[] args) {
        Optional<Claim> found = serverClaim(claims, sender, args, "/serverclaim info <name>");
        if (found.isEmpty()) {
            return;
        }
        Claim claim = found.get();
        claims.messages().send(sender, "admin.here-header", "claim", claims.names().qualified(claim));
        claims.messages().sendPlain(sender, "admin.here-owners", "owners", claims.names().allOwners(claim));
        claims.messages().sendPlain(sender, "admin.here-shape",
                "world", claim.worldName(),
                "area", String.valueOf(claim.shape().areaBlocks()),
                "min-y", String.valueOf(claim.shape().minY()),
                "max-y", String.valueOf(claim.shape().maxY()),
                "corners", String.valueOf(claim.shape().vertices().size()));
        claims.messages().sendPlain(sender, "server.info-bounds",
                "x1", String.valueOf(claim.shape().minX()), "z1", String.valueOf(claim.shape().minZ()),
                "x2", String.valueOf(claim.shape().maxX()), "z2", String.valueOf(claim.shape().maxZ()));
        claims.messages().sendPlain(sender, "server.info-border",
                "border", claim.borderAlwaysShown() ? "always shown" : "shown on entering");
        claims.messages().sendPlain(sender, "server.info-trusted",
                "people", String.valueOf(claim.members().size()),
                "permission", ClaimAreaRule.rolePermission(claim));
        List<String> open = new ArrayList<>();
        List<String> shut = new ArrayList<>();
        for (LandAction action : LandAction.values()) {
            (claim.area().may(null, action) ? open : shut).add(action.key());
        }
        claims.messages().sendPlain(sender, "server.info-visitors",
                "allowed", String.join(", ", open), "denied", String.join(", ", shut));
        claims.messages().sendPlain(sender, "admin.here-flags-header");
        for (LandFlag flag : LandFlag.values()) {
            if (!claims.flags().isEnforced(flag)) {
                continue;
            }
            Optional<Boolean> override = claim.flagOverride(flag, LandAudience.VISITOR);
            claims.messages().sendPlain(sender, "server.info-flag",
                    "flag", flag.key(),
                    "override", override.map(value -> value ? "allowed" : "denied").orElse("—"),
                    "verdict", claims.flags().isAllowed(claim.area(), flag, LandAudience.VISITOR)
                            ? "allowed" : "denied");
        }
    }

    /** {@code flag <name> <flag> <allow|deny|default>} — for everybody on the claim at once. */
    private void flag(ClaimServices claims, CommandSender sender, String[] args) {
        String usage = "/serverclaim flag <name> <flag> <allow|deny|default>";
        Optional<Claim> found = serverClaim(claims, sender, args, usage);
        if (found.isEmpty()) {
            return;
        }
        if (args.length < 4) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return;
        }
        Optional<LandFlag> flag = LandFlag.byKey(args[2]);
        if (flag.isEmpty()) {
            claims.messages().send(sender, "server.no-such-flag", "flag", args[2]);
            return;
        }
        Optional<Optional<Boolean>> value = switchWord(args[3]);
        if (value.isEmpty()) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return;
        }
        Claim claim = found.get();
        claim.setFlagOverride(flag.get(), null, value.get().orElse(null));
        claims.claimService().saveAsync(claim);
        claims.messages().send(sender, "server.flag-set", "claim", claim.name(), "flag", flag.get().key(),
                "value", args[3].toLowerCase(Locale.ROOT),
                "verdict", claims.flags().isAllowed(claim.area(), flag.get(), LandAudience.VISITOR)
                        ? "allowed" : "denied");
    }

    /** {@code public <name> <action> <allow|deny>} — what a visitor may do there, doors or buttons say. */
    private void publicAction(ClaimServices claims, CommandSender sender, String[] args) {
        String usage = "/serverclaim public <name> <action> <allow|deny>";
        Optional<Claim> found = serverClaim(claims, sender, args, usage);
        if (found.isEmpty()) {
            return;
        }
        if (args.length < 4) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return;
        }
        Optional<LandAction> action = LandAction.byKey(args[2]);
        Optional<Optional<Boolean>> value = switchWord(args[3]);
        if (action.isEmpty() || value.isEmpty() || value.get().isEmpty()) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return;
        }
        Claim claim = found.get();
        claim.setPublic(action.get(), value.get().get());
        claims.claimService().saveAsync(claim);
        claims.messages().send(sender, "server.public-set", "claim", claim.name(), "action", action.get().key(),
                "value", value.get().get() ? "allowed" : "denied");
    }

    /** {@code border <name> <on|off>} — whether everybody near it sees its border all the time. */
    private void border(ClaimServices claims, CommandSender sender, String[] args) {
        String usage = "/serverclaim border <name> <on|off>";
        Optional<Claim> found = serverClaim(claims, sender, args, usage);
        if (found.isEmpty()) {
            return;
        }
        Optional<Optional<Boolean>> value = args.length < 3 ? Optional.empty() : switchWord(args[2]);
        if (value.isEmpty() || value.get().isEmpty()) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return;
        }
        Claim claim = found.get();
        claim.borderAlwaysShown(value.get().get());
        claims.claimService().saveAsync(claim);
        claims.messages().send(sender, value.get().get() ? "server.border-on" : "server.border-off",
                "claim", claim.name());
    }

    /** Trusts a named person — a builder — with the usual package, or takes it away again. */
    private void trust(ClaimServices claims, CommandSender sender, String[] args, boolean trusting) {
        String usage = "/serverclaim " + (trusting ? "trust" : "untrust") + " <name> <player>";
        Optional<Claim> found = serverClaim(claims, sender, args, usage);
        if (found.isEmpty()) {
            return;
        }
        if (args.length < 3) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return;
        }
        Optional<UUID> who = Subjects.one(claims.server(), claims.messages(), sender, args[2]);
        if (who.isEmpty()) {
            return;
        }
        Claim claim = found.get();
        if (trusting) {
            claim.memberOrCreate(who.get()).applyDefaultTrust();
            claim.markDirty();
        } else if (!claim.removeMember(who.get())) {
            claims.messages().send(sender, "server.not-trusted", "player", args[2], "claim", claim.name());
            return;
        }
        claims.claimService().saveAsync(claim);
        claims.messages().send(sender, trusting ? "server.trusted" : "server.untrusted",
                "player", args[2], "claim", claim.name());
    }

    private void open(ClaimServices claims, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            claims.messages().send(sender, "error.players-only");
            return;
        }
        if (args.length < 2) {
            claims.screens().serverClaims(player);
            return;
        }
        serverClaim(claims, sender, args, "/serverclaim open <name>")
                .ifPresent(claim -> claims.screens().claim(player, claim));
    }

    // ------------------------------------------------------------------ parsing

    private Optional<Claim> serverClaim(ClaimServices claims, CommandSender sender, String[] args, String usage) {
        if (args.length < 2) {
            claims.messages().send(sender, "claim.who", "usage", usage);
            return Optional.empty();
        }
        Optional<Claim> claim = claims.claims().serverClaim(args[1]);
        if (claim.isEmpty()) {
            claims.messages().send(sender, "server.no-such-claim", "claim", args[1]);
        }
        return claim;
    }

    private static final String CREATE_USAGE =
            "/serverclaim create <name> [<world> <x1> <z1> <x2> <z2> | <world> circle <x> <z> <radius>] [force]";
    private static final String RESIZE_USAGE =
            "/serverclaim resize <name> [<x1> <z1> <x2> <z2> | circle <x> <z> <radius>] [force]";

    /** Four words: two opposite corners, or {@code circle <x> <z> <radius>} for a round claim. */
    private static Optional<ClaimShape> shapeFrom(ClaimServices claims, CommandSender sender, String[] words,
                                                  int minY, int maxY) {
        boolean round = words[0].equalsIgnoreCase("circle") || words[0].equalsIgnoreCase("round");
        Optional<int[]> numbers = numbers(claims, sender, round ? Arrays.copyOfRange(words, 1, 4) : words);
        if (numbers.isEmpty()) {
            return Optional.empty();
        }
        int[] n = numbers.get();
        if (!round) {
            return Optional.of(ClaimShape.rectangle(n[0], n[1], n[2], n[3], minY, maxY));
        }
        if (n[2] < 1) {
            claims.messages().send(sender, "server.not-a-number", "input", words[3]);
            return Optional.empty();
        }
        return Optional.of(ClaimShape.circle(n[0], n[1], n[2], minY, maxY));
    }

    private static boolean endsWithForce(String[] args) {
        return args.length > 2 && args[args.length - 1].equalsIgnoreCase("force");
    }

    private static Optional<int[]> numbers(ClaimServices claims, CommandSender sender, String[] raw) {
        int[] values = new int[raw.length];
        for (int at = 0; at < raw.length; at++) {
            try {
                values[at] = Integer.parseInt(raw[at]);
            } catch (NumberFormatException notANumber) {
                claims.messages().send(sender, "server.not-a-number", "input", raw[at]);
                return Optional.empty();
            }
        }
        return Optional.of(values);
    }

    /** allow/deny/default as on, off and "the server's default"; empty when it is none of them. */
    static Optional<Optional<Boolean>> switchWord(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "allow", "on", "true", "yes" -> Optional.of(Optional.of(true));
            case "deny", "off", "false", "no" -> Optional.of(Optional.of(false));
            case "default", "reset", "unset" -> Optional.of(Optional.empty());
            default -> Optional.empty();
        };
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return suggest(source.getSender(), args);
    }

    /** Tab completion, for {@code /claimadmin server} as well. */
    public List<String> suggest(CommandSender sender, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        if (args.length <= 1) {
            words.addAll(WORDS);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")) {
            services.get().claims().serverClaims().forEach(claim -> words.add(claim.name()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            services.get().server().getWorlds().forEach(world -> words.add(world.getName()));
            words.add("force");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("flag")) {
            for (LandFlag flag : LandFlag.values()) {
                words.add(flag.key());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("public")) {
            for (LandAction action : LandAction.values()) {
                words.add(action.key());
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("flag")) {
            words.addAll(List.of("allow", "deny", "default"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("border")) {
            words.addAll(List.of("on", "off"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("public")) {
            words.addAll(List.of("allow", "deny"));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return Subjects.suggest(services.get().server(), sender, args[2], who -> true);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("resize")) {
            words.addAll(List.of("circle", "force"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            words.add("circle");
        }
        words.removeIf(word -> !word.toLowerCase(Locale.ROOT).startsWith(typed));
        return words;
    }
}
