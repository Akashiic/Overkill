package com.akashic.overkill.bukkit;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps blades and the forge's materials out of every crafting recipe: two swords in a crafting grid would "repair"
 * into a plain sword, and a tamahagane is an iron ingot underneath. Katanas have no recipe of their own.
 */
final class Recipes implements Listener {

    private final BladeItems blades;
    private final ForgeItems materials;

    Recipes(BladeItems blades, ForgeItems materials) {
        this.blades = blades;
        this.materials = materials;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory()
            .getMatrix()) {
            if (it == null || it.getType() == Material.AIR) {
                continue;
            }
            if (materials.isOurs(it) || blades.read(it) != null) {
                e.getInventory()
                    .setResult(null);
                return;
            }
        }
    }
}
