package com.akashic.overkill.bukkit;

import java.util.Arrays;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import com.akashic.kunftig.api.bukkit.persistence.KunftigItems;
import com.akashic.kunftig.api.bukkit.persistence.NamespacedKey;
import com.akashic.kunftig.api.bukkit.persistence.PersistentDataContainer;
import com.akashic.kunftig.api.bukkit.persistence.PersistentDataType;
import com.akashic.overkill.core.SealTier;
import com.akashic.overkill.core.StoneTier;

/**
 * The forge's materials as items: the whetstones (pedras de afiar, one per tier, stacking by tier), the protection
 * talismans (ofuda, never stacking: each has its own id) and the tamahagane (the Ascension's steel). Optionally the
 * AkashicFishing fortification stones, seals and ascension stones count too: same tiers, same keys.
 */
public final class ForgeItems {

    static final String TAMAHAGANE = "tamahagane";
    private static final String AF = "akashicfishing";

    private final Plugin plugin;
    private final NamespacedKey stoneKey;
    private final NamespacedKey sealKey;
    private final NamespacedKey sealIdKey;
    private final NamespacedKey afStoneKey = new NamespacedKey(AF, "pedra");
    private final NamespacedKey afSealKey = new NamespacedKey(AF, "selo");
    private boolean acceptFishing = true;

    public ForgeItems(Plugin plugin) {
        this.plugin = plugin;
        stoneKey = new NamespacedKey(plugin, "pedra");
        sealKey = new NamespacedKey(plugin, "ofuda");
        sealIdKey = new NamespacedKey(plugin, "ofuda_id");
    }

    /** Whether the AkashicFishing stones and seals work at the forge. */
    public void acceptFishing(boolean accept) {
        this.acceptFishing = accept;
    }

    public static String stoneName(StoneTier t) {
        return "§" + t.colorCode() + "Pedra de Afiar " + t.displayName();
    }

    public static String sealName(SealTier t) {
        return "§" + t.colorCode() + "Ofuda de Proteção " + t.percent() + "%";
    }

    public static final String TAMAHAGANE_NAME = "§dTamahagane";

    public ItemStack stone(StoneTier tier, int amount) {
        ItemStack item = base(Material.FLINT, Math.max(1, Math.min(64, amount)));
        KunftigItems.getData(item)
            .set(stoneKey, PersistentDataType.STRING, tier.key());
        model(item, "forja/pedra_" + tier.key());
        meta(
            item,
            stoneName(tier),
            "§7Pedra de afiar para a §f/forja§7.",
            "§7Multiplica a chance de fortificar: §a×" + BladeItems.number(tier.multiplier()),
            "§8Gasta uma por tentativa.");
        return item;
    }

    public ItemStack seal(SealTier tier) {
        ItemStack item = base(Material.PAPER, 1);
        PersistentDataContainer data = KunftigItems.getData(item);
        data.set(sealKey, PersistentDataType.STRING, tier.key());
        data.set(sealIdKey, PersistentDataType.STRING, UUID.randomUUID()
            .toString());
        model(item, "forja/ofuda_" + tier.key());
        meta(
            item,
            sealName(tier),
            "§7Talismã de proteção para a §f/forja§7.",
            "§7Segura §f" + tier.percent() + "% §7das regressões de uma tentativa.",
            "§8Só é gasto quando segura a katana.");
        return item;
    }

    public ItemStack tamahagane(int amount) {
        ItemStack item = base(Material.IRON_INGOT, Math.max(1, Math.min(64, amount)));
        KunftigItems.getData(item)
            .set(stoneKey, PersistentDataType.STRING, TAMAHAGANE);
        model(item, "forja/" + TAMAHAGANE);
        meta(
            item,
            TAMAHAGANE_NAME,
            "§7O aço-joia das katanas lendárias.",
            "§7Na §f/forja§7, no espaço da pedra: a §dAscensão §7depois do §c+17§7.",
            "§8Gasto na tentativa.");
        return item;
    }

    private static ItemStack base(Material m, int amount) {
        return KunftigItems.asDataCapable(new ItemStack(m, amount));
    }

    private void model(ItemStack item, String path) {
        KunftigItems.setModel(item, new NamespacedKey(plugin, path));
    }

    private static void meta(ItemStack item, String name, String... lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        KunftigItems.setItemMeta(item, meta);
    }

    private static String get(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() == Material.AIR || !KunftigItems.canHoldData(item)) {
            return null;
        }
        return KunftigItems.getData(item)
            .get(key, PersistentDataType.STRING);
    }

    /** The whetstone's tier (ours, or an AkashicFishing fortification stone), or null. */
    public StoneTier stoneTier(ItemStack item) {
        String v = get(item, stoneKey);
        if (v == null && acceptFishing) {
            v = get(item, afStoneKey);
        }
        return v == null ? null : StoneTier.parse(v);
    }

    /** Whether this is a tamahagane (or an AkashicFishing ascension stone). */
    public boolean isTamahagane(ItemStack item) {
        if (TAMAHAGANE.equals(get(item, stoneKey))) {
            return true;
        }
        return acceptFishing && "ascensao".equals(get(item, afStoneKey));
    }

    /** The talisman's tier (ours, or an AkashicFishing seal), or null. */
    public SealTier sealTier(ItemStack item) {
        String v = get(item, sealKey);
        if (v == null && acceptFishing) {
            v = get(item, afSealKey);
        }
        return v == null ? null : SealTier.parse(v);
    }

    /** Whether the item is one of ours (kept out of crafting recipes). */
    public boolean isOurs(ItemStack item) {
        return get(item, stoneKey) != null || get(item, sealKey) != null;
    }
}
