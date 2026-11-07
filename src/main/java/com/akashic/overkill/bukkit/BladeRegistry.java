package com.akashic.overkill.bukkit;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import com.akashic.overkill.combo.SlashArt;
import com.akashic.overkill.core.Blade;

/**
 * The blades of this server, read from {@code blades.yml}: each entry becomes a {@link Blade} plus the vanilla item it
 * is made of. A bad entry is logged and left out; the rest still load.
 */
public final class BladeRegistry {

    private final Map<String, Blade> blades;
    private final Map<String, Material> materials;
    private final Set<Material> baseMaterials;

    private BladeRegistry(Map<String, Blade> blades, Map<String, Material> materials) {
        this.blades = Collections.unmodifiableMap(blades);
        this.materials = Collections.unmodifiableMap(materials);
        Set<Material> base = EnumSet.noneOf(Material.class);
        base.addAll(materials.values());
        this.baseMaterials = Collections.unmodifiableSet(base);
    }

    /** Reads {@code laminas} of blades.yml. */
    public static BladeRegistry read(ConfigurationSection root, Logger log) {
        Map<String, Blade> blades = new LinkedHashMap<>();
        Map<String, Material> materials = new LinkedHashMap<>();
        ConfigurationSection section = root == null ? null : root.getConfigurationSection("laminas");
        if (section == null) {
            log.warning("blades.yml has no 'laminas': using the built-in blades");
            for (Blade b : DEFAULTS) {
                blades.put(b.id, b);
                materials.put(b.id, Material.GOLD_SWORD);
            }
            return new BladeRegistry(blades, materials);
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String id = s.getString("id", key);
            try {
                String art = s.getString("slash_art", Blade.NO_ART);
                if (!Blade.NO_ART.equals(art) && SlashArt.byId(art) == null) {
                    throw new IllegalArgumentException("unknown slash art '" + art + "' (" + ARTS + ")");
                }
                int color = color(s.getString("cor", "#3333FF"));
                Material m = Material.matchMaterial(s.getString("item_base", "GOLD_SWORD"));
                if (m == null || !m.name()
                    .endsWith("_SWORD")) {
                    throw new IllegalArgumentException("item_base must be a sword (WOOD_SWORD ... DIAMOND_SWORD)");
                }
                Blade b = new Blade(
                    id,
                    s.getString("nome", id),
                    s.getDouble("dano_base", 6),
                    s.getInt("durabilidade", 600),
                    art,
                    color);
                blades.put(b.id, b);
                materials.put(b.id, m);
            } catch (IllegalArgumentException e) {
                log.warning("blades.yml: blade '" + key + "' left out: " + e.getMessage());
            }
        }
        return new BladeRegistry(blades, materials);
    }

    /** The slash arts a blade may have. */
    public static final String ARTS = "nenhuma, judgement_cut, drive_horizontal, drive_vertical, wave_edge, circle_slash, "
        + "void_slash, sakura_end";

    /** The five katanas, when blades.yml has none. */
    public static final List<Blade> DEFAULTS = Collections.unmodifiableList(
        java.util.Arrays.asList(
            new Blade("katana/aco", "&fKatana de Aço", 4, 600, Blade.NO_ART, 0x3333FF),
            new Blade("katana/serpente", "&aKatana da Serpente", 5, 800, "drive_vertical", 0x33CC77),
            new Blade("katana/sakurajima", "&6Katana Sakurajima", 6, 1000, "circle_slash", 0xFF6A2A),
            new Blade("katana/raijin", "&9Katana Raijin", 6, 1000, "wave_edge", 0x55AAFF),
            new Blade("katana/kusanagi", "&eKusanagi no Tsurugi", 7, 1500, "judgement_cut", 0xFFD966)));

    /** "#RRGGBB", "0xRRGGBB" or a number. */
    static int color(String s) {
        String v = s.trim();
        try {
            if (v.startsWith("#")) {
                return Integer.parseInt(v.substring(1), 16);
            }
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Integer.parseInt(v.substring(2), 16);
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cor must be #RRGGBB: " + s);
        }
    }

    public Blade get(String id) {
        return id == null ? null : blades.get(id);
    }

    public Material material(Blade b) {
        return materials.get(b.id);
    }

    /** Whether some blade is made of this item (a cheap first check before reading an item's data). */
    public boolean isBaseMaterial(Material m) {
        return m != null && baseMaterials.contains(m);
    }

    public Map<String, Blade> all() {
        return blades;
    }

    public List<String> ids() {
        return new java.util.ArrayList<>(blades.keySet());
    }
}
