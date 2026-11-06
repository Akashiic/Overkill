package com.akashic.overkill.pack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the committed resource pack against the code: its manifests must be exactly what {@link PackGenerator}
 * writes now (a move changed, a blade was added: run {@code ./gradlew generatePack}), and every file a manifest
 * names must exist. The art itself is not compared: an artist may have replaced it.
 */
public final class PackCheck {

    static final List<String> MANIFESTS = Arrays.asList(
        "pack.mcmeta",
        "assets/overkill/kunftig/item_models_3d.json",
        "assets/overkill/kunftig/item_models.json",
        "assets/overkill/kunftig/displays.json");
    private static final Pattern RESOURCE = Pattern.compile("\"overkill:([a-z0-9_./-]+\\.(?:json|png))\"");

    private PackCheck() {}

    public static void main(String[] args) throws IOException {
        File pack = new File(args.length > 0 ? args[0] : "resourcepack");
        List<String> problems = check(pack);
        if (!problems.isEmpty()) {
            for (String p : problems) {
                System.err.println("Overkill pack: " + p);
            }
            System.exit(1);
        }
        System.out.println("Overkill pack: manifests up to date, every named file present");
    }

    static List<String> check(File pack) throws IOException {
        List<String> problems = new ArrayList<>();
        File fresh = Files.createTempDirectory("overkill-pack")
            .toFile();
        try {
            new PackGenerator(fresh, true).generate();
            for (String m : MANIFESTS) {
                File have = new File(pack, m);
                if (!have.isFile()) {
                    problems.add(m + " is missing (./gradlew generatePack)");
                    continue;
                }
                byte[] want = Files.readAllBytes(new File(fresh, m).toPath());
                if (!Arrays.equals(want, Files.readAllBytes(have.toPath()))) {
                    problems.add(m + " is not what the moves and blades generate (./gradlew generatePack)");
                }
                Matcher r = RESOURCE.matcher(new String(Files.readAllBytes(have.toPath()), "UTF-8"));
                while (r.find()) {
                    String path = "assets/overkill/" + r.group(1);
                    if (!new File(pack, path).isFile()) {
                        problems.add(m + " names " + path + ", which is missing");
                    }
                }
            }
            // icons: item_models.json names textures/items/<id>.png
            for (com.akashic.overkill.core.Blade b : com.akashic.overkill.core.Arsenal.DEFAULT_BLADES) {
                String icon = "assets/overkill/textures/items/" + b.id + ".png";
                if (!new File(pack, icon).isFile()) {
                    problems.add("icon " + icon + " is missing");
                }
            }
        } finally {
            delete(fresh);
        }
        return problems;
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                delete(c);
            }
        }
        f.delete();
    }
}
