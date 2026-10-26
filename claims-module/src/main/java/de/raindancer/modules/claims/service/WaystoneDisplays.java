package de.raindancer.modules.claims.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.claims.model.ClaimWaystone;
import de.raindancer.modules.claims.selection.ClaimsPack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * What a waystone looks like in the world: the stone and the fire in its brazier, both item displays.
 *
 * <h2>Never saved</h2>
 * The displays are not persistent. They go when their chunk unloads and are put back when it loads, so a
 * crash, a stone taken down while its chunk was unloaded or a world edited offline can never leave an orphan
 * standing — the stone list in the claim file is the only truth, and the displays only ever show it.
 *
 * <h2>The flame</h2>
 * Vanilla's own floor fire, so it flickers like any fire without a texture of ours. A display of its own,
 * apart from the stone, so it can flare and turn to soul fire while somebody is being carried off: the scale
 * changes through the display's interpolation, which the client animates smoothly at its own frame rate.
 *
 * <p>Everything here runs on the thread owning the stone's region; callers hop there first.
 */
public final class WaystoneDisplays {

    /** Where the brazier's floor is, in blocks above the stone's own block. */
    private static final float BRAZIER_FLOOR = 27.3f / 16f;
    private static final Vector3f CALM = new Vector3f(0.4f, 0.55f, 0.4f);
    private static final Vector3f FLARED = new Vector3f(0.42f, 0.95f, 0.42f);
    private static final int EASE_TICKS = 8;
    private static final long IDLE_EVERY_TICKS = 10L;

    private record Shown(UUID stone, UUID fire, ScheduledTask idle) {
    }

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final BooleanSupplier animated;
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    /** Stones flaring, and the server tick until which they stay so. */
    private final Map<UUID, Integer> flaring = new ConcurrentHashMap<>();

