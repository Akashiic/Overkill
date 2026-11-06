package com.akashic.overkill.bukkit;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import com.akashic.kunftig.api.bukkit.persistence.KunftigItems;
import com.akashic.kunftig.api.bukkit.persistence.NamespacedKey;
import com.akashic.kunftig.api.bukkit.persistence.PersistentDataContainer;
import com.akashic.kunftig.api.bukkit.persistence.PersistentDataType;
import com.akashic.overkill.core.Arsenal;
import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.SlashArt;

/**
 * Blades as items: which blade an item is and its {@link BladeStats}, kept in the item's persistent data (Kunftig's
 * {@code PersistentDataContainer}, the modern layout), plus the model id the pack draws (2D icon and 3D held model)
 * and a lore that shows the stats.
 */
public final class BladeItems {

    /** A blade in a hand: which one, and its stats as read now. */
    public static final class Held {

        public final Blade blade;
        public final BladeStats stats;
        public final ItemStack item;

        Held(Blade blade, BladeStats stats, ItemStack item) {
            this.blade = blade;
            this.stats = stats;
            this.item = item;
        }
    }

    private final Plugin plugin;
    private final BladeRegistry registry;
    private final NamespacedKey bladeKey;
    private final NamespacedKey killsKey;
    private final NamespacedKey soulKey;
    private final NamespacedKey refineKey;
    private final NamespacedKey wearKey;

    public BladeItems(Plugin plugin, BladeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        bladeKey = new NamespacedKey(plugin, "blade");
        killsKey = new NamespacedKey(plugin, "kills");
        soulKey = new NamespacedKey(plugin, "proud_soul");
        refineKey = new NamespacedKey(plugin, "refine");
        wearKey = new NamespacedKey(plugin, "wear");
    }

    /** A new blade item, with fresh stats. */
    public ItemStack create(Blade b) {
        ItemStack item = KunftigItems.asDataCapable(new ItemStack(registry.material(b)));
        KunftigItems.getData(item)
            .set(bladeKey, PersistentDataType.STRING, b.id);
        KunftigItems.setModel(item, new NamespacedKey(plugin, b.id));
        write(item, b, new BladeStats());
        return item;
    }

    /** The blade in {@code item}, or null when it is not one (or names a blade this server no longer has). */
    public Held read(ItemStack item) {
        if (item == null || !registry.isBaseMaterial(item.getType()) || !KunftigItems.canHoldData(item)) {
            return null;
        }
        PersistentDataContainer data = KunftigItems.getData(item);
        Blade b = registry.get(data.get(bladeKey, PersistentDataType.STRING));
        if (b == null) {
            return null;
        }
        BladeStats s = new BladeStats(
            get(data, killsKey),
            get(data, soulKey),
            get(data, refineKey),
            get(data, wearKey));
        return new Held(b, s, item);
    }

    private static int get(PersistentDataContainer data, NamespacedKey key) {
        Integer v = data.get(key, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** Stores the stats in the item and refreshes its name and lore. */
    public void write(ItemStack item, Blade b, BladeStats s) {
        PersistentDataContainer data = KunftigItems.getData(item);
        data.set(killsKey, PersistentDataType.INTEGER, s.kills);
        data.set(soulKey, PersistentDataType.INTEGER, s.proudSoul);
        data.set(refineKey, PersistentDataType.INTEGER, s.refine);
        data.set(wearKey, PersistentDataType.INTEGER, s.wear);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(ChatColor.RESET + ChatColor.translateAlternateColorCodes('&', b.name));
        meta.setLore(lore(b, s));
        KunftigItems.setItemMeta(item, meta);
    }

    static List<String> lore(Blade b, BladeStats s) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Abates: " + ChatColor.WHITE + s.kills);
        lore.add(ChatColor.GRAY + "Proud Soul: " + ChatColor.AQUA + s.proudSoul);
        lore.add(ChatColor.GRAY + "Refino: " + ChatColor.YELLOW + "+" + s.refine);
        if (s.broken(b)) {
            lore.add(ChatColor.RED + "" + ChatColor.BOLD + "QUEBRADA" + ChatColor.GRAY + " (/overkill reparar)");
        } else {
            lore.add(
                ChatColor.GRAY + "Durabilidade: "
                    + ChatColor.GREEN
                    + (b.durability - s.wear)
                    + ChatColor.GRAY
                    + "/"
                    + b.durability);
        }
        SlashArt art = Arsenal.SLASH_ARTS.get(b.slashArt);
        if (art != null) {
            lore.add(ChatColor.GRAY + "Slash Art: " + ChatColor.LIGHT_PURPLE + art.name);
        }
        lore.add(
            ChatColor.DARK_GRAY + "Dano: " + String.format(java.util.Locale.ROOT, "%.1f", b.attackDamage(s)));
        return lore;
    }
}
