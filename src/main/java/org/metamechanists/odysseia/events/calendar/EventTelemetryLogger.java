package org.metamechanists.odysseia.events.calendar;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Telemetría estructurada JSONL para auditoría de eventos diarios y detección automática por SAORI.
 */
public class EventTelemetryLogger {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JavaPlugin plugin;
    private final File file;

    public EventTelemetryLogger(JavaPlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), "events_telemetry.jsonl"));
    }

    public EventTelemetryLogger(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    public void record(int day, String eventId, String kind, String status, int participants, String detail) {
        long epochSec = Instant.now().getEpochSecond();
        ZonedDateTime nowCl = ZonedDateTime.now(SANTIAGO);
        String dateStr = nowCl.format(DATE_FMT);
        String line = "{\"ts\":" + epochSec
                + ",\"date\":\"" + escape(dateStr) + "\""
                + ",\"day\":" + day
                + ",\"event_id\":\"" + escape(eventId) + "\""
                + ",\"kind\":\"" + escape(kind) + "\""
                + ",\"status\":\"" + escape(status) + "\""
                + ",\"participants\":" + Math.max(0, participants)
                + ",\"detail\":\"" + escape(detail) + "\"}\n";

        if (plugin != null && plugin.isEnabled() && Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> appendLine(line));
        } else {
            appendLine(line);
        }
    }

    private synchronized void appendLine(String line) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8, true)) {
                fw.write(line);
            }
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[OdysseiaEvents] No se pudo escribir telemetria de eventos", e);
            }
        }
    }

    public File getFile() {
        return file;
    }

    static String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }
}
