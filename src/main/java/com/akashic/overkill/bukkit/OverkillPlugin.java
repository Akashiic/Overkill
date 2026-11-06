package com.akashic.overkill.bukkit;

import java.io.File;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Overkill: SlashBlade-style blades and DMC-style combat for Thermos/Crucible 1.7.10, on top of the Kunftig mod (held
 * 3D models with predicted attacks, displays, keys, HUD, lock-on). Everything is decided here, on the server.
 */
public final class OverkillPlugin extends JavaPlugin {

    private Kunftig kunftig;
    private BladeRegistry blades;
    private BladeItems items;
    private Combat combat;
    private boolean pvp;

    @Override
    public void onEnable() {
        kunftig = Kunftig.detect(this, getLogger());
        if (kunftig == null) {
            getServer().getPluginManager()
                .disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        if (!new File(getDataFolder(), "blades.yml").isFile()) {
            saveResource("blades.yml", false);
        }
        readConfig();
        items = new BladeItems(this, blades);
        combat = new Combat(this, items, kunftig);
        getServer().getPluginManager()
            .registerEvents(new CombatListener(combat, items), this);
        getServer().getPluginManager()
            .registerEvents(new InputListener(combat), this);
        OverkillCommand command = new OverkillCommand(this);
        getCommand("overkill").setExecutor(command);
        getCommand("overkill").setTabCompleter(command);
        getServer().getScheduler()
            .runTaskTimer(this, combat::tick, 1, 1);
        getLogger().info(
            blades.all()
                .size() + " lâmina(s): "
                + blades.ids());
    }

    @Override
    public void onDisable() {
        if (combat != null) {
            combat.shutdown();
        }
    }

    private void readConfig() {
        reloadConfig();
        pvp = getConfig().getBoolean("pvp", false);
        blades = BladeRegistry.read(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "blades.yml")), getLogger());
    }

    /** /overkill recarregar: blades and settings again (the blades in players' hands keep their stats). */
    void reload() {
        readConfig();
        combat.shutdown();
        items = new BladeItems(this, blades);
        combat = new Combat(this, items, kunftig);
        getServer().getScheduler()
            .cancelTasks(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        getServer().getPluginManager()
            .registerEvents(new CombatListener(combat, items), this);
        getServer().getPluginManager()
            .registerEvents(new InputListener(combat), this);
        getServer().getScheduler()
            .runTaskTimer(this, combat::tick, 1, 1);
    }

    boolean pvp() {
        return pvp;
    }

    BladeRegistry blades() {
        return blades;
    }

    Combat combat() {
        return combat;
    }
}
