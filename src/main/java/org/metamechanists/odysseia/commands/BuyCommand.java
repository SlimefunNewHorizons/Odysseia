package org.metamechanists.odysseia.commands;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manda al jugador a la tienda oficial en vez de abrir el GUI de Tebex.
 *
 * Existe como comando de plugin y no como alias de commands.yml por lo mismo que
 * {@link SurvivalCommand}: los alias de Bukkit tienen la prioridad mas baja y el plugin de Tebex
 * registra /buy. Hay que apagar tambien "buy-command.enabled" en su config.yml.
 *
 * El motivo de reemplazarlo: el GUI de Tebex era una segunda fuente de verdad que se desactualiza
 * sola. Anunciaba "Duracion: 30 dias" en todos los paquetes por igual, incluidos los Dragmas y las
 * protecciones, que son permanentes.
 */
public final class BuyCommand implements CommandExecutor {

    private static final String DEFAULT_URL = "https://web.drakescraft.cl/store.html";

    private final JavaPlugin plugin;

    public BuyCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String url = plugin.getConfig().getString("tienda.url-web", DEFAULT_URL);

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Official store: " + url);
            return true;
        }

        player.sendMessage(color("&8&m                                                    "));
        player.sendMessage(color("&6⚡ &lDrakesCraft &8· &fOfficial Store"));
        player.sendMessage("");
        player.sendMessage(color("&7Ranks, kits, protections and Dragmas."));
        player.sendMessage(color("&7Automatic delivery when you join."));
        player.sendMessage("");

        TextComponent link = new TextComponent(color("&a&l➜ Open the store"));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(color("&7" + url))));
        player.spigot().sendMessage(link);

        player.sendMessage("");
        player.sendMessage(color("&8Open the server main menu with &7/menu&8."));
        player.sendMessage(color("&8&m                                                    "));
        return true;
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
