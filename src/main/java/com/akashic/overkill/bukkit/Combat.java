package com.akashic.overkill.bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.akashic.kunftig.api.bukkit.display.Display;
import com.akashic.kunftig.api.bukkit.hud.HudElement;
import com.akashic.kunftig.api.bukkit.input.KunftigInput;
import com.akashic.kunftig.api.particle.KunftigParticle;
import com.akashic.overkill.core.Arsenal;
import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.ComboMachine;
import com.akashic.overkill.core.Geometry;
import com.akashic.overkill.core.Move;
import com.akashic.overkill.core.SlashArt;
import com.akashic.overkill.core.Stance;
import com.akashic.overkill.core.StyleRank;

/**
 * The fight, on the server: swings become moves of the blade's combo, hit ticks cut what is in the swing, slash arts
 * release when the raised sword is lowered after a full charge, Kunftig keys summon swords, dodge, blink and lock on,
 * and every hit feeds the style rank. The server decides everything; the client only predicts the animation. Main
 * thread; {@link #tick} once per server tick.
 */
final class Combat {

    /** Rank names shown under the letter (original, not taken from any game). */
    private static final String[] RANK_TITLES = { "Desleixado", "Cortante", "Brutal", "Avassalador", "Selvagem",
        "Supremo", "Soberano" };
    private static final ChatColor[] RANK_COLORS = { ChatColor.GRAY, ChatColor.WHITE, ChatColor.AQUA,
        ChatColor.GREEN, ChatColor.YELLOW, ChatColor.GOLD, ChatColor.RED };
    private static final int SLASH_RGB = 0xBFE6FF;
    private static final int ART_RGB = 0xC77DFF;

    private final OverkillPlugin plugin;
    private final BladeItems items;
    private final Kunftig kunftig;
    private final Effects fx;
    private final Map<UUID, Fighter> fighters = new HashMap<>();
    private final List<Effect> effects = new ArrayList<>();
    private long now;
    /** True while this plugin deals damage (so its own damage events are not cancelled as vanilla swings). */
    private boolean dealing;

    Combat(OverkillPlugin plugin, BladeItems items, Kunftig kunftig) {
        this.plugin = plugin;
        this.items = items;
        this.kunftig = kunftig;
        this.fx = new Effects(kunftig.particles);
    }

    long now() {
        return now;
    }

    boolean dealing() {
        return dealing;
    }

    Fighter fighter(Player p) {
        return fighters.get(p.getUniqueId());
    }

    // ================================================================================================================
    // Tick

    void tick() {
        now++;
        Map<UUID, Player> online = new HashMap<>();
        for (World w : Bukkit.getWorlds()) {
            for (Player p : w.getPlayers()) {
                online.put(p.getUniqueId(), p);
                tick(p);
            }
        }
        for (Iterator<Map.Entry<UUID, Fighter>> it = fighters.entrySet()
            .iterator(); it.hasNext();) {
            if (!online.containsKey(it.next()
                .getKey())) {
                it.remove();
            }
        }
        for (Iterator<Effect> it = effects.iterator(); it.hasNext();) {
            Effect e = it.next();
            if (!e.tick()) {
                e.end();
                it.remove();
            }
        }
    }

    private void tick(Player p) {
        BladeItems.Held held = items.read(p.getItemInHand());
        Fighter f = fighters.get(p.getUniqueId());
        String id = held == null ? null : held.blade.id;
        if (f == null && id == null) {
            return;
        }
        if (f == null) {
            f = new Fighter();
            fighters.put(p.getUniqueId(), f);
        }
        if (id == null ? f.bladeId != null : !id.equals(f.bladeId)) {
            changed(p, f, held);
        }
        f.rank.tick(now);
        if (held != null) {
            if (held.item.getDurability() != 0) {
                held.item.setDurability((short) 0); // the blade's wear is its own (BladeStats), never the sword's
            }
            charge(p, f, held);
            hits(p, f, held);
        }
        if (f.lock != null && !lockable(p, f.lock, Arsenal.LockOn.RANGE + 6)) {
            unlock(p, f);
        }
        if (now - f.lastHit > 60) {
            f.hits = 0;
        }
        hud(p, f);
        if (f.bladeId == null && f.rank.rank() < 0 && f.lock == null) {
            fighters.remove(p.getUniqueId());
        }
    }

    /** The blade in his hand changed (taken, put away, another one). */
    private void changed(Player p, Fighter f, BladeItems.Held held) {
        f.charge.cancel();
        f.hitting = null;
        if (held == null) {
            f.bladeId = null;
            f.combo = null;
            kunftig.bind(p, java.util.Collections.<String>emptyList());
            kunftig.hudRemove(p, "overkill:carga");
            unlock(p, f);
            return;
        }
        f.bladeId = held.blade.id;
        f.combo = new ComboMachine(Arsenal.COMBOS.get(held.blade.combo));
        f.combo.draw(now); // the client plays "sacar" on its own: an attack within it is a quick-draw
        kunftig.bind(p, Kunftig.COMBAT_KEYS);
        Effects.sound(p.getLocation(), Sound.ITEM_PICKUP, 0.4F, 0.6F);
    }

