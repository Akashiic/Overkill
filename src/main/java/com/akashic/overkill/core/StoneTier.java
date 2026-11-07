package com.akashic.overkill.core;

import java.util.Locale;

/**
 * The tiers of the whetstones (pedras de afiar, the fortification stones): each multiplies the forge's chance. The keys
 * are the AkashicFishing stones' keys, so the forge can take those too.
 */
public enum StoneTier {
    COMMON("comum", "Comum", 'f', 1.15),
    UNCOMMON("incomum", "Incomum", 'a', 1.3),
    RARE("rara", "Rara", '9', 1.5),
    EPIC("epica", "Épica", '5', 1.75),
    LEGENDARY("lendaria", "Lendária", '6', 2.0),
    MYTHIC("mitica", "Mítica", 'c', 2.5);

    private final String key;
    private final String displayName;
    private final char colorCode;
    private final double multiplier;

    StoneTier(String key, String displayName, char colorCode, double multiplier) {
        this.key = key;
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.multiplier = multiplier;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public char colorCode() {
        return colorCode;
    }

    /** What the stone multiplies the chance by. */
    public double multiplier() {
        return multiplier;
    }

    /** "rara", "RARE", "Rara"; null when unknown. */
    public static StoneTier parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim()
            .toLowerCase(Locale.ROOT)
            .replace("é", "e")
            .replace("á", "a")
            .replace("í", "i");
        for (StoneTier t : values()) {
            if (t.key.equals(v) || t.name()
                .toLowerCase(Locale.ROOT)
                .equals(v)) {
                return t;
            }
        }
        return null;
    }
}
