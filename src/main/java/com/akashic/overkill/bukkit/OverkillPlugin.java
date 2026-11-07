package com.akashic.overkill.bukkit;

import java.io.File;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.akashic.overkill.bukkit.combat.Combat;

/**
 * Overkill: SlashBlade's combat (Resharped's combo graph, slash effects, slash arts, lock-on and rank) on
 * Thermos/Crucible 1.7.10, drawn by the Kunftig mod (the blade engine, the effects, the held keys). Everything is
 * decided here, on the server.
 */
public final class OverkillPlugin extends JavaPlugin {

    private BladeRegistry blades;
    private BladeItems items;
    private Combat combat;

    @Override
    public void onEnable() {
        if (!kunftigPresent()) {
            getLogger().severe("Overkill precisa do mod Kunftig (API 9: lâminas, efeitos e teclas) no servidor.");
            getServer().getPluginManager()
                .disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        if (!new File(getDataFolder(), "blades.yml").isFile()) {
            saveResource("blades.yml", false);
        }
        start();
        OverkillCommand command = new OverkillCommand(this);
        getCommand("overkill").setExecutor(command);
        getCommand("overkill").setTabCompleter(command);
        getLogger().info(
            blades.all()
                .size() + " lâmina(s): "
                + blades.ids());
    }

    private static boolean kunftigPresent() {
        try {
            return com.akashic.kunftig.api.bukkit.blade.KunftigBlades.isAvailable()
                && com.akashic.kunftig.api.bukkit.blade.KunftigEffects.isAvailable()
                && com.akashic.kunftig.api.bukkit.input.KunftigInput.isAvailable();
        } catch (LinkageError e) {
            return false;
        }
    }

    private void start() {
        reloadConfig();
        blades = BladeRegistry.read(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "blades.yml")), getLogger());
        items = new BladeItems(this, blades);
        combat = new Combat(
            this,
            items,
            getConfig().getBoolean("pvp", false),
            getConfig().getBoolean("sons_proprios", false));
        combat.start();
    }

    @Override
    public void onDisable() {
        if (combat != null) {
            combat.shutdown();
        }
    }

    /** /overkill recarregar: blades and settings again (the blades in players' hands keep their stats). */
    void reload() {
        if (combat != null) {
            combat.shutdown();
        }
        start();
    }

    BladeRegistry blades() {
        return blades;
    }

    BladeItems items() {
        return items;
    }

    Combat combat() {
        return combat;
    }
}
