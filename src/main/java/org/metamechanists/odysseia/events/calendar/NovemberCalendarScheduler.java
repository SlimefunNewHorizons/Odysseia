package org.metamechanists.odysseia.events.calendar;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.metamechanists.odysseia.events.OdysseiaEventManager;
import org.metamechanists.odysseia.events.boost.ServerBoostManager;
import org.metamechanists.odysseia.events.rush.RushEvent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Planificador diario de noviembre (1-30 nov) con zona horaria America/Santiago,
 * desactivación individual por día, límite anti-spam (1 aviso previo + 1 cierre) y telemetría.
 */
public class NovemberCalendarScheduler {

    private final JavaPlugin plugin;
    private final OdysseiaEventManager eventManager;
    private final EventTelemetryLogger telemetry;
    private final EventOptOutStore optOutStore;

    @Getter private ZoneId zoneId;
    @Getter private boolean masterEnabled;
    @Getter private int targetYear;
    @Getter private int preAnnounceMinutes;

    private final Map<Integer, NovemberDaySpec> schedule = new LinkedHashMap<>();
    private final Set<LocalDate> preAnnouncedDates = ConcurrentHashMap.newKeySet();
    private final Set<LocalDate> executedDates = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    public NovemberCalendarScheduler(
            JavaPlugin plugin,
            OdysseiaEventManager eventManager,
            EventTelemetryLogger telemetry,
            EventOptOutStore optOutStore
    ) {
        this.plugin = plugin;
        this.eventManager = eventManager;
        this.telemetry = telemetry;
        this.optOutStore = optOutStore;
        reloadConfig();
    }

    public synchronized void reloadConfig() {
        ConfigurationSection sec = plugin != null
                ? plugin.getConfig().getConfigurationSection("events.november-calendar")
                : null;
        this.masterEnabled = sec == null || sec.getBoolean("enabled", true);
        this.targetYear = sec != null ? sec.getInt("year", 2026) : 2026;
        this.preAnnounceMinutes = sec != null ? Math.max(1, sec.getInt("pre-announce-minutes", 10)) : 10;
        String tzRaw = sec != null ? sec.getString("timezone", "America/Santiago") : "America/Santiago";
        try {
            this.zoneId = ZoneId.of(tzRaw);
        } catch (Exception ignored) {
            this.zoneId = ZoneId.of("America/Santiago");
        }
        schedule.clear();
        schedule.putAll(NovemberDaySpec.loadSchedule(sec));
    }

