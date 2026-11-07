package com.akashic.overkill.bukkit.combat;

import java.util.List;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.inventory.ItemStack;

import com.akashic.overkill.bukkit.ForgeItems;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.Drops;
import com.akashic.overkill.core.Leveling;
import com.akashic.overkill.core.Star;

/**
 * What a katana lives through in a fight: the wear of each hit (SlashBlade: one per hit), the proud soul a slash art
 * costs (or a wear without it), and each kill: one more kill, the level XP, SlashBlade's proud soul, and maybe a
 * whetstone, a talisman or a tamahagane. Also the star, rolled the first time a katana is held. Changes go to the
 * fighter's stats; {@link Combat} writes them to the item.
 */
final class Progression {

    /** Settings from config.yml. */
    static final class Settings {

        final Leveling leveling;
        final Drops drops;
        /** From which star a new katana is announced to the server (null: never). */
        final Star announceStar;

        Settings(Leveling leveling, Drops drops, Star announceStar) {
            this.leveling = leveling;
            this.drops = drops;
            this.announceStar = announceStar;
        }
    }

    private final Combat combat;
    private final ForgeItems forgeItems;
    private final Settings settings;
    private final Random random = new Random();

    Progression(Combat combat, ForgeItems forgeItems, Settings settings) {
        this.combat = combat;
        this.forgeItems = forgeItems;
        this.settings = settings;
    }

    Leveling leveling() {
        return settings.leveling;
    }

    /** A hit landed: one wear. */
    void hit(Fighter f) {
        if (f.stats.wear(f.blade, 1)) {
            broke(f);
        }
        f.dirty = true;
    }

    /** A slash art came out: its proud soul, or one wear without enough. */
    void art(Fighter f) {
        if (!f.stats.spend(BladeStats.ART_COST) && f.stats.wear(f.blade, 1)) {
            broke(f);
        }
        f.dirty = true;
    }

    private void broke(Fighter f) {
        combat.sound(f.player.getLocation(), Sfx.BREAK, 1.0f, 0.8f);
        f.player.sendMessage(
            ChatColor.RED + "Sua lâmina quebrou." + ChatColor.GRAY + " Ela ainda corta, fraca e sem Slash Art, até ser"
                + " reparada na /forja.");
    }

    /** A kill with the blade in hand: kills, XP, proud soul, and the forge's materials into the mob's drops. */
    void kill(Fighter f, LivingEntity victim, int droppedXp, List<ItemStack> drops) {
        BladeStats s = f.stats;
        float rankLevel = f.rank.level(combat.now());
        int before = s.level(settings.leveling);
        s.kills++;
        s.xp += Leveling.xpForKill(victim.getMaxHealth(), rankLevel);
        s.proudSoul += Leveling.proudSoulForKill(droppedXp, rankLevel);
        f.dirty = true;
        int after = s.level(settings.leveling);
        if (after > before) {
            f.levelUp = true;
            combat.sound(f.player.getLocation(), Sfx.LEVEL_UP, 0.8f, 1.2f);
            f.player.sendMessage(
                ChatColor.GOLD + "Sua lâmina subiu para o nível " + ChatColor.WHITE + after + ChatColor.GOLD + "!");
        }
        if (s.kills == Leveling.FIERCER_EDGE_KILLS) {
            f.player.sendMessage(
                ChatColor.DARK_PURPLE + "Mil abates: o fio da lâmina ficou feroz. Cada nível agora soma o dobro de dano.");
        }
        boolean boss = victim instanceof Wither || victim instanceof EnderDragon;
        Drops.Roll roll = settings.drops.roll(victim.getMaxHealth(), boss, random);
        if (roll.stone != null) {
            drops.add(forgeItems.stone(roll.stone, 1));
            found(f, ForgeItems.stoneName(roll.stone));
        }
        if (roll.seal != null) {
            drops.add(forgeItems.seal(roll.seal));
            found(f, ForgeItems.sealName(roll.seal));
        }
        if (roll.tamahagane) {
            drops.add(forgeItems.tamahagane(1));
            found(f, ForgeItems.TAMAHAGANE_NAME);
            Chat.broadcast(
                ChatColor.LIGHT_PURPLE + f.player.getName() + ChatColor.GRAY + " arrancou um " + ForgeItems.TAMAHAGANE_NAME
                    + ChatColor.GRAY + " de um inimigo lendário!");
        }
    }

    private static void found(Fighter f, String what) {
        f.player.sendMessage(ChatColor.GRAY + "Caiu: " + what + ChatColor.RESET);
    }

    /** The first time a katana is held: its star. */
    void rollStar(Fighter f, String bladeName) {
        Star star = Star.roll(random);
        f.stats.star = star;
        if (f.stats.maker == null) {
            f.stats.maker = f.player.getName();
        }
        f.dirty = true;
        f.levelUp = true;
        String name = ChatColor.translateAlternateColorCodes('&', bladeName);
        String line = name + ChatColor.GRAY + ": " + "§" + star.colorCode() + (star == Star.NORMAL ? "" : "★ ")
            + "Estrela " + star.displayName() + ChatColor.GRAY + " - Obtida!";
        f.player.sendMessage(line);
        combat.sound(f.player.getLocation(), star == Star.NORMAL ? Sfx.SHEATH : Sfx.LEVEL_UP, 1.0f, 1.0f);
        if (settings.announceStar != null && star.ordinal() >= settings.announceStar.ordinal()) {
            Chat.broadcast(ChatColor.WHITE + f.player.getName() + ChatColor.GRAY + " obteve " + line);
        }
    }

    /** Messages to every player online. */
    static final class Chat {

        private Chat() {}

        static void broadcast(String message) {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Player p : w.getPlayers()) {
                    p.sendMessage(message);
                }
            }
        }
    }
}
