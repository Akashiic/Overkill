package com.akashic.overkill.bukkit;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import com.akashic.kunftig.api.KunftigApi;
import com.akashic.kunftig.api.bukkit.camera.KunftigCamera;
import com.akashic.kunftig.api.bukkit.display.Display;
import com.akashic.kunftig.api.bukkit.display.KunftigDisplays;
import com.akashic.kunftig.api.bukkit.hud.HudElement;
import com.akashic.kunftig.api.bukkit.hud.KunftigHud;
import com.akashic.kunftig.api.bukkit.input.KunftigInput;
import com.akashic.kunftig.api.bukkit.rod.KunftigRods;

/**
 * Every optional Kunftig feature the plugin uses, behind one door: each call does nothing (or returns null) when the
 * feature is off on this server, so the combat runs the same with fewer visuals. Item data is not optional (the
 * plugin refuses to start without it). Main thread.
 */
public final class Kunftig {

    /** The Kunftig API level this plugin is written against. */
    public static final int API = 9;

    private final boolean rods;
    private final boolean displays;
    private final boolean hud;
    private final boolean camera;
    private final boolean input;
    final boolean particles;

    private Kunftig(boolean rods, boolean displays, boolean hud, boolean camera, boolean input, boolean particles) {
        this.rods = rods;
        this.displays = displays;
        this.hud = hud;
        this.camera = camera;
        this.input = input;
        this.particles = particles;
    }

    /**
     * Checks the installed Kunftig. Null when it is missing, too old, or has no item data (the plugin cannot run).
     */
    public static Kunftig detect(Plugin plugin, Logger log) {
        try {
            Class.forName("com.akashic.kunftig.api.KunftigApi");
        } catch (ClassNotFoundException e) {
            log.severe("O mod Kunftig não está em mods/: o Overkill precisa dele (API " + API + ").");
            return null;
        }
        if (KunftigApi.apiVersion() < API || !KunftigApi.isItemDataAvailable()) {
            log.severe(
                "Kunftig API " + KunftigApi.apiVersion()
                    + " sem os recursos necessários: o Overkill precisa da API "
                    + API
                    + " com dados em itens.");
            return null;
        }
        Kunftig k = new Kunftig(
            KunftigApi.isRodsAvailable(),
            KunftigApi.isDisplaysAvailable(),
            KunftigApi.isHudAvailable(),
            KunftigApi.isCameraAvailable(),
            KunftigApi.isInputAvailable(),
            KunftigApi.isParticlesAvailable());
        if (k.input) {
            KunftigInput.register(plugin);
        }
        log.info(
            "Kunftig API " + KunftigApi.apiVersion()
                + ": modelos na mão "
                + k.rods
                + ", exibições "
                + k.displays
                + ", HUD "
                + k.hud
                + ", câmera "
                + k.camera
                + ", teclas "
                + k.input
                + ", partículas "
                + k.particles);
        return k;
    }

    // ---- held model --------------------------------------------------------------------------------------------

    /** The animation state of the player's held blade, for everyone around. */
    public void state(Player p, String state) {
        if (rods) {
            KunftigRods.setState(p, state);
        }
    }

    // ---- displays ----------------------------------------------------------------------------------------------

    /** A 3D effect in the world, or null when displays are off. */
    public Display spawn(Location at, String model, String state, double range) {
        if (!displays) {
            return null;
        }
        return KunftigDisplays.spawn(at, model, "", state, Collections.<ItemStack>emptyList(), range);
    }

    public void fly(Display d, Vector perTick) {
        if (d != null && !d.isRemoved()) {
            d.setMotion(perTick);
        }
    }

    public void glide(Display d, Location to, int ticks) {
        if (d != null && !d.isRemoved()) {
            d.moveTo(to, ticks);
        }
    }

    public void turn(Display d, float yaw, float pitch, float roll) {
        if (d != null && !d.isRemoved()) {
            d.setRotation(yaw, pitch, roll);
        }
    }

    public void remove(Display d) {
        if (d != null) {
            d.remove();
        }
    }

    // ---- HUD and camera ----------------------------------------------------------------------------------------

    public void hud(Player p, HudElement e) {
        if (hud) {
            KunftigHud.show(p, e);
        }
    }

    public void hudRemove(Player p, String id) {
        if (hud) {
            KunftigHud.remove(p, id);
        }
    }

    public boolean lockOn(Player p, Entity target, float strength, float distance) {
        if (!camera) {
            return false;
        }
        KunftigCamera.lockOn(p, target, strength, distance, true);
        return KunftigCamera.followsLockOn(p);
    }

    public void release(Player p) {
        if (camera) {
            KunftigCamera.release(p);
        }
    }

    // ---- input -------------------------------------------------------------------------------------------------

    /** The Kunftig keys this player's client reports (none: an empty list). */
    public void bind(Player p, List<String> actions) {
        if (input) {
            KunftigInput.bind(p, actions);
        }
    }

    public boolean sendsInput(Player p) {
        return input && KunftigInput.sendsInput(p);
    }

    public static final List<String> COMBAT_KEYS = java.util.Arrays
        .asList(KunftigInput.SPECIAL, KunftigInput.SKILL, KunftigInput.DODGE, KunftigInput.LOCK_ON);
}