    // ================================================================================================================
    // Swings and hits

    /** An arm swing ({@code PlayerAnimationEvent}): the same click the client predicts from. */
    void swing(Player p) {
        Fighter f = fighters.get(p.getUniqueId());
        BladeItems.Held held = items.read(p.getItemInHand());
        if (f == null || held == null || f.combo == null || f.charge.holding()) {
            return;
        }
        Move m = f.combo.attack(now, new Stance(onGround(p), p.isSneaking(), p.isSprinting()));
        if (m == null) {
            return;
        }
        kunftig.state(p, m.id);
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        if (m.lunge > 0) {
            Vector flat = new Vector(look.getX(), 0, look.getZ());
            if (flat.lengthSquared() > 1e-6) {
                flat.normalize()
                    .multiply(m.lunge);
            }
            p.setVelocity(flat.setY(Math.max(0.05, p.getVelocity()
                .getY())));
        }
        if (!Double.isNaN(m.lift)) {
            p.setVelocity(p.getVelocity()
                .setY(m.lift));
            if (m.lift > 0) {
                p.setFallDistance(0);
            }
        }
        Effects.sound(eye, Sound.ENDERDRAGON_WINGS, 0.35F, 1.9F);
    }

    /**
     * Whether the player stands on the ground, as his client says (deprecated in 1.8 for that reason; enough to pick a
     * move, which is all it does here).
     */
    @SuppressWarnings("deprecation")
    private static boolean onGround(Player p) {
        return p.isOnGround();
    }

    /** On hit ticks, cut whatever the swing touches (each target once per move). */
    private void hits(Player p, Fighter f, BladeItems.Held held) {
        if (f.combo == null) {
            return;
        }
        Move m = f.combo.current(now);
        if (m == null || !f.combo.hitting(now)) {
            return;
        }
        long since = now - f.combo.elapsed(now);
        if (f.hitting != m || f.hittingSince != since) {
            f.hitting = m;
            f.hittingSince = since;
            f.hit.clear();
            Location eye = p.getEyeLocation();
            fx.arc(eye, m.reach, m.arc, tilt(m), SLASH_RGB);
        }
        Location eye = p.getEyeLocation();
        double attack = held.blade.attackDamage(held.stats);
        int landed = 0;
        for (LivingEntity t : targets(p, m.reach + 2)) {
            if (f.hit.contains(t.getEntityId())) {
                continue;
            }
            double[] box = box(t);
            if (!Geometry.swingHits(
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                eye.getYaw(),
                m.reach,
                m.arc,
                box[0],
                box[1],
                box[2],
                box[3],
                box[4])) {
                continue;
            }
            f.hit.add(t.getEntityId());
            if (hit(p, f, t, attack * m.damage, m.push, m.launch, m.id, m.style)) {
                landed++;
            }
        }
        if (landed > 0) {
            wear(p, held, landed);
        }
    }

    /** The tilt of a move's particle arc: finishers and launchers cut vertically, the rest across. */
    private static double tilt(Move m) {
        if (m.launch > 0 || m.lift < 0) {
            return 80;
        }
        return m.id.hashCode() % 2 == 0 ? 20 : -20;
    }

    /**
     * Deals a cut: damage as the player (so protection plugins and mods see it), past the target's hurt cooldown,
     * then the push and launch.
     *
     * @return whether it landed
     */
    boolean hit(Player p, Fighter f, LivingEntity t, double damage, double push, double launch, String move,
        int style) {
        if (t.isDead()) {
            return false;
        }
        double before = t.getHealth();
        t.setNoDamageTicks(0);
        dealing = true;
        try {
            t.damage(damage, p);
        } finally {
            dealing = false;
        }
        if (t.getHealth() >= before && !t.isDead()) {
            return false; // cancelled by someone (a region, a god mode)
        }
        Vector away = t.getLocation()
            .toVector()
            .subtract(
                p.getLocation()
                    .toVector())
            .setY(0);
        if (away.lengthSquared() > 1e-6) {
            away.normalize()
                .multiply(push);
        }
        double vy = launch != 0 ? launch
            : Math.max(-0.08, t.getVelocity()
                .getY()) * 0.5;
        t.setVelocity(away.setY(vy));
        if (launch > 0) {
            t.setFallDistance(0);
        }
        f.rank.hit(move, style, now);
        f.hits++;
        f.lastHit = now;
        Location at = t.getLocation()
            .add(0, box(t)[4], 0);
        fx.particle(at, KunftigParticle.CRIT, 6, 0.3, 0.2);
        fx.particle(at, KunftigParticle.DAMAGE_INDICATOR, 2, 0.2, 0.05);
        Effects.sound(at, Sound.IRONGOLEM_HIT, 0.3F, 1.6F);
        return true;
    }

