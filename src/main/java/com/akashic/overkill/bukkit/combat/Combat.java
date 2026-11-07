package com.akashic.overkill.bukkit.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.akashic.kunftig.api.bukkit.blade.BladeMotion;
import com.akashic.kunftig.api.bukkit.blade.KunftigBlades;
import com.akashic.kunftig.api.bukkit.blade.KunftigEffects;
import com.akashic.kunftig.api.bukkit.input.KunftigInput;
import com.akashic.overkill.bukkit.BladeItems;
import com.akashic.overkill.bukkit.ForgeItems;
import com.akashic.overkill.combo.Actor;
import com.akashic.overkill.combo.ComboMachine;
import com.akashic.overkill.combo.ComboState;
import com.akashic.overkill.combo.Combos;
import com.akashic.overkill.combo.Concentration;
import com.akashic.overkill.combo.Input;
import com.akashic.overkill.combo.Knockback;
import com.akashic.overkill.combo.Offset;
import com.akashic.overkill.combo.SlashArt;
import com.akashic.overkill.core.Drops;
import com.akashic.overkill.core.Star;

/**
 * SlashBlade's combat on a Thermos/Crucible server, driven by the combo engine ({@link Combos}): the clicks (a right
 * click, or a left click on an entity, which never hits on its own), the use button held and released (charge, slash
 * arts), the blade and body motion each state plays on the clients ({@link KunftigBlades}), the slash effects and
 * their hits (SlashBlade's {@code EntitySlashEffect}: area attacks around the player on its ticks 2..10, each target
 * once), damage, knockback, stun, the air hover, the concentration rank with its HUD and the DmC-style hit counter,
 * the lock-on held with Sneak, and the slash arts (Judgement Cut, drives, wave edge, circle and void slash). Main
 * thread.
 */
public final class Combat implements Listener {

    /** The pack namespace of the effects, models and HUD. */
    public static final String NS = "overkill";
    /** Ticks the hit counter keeps counting after the last hit (the client hides it at the same time). */
    static final int HITS_WINDOW = 60;
    /** Ticks between writes of a fight's changes to the item (a level up is written at once). */
    static final int FLUSH_TICKS = 20;

    private final Plugin plugin;
    private final BladeItems items;
    private final Progression progression;
    private final Combos combos = Combos.base();
    private final boolean pvp;
    private final boolean packSounds;
    private final Map<UUID, Fighter> fighters = new HashMap<>();
    private final Map<UUID, Stun> stuns = new HashMap<>();
    private final Map<UUID, UUID> revenge = new HashMap<>();
    private final List<Ticking> arts = new ArrayList<>();
    private final Random random = new Random();
    private BukkitTask task;
    private long now;
    private boolean dealing;
    private boolean warnedKeys;
    private final java.util.Set<UUID> failed = new java.util.HashSet<>();

    /**
     * @param announceStar from which star a new katana is announced to the server (null: never)
     */
    public Combat(Plugin plugin, BladeItems items, ForgeItems forgeItems, Drops drops, Star announceStar, boolean pvp,
        boolean packSounds) {
        this.plugin = plugin;
        this.items = items;
        this.progression = new Progression(this, forgeItems,
            new Progression.Settings(items.leveling(), drops, announceStar));
        this.pvp = pvp;
        this.packSounds = packSounds;
    }

    public void start() {
        Bukkit.getPluginManager()
            .registerEvents(this, plugin);
        KunftigInput.register(plugin);
        task = Bukkit.getScheduler()
            .runTaskTimer(plugin, this::tick, 1, 1);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Fighter f : fighters.values()) {
            leave(f);
        }
        fighters.clear();
        for (Ticking t : arts) {
            t.end();
        }
        arts.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    Combos combos() {
        return combos;
    }

    long now() {
        return now;
    }

    /** Whether an attack of ours is being dealt (our own damage events must not count as clicks). */
    public boolean dealing() {
        return dealing;
    }

    // ---- the tick ---------------------------------------------------------------------------------------------------

    private void tick() {
        now++;
        for (World w : Bukkit.getWorlds()) {
            for (Player p : w.getPlayers()) {
                try {
                    tick(p);
                } catch (RuntimeException e) {
                    // one player's trouble must not stop everyone's combat; logged once per player
                    if (failed.add(p.getUniqueId())) {
                        plugin.getLogger()
                            .log(java.util.logging.Level.WARNING, "combate de " + p.getName() + " falhou", e);
                    }
                }
            }
        }
        tickStuns();
        for (Iterator<Ticking> it = arts.iterator(); it.hasNext();) {
            Ticking t = it.next();
            if (!t.step()) {
                t.end();
                it.remove();
            }
        }
        if ((now & 255) == 0) {
            revenge.entrySet()
                .removeIf(e -> Bukkit.getPlayer(e.getValue()) == null);
        }
    }

