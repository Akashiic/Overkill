package com.akashic.overkill.bukkit;

import java.io.File;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.akashic.overkill.bukkit.combat.Combat;
import com.akashic.overkill.core.Drops;
import com.akashic.overkill.core.Forge;
import com.akashic.overkill.core.Leveling;
import com.akashic.overkill.core.Star;

/**
 * Overkill: SlashBlade's combat (Resharped's combo graph, slash effects, slash arts, lock-on and rank) on
 * Thermos/Crucible 1.7.10, drawn by the Kunftig mod (the blade engine, the effects, the held keys). Everything is
 * decided here, on the server.
 */
public final class OverkillPlugin extends JavaPlugin {

    private BladeRegistry blades;
    private BladeItems items;
    private ForgeItems forgeItems;
    private ForgeGui forge;
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
        getCommand("forja").setExecutor(command);
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
        items.leveling(leveling());
        forgeItems = new ForgeItems(this);
        forgeItems.acceptFishing(getConfig().getBoolean("progressao.aceitar_itens_do_akashicfishing", true));
        combat = new Combat(
            this,
            items,
            forgeItems,
            drops(),
            Star.parse(getConfig().getString("progressao.anunciar_estrela_a_partir_de", "ouro")),
            getConfig().getBoolean("pvp", false),
            getConfig().getBoolean("sons_proprios", false));
        combat.setDebug(getConfig().getBoolean("depuracao", false));
        combat.start();
        forge = new ForgeGui(this, items, forgeItems, Forge.DEFAULT);
        forge.announceFrom(getConfig().getInt("progressao.anunciar_fortificacao_a_partir_de", 10));
        getServer().getPluginManager()
            .registerEvents(forge, this);
        getServer().getPluginManager()
            .registerEvents(new Recipes(items, forgeItems), this);
    }

    private Leveling leveling() {
        org.bukkit.configuration.ConfigurationSection c = getConfig().getConfigurationSection("progressao.curva");
        if (c == null) {
            return Leveling.DEFAULT;
        }
        try {
            return new Leveling(c.getDouble("base", 20), c.getDouble("fator", 1.37), c.getDouble("expoente", 1.5));
        } catch (IllegalArgumentException e) {
            getLogger().warning("config.yml progressao.curva: " + e.getMessage() + "; usando a padrão");
            return Leveling.DEFAULT;
        }
    }

    private Drops drops() {
        org.bukkit.configuration.ConfigurationSection c = getConfig().getConfigurationSection("progressao.drops");
        if (c == null) {
            return Drops.DEFAULT;
        }
        try {
            return new Drops(c.getDouble("pedra", 0.0025), c.getDouble("ofuda", 0.004),
                c.getDouble("tamahagane_lendario", 0.005), c.getDouble("tamahagane_mitico", 0.03));
        } catch (IllegalArgumentException e) {
            getLogger().warning("config.yml progressao.drops: " + e.getMessage() + "; usando os padrões");
            return Drops.DEFAULT;
        }
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void stop() {
        if (forge != null) {
            forge.closeAll();
            org.bukkit.event.HandlerList.unregisterAll(forge);
        }
        if (combat != null) {
            combat.shutdown();
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    /** /overkill recarregar: blades and settings again (the blades in players' hands keep their stats). */
    void reload() {
        stop();
        start();
    }

    ForgeItems forgeItems() {
        return forgeItems;
    }

    /** Opens the forge, the blade in hand written first (a fight's changes may still be waiting). */
    void openForge(org.bukkit.entity.Player p) {
        combat.flush(p);
        forge.open(p);
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
