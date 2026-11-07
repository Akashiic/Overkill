package com.akashic.overkill.combo;

import static com.akashic.overkill.combo.ComboState.Hover.DECREASE;
import static com.akashic.overkill.combo.ComboState.Hover.RESIST;
import static com.akashic.overkill.combo.ComboState.of;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The combo graph: SlashBlade: Resharped's {@code ComboStateRegistry} (MIT), state by state (frames, priorities, lock
 * windows, the slashes on their ticks, the scripts of rapid slash, rising star, aerial cleave, Judgement Cut and void
 * slash, hovers, stuns), the body clips of its {@code PlayerAnimationOverrider} and the root command table of
 * {@code ComboCommands}. The piercing art (another motion file) is left out.
 */
public final class Combos {

    public static final String NONE = "none";
    public static final String STANDBY = "standby";

    private final Map<String, ComboState> states = new LinkedHashMap<>();
    private final List<RootCommand> root = new ArrayList<>();

    /** A row of the root command table: every input required, the state it starts. */
    public static final class RootCommand {

        public final int inputs;
        public final String state;

        RootCommand(int inputs, String state) {
            this.inputs = inputs;
            this.state = state;
        }
    }

    public ComboState get(String id) {
        ComboState s = states.get(id);
        return s != null ? s : states.get(NONE);
    }

    public boolean has(String id) {
        return states.containsKey(id);
    }

    public Collection<ComboState> all() {
        return Collections.unmodifiableCollection(states.values());
    }

    /**
     * {@code ComboCommands.initStandByCommand}: among the rows whose inputs are all present, the one whose state has
     * the lowest priority number; {@link #NONE} when none matches.
     */
    public String rootCommand(int inputs) {
        String best = NONE;
        int bestPriority = Integer.MAX_VALUE;
        for (RootCommand r : root) {
            if (Input.has(inputs, r.inputs)) {
                int p = get(r.state).priority;
                if (p < bestPriority) {
                    best = r.state;
                    bestPriority = p;
                }
            }
        }
        return best;
    }

    /**
     * The states a client plays from {@code id} on if nothing interrupts: the chain of timeouts until {@link #NONE} (at
     * most {@code max} states).
     */
    public List<ComboState> chain(String id, int max) {
        List<ComboState> out = new ArrayList<>();
        ComboState s = get(id);
        while (!s.isNone() && out.size() < max && !out.contains(s)) {
            out.add(s);
            s = get(s.nextOfTimeout);
        }
        return out;
    }

    public void add(ComboState s) {
        states.put(s.id, s);
    }

    public void addRoot(int inputs, String state) {
        root.add(new RootCommand(inputs, state));
    }

    // ---- actions ----------------------------------------------------------------------------------------------------

    static ComboState.Action slash(float roll, boolean mute, boolean critical, float ratio) {
        return slash(roll, Offset.ZERO, mute, critical, ratio, Knockback.CANCEL);
    }

    static ComboState.Action slash(float roll, Offset offset, boolean mute, boolean critical, float ratio,
        Knockback knockback) {
        return c -> c.actor.slash(roll, offset, mute, critical, ratio, knockback);
    }

    /** The unmuted rush slash: roll -90 + 180r. */
    static ComboState.Action rushA(float ratio) {
        return c -> c.actor.slash(-90 + 180 * c.actor.random(), Offset.RUSH, false, false, ratio, Knockback.CANCEL);
    }

    /** The muted rush slash: roll 90 + 180r. */
    static ComboState.Action rushB(float ratio) {
        return c -> c.actor.slash(90 + 180 * c.actor.random(), Offset.RUSH, true, false, ratio, Knockback.CANCEL);
    }

    static ComboState.Action art(Actor.Art art, float... args) {
        return c -> c.actor.art(art, args);
    }

    /** Alternating rush slashes on keys from..to (inclusive), starting with the unmuted one on even offsets. */
    static void rush(ComboState.Builder b, int from, int to, float ratio) {
        for (int t = from; t <= to; t++) {
            b.at(t, (t - from) % 2 == 0 ? rushA(ratio) : rushB(ratio));
        }
    }

    // ---- the base graph ---------------------------------------------------------------------------------------------

