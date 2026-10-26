package de.raindancer.modules.claims.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.BuyableSlots;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.claims.WaystoneSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimWaystone;
import de.raindancer.modules.claims.rules.ClaimRightsRule;
import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.modules.claims.rules.WaystoneRule;
import de.raindancer.modules.claims.selection.ClaimsPack;
import de.raindancer.modules.claims.store.ClaimRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Waystones: putting them down, taking them up, and travelling between them inside one claim.
 *
 * <p>Every decision is {@link WaystoneRule}'s and the claim's rights; what is here is doing it — the two
 * barrier blocks that make a stone solid, the displays that make it look like one, the trip itself.
 *
 * <h2>The blocks</h2>
 * A stone is two barriers with displays over them. Barriers because nothing in survival breaks or moves them
 * — no pickaxe, piston or creeper — so the only way a stone goes is its owner taking it down, and the item
 * comes back to them. A world edited under a stone is noticed when its chunk next loads (see
 * {@link #chunkLoaded}).
 */
public final class WaystoneService {

    public static final String SLOT_SOURCE = "claims.waystone-slot";
    private static final LogChannel log = Log.of("claims");
    /** How near the stone somebody has to be still for the trip to count as from it. */
    private static final double REACH = 6.0;

    /** A stone and the claim it stands in. */
    public record Located(Claim claim, ClaimWaystone stone) {
    }

    private final Plugin plugin;
    private final ClaimRegistry claims;
    private final ClaimService claimService;
    private final FeatureRules features;
    private final ClaimRightsRule rights;
    private final Messages messages;
    private final BuyableSlots bought;
    private final Travel travel;
    private final WaystoneDisplays displays;
    private final WaystoneRule rule = new WaystoneRule();
    private final NamespacedKey itemTag;
    private volatile WaystoneSettings settings;

    public WaystoneService(Plugin plugin, ClaimRegistry claims, ClaimService claimService, FeatureRules features,
                           ClaimRightsRule rights, Messages messages, BuyableSlots bought, Travel travel,
                           WaystoneDisplays displays, WaystoneSettings settings) {
        this.plugin = plugin;
        this.claims = claims;
        this.claimService = claimService;
        this.features = features;
        this.rights = rights;
        this.messages = messages;
        this.bought = bought;
        this.travel = travel;
        this.displays = displays;
        this.settings = settings;
        this.itemTag = new NamespacedKey(plugin, "waystone-item");
    }

    public void settings(WaystoneSettings fresh) {
        this.settings = fresh;
    }

    public WaystoneSettings settings() {
        return settings;
    }

    public WaystoneRule rule() {
        return rule;
    }

    /** Whether the server lets claims have waystones at all. */
    public boolean offered() {
        return features.isOffered(ClaimFeature.WAYSTONES);
    }

    public boolean mayManage(Claim claim, Player player) {
        return rights.canManage(claim, player, ClaimAdminPermission.MANAGE_WAYSTONES);
    }

    // ------------------------------------------------------------------------ the item

    /** A waystone to put down. Lodestone underneath, so it places like a block; it never stays one. */
    public ItemStack item() {
        ItemStack stack = ItemStack.of(Material.LODESTONE);
        stack.editMeta(meta -> {
            meta.setItemModel(new NamespacedKey(ClaimsPack.WAYSTONE.namespace(), ClaimsPack.WAYSTONE.value()));
            meta.displayName(messages.get("waystone.item-name").decoration(
                    net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    messages.get("waystone.item-lore-1").decoration(
                            net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                    messages.get("waystone.item-lore-2").decoration(
                            net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(itemTag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    public boolean isWaystone(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(itemTag, PersistentDataType.BYTE);
    }

    /** Hands somebody one to put down. */
    public void give(Player player, Claim claim) {
        if (!offered()) {
            messages.send(player, "waystone.off");
            return;
        }
        if (!mayManage(claim, player)) {
            messages.send(player, "waystone.not-yours", "claim", claim.name());
            return;
        }
        for (ItemStack overflow : player.getInventory().addItem(item()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        messages.send(player, "waystone.given", "claim", claim.name(),
                "count", claim.waystones().size(), "limit", limitFor(claim));
    }

    // ------------------------------------------------------------------------ how many

    /** How many this claim may have: the settings, its owners' permissions, what its primary owner bought. */
    public int limitFor(Claim claim) {
        int perk = -1;
        for (UUID owner : claim.owners()) {
            Player online = plugin.getServer().getPlayer(owner);
            if (online != null) {
                List<String> nodes = new ArrayList<>();
                online.getEffectivePermissions().forEach(granted -> {
                    if (granted.getValue()) {
                        nodes.add(granted.getPermission());
                    }
                });
                perk = Math.max(perk, WaystoneRule.perkFrom(nodes));
            }
        }
        UUID payer = claim.primaryOwner();
        return rule.limit(settings, claim.shape().areaBlocks(), perk, payer == null ? 0 : bought.bought(payer));
    }

    public int bought(UUID player) {
        return bought.bought(player);
    }

    /** Why one more cannot be bought, in words for a greyed button; empty when it can. */
    public Optional<String> whyNotBuy(UUID player) {
        return bought.why(player, settings.slotTerms()).map(why -> switch (why) {
            case OFF -> "Buying waystones is switched off on this server.";
            case NO_PRICE -> "No price is set for a waystone.";
            case MAXED -> "You have bought as many waystones as anybody may.";
            default -> "";
        });
    }

    public String describeNextPrice(UUID player) {
        return Fees.format(bought.quote(player, settings.slotTerms()));
    }

    /** Buys one more waystone for every claim the buyer is the primary owner of. Off the server thread. */
    public void buy(Player buyer) {
        UUID who = buyer.getUniqueId();
        bought.buyOffThread(who, settings.slotTerms(), task -> Scheduling.async(plugin, task))
                .thenAccept(purchase -> Scheduling.entity(plugin, buyer, () -> {
                    switch (purchase.outcome()) {
                        case BOUGHT -> messages.send(buyer, "waystone.bought",
                                "price", Fees.format(purchase.paid()), "count", purchase.owned());
                        case OFF -> messages.send(buyer, "waystone.buy-off");
                        case NO_PRICE -> messages.send(buyer, "waystone.buy-no-price");
                        case MAXED -> messages.send(buyer, "waystone.buy-maxed",
                                "most", settings.mostBought());
                        case NOT_ENOUGH -> messages.send(buyer, "waystone.buy-cannot-afford",
                                "price", describeNextPrice(who));
                        case NO_ECONOMY -> messages.send(buyer, "slot.no-economy");
                        case NOT_SAVED -> messages.send(buyer, "slot.not-saved");
                        case REFUSED -> messages.send(buyer, "slot.refused");
                    }
                }));
    }

    // ------------------------------------------------------------------------ finding them

    public Optional<Located> at(Block block) {
        return at(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public Optional<Located> at(World world, int x, int y, int z) {
        for (Claim claim : claims.nearby(world, x, z, 0)) {
            if (!claim.worldId().equals(world.getUID())) {
                continue;
            }
            Optional<ClaimWaystone> stone = claim.waystones().at(x, y, z);
            if (stone.isPresent()) {
                return Optional.of(new Located(claim, stone.get()));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------ putting one down

    /**
     * Puts a stone down at {@code block}, the block a placed lodestone would have filled. The caller has
     * already cancelled the placement; this either builds the stone and takes the item, or says why not.
     */
    public boolean place(Player player, Block block, ItemStack hand) {
        if (!offered()) {
            messages.send(player, "waystone.off");
            return false;
        }
        Optional<Claim> claim = claims.at(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (claim.isEmpty()) {
            messages.send(player, "waystone.outside");
            return false;
        }
        Claim here = claim.get();
        if (!mayManage(here, player)) {
            messages.send(player, "waystone.not-yours", "claim", here.name());
            return false;
        }
        Block above = block.getRelative(0, 1, 0);
        // The block itself as well: placing happens a tick after the click, and something may have filled it.
        boolean roomAbove = (above.getType().isAir() || above.isReplaceable())
                && (block.getType().isAir() || block.isReplaceable());
        int limit = limitFor(here);
        switch (rule.placement(settings, here, block.getX(), block.getY(), block.getZ(), roomAbove, limit)) {
            case OUTSIDE -> {
                messages.send(player, "waystone.outside-height", "claim", here.name());
                return false;
            }
            case BLOCKED -> {
                messages.send(player, "waystone.no-room");
                return false;
            }
            case TOO_CLOSE -> {
                messages.send(player, "waystone.too-close", "blocks", settings.minDistance());
                return false;
            }
            case FULL -> {
                messages.send(player, "waystone.full", "claim", here.name(), "limit", limit);
                return false;
            }
            case OK -> {
                // falls through to building it
            }
        }
        // Facing whoever put it down, so its front is the side they were looking at.
        float facing = player.getLocation().getYaw() + 180f;
        ClaimWaystone stone = ClaimWaystone.at(here.waystones().freeName("Waystone"),
                block.getX(), block.getY(), block.getZ(), facing, settings.defaultAudience());
        if (!here.waystones().add(stone)) {
            messages.send(player, "waystone.no-room");
            return false;
        }
        block.setType(Material.BARRIER, false);
        above.setType(Material.BARRIER, false);
        displays.show(block.getWorld(), stone);
        here.markDirty();
        claimService.saveAsync(here);
        hand.setAmount(hand.getAmount() - 1);
        messages.send(player, "waystone.placed", "name", stone.name(), "claim", here.name(),
                "count", here.waystones().size(), "limit", limit);
        return true;
    }

    // ------------------------------------------------------------------------ changing one

    /** Takes a stone down and gives its item back. */
    public void takeDown(Player player, Claim claim, ClaimWaystone stone) {
        if (!mayManage(claim, player)) {
            messages.send(player, "waystone.not-yours", "claim", claim.name());
            return;
        }
        if (!remove(claim, stone)) {
            return;
        }
        for (ItemStack overflow : player.getInventory().addItem(item()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        messages.send(player, "waystone.taken-down", "name", stone.name());
    }

    public void rename(Player player, Claim claim, ClaimWaystone stone, String wanted) {
        if (!mayManage(claim, player)) {
            messages.send(player, "waystone.not-yours", "claim", claim.name());
            return;
        }
        String name = wanted == null ? "" : wanted.strip();
        if (name.isEmpty() || name.length() > 32) {
            messages.send(player, "waystone.bad-name");
            return;
        }
        if (!claim.waystones().replace(current(claim, stone).named(name))) {
            messages.send(player, "waystone.name-taken", "name", name);
            return;
        }
        claim.markDirty();
        claimService.saveAsync(claim);
        messages.send(player, "waystone.renamed", "name", name);
    }

    public void openTo(Player player, Claim claim, ClaimWaystone stone, LandAudience audience) {
        if (!mayManage(claim, player)) {
            messages.send(player, "waystone.not-yours", "claim", claim.name());
            return;
        }
        claim.waystones().replace(current(claim, stone).openTo(audience));
        claim.markDirty();
        claimService.saveAsync(claim);
    }

    private static ClaimWaystone current(Claim claim, ClaimWaystone stone) {
        return claim.waystones().byId(stone.id()).orElse(stone);
    }

    private boolean remove(Claim claim, ClaimWaystone stone) {
        return remove(claim, stone, true);
    }

    /**
     * Takes a stone out of the claim and out of the world. @return whether it was there
     *
     * @param save false for a claim being deleted — saving it now would write its file back after the delete
     */
    private boolean remove(Claim claim, ClaimWaystone stone, boolean save) {
        if (!claim.waystones().remove(stone.id())) {
            return false;
        }
        if (save) {
            claim.markDirty();
            claimService.saveAsync(claim);
        }
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world != null) {
            Scheduling.region(plugin, new Location(world, stone.x(), stone.y(), stone.z()), () -> {
                displays.hide(world, stone);
                clearBlocks(world, stone);
            });
        }
        return true;
    }

    private static void clearBlocks(World world, ClaimWaystone stone) {
        for (int dy = 0; dy <= 1; dy++) {
            Block block = world.getBlockAt(stone.x(), stone.y() + dy, stone.z());
            if (block.getType() == Material.BARRIER) {
                block.setType(Material.AIR, false);
            }
        }
    }

    // ------------------------------------------------------------------------ travelling

    /**
     * Sends somebody to {@code to}.
     *
     * @param from the stone they used, or null when travelling from the claim's waystone list — then they must
     *             be standing in the claim, which is the whole point: these are not warps from anywhere
     */
    public void travel(Player traveller, Claim claim, ClaimWaystone from, ClaimWaystone to) {
        World world = plugin.getServer().getWorld(claim.worldId());
        UUID who = traveller.getUniqueId();
        if (world == null || !traveller.getWorld().equals(world)) {
            messages.send(traveller, "waystone.not-here", "claim", claim.name());
            return;
        }
        ClaimWaystone target = claim.waystones().byId(to.id()).orElse(null);
        ClaimWaystone origin = from == null ? null : claim.waystones().byId(from.id()).orElse(null);
        if (target == null || (from != null && origin == null)) {
            messages.send(traveller, "waystone.gone");
            return;
        }
        Location standing = traveller.getLocation();
        boolean inside = claim.shape().containsBlock(standing.getBlockX(), standing.getBlockY(), standing.getBlockZ());
        boolean nearOrigin = origin != null && origin.distanceTo(standing.getBlockX(), standing.getBlockY(),
                standing.getBlockZ()) <= REACH;
        if (origin == null ? !inside : !nearOrigin) {
            messages.send(traveller, "waystone.not-here", "claim", claim.name());
            return;
        }
        if (!rule.mayUse(claim, who, origin, target)) {
            messages.send(traveller, "waystone.not-for-you", "name", target.name());
            return;
        }
        ClaimWaystone.Arrival arrival = target.arrival();
        Location destination = new Location(world, arrival.x(), arrival.y(), arrival.z(), arrival.yaw(), 0f);
        int warmup = Math.max(0, settings.warmupSeconds());
        if (origin != null) {
            ClaimWaystone lit = origin;
            Scheduling.region(plugin, new Location(world, lit.x(), lit.y(), lit.z()),
                    () -> displays.flare(world, lit, warmup * 20 + 10));
        }
        Trip trip = Trip.to(target.name()).after(warmup).searching(3).quiet();
        travel.go(traveller, destination, trip, new TravelWatcher() {
            @Override
            public void counting(Player player, int secondsLeft, Trip ignored) {
                player.sendActionBar(messages.get("waystone.counting", "name", target.name(),
                        "seconds", secondsLeft));
            }

            @Override
            public void arrived(Player player, Location where, Trip ignored) {
                player.sendActionBar(Component.empty());
                Scheduling.region(plugin, new Location(world, target.x(), target.y(), target.z()),
                        () -> displays.arrived(world, target));
            }

            @Override
            public void cancelled(Player player, TravelReason why, Trip ignored) {
                messages.send(player, why == TravelReason.HURT ? "waystone.cancelled-hurt"
                        : "waystone.cancelled-moved");
            }

            @Override
            public void refused(Player player, TravelReason why, Trip ignored) {
                messages.send(player, why == TravelReason.ALREADY_TRAVELLING ? "waystone.already-travelling"
                        : "waystone.nowhere-safe", "name", target.name());
            }
        });
    }

    // ------------------------------------------------------------------------ the world coming and going

    /**
     * A chunk came in: puts up the displays of the stones in it, a tick later so the chunk is whole.
     *
     * <p>A stone whose barriers are gone was edited out from under it while nobody watched. If the two
     * blocks are free it is simply rebuilt; if something else stands there now, the stone is dropped with a
     * line in the log, rather than knocking out whatever was built.
     */
    public void chunkLoaded(Chunk chunk) {
        List<Located> inChunk = stonesIn(chunk);
        if (inChunk.isEmpty()) {
            return;
        }
        World world = chunk.getWorld();
        Scheduling.regionLater(plugin, new Location(world, chunk.getX() << 4, 64, chunk.getZ() << 4), 1L, () -> {
            if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) {
                return;
            }
            for (Located located : inChunk) {
                ClaimWaystone stone = located.stone();
                Block base = world.getBlockAt(stone.x(), stone.y(), stone.z());
                Block top = base.getRelative(0, 1, 0);
                if (!standable(base) || !standable(top)) {
                    log.warn("The waystone '{}' in {} was built over while its chunk was unloaded; it is gone.",
                            stone.name(), located.claim().name());
                    located.claim().waystones().remove(stone.id());
                    located.claim().markDirty();
                    claimService.saveAsync(located.claim());
                    continue;
                }
                base.setType(Material.BARRIER, false);
                top.setType(Material.BARRIER, false);
                displays.show(world, stone);
            }
        });
    }

    public void chunkUnloaded(Chunk chunk) {
        for (Located located : stonesIn(chunk)) {
            displays.forget(located.stone().id());
        }
    }

    /** At start: the stones in chunks already loaded, each on its own region. */
    public void showLoaded() {
        for (Claim claim : claims.all()) {
            World world = plugin.getServer().getWorld(claim.worldId());
            if (world == null) {
                continue;
            }
            for (ClaimWaystone stone : claim.waystones().all()) {
                Location at = new Location(world, stone.x(), stone.y(), stone.z());
                Scheduling.region(plugin, at, () -> {
                    if (world.isChunkLoaded(stone.x() >> 4, stone.z() >> 4)) {
                        displays.show(world, stone);
                    }
                });
            }
        }
    }

    /** At stop: every display down. The barriers stay — the stones are still there, unlit. */
    public void hideAll() {
        displays.hideAll(id -> {
            for (Claim claim : claims.all()) {
                if (claim.waystones().byId(id).isPresent()) {
                    return plugin.getServer().getWorld(claim.worldId());
                }
            }
            return null;
        });
    }

    /** A claim was redrawn: the stones now outside it come down. Their items are not owed to anybody. */
    public void reshaped(Claim claim) {
        for (ClaimWaystone stone : claim.waystones().all()) {
            boolean inside = claim.shape().containsBlock(stone.x(), stone.y(), stone.z())
                    && claim.shape().containsBlock(stone.x(), stone.y() + 1, stone.z());
            if (!inside) {
                remove(claim, stone);
            }
        }
    }

    /** A claim was given up: all its stones come down, so none is left standing on somebody else's ground. */
    public void deleted(Claim claim) {
        for (ClaimWaystone stone : claim.waystones().all()) {
            remove(claim, stone, false);
        }
    }

    private List<Located> stonesIn(Chunk chunk) {
        List<Located> found = new ArrayList<>();
        World world = chunk.getWorld();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        for (Claim claim : claims.nearby(world, (cx << 4) + 8, (cz << 4) + 8, 0)) {
            if (!claim.worldId().equals(world.getUID()) || claim.waystones().isEmpty()) {
                continue;
            }
            for (ClaimWaystone stone : claim.waystones().all()) {
                if (stone.x() >> 4 == cx && stone.z() >> 4 == cz) {
                    found.add(new Located(claim, stone));
                }
            }
        }
        return found;
    }

    private static boolean standable(Block block) {
        return block.getType() == Material.BARRIER || block.getType().isAir() || block.isReplaceable();
    }
}
