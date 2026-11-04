package de.raindancer.modules.claims.util;

import de.raindancer.core.platform.util.Wrapping;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Small fluent builder for GUI icons. */
public final class Items {

    private final ItemStack stack;
    private final List<Component> lore = new ArrayList<>();

    private Items(Material material, int amount) {
        this.stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
    }

    private Items(ItemStack existing) {
        this.stack = existing.clone();
        List<Component> current = this.stack.lore();
        if (current != null) {
            lore.addAll(current);
        }
    }

    public static Items of(Material material) {
        return new Items(material, 1);
    }

    public static Items of(Material material, int amount) {
        return new Items(material, amount);
    }

    public static Items copyOf(ItemStack existing) {
        return new Items(existing);
    }

    public Items name(String miniMessage) {
        stack.editMeta(meta -> meta.displayName(de.raindancer.core.ui.menu.Icons.name(miniMessage)));
        return this;
    }

    public Items name(Component component) {
        stack.editMeta(meta -> meta.displayName(component.decoration(TextDecoration.ITALIC, false)));
        return this;
    }

    public Items lore(String... lines) {
        for (String line : lines) {
            lore.add(de.raindancer.core.ui.menu.Icons.loreLine(line));
        }
        return this;
    }

    public Items lore(Component line) {
        lore.add(line.decoration(TextDecoration.ITALIC, false));
        return this;
    }

    public Items loreRaw(List<Component> lines) {
        for (Component line : lines) {
            lore.add(line.decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    public Items blank() {
        lore.add(Component.empty());
        return this;
    }

    /** Wraps a long sentence into lore lines of roughly {@code width} characters. */
    public Items wrapped(String text, int width, NamedTextColor color) {
        for (String line : Wrapping.wrap(text, width)) {
            lore.add(Component.text(line, color).decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    public Items amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        return this;
    }

    public Items glint(boolean enabled) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(enabled));
        return this;
    }

    public Items hideAttributes() {
        stack.editMeta(meta -> meta.addItemFlags(ItemFlag.values()));
        return this;
    }

    public Items skullOf(UUID owner) {
        stack.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(owner)));
        return this;
    }

    public Items tag(NamespacedKey key, String value) {
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value));
        return this;
    }

    public Items itemModel(net.kyori.adventure.key.Key model) {
        stack.editMeta(meta -> meta.setItemModel(new NamespacedKey(model.namespace(), model.value())));
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) {
            stack.lore(new ArrayList<>(lore));
        }
        return stack;
    }

    public static boolean hasTag(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public static String tagValue(ItemStack stack, NamespacedKey key) {
        if (stack == null) {
            return null;
        }
        return stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** Copies the item meta of {@code source} onto a fresh stack of the same type. */
    public static ItemStack describe(ItemStack source, List<Component> extraLore) {
        ItemStack copy = source.clone();
        copy.editMeta(meta -> {
            List<Component> combined = new ArrayList<>();
            List<Component> existing = meta.lore();
            if (existing != null) {
                combined.addAll(existing);
            }
            combined.addAll(extraLore);
            meta.lore(combined);
        });
        return copy;
    }

    public static List<Component> loreOf(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return List.of();
        }
        return meta.lore();
    }

    /** @deprecated call {@link Wrapping#wrap(String, int)} directly. */
    @Deprecated
    public static List<String> split(String text, int width) {
        return Wrapping.wrap(text, width);
    }

    public static List<Material> woolColors() {
        return Arrays.asList(
                Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
                Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
                Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
                Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL);
    }
}
