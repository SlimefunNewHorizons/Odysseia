package org.metamechanists.odysseia.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.metamechanists.odysseia.Odysseia;

import java.util.List;

/** Comando administrativo para recargar YAML y servicios runtime sin reiniciar el servidor. */
public final class ReloadCommand implements CommandExecutor, TabCompleter {
    private final Odysseia plugin;

    public ReloadCommand(Odysseia plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(color("&6[Odysseia] &7Productos: &f" + plugin.getPurchaseEngineProductCount()
                    + " &8| &7Instance: &f" + plugin.getInstanceId()));
            return true;
        }
        if (args[0].equalsIgnoreCase("vipalerts")) {
            if (!sender.hasPermission("odysseia.reload")) {
                sender.sendMessage(color("&cNo tienes permiso."));
                return true;
            }
            sender.sendMessage(color("&e[Odysseia] &7Generando y enviando reporte de caducidad VIP a Discord..."));
            boolean ok = plugin.getVipExpiryAlertService() != null && plugin.getVipExpiryAlertService().sendVipExpiryReport();
            if (ok) {
                sender.sendMessage(color("&a[Odysseia] Reporte de caducidad VIP enviado exitosamente a Discord."));
            } else {
                sender.sendMessage(color("&c[Odysseia] No se pudo enviar el reporte. Verifica discord.webhook-url en config.yml."));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("sfmaster")) {
            return handleSfMaster(sender, args);
        }
        if (args[0].equalsIgnoreCase("maintenance")) {
            return handleMaintenance(sender, args);
        }
        if (args[0].equalsIgnoreCase("pack")) {
            return handlePack(sender, args);
        }
        if (args[0].equalsIgnoreCase("sii")) {
            return handleSii(sender, args);
        }
        if (!args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(color("&eUso: &f/odysseia <reload|status|vipalerts|sfmaster|maintenance|pack|sii>"));
            return true;
        }
        if (!sender.hasPermission("odysseia.reload")) {
            sender.sendMessage(color("&cNo tienes permiso para recargar Odysseia."));
            return true;
        }

        sender.sendMessage(color("&e[Odysseia] &7Recargando config.yml, purchases.yml y servicios..."));
        try {
            List<String> errors = plugin.reloadRuntime();
            if (errors.isEmpty()) {
                sender.sendMessage(color("&a[Odysseia] Recarga completa sin errores."));
                return true;
            }
            sender.sendMessage(color("&6[Odysseia] Recarga aplicada con advertencias:"));
            errors.stream().limit(8).forEach(error -> sender.sendMessage(color("&e- &f" + error)));
            if (errors.size() > 8) {
                sender.sendMessage(color("&e... y " + (errors.size() - 8) + " advertencias más en consola."));
            }
        } catch (Exception error) {
            sender.sendMessage(color("&c[Odysseia] Error al recargar: " + error.getMessage()));
            plugin.getLogger().severe("[Reload] Error al recargar: " + error.getMessage());
        }
        return true;
    }