    private void tick(Player p) {
        int slot = p.getInventory()
            .getHeldItemSlot();
        Fighter f = fighters.get(p.getUniqueId());
        if (f != null && f.dirty && f.slot != slot) {
            flush(f);
        }
        ItemStack hand = p.getItemInHand();
        BladeItems.Held held = items.read(hand);
        if (held == null) {
            if (f != null) {
                leave(f);
                fighters.remove(p.getUniqueId());
            }
            return;
        }
        if (f == null) {
            f = join(p);
        }
        // While the stats changed and are not written yet, ours are newer than the item's.
        if (!(f.dirty && f.slot == slot && f.blade == held.blade)) {
            f.blade = held.blade;
            f.stats = held.stats;
        }
        f.slot = slot;
        if (hand.getDurability() != 0) {
            // A vanilla sword wears by mining; ours keep their wear in the data.
            hand.setDurability((short) 0);
        }
        if (f.stats.star == null) {
            progression.rollStar(f, f.blade.name);
        }
        if (f.dirty && !f.using && (f.levelUp || now - f.lastFlush >= FLUSH_TICKS)) {
            flush(f);
        }
        bindKeys(f);
        f.sampleMotion();
        boolean ground = f.onGround();
        if (ground && !f.wasOnGround) {
            f.fallDecreaseRate = 0;
            f.machine.land(now, f);
        }
        f.wasOnGround = ground;
        boolean blocking = p.isBlocking();
        if (blocking) {
            f.useTicks = f.using ? f.useTicks + 1 : 1;
            f.using = true;
            f.machine.hold(now, f);
        } else if (f.using) {
            int heldTicks = f.useTicks;
            f.using = false;
            f.useTicks = 0;
            release(f, heldTicks);
        }
        boolean charged = f.using && f.useTicks > SlashArt.CHARGE_TICKS && art(f) != null;
        KunftigBlades.setCharged(p, charged);
        f.machine.tick(now, f);
        tickSlashes(f);
        if (f.lockTarget != null && (f.lockTarget.isDead() || !f.lockTarget.isValid())) {
            lock(f, null);
        }
        if (f.hits > 0 && now - f.lastHit > HITS_WINDOW) {
            f.hits = 0;
        }
    }

    private Fighter join(Player p) {
        Fighter f = new Fighter(p, this);
        fighters.put(p.getUniqueId(), f);
        bindKeys(f);
        return f;
    }

    /** The held keys, once the client listens (it may register the channel after the blade is already in hand). */
    private void bindKeys(Fighter f) {
        if (!f.keysBound && KunftigInput.sendsInput(f.player)) {
            f.keysBound = true;
            try {
                KunftigInput.bind(f.player, KunftigInput.KEYS, KunftigInput.SPECIAL);
            } catch (IllegalArgumentException e) {
                // a Kunftig whose API refuses the held keys: the combo still works on clicks, Sneak and the ground
                KunftigInput.bind(f.player, KunftigInput.SPECIAL);
                if (!warnedKeys) {
                    warnedKeys = true;
                    plugin.getLogger()
                        .warning("O Kunftig deste servidor não aceita as teclas seguradas (" + e.getMessage()
                            + "): comandos com frente/trás ficam sem as teclas. Atualize o Kunftig.");
                }
            }
        }
    }

    /**
     * Writes the fighter's stats to the blade in its slot (name and lore too). Never while the use button is held: a
     * changed item in hand makes the client drop the use (the charge), so a fight's changes wait for the release.
     */
    private void flush(Fighter f) {
        f.dirty = false;
        f.levelUp = false;
        f.lastFlush = now;
        if (f.slot < 0 || f.stats == null) {
            return;
        }
        ItemStack it = f.player.getInventory()
            .getItem(f.slot);
        BladeItems.Held h = items.read(it);
        if (h == null || h.blade != f.blade) {
            return;
        }
        items.write(it, f.blade, f.stats);
        f.player.getInventory()
            .setItem(f.slot, it);
    }

    private void leave(Fighter f) {
        if (f.dirty) {
            flush(f);
        }
        Player p = f.player;
        if (p.isOnline()) {
            KunftigInput.bind(p);
            KunftigBlades.setCharged(p, false);
            if (f.lockTarget != null) {
                KunftigEffects.lockOn(p, NS, null, 0);
            }
        }
        f.lockTarget = null;
        f.slashes.clear();
    }

    private Fighter fighter(Player p) {
        Fighter f = fighters.get(p.getUniqueId());
        if (f == null && items.read(p.getItemInHand()) != null) {
            f = join(p);
            BladeItems.Held h = items.read(p.getItemInHand());
            f.blade = h.blade;
            f.stats = h.stats;
        }
        return f;
    }