    private void wear(Player p, BladeItems.Held held, int amount) {
        boolean wasBroken = held.stats.broken(held.blade);
        held.stats.wear(held.blade, amount);
        items.write(held.item, held.blade, held.stats);
        if (!wasBroken && held.stats.broken(held.blade)) {
            Effects.sound(p.getLocation(), Sound.ITEM_BREAK, 1, 0.8F);
            p.sendMessage(ChatColor.RED + "Sua lâmina quebrou. Ela ainda corta, mas sem força. /overkill reparar");
        }
    }

    // ================================================================================================================
    // Slash arts

    /** Holding the raised sword charges the slash art; lowering it after a full charge releases it. */
    private void charge(Player p, Fighter f, BladeItems.Held held) {
        SlashArt art = Arsenal.SLASH_ARTS.get(held.blade.slashArt);
        if (art == null) {
            return;
        }
        if (p.isBlocking()) {
            f.charge.hold(now);
            float progress = f.charge.progress(now, art.chargeTicks);
            if (progress >= 1 && !f.chargeAnnounced) {
                f.chargeAnnounced = true;
                Effects.sound(p.getLocation(), Sound.ORB_PICKUP, 0.6F, 1.8F);
                fx.particle(
                    p.getLocation()
                        .add(0, 1, 0),
                    KunftigParticle.ENCHANT,
                    20,
                    0.6,
                    0.5);
            }
            if ((now & 1) == 0) {
                kunftig.hud(
                    p,
                    HudElement.builder("overkill:carga")
                        .anchor(HudElement.Anchor.CENTER)
                        .offset(0, 22)
                        .text(progress >= 1 ? "§d§l" + art.name : "§7Carregando...")
                        .bar(progress, 60, progress >= 1 ? 0xFFC77DFF : 0xFFFFFFFF, 0x80000000)
                        .build());
            }
            return;
        }
        if (f.charge.holding()) {
            boolean charged = f.charge.release(now, art.chargeTicks);
            f.chargeAnnounced = false;
            kunftig.hudRemove(p, "overkill:carga");
            if (charged) {
                release(p, f, held, art);
            }
        }
    }

    private void release(Player p, Fighter f, BladeItems.Held held, SlashArt art) {
        if (held.stats.broken(held.blade)) {
            p.sendMessage(ChatColor.RED + "Uma lâmina quebrada não usa Slash Art.");
            return;
        }
        if (!held.stats.spend(art.cost)) {
            p.sendMessage(ChatColor.RED + "Proud Soul insuficiente (" + art.cost + ") para " + art.name + ".");
            return;
        }
        items.write(held.item, held.blade, held.stats);
        kunftig.state(p, art.id);
        f.combo.reset();
        double damage = held.blade.attackDamage(held.stats) * art.damage;
        switch (art.kind) {
            case DIMENSION:
                effects.add(new DimensionCut(p, f, art, damage, aimPoint(p, f, art.range)));
                break;
            case WAVE:
                effects.add(new Wave(p, f, art, damage));
                break;
            case CIRCLE:
                effects.add(new CircleCut(p, f, art, damage));
                break;
            default:
                break;
        }
        Effects.sound(p.getLocation(), Sound.WITHER_SHOOT, 0.7F, 1.4F);
    }

    /** Where a ranged art lands: the locked target, else what the player looks at, else the end of his reach. */
    private Location aimPoint(Player p, Fighter f, double range) {
        if (f.lock != null && lockable(p, f.lock, range)) {
            double[] b = box(f.lock);
            return new Location(p.getWorld(), b[0], b[1], b[2]);
        }
        LivingEntity looked = picked(p, range, 2.0);
        if (looked != null) {
            double[] b = box(looked);
            return new Location(p.getWorld(), b[0], b[1], b[2]);
        }
        Location eye = p.getEyeLocation();
        Block block = targetBlock(p, (int) range);
        double dist = block == null ? range
            : Math.min(
                range,
                block.getLocation()
                    .add(0.5, 0.5, 0.5)
                    .distance(eye) - 0.6);
        return eye.clone()
            .add(
                eye.getDirection()
                    .multiply(Math.max(1.5, dist)));
    }

    @SuppressWarnings("deprecation")
    private static Block targetBlock(Player p, int range) {
        try {
            return p.getTargetBlock((java.util.HashSet<Byte>) null, range);
        } catch (IllegalStateException e) {
            return null; // the block iterator fails at the edge of the world
        }
    }

    // ================================================================================================================
    // Kunftig keys

