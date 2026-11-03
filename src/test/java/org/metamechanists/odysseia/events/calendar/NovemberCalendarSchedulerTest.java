package org.metamechanists.odysseia.events.calendar;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovemberCalendarSchedulerTest {

    @Test
    void defaultScheduleHasExactlyThirtyDays() {
        List<NovemberDaySpec> list = NovemberDaySpec.defaultSpecs();
        assertEquals(30, list.size(), "Debe haber exactamente 30 eventos para noviembre");

        Map<Integer, NovemberDaySpec> map = NovemberDaySpec.loadSchedule(null);
        assertEquals(30, map.size());

        for (int day = 1; day <= 30; day++) {
            assertTrue(map.containsKey(day), "Falta el día " + day);
            NovemberDaySpec spec = map.get(day);
            assertEquals(day, spec.day());
            assertNotNull(spec.scheduledTime());
            assertTrue(spec.durationMinutes() >= 15, "Duración mínima 15 min");
            assertNotNull(spec.nameEs());
            assertNotNull(spec.nameEn());
            assertNotNull(spec.kind());
        }
    }

    @Test
    void founderWeekAndBirthdayMilestones() {
        Map<Integer, NovemberDaySpec> map = NovemberDaySpec.loadSchedule(null);

        // Días 22-28: semana_cumple
        for (int day = 22; day <= 28; day++) {
            assertEquals("semana_cumple", map.get(day).phase(), "Día " + day + " debe pertenecer a semana_cumple");
        }

        // Día 29: cumple_owner
        NovemberDaySpec day29 = map.get(29);
        assertEquals("cumple_owner", day29.phase());
        assertTrue(day29.nameEs().contains("JackStar") || day29.nameEs().contains("Cumpleaños"));

        // Día 30: cierre_mes
        NovemberDaySpec day30 = map.get(30);
        assertEquals("cierre_mes", day30.phase());
    }

    @Test
    void scheduleOverridesFromYaml() {
        String yaml = """
        events:
          november-calendar:
            enabled: true
            days:
              1:
                enabled: false
                time: "21:30"
                multiplier: 3.5
              29:
                enabled: true
                duration-minutes: 45
        """;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        var section = config.getConfigurationSection("events.november-calendar");

        Map<Integer, NovemberDaySpec> map = NovemberDaySpec.loadSchedule(section);
        NovemberDaySpec day1 = map.get(1);
        assertFalse(day1.enabled());
        assertEquals(LocalTime.of(21, 30), day1.scheduledTime());
        assertEquals(3.5, day1.multiplier(), 0.001);

        NovemberDaySpec day29 = map.get(29);
        assertTrue(day29.enabled());
        assertEquals(45, day29.durationMinutes());
    }

    @Test
    void optOutStoreTogglesAndPersists(@TempDir Path tempDir) {
        JavaPlugin pluginMock = mock(JavaPlugin.class);
        when(pluginMock.getDataFolder()).thenReturn(tempDir.toFile());
        when(pluginMock.isEnabled()).thenReturn(false);

        EventOptOutStore store1 = new EventOptOutStore(pluginMock);
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        assertFalse(store1.isOptedOut(playerA));
        assertTrue(store1.toggle(playerA)); // Now true
        assertTrue(store1.isOptedOut(playerA));

        // Re-load from disk
        EventOptOutStore store2 = new EventOptOutStore(pluginMock);
        assertTrue(store2.isOptedOut(playerA));
        assertFalse(store2.isOptedOut(playerB));

        // Toggle off
        assertFalse(store2.toggle(playerA));
        assertFalse(store2.isOptedOut(playerA));
    }

    @Test
    void telemetryLoggerWritesValidJsonl(@TempDir Path tempDir) throws Exception {
        File logFile = tempDir.resolve("events_telemetry.jsonl").toFile();
        EventTelemetryLogger logger = new EventTelemetryLogger(null, logFile);

        logger.record(3, "nov2026-day03", "RUSH_MINING", "START", 0, "inicio_programado");
        logger.record(3, "nov2026-day03", "RUSH_MINING", "COMPLETED", 12, "cierre_con_podio");
        logger.record(4, "nov2026-day04", "GLOBAL_BOOST", "ERROR", 0, "ChunkLoadTimeout");

        assertTrue(logFile.exists());

        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"status\":\"START\""));
        assertTrue(lines.get(0).contains("\"event_id\":\"nov2026-day03\""));
        assertTrue(lines.get(1).contains("\"status\":\"COMPLETED\""));
        assertTrue(lines.get(1).contains("\"participants\":12"));
        assertTrue(lines.get(2).contains("\"status\":\"ERROR\""));
        assertTrue(lines.get(2).contains("\"detail\":\"ChunkLoadTimeout\""));
    }
}
