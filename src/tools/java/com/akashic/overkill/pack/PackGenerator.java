package com.akashic.overkill.pack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import com.akashic.overkill.core.Arsenal;
import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.Combo;
import com.akashic.overkill.core.Move;
import com.akashic.overkill.core.SlashArt;

/**
 * Writes Overkill's resource pack from the plugin's own definitions ({@link Arsenal}): the 3D blade manifest
 * ({@code item_models_3d.json}, with each move's {@code ao_atacar} window taken from the same {@link Move} the server
 * plays), the 2D icons, the displays of the slash arts and summoned swords, and placeholder art (Bedrock geometry,
 * animations, textures) where none exists yet.
 *
 * <pre>
 * ./gradlew generatePack                       manifests always; art only where missing
 * ./gradlew generatePack --args="--force-art"  art too (overwrites what an artist made!)
 * </pre>
 *
 * Manifests are code: they must follow the moves. Art is a starting point: an artist replaces it in Blockbench,
 * keeping the state names, the bone names the manifest lists ({@code luz}, {@code translucido}) and each animation's
 * length ({@link #seconds}).
 */
public final class PackGenerator {

    static final String NS = "overkill";
    /** Every vanilla sword: a blade can be made of any (blades.yml), the renderer only draws items with a model. */
    static final List<String> SWORDS = Arrays.asList(
        "minecraft:wooden_sword",
        "minecraft:stone_sword",
        "minecraft:iron_sword",
        "minecraft:golden_sword",
        "minecraft:diamond_sword");

    private final File root;
    private final boolean forceArt;
    private int written;
    private int kept;