    void input(Player p, String action, boolean pressed, float yaw, int movement) {
        Fighter f = fighters.get(p.getUniqueId());
        BladeItems.Held held = items.read(p.getItemInHand());
        if (f == null || held == null || !pressed) {
            return;
        }
        switch (action) {
            case KunftigInput.SPECIAL:
                summon(p, f, held);
                break;
            case KunftigInput.SKILL:
                airTrick(p, f, held);
                break;
            case KunftigInput.DODGE:
                dodge(p, f, yaw, movement);
                break;
            case KunftigInput.LOCK_ON:
                if (f.lock != null) {
                    unlock(p, f);
                } else {
                    lock(p, f);
                }
                break;
            default:
                break;
        }
    }

    /** Summoned swords: a volley that homes in on the target (or flies straight). */
    private void summon(Player p, Fighter f, BladeItems.Held held) {
        if (now < f.summonReady) {
            return;
        }
        if (!held.stats.spend(Arsenal.Summoned.COST)) {
            p.sendMessage(ChatColor.RED + "Proud Soul insuficiente para espadas invocadas.");
            return;
        }
        items.write(held.item, held.blade, held.stats);
        f.summonReady = now + Arsenal.Summoned.COOLDOWN;
        LivingEntity target = f.lock != null && lockable(p, f.lock, 24) ? f.lock : picked(p, 24, 2.5);
        double damage = held.blade.attackDamage(held.stats) * Arsenal.Summoned.DAMAGE;
        Location eye = p.getEyeLocation();
        double[] fwd = Geometry.look(eye.getYaw(), 0);
        for (int i = 0; i < Arsenal.Summoned.SWORDS; i++) {
            double side = (i - (Arsenal.Summoned.SWORDS - 1) / 2.0) * 0.9;
            Location start = eye.clone()
                .add(-fwd[2] * side, 0.6 + Math.abs(side) * 0.3, fwd[0] * side);
            effects.add(new SummonedSword(p, f, start, target, damage, i * 2));
        }
        Effects.sound(eye, Sound.ENDERMAN_TELEPORT, 0.5F, 2F);
    }

    /** A dash in the direction held (back when none), invulnerable for a few ticks. */
    private void dodge(Player p, Fighter f, float yaw, int movement) {
        if (now < f.dodgeReady) {
            return;
        }
        f.dodgeReady = now + Arsenal.Dodge.COOLDOWN;
        f.invulnerableUntil = now + Arsenal.Dodge.INVULNERABLE;
        f.dodgeRewarded = false;
        double[] fwd = Geometry.look(yaw, 0);
        double x = 0;
        double z = 0;
        if ((movement & com.akashic.kunftig.api.bukkit.input.KunftigInputEvent.FORWARD) != 0) {
            x += fwd[0];
            z += fwd[2];
        }
        if ((movement & com.akashic.kunftig.api.bukkit.input.KunftigInputEvent.BACK) != 0) {
            x -= fwd[0];
            z -= fwd[2];
        }
        // right of the look is (-fwd.z, fwd.x)
        if ((movement & com.akashic.kunftig.api.bukkit.input.KunftigInputEvent.RIGHT) != 0) {
            x -= fwd[2];
            z += fwd[0];
        }
        if ((movement & com.akashic.kunftig.api.bukkit.input.KunftigInputEvent.LEFT) != 0) {
            x += fwd[2];
            z -= fwd[0];
        }
        if (x * x + z * z < 1e-6) {
            x = -fwd[0];
            z = -fwd[2];
        }
        double len = Math.sqrt(x * x + z * z);
        p.setVelocity(
            new Vector(x / len * Arsenal.Dodge.SPEED, onGround(p) ? 0.25 : 0.1, z / len * Arsenal.Dodge.SPEED));
        p.setFallDistance(0);
        fx.particle(p.getLocation(), KunftigParticle.CLOUD, 8, 0.3, 0.02);
        Effects.sound(p.getLocation(), Sound.BAT_TAKEOFF, 0.4F, 1.6F);
    }

    /** Blinks next to the locked target, facing it. */
    private void airTrick(Player p, Fighter f, BladeItems.Held held) {
        if (now < f.trickReady || f.lock == null || !lockable(p, f.lock, Arsenal.AirTrick.RANGE)) {
            return;
        }
        if (!held.stats.spend(Arsenal.AirTrick.COST)) {
            p.sendMessage(ChatColor.RED + "Proud Soul insuficiente para o Truque Aéreo.");
            return;
        }
        items.write(held.item, held.blade, held.stats);
        f.trickReady = now + Arsenal.AirTrick.COOLDOWN;
        Location from = p.getLocation();
        Location t = f.lock.getLocation();
        Vector back = from.toVector()
            .subtract(t.toVector())
            .setY(0);
        if (back.lengthSquared() < 1e-6) {
            back = new Vector(1, 0, 0);
        }
        back.normalize()
            .multiply(1.6 + box(f.lock)[3]);
        Location to = t.clone()
            .add(back);
        to.setY(t.getY() + 0.1);
        to.setYaw((float) Geometry.yawTo(to.getX(), to.getZ(), t.getX(), t.getZ()));
        to.setPitch(10);
        if (to.getBlock()
            .getType()
            .isSolid()
            || to.clone()
                .add(0, 1, 0)
                .getBlock()
                .getType()
                .isSolid()) {
            to = t.clone()
                .add(0, box(f.lock)[4] * 2 + 0.2, 0); // no room beside it: above it
            to.setYaw(from.getYaw());
        }
        fx.line(from.clone()
            .add(0, 1, 0),
            to.clone()
                .add(0, 1, 0),
            KunftigParticle.PORTAL,
            0.5);
        p.teleport(to);
        p.setVelocity(new Vector(0, 0.15, 0));
        p.setFallDistance(0);
        f.rank.bonus(Arsenal.AirTrick.STYLE, now);
        Effects.sound(to, Sound.ENDERMAN_TELEPORT, 0.6F, 1.4F);
    }

