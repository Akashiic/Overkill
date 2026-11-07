package com.akashic.overkill.core;

import java.util.Locale;

/**
 * The protection talismans (ofuda): the share of the forge's regressions they hold back. Keys as the AkashicFishing
 * seals ("25" .. "100").
 */
public enum SealTier {
    P25(25, '7'),
    P50(50, 'a'),
    P75(75, '9'),
    P100(100, 'd');

    private final int percent;
    private final char colorCode;

    SealTier(int percent, char colorCode) {
        this.percent = percent;
        this.colorCode = colorCode;
    }

    public int percent() {
        return percent;
    }

    /** Chance (0..1) of holding a regression back. */
    public double protection() {
        return percent / 100.0;
    }

    public String key() {
        return Integer.toString(percent);
    }

    public char colorCode() {
        return colorCode;
    }

    /** "25", "25%", "p25"; null when unknown. */
    public static SealTier parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim()
            .toLowerCase(Locale.ROOT)
            .replace("%", "")
            .replace("p", "");
        for (SealTier t : values()) {
            if (t.key()
                .equals(v)) {
                return t;
            }
        }
        return null;
    }
}
