package com.akashic.overkill.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import com.akashic.kunftig.api.bukkit.input.KunftigInputEvent;

/** Kunftig keys into {@link Combat}. Registered only when Kunftig's input works on this server. */
final class InputListener implements Listener {

    private final Combat combat;

    InputListener(Combat combat) {
        this.combat = combat;
    }

    @EventHandler
    public void onInput(KunftigInputEvent e) {
        combat.input(e.getPlayer(), e.getAction(), e.isPressed(), e.getYaw(), e.getMovement());
    }
}
