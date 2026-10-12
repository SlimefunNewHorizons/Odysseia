package org.metamechanists.odysseia.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.metamechanists.odysseia.welcome.WelcomeMenu;
import org.metamechanists.odysseia.welcome.WelcomeMenuService;

/** /bienvenida [es|en]: vuelve a abrir el menu de bienvenida. */
public final class WelcomeCommand implements CommandExecutor {
    private final WelcomeMenuService service;

    public WelcomeCommand(WelcomeMenuService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo un jugador puede abrir el menu de bienvenida.");
            return true;
        }
        if (args.length > 0) {
            String forced = WelcomeMenu.dialogForArgument(args[0]);
            if (forced == null) {
                player.sendMessage("Uso: /" + label + " [es|en]");
                return true;
            }
            service.show(player, forced);
            return true;
        }
        service.show(player);
        return true;
    }
}
