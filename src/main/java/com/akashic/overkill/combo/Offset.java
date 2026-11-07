package com.akashic.overkill.combo;

/** Where a slash effect sits relative to its usual spot (visual only: hits do not depend on it). */
public enum Offset {
    ZERO,
    /** {@code genRushOffset}: up and down by up to one block. */
    RUSH,
    /** {@code (r - 0.5, 0.8, 0)}: the finishers' rising slashes. */
    UP
}
