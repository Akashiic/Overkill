package com.akashic.overkill.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.Leveling;
import com.akashic.overkill.core.Star;

class BladeRegistryTest {

    private static Logger logger(List<String> into) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {

            @Override
            public void publish(LogRecord r) {
                into.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return log;
    }

    @Test
    void readsBladesAndLeavesBadOnesOut() {
        MemoryConfiguration c = new MemoryConfiguration();
        c.set("laminas.boa.id", "katana/boa");
        c.set("laminas.boa.nome", "&fBoa");
        c.set("laminas.boa.item_base", "IRON_SWORD");
        c.set("laminas.boa.dano_base", 8.5);
        c.set("laminas.boa.slash_art", "drive_vertical");
        c.set("laminas.boa.cor", "#FF0000");
        c.set("laminas.machado.item_base", "IRON_AXE");
        c.set("laminas.arte.slash_art", "voar");
        c.set("laminas.Feia.dano_base", 1);
        List<String> warnings = new ArrayList<>();
        BladeRegistry r = BladeRegistry.read(c, logger(warnings));
        Blade b = r.get("katana/boa");
        assertEquals(8.5, b.baseDamage, 0);
        assertEquals("drive_vertical", b.slashArt);
        assertEquals(0xFF0000, b.color);
        assertEquals(600, b.durability, "default");
        assertEquals(Material.IRON_SWORD, r.material(b));
        assertTrue(r.isBaseMaterial(Material.IRON_SWORD));
        assertFalse(r.isBaseMaterial(Material.GOLD_SWORD));
        assertEquals(1, r.all()
            .size());
        assertEquals(3, warnings.size(), warnings.toString());
        assertNull(r.get(null));
    }

    @Test
    void withoutTheSectionTheBuiltInBladesLoad() {
        List<String> warnings = new ArrayList<>();
        BladeRegistry r = BladeRegistry.read(new MemoryConfiguration(), logger(warnings));
        assertEquals(BladeRegistry.DEFAULTS.size(), r.all()
            .size());
        assertEquals(1, warnings.size());
    }

    @Test
    void nameAndLoreShowTheStats() {
        Blade b = BladeRegistry.DEFAULTS.get(4); // Kusanagi: Judgement Cut, 7 base, 1500 durability
        BladeStats s = new BladeStats(Leveling.DEFAULT.totalXpFor(45), 1203, 4210, 7, Star.OURO, 5, "Steve");
        String name = BladeItems.name(b, s, Leveling.DEFAULT);
        assertTrue(name.contains("Kusanagi no Tsurugi"), name);
        assertTrue(name.contains("★"), name);
        assertTrue(name.contains("+7"), name);
        assertTrue(name.endsWith("[Nv. 45]"), name);
        List<String> lore = BladeItems.lore(b, s, Leveling.DEFAULT);
        String all = String.join("\n", lore);
        assertTrue(lore.get(0)
            .contains("Nível §f45"), lore.get(0));
        assertTrue(all.contains("Ouro"), all);
        assertTrue(all.contains("×1,28"), all);
        assertTrue(all.contains("1915§7/1920"), all);
        assertTrue(all.contains("1.203"), all);
        assertTrue(all.contains("4.210"), all);
        assertTrue(all.contains("Corte do Julgamento"), all);
        assertTrue(all.contains("Forjada por Steve"), all);
        BladeStats fresh = new BladeStats();
        assertTrue(String.join("\n", BladeItems.lore(b, fresh, Leveling.DEFAULT))
            .contains("sorteada ao empunhar"));
        fresh.wear = b.durability;
        assertTrue(String.join("\n", BladeItems.lore(b, fresh, Leveling.DEFAULT))
            .contains("QUEBRADA"));
    }
}
