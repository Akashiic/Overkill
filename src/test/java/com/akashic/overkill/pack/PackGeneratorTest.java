package com.akashic.overkill.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akashic.overkill.core.Arsenal;
import com.akashic.overkill.core.Combo;
import com.akashic.overkill.core.Move;

class PackGeneratorTest {

    @Test
    @SuppressWarnings("unchecked")
    void thePredictionWindowsAreTheServersWindows() {
        Map<String, Object> model = PackGenerator
            .bladeModel(Arsenal.DEFAULT_BLADES.get(0), Arsenal.KATANA, "katana_basica");
        Map<String, Object> states = (Map<String, Object>) model.get("estados");
        assertEquals("golpe_1", ((Map<String, Object>) states.get(Combo.IDLE)).get("ao_atacar"));
        Map<String, Object> draw = (Map<String, Object>) ((Map<String, Object>) states.get(Combo.DRAW)).get("ao_atacar");
        assertEquals("iaido", draw.get("estado"));
        assertEquals(Arsenal.KATANA.drawTicks - 1, draw.get("ate"));
        for (Move m : Arsenal.KATANA.moves()
            .values()) {
            Map<String, Object> s = (Map<String, Object>) states.get(m.id);
            Move next = Arsenal.KATANA.predicted(m);
            Map<String, Object> a = (Map<String, Object>) s.get("ao_atacar");
            if (next == null) {
                assertNull(a, m.id);
                continue;
            }
            assertEquals(next.id, a.get("estado"), m.id);
            assertEquals(m.cancelFrom, a.get("de"), m.id);
            assertEquals(m.cancelTo, a.get("ate"), m.id);
        }
        for (String art : Arsenal.SLASH_ARTS.keySet()) {
            assertTrue(states.containsKey(art), art);
        }
        assertTrue(((List<Object>) model.get("itens")).contains("minecraft:golden_sword"));
    }

    @Test
    void animationsLastAsLongAsTheMoves() {
        for (Move m : Arsenal.KATANA.moves()
            .values()) {
            assertEquals(m.total() / 20.0, PackGenerator.seconds(m.id), 1e-9, m.id);
        }
    }

    @Test
    void theCommittedPackIsUpToDate() throws Exception {
        File pack = new File("resourcepack");
        assertTrue(pack.isDirectory(), "run ./gradlew generatePack");
        assertEquals(java.util.Collections.emptyList(), PackCheck.check(pack));
    }

    @Test
    void artIsKeptUnlessForced() throws Exception {
        File dir = Files.createTempDirectory("overkill-pack-test")
            .toFile();
        new PackGenerator(dir, false).generate();
        File geo = new File(dir, "assets/overkill/geo/blade/katana.geo.json");
        Files.write(geo.toPath(), "artist's work".getBytes("UTF-8"));
        new PackGenerator(dir, false).generate();
        assertEquals("artist's work", new String(Files.readAllBytes(geo.toPath()), "UTF-8"));
        new PackGenerator(dir, true).generate();
        assertFalse(new String(Files.readAllBytes(geo.toPath()), "UTF-8").startsWith("artist"));
    }
}