    public WaystoneDisplays(Plugin plugin, BooleanSupplier animated) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "waystone");
        this.animated = animated;
    }

    public NamespacedKey tag() {
        return tag;
    }

    /** Puts the stone and its fire up, unless they already stand. On the stone's region thread. */
    public void show(World world, ClaimWaystone stone) {
        if (world == null || shown.containsKey(stone.id())) {
            return;
        }
        Location at = new Location(world, stone.x() + 0.5, stone.y(), stone.z() + 0.5);
        if (!world.isChunkLoaded(stone.x() >> 4, stone.z() >> 4)) {
            return;
        }
        ItemDisplay base = world.spawn(at.clone().add(0, 0.5, 0), ItemDisplay.class, display -> {
            prepare(display, stone, ClaimsPack.WAYSTONE_BASE);
            display.setRotation(stone.yaw(), 0f);
        });
        ItemDisplay fire = null;
        ScheduledTask idle = null;
        if (animated.getAsBoolean()) {
            fire = world.spawn(at, ItemDisplay.class, display -> {
                prepare(display, stone, ClaimsPack.WAYSTONE_FIRE);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setTransformation(flame(CALM));
            });
            ItemDisplay flame = fire;
            idle = Scheduling.entityTimer(plugin, fire, 5L + (stone.id().hashCode() & 7), IDLE_EVERY_TICKS,
                    task -> crackle(flame, stone.id()), () -> shown.remove(stone.id()));
        }
        shown.put(stone.id(), new Shown(base.getUniqueId(), fire == null ? null : fire.getUniqueId(), idle));
    }

    /** Takes the stone's displays down. On the stone's region thread. */
    public void hide(World world, ClaimWaystone stone) {
        Shown was = shown.remove(stone.id());
        flaring.remove(stone.id());
        if (was == null || world == null) {
            return;
        }
        if (was.idle() != null) {
            was.idle().cancel();
        }
        remove(world, was.stone());
        remove(world, was.fire());
    }

    /** Forgets what stood in an unloaded chunk; the server has already thrown the displays away. */
    public void forget(UUID stoneId) {
        Shown was = shown.remove(stoneId);
        if (was != null && was.idle() != null) {
            was.idle().cancel();
        }
        flaring.remove(stoneId);
    }

    /** Takes every display down, for the module stopping. */
    public void hideAll(java.util.function.Function<UUID, World> worldOfStone) {
        for (UUID id : java.util.List.copyOf(shown.keySet())) {
            Shown was = shown.remove(id);
            if (was == null) {
                continue;
            }
            if (was.idle() != null) {
                was.idle().cancel();
            }
            World world = worldOfStone.apply(id);
            if (world != null) {
                remove(world, was.stone());
                remove(world, was.fire());
            }
        }
    }

    /** Whether this stone's displays are standing. */
    public boolean isShown(UUID stoneId) {
        return shown.containsKey(stoneId);
    }

    /**
     * The fire flares up and burns blue for {@code ticks}, with soul flames wound round the pillar.
     * Again before it runs out keeps it burning. On the stone's region thread.
     */
    public void flare(World world, ClaimWaystone stone, int ticks) {
        Shown at = shown.get(stone.id());
        if (at == null || at.fire() == null || world == null) {
            return;
        }
        if (!(world.getEntity(at.fire()) instanceof ItemDisplay fire)) {
            return;
        }
        int until = plugin.getServer().getCurrentTick() + Math.max(1, ticks);
        boolean already = flaring.put(stone.id(), until) != null;
        if (!already) {
            fire.setItemStack(item(ClaimsPack.WAYSTONE_SOUL_FIRE));
            ease(fire, FLARED);
            world.playSound(fire.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.7f, 1.4f);
            int[] step = {0};
            Scheduling.entityTimer(plugin, fire, 1L, 2L, task -> {
                Integer end = flaring.get(stone.id());
                if (end == null || plugin.getServer().getCurrentTick() >= end) {
                    flaring.remove(stone.id());
                    fire.setItemStack(item(ClaimsPack.WAYSTONE_FIRE));
                    ease(fire, CALM);
                    task.cancel();
                    return;
                }
                spiral(world, stone, step[0]++);
            });
        }
    }

    /** Somebody arrived: a short burst of blue at the stone they came to. On its region thread. */
    public void arrived(World world, ClaimWaystone stone) {
        if (world == null) {
            return;
        }
        Location top = new Location(world, stone.x() + 0.5, stone.y() + BRAZIER_FLOOR + 0.2, stone.z() + 0.5);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, top, 24, 0.25, 0.4, 0.25, 0.03);
        world.playSound(top, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 0.8f, 1.2f);
        flare(world, stone, 24);
    }

    private void prepare(ItemDisplay display, ClaimWaystone stone, Key model) {
        display.setPersistent(false);
        display.setItemStack(item(model));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.setViewRange(1.0f);
        display.getPersistentDataContainer().set(tag, PersistentDataType.STRING, stone.id().toString());
    }

    private static ItemStack item(Key model) {
        ItemStack stack = ItemStack.of(Material.LODESTONE);
        stack.editMeta(meta -> meta.setItemModel(new NamespacedKey(model.namespace(), model.value())));
        return stack;
    }

    /**
     * The flame model sits on the brazier's floor whatever its height: the model is centred on the display,
     * so its foot is half its scaled height below the translation.
     */
    private static Transformation flame(Vector3f scale) {
        return new Transformation(new Vector3f(0f, BRAZIER_FLOOR + scale.y * 0.5f, 0f), new Quaternionf(),
                new Vector3f(scale), new Quaternionf());
    }

    private static void ease(ItemDisplay fire, Vector3f scale) {
        fire.setInterpolationDelay(0);
        fire.setInterpolationDuration(EASE_TICKS);
        fire.setTransformation(flame(scale));
    }

    /** An ember now and then, so a fire nobody looks at directly still reads as burning. */
    private void crackle(ItemDisplay fire, UUID stoneId) {
        if (flaring.containsKey(stoneId)) {
            return;
        }
        Location top = fire.getLocation().add(0, BRAZIER_FLOOR + 0.15, 0);
        World world = fire.getWorld();
        if (Math.random() < 0.6) {
            world.spawnParticle(Particle.SMALL_FLAME, top, 1, 0.12, 0.05, 0.12, 0.004);
        }
        if (Math.random() < 0.15) {
            world.spawnParticle(Particle.SMOKE, top.add(0, 0.5, 0), 1, 0.05, 0.05, 0.05, 0.01);
        }
    }

    /** Soul flames climbing the pillar in two strands. */
    private static void spiral(World world, ClaimWaystone stone, int step) {
        double cx = stone.x() + 0.5;
        double cz = stone.z() + 0.5;
        for (int strand = 0; strand < 2; strand++) {
            double angle = step * 0.55 + strand * Math.PI;
            double height = (step % 12) / 12.0 * 1.9;
            world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                    cx + Math.cos(angle) * 0.62, stone.y() + 0.1 + height, cz + Math.sin(angle) * 0.62,
                    1, 0, 0, 0, 0);
        }
    }

    private static void remove(World world, UUID entity) {
        if (entity == null) {
            return;
        }
        Entity found = world.getEntity(entity);
        if (found != null) {
            found.remove();
        }
    }
}
