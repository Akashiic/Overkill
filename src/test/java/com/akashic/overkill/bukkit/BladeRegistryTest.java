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
    void loreShowsTheStats() {
        Blade b = BladeRegistry.DEFAULTS.get(4); // Kusanagi: Judgement Cut
        List<String> lore = BladeItems.lore(b, new BladeStats(12, 30, 2, 5));
        assertTrue(lore.get(0)
            .endsWith("12"));
        assertTrue(lore.get(1)
            .endsWith("30"));
        assertTrue(lore.get(2)
            .endsWith("+2"));
        assertTrue(lore.get(3)
            .contains("1495"));
        assertTrue(lore.get(4)
            .contains("Corte do Julgamento"));
        List<String> broken = BladeItems.lore(b, new BladeStats(0, 0, 0, b.durability));
        assertTrue(broken.get(3)
            .contains("QUEBRADA"));
    }
}
