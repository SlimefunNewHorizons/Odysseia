package org.metamechanists.odysseia.listeners;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.metamechanists.odysseia.Odysseia;

/**
 * Rejects a command which has no useful effect but can flood the console when a plugin builds a
 * malformed announcement. The first occurrence in each interval retains its Java call path so the
 * emitting plugin can be fixed without logging a stack trace for every duplicate.
 */
public final class EmptySayCommandGuardListener implements Listener {

    private static final long DIAGNOSTIC_INTERVAL_MILLIS = 60_000L;

    private final Odysseia plugin;
    private final AtomicLong nextDiagnosticAt = new AtomicLong();

    public EmptySayCommandGuardListener(Odysseia plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!isEmptySay(event.getCommand())) {
            return;
        }

        event.setCancelled(true);
        long now = System.currentTimeMillis();
        long next = nextDiagnosticAt.get();
        if (now < next || !nextDiagnosticAt.compareAndSet(next, now + DIAGNOSTIC_INTERVAL_MILLIS)) {
            return;
        }

        plugin.getLogger().warning("Comando vacio 'say' bloqueado; se conserva la traza para identificar su emisor.");
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Origen del comando vacio 'say'", new Throwable());
    }

    static boolean isEmptySay(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim();
        return normalized.equalsIgnoreCase("say") || normalized.toLowerCase(Locale.ROOT).equals("minecraft:say");
    }
}
