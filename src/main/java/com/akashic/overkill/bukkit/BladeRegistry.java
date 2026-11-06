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

import com.akashic.overkill.core.Arsenal;
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
            for (Blade b : Arsenal.DEFAULT_BLADES) {
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
                String combo = s.getString("combo", "katana");
                String art = s.getString("slash_art", "corte_dimensional");
                if (!Arsenal.COMBOS.containsKey(combo)) {
                    throw new IllegalArgumentException("unknown combo '" + combo + "' (" + Arsenal.COMBOS.keySet() + ")");
                }
                if (!Arsenal.SLASH_ARTS.containsKey(art)) {
                    throw new IllegalArgumentException(
                        "unknown slash art '" + art + "' (" + Arsenal.SLASH_ARTS.keySet() + ")");
                }
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
                    combo,
                    art,
                    s.getInt("refino_maximo", 50),
                    s.getDouble("dano_por_refino", 0.1));
                blades.put(b.id, b);
                materials.put(b.id, m);
            } catch (IllegalArgumentException e) {
                log.warning("blades.yml: blade '" + key + "' left out: " + e.getMessage());
            }
        }
        return new BladeRegistry(blades, materials);
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
