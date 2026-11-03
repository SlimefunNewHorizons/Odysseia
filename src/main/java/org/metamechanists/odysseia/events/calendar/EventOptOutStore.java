package org.metamechanists.odysseia.events.calendar;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Almacén ligero y thread-safe de jugadores que silenciaron efectos/BossBars/sonidos de eventos (/evento silenciar).
 */
public class EventOptOutStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();

    public EventOptOutStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "event-optout.yml");
        load();
    }

    public void load() {
        optedOut.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String raw : yaml.getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public boolean isOptedOut(UUID uuid) {
        return uuid != null && optedOut.contains(uuid);
    }

    /**
     * Alterna el estado de silencio para un jugador.
     *
     * @return true si ahora está en modo silencioso (opt-out activo), false si volvió a activar avisos.
     */
    public boolean toggle(UUID uuid) {
        boolean nowOptedOut;
        if (optedOut.remove(uuid)) {
            nowOptedOut = false;
        } else {
            optedOut.add(uuid);
            nowOptedOut = true;
        }
        saveAsync();
        return nowOptedOut;
    }

    public int count() {
        return optedOut.size();
    }

    private void saveAsync() {
        List<String> snapshot = new ArrayList<>(optedOut.size());
        for (UUID id : optedOut) {
            snapshot.add(id.toString());
        }
        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeSnapshot(snapshot));
        } else {
            writeSnapshot(snapshot);
        }
    }

    private synchronized void writeSnapshot(List<String> snapshot) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("opted-out", snapshot);
            yaml.save(file);
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[OdysseiaEvents] Error guardando event-optout.yml", e);
            }
        }
    }
}
