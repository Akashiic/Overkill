package com.akashic.overkill.bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.akashic.overkill.core.Blade;

/** {@code /overkill}: give blades, refine, repair, inspect, reload. */
final class OverkillCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("dar", "refinar", "reparar", "info", "recarregar");
    private final OverkillPlugin plugin;

    OverkillCommand(OverkillPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "dar":
                return give(sender, args);
            case "refinar":
            case "reparar":
            case "info": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Só jogadores.");
                    return true;
                }
                Player p = (Player) sender;
                Combat c = plugin.combat();
                sender.sendMessage(
                    sub.equals("refinar") ? c.refine(p) : sub.equals("reparar") ? c.repair(p) : c.info(p));
                return true;
            }
            case "recarregar":
                if (!sender.hasPermission("overkill.admin")) {
                    sender.sendMessage(ChatColor.RED + "Sem permissão.");
                    return true;
                }
                plugin.reload();
                sender.sendMessage(
                    ChatColor.GREEN + "Overkill: " + plugin.blades()
                        .all()
                        .size() + " lâmina(s) carregada(s).");
                return true;
            default:
                sender.sendMessage(
                    ChatColor.GOLD + "/" + label
                        + " dar <lâmina> [jogador]"
                        + ChatColor.GRAY
                        + " | refinar | reparar | info | recarregar");
                sender.sendMessage(
                    ChatColor.GRAY + "Lâminas: " + String.join(
                        ", ",
                        plugin.blades()
                            .ids()));
                return true;
        }
    }

    @SuppressWarnings("deprecation") // getPlayerExact: deprecated on 1.7.10 for UUIDs, still the way to find by name
    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("overkill.admin")) {
            sender.sendMessage(ChatColor.RED + "Sem permissão.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "/overkill dar <lâmina> [jogador]");
            return true;
        }
        Blade b = plugin.blades()
            .get(args[1]);
        if (b == null) {
            sender.sendMessage(ChatColor.RED + "Lâmina desconhecida: " + args[1]);
            return true;
        }
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
            : sender instanceof Player ? (Player) sender : null;
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jogador não encontrado.");
            return true;
        }
        ItemStack item = plugin.combat()
            .create(b);
        if (!target.getInventory()
            .addItem(item)
            .isEmpty()) {
            target.getWorld()
                .dropItem(target.getLocation(), item);
        }
        sender.sendMessage(ChatColor.GREEN + "Entregue: " + ChatColor.translateAlternateColorCodes('&', b.name));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("dar")) {
            for (String id : plugin.blades()
                .ids()) {
                if (id.startsWith(args[1])) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
