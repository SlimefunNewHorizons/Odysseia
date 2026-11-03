package org.metamechanists.odysseia.events;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.metamechanists.odysseia.events.boost.BoostListener;
import org.metamechanists.odysseia.events.boost.ServerBoostManager;
import org.metamechanists.odysseia.events.boss.EventBossManager;
import org.metamechanists.odysseia.events.boxes.StaffBoxRepository;
import org.metamechanists.odysseia.events.calendar.EventOptOutStore;
import org.metamechanists.odysseia.events.calendar.EventTelemetryLogger;
import org.metamechanists.odysseia.events.calendar.NovemberCalendarScheduler;
import org.metamechanists.odysseia.events.drop.SupplyDropEvent;
import org.metamechanists.odysseia.events.model.ActiveEvent;
import org.metamechanists.odysseia.events.pvp.EventPvPManager;
import org.metamechanists.odysseia.events.pvp.PvPListener;
import org.metamechanists.odysseia.events.rush.RushEvent;
import org.metamechanists.odysseia.events.rush.RushListener;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Coordinador maestro de la Suite de Eventos de Odysseia.
 * Gestiona el ciclo de vida de maratones Rush, entregas celestiales, multiplicadores globales,
 * cofres de recompensa, jefes mundiales y torneos PvP seguros.
 */
public class OdysseiaEventManager {

    private final JavaPlugin plugin;
    @Getter private final String discordWebhookUrl;

    @Getter private final EventOptOutStore optOutStore;
    @Getter private final EventTelemetryLogger telemetryLogger;
    @Getter private final ServerBoostManager boostManager;
    @Getter private final StaffBoxRepository boxRepository;
    @Getter private final EventBossManager bossManager;
    @Getter private final EventPvPManager pvpManager;
    @Getter private final SeasonalEventManager seasonalManager;
    @Getter private final NovemberCalendarScheduler novemberCalendar;

    @Getter private RushEvent currentRush;
    @Getter private SupplyDropEvent currentDrop;

    private BukkitTask tickTask;

    public OdysseiaEventManager(JavaPlugin plugin) {
        this.plugin = plugin;
        
        // Obtener webhook URL de configuración o default
        String url = plugin.getConfig().getString("events.discord-webhook-url", "");
        if (url == null || url.isBlank() || url.contains("REPLACE_ME")) {
            url = plugin.getConfig().getString("discord.webhook-anuncios-url", "");
        }
        this.discordWebhookUrl = url;

        this.optOutStore = new EventOptOutStore(plugin);
        this.telemetryLogger = new EventTelemetryLogger(plugin);
        this.boostManager = new ServerBoostManager(plugin, discordWebhookUrl, optOutStore, telemetryLogger);

        StaffBoxRepository boxRepo = null;
        try {
            File boxDb = new File(plugin.getDataFolder(), "events_boxes.db");
            boxRepo = new StaffBoxRepository(boxDb, plugin.getLogger());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[OdysseiaEvents] No se pudo inicializar la base de datos de StaffBoxes", e);
        }
        this.boxRepository = boxRepo;

        this.bossManager = new EventBossManager(plugin, discordWebhookUrl);
        this.pvpManager = new EventPvPManager(plugin, discordWebhookUrl);
        this.seasonalManager = new SeasonalEventManager(plugin, boostManager, discordWebhookUrl, optOutStore, pvpManager);
        this.novemberCalendar = new NovemberCalendarScheduler(plugin, this, telemetryLogger, optOutStore);

        registerListeners();
        startTickTask();
        seasonalManager.start();
        novemberCalendar.start();

        plugin.getLogger().info("[OdysseiaEvents] Suite de Eventos inicializada correctamente.");
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new RushListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(new BoostListener(boostManager), plugin);
        Bukkit.getPluginManager().registerEvents(bossManager, plugin);
        Bukkit.getPluginManager().registerEvents(new PvPListener(pvpManager), plugin);
    }

    private void startTickTask() {
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Tick Rush Event
            if (currentRush != null) {
                if (currentRush.isActive()) {
                    currentRush.tick();
                } else {
                    currentRush = null;
                }
            }

            // Tick Supply Drop
            if (currentDrop != null) {
                if (currentDrop.isActive()) {
                    currentDrop.tick();
                } else {
                    currentDrop = null;
                }
            }

            // Tick Server Boosts
            boostManager.tick();

        }, 20L, 20L); // 1 segundo (20 ticks)
    }

    public boolean startRush(RushEvent.RushType type, long durationSeconds) {
        if (currentRush != null && currentRush.isActive()) {
            return false;
        }
        this.currentRush = new RushEvent(plugin, type, durationSeconds, discordWebhookUrl, optOutStore, telemetryLogger);
        this.currentRush.start();
        return true;
    }

    public void stopRush() {
        if (currentRush != null) {
            currentRush.stop();
            currentRush = null;
        }
    }

    public boolean startSupplyDrop(String lootTier, int radius) {
        if (currentDrop != null && currentDrop.isActive()) {
            return false;
        }
        SupplyDropEvent candidate = new SupplyDropEvent(plugin, 900L, discordWebhookUrl, lootTier, radius, optOutStore, telemetryLogger);
        candidate.start();
        if (!candidate.isActive()) {
            return false;
        }
        this.currentDrop = candidate;
        return true;
    }

    public void stopSupplyDrop() {
        if (currentDrop != null) {
            currentDrop.stop();
            currentDrop = null;
        }
    }

    public void stopAll() {
        stopRush();
        stopSupplyDrop();
        boostManager.stopAll();
        pvpManager.stopTournament();
    }

    public List<ActiveEvent> getActiveEvents() {
        List<ActiveEvent> list = new ArrayList<>();
        if (currentRush != null && currentRush.isActive()) list.add(currentRush);
        if (currentDrop != null && currentDrop.isActive()) list.add(currentDrop);
        list.addAll(boostManager.getActiveBoosts().values());
        return list;
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        stopAll();
        if (boxRepository != null) {
            boxRepository.close();
        }
        if (pvpManager != null) {
            pvpManager.shutdown();
        }
        if (seasonalManager != null) {
            seasonalManager.shutdown();
        }
        if (novemberCalendar != null) {
            novemberCalendar.shutdown();
        }
    }
}
