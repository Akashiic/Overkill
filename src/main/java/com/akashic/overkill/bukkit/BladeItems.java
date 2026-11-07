package com.akashic.overkill.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import com.akashic.kunftig.api.bukkit.persistence.KunftigItems;
import com.akashic.kunftig.api.bukkit.persistence.NamespacedKey;
import com.akashic.kunftig.api.bukkit.persistence.PersistentDataContainer;
import com.akashic.kunftig.api.bukkit.persistence.PersistentDataType;
import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.Forge;
import com.akashic.overkill.core.Leveling;
import com.akashic.overkill.core.Star;

/**
 * Blades as items: which blade an item is and its {@link BladeStats}, kept in the item's persistent data (Kunftig's
 * {@code PersistentDataContainer}), the model the pack draws, and a name and lore rebuilt from the data
 * ("Katana Sakurajima ★ +7 [Nv. 45]").
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
    private Leveling leveling = Leveling.DEFAULT;
    private final NamespacedKey bladeKey;
    private final NamespacedKey xpKey;
    private final NamespacedKey killsKey;
    private final NamespacedKey soulKey;
    private final NamespacedKey enhancementKey;
    private final NamespacedKey starKey;
    private final NamespacedKey wearKey;
    private final NamespacedKey makerKey;

    public BladeItems(Plugin plugin, BladeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        bladeKey = new NamespacedKey(plugin, "blade");
        xpKey = new NamespacedKey(plugin, "xp");
        killsKey = new NamespacedKey(plugin, "kills");
        soulKey = new NamespacedKey(plugin, "proud_soul");
        enhancementKey = new NamespacedKey(plugin, "fortificacao");
        starKey = new NamespacedKey(plugin, "estrela");
        wearKey = new NamespacedKey(plugin, "wear");
        makerKey = new NamespacedKey(plugin, "criador");
    }

    public void leveling(Leveling leveling) {
        this.leveling = leveling;
    }

    public Leveling leveling() {
        return leveling;
    }

    /** A new blade item with these stats (fresh ones: star rolled the first time it is held). */
    public ItemStack create(Blade b, BladeStats s) {
        ItemStack item = KunftigItems.asDataCapable(new ItemStack(registry.material(b)));
        KunftigItems.getData(item)
            .set(bladeKey, PersistentDataType.STRING, b.id);
        KunftigItems.setModel(item, new NamespacedKey(plugin, b.id));
        write(item, b, s);
        return item;
    }

    public ItemStack create(Blade b) {
        return create(b, new BladeStats());
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
        Long xp = data.get(xpKey, PersistentDataType.LONG);
        BladeStats s = new BladeStats(
            xp == null ? 0 : xp,
            get(data, killsKey),
            get(data, soulKey),
            get(data, enhancementKey),
            Star.parse(data.get(starKey, PersistentDataType.STRING)),
            get(data, wearKey),
            data.get(makerKey, PersistentDataType.STRING));
        return new Held(b, s, item);
    }

    private static int get(PersistentDataContainer data, NamespacedKey key) {
        Integer v = data.get(key, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** Stores the stats in the item, without touching its name and lore (cheap: for every hit). */
    public void writeData(ItemStack item, BladeStats s) {
        PersistentDataContainer data = KunftigItems.getData(item);
        data.set(xpKey, PersistentDataType.LONG, s.xp);
        data.set(killsKey, PersistentDataType.INTEGER, s.kills);
        data.set(soulKey, PersistentDataType.INTEGER, s.proudSoul);
        data.set(enhancementKey, PersistentDataType.INTEGER, s.enhancement);
        data.set(wearKey, PersistentDataType.INTEGER, s.wear);
        if (s.star != null) {
            data.set(starKey, PersistentDataType.STRING, s.star.key());
        }
        if (s.maker != null) {
            data.set(makerKey, PersistentDataType.STRING, s.maker);
        }
    }

    /** Stores the stats and rebuilds the name and lore. */
    public void write(ItemStack item, Blade b, BladeStats s) {
        writeData(item, s);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(name(b, s, leveling));
        meta.setLore(lore(b, s, leveling));
        KunftigItems.setItemMeta(item, meta);
    }

    /** The slash art's name in the lore, or null for none. */
    static String artName(String id) {
        switch (id) {
            case "judgement_cut":
                return "Corte do Julgamento";
            case "drive_horizontal":
                return "Onda Horizontal";
            case "drive_vertical":
                return "Onda Vertical";
            case "wave_edge":
                return "Fio da Tempestade";
            case "circle_slash":
                return "Corte Circular";
            case "void_slash":
                return "Corte do Vazio";
            case "sakura_end":
                return "Fim da Sakura";
            default:
                return null;
        }
    }

    /** "&6Katana Sakurajima &6★ &b+7 &8[Nv. 45]". */
    static String name(Blade b, BladeStats s, Leveling leveling) {
        StringBuilder n = new StringBuilder(ChatColor.RESET + ChatColor.translateAlternateColorCodes('&', b.name));
        if (s.star != null && s.star != Star.NORMAL) {
            n.append(' ')
                .append('§')
                .append(s.star.colorCode())
                .append('★');
        }
        if (s.enhancement > 0) {
            n.append(' ')
                .append(enhancementColor(s.enhancement))
                .append(Forge.label(s.enhancement));
        }
        n.append(ChatColor.DARK_GRAY)
            .append(" [Nv. ")
            .append(s.level(leveling))
            .append(']');
        return n.toString();
    }

    /** The colour of a fortification level: steel, blue (+7), gold (+10), red (+12), purple (+15), fire, prism. */
    static ChatColor enhancementColor(int n) {
        if (n >= Forge.ASCENSION) {
            return ChatColor.LIGHT_PURPLE;
        }
        if (n >= 17) {
            return ChatColor.RED;
        }
        if (n >= 15) {
            return ChatColor.DARK_PURPLE;
        }
        if (n >= 12) {
            return ChatColor.DARK_RED;
        }
        if (n >= 10) {
            return ChatColor.GOLD;
        }
        return n >= 7 ? ChatColor.AQUA : ChatColor.WHITE;
    }

    static String number(double v) {
        return String.format(Locale.ROOT, "%.2f", v)
            .replace('.', ',')
            .replaceAll(",?0+$", "");
    }

    static String thousands(long v) {
        return String.format(Locale.ROOT, "%,d", v)
            .replace(',', '.');
    }

    static List<String> lore(Blade b, BladeStats s, Leveling leveling) {
        List<String> lore = new ArrayList<>();
        int level = s.level(leveling);
        if (level >= Leveling.MAX_LEVEL) {
            lore.add(ChatColor.GRAY + "Nível " + ChatColor.GOLD + level + ChatColor.GRAY + " (máximo)");
        } else {
            long into = leveling.xpIntoLevel(s.xp);
            long need = leveling.xpToNext(level);
            int filled = (int) Math.min(10, into * 10 / Math.max(1, need));
            StringBuilder bar = new StringBuilder();
            bar.append(ChatColor.GREEN);
            for (int i = 0; i < 10; i++) {
                if (i == filled) {
                    bar.append(ChatColor.DARK_GRAY);
                }
                bar.append('▮');
            }
            lore.add(
                ChatColor.GRAY + "Nível " + ChatColor.WHITE + level + " " + bar + ChatColor.GRAY + " " + into + "/"
                    + need + " XP");
        }
        if (s.star == null) {
            lore.add(ChatColor.GRAY + "Estrela: " + ChatColor.DARK_GRAY + "sorteada ao empunhar");
        } else {
            lore.add(
                ChatColor.GRAY + "Estrela: " + "§" + s.star.colorCode() + (s.star == Star.NORMAL ? "" : "★ ")
                    + s.star.displayName() + ChatColor.DARK_GRAY + " (×" + number(s.star.multiplier()) + ")");
        }
        lore.add(
            ChatColor.GRAY + "Fortificação: " + enhancementColor(s.enhancement) + Forge.label(s.enhancement)
                + ChatColor.DARK_GRAY + " (×" + number(Forge.multiplier(s.enhancement)) + ")");
        lore.add(ChatColor.GRAY + "Dano: " + ChatColor.RED + number(b.attackDamage(s, leveling)));
        int durability = b.durability(s);
        if (s.broken(b)) {
            lore.add(ChatColor.RED + "" + ChatColor.BOLD + "QUEBRADA" + ChatColor.GRAY + " (repare na /forja)");
        } else {
            lore.add(
                ChatColor.GRAY + "Durabilidade: " + ChatColor.GREEN + (durability - s.wear) + ChatColor.GRAY + "/"
                    + durability);
        }
        lore.add(
            ChatColor.GRAY + "Abates: " + (s.kills >= Leveling.FIERCER_EDGE_KILLS ? ChatColor.DARK_PURPLE
                : ChatColor.WHITE) + thousands(s.kills));
        lore.add(ChatColor.GRAY + "Almas: " + ChatColor.AQUA + thousands(s.proudSoul));
        String art = artName(b.slashArt);
        if (art != null) {
            lore.add(
                ChatColor.GRAY + "Slash Art: " + ChatColor.LIGHT_PURPLE + art + ChatColor.DARK_GRAY + " ("
                    + BladeStats.ART_COST + " almas)");
        }
        if (s.maker != null) {
            lore.add(ChatColor.DARK_GRAY + "Forjada por " + s.maker);
        }
        return lore;
    }
}