    public static Combos base() {
        Combos g = new Combos();
        g.add(of(NONE, 0, 1).loop()
            .build());
        g.add(of(STANDBY, 0, 1).loop()
            .timeout(1000)
            .next(Next.root())
            .build());

        // ground combo A / C
        g.add(of("combo_a1", 1, 10).priority(100)
            .next(Next.lock(5, "combo_a2"))
            .timeoutTo("combo_a1_end")
            .click(slash(-10, true, false, 0.44f))
            .stun()
            .clip(1, 41, false, true)
            .build());
        g.add(of("combo_a1_end", 10, 21).priority(100)
            .next("combo_a2")
            .timeoutTo("combo_a1_end2")
            .quickCharge()
            .build());
        g.add(of("combo_a1_end2", 21, 41).priority(100)
            .clip(21, 41, false, true)
            .build());
        g.add(of("combo_a2", 100, 115).priority(100)
            .next(Next.lock(5, "combo_a3"))
            .timeoutTo("combo_a2_end")
            .click(slash(180 - 10, true, false, 0.44f))
            .stun()
            .clip(100, 151, false, true)
            .build());
        g.add(of("combo_a2_end", 115, 132).priority(100)
            .next("combo_c")
            .timeoutTo("combo_a2_end2")
            .quickCharge()
            .build());
        g.add(of("combo_a2_end2", 132, 151).priority(100)
            .build());
        g.add(of("combo_c", 400, 459).priority(100)
            .next(Next.lock(15, NONE))
            .timeoutTo("combo_c_end")
            .at(2, slash(-30, false, false, 0.88f))
            .at(3, slash(-35, true, false, 0.88f))
            .stun()
            .clip(400, 488, false, true)
            .build());
        g.add(of("combo_c_end", 459, 488).priority(100)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("combo_a3", 200, 218).priority(100)
            .next(Next.lock(9, Next.powered("combo_a4_ex", "combo_a4")))
            .timeoutTo("combo_a3_end")
            .at(2, slash(-61, false, false, 0.44f))
            .at(6, slash(180 - 42, false, false, 0.44f))
            .stun()
            .clip(200, 306, false, true)
            .build());
        g.add(of("combo_a3_end", 218, 230).priority(100)
            .next("combo_b1")
            .timeoutTo("combo_a3_end2")
            .build());
        g.add(of("combo_a3_end2", 230, 281).priority(100)
            .timeoutTo("combo_a3_end3")
            .build());
        g.add(of("combo_a3_end3", 281, 306).priority(100)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("combo_a4", 500, 576).priority(100)
            .next(Next.lock(21, NONE))
            .timeoutTo("combo_a4_end")
            .at(8, slash(45, false, false, 0.44f))
            .at(9, slash(50, true, false, 0.44f))
            .spin(8, 13, 72)
            .stun()
            .clip(500, 608, false, true)
            .build());
        g.add(of("combo_a4_end", 576, 608).priority(100)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("combo_a4_ex", 800, 839).priority(100)
            .next(Next.lock(22, "combo_a5ex"))
            .timeoutTo("combo_a4_ex_end")
            .at(7, slash(70, false, false, 1f))
            .at(14, slash(180 + 75, false, false, 1f))
            .stun()
            .clip(800, 894, false, true)
            .build());
        g.add(of("combo_a4_ex_end", 839, 877).priority(100)
            .timeoutTo("combo_a4_ex_end2")
            .build());
        g.add(of("combo_a4_ex_end2", 877, 894).priority(100)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("combo_a5ex", 900, 1013).priority(100)
            .next(Next.lock(33, NONE))
            .timeoutTo("combo_a5ex_end")
            .at(15, slash(35, false, true, 1f))
            .at(17, slash(40, true, true, 1f))
            .at(19, slash(30, true, true, 1f))
            .spin(13, 18, 72)
            .stun()
            .clip(900, 1061, false, true)
            .build());
        g.add(of("combo_a5ex_end", 1013, 1061).priority(100)
            .sheath()
            .quickCharge()
            .build());

        // ground combo B (rush)
        ComboState.Builder b1 = of("combo_b1", 700, 720).priority(100)
            .next(Next.lock(13, "combo_b2"))
            .timeoutTo("combo_b1_end")
            .at(6, slash(-30, false, false, 0.244f))
            .at(6, slash(180 - 35, true, false, 0.244f))
            .stun()
            .clip(700, 787, false, true);
        rush(b1, 7, 14, 0.244f);
        g.add(b1.build());
        g.add(of("combo_b1_end", 720, 743).priority(100)
            .next(Next.self())
            .timeoutTo("combo_b1_end2")
            .at(9, slash(0, Offset.UP, false, true, 1f, Knockback.CANCEL))
            .at(10, slash(5, Offset.UP, true, false, 1f, Knockback.CANCEL))
            .spin(9, 14, 72)
            .stun()
            .build());
        g.add(of("combo_b1_end2", 743, 764).priority(100)
            .timeoutTo("combo_b1_end3")
            .build());
        g.add(of("combo_b1_end3", 764, 787).priority(100)
            .sheath()
            .build());
        String[] bs = { "combo_b2", "combo_b3", "combo_b4", "combo_b5", "combo_b6", "combo_b7" };
        for (int i = 0; i < 5; i++) {
            ComboState.Builder b = of(bs[i], 710, 720).priority(100)
                .next(Next.lock(6, bs[i + 1]))
                .timeoutTo("combo_b_end")
                .stun()
                .clip(710, 787, false, true);
            for (int t = 0; t <= 6; t++) {
                float ratio = i == 1 && t == 2 ? 0.24f : 0.244f; // combo_b3's key 2 says 0.24 in SlashBlade
                b.at(t, t % 2 == 0 ? rushA(ratio) : rushB(ratio));
            }
            g.add(b.build());
        }
        ComboState.Builder b7 = of("combo_b7", 710, 764).priority(100)
            .next(Next.lock(33, NONE))
            .timeoutTo("combo_b7_end")
            .at(12, slash(0, Offset.UP, false, true, 0.244f, Knockback.CANCEL))
            .at(13, slash(5, Offset.UP, true, false, 0.244f, Knockback.CANCEL))
            .spin(12, 17, 72)
            .stun()
            .clip(710, 787, false, true);
        rush(b7, 0, 7, 0.244f);
        g.add(b7.build());
        g.add(of("combo_b7_end", 764, 787).priority(100)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("combo_b_end", 720, 743).priority(100)
            .next(Next.self())
            .timeoutTo("combo_b_end2")
            .at(9, slash(0, Offset.UP, false, true, 0.244f, Knockback.CANCEL))
            .at(10, slash(5, Offset.UP, true, false, 0.244f, Knockback.CANCEL))
            .spin(9, 14, 72)
            .stun()
            .build());
        g.add(of("combo_b_end2", 743, 764).priority(100)
            .timeoutTo("combo_b_end3")
            .build());
        g.add(of("combo_b_end3", 764, 787).priority(100)
            .sheath()
            .quickCharge()
            .build());

        // aerial rave
        g.add(of("aerial_rave_a1", 1100, 1122).priority(80)
            .aerial()
            .next(Next.lock(5, "aerial_rave_a2"))
            .timeoutTo("aerial_rave_a1_end")
            .atFrame(3, slash(-20, false, false, 0.28f))
            .hover(DECREASE)
            .stun()
            .clip(1100, 1132, false, false)
            .build());
        g.add(of("aerial_rave_a1_end", 1122, 1132).priority(80)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("aerial_rave_a2", 1200, 1210).priority(80)
            .aerial()
            .next(Next.lock(5, "aerial_rave_a3"))
            .timeoutTo("aerial_rave_a2_end")
            .atFrame(3, slash(180 - 30, false, false, 0.28f))
            .hover(DECREASE)
            .stun()
            .clip(1200, 1241, false, false)
            .build());
        g.add(of("aerial_rave_a2_end", 1210, 1231).priority(80)
            .aerial()
            .next("aerial_rave_b3")
            .timeoutTo("aerial_rave_a2_end2")
            .hover(DECREASE)
            .build());
        g.add(of("aerial_rave_a2_end2", 1231, 1241).priority(80)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("aerial_rave_a3", 1300, 1328).priority(80)
            .aerial()
            .next(Next.lock(9, NONE))
            .timeoutTo("aerial_rave_a3_end")
            .atFrame(4, slash(0, Offset.ZERO, false, false, 0.28f, Knockback.SMASH))
            .at(ComboState.ticks(4) + 1, slash(-3, Offset.ZERO, true, true, 0.28f, Knockback.SMASH))
            .hover(DECREASE)
            .stun()
            .clip(1300, 1338, false, false)
            .build());
        g.add(of("aerial_rave_a3_end", 1328, 1338).priority(80)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("aerial_rave_b3", 1400, 1437).priority(80)
            .aerial()
            .next(Next.lock(9, "aerial_rave_b4"))
            .timeoutTo("aerial_rave_b3_end")
            .click(c -> c.actor.setVelocityY(0.6))
            .atFrame(5, slash(180 + 57, Offset.ZERO, false, false, 0.34f, Knockback.TOSS))
            .atFrame(10, slash(180 + 57, Offset.ZERO, false, false, 0.34f, Knockback.TOSS))
            .spin(0, 4, -90)
            .at(4, c -> c.actor.spin(-120))
            .at(5, c -> c.actor.spin(-120))
            .at(6, c -> c.actor.spin(-120))
            .at(7, c -> c.actor.spin(0))
            .hover(DECREASE)
            .stun()
            .clip(1400, 1443, false, false)
            .build());
        g.add(of("aerial_rave_b3_end", 1437, 1443).priority(80)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("aerial_rave_b4", 1500, 1537).priority(80)
            .aerial()
            .next(Next.lock(9, NONE))
            .timeoutTo("aerial_rave_b4_end")
            .atFrame(10, slash(45, Offset.ZERO, false, false, 0.34f, Knockback.METEOR))
            .at(ComboState.ticks(10) + 1, slash(50, Offset.ZERO, true, true, 0.34f, Knockback.METEOR))
            .spin(5, 9, 90)
            .hover(DECREASE)
            .stun()
            .clip(1500, 1547, false, false)
            .build());
        g.add(of("aerial_rave_b4_end", 1537, 1547).priority(80)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());

        // upperslash
        g.add(of("upperslash", 1600, 1659).priority(90)
            .next(Next.lock(11, NONE))
            .timeoutTo("upperslash_end")
            .hold(c -> {
                if (c.actor.useTicks() == ComboState.ticks(9) && Input.has(c.inputs, Input.BACK | Input.R_DOWN)) {
                    c.machine.enter("upperslash_jump", c.now, c.actor, ComboMachine.Cause.HOLD);
                }
            })
            .atFrame(7, slash(-80, Offset.ZERO, false, false, 0.5f, Knockback.TOSS))
            .stun(15)
            .clip(1600, 1693, false, true)
            .build());
        g.add(of("upperslash_end", 1659, 1693).priority(90)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("upperslash_jump", 1700, 1713).priority(90)
            .aerial()
            .next(Next.lock(7, NONE))
            .timeoutTo("upperslash_jump_end")
            .click(c -> c.actor.setVelocityY(0.6))
            .spin(0, 4, 90)
            .hover(DECREASE)
            .stun()
            .clip(1700, 1717, false, false)
            .build());
        g.add(of("upperslash_jump_end", 1713, 1717).priority(90)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());

        // aerial cleave
        g.add(of("aerial_cleave", 1800, 1812).priority(70)
            .aerial()
            .next(Next.self())
            .timeoutTo("aerial_cleave_loop")
            .click(c -> c.actor.setVelocityY(0.1))
            .tick(c -> {
                Actor a = c.actor;
                a.setFallDistance(1);
                if (c.elapsed == 2) {
                    a.sound(Actor.Sound.ATTACK_STRONG, 0.75f, 1.0f);
                }
                if (c.elapsed > 2) {
                    a.setVelocityY(a.velocityY() - 3.0);
                }
                if (c.elapsed % 2 == 0) {
                    a.areaAttack(Knockback.METEOR, 0.44f, true, false, true, -1);
                }
                if (a.onGround()) {
                    a.slash(55, Offset.ZERO, true, true, 0.44f, Knockback.METEOR);
                    c.machine.enter("aerial_cleave_landing", c.now, a, ComboMachine.Cause.SCRIPT);
                    return;
                }
                if (c.elapsed == 1 && "aerial_cleave".equals(c.machine.state().id)) {
                    c.machine.enter("aerial_cleave_loop", c.now, a, ComboMachine.Cause.SCRIPT);
                }
            })
            .spin(0, 4, 90)
            .clip(1800, 1817, false, false)
            .build());
        g.add(of("aerial_cleave_loop", 1812, 1817).priority(70)
            .loop()
            .timeout(1000)
            .next(Next.self())
            .tick(c -> {
                Actor a = c.actor;
                a.setFallDistance(1);
                a.setVelocityY(a.velocityY() - 3.0);
                if (c.elapsed % 2 == 0) {
                    a.areaAttack(Knockback.METEOR, 0.44f, true, false, true, -1);
                }
                if (a.onGround()) {
                    a.slash(55, Offset.ZERO, true, true, 0.44f, Knockback.METEOR);
                    c.machine.enter("aerial_cleave_landing", c.now, a, ComboMachine.Cause.SCRIPT);
                }
            })
            .stun(15)
            .clip(1812, 1817, true, false)
            .build());
        g.add(of("aerial_cleave_landing", 1816, 1859).priority(70)
            .next(Next.lock(6, NONE))
            .timeoutTo("aerial_cleave_end")
            .click(slash(60, Offset.ZERO, false, false, 0.44f, Knockback.METEOR))
            .clip(1816, 1886, false, true)
            .build());
        g.add(of("aerial_cleave_end", 1859, 1886).priority(70)
            .sheath()
            .quickCharge()
            .build());

        // rapid slash and rising star
        g.add(of("rapid_slash", 2000, 2019).priority(70)
            .next(Next.powered("rapid_slash_quick", "rapid_slash"))
            .timeoutTo("rapid_slash_end")
            .hold(c -> {
                Actor a = c.actor;
                if (a.useTicks() < 2) {
                    return;
                }
                boolean found = a.areaAttack(Knockback.CANCEL, 0.44f, true, false, true, 1.5);
                if (found && Input.has(a.inputs(), Input.R_DOWN) && "rapid_slash".equals(c.machine.state().id)
                    && a.areaAttack(Knockback.TOSS, 0.44f, true, true, true, 1.5)) {
                    c.machine.enter("rising_star", c.now, a, ComboMachine.Cause.HOLD);
                }
            })
            .tick(c -> {
                Actor a = c.actor;
                long e = c.elapsed;
                if (e == 0) {
                    a.sound(Actor.Sound.ARMOR_EQUIP_IRON, 1.0f, 1.0f);
                }
                if (e <= 3 && a.onGround()) {
                    a.addForward(a.inWater() ? 0.35 : 0.8);
                }
                if (2 <= e && e < 6) {
                    float roll = -45 + 90 * a.random() + (e % 2 == 0 ? 180 : 0);
                    a.slash(roll, Offset.RUSH, false, a.isPowered(), 0.44f, Knockback.CANCEL);
                }
                if (e == 7) {
                    a.slash(-30, Offset.RUSH, false, true, 0.44f, Knockback.CANCEL);
                }
                if (7 <= e && e <= 10) {
                    a.spin(90);
                } else if (e > 10) {
                    a.spin(0);
                }
            })
            .stun()
            .clip(2000, 2073, false, false)
            .build());
        g.add(of("rapid_slash_quick", 2000, 2001).priority(70)
            .next(Next.self())
            .timeoutTo("rapid_slash")
            .sheath()
            .quickCharge()
            .clip(2000, 2073, false, false)
            .build());
        g.add(of("rapid_slash_end", 2019, 2054).priority(70)
            .timeoutTo("rapid_slash_end2")
            .build());
        g.add(of("rapid_slash_end2", 2054, 2073).priority(70)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("rising_star", 2100, 2137).priority(80)
            .aerial()
            .next(Next.lock(18, NONE))
            .timeoutTo("rising_star_end")
            .click(c -> {
                c.actor.setVelocity(0, 0.6, 0);
                c.actor.slash(-57, Offset.ZERO, false, false, 0.6f, Knockback.TOSS);
            })
            .atFrame(9, slash(-57, Offset.ZERO, false, false, 0.6f, Knockback.TOSS))
            .spin(0, 10, 72)
            .tick(c -> {
                if (c.elapsed < 3) {
                    double vy = c.actor.velocityY();
                    c.actor.setVelocity(0, vy <= 0 ? 0.6 : vy, 0);
                }
            })
            .hover(DECREASE)
            .stun()
            .clip(2100, 2147, false, false)
            .build());
        g.add(of("rising_star_end", 2137, 2147).priority(70)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());

        // slash arts
        g.add(of("judgement_cut", 1900, 1923).priority(50)
            .next(Next.self())
            .timeoutTo("judgement_cut_slash")
            .tick(c -> {
                Actor a = c.actor;
                if (c.elapsed == 0) {
                    a.sound(Actor.Sound.TRIDENT_THROW, 0.8f, 0.625f + 0.1f * a.random());
                }
                a.stopHorizontal();
                if (c.elapsed <= 3) {
                    a.addForward(-0.3); // the backstep
                }
            })
            .hover(DECREASE)
            .clip(1900, 1963, false, false)
            .build());
        g.add(of("judgement_cut_slash", 1923, 1928).speed(0.4f)
            .priority(50)
            .next(Next.self())
            .timeoutTo("judgement_cut_sheath")
            .at(0, art(Actor.Art.JUDGEMENT_CUT))
            .hover(DECREASE)
            .stun()
            .build());
        g.add(of("judgement_cut_sheath", 1928, 1963).priority(50)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("judgement_cut_slash_air", 1923, 1928).speed(0.5f)
            .priority(50)
            .next(Next.self())
            .timeoutTo("judgement_cut_sheath_air")
            .at(0, art(Actor.Art.JUDGEMENT_CUT))
            .hover(RESIST)
            .stun()
            .clip(1923, 1963, false, false)
            .build());
        g.add(of("judgement_cut_sheath_air", 1928, 1963).priority(50)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("judgement_cut_slash_just", 1923, 1928).priority(45)
            .next(Next.self())
            .timeoutTo("judgement_cut_slash_just2")
            .at(1, art(Actor.Art.JUDGEMENT_CUT_JUST))
            .hover(RESIST)
            .stun()
            .clip(1923, 1963, false, false)
            .build());
        g.add(of("judgement_cut_slash_just2", 1923, 1928).speed(0.75f)
            .priority(50)
            .next(Next.self())
            .timeoutTo("judgement_cut_slash_just_sheath")
            .hover(RESIST)
            .build());
        g.add(of("judgement_cut_slash_just_sheath", 1928, 1963).priority(50)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("judgement_cut_end", 1923, 1928).priority(50)
            .next(Next.self())
            .timeoutTo("judgement_cut_sheath")
            .at(0, art(Actor.Art.JUDGEMENT_CUT_SUPER))
            .hover(DECREASE)
            .stun()
            .clip(1923, 1963, false, true)
            .build());
        g.add(of("void_slash", 2200, 2277).priority(50)
            .next(Next.self())
            .timeoutTo("void_slash_sheath")
            .tick(c -> c.actor.setVelocity(0, 0, 0))
            .at(16, art(Actor.Art.VOID_SLASH))
            .spin(16, 21, -36)
            .spin(57, 67, 18)
            .hover(DECREASE)
            .stun(40)
            .clip(2200, 2299, false, false)
            .build());
        g.add(of("void_slash_sheath", 2278, 2299).priority(50)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("sakura_end_left", 1816, 1859).speed(6f)
            .priority(50)
            .next("sakura_end_right")
            .timeoutTo("sakura_end_right")
            .click(slash(22.5f, false, false, 0.5f))
            .stun()
            .clip(1816, 1859, false, false)
            .build());
        g.add(of("sakura_end_right", 204, 218).speed(1.1f)
            .priority(50)
            .timeoutTo("sakura_end_finish")
            .click(slash(180 - 22.5f, false, true, 0.76f))
            .stun(36)
            .clip(204, 314, false, false)
            .build());
        g.add(of("sakura_end_finish", 218, 281).priority(50)
            .aerial()
            .timeoutTo("sakura_end_finish2")
            .build());
        g.add(of("sakura_end_finish2", 281, 314).priority(80)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("sakura_end_left_air", 1300, 1328).speed(3.2f)
            .priority(50)
            .aerial()
            .next("sakura_end_right_air")
            .timeoutTo("sakura_end_right_air")
            .click(slash(22.5f, false, false, 0.5f))
            .hover(DECREASE)
            .stun()
            .clip(1300, 1328, false, false)
            .build());
        g.add(of("sakura_end_right_air", 1200, 1210).priority(50)
            .aerial()
            .timeoutTo("sakura_end_finish_air")
            .click(slash(180 - 22.5f, false, true, 0.76f))
            .hover(DECREASE)
            .stun()
            .clip(1200, 1241, false, false)
            .build());
        g.add(of("sakura_end_finish_air", 1210, 1231).priority(50)
            .aerial()
            .timeoutTo("sakura_end_finish2_air")
            .hover(DECREASE)
            .build());
        g.add(of("sakura_end_finish2_air", 1231, 1241).priority(50)
            .hover(DECREASE)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("circle_slash", 725, 743).priority(50)
            .next(Next.self())
            .timeoutTo("circle_slash_end")
            .at(4, art(Actor.Art.CIRCLE_SLASH, 180))
            .at(5, art(Actor.Art.CIRCLE_SLASH, 90))
            .at(6, art(Actor.Art.CIRCLE_SLASH, 0))
            .at(7, art(Actor.Art.CIRCLE_SLASH, -90))
            .spin(4, 9, 72)
            .stun()
            .clip(725, 787, false, true)
            .build());
        g.add(of("circle_slash_end", 743, 764).priority(100)
            .timeoutTo("circle_slash_end2")
            .build());
        g.add(of("circle_slash_end2", 764, 787).priority(100)
            .sheath()
            .build());
        g.add(of("drive_horizontal", 400, 459).priority(50)
            .next(Next.lock(15, NONE))
            .timeoutTo("drive_horizontal_end")
            .at(2, slash(-30, false, false, 0.21f))
            .at(3, art(Actor.Art.DRIVE, 0, 10, 1.5f, 2f))
            .stun()
            .clip(400, 488, false, true)
            .build());
        g.add(of("drive_horizontal_end", 459, 488).priority(50)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("drive_vertical", 1600, 1659).priority(50)
            .next(Next.lock(15, NONE))
            .timeoutTo("drive_vertical_end")
            .at(2, slash(-80, false, false, 0.21f))
            .at(3, art(Actor.Art.DRIVE, -90, 10, 1.5f, 2f))
            .stun()
            .clip(1600, 1693, false, true)
            .build());
        g.add(of("drive_vertical_end", 1659, 1693).priority(50)
            .sheath()
            .quickCharge()
            .build());
        g.add(of("wave_edge_vertical", 1600, 1659).priority(50)
            .next(Next.lock(15, NONE))
            .timeoutTo("drive_vertical_end")
            .at(2, slash(-80, false, false, 0.21f))
            .at(3, art(Actor.Art.WAVE_EDGE, 90, 20, 0.4f, 0.2f, 1f, 4))
            .stun()
            .clip(1600, 1693, false, true)
            .build());

        // ComboCommands
        g.addRoot(Input.ON_GROUND | Input.SNEAK | Input.FORWARD | Input.R_CLICK, "rapid_slash");
        g.addRoot(Input.ON_GROUND | Input.BACK | Input.SNEAK | Input.R_CLICK, "upperslash");
        g.addRoot(Input.ON_GROUND | Input.L_CLICK, "combo_a1");
        g.addRoot(Input.ON_GROUND | Input.R_CLICK, "combo_a1");
        g.addRoot(Input.ON_AIR | Input.SNEAK | Input.BACK | Input.R_CLICK, "aerial_cleave");
        g.addRoot(Input.ON_AIR, "aerial_rave_a1");
        return g;
    }
}
