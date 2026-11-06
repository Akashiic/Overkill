package com.akashic.overkill.bukkit;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;

/** Bukkit events into {@link Combat}. */
final class CombatListener implements Listener {

    private final Combat combat;
    private final BladeItems items;

    CombatListener(Combat combat, BladeItems items) {
        this.combat = combat;
        this.items = items;
    }

    /** Every left click (in the air, on a block, on an entity) is an arm swing: the client predicts from it too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            combat.swing(e.getPlayer());
        }
    }

    /**
     * A vanilla hit with a blade does nothing: the blade cuts on its move's hit ticks, the same for every target in the
     * swing. The plugin's own damage passes ({@link Combat#dealing}).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVanillaHit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (!combat.dealing() && damager instanceof Player
            && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            && items.read(((Player) damager).getItemInHand()) != null) {
            e.setCancelled(true);
        }
    }

    /** A fighter inside his dodge's invulnerable ticks takes nothing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && combat.dodged((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    /** Getting hit for real drops the style rank. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && e.getDamage() > 0) {
            combat.hurt((Player) e.getEntity(), e.getDamage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && killer != victim) {
            combat.kill(killer, victim);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Fighter f = combat.fighter(e.getPlayer());
        if (f != null) {
            f.lock = null;
        }
    }
}
