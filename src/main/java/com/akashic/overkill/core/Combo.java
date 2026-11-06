package com.akashic.overkill.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A blade's set of moves: the entries (what an attack from idle starts, first match wins), the follow-ups of each move,
 * and the quick-draw ({@code iaido}: an attack while the blade is being drawn). Validated on construction: every name
 * refers to a move.
 */
public final class Combo {

    /** The idle animation state (looping). */
    public static final String IDLE = "parada";
    /** The draw animation state (the blade comes to the hand). */
    public static final String DRAW = "sacar";

    public final String id;
    private final Map<String, Move> moves;
    private final List<String> entries;
    private final String quickDraw;
    /** Ticks the draw lasts: an attack within them is a quick-draw. */
    public final int drawTicks;

    public Combo(String id, List<Move> moves, List<String> entries, String quickDraw, int drawTicks) {
        this.id = id;
        Map<String, Move> m = new LinkedHashMap<>();
        for (Move mv : moves) {
            if (mv.id.equals(IDLE) || mv.id.equals(DRAW)) {
                throw new IllegalArgumentException(id + ": '" + mv.id + "' is reserved");
            }
            if (m.put(mv.id, mv) != null) {
                throw new IllegalArgumentException(id + ": move '" + mv.id + "' twice");
            }
        }
        this.moves = Collections.unmodifiableMap(m);
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.quickDraw = quickDraw;
        this.drawTicks = drawTicks;
        for (String e : entries) {
            require(e, "entry");
        }
        if (quickDraw != null) {
            require(quickDraw, "quick-draw");
        }
        for (Move mv : moves) {
            for (String n : mv.next) {
                require(n, mv.id + ".next");
            }
        }
    }

    private void require(String name, String where) {
        if (!moves.containsKey(name)) {
            throw new IllegalArgumentException(id + ": " + where + " names no move '" + name + "'");
        }
    }

    public Map<String, Move> moves() {
        return moves;
    }

    public Move move(String name) {
        return moves.get(name);
    }

    public List<String> entries() {
        return entries;
    }

    public String quickDraw() {
        return quickDraw;
    }

    /** What an attack from idle starts, or null; {@code drawing}: the blade is still being drawn. */
    public Move entry(Stance s, boolean drawing) {
        if (drawing && quickDraw != null && moves.get(quickDraw).when.matches(s)) {
            return moves.get(quickDraw);
        }
        return first(entries, s);
    }

    /** What an attack in {@code from}'s cancel window chains into, or null. */
    public Move next(Move from, Stance s) {
        return first(from.next, s);
    }

    private Move first(List<String> names, Stance s) {
        for (String n : names) {
            Move m = moves.get(n);
            if (m.when.matches(s)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The move the client predicts after {@code from} ({@code ao_atacar} in the pack): the first follow-up the plain
     * chain could take, on the ground or in the air like {@code from}; null when none. For idle ({@code from} null),
     * the first plain ground entry.
     */
    public Move predicted(Move from) {
        boolean air = from != null && (from.when == Condition.AIR || from.when == Condition.AIR_SNEAK);
        for (String n : from == null ? entries : from.next) {
            Move m = moves.get(n);
            if (m.when.predictable(air)) {
                return m;
            }
        }
        return null;
    }
}
