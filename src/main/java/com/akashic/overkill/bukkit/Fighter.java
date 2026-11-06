package com.akashic.overkill.bukkit;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.entity.LivingEntity;

import com.akashic.overkill.core.ChargeMeter;
import com.akashic.overkill.core.ComboMachine;
import com.akashic.overkill.core.Move;
import com.akashic.overkill.core.StyleRank;

/** What the plugin keeps about one player while he fights. Main thread only. */
final class Fighter {

    /** The blade id in his hand last tick (null: none). */
    String bladeId;
    ComboMachine combo;
    final ChargeMeter charge = new ChargeMeter();
    final StyleRank rank = new StyleRank();
    /** The move whose hits are being dealt, its start, and who it already hit. */
    Move hitting;
    long hittingSince = Long.MIN_VALUE;
    final Set<Integer> hit = new HashSet<>();
    long invulnerableUntil = Long.MIN_VALUE;
    boolean dodgeRewarded;
    long dodgeReady;
    long summonReady;
    long trickReady;
    LivingEntity lock;
    boolean chargeAnnounced;
    /** Hits in a row (resets when he stops hitting for a while). */
    int hits;
    long lastHit = Long.MIN_VALUE / 2;
    /** What the HUD last showed, to skip resending. */
    String hudRank = "";
}
