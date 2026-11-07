package com.akashic.overkill.combo;

/**
 * A slash art (SlashBlade's {@code SlashArts}): the combo state a charged release starts, on the ground or in the air,
 * the "just" one (released inside the just window, or right as the blade sheathes) and the super one.
 */
public final class SlashArt {

    /** How a release went (SlashBlade's {@code ArtsType}). */
    public enum Type {
        FAIL,
        SUCCESS,
        JACKPOT,
        SUPER
    }

    /** Ticks to a full charge ({@code ChargeTicks}). */
    public static final int CHARGE_TICKS = 9;
    /** The just window after a full charge ({@code ChargeJustTicks}, plus Soul Speed up to 5). */
    public static final int JUST_TICKS = 3;
    public static final int JUST_TICKS_MAX = 5;

    public final String id;
    private final String ground;
    private final String air;
    private final String just;
    private final String superState;

    public SlashArt(String id, String ground, String air, String just, String superState) {
        this.id = id;
        this.ground = ground;
        this.air = air;
        this.just = just;
        this.superState = superState;
    }

    /** The state a release of {@code type} starts ({@link Combos#NONE} for a fail). */
    public String state(Type type, boolean onGround) {
        switch (type) {
            case JACKPOT:
                return just != null ? just : onGround ? ground : air;
            case SUCCESS:
                return onGround ? ground : air;
            case SUPER:
                return superState;
            default:
                return Combos.NONE;
        }
    }

    /** SlashBlade's charge windows: under 9 ticks a fail, then the just window, then a success. */
    public static Type charge(int heldTicks, int soulSpeed) {
        int span = Math.min(JUST_TICKS_MAX, JUST_TICKS + soulSpeed);
        if (heldTicks < CHARGE_TICKS) {
            return Type.FAIL;
        }
        return heldTicks < CHARGE_TICKS + span ? Type.JACKPOT : Type.SUCCESS;
    }

    public static final String SUPER = "judgement_cut_end";

    public static final SlashArt JUDGEMENT_CUT = new SlashArt(
        "judgement_cut",
        "judgement_cut",
        "judgement_cut_slash_air",
        "judgement_cut_slash_just",
        SUPER);
    public static final SlashArt SAKURA_END = new SlashArt(
        "sakura_end",
        "sakura_end_left",
        "sakura_end_left_air",
        null,
        SUPER);
    public static final SlashArt VOID_SLASH = new SlashArt("void_slash", "void_slash", "void_slash", null, SUPER);
    public static final SlashArt CIRCLE_SLASH = new SlashArt("circle_slash", "circle_slash", "circle_slash", null, SUPER);
    public static final SlashArt DRIVE_VERTICAL = new SlashArt(
        "drive_vertical",
        "drive_vertical",
        "drive_vertical",
        null,
        SUPER);
    public static final SlashArt DRIVE_HORIZONTAL = new SlashArt(
        "drive_horizontal",
        "drive_horizontal",
        "drive_horizontal",
        null,
        SUPER);
    public static final SlashArt WAVE_EDGE = new SlashArt(
        "wave_edge",
        "wave_edge_vertical",
        "wave_edge_vertical",
        null,
        SUPER);

    /** The built-in arts by id. */
    public static SlashArt byId(String id) {
        for (SlashArt a : new SlashArt[] { JUDGEMENT_CUT, SAKURA_END, VOID_SLASH, CIRCLE_SLASH, DRIVE_VERTICAL,
            DRIVE_HORIZONTAL, WAVE_EDGE }) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }
}