    private boolean handlePack(CommandSender sender, String[] args) {
        if (!sender.hasPermission("odysseia.reload")) {
            sender.sendMessage(color("&cNo tienes permiso para reenviar el resource pack."));
            return true;
        }
        org.metamechanists.odysseia.listeners.ResourcePackListener pack = plugin.getResourcePack();
        if (pack == null || !pack.enabled()) {
            sender.sendMessage(color("&c[Odysseia] resource-pack.enabled es false o falta la url en config.yml."));
            return true;
        }
        java.util.List<org.bukkit.entity.Player> targets = new java.util.ArrayList<>();
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            targets.addAll(org.bukkit.Bukkit.getOnlinePlayers());
        } else if (args.length >= 2) {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (p == null) {
                sender.sendMessage(color("&c[Odysseia] Jugador no conectado: " + args[1]));
                return true;
            }
            targets.add(p);
        } else if (sender instanceof org.bukkit.entity.Player self) {
            targets.add(self);
        } else {
            sender.sendMessage(color("&eUso: &f/odysseia pack <jugador|all>"));
            return true;
        }
        int sent = 0;
        for (org.bukkit.entity.Player p : targets) {
            if (!org.metamechanists.odysseia.listeners.ResourcePackListener.isBedrock(p) && pack.send(p)) {
                sent++;
            }
        }
        sender.sendMessage(color("&a[Odysseia] Resource pack enviado a " + sent + " jugador(es) Java."));
        return true;
    }

    private boolean handleSfMaster(CommandSender sender, String[] args) {
        if (!sender.hasPermission("odysseia.sfmaster.audit")) {
            sender.sendMessage(color("&cNo tienes permiso para auditar SFMaster."));
            return true;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("audit")) {
            sender.sendMessage(color("&eUso: &f/odysseia sfmaster audit <jugador-online>"));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(color("&cEl jugador debe estar conectado para auditar inventario y Ender Chest."));
            return true;
        }
        var watcher = plugin.getSfMasterWatcher();
        if (watcher == null) {
            sender.sendMessage(color("&c[SFMaster] El servicio aún no está disponible; revisa el arranque de Odysseia."));
            plugin.getLogger().warning("[SFMaster Audit] Servicio no inicializado al auditar a " + target.getName());
            return true;
        }
        var result = watcher.audit(target);
        sender.sendMessage(color("&6[SFMaster] &f" + target.getName()
                + " &8| &7marcados: &f" + result.markedItems()
                + " &8| &7guías: &f" + result.cheatGuides()
                + " &8| &7candidatos legacy: &f" + result.suspiciousLegacy().size()));
        result.suspiciousLegacy().entrySet().stream().limit(20)
                .forEach(entry -> sender.sendMessage(color("&e- &f" + entry.getKey() + " &7x" + entry.getValue())));
        if (result.suspiciousLegacy().size() > 20) {
            sender.sendMessage(color("&7... y " + (result.suspiciousLegacy().size() - 20) + " IDs adicionales."));
        }
        plugin.getLogger().info("[SFMaster Audit] " + sender.getName() + " revisó a " + target.getName()
                + ": marked=" + result.markedItems() + ", guides=" + result.cheatGuides()
                + ", legacyCandidates=" + result.suspiciousLegacy());
        sender.sendMessage(color("&cLos candidatos no se borran automáticamente: pueden haber sido fabricados legítimamente."));
        return true;
    }

    private boolean handleSii(CommandSender sender, String[] args) {
        if (!sender.hasPermission("odysseia.sii.admin")) {
            sender.sendMessage(color("&cNo tienes permiso para administrar el SII."));
            return true;
        }
        org.metamechanists.odysseia.economy.EconomyWatchdog sii = plugin.getEconomyWatchdog();
        if (sii == null) {
            sender.sendMessage(color("&cEl vigilante de economia no esta activo."));
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("estado")) {
            sender.sendMessage(color("&6[SII] &7" + sii.resumen().replace("\n", "\n&7")));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(color("&eUso: &f/odysseia sii <estado|liberar <jugador>|confiscar <jugador>|congelar <jugador> <horas> [motivo]>"));
            return true;
        }
        org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(args[2]);
        try {
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "liberar" -> {
                double r = sii.liberar(target);
                sender.sendMessage(color(r < 0 ? "&c" + args[2] + " no esta fiscalizado." : "&aLiberados ₯" + String.format("%,.0f", r) + " a " + args[2] + "."));
            }
            case "confiscar" -> {
                double r = sii.confiscar(target);
                sender.sendMessage(color(r < 0 ? "&c" + args[2] + " no esta fiscalizado." : "&aConfiscados ₯" + String.format("%,.0f", r) + " a " + args[2] + " (sii-confiscaciones.log)."));
            }
            case "congelar" -> {
                org.bukkit.entity.Player online = target.getPlayer();
                if (online == null) {
                    sender.sendMessage(color("&c" + args[2] + " debe estar conectado para congelarlo."));
                    return true;
                }
                double horas = 5;
                if (args.length >= 4) {
                    try { horas = Double.parseDouble(args[3]); } catch (NumberFormatException ignored) { }
                }
                String motivo = args.length >= 5 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : "orden del staff";
                sii.congelar(online, horas, motivo);
                sender.sendMessage(color("&a" + args[2] + " fiscalizado " + horas + " h."));
            }
            default -> sender.sendMessage(color("&eUso: &f/odysseia sii <estado|liberar|confiscar|congelar>"));
        }
        } catch (IllegalStateException e) {
            sender.sendMessage(color("&c[SII] " + e.getMessage()));
        }
        return true;
    }

    private boolean handleMaintenance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("odysseia.maintenance.admin")) {
            sender.sendMessage(color("&cNo tienes permiso para administrar mantenimiento."));
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(color(plugin.getMaintenanceGuard().isActive()
                    ? "&eMantenimiento activo por " + plugin.getMaintenanceGuard().remainingSeconds() + " segundos."
                    : "&aNo hay una ventana de mantenimiento activa."));
            return true;
        }
        if (args[1].equalsIgnoreCase("cancel")) {
            plugin.getMaintenanceGuard().cancel();
            return true;
        }
        if (args[1].equalsIgnoreCase("start")) {
            long seconds = 60L;
            if (args.length >= 3) {
                try {
                    seconds = Long.parseLong(args[2]);
                } catch (NumberFormatException error) {
                    sender.sendMessage(color("&cLos segundos deben ser un número."));
                    return true;
                }
            }
            plugin.getMaintenanceGuard().begin(seconds);
            return true;
        }
        sender.sendMessage(color("&eUso: &f/odysseia maintenance <start [segundos]|status|cancel>"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status", "vipalerts", "sfmaster", "maintenance", "pack", "sii").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sfmaster")) {
            return List.of("audit");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("maintenance")) {
            return List.of("start", "status", "cancel");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sii")) {
            return List.of("estado", "liberar", "confiscar", "congelar");
        }
        return List.of();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