    /** The keys a Kunftig client reports, as combo inputs (no ground, sneak or use: the server knows those). */
    int heldInputs(Player p) {
        int k = KunftigInput.getKeys(p);
        int bits = 0;
        bits |= (k & KunftigInput.KEY_FORWARD) != 0 ? Input.FORWARD : 0;
        bits |= (k & KunftigInput.KEY_BACK) != 0 ? Input.BACK : 0;
        bits |= (k & KunftigInput.KEY_LEFT) != 0 ? Input.LEFT : 0;
        bits |= (k & KunftigInput.KEY_RIGHT) != 0 ? Input.RIGHT : 0;
        bits |= (k & KunftigInput.KEY_JUMP) != 0 ? Input.JUMP : 0;
        bits |= (k & KunftigInput.KEY_ATTACK) != 0 ? Input.L_DOWN : 0;
        bits |= (k & KunftigInput.KEY_PICK) != 0 ? Input.M_DOWN : 0;
        bits |= (k & KunftigInput.KEY_SPECIAL) != 0 ? Input.SPECIAL : 0;
        return bits;
    }

    /** The blade's slash art; none while broken (SlashBlade). */
    private SlashArt art(Fighter f) {
        if (f.blade == null || f.stats == null || f.stats.broken(f.blade)) {
            return null;
        }
        return SlashArt.byId(f.blade.slashArt);
    }

    // ---- clicks, hold, release --------------------------------------------------------------------------------------

    private void click(Fighter f, int clickBit) {
        if (f.lastClick == now) {
            return; // 1.7.10 sends two use packets for a click on a block
        }
        f.lastClick = now;
        f.machine.click(now, f, f.inputs() | clickBit);
    }

    private void release(Fighter f, int heldTicks) {
        SlashArt art = art(f);
        if (art != null && f.machine.release(now, f, heldTicks, art, 0) != null) {
            progression.art(f);
        }
    }

    /** The combo engine entered a state: the clients play it and what follows it (unless it is that very chain). */
    void entered(Fighter f, ComboState s, ComboMachine.Cause cause, long when) {
        if (cause == ComboMachine.Cause.TIMEOUT || cause == ComboMachine.Cause.RESET || s.isNone()
            || Combos.STANDBY.equals(s.id)) {
            return;
        }
        BladeMotion.Builder b = BladeMotion.builder();
        for (ComboState c : combos.chain(s.id, BladeMotion.MAX_STATES)) {
            b.state(c.start, c.end, c.loop, BladeMotion.duration(c.start, c.end, c.speed, c.timeout));
        }
        if (s.clip != null) {
            b.body(s.clip.start, s.clip.end, s.clip.blendLegs);
        }
        KunftigBlades.play(f.player, b.build());
    }

    // ---- slashes and hits -------------------------------------------------------------------------------------------

    /** SlashBlade's {@code doSlash}: the slash effect (hits for 10 ticks) and its arc on the clients. */
    void slash(Fighter f, float roll, float yawOffset, Offset offset, boolean mute, boolean critical, float ratio,
        Knockback knockback) {
        f.slashes.add(new SlashEffect(ratio, knockback, mute, critical));
        Location feet = f.player.getLocation();
        Vector look = feet.getDirection();
        double up = offset == Offset.RUSH ? (f.random.nextFloat() - 0.5) * 2 : offset == Offset.UP ? 0.8 : 0;
        Location at = feet.clone()
            .add(look.getX() * 0.3, 1.62 * 0.75 + look.getY() * 0.3 + up, look.getZ() * 0.3);
        KunftigEffects.slash(
            NS,
            at,
            feet.getYaw() + yawOffset,
            roll,
            f.blade.color,
            f.rank.level(now),
            1f);
    }

    private void tickSlashes(Fighter f) {
        for (Iterator<SlashEffect> it = f.slashes.iterator(); it.hasNext();) {
            SlashEffect e = it.next();
            e.age++;
            if (e.age == 2) {
                sound(f.player.getLocation(), e.mute ? Sfx.SLASH_MUTED : Sfx.SLASH, e.mute ? 0.5f : 0.8f,
                    e.mute ? 0.4f / (0.8f + 0.4f * random.nextFloat()) : 0.625f + 0.1f * random.nextFloat());
                if (e.critical) {
                    sound(f.player.getLocation(), Sfx.CRITICAL, 0.2f, 0.4f + 0.25f * random.nextFloat());
                }
            }
            if (e.hitsThisTick()) {
                for (LivingEntity t : areaAttack(f, e.knockback, e.hitRatio(), true, false, true, -1, e.hit)) {
                    e.hit.add(t.getUniqueId());
                }
            }
            if (e.done()) {
                it.remove();
            }
        }
    }

    /** The blade's reach: 4.5 (broken 3.25). */
    private double reach(Fighter f) {
        return f.stats != null && f.blade != null && f.stats.broken(f.blade) ? 3.25 : 4.5;
    }

