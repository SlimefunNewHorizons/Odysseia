package org.metamechanists.odysseia.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.metamechanists.odysseia.events.OdysseiaEventManager;
import org.metamechanists.odysseia.events.boost.ServerBoostManager;
import org.metamechanists.odysseia.events.boxes.StaffBoxRepository;
import org.metamechanists.odysseia.events.calendar.NovemberCalendarScheduler;
import org.metamechanists.odysseia.events.calendar.NovemberDaySpec;
import org.metamechanists.odysseia.events.model.ActiveEvent;
import org.metamechanists.odysseia.events.pvp.PvPKit;
import org.metamechanists.odysseia.events.rush.RushEvent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Comando principal /evento para el control y participación en la suite de eventos.
 */
public class EventoCommand implements CommandExecutor, TabCompleter {

    private final OdysseiaEventManager eventManager;

    public EventoCommand(OdysseiaEventManager eventManager) {
        this.eventManager = eventManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // Subcomandos públicos disponibles para todos los jugadores
        if (sub.equals("status")) {
            handleStatus(sender);
            return true;
        }
        if (sub.equals("hoy") || sub.equals("today")) {
            handleCalendarView(sender, new String[]{"calendario", "hoy"});
            return true;
        }
        if (sub.equals("silenciar") || sub.equals("optout") || sub.equals("mute")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Solo jugadores pueden alternar su modo silencioso de eventos.");
                return true;
            }
            boolean muted = eventManager.getOptOutStore().toggle(player.getUniqueId());
            if (muted) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&8[&6✦ Eventos&8] &eModo silencioso &cACTIVADO&e. No verás BossBars, sonidos ni efectos festivos de eventos (&f/evento silenciar &epara reactivar)."));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&8[&6✦ Eventos&8] &aAvisos y efectos de eventos REACTIVADOS."));
            }
            return true;
        }
        if ((sub.equals("calendario") || sub.equals("calendar") || sub.equals("noviembre"))
                && (args.length == 1
                || args[1].equalsIgnoreCase("hoy")
                || args[1].equalsIgnoreCase("proximos")
                || isDayNumber(args[1]))) {
            handleCalendarView(sender, args);
            return true;
        }

        // Subcomandos permitidos a jugadores regulares si hay torneo activo
        if (sub.equals("pvp") && args.length >= 2 && (args[1].equalsIgnoreCase("join") || args[1].equalsIgnoreCase("leave"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Comando solo disponible para jugadores.");
                return true;
            }
            if (args[1].equalsIgnoreCase("join")) {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.YELLOW + "Uso: /evento pvp join <gladiador|tanque|arquero|berserker>");
                    return true;
                }
                PvPKit kit = PvPKit.fromString(args[2]);
                if (kit == null) {
                    player.sendMessage(ChatColor.RED + "Kit no válido. Opciones: gladiador, tanque, arquero, berserker.");
                    return true;
                }
                eventManager.getPvpManager().joinTournament(player, kit);
                return true;
            } else {
                eventManager.getPvpManager().leaveTournament(player);
                return true;
            }
        }

        // El resto de subcomandos requieren permiso administrativo
        if (!sender.hasPermission("odysseia.evento.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permisos para administrar eventos.");
            return true;
        }

        switch (sub) {
            case "rush" -> handleRush(sender, args);
            case "drop" -> handleDrop(sender, args);
            case "boost" -> handleBoost(sender, args);
            case "box" -> handleBox(sender, args);
            case "boss" -> handleBoss(sender, args);
            case "pvp" -> handlePvP(sender, args);
            case "seasonal" -> handleSeasonal(sender, args);
            case "calendario", "calendar", "noviembre" -> handleCalendarAdmin(sender, args);
            case "stop" -> handleStop(sender, args);
            case "status" -> handleStatus(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleRush(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /evento rush <mineria|pesca|cosecha> [minutos]");
            return;
        }

        String typeStr = args[1].toLowerCase(Locale.ROOT);
        RushEvent.RushType type = switch (typeStr) {
            case "mineria", "mining" -> RushEvent.RushType.MINING;
            case "pesca", "fishing" -> RushEvent.RushType.FISHING;
            case "cosecha", "harvest", "papas" -> RushEvent.RushType.HARVEST;
            default -> null;
        };

        if (type == null) {
            sender.sendMessage(ChatColor.RED + "Tipo de rush no reconocido. Opciones: mineria, pesca, cosecha.");
            return;
        }

        long minutes = 15;
        if (args.length >= 3) {
            try {
                minutes = Long.parseLong(args[2].replace("m", ""));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Duración inválida en minutos.");
                return;
            }
        }

        boolean ok = eventManager.startRush(type, minutes * 60);
        if (ok) {
            sender.sendMessage(ChatColor.GREEN + "Rush de " + type.getDisplayName() + " iniciado por " + minutes + " minutos.");
        } else {
            sender.sendMessage(ChatColor.RED + "Ya hay un Rush activo en este momento. Usa /evento stop rush primero.");
        }
    }

    private void handleDrop(CommandSender sender, String[] args) {
        String tier = args.length >= 2 ? args[1] : "epico";
        int radius = 1000;
        if (args.length >= 3) {
            try {
                radius = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {}
        }

        boolean ok = eventManager.startSupplyDrop(tier, radius);
        if (ok) {
            sender.sendMessage(ChatColor.GREEN + "Entrega de suministros (" + tier + ") generada con éxito.");
        } else {
            sender.sendMessage(ChatColor.RED + "Ya hay un evento de suministros activo o no se encontró ubicación segura.");
        }
    }

    private void handleBoost(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /evento boost <economia|xp|slimefun|dracmas> <multiplicador> [minutos]");
            return;
        }

        String catStr = args[1].toLowerCase(Locale.ROOT);
        if (catStr.contains("voto") || catStr.contains("vote")) {
            sender.sendMessage(ChatColor.RED + "El boost de votos está deshabilitado temporalmente debido a configuración de sitios pendiente.");
            return;
        }

        ServerBoostManager.BoostCategory cat = ServerBoostManager.BoostCategory.fromString(catStr);
        if (cat == null) {
            sender.sendMessage(ChatColor.RED + "Categoría no válida. Opciones: economia, xp, slimefun, dracmas.");
            return;
        }

        double mult;
        try {
            mult = Double.parseDouble(args[2].replace("x", ""));
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Multiplicador inválido (ej: 1.5, 2.0).");
            return;
        }

        long minutes = 30;
        if (args.length >= 4) {
            try {
                minutes = Long.parseLong(args[3].replace("m", ""));
            } catch (NumberFormatException ignored) {}
        }

        var boost = eventManager.getBoostManager().startBoost(cat, mult, minutes * 60);
        if (boost != null) {
            sender.sendMessage(ChatColor.GREEN + "Boost " + cat.getDisplayName() + " " + mult + "x activado por " + minutes + " minutos.");
        } else {
            sender.sendMessage(ChatColor.RED + "Error activando boost.");
        }
    }

    private void handleBox(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /evento box <save|list|give|delete> ...");
            return;
        }

        StaffBoxRepository repo = eventManager.getBoxRepository();
        if (repo == null) {
            sender.sendMessage(ChatColor.RED + "Repositorio de cajas no disponible.");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "save" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Solo jugadores pueden guardar cajas mirando a un cofre.");
                    return;
                }
                if (args.length < 3) {
                    player.sendMessage(ChatColor.YELLOW + "Uso: /evento box save <nombre>");
                    return;
                }
                String name = args[2];
                ItemStack[] items = StaffBoxRepository.getItemsFromTargetBlock(player);
                if (items == null || items.length == 0) {
                    // Si no está mirando un cofre, guardar su inventario
                    items = player.getInventory().getStorageContents();
                }
                boolean ok = repo.saveBox(name, player.getName(), items);
                if (ok) {
                    player.sendMessage(ChatColor.GREEN + "Plantilla de caja '" + name + "' guardada exitosamente.");
                } else {
                    player.sendMessage(ChatColor.RED + "No se pudo guardar la caja.");
                }
            }
            case "list" -> {
                List<String> list = repo.listBoxes();
                sender.sendMessage(ChatColor.GOLD + "=== Plantillas de Recompensas Registradas ===");
                if (list.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "No hay cajas guardadas todavía.");
                } else {
                    list.forEach(item -> sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "  • " + item)));
                }
            }
            case "give" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /evento box give <nombre> <all|jugador>");
                    return;
                }
                String name = args[2];
                String targetStr = args[3];
                if (targetStr.equalsIgnoreCase("all")) {
                    int delivered = repo.giveBoxToAll(name);
                    sender.sendMessage(ChatColor.GREEN + "Caja '" + name + "' entregada a " + delivered + " jugadores conectados.");
                } else {
                    Player target = Bukkit.getPlayer(targetStr);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Jugador no encontrado.");
                        return;
                    }
                    int items = repo.giveBox(name, target);
                    if (items > 0) {
                        sender.sendMessage(ChatColor.GREEN + "Caja '" + name + "' entregada a " + target.getName() + " (" + items + " items).");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Caja no encontrada o vacía.");
                    }
                }
            }
            case "delete" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /evento box delete <nombre>");
                    return;
                }
                String name = args[2];
                boolean deleted = repo.deleteBox(name);
                if (deleted) {
                    sender.sendMessage(ChatColor.GREEN + "Caja '" + name + "' eliminada.");
                } else {
                    sender.sendMessage(ChatColor.RED + "No se encontró la caja para eliminar.");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Acción desconocida para /evento box.");
        }
    }

    private void handleBoss(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("spawn")) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /evento boss spawn <tipo>");
            return;
        }

        String bossType = args[2].toLowerCase(Locale.ROOT);
        Location spawnLoc = sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        eventManager.getBossManager().spawnEventBoss(bossType, spawnLoc, sender instanceof Player p ? p : null);
        sender.sendMessage(ChatColor.GREEN + "Invocación de jefe '" + bossType + "' enviada.");
    }

    private void handlePvP(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /evento pvp <start|stop|rank|setspawn> ...");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> {
                Location loc = sender instanceof Player p ? p.getLocation() : null;
                eventManager.getPvpManager().startTournament(loc);
                sender.sendMessage(ChatColor.GREEN + "Torneo PvP iniciado.");
            }
            case "stop" -> {
                eventManager.getPvpManager().stopTournament();
                sender.sendMessage(ChatColor.GREEN + "Torneo PvP detenido y participantes restaurados.");
            }
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Comando solo para jugadores en la arena.");
                    return;
                }
                eventManager.getPvpManager().setArenaSpawn(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Punto de spawn de la arena PvP establecido.");
            }
            case "rank" -> {
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /evento pvp rank <jugador> <rango> <dias>");
                    return;
                }
                Player winner = Bukkit.getPlayer(args[2]);
                if (winner == null) {
                    sender.sendMessage(ChatColor.RED + "Jugador no encontrado.");
                    return;
                }
                String rank = args[3];
                int days;
                try {
                    days = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Días inválidos.");
                    return;
                }
                eventManager.getPvpManager().awardTemporaryRank(winner, rank, days);
                sender.sendMessage(ChatColor.GREEN + "Rango temporal " + rank + " otorgado a " + winner.getName() + " por " + days + " días.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Acción PvP no válida.");
        }
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
            eventManager.stopAll();
            sender.sendMessage(ChatColor.GREEN + "Todos los eventos activos han sido detenidos.");
            return;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        switch (target) {
            case "rush" -> {
                eventManager.stopRush();
                sender.sendMessage(ChatColor.GREEN + "Evento Rush detenido.");
            }
            case "drop" -> {
                eventManager.stopSupplyDrop();
                sender.sendMessage(ChatColor.GREEN + "Entrega de suministros detenida.");
            }
            case "boost" -> {
                eventManager.getBoostManager().stopAll();
                sender.sendMessage(ChatColor.GREEN + "Multiplicadores globales detenidos.");
            }
            case "pvp" -> {
                eventManager.getPvpManager().stopTournament();
                sender.sendMessage(ChatColor.GREEN + "Torneo PvP detenido.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Tipo de evento desconocido para detener.");
        }
    }

    private void handleStatus(CommandSender sender) {
        List<ActiveEvent> active = eventManager.getActiveEvents();
        sender.sendMessage(ChatColor.GOLD + "=== ESTADO DE EVENTOS ODYSSEIA ===");
        if (active.isEmpty() && !eventManager.getPvpManager().isActive()) {
            sender.sendMessage(ChatColor.GRAY + "No hay ningún evento activo en este momento.");
            return;
        }

        for (ActiveEvent ev : active) {
            long rem = ev.getTimeRemainingSeconds();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  • &e" + ev.getName() + " &8| &fRestante: &b" + (rem / 60) + "m " + (rem % 60) + "s"));
        }
        if (eventManager.getPvpManager().isActive()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  • &cTorneo PvP Gladiador &8| &aActivo"));
        }
    }

    private void handleSeasonal(CommandSender sender, String[] args) {
        var sm = eventManager.getSeasonalManager();
        if (sm == null) {
            sender.sendMessage(ChatColor.RED + "El gestor estacional no está disponible.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.GOLD + "=== ESTADO DE EVENTOS ESTACIONALES ===");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "  • &cHalloween (Tártaro & Noche Perpetua): " + (sm.isHalloweenActive() ? "&aACTIVO" : "&cINACTIVO")));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "  • &eCumpleaños del Creador (29 Nov, sin boost): " + (sm.isBirthdayActive() ? "&aACTIVO" : "&cINACTIVO")));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "  • &bSolsticio / Navidad (Diciembre): " + (sm.isWinterActive() ? "&aACTIVO" : "&cINACTIVO")));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "  • &dSan Valentín / Afrodita (Febrero): " + (sm.isValentinesActive() ? "&aACTIVO" : "&cINACTIVO")));
            sender.sendMessage(ChatColor.YELLOW + "Uso: /evento seasonal <halloween|cumplejack|solsticio|valentin> <on|off|auto>");
            return;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        String state = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "toggle";

        Boolean val = switch (state) {
            case "on", "true", "1" -> Boolean.TRUE;
            case "off", "false", "0" -> Boolean.FALSE;
            case "auto", "default", "reset" -> null;
            default -> null;
        };

        switch (mode) {
            case "halloween", "tartaro" -> {
                if ("toggle".equals(state)) val = !sm.isHalloweenActive();
                sm.setForceHalloween(val);
                sender.sendMessage(ChatColor.GREEN + "Halloween forzado a: " + (val == null ? "AUTO" : (val ? "ON" : "OFF")));
            }
            case "cumplejack", "birthday", "creador" -> {
                if ("toggle".equals(state)) val = !sm.isBirthdayActive();
                sm.setForceBirthday(val);
                sender.sendMessage(ChatColor.GREEN + "Cumpleaños del Creador forzado a: " + (val == null ? "AUTO" : (val ? "ON" : "OFF")));
            }
            case "solsticio", "navidad", "winter" -> {
                if ("toggle".equals(state)) val = !sm.isWinterActive();
                sm.setForceWinter(val);
                sender.sendMessage(ChatColor.GREEN + "Solsticio/Navidad forzado a: " + (val == null ? "AUTO" : (val ? "ON" : "OFF")));
            }
            case "valentin", "afrodita" -> {
                if ("toggle".equals(state)) val = !sm.isValentinesActive();
                sm.setForceValentines(val);
                sender.sendMessage(ChatColor.GREEN + "San Valentín forzado a: " + (val == null ? "AUTO" : (val ? "ON" : "OFF")));
            }
            default -> sender.sendMessage(ChatColor.RED + "Modo estacional desconocido. Opciones: halloween, cumplejack, solsticio, valentin.");
        }
    }

    private static boolean isDayNumber(String raw) {
        try {
            int d = Integer.parseInt(raw);
            return d >= 1 && d <= 30;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void handleCalendarView(CommandSender sender, String[] args) {
        NovemberCalendarScheduler cal = eventManager.getNovemberCalendar();
        if (cal == null) {
            sender.sendMessage(ChatColor.RED + "El calendario de eventos no está disponible.");
            return;
        }
        if (args.length >= 2 && isDayNumber(args[1])) {
            int d = Integer.parseInt(args[1]);
            NovemberDaySpec spec = cal.getDaySpec(d);
            if (spec == null) {
                sender.sendMessage(ChatColor.RED + "Día fuera de rango (1-30).");
                return;
            }
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&6&l✦ Evento " + d + " Nov (" + spec.formattedTime() + " CL) &8» "
                    + (spec.enabled() ? "&a[ACTIVO] " : "&c[PAUSADO] ")
                    + "&e" + spec.nameEs() + " &7(" + spec.durationMinutes() + "m · " + spec.rewardEs() + ")"));
            return;
        }

        NovemberDaySpec today = cal.getTodaySpec();
        sender.sendMessage(ChatColor.GOLD + "=== CALENDARIO DE EVENTOS NOVIEMBRE 2026 (Hora Chile) ===");
        if (today != null) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &6★ HOY (" + today.day() + " Nov · " + today.formattedTime() + "): &e" + today.nameEs()
                    + " &7— " + today.rewardEs() + (today.enabled() ? "" : " &c[PAUSADO]")));
        }
        List<NovemberDaySpec> next = cal.getUpcomingSpecs(4);
        for (NovemberDaySpec s : next) {
            if (today != null && s.day() == today.day()) continue;
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &7• " + s.day() + " Nov (" + s.formattedTime() + "): &f" + s.nameEs()
                    + " &8[" + s.durationMinutes() + "m]" + (s.enabled() ? "" : " &c[PAUSADO]")));
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &7Calendario completo: &bweb.drakescraft.cl/eventos &8| &7Silenciar avisos: &f/evento silenciar"));
    }

    private void handleCalendarAdmin(CommandSender sender, String[] args) {
        NovemberCalendarScheduler cal = eventManager.getNovemberCalendar();
        if (cal == null) {
            sender.sendMessage(ChatColor.RED + "El calendario de eventos no está disponible.");
            return;
        }
        if (args.length < 2) {
            handleCalendarView(sender, args);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                cal.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "Calendario de noviembre recargado desde config.yml.");
            }
            case "run" -> {
                if (args.length < 3 || !isDayNumber(args[2])) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /evento calendario run <1-30>");
                    return;
                }
                int day = Integer.parseInt(args[2]);
                NovemberDaySpec spec = cal.getDaySpec(day);
                boolean ok = cal.executeDaySpec(spec, "admin:" + sender.getName());
                sender.sendMessage(ok
                        ? ChatColor.GREEN + "Evento del día " + day + " (" + spec.nameEs() + ") iniciado."
                        : ChatColor.YELLOW + "No se inició el evento del día " + day + " (revisa /evento status o chunks cargados).");
            }
            case "toggle" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /evento calendario toggle <1-30|all> <on|off>");
                    return;
                }
                boolean enable = args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true");
                if (args[2].equalsIgnoreCase("all")) {
                    cal.setMasterEnabled(enable);
                    sender.sendMessage(ChatColor.GREEN + "Calendario maestro de noviembre: " + (enable ? "ON" : "OFF"));
                } else if (isDayNumber(args[2])) {
                    int day = Integer.parseInt(args[2]);
                    cal.setDayEnabled(day, enable);
                    sender.sendMessage(ChatColor.GREEN + "Evento día " + day + " de noviembre: " + (enable ? "ON" : "OFF"));
                } else {
                    sender.sendMessage(ChatColor.RED + "Día inválido. Usa 1-30 o all.");
                }
            }
            default -> handleCalendarView(sender, args);
        }
    }

    private void sendHelp(CommandSender sender) {
        if (!sender.hasPermission("odysseia.evento.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', """
            &6&l═══════ EVENTOS DE DRAKESCRAFT ═══════
            &e/evento hoy &7- Ver el evento programado de hoy y próximos
            &e/evento calendario [1-30] &7- Consultar calendario de noviembre
            &e/evento status &7- Ver eventos activos en este momento
            &e/evento silenciar &7- Silenciar/activar BossBar, sonidos y efectos
            &e/evento pvp <join|leave> &7- Unirse o salir si hay torneo PvP activo
            &7Web oficial: &bweb.drakescraft.cl/eventos
            &6&l══════════════════════════════════════
            """));
            return;
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', """
        &6&l═══════ SUITE DE EVENTOS ODYSSEIA ═══════
        &e/evento hoy | calendario [1-30|run|toggle|reload] &7- Calendario Noviembre
        &e/evento silenciar &7- Opt-out personal de BossBar/sonidos/efectos
        &e/evento rush <mineria|pesca|cosecha> [min] &7- Maratón cronometrado
        &e/evento drop [tier] [radio] &7- Caída de suministros celestiales seguros
        &e/evento boost <economia|xp|slimefun|dracmas> <mult> [min] &7- Boost global
        &e/evento box <save|list|give|delete> &7- Cajas y plantillas de premios
        &e/evento boss spawn <tipo> &7- Invocar jefe con reparto por daño
        &e/evento pvp <start|stop|rank|setspawn> &7- Torneo PvP 100% seguro
        &e/evento seasonal [modo] [on|off|auto] &7- Eventos de Halloween y Calendario
        &e/evento status &7- Ver eventos activos
        &e/evento stop [rush|drop|boost|pvp|all] &7- Detener eventos
        &6&l═════════════════════════════════════════
        """));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("hoy", "calendario", "status", "silenciar"));
            if (sender.hasPermission("odysseia.evento.admin")) {
                list.addAll(List.of("rush", "drop", "boost", "box", "boss", "pvp", "seasonal", "stop"));
            } else if (eventManager.getPvpManager().isActive()) {
                list.add("pvp");
            }
            return filter(list, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "calendario", "calendar", "noviembre" -> sender.hasPermission("odysseia.evento.admin")
                        ? filter(List.of("hoy", "proximos", "toggle", "run", "reload"), args[1])
                        : filter(List.of("hoy", "proximos"), args[1]);
                case "rush" -> filter(List.of("mineria", "pesca", "cosecha"), args[1]);
                case "drop" -> filter(List.of("comun", "raro", "epico", "legendario"), args[1]);
                case "boost" -> filter(List.of("economia", "xp", "slimefun", "dracmas"), args[1]);
                case "box" -> filter(List.of("save", "list", "give", "delete"), args[1]);
                case "boss" -> filter(List.of("spawn"), args[1]);
                case "seasonal" -> filter(List.of("halloween", "cumplejack", "solsticio", "valentin"), args[1]);
                case "pvp" -> {
                    if (sender.hasPermission("odysseia.evento.admin")) {
                        yield filter(List.of("start", "stop", "join", "leave", "rank", "setspawn"), args[1]);
                    } else {
                        yield filter(List.of("join", "leave"), args[1]);
                    }
                }
                case "stop" -> filter(List.of("all", "rush", "drop", "boost", "pvp"), args[1]);
                default -> Collections.emptyList();
            };
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("box") && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("delete"))) {
                if (eventManager.getBoxRepository() != null) {
                    return filter(new ArrayList<>(eventManager.getBoxRepository().getBoxNames()), args[2]);
                }
            } else if (sub.equals("pvp") && args[1].equalsIgnoreCase("join")) {
                return filter(List.of("gladiador", "tanque", "arquero", "berserker"), args[2]);
            } else if (sub.equals("boss") && args[1].equalsIgnoreCase("spawn")) {
                return filter(List.of("zeus", "poseidon", "hades", "thor", "odin", "ares", "kratos", "garou", "cerbero", "hidra"), args[2]);
            } else if (sub.equals("seasonal")) {
                return filter(List.of("on", "off", "auto"), args[2]);
            }
        }

        if (args.length == 4) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("box") && args[1].equalsIgnoreCase("give")) {
                List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                players.add("all");
                return filter(players, args[3]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
