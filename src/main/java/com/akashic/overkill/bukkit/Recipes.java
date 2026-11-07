package com.akashic.overkill.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

import com.akashic.overkill.core.Blade;

/**
 * The starter katana's recipe (two iron ingots on a diagonal over a stick: the blade is a new item, its star rolled
 * the first time it is held) and the guard that keeps blades and the forge's materials out of every recipe (two
 * swords in a crafting grid would "repair" into a plain sword; a tamahagane is an iron ingot underneath).
 */
final class Recipes implements Listener {

    private final BladeItems blades;
    private final ForgeItems materials;

    Recipes(BladeItems blades, ForgeItems materials) {
        this.blades = blades;
        this.materials = materials;
    }

    /** Adds the starter katana's recipe (the server keeps it until restart: added once). */
    static void addStarter(BladeItems blades, Blade starter) {
        ShapedRecipe r = new ShapedRecipe(blades.create(starter));
        r.shape("  I", " I ", "S  ");
        r.setIngredient('I', Material.IRON_INGOT);
        r.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(r);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory()
            .getResult();
        boolean starter = result != null && blades.read(result) != null;
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
        if (starter) {
            // a fresh copy each time: never two katanas sharing one item's data
            BladeItems.Held h = blades.read(result);
            e.getInventory()
                .setResult(blades.create(h.blade));
        }
    }
}
