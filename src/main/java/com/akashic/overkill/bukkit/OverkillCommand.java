package com.akashic.overkill.bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.akashic.overkill.core.Blade;
import com.akashic.overkill.core.BladeStats;
import com.akashic.overkill.core.Forge;
import com.akashic.overkill.core.Leveling;
import com.akashic.overkill.core.SealTier;
import com.akashic.overkill.core.Star;
import com.akashic.overkill.core.StoneTier;

/** {@code /overkill} (give blades and forge materials, the forge, inspect, reload) and {@code /forja}. */
final class OverkillCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("dar", "pedra", "ofuda", "forja", "info", "recarregar");
    private final OverkillPlugin plugin;

    OverkillCommand(OverkillPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName()
            .equalsIgnoreCase("forja")) {
            return forge(sender);
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "dar":
                return give(sender, args);
            case "pedra":
                return stone(sender, args);
            case "ofuda":
                return seal(sender, args);
            case "forja":
                return forge(sender);
            case "info": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Só jogadores.");
                    return true;
                }
                String state = plugin.combat()
                    .state((Player) sender);
                sender.sendMessage(
                    state == null ? ChatColor.GRAY + "Sem lâmina na mão."
                        : ChatColor.GOLD + "Estado do combo: " + ChatColor.WHITE + state);
                return true;
            }
            case "recarregar":
                if (!admin(sender)) {
                    return true;
                }
                plugin.reload();
                sender.sendMessage(
                    ChatColor.GREEN + "Overkill: " + plugin.blades()
                        .all()
                        .size() + " lâmina(s) carregada(s).");
                return true;
            default:
                sender.sendMessage(ChatColor.GOLD + "/" + label + " dar <lâmina> [jogador] [estrela] [nível] [+N|asc]");
                sender.sendMessage(ChatColor.GOLD + "/" + label + " pedra <tier|tamahagane> [quantidade] [jogador]");
                sender.sendMessage(ChatColor.GOLD + "/" + label + " ofuda <25|50|75|100> [quantidade] [jogador]");
                sender.sendMessage(ChatColor.GOLD + "/forja" + ChatColor.GRAY + " | /" + label + " info | recarregar");
                sender.sendMessage(
                    ChatColor.GRAY + "Lâminas: " + String.join(
                        ", ",
                        plugin.blades()
                            .ids()));
                return true;
        }
    }

    private static boolean admin(CommandSender sender) {
        if (!sender.hasPermission("overkill.admin")) {
            sender.sendMessage(ChatColor.RED + "Sem permissão.");
            return false;
        }
        return true;
    }

    private boolean forge(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Só jogadores.");
            return true;
        }
        if (!sender.hasPermission("overkill.forja")) {
            sender.sendMessage(ChatColor.RED + "Sem permissão.");
            return true;
        }
        plugin.openForge((Player) sender);
        return true;
    }

    @SuppressWarnings("deprecation") // getPlayerExact: deprecated on 1.7.10 for UUIDs, still the way to find by name
    private static Player target(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            return Bukkit.getPlayerExact(args[index]);
        }
        return sender instanceof Player ? (Player) sender : null;
    }

    private static void hand(Player target, ItemStack item) {
        for (ItemStack left : target.getInventory()
            .addItem(item)
            .values()) {
            target.getWorld()
                .dropItem(target.getLocation(), left);
        }
    }

    private static int amount(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(args[index])));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** {@code dar <lâmina> [jogador] [estrela] [nível] [+N|asc]}: without a star, it is rolled when first held. */
    private boolean give(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "/overkill dar <lâmina> [jogador] [estrela] [nível] [+N|asc]");
            return true;
        }
        Blade b = plugin.blades()
            .get(args[1]);
        if (b == null) {
            sender.sendMessage(ChatColor.RED + "Lâmina desconhecida: " + args[1]);
            return true;
        }
        Player target = target(sender, args, 2);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jogador não encontrado.");
            return true;
        }
        BladeStats s = new BladeStats();
        if (args.length > 3) {
            s.star = Star.parse(args[3]);
            if (s.star == null) {
                sender.sendMessage(ChatColor.RED + "Estrela: normal, prata, ouro ou iridio.");
                return true;
            }
        }
        if (args.length > 4) {
            try {
                int level = Math.max(1, Math.min(Leveling.MAX_LEVEL, Integer.parseInt(args[4])));
                s.xp = plugin.items()
                    .leveling()
                    .totalXpFor(level);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Nível de 1 a 99.");
                return true;
            }
        }
        if (args.length > 5) {
            String v = args[5].toLowerCase(Locale.ROOT);
            try {
                s.enhancement = v.startsWith("asc") ? Forge.ASCENSION
                    : Math.max(0, Math.min(Forge.MAX_PLUS, Integer.parseInt(v.replace("+", ""))));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Fortificação: +0 a +17, ou asc.");
                return true;
            }
        }
        hand(target, plugin.items()
            .create(b, s));
        sender.sendMessage(ChatColor.GREEN + "Entregue: " + ChatColor.translateAlternateColorCodes('&', b.name));
        return true;
    }

    private boolean stone(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "/overkill pedra <comum|incomum|rara|epica|lendaria|mitica|tamahagane>"
                + " [quantidade] [jogador]");
            return true;
        }
        Player target = target(sender, args, 3);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jogador não encontrado.");
            return true;
        }
        int n = amount(args, 2);
        if (args[1].equalsIgnoreCase("tamahagane")) {
            hand(target, plugin.forgeItems()
                .tamahagane(n));
        } else {
            StoneTier t = StoneTier.parse(args[1]);
            if (t == null) {
                sender.sendMessage(ChatColor.RED + "Tier desconhecido: " + args[1]);
                return true;
            }
            hand(target, plugin.forgeItems()
                .stone(t, n));
        }
        sender.sendMessage(ChatColor.GREEN + "Entregue.");
        return true;
    }

    private boolean seal(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return true;
        }
        SealTier t = args.length < 2 ? null : SealTier.parse(args[1]);
        if (t == null) {
            sender.sendMessage(ChatColor.RED + "/overkill ofuda <25|50|75|100> [quantidade] [jogador]");
            return true;
        }
        Player target = target(sender, args, 3);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jogador não encontrado.");
            return true;
        }
        for (int i = amount(args, 2); i > 0; i--) {
            hand(target, plugin.forgeItems()
                .seal(t));
        }
        sender.sendMessage(ChatColor.GREEN + "Entregue.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName()
            .equalsIgnoreCase("forja")) {
            return out;
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBS);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("dar")) {
            options.addAll(plugin.blades()
                .ids());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("dar")) {
            for (Star s : Star.values()) {
                options.add(s.key());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pedra")) {
            for (StoneTier t : StoneTier.values()) {
                options.add(t.key());
            }
            options.add("tamahagane");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ofuda")) {
            for (SealTier t : SealTier.values()) {
                options.add(t.key());
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.startsWith(last)) {
                out.add(o);
            }
        }
        return out;
    }
}
