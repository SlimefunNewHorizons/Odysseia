package org.metamechanists.odysseia.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.metamechanists.odysseia.cheques.ChequeService;
import org.metamechanists.odysseia.cheques.ChequeSigner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Comando administrativo usado por ExcellentCrates para emitir cheques físicos. */
public final class ChequeCommand implements CommandExecutor, TabCompleter {

    private final ChequeService service;

    public ChequeCommand(ChequeService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /cheque give <jugador> <5000|10000|25000|50000> [cantidad]");
            return true;
        }
        if (!sender.hasPermission("odysseia.cheque.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para emitir cheques.");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Ese jugador no está conectado.");
            return true;
        }
        try {
            long amount = Long.parseLong(args[2].replace(".", "").replace(",", ""));
            int count = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
            if (!ChequeSigner.ALLOWED_AMOUNTS.contains(amount) || count < 1 || count > 64) {
                throw new IllegalArgumentException();
            }
            for (int index = 0; index < count; index++) ChequeService.restore(target, service.issue(amount));
            sender.sendMessage(ChatColor.GREEN + "Emitidos " + count + " cheque(s) de "
                    + ChequeService.format(amount) + " Dragmas para " + target.getName() + ".");
            target.sendMessage(ChatColor.GOLD + "Recibiste " + count + " cheque(s) de "
                    + ChequeService.format(amount) + " Dragmas.");
            return true;
        } catch (IllegalArgumentException error) {
            sender.sendMessage(ChatColor.RED + "Monto/cantidad inválidos. Solo: 5000, 10000, 25000 o 50000; máximo 64.");
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("odysseia.cheque.admin")) return List.of();
        if (args.length == 1) return List.of("give");
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(player.getName());
            }
            return names;
        }
        if (args.length == 3) return List.of("5000", "10000", "25000", "50000");
        if (args.length == 4) return List.of("1");
        return List.of();
    }
}