    /**
     * SlashBlade's {@code areaAttack} around the player: every attackable target in reach (feet to feet) inside the box
     * pushed half the reach forward, damaged at {@code ratio}. Returns the targets found (hit or not).
     */
    List<LivingEntity> areaAttack(Fighter f, Knockback kb, float ratio, boolean forceHit, boolean resetHit,
        boolean mute, double reachArg, Set<UUID> exclude) {
        double r = reachArg > 0 ? reachArg : reach(f);
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity t : targets(f.player, r)) {
            if (exclude != null && exclude.contains(t.getUniqueId())) {
                continue;
            }
            found.add(t);
            strike(f, t, attack(f, t) * ratio, kb, forceHit, resetHit, true);
        }
        return found;
    }

    /** SlashBlade's {@code TargetSelector}: the sphere of {@code reach} around the feet, cut by the forward box. */
    List<LivingEntity> targets(Player p, double reach) {
        Location feet = p.getLocation();
        Vector look = feet.getDirection();
        double cx = feet.getX() + look.getX() * reach / 2;
        double cy = feet.getY() + 0.9 + look.getY() * reach / 2;
        double cz = feet.getZ() + look.getZ() * reach / 2;
        double hx = 0.3 + reach + 1;
        double hy = 0.9 + reach + 1;
        double limit = Math.max(reach, 2);
        List<LivingEntity> out = new ArrayList<>();
        double scan = reach * 1.6 + 2;
        for (Entity e : p.getNearbyEntities(scan, scan, scan)) {
            if (!(e instanceof LivingEntity) || e.isDead()) {
                continue;
            }
            LivingEntity t = (LivingEntity) e;
            Location l = t.getLocation();
            double h = height(t);
            if (Math.abs(l.getX() - cx) > hx + 0.3 || Math.abs(l.getZ() - cz) > hx + 0.3 || l.getY() > cy + hy
                || l.getY() + h < cy - hy) {
                continue;
            }
            if (l.distanceSquared(feet) > limit * limit) {
                continue;
            }
            if (attackable(p, t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static double height(LivingEntity t) {
        return Math.max(0.5, t.getEyeHeight() / 0.85);
    }

    /**
     * SlashBlade's {@code AttackablePredicate} with its defaults: hostile mobs, and anything that hurt or targets the
     * player; never villagers, the player's pets, or a mount with a player on it; players only with PvP.
     */
    boolean attackable(Player p, LivingEntity t) {
        if (t == p || t.isDead()) {
            return false;
        }
        if (t instanceof Player) {
            return pvp && ((Player) t).getGameMode() != org.bukkit.GameMode.CREATIVE;
        }
        if (t instanceof Villager || t.getPassenger() instanceof Player) {
            return false;
        }
        if (t instanceof Tameable && ((Tameable) t).isTamed() && p.equals(((Tameable) t).getOwner())) {
            return false;
        }
        if (p.getUniqueId()
            .equals(revenge.get(t.getUniqueId()))) {
            return true;
        }
        if (t instanceof Creature && p.equals(((Creature) t).getTarget())) {
            return true;
        }
        return t instanceof Monster || t instanceof Slime || t instanceof Ghast || t instanceof ComplexLivingEntity;
    }

    /**
     * The attack before the move's ratio: the blade's attack damage (SlashBlade's {@code ATTACK_DAMAGE}), the rank
     * bonus (rank / 2; from S, at least min(XP level, fortification): the fortification stands for SlashBlade's
     * refines) and the damage enchantments.
     */
    double attack(Fighter f, LivingEntity t) {
        double atk = f.blade.attackDamage(f.stats, progression.leveling());
        double bonus = f.rank.damageBonus(now);
        if (f.rank.rank(now) >= 5) {
            bonus = Math.max(bonus, Math.min(f.player.getLevel(), f.stats.enhancement));
        }
        ItemStack hand = f.player.getItemInHand();
        double ench = 0;
        if (hand != null) {
            ench += 1.25 * hand.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
            if (t instanceof Zombie || t instanceof Skeleton || t instanceof PigZombie || t instanceof Wither) {
                ench += 2.5 * hand.getEnchantmentLevel(Enchantment.DAMAGE_UNDEAD);
            }
            if (t instanceof Spider || t instanceof CaveSpider || t instanceof Silverfish) {
                ench += 2.5 * hand.getEnchantmentLevel(Enchantment.DAMAGE_ARTHROPODS);
            }
        }
        return atk + bonus + ench;
    }

    /**
     * One hit: i-frames bypassed ({@code forceHit}), the vanilla jump crit, the knockback replaced by SlashBlade's
     * factor, the stun of the current state, rank points and the hit counter. Returns whether it hurt.
     */
    @SuppressWarnings("deprecation")
    boolean strike(Fighter f, LivingEntity t, double damage, Knockback kb, boolean forceHit, boolean resetHit,
        boolean melee) {
        Player p = f.player;
        if (melee && p.getFallDistance() > 0 && !p.isOnGround() && !f.inWater() && p.getVehicle() == null
            && !p.isSprinting() && !p.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            damage *= 1.5;
        }
        if (damage <= 0 || t.isDead()) {
            return false;
        }
        Vector before = t.getVelocity();
        double hp = t.getHealth();
        if (forceHit) {
            t.setNoDamageTicks(0);
        }
        dealing = true;
        try {
            t.damage(damage, p);
        } finally {
            dealing = false;
        }
        boolean ok = t.isDead() || t.getHealth() < hp;
        if (resetHit) {
            t.setNoDamageTicks(0);
        }
        if (!ok) {
            return false;
        }
        knockback(t, before, kb);
        stun(t, f.machine.state().stun);
        progression.hit(f);
        f.rank.add(Concentration.HIT, now);
        KunftigEffects.rank(p, NS, (int) f.rank.points(now));
        f.hits = now - f.lastHit > HITS_WINDOW ? 1 : f.hits + 1;
        f.lastHit = now;
        KunftigEffects.hits(p, NS, f.hits);
        return true;
    }

    /** SlashBlade's knockback factor, applied after the vanilla knockback of the hit. */
    private static void knockback(LivingEntity t, Vector before, Knockback kb) {
        Vector after = t.getVelocity();
        if (kb == Knockback.SMASH) {
            double hx = before.getX() + (after.getX() - before.getX()) * kb.horizontal;
            double hz = before.getZ() + (after.getZ() - before.getZ()) * kb.horizontal;
            t.setVelocity(new Vector(hx, after.getY(), hz));
            return;
        }
        double vy = before.getY();
        if (kb.vertical > 0) {
            vy = Math.max(vy, kb.vertical);
        } else if (kb.vertical < 0) {
            vy = Math.min(vy, kb.vertical);
        }
        t.setVelocity(new Vector(0, vy, 0));
        if (kb.fall != 0) {
            t.setFallDistance((float) (Math.max(0, t.getFallDistance()) + kb.fall));
        }
    }

    // ---- stun -------------------------------------------------------------------------------------------------------

    private static final class Stun {

        final LivingEntity entity;
        long until;

        Stun(LivingEntity entity, long until) {
            this.entity = entity;
            this.until = until;
        }
    }

    /** SlashBlade's {@code StunManager.setStun}: mobs only, extended and never shortened, up to 200 ticks. */
    void stun(LivingEntity t, int ticks) {
        if (ticks <= 0 || t instanceof Player || !(t instanceof Creature || t instanceof Slime || t instanceof Ghast)) {
            return;
        }
        long until = now + Math.min(ticks, 200);
        Stun s = stuns.get(t.getUniqueId());
        if (s == null) {
            stuns.put(t.getUniqueId(), new Stun(t, until));
        } else if (until > s.until) {
            s.until = until;
        }
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, (int) (until - now), 6, true), true);
        if (t instanceof Creature) {
            ((Creature) t).setTarget(null);
        }
    }

    /** Stunned mobs: frozen in place, falling slowly, slammed down after a meteor hit. */
    private void tickStuns() {
        for (Iterator<Stun> it = stuns.values()
            .iterator(); it.hasNext();) {
            Stun s = it.next();
            if (now > s.until || s.entity.isDead() || !s.entity.isValid()) {
                it.remove();
                continue;
            }
            Vector v = s.entity.getVelocity();
            double vy = v.getY();
            if (s.entity.getFallDistance() > 5) {
                vy -= 2.0;
            } else if (vy < 0) {
                vy *= 0.25;
            }
            s.entity.setVelocity(new Vector(0, vy, 0));
        }
    }

    // ---- lock-on ----------------------------------------------------------------------------------------------------

    /** SlashBlade's lock-on: chosen when Sneak goes down (a 40-block ray, else the nearest target), cleared on release. */
    private LivingEntity pickLock(Player p) {
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        double blocks = rayBlocks(eye, look, 40);
        LivingEntity best = null;
        double bestT = Double.MAX_VALUE;
        for (Entity e : p.getNearbyEntities(40, 40, 40)) {
            if (!(e instanceof LivingEntity) || !attackable(p, (LivingEntity) e)) {
                continue;
            }
            LivingEntity t = (LivingEntity) e;
            Vector c = t.getLocation()
                .toVector()
                .add(new Vector(0, height(t) / 2, 0));
            Vector rel = c.clone()
                .subtract(eye.toVector());
            double along = rel.dot(look);
            if (along < 0 || along > blocks) {
                continue;
            }
            double off = rel.clone()
                .subtract(look.clone()
                    .multiply(along))
                .length();
            if (off <= Math.max(0.8, height(t) / 2) + 0.5 && along < bestT) {
                best = t;
                bestT = along;
            }
        }
        if (best != null) {
            return best;
        }
        double bestD = Double.MAX_VALUE;
        Location feet = p.getLocation();
        for (Entity e : p.getNearbyEntities(12.3, 6.9, 12.3)) {
            if (e instanceof LivingEntity && attackable(p, (LivingEntity) e)) {
                double d = e.getLocation()
                    .distanceSquared(feet);
                if (d < bestD) {
                    bestD = d;
                    best = (LivingEntity) e;
                }
            }
        }
        return best;
    }

    private static double rayBlocks(Location from, Vector dir, double max) {
        World w = from.getWorld();
        for (double d = 0; d < max; d += 0.25) {
            Block b = w.getBlockAt(
                (int) Math.floor(from.getX() + dir.getX() * d),
                (int) Math.floor(from.getY() + dir.getY() * d),
                (int) Math.floor(from.getZ() + dir.getZ() * d));
            if (b.getType()
                .isSolid()) {
                return d;
            }
        }
        return max;
    }

    private void lock(Fighter f, LivingEntity target) {
        f.lockTarget = target;
        KunftigEffects.lockOn(f.player, NS, target, f.blade == null ? 0x3333FF : f.blade.color);
    }

    // ---- slash arts -------------------------------------------------------------------------------------------------

    /** A slash art's effect running on the server (Judgement Cut, drive, void slash). */
    private interface Ticking {

        /** One tick; false when done. */
        boolean step();

        default void end() {}
    }

    void art(Fighter f, Actor.Art art, float[] args) {
        switch (art) {
            case JUDGEMENT_CUT:
                judgementCut(f, judgementPoint(f), false);
                break;
            case JUDGEMENT_CUT_JUST:
                judgementCut(f, judgementPoint(f), true);
                break;
            case JUDGEMENT_CUT_SUPER: {
                for (Entity e : f.player.getNearbyEntities(36.5, 36.5, 36.5)) {
                    if (e instanceof LivingEntity && attackable(f.player, (LivingEntity) e)
                        && e.getLocation()
                            .distance(f.player.getLocation()) <= reach(f) + 32) {
                        ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 40, 10), true);
                        judgementCut(f, e.getLocation(), false);
                    }
                }
                sound(f.player.getLocation(), Sfx.JUDGEMENT, 1.0f, 1.0f);
                break;
            }
            case DRIVE:
                drive(f, args[0], (int) args[1], args[2], args[3]);
                break;
            case WAVE_EDGE: {
                int count = (int) args[5];
                for (int i = 0; i <= count; i++) {
                    drive(f, args[0], (int) args[1], args[2], args[3] + f.random.nextFloat() * (args[4] - args[3]));
                }
                break;
            }
            case CIRCLE_SLASH:
                slash(f, 0, -22.5f + args[0], Offset.ZERO, true, true, 0.325f, Knockback.CANCEL);
                break;
            case VOID_SLASH:
                voidSlash(f);
                break;
            default:
                break;
        }
    }

    /** Where a Judgement Cut lands: the lock target's eyes, else what the player looks at (7 / 5 blocks), else 5 ahead. */
    private Location judgementPoint(Fighter f) {
        if (f.lockTarget != null && !f.lockTarget.isDead()) {
            return f.lockTarget.getEyeLocation();
        }
        Location eye = f.player.getEyeLocation();
        Vector look = eye.getDirection();
        LivingEntity best = null;
        double bestT = 7;
        for (Entity e : f.player.getNearbyEntities(8, 8, 8)) {
            if (!(e instanceof LivingEntity) || e == f.player) {
                continue;
            }
            LivingEntity t = (LivingEntity) e;
            Vector c = t.getLocation()
                .toVector()
                .add(new Vector(0, height(t) / 2, 0));
            Vector rel = c.clone()
                .subtract(eye.toVector());
            double along = rel.dot(look);
            double off = rel.clone()
                .subtract(look.clone()
                    .multiply(along))
                .length();
            if (along > 0 && along < bestT && off < 1.0) {
                best = t;
                bestT = along;
            }
        }
        if (best != null) {
            return best.getLocation()
                .add(0, best.getEyeHeight() / 2, 0);
        }
        double b = rayBlocks(eye, look, 5);
        return eye.clone()
            .add(look.multiply(b));
    }

    /**
     * SlashBlade's {@code EntityJudgementCut}: 10 ticks; on even ticks an area hit around it (its box grown by 4) at
     * {@code (1 + 0.1 Power) x attack + rank bonus} x 0.16; the critical one tosses and adds three slashes 120 degrees
     * apart on its first ticks.
     */
    private void judgementCut(Fighter f, Location at, boolean critical) {
        KunftigEffects.judgementCut(NS, at, f.blade.color);
        sound(at, Sfx.JUDGEMENT, 0.5f, 0.8f / (random.nextFloat() * 0.4f + 0.8f));
        int seed = random.nextInt(360);
        arts.add(new Ticking() {

            int age;

            @Override
            public boolean step() {
                age++;
                if (age < 8 && age % 2 == 0) {
                    sound(at, Sfx.JUDGEMENT_PULSE, 0.2f, 0.5f + 0.25f * random.nextFloat());
                }
                if (!f.player.isOnline()) {
                    return age <= 10;
                }
                if (age % 2 == 0 && age <= 10) {
                    for (LivingEntity t : around(f, at, 1.25 + 4)) {
                        strike(f, t, attack(f, t) * 0.16, critical ? Knockback.TOSS : Knockback.CANCEL, true, false,
                            false);
                    }
                }
                if (critical && age >= 1 && age <= 3) {
                    KunftigEffects.slash(NS, at, 120f * age + seed, 0, 30, 0, f.blade.color, f.rank.level(now), 0.5f, 10);
                    for (LivingEntity t : around(f, at, 4)) {
                        strike(f, t, attack(f, t) * 0.1 * 1.1, Knockback.CANCEL, true, false, false);
                    }
                }
                return age <= 10;
            }
        });
    }

    /** Attackable targets within a box of half-size {@code half} around {@code at}. */
    private List<LivingEntity> around(Fighter f, Location at, double half) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : at.getWorld()
            .getEntities()) {
            if (!(e instanceof LivingEntity) || e.isDead()) {
                continue;
            }
            Location l = e.getLocation();
            if (Math.abs(l.getX() - at.getX()) <= half && Math.abs(l.getZ() - at.getZ()) <= half
                && l.getY() <= at.getY() + half && l.getY() + height((LivingEntity) e) >= at.getY() - half
                && attackable(f.player, (LivingEntity) e)) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    /**
     * SlashBlade's {@code EntityDrive}: a crescent flying with the look x speed, slowing 1% a tick, ending at blocks
     * and after its lifetime; each tick it hits the first target on its path at {@code damage x attack + rank bonus}.
     */
    private void drive(Fighter f, float roll, int lifetime, float damage, float speed) {
        Location feet = f.player.getLocation();
        Vector look = feet.getDirection();
        Location at = feet.clone()
            .add(look.getX() * 0.3, 1.62 * 0.75 + look.getY() * 0.3, look.getZ() * 0.3);
        Vector vel = look.clone()
            .multiply(speed);
        KunftigEffects.Projectile handle = KunftigEffects.drive(NS, at, vel, roll, f.blade.color, lifetime);
        arts.add(new Ticking() {

            final Location pos = at.clone();
            final Vector v = vel.clone();
            int age;
            boolean ended;

            @Override
            public boolean step() {
                age++;
                if (age > lifetime || !f.player.isOnline()) {
                    return false;
                }
                double len = v.length();
                double blocked = rayBlocks(pos, v.clone()
                    .normalize(), len);
                if (blocked < len) {
                    return false;
                }
                LivingEntity hit = null;
                double bestT = Double.MAX_VALUE;
                for (Entity e : pos.getWorld()
                    .getEntities()) {
                    if (!(e instanceof LivingEntity) || !attackable(f.player, (LivingEntity) e)
                        || e.getLocation()
                            .distanceSquared(pos) > (len + 4) * (len + 4)) {
                        continue;
                    }
                    LivingEntity t = (LivingEntity) e;
                    Vector c = t.getLocation()
                        .toVector()
                        .add(new Vector(0, height(t) / 2, 0));
                    Vector rel = c.subtract(pos.toVector());
                    double along = rel.dot(v) / (len * len);
                    if (along < 0 || along > 1) {
                        continue;
                    }
                    double off = rel.clone()
                        .subtract(v.clone()
                            .multiply(along))
                        .length();
                    if (off <= Math.max(0.6, height(t) / 2) + 0.3 && along < bestT) {
                        hit = t;
                        bestT = along;
                    }
                }
                if (hit != null && strike(f, hit, damage * f.blade.attackDamage(f.stats, progression.leveling()) + f.rank.damageBonus(now),
                    Knockback.CANCEL, true, false, false)) {
                    sound(hit.getLocation(), Sfx.DRIVE_HIT, 1.0f, 1.2f / (random.nextFloat() * 0.2f + 0.9f));
                }
                pos.add(v);
                v.multiply(0.99);
                return true;
            }

            @Override
            public void end() {
                if (!ended) {
                    ended = true;
                    handle.remove(false);
                }
            }
        });
    }

    /**
     * SlashBlade's void slash: the arc marks what it touches for 36 ticks (every even tick, no damage); then each mark
     * takes {@code (attack x (1 + 0.1 Power) + rank bonus) x 5.1} and a push forward, with the glass sound.
     */
    private void voidSlash(Fighter f) {
        slash(f, 180, -22.5f, Offset.ZERO, false, false, 0, Knockback.CANCEL);
        java.util.Set<LivingEntity> marked = new java.util.LinkedHashSet<>();
        float yaw = f.player.getLocation()
            .getYaw();
        arts.add(new Ticking() {

            int age;

            @Override
            public boolean step() {
                age++;
                if (!f.player.isOnline()) {
                    return false;
                }
                if (age <= 36 && age % 2 == 0) {
                    marked.addAll(targets(f.player, reach(f)));
                }
                if (age > 36) {
                    sound(f.player.getLocation(), Sfx.SHATTER, 1.0f, 0.625f + 0.1f * random.nextFloat());
                    double rad = Math.toRadians(yaw);
                    for (LivingEntity t : marked) {
                        if (t.isDead()) {
                            continue;
                        }
                        strike(f, t, attack(f, t) * 5.1, Knockback.CANCEL, true, true, false);
                        t.setVelocity(t.getVelocity()
                            .add(new Vector(-Math.sin(rad) * 0.5, 0.05, Math.cos(rad) * 0.5)));
                    }
                    return false;
                }
                return true;
            }
        });
    }

    // ---- sounds -----------------------------------------------------------------------------------------------------

    /** A combat sound for the players within 16 blocks: the pack's own, or the vanilla stand-in. */
    @SuppressWarnings("deprecation") // playSound(String): deprecated on 1.7.10 (custom sound names), still the way
    void sound(Location at, Sfx sfx, float volume, float pitch) {
        String name = packSounds ? sfx.name : sfx.fallback;
        for (Player p : at.getWorld()
            .getPlayers()) {
            if (p.getLocation()
                .distanceSquared(at) <= 16 * 16 * Math.max(1, volume)) {
                p.playSound(at, name, volume, pitch);
            }
        }
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Fighter f = fighter(e.getPlayer());
        if (f != null) {
            click(f, Input.R_CLICK);
        }
    }

    /** A left click on an entity with a blade never hits by itself: it is a combo click (SlashBlade cancels it). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (dealing) {
            return;
        }
        Entity damager = e.getDamager();
        if (e.getEntity() instanceof Player && damager instanceof LivingEntity) {
            revenge.put(damager.getUniqueId(), e.getEntity()
                .getUniqueId());
        } else if (e.getEntity() instanceof Player && damager instanceof Projectile
            && ((Projectile) damager).getShooter() instanceof LivingEntity) {
                revenge.put(((LivingEntity) ((Projectile) damager).getShooter()).getUniqueId(), e.getEntity()
                    .getUniqueId());
            }
        if (!(damager instanceof Player) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        Fighter f = fighter((Player) damager);
        if (f == null) {
            return;
        }
        e.setCancelled(true);
        click(f, Input.L_CLICK);
    }

    /** A hurt taken costs a whole rank level (SlashBlade's {@code RankPointHandler}) and ends the hit counter. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player) || e.getDamage() <= 0) {
            return;
        }
        Fighter f = fighters.get(e.getEntity()
            .getUniqueId());
        if (f == null) {
            return;
        }
        f.rank.add(Concentration.HURT, now);
        KunftigEffects.rank(f.player, NS, (int) f.rank.points(now));
        if (f.hits > 0) {
            f.hits = 0;
            KunftigEffects.hits(f.player, NS, 0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        Fighter f = fighter(e.getPlayer());
        if (f == null) {
            return;
        }
        lock(f, e.isSneaking() ? pickLock(e.getPlayer()) : null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Stun s = stuns.get(e.getEntity()
            .getUniqueId());
        if (s != null && now <= s.until) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Fighter f = fighters.remove(e.getPlayer()
            .getUniqueId());
        if (f != null) {
            if (f.dirty) {
                flush(f);
            }
            f.slashes.clear();
        }
    }

    /** A kill with a blade in hand (SlashBlade's {@code KillCounter}): kills, XP, proud soul, forge materials. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        Player killer = e.getEntity()
            .getKiller();
        if (killer == null || e.getEntity() == killer) {
            return;
        }
        Fighter f = fighters.get(killer.getUniqueId());
        if (f == null || f.stats == null) {
            return;
        }
        progression.kill(f, e.getEntity(), e.getDroppedExp(), e.getDrops());
    }

    /** Mining with a blade wears it (SlashBlade: one per block that is not instant). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Fighter f = fighters.get(e.getPlayer()
            .getUniqueId());
        if (f != null && f.stats != null && e.getBlock()
            .getType()
            .isSolid()) {
            progression.hit(f);
        }
    }

    /** Writes a player's pending blade changes to the item now (before the forge takes it, for one). */
    public void flush(Player p) {
        Fighter f = fighters.get(p.getUniqueId());
        if (f != null && f.dirty) {
            flush(f);
        }
    }

    /** For the /overkill command and the tests: the fighter's current state, or null. */
    public String state(Player p) {
        Fighter f = fighters.get(p.getUniqueId());
        return f == null ? null : f.machine.state().id;
    }
}
