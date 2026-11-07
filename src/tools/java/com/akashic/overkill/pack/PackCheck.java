package com.akashic.overkill.pack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Checks the resource pack before it ships: {@code kunftig/blades.json} parses; every katana and effect it names has
 * its OBJ and PNG, and each OBJ has the groups the Kunftig blade engine draws; every katana and every forge material
 * has its inventory icon in {@code kunftig/item_models.json}, and every icon there exists. The motion files (VMD) come from the alpha's own pack and only warn.
 *
 * <pre>java PackCheck &lt;resourcepack dir&gt;</pre>
 */
public final class PackCheck {

    static final Map<String, List<String>> EFFECT_GROUPS = new LinkedHashMap<>();

    static {
        EFFECT_GROUPS.put("corte", Arrays.asList("base"));
        EFFECT_GROUPS.put("julgamento", Arrays.asList("base", "wind"));
        EFFECT_GROUPS.put("onda", Arrays.asList("base"));
        EFFECT_GROUPS.put("espada", Arrays.asList("ss"));
        EFFECT_GROUPS.put("mira", Arrays.asList("lockonBase", "lockonHealthMask", "lockonHealth"));
        EFFECT_GROUPS.put("rank", Arrays.<String>asList());
        EFFECT_GROUPS.put("contador", Arrays.<String>asList());
    }

    static final List<String> BLADE_GROUPS = Arrays.asList("blade", "blade_damaged", "sheath", "effect", "item_blade",
        "item_bladens", "item_damaged");

    private final File root;
    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    PackCheck(File root) {
        this.root = root;
    }

    public static void main(String[] args) throws IOException {
        PackCheck c = new PackCheck(new File(args[0]));
        c.run();
        for (String w : c.warnings) {
            System.out.println("aviso: " + w);
        }
        for (String e : c.errors) {
            System.err.println("ERRO: " + e);
        }
        if (!c.errors.isEmpty()) {
            System.exit(1);
        }
        System.out.println("pack ok");
    }

    void run() throws IOException {
        File blades = new File(root, "assets/overkill/kunftig/blades.json");
        if (!blades.isFile()) {
            errors.add("falta " + blades);
            return;
        }
        Object parsed = new Parser(new String(Files.readAllBytes(blades.toPath()), StandardCharsets.UTF_8)).value();
        if (!(parsed instanceof Map)) {
            errors.add("blades.json não é um objeto");
            return;
        }
        Map<?, ?> json = (Map<?, ?>) parsed;
        if (!Double.valueOf(1).equals(json.get("_formato"))) {
            errors.add("blades.json: _formato deve ser 1");
        }
        for (String k : new String[] { "movimento_lamina", "movimento_jogador" }) {
            Object v = json.get(k);
            if (!(v instanceof String)) {
                errors.add("blades.json: falta " + k);
            } else if (!file((String) v).isFile()) {
                warnings.add(k + " (" + v + ") não está no pack: vem do pack da alfa");
            }
        }
        Object laminas = json.get("laminas");
        Set<String> ids = new HashSet<>();
        if (laminas instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) laminas).entrySet()) {
                Map<?, ?> b = (Map<?, ?>) e.getValue();
                ids.add(String.valueOf(e.getKey()));
                model(String.valueOf(e.getKey()), (String) b.get("obj"), (String) b.get("textura"), BLADE_GROUPS);
            }
        } else {
            errors.add("blades.json: falta laminas");
        }
        Object efeitos = json.get("efeitos");
        if (efeitos instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) efeitos).entrySet()) {
                String name = String.valueOf(e.getKey());
                Map<?, ?> f = (Map<?, ?>) e.getValue();
                List<String> groups = EFFECT_GROUPS.get(name);
                if (groups == null) {
                    warnings.add("efeito desconhecido: " + name);
                    continue;
                }
                model(name, (String) f.get("obj"), (String) f.get("textura"), groups);
            }
        }
        icons(ids);
    }

    private File file(String resource) {
        String[] p = resource.split(":", 2);
        return new File(root, "assets/" + p[0] + "/" + p[1]);
    }

    private void model(String name, String obj, String texture, List<String> groups) throws IOException {
        if (texture == null || !image(file(texture))) {
            errors.add(name + ": textura ausente ou ilegível: " + texture);
        }
        if (groups.isEmpty()) {
            return;
        }
        if (obj == null || !file(obj).isFile()) {
            errors.add(name + ": OBJ ausente: " + obj);
            return;
        }
        Set<String> found = new HashSet<>();
        for (String line : Files.readAllLines(file(obj).toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("g ") || line.startsWith("o ")) {
                found.add(line.substring(2)
                    .trim());
            }
        }
        for (String g : groups) {
            if (!found.contains(g)) {
                errors.add(name + ": o OBJ " + obj + " não tem o grupo " + g);
            }
        }
    }

    private static boolean image(File f) {
        try {
            BufferedImage img = f.isFile() ? ImageIO.read(f) : null;
            return img != null && img.getWidth() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** The forge's materials (ForgeItems' models). */
    static final List<String> FORGE_ICONS = java.util.Arrays.asList(
        "forja/pedra_comum", "forja/pedra_incomum", "forja/pedra_rara", "forja/pedra_epica", "forja/pedra_lendaria",
        "forja/pedra_mitica", "forja/ofuda_25", "forja/ofuda_50", "forja/ofuda_75", "forja/ofuda_100",
        "forja/tamahagane");

    private void icons(Set<String> ids) throws IOException {
        File models = new File(root, "assets/overkill/kunftig/item_models.json");
        if (!models.isFile()) {
            errors.add("falta " + models);
            return;
        }
        Object parsed = new Parser(new String(Files.readAllBytes(models.toPath()), StandardCharsets.UTF_8)).value();
        Map<?, ?> m = parsed instanceof Map ? (Map<?, ?>) parsed : new LinkedHashMap<>();
        Set<String> wanted = new java.util.LinkedHashSet<>(ids);
        wanted.addAll(FORGE_ICONS);
        for (Object key : m.keySet()) {
            wanted.add(String.valueOf(key));
        }
        for (String id : wanted) {
            Object icon = m.get(id);
            if (!(icon instanceof String)) {
                errors.add("item_models.json: falta o ícone de " + id);
                continue;
            }
            String[] p = ((String) icon).split(":", 2);
            File png = new File(root, "assets/" + p[0] + "/textures/items/" + p[1] + ".png");
            if (!image(png)) {
                errors.add("ícone ausente: " + png);
            }
        }
    }

    /** A small JSON reader (objects, arrays, strings, numbers, booleans, null). */
    static final class Parser {

        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    skip();
                    String k = string();
                    skip();
                    expect(':');
                    m.put(k, value());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.valueOf(s.substring(start, i));
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char n = s.charAt(i++);
                    if (n == 'u') {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else {
                        b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                    }
                } else {
                    b.append(c);
                }
            }
            i++;
            return b.toString();
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalArgumentException("JSON: esperado '" + c + "' em " + i);
            }
            i++;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