    public void start() {
        shutdown();
        if (plugin != null && plugin.isEnabled()) {
            // Revisa cada 20 segundos (400 ticks): ligero, O(1), cero impacto en TPS
            this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkScheduleTick, 200L, 400L);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void checkScheduleTick() {
        evaluateAt(ZonedDateTime.now(zoneId));
    }

    /**
     * Evalúa el calendario en un instante dado (testeable determinísticamente sin reloj real).
     */
    public void evaluateAt(ZonedDateTime now) {
        if (!masterEnabled) return;
        if (now.getYear() != targetYear || now.getMonth() != Month.NOVEMBER) return;

        LocalDate today = now.toLocalDate();
        int day = today.getDayOfMonth();
        NovemberDaySpec spec = schedule.get(day);
        if (spec == null || !spec.enabled()) return;

        LocalTime current = now.toLocalTime();
        LocalTime target = spec.scheduledTime();
        long minutesUntil = ChronoUnit.MINUTES.between(current, target);

        // 1 único aviso previo (ej. entre 10 y 1 min antes)
        if (minutesUntil > 0 && minutesUntil <= preAnnounceMinutes && preAnnouncedDates.add(today)) {
            broadcastPreAnnouncement(spec, minutesUntil);
        }

        // Ventana de ejecución puntual: desde la hora exacta hasta +15 min por si hubo reinicio justo en punto
        long minutesPast = ChronoUnit.MINUTES.between(target, current);
        if (minutesPast >= 0 && minutesPast <= 15 && executedDates.add(today)) {
            executeDaySpec(spec, "auto-scheduler");
        }
    }

    private void broadcastPreAnnouncement(NovemberDaySpec spec, long minutesUntil) {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        String msg = ChatColor.translateAlternateColorCodes('&',
                "&8[&6✦ Noviembre&8] &eEn &f" + minutesUntil + " min &ecomienza &6" + spec.nameEs()
                + " &7(" + spec.rewardEs() + ") &8· &7Silenciar: &f/evento silenciar");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (optOutStore != null && optOutStore.isOptedOut(p.getUniqueId())) continue;
            p.sendMessage(msg);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.3f);
        }
    }

    public synchronized boolean executeDaySpec(NovemberDaySpec spec, String triggerSource) {
        if (spec == null) return false;
        int online = Bukkit.getOnlinePlayers().size();
        try {
            boolean startedAny = false;

            if (spec.boostCategory() != null && !spec.boostCategory().isBlank() && spec.multiplier() > 1.0) {
                ServerBoostManager.BoostCategory cat = ServerBoostManager.BoostCategory.fromString(spec.boostCategory());
                if (cat != null) {
                    var b = eventManager.getBoostManager().startBoost(cat, spec.multiplier(), spec.durationMinutes() * 60L);
                    startedAny |= (b != null);
                }
            }

            if (spec.secondaryBoost() != null && !spec.secondaryBoost().isBlank() && spec.secondaryMultiplier() > 1.0) {
                ServerBoostManager.BoostCategory cat2 = ServerBoostManager.BoostCategory.fromString(spec.secondaryBoost());
                if (cat2 != null) {
                    var b2 = eventManager.getBoostManager().startBoost(cat2, spec.secondaryMultiplier(), spec.durationMinutes() * 60L);
                    startedAny |= (b2 != null);
                }
            }

            if (spec.rushType() != null && !spec.rushType().isBlank()) {
                RushEvent.RushType rt = RushEvent.RushType.valueOf(spec.rushType());
                int rMin = spec.rushMinutes() > 0 ? spec.rushMinutes() : spec.durationMinutes();
                startedAny |= eventManager.startRush(rt, rMin * 60L);
            }

            if (spec.supplyDropTier() != null && !spec.supplyDropTier().isBlank()) {
                boolean dropOk = eventManager.startSupplyDrop(spec.supplyDropTier(), 1500);
                // Si no hay jugadores cargando chunks salvajes en ese instante, no es error fatal en COMBO
                startedAny |= dropOk;
            }

            if (startedAny) {
                telemetry.record(spec.day(), spec.eventId(), spec.kind(), "STARTED", online,
                        "Iniciado (" + triggerSource + "): " + spec.nameEs());
                return true;
            } else {
                telemetry.record(spec.day(), spec.eventId(), spec.kind(), "SKIPPED", online,
                        "Sin arranque efectivo (" + triggerSource + "), posible evento previo activo o mundo sin jugadores en drop");
                return false;
            }
        } catch (Exception ex) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[NovemberCalendar] Error ejecutando dia " + spec.day(), ex);
            }
            telemetry.record(spec.day(), spec.eventId(), spec.kind(), "ERROR", online,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    public synchronized boolean setDayEnabled(int day, boolean enabled) {
        NovemberDaySpec cur = schedule.get(day);
        if (cur == null) return false;
        schedule.put(day, cur.withEnabled(enabled));
        if (plugin != null) {
            plugin.getConfig().set("events.november-calendar.days." + day + ".enabled", enabled);
            plugin.saveConfig();
        }
        return true;
    }

    public synchronized void setMasterEnabled(boolean enabled) {
        this.masterEnabled = enabled;
        if (plugin != null) {
            plugin.getConfig().set("events.november-calendar.enabled", enabled);
            plugin.saveConfig();
        }
    }

    public synchronized NovemberDaySpec getDaySpec(int day) {
        return schedule.get(day);
    }

    public synchronized NovemberDaySpec getTodaySpec() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        if (now.getMonth() != Month.NOVEMBER) return null;
        return schedule.get(now.getDayOfMonth());
    }

    public synchronized List<NovemberDaySpec> getUpcomingSpecs(int maxCount) {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        int startDay = (now.getMonth() == Month.NOVEMBER) ? now.getDayOfMonth() : 1;
        List<NovemberDaySpec> out = new ArrayList<>();
        for (int d = startDay; d <= 30 && out.size() < maxCount; d++) {
            NovemberDaySpec s = schedule.get(d);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public synchronized Map<Integer, NovemberDaySpec> getAllSpecs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(schedule));
    }
}