    PackGenerator(File root, boolean forceArt) {
        this.root = root;
        this.forceArt = forceArt;
    }

    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 && !args[0].startsWith("--") ? args[0] : "resourcepack");
        boolean force = Arrays.asList(args)
            .contains("--force-art");
        PackGenerator g = new PackGenerator(out, force);
        g.generate();
        System.out.println(
            "Overkill pack: " + g.written + " file(s) written, " + g.kept + " art file(s) kept, in " + out);
    }

    void generate() throws IOException {
        text(
            "pack.mcmeta",
            Json.write(obj("pack", obj("pack_format", 1, "description", "Overkill: lâminas e efeitos (Kunftig 9)"))),
            false);
        blades();
        displays();
    }

    // ================================================================================================================
    // Blades

    /** Blade look: blade, glow and fitting colors (RGB). */
    static final class Look {

        final int blade;
        final int glow;
        final int fitting;
        final int wrap;

        Look(int blade, int glow, int fitting, int wrap) {
            this.blade = blade;
            this.glow = glow;
            this.fitting = fitting;
            this.wrap = wrap;
        }
    }

    static Look look(String bladeId) {
        switch (bladeId) {
            case "katana/rubra":
                return new Look(0x5A1E22, 0xFF3344, 0x2A2A2A, 0x3A0E10);
            case "katana/tempestade":
                return new Look(0xC9D8E6, 0x4FD6FF, 0x3B5878, 0x1C2E44);
            default:
                return new Look(0xD8DDE2, 0xBFE6FF, 0xC9A23A, 0x2B2B33);
        }
    }

    private void blades() throws IOException {
        Combo combo = Arsenal.KATANA;
        Map<String, Object> models = new LinkedHashMap<>();
        Map<String, Object> icons = new LinkedHashMap<>();
        for (Blade b : Arsenal.DEFAULT_BLADES) {
            String slug = b.id.replace('/', '_');
            models.put(b.id, bladeModel(b, combo, slug));
            icons.put(b.id, NS + ":" + b.id);
            png("assets/overkill/textures/items/" + b.id + ".png", icon(look(b.id)), true);
            png("assets/overkill/textures/blade/" + slug + ".png", bladeTexture(look(b.id)), true);
        }
        text("assets/overkill/kunftig/item_models_3d.json", Json.write(obj("_formato", 1, "modelos", models)), false);
        text("assets/overkill/kunftig/item_models.json", Json.write(icons), false);
        text("assets/overkill/geo/blade/katana.geo.json", Json.write(katanaGeometry()), true);
        text("assets/overkill/animations/blade/katana.animation.json", Json.write(animations(combo, false)), true);
        text("assets/overkill/animations/blade/braco.animation.json", Json.write(animations(combo, true)), true);
    }

    /** One blade's entry in item_models_3d.json. */
    static Map<String, Object> bladeModel(Blade b, Combo combo, String slug) {
        Map<String, Object> states = new LinkedHashMap<>();
        Move entry = combo.predicted(null);
        states.put(
            Combo.IDLE,
            obj(
                "modelo",
                "animation.katana." + Combo.IDLE,
                "braco",
                "animation.braco." + Combo.IDLE,
                "laco",
                true,
                "ao_atacar",
                entry == null ? null : entry.id));
        states.put(
            Combo.DRAW,
            obj(
                "modelo",
                "animation.katana." + Combo.DRAW,
                "braco",
                "animation.braco." + Combo.DRAW,
                "ao_atacar",
                combo.quickDraw() == null ? null : obj("estado", combo.quickDraw(), "de", 0, "ate", combo.drawTicks - 1)));
        for (Move m : combo.moves()
            .values()) {
            Move next = combo.predicted(m);
            states.put(
                m.id,
                obj(
                    "modelo",
                    "animation.katana." + m.id,
                    "braco",
                    "animation.braco." + m.id,
                    "ao_atacar",
                    next == null ? null : obj("estado", next.id, "de", m.cancelFrom, "ate", m.cancelTo)));
        }
        for (SlashArt a : Arsenal.SLASH_ARTS.values()) {
            states.put(a.id, obj("modelo", "animation.katana." + a.id, "braco", "animation.braco." + a.id));
        }
        stripNulls(states);
        return obj(
            "nome",
            b.name,
            "itens",
            SWORDS,
            "geo",
            NS + ":geo/blade/katana.geo.json",
            "animacoes",
            NS + ":animations/blade/katana.animation.json",
            "braco",
            NS + ":animations/blade/braco.animation.json",
            "textura",
            NS + ":textures/blade/" + slug + ".png",
            "estados",
            states,
            "luz",
            list("brilho"),
            "translucido",
            list("brilho"),
            "transicao_ticks",
            obj("vara", 1),
            "pega",
            list(0, 3, 0),
            "display",
            obj(
                "primeira_pessoa",
                obj("rotation", list(20, -40, 15), "translation", list(-5, -4, -14), "scale", 0.3),
                "terceira_pessoa",
                obj("rotation", list(70, 0, 0), "translation", list(0, 0, 0), "scale", 0.6),
                "chao",
                obj("rotation", list(90, 0, 0), "translation", list(0, 0, 0), "scale", 0.45),
                "moldura",
                obj("rotation", list(0, 0, -45), "translation", list(0, 0, 0), "scale", 0.5)));
    }

    @SuppressWarnings("unchecked")
    private static void stripNulls(Map<String, Object> states) {
        for (Object v : states.values()) {
            ((Map<String, Object>) v).values()
                .removeIf(x -> x == null);
        }
    }

    /** The katana along +Y, the grip at the origin: handle, guard, blade, tip, and a glowing shell. */
    static Map<String, Object> katanaGeometry() {
        List<Object> bones = new ArrayList<>();
        bones.add(bone("katana", null, list(0, 0, 0)));
        bones.add(bone("cabo", "katana", list(0, 0, 0), cube(list(-1, -3, -1), list(2, 9, 2), list(0, 0), 0)));
        bones.add(bone("guarda", "katana", list(0, 6, 0), cube(list(-2, 6, -2), list(4, 1, 4), list(8, 0), 0)));
        bones.add(bone("lamina", "katana", list(0, 7, 0), cube(list(-0.5, 7, -1), list(1, 22, 2), list(0, 12), 0)));
        bones.add(bone("ponta", "lamina", list(0, 29, 0), cube(list(-0.5, 29, -0.5), list(1, 2, 1), list(8, 12), 0)));
        bones.add(bone("brilho", "lamina", list(0, 7, 0), cube(list(-0.5, 9, -1), list(1, 20, 2), list(16, 12), 0.12)));
        return geometry("geometry.overkill.katana", 64, 64, bones, 3);
    }

    // ---- animations ------------------------------------------------------------------------------------------------

    /** A pose of the blade (rotation and position of the root bone) and of the right arm. */
    static final class Pose {

        final List<Object> item;
        final List<Object> at;
        final List<Object> arm;

        Pose(List<Object> item, List<Object> at, List<Object> arm) {
            this.item = item;
            this.at = at;
            this.arm = arm;
        }
    }

    static Pose pose(double ix, double iy, double iz, double ax, double ay, double az) {
        return new Pose(list(ix, iy, iz), list(0, 0, 0), list(ax, ay, az));
    }

    static final Pose REST = pose(0, 0, 0, -15, 0, 5);

    /** The wind-up and strike of each kind of cut (placeholder choreography). */
    static Pose[] cut(String id) {
        switch (id) {
            case "golpe_1":
            case "aereo_1":
                return new Pose[] { pose(-60, 70, 0, -90, 50, 0), pose(-70, -80, 0, -80, -60, 0) };
            case "golpe_2":
            case "aereo_2":
                return new Pose[] { pose(-60, -80, 0, -80, -60, 0), pose(-70, 70, 0, -90, 50, 0) };
            case "golpe_3":
            case "aereo_3":
            case "onda":
                return new Pose[] { pose(40, 0, 0, -170, 0, 0), pose(-100, 0, 0, -40, 0, 0) };
            case "golpe_4":
            case "corte_circular":
                return new Pose[] { pose(-80, 90, 0, -90, 80, 0), pose(-80, -270, 0, -90, -90, 0) };
            case "estocada":
                return new Pose[] { new Pose(list(-90, 0, 0), list(0, 0, 6), list(-60, 0, 0)),
                    new Pose(list(-90, 0, 0), list(0, 0, -8), list(-95, 0, 0)) };
            case "lancamento":
                return new Pose[] { pose(-140, 0, 0, -10, 0, 0), pose(30, 0, 0, -170, 0, 0) };
            case "queda":
                return new Pose[] { pose(40, 0, 0, -170, 0, 0), pose(-150, 0, 0, -20, 0, 0) };
            case "iaido":
            case "corte_dimensional":
            default:
                return new Pose[] { pose(-20, -100, 40, -30, -40, 0), pose(-80, 100, 0, -90, 70, 0) };
        }
    }

    /** Animation length in seconds of a state: a move's whole duration, the draw's, an art's. */
    static double seconds(String state) {
        Move m = Arsenal.KATANA.move(state);
        if (m != null) {
            return m.total() / 20.0;
        }
        if (Combo.DRAW.equals(state)) {
            return Arsenal.KATANA.drawTicks / 20.0;
        }
        if (Combo.IDLE.equals(state)) {
            return 3;
        }
        return 0.9; // a slash art's release
    }

    /** All the animations of the blade ({@code arm}: of the right arm) for every state. */
    static Map<String, Object> animations(Combo combo, boolean arm) {
        Map<String, Object> anims = new LinkedHashMap<>();
        String prefix = arm ? "animation.braco." : "animation.katana.";
        String bone = arm ? "braco_direito" : "katana";
        // idle: a slow breath
        Pose breathe = pose(-3, 0, 2, -18, 0, 7);
        anims.put(
            prefix + Combo.IDLE,
            obj(
                "loop",
                true,
                "animation_length",
                3,
                "bones",
                obj(bone, channels(arm, new double[] { 0, 1.5, 3 }, new Pose[] { REST, breathe, REST }))));
        // draw: from the hip
        Pose sheathed = new Pose(list(-160, 0, 0), list(0, -4, 0), list(10, 0, -10));
        double draw = seconds(Combo.DRAW);
        anims.put(
            prefix + Combo.DRAW,
            obj(
                "animation_length",
                draw,
                "bones",
                obj(bone, channels(arm, new double[] { 0, draw * 0.6, draw }, new Pose[] { sheathed, pose(-40, 0, 0, -60, 0, 0), REST }))));
        List<String> states = new ArrayList<>(combo.moves()
            .keySet());
        states.addAll(Arsenal.SLASH_ARTS.keySet());
        for (String s : states) {
            Move m = combo.move(s);
            double total = seconds(s);
            double windup = m == null ? total * 0.35 : Math.max(0.05, m.startup / 20.0);
            double strike = m == null ? total * 0.55 : (m.startup + m.active) / 20.0;
            Pose[] c = cut(s);
            anims.put(
                prefix + s,
                obj(
                    "animation_length",
                    total,
                    "bones",
                    obj(bone, channels(arm, new double[] { 0, windup, strike, total }, new Pose[] { REST, c[0], c[1], REST }))));
        }
        return obj("format_version", "1.8.0", "animations", anims);
    }

    private static Map<String, Object> channels(boolean arm, double[] times, Pose[] poses) {
        Map<String, Object> rotation = new LinkedHashMap<>();
        Map<String, Object> position = new LinkedHashMap<>();
        for (int i = 0; i < times.length; i++) {
            String t = key(times[i]);
            rotation.put(t, arm ? poses[i].arm : poses[i].item);
            position.put(t, poses[i].at);
        }
        return arm ? obj("rotation", rotation) : obj("rotation", rotation, "position", position);
    }

    private static String key(double seconds) {
        String s = String.format(java.util.Locale.ROOT, "%.4f", seconds);
        return s.replaceAll("0+$", "")
            .replaceAll("\\.$", ".0");
    }

    // ================================================================================================================
    // Displays (slash arts, summoned swords)

    private void displays() throws IOException {
        Map<String, Map<String, Object>> geos = new LinkedHashMap<>();
        geos.put("corte_dimensional", dimensionGeometry());
        geos.put("onda", waveGeometry());
        geos.put("corte_circular", circleGeometry());
        geos.put("espada_invocada", swordGeometry());
        Map<String, Object> displays = new LinkedHashMap<>();
        displays.put(
            "corte_dimensional",
            display(
                "corte_dimensional",
                obj("cortar", obj("animacao", "animation.corte_dimensional.cortar")),
                geos.get("corte_dimensional"),
                64));
        displays.put(
            "onda",
            display("onda", obj("voar", obj("animacao", "animation.onda.voar", "laco", true)), geos.get("onda"), 64));
        displays.put(
            "corte_circular",
            display(
                "corte_circular",
                obj("girar", obj("animacao", "animation.corte_circular.girar")),
                geos.get("corte_circular"),
                48));
        displays.put(
            "espada_invocada",
            display(
                "espada_invocada",
                obj("voar", obj("animacao", "animation.espada_invocada.voar", "laco", true)),
                geos.get("espada_invocada"),
                48));
        text("assets/overkill/kunftig/displays.json", Json.write(obj("_formato", 1, "exibicoes", displays)), false);
        for (Map.Entry<String, Map<String, Object>> g : geos.entrySet()) {
            text("assets/overkill/geo/efeito/" + g.getKey() + ".geo.json", Json.write(g.getValue()), true);
        }

        text("assets/overkill/animations/efeito/corte_dimensional.animation.json", Json.write(obj(
            "format_version", "1.8.0",
            "animations", obj("animation.corte_dimensional.cortar", obj(
                "animation_length", 1.4,
                "bones", obj(
                    "raiz", obj(
                        "scale", obj("0.0", list(0, 0, 0), "0.25", list(1.2, 1.2, 1.2), "1.0", list(1, 1, 1), "1.4", list(0, 0, 0)),
                        "rotation", obj("0.0", list(0, 0, 0), "1.4", list(0, 180, 0))),
                    "nucleo", obj("rotation", obj("0.0", list(45, 0, 45), "1.4", list(45, 720, 45)))))))), true);
        text("assets/overkill/animations/efeito/onda.animation.json", Json.write(obj(
            "format_version", "1.8.0",
            "animations", obj("animation.onda.voar", obj(
                "loop", true,
                "animation_length", 0.4,
                "bones", obj("raiz", obj("scale", obj("0.0", list(1, 1, 1), "0.2", list(1.1, 1.15, 1.1), "0.4", list(1, 1, 1)))))))), true);
        text("assets/overkill/animations/efeito/corte_circular.animation.json", Json.write(obj(
            "format_version", "1.8.0",
            "animations", obj("animation.corte_circular.girar", obj(
                "animation_length", 0.8,
                "bones", obj("raiz", obj(
                    "rotation", obj("0.0", list(0, 0, 0), "0.8", list(0, -540, 0)),
                    "scale", obj("0.0", list(0.3, 1, 0.3), "0.3", list(1.1, 1, 1.1), "0.8", list(0, 0, 0)))))))), true);
        text("assets/overkill/animations/efeito/espada_invocada.animation.json", Json.write(obj(
            "format_version", "1.8.0",
            "animations", obj("animation.espada_invocada.voar", obj(
                "loop", true,
                "animation_length", 0.5,
                "bones", obj("espada", obj("rotation", obj("0.0", list(0, 0, 0), "0.5", list(0, 0, 360)))))))), true);

        png("assets/overkill/textures/efeito/corte.png", glow(0xC77DFF, 0xFFFFFF), true);
        png("assets/overkill/textures/efeito/onda.png", glow(0xC77DFF, 0xF0E0FF), true);
        png("assets/overkill/textures/efeito/espada.png", glow(0x7FD8FF, 0xFFFFFF), true);
    }

    /** A display's manifest entry: every bone with cubes glows and is translucent (per bone, no inheritance). */
    private static Map<String, Object> display(String name, Map<String, Object> states, Map<String, Object> geometry,
        int distance) {
        String texture = name.equals("espada_invocada") ? "espada" : name.equals("onda") ? "onda" : "corte";
        List<Object> glowing = cubeBones(geometry);
        return obj(
            "geo",
            NS + ":geo/efeito/" + name + ".geo.json",
            "animacoes",
            NS + ":animations/efeito/" + name + ".animation.json",
            "textura",
            NS + ":textures/efeito/" + texture + ".png",
            "estados",
            states,
            "luz",
            glowing,
            "translucido",
            glowing,
            "distancia",
            distance);
    }

    /** The names of the bones of a geometry that have cubes. */
    @SuppressWarnings("unchecked")
    static List<Object> cubeBones(Map<String, Object> geometry) {
        List<Object> out = new ArrayList<>();
        Map<String, Object> g = (Map<String, Object>) ((List<Object>) geometry.get("minecraft:geometry")).get(0);
        for (Object b : (List<Object>) g.get("bones")) {
            Map<String, Object> bone = (Map<String, Object>) b;
            if (bone.containsKey("cubes")) {
                out.add(bone.get("name"));
            }
        }
        return out;
    }

    static Map<String, Object> dimensionGeometry() {
        List<Object> bones = new ArrayList<>();
        bones.add(bone("raiz", null, list(0, 0, 0)));
        bones.add(bone("nucleo", "raiz", list(0, 0, 0), cube(list(-3, -3, -3), list(6, 6, 6), list(0, 0), 0)));
        Random r = new Random(7);
        for (int i = 0; i < 7; i++) {
            Map<String, Object> b = bone("corte_" + i, "raiz", list(0, 0, 0), cube(list(-0.5, -24, -0.5), list(1, 48, 1), list(0, 12), 0.2));
            b.put("rotation", list(r.nextInt(180) - 90, r.nextInt(360), r.nextInt(180) - 90));
            bones.add(b);
        }
        return geometry("geometry.overkill.corte_dimensional", 32, 64, bones, 6);
    }

    static Map<String, Object> waveGeometry() {
        List<Object> bones = new ArrayList<>();
        bones.add(bone("raiz", null, list(0, 0, 0)));
        double radius = 18;
        for (int i = 0; i <= 8; i++) {
            double a = Math.toRadians(-80 + 20 * i);
            double x = radius * Math.sin(a);
            double z = radius * Math.cos(a) - radius;
            double thick = 3 - Math.abs(i - 4) * 0.5;
            Map<String, Object> b = bone("arco_" + i, "raiz", list(round(x), 0, round(z)), cube(list(round(x - 3.5), round(-thick), round(z - 0.5)), list(7, round(thick * 2), 1), list(0, 0), 0));
            b.put("rotation", list(0, round(Math.toDegrees(a)), 0));
            bones.add(b);
        }
        return geometry("geometry.overkill.onda", 32, 32, bones, 4);
    }

    static Map<String, Object> circleGeometry() {
        List<Object> bones = new ArrayList<>();
        bones.add(bone("raiz", null, list(0, 0, 0)));
        double radius = 64;
        for (int i = 0; i < 16; i++) {
            double a = 2 * Math.PI * i / 16;
            double x = radius * Math.sin(a);
            double z = radius * Math.cos(a);
            Map<String, Object> b = bone("segmento_" + i, "raiz", list(round(x), 8, round(z)), cube(list(round(x - 12), 7, round(z - 0.5)), list(24, 2, 1), list(0, 0), 0.1));
            b.put("rotation", list(0, round(Math.toDegrees(a)), 0));
            bones.add(b);
        }
        return geometry("geometry.overkill.corte_circular", 32, 32, bones, 10);
    }

    static Map<String, Object> swordGeometry() {
        List<Object> bones = new ArrayList<>();
        bones.add(bone("espada", null, list(0, 0, 0), cube(list(-0.5, -0.5, -6), list(1, 1, 14), list(0, 0), 0.15), cube(list(-1.5, -0.5, -7), list(3, 1, 1), list(0, 16), 0)));
        return geometry("geometry.overkill.espada_invocada", 32, 32, bones, 2);
    }

    // ================================================================================================================
    // Geometry helpers

    static Map<String, Object> geometry(String id, int tw, int th, List<Object> bones, double bounds) {
        return obj(
            "format_version",
            "1.12.0",
            "minecraft:geometry",
            list(
                obj(
                    "description",
                    obj(
                        "identifier",
                        id,
                        "texture_width",
                        tw,
                        "texture_height",
                        th,
                        "visible_bounds_width",
                        bounds,
                        "visible_bounds_height",
                        bounds,
                        "visible_bounds_offset",
                        list(0, bounds / 4, 0)),
                    "bones",
                    bones)));
    }

    static Map<String, Object> bone(String name, String parent, List<Object> pivot, Object... cubes) {
        Map<String, Object> b = obj("name", name);
        if (parent != null) {
            b.put("parent", parent);
        }
        b.put("pivot", pivot);
        if (cubes.length > 0) {
            b.put("cubes", Arrays.asList(cubes));
        }
        return b;
    }

    static Map<String, Object> cube(List<Object> origin, List<Object> size, List<Object> uv, double inflate) {
        Map<String, Object> c = obj("origin", origin, "size", size, "uv", uv);
        if (inflate != 0) {
            c.put("inflate", inflate);
        }
        return c;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ================================================================================================================
    // Textures

    /** The blade texture (64x64, box UV): handle, guard, blade, tip, glow. */
    static BufferedImage bladeTexture(Look l) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(l.blade);
        // handle: wrapped in a diamond pattern
        for (int y = 0; y < 11; y++) {
            for (int x = 0; x < 8; x++) {
                boolean band = ((x + y) % 4) < 2;
                img.setRGB(x, y, argb(255, shade(band ? l.wrap : lighten(l.wrap, 0.35), r)));
            }
        }
        fill(img, 8, 0, 16, 5, l.fitting, r);
        // blade: steel with a bright edge
        for (int y = 12; y < 36; y++) {
            for (int x = 0; x < 6; x++) {
                int c = x == 2 || x == 5 ? lighten(l.blade, 0.5) : l.blade;
                img.setRGB(x, y, argb(255, shade(c, r)));
            }
        }
        fill(img, 8, 12, 4, 3, lighten(l.blade, 0.3), r);
        // glow shell: translucent
        for (int y = 12; y < 36; y++) {
            for (int x = 16; x < 22; x++) {
                img.setRGB(x, y, argb(40 + r.nextInt(35), l.glow));
            }
        }
        return img;
    }

    /** A 16x16 icon: a diagonal katana. */
    static BufferedImage icon(Look l) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(l.glow);
        for (int i = 0; i < 16; i++) {
            int x = i;
            int y = 15 - i;
            if (i < 4) {
                img.setRGB(x, y, argb(255, shade(l.wrap, r)));
            } else if (i == 4) {
                img.setRGB(x, y, argb(255, l.fitting));
                if (x + 1 < 16) {
                    img.setRGB(x + 1, y + 1 < 16 ? y + 1 : y, argb(255, l.fitting));
                }
                if (x - 1 >= 0) {
                    img.setRGB(x - 1, y - 1 >= 0 ? y - 1 : y, argb(255, l.fitting));
                }
            } else {
                img.setRGB(x, y, argb(255, shade(l.blade, r)));
                if (y + 1 < 16) {
                    img.setRGB(x, y + 1, argb(255, lighten(l.blade, 0.45)));
                }
                if (i > 6 && y - 1 >= 0) {
                    img.setRGB(x, y - 1, argb(110, l.glow));
                }
            }
        }
        return img;
    }

    /** An effect texture: a bright core fading to translucent color. */
    static BufferedImage glow(int color, int core) {
        BufferedImage img = new BufferedImage(32, 64, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(color);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 32; x++) {
                boolean bright = r.nextInt(5) == 0;
                img.setRGB(x, y, argb(bright ? 230 : 150, bright ? core : color));
            }
        }
        return img;
    }

    private static void fill(BufferedImage img, int x0, int y0, int w, int h, int rgb, Random r) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                img.setRGB(x, y, argb(255, shade(rgb, r)));
            }
        }
    }

    private static int shade(int rgb, Random r) {
        double f = 0.9 + r.nextDouble() * 0.2;
        int red = (int) Math.min(255, ((rgb >> 16) & 255) * f);
        int green = (int) Math.min(255, ((rgb >> 8) & 255) * f);
        int blue = (int) Math.min(255, (rgb & 255) * f);
        return red << 16 | green << 8 | blue;
    }

    private static int lighten(int rgb, double amount) {
        int red = (int) (((rgb >> 16) & 255) + (255 - ((rgb >> 16) & 255)) * amount);
        int green = (int) (((rgb >> 8) & 255) + (255 - ((rgb >> 8) & 255)) * amount);
        int blue = (int) ((rgb & 255) + (255 - (rgb & 255)) * amount);
        return red << 16 | green << 8 | blue;
    }

    private static int argb(int a, int rgb) {
        return a << 24 | (rgb & 0xFFFFFF);
    }

    // ================================================================================================================
    // Files

    private void text(String path, String content, boolean art) throws IOException {
        File f = new File(root, path);
        if (art && f.isFile() && !forceArt) {
            kept++;
            return;
        }
        f.getParentFile()
            .mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        written++;
    }

    private void png(String path, BufferedImage img, boolean art) throws IOException {
        File f = new File(root, path);
        if (art && f.isFile() && !forceArt) {
            kept++;
            return;
        }
        f.getParentFile()
            .mkdirs();
        ImageIO.write(img, "png", f);
        written++;
    }

    // ================================================================================================================
    // Builders

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static List<Object> list(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
