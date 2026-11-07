package com.akashic.overkill.core;

import java.util.Locale;
import java.util.Random;

/**
 * The star a katana is born with (its quality), rolled once when it is obtained, as the AkashicFishing rods: each star
 * five times rarer than the one below, multiplying the katana's attack.
 */
public enum Star {
    NORMAL("normal", "Normal", 625, 1.0, 'f'),
    PRATA("prata", "Prata", 125, 1.1, '7'),
    OURO("ouro", "Ouro", 25, 1.25, '6'),
    IRIDIO("iridio", "Irídio", 5, 1.5, 'd');

    private final String key;
    private final String displayName;
    private final int weight;
    private final double multiplier;
    private final char colorCode;

    Star(String key, String displayName, int weight, double multiplier, char colorCode) {
        this.key = key;
        this.displayName = displayName;
        this.weight = weight;
        this.multiplier = multiplier;
        this.colorCode = colorCode;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public int weight() {
        return weight;
    }

    public double multiplier() {
        return multiplier;
    }

    public char colorCode() {
        return colorCode;
    }

    /** One roll by the weights (80.1%, 16%, 3.2%, 0.64%). */
    public static Star roll(Random random) {
        int total = 0;
        for (Star s : values()) {
            total += s.weight;
        }
        int r = random.nextInt(total);
        for (Star s : values()) {
            r -= s.weight;
            if (r < 0) {
                return s;
            }
        }
        return NORMAL;
    }

    /** "ouro", "OURO", "2"; null when unknown. */
    public static Star parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim()
            .toLowerCase(Locale.ROOT)
            .replace("í", "i");
        for (Star s : values()) {
            if (s.key.equals(v) || Integer.toString(s.ordinal())
                .equals(v)) {
                return s;
            }
        }
        return null;
    }
}