    private void lock(Player p, Fighter f) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        Location eye = p.getEyeLocation();
        double[] look = Geometry.look(eye.getYaw(), eye.getPitch());
        for (LivingEntity t : targets(p, Arsenal.LockOn.RANGE)) {
            double[] b = box(t);
            double angle = Geometry.angleTo(look, eye.getX(), eye.getY(), eye.getZ(), b[0], b[1], b[2]);
            if (angle > Arsenal.LockOn.CONE || !p.hasLineOfSight(t)) {
                continue;
            }
            double score = angle + eye.distance(new Location(p.getWorld(), b[0], b[1], b[2])) * 1.5;
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best == null) {
            p.sendMessage(ChatColor.GRAY + "Nenhum alvo à vista.");
            return;
        }
        f.lock = best;
        kunftig.lockOn(p, best, Arsenal.LockOn.STRENGTH, (float) Arsenal.LockOn.RANGE + 4);
        Effects.sound(p.getLocation(), Sound.CLICK, 0.5F, 1.8F);
    }

    void unlock(Player p, Fighter f) {
        if (f.lock != null) {
            f.lock = null;
            kunftig.release(p);
        }
    }

    // ================================================================================================================
    // Damage taken and kills

    /** The player was hit: dodged if inside the invulnerable ticks (a stylish dodge), else the rank drops. */
    boolean dodged(Player p) {
        Fighter f = fighters.get(p.getUniqueId());
        if (f == null || now > f.invulnerableUntil) {
            return false;
        }
        if (!f.dodgeRewarded) {
            f.dodgeRewarded = true;
            f.rank.bonus(Arsenal.Dodge.STYLE, now);
            fx.particle(
                p.getLocation()
                    .add(0, 1, 0),
                KunftigParticle.END_ROD,
                10,
                0.4,
                0.05);
        }
        return true;
    }

    void hurt(Player p, double damage) {
        Fighter f = fighters.get(p.getUniqueId());
        if (f != null) {
            f.rank.hurt(damage);
            f.charge.cancel();
        }
    }

    /** Something died by this player's hand: the blade in his hand counts it and earns proud soul. */
    void kill(Player killer, LivingEntity victim) {
        BladeItems.Held held = items.read(killer.getItemInHand());
        if (held == null) {
            return;
        }
        Fighter f = fighters.get(killer.getUniqueId());
        int soul = BladeStats.proudSoulFor(victim.getMaxHealth(), f == null ? -1 : f.rank.rank());
        held.stats.kills++;
        held.stats.proudSoul += soul;
        items.write(held.item, held.blade, held.stats);
        kunftig.hud(
            killer,
            HudElement.builder("overkill:alma")
                .anchor(HudElement.Anchor.BOTTOM)
                .offset(0, -62)
                .text("§b+" + soul + " Proud Soul")
                .duration(40, 15)
                .build());
        if (f != null && f.lock == victim) {
            unlock(killer, f);
        }
    }

    // ================================================================================================================
    // HUD

    private void hud(Player p, Fighter f) {
        if ((now & 1) != 0) {
            return;
        }
        StyleRank r = f.rank;
        int rank = r.rank();
        String key = rank < 0 ? "" : rank + ":" + Math.round(r.progress() * 40);
        if (key.equals(f.hudRank)) {
            return;
        }
        f.hudRank = key;
        if (rank < 0) {
            kunftig.hudRemove(p, "overkill:rank");
            kunftig.hudRemove(p, "overkill:rank_titulo");
            return;
        }
        ChatColor c = RANK_COLORS[rank];
        kunftig.hud(
            p,
            HudElement.builder("overkill:rank")
                .anchor(HudElement.Anchor.RIGHT)
                .offset(-16, -34)
                .scale(3)
                .text(c + "" + ChatColor.BOLD + StyleRank.NAMES[rank])
                .build());
        kunftig.hud(
            p,
            HudElement.builder("overkill:rank_titulo")
                .anchor(HudElement.Anchor.RIGHT)
                .offset(-16, 0)
                .text(c + "" + ChatColor.BOLD + RANK_TITLES[rank] + (f.hits >= 2 ? "\n§7" + f.hits + " golpes" : ""))
                .bar(r.progress(), 64, 0xFF000000 | rgb(c), 0x80000000)
                .build());
    }

    private static int rgb(ChatColor c) {
        switch (c) {
            case GRAY:
                return 0xAAAAAA;
            case AQUA:
                return 0x55FFFF;
            case GREEN:
                return 0x55FF55;
            case YELLOW:
                return 0xFFFF55;
            case GOLD:
                return 0xFFAA00;
            case RED:
                return 0xFF5555;
            default:
                return 0xFFFFFF;
        }
    }

    // ================================================================================================================
    // Targets

    /** Living things around the player that his blade may cut (players only with PvP on). */
    List<LivingEntity> targets(Player p, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof LivingEntity && !e.isDead() && (plugin.pvp() || !(e instanceof Player))) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    List<LivingEntity> targetsAround(Player p, Location at, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        World w = at.getWorld();
        if (w == null) {
            return out;
        }
        for (Entity e : w.getEntities()) {
            if (e instanceof LivingEntity && e != p
                && !e.isDead()
                && (plugin.pvp() || !(e instanceof Player))
                && e.getLocation()
                    .distanceSquared(at) <= (radius + 3) * (radius + 3)) {
                double[] b = box((LivingEntity) e);
                double dx = Math.max(Math.abs(b[0] - at.getX()) - b[3], 0);
                double dy = Math.max(Math.abs(b[1] - at.getY()) - b[4], 0);
                double dz = Math.max(Math.abs(b[2] - at.getZ()) - b[3], 0);
                if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                    out.add((LivingEntity) e);
                }
            }
        }
        return out;
    }

    /** What the player looks at within {@code range}: the target nearest the look ray, at most {@code off} away. */
    LivingEntity picked(Player p, double range, double off) {
        Location eye = p.getEyeLocation();
        double[] dir = Geometry.look(eye.getYaw(), eye.getPitch());
        LivingEntity best = null;
        double bestOff = Double.MAX_VALUE;
        for (LivingEntity t : targets(p, range)) {
            double[] b = box(t);
            double[] r = Geometry.alongRay(eye.getX(), eye.getY(), eye.getZ(), dir, b[0], b[1], b[2]);
            double o = r[1] - b[3];
            if (r[0] > 0 && r[0] <= range && o <= off && o < bestOff && p.hasLineOfSight(t)) {
                bestOff = o;
                best = t;
            }
        }
        return best;
    }

    private boolean lockable(Player p, LivingEntity t, double range) {
        return t.isValid() && !t.isDead()
            && t.getWorld()
                .equals(p.getWorld())
            && t.getLocation()
                .distanceSquared(p.getLocation()) <= range * range;
    }

    /**
     * A target's box: centre x, y, z and half width, half height. Bukkit 1.7.10 does not tell an entity's size;
     * the eyes give its height (eyes sit at about 85% of it, for vanilla and modded mobs alike).
     */
    static double[] box(LivingEntity t) {
        Location l = t.getLocation();
        double eye = t.getEyeLocation()
            .getY() - l.getY();
        double height = Math.max(0.3, eye / 0.85);
        double half = Math.max(0.3, Math.min(2.5, height * 0.3));
        return new double[] { l.getX(), l.getY() + height / 2, l.getZ(), half, height / 2 };
    }

    // ================================================================================================================
    // Effects over time

    /** Something that lasts some ticks (a slash art, a summoned sword). */
    private abstract class Effect {

        final Player owner;
        final Fighter fighter;
        int age;

        Effect(Player owner, Fighter fighter) {
            this.owner = owner;
            this.fighter = fighter;
        }

        /** @return whether it goes on */
        abstract boolean step();

        final boolean tick() {
            if (!owner.isOnline()) {
                return false;
            }
            age++;
            return step();
        }

        void end() {}
    }

    /** Cuts space at a point: a burst of slashes hitting everything around it several times. */
    private final class DimensionCut extends Effect {

        private final SlashArt art;
        private final double damage;
        private final Location point;
        private final Display display;
        private int pulses;

        DimensionCut(Player p, Fighter f, SlashArt art, double damage, Location point) {
            super(p, f);
            this.art = art;
            this.damage = damage;
            this.point = point;
            this.display = kunftig.spawn(point, "overkill:corte_dimensional", "cortar", 64);
            fx.particle(point, KunftigParticle.PORTAL, 40, art.radius * 0.5, 0.6);
        }

        @Override
        boolean step() {
            if (age >= 6 && (age - 6) % 3 == 0 && pulses < art.hits) {
                pulses++;
                for (LivingEntity t : targetsAround(owner, point, art.radius)) {
                    hit(owner, fighter, t, damage, 0.05, 0.12, art.id, art.style / art.hits);
                }
                for (int i = 0; i < 3; i++) {
                    double a = Math.random() * Math.PI;
                    double r = art.radius;
                    Location from = point.clone()
                        .add(Math.cos(a) * r, (Math.random() - 0.5) * r, Math.sin(a) * r);
                    Location to = point.clone()
                        .add(-Math.cos(a) * r, (Math.random() - 0.5) * r, -Math.sin(a) * r);
                    fx.line(from, to, KunftigParticle.END_ROD, 0.35);
                }
                Effects.sound(point, Sound.FIREWORK_BLAST, 0.6F, 1.6F);
            }
            return age < 6 + art.hits * 3 + 8;
        }

        @Override
        void end() {
            kunftig.remove(display);
        }
    }

    /** A crescent wave flying straight ahead, cutting everything on its way once. */
    private final class Wave extends Effect {

        private static final double SPEED = 1.2;
        private final SlashArt art;
        private final double damage;
        private final Location at;
        private final Vector dir;
        private final Display display;
        private final java.util.Set<Integer> hitIds = new java.util.HashSet<>();

        Wave(Player p, Fighter f, SlashArt art, double damage) {
            super(p, f);
            this.art = art;
            this.damage = damage;
            Location eye = p.getEyeLocation();
            float pitch = Math.max(-30, Math.min(30, eye.getPitch()));
            double[] d = Geometry.look(eye.getYaw(), pitch);
            this.dir = new Vector(d[0], d[1], d[2]);
            this.at = eye.clone()
                .add(0, -0.4, 0)
                .add(dir.clone()
                    .multiply(1.2));
            Location shown = at.clone();
            shown.setYaw(eye.getYaw());
            this.display = kunftig.spawn(shown, "overkill:onda", "voar", 64);
            kunftig.turn(display, eye.getYaw(), pitch, (float) (Math.random() * 60 - 30));
            kunftig.fly(display, dir.clone()
                .multiply(SPEED));
        }

        @Override
        boolean step() {
            at.add(dir.clone()
                .multiply(SPEED));
            if (at.getBlock()
                .getType()
                .isSolid()) {
                fx.particle(at, KunftigParticle.EXPLOSION, 1, 0, 0);
                return false;
            }
            for (LivingEntity t : targetsAround(owner, at, art.radius)) {
                if (hitIds.add(t.getEntityId())) {
                    hit(owner, fighter, t, damage, 0.7, 0.25, art.id, art.style);
                }
            }
            fx.dust(at, ART_RGB, 4, 0.4);
            fx.particle(at, KunftigParticle.END_ROD, 1, 0.3, 0.01);
            return age * SPEED < art.range;
        }

        @Override
        void end() {
            kunftig.remove(display);
        }
    }

    /** One spinning cut all around the player, hitting twice. */
    private final class CircleCut extends Effect {

        private final SlashArt art;
        private final double damage;
        private final Display display;

        CircleCut(Player p, Fighter f, SlashArt art, double damage) {
            super(p, f);
            this.art = art;
            this.damage = damage;
            this.display = kunftig.spawn(p.getLocation(), "overkill:corte_circular", "girar", 48);
        }

        @Override
        boolean step() {
            if (age == 3 || age == 3 + 5 && art.hits > 1) {
                Location c = owner.getLocation();
                fx.ring(c, art.range * 0.8, ART_RGB);
                for (LivingEntity t : targetsAround(owner, c.clone()
                    .add(0, 1, 0), art.range)) {
                    hit(owner, fighter, t, damage, 0.8, 0.2, art.id, art.style / art.hits);
                }
                Effects.sound(c, Sound.ENDERDRAGON_WINGS, 0.8F, 1.3F);
            }
            return age < 16;
        }

        @Override
        void end() {
            kunftig.remove(display);
        }
    }

    /** One summoned sword: waits its turn, then homes in on the target and cuts it. */
    private final class SummonedSword extends Effect {

        private final Location at;
        private final LivingEntity target;
        private final double damage;
        private final int delay;
        private final Vector straight;
        private final Display display;

        SummonedSword(Player p, Fighter f, Location start, LivingEntity target, double damage, int delay) {
            super(p, f);
            this.at = start.clone();
            this.target = target;
            this.damage = damage;
            this.delay = delay;
            Location eye = p.getEyeLocation();
            this.straight = eye.getDirection()
                .multiply(Arsenal.Summoned.SPEED);
            Location shown = at.clone();
            shown.setYaw(eye.getYaw());
            this.display = kunftig.spawn(shown, "overkill:espada_invocada", "voar", 48);
            kunftig.turn(display, eye.getYaw(), eye.getPitch(), 0);
        }

        @Override
        boolean step() {
            if (age <= delay) {
                fx.particle(at, KunftigParticle.ENCHANTED_HIT, 1, 0.05, 0);
                return true;
            }
            Vector step;
            boolean homing = target != null && target.isValid() && !target.isDead();
            if (homing) {
                double[] b = box(target);
                Vector to = new Vector(b[0], b[1], b[2]).subtract(at.toVector());
                double dist = to.length();
                if (dist <= b[3] + 0.6) {
                    hit(owner, fighter, target, damage, 0.15, 0.05, "espadas", Arsenal.Summoned.STYLE);
                    fx.particle(at, KunftigParticle.ENCHANTED_HIT, 8, 0.2, 0.2);
                    return false;
                }
                step = to.multiply(Math.min(Arsenal.Summoned.SPEED, dist) / dist);
            } else {
                step = straight.clone();
                for (LivingEntity t : targetsAround(owner, at, 0.6)) {
                    hit(owner, fighter, t, damage, 0.15, 0.05, "espadas", Arsenal.Summoned.STYLE);
                    return false;
                }
            }
            Location from = at.clone();
            at.add(step);
            if (at.getBlock()
                .getType()
                .isSolid()) {
                return false;
            }
            fx.line(from, at, KunftigParticle.END_ROD, 0.6);
            Location shown = at.clone();
            float yaw = (float) Geometry.yawTo(from.getX(), from.getZ(), at.getX(), at.getZ());
            float pitch = (float) Geometry.pitchTo(from.getX(), from.getY(), from.getZ(), at.getX(), at.getY(), at.getZ());
            shown.setYaw(yaw);
            kunftig.glide(display, shown, 1);
            kunftig.turn(display, yaw, pitch, 0);
            return age < Arsenal.Summoned.LIFETIME;
        }

        @Override
        void end() {
            kunftig.remove(display);
        }
    }

    // ================================================================================================================
    // Commands

    /** Proud soul into a refine of the blade in hand. */
    String refine(Player p) {
        BladeItems.Held held = items.read(p.getItemInHand());
        if (held == null) {
            return ChatColor.RED + "Segure uma lâmina.";
        }
        int cost = held.stats.refineCost();
        if (!held.stats.spend(cost)) {
            return ChatColor.RED + "Refinar custa " + cost + " Proud Soul (a lâmina tem " + held.stats.proudSoul + ").";
        }
        held.stats.refine++;
        items.write(held.item, held.blade, held.stats);
        Effects.sound(p.getLocation(), Sound.ANVIL_USE, 0.8F, 1.2F);
        return ChatColor.GREEN + "Refino +" + held.stats.refine + " (custou " + cost + " Proud Soul).";
    }

    /** Proud soul into a full repair of the blade in hand. */
    String repair(Player p) {
        BladeItems.Held held = items.read(p.getItemInHand());
        if (held == null) {
            return ChatColor.RED + "Segure uma lâmina.";
        }
        if (held.stats.wear == 0) {
            return ChatColor.GRAY + "A lâmina está inteira.";
        }
        int cost = held.stats.repairCost();
        if (!held.stats.spend(cost)) {
            return ChatColor.RED + "Reparar custa " + cost + " Proud Soul (a lâmina tem " + held.stats.proudSoul + ").";
        }
        held.stats.wear = 0;
        items.write(held.item, held.blade, held.stats);
        Effects.sound(p.getLocation(), Sound.ANVIL_USE, 0.8F, 0.9F);
        return ChatColor.GREEN + "Lâmina reparada (custou " + cost + " Proud Soul).";
    }

    /** On disable: effects gone, cameras free, keys unbound, HUD cleared. */
    void shutdown() {
        for (Effect e : effects) {
            e.end();
        }
        effects.clear();
        for (Map.Entry<UUID, Fighter> e : fighters.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                unlock(p, e.getValue());
                kunftig.bind(p, java.util.Collections.<String>emptyList());
                for (String id : new String[] { "overkill:rank", "overkill:rank_titulo", "overkill:carga" }) {
                    kunftig.hudRemove(p, id);
                }
            }
        }
        fighters.clear();
    }

    /** Debug line for /overkill info. */
    String info(Player p) {
        BladeItems.Held held = items.read(p.getItemInHand());
        if (held == null) {
            return ChatColor.RED + "Segure uma lâmina.";
        }
        Blade b = held.blade;
        BladeStats s = held.stats;
        Fighter f = fighters.get(p.getUniqueId());
        return ChatColor.GOLD + b.name
            + ChatColor.GRAY
            + " ("
            + b.id
            + ") dano "
            + String.format(java.util.Locale.ROOT, "%.1f", b.attackDamage(s))
            + ", abates "
            + s.kills
            + ", proud soul "
            + s.proudSoul
            + ", refino "
            + s.refine
            + ", desgaste "
            + s.wear
            + "/"
            + b.durability
            + (f == null ? "" : ", rank " + (f.rank.rank() < 0 ? "-" : f.rank.name()))
            + (kunftig.sendsInput(p) ? ", teclas Kunftig ativas" : ", sem teclas Kunftig no cliente");
    }

    /** For /overkill dar. */
    ItemStack create(Blade b) {
        return items.create(b);
    }
}
