package org.metamechanists.odysseia.events.calendar;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Definición inmutable de cada uno de los 30 eventos diarios de noviembre 2026.
 */
public record NovemberDaySpec(
        int day,
        LocalTime scheduledTime,
        int durationMinutes,
        boolean enabled,
        String phase,
        String kind,
        String nameEs,
        String nameEn,
        String rewardEs,
        String boostCategory,
        double multiplier,
        String secondaryBoost,
        double secondaryMultiplier,
        String rushType,
        int rushMinutes,
        String supplyDropTier
) {

    public String formattedTime() {
        return String.format(Locale.ROOT, "%02d:%02d", scheduledTime.getHour(), scheduledTime.getMinute());
    }

    public String eventId() {
        return String.format(Locale.ROOT, "nov2026-day%02d", day);
    }

    public NovemberDaySpec withEnabled(boolean newEnabled) {
        return new NovemberDaySpec(
                day, scheduledTime, durationMinutes, newEnabled, phase, kind,
                nameEs, nameEn, rewardEs, boostCategory, multiplier,
                secondaryBoost, secondaryMultiplier, rushType, rushMinutes, supplyDropTier
        );
    }

    public static Map<Integer, NovemberDaySpec> loadSchedule(ConfigurationSection calendarSection) {
        Map<Integer, NovemberDaySpec> map = new LinkedHashMap<>();
        for (NovemberDaySpec base : defaultSpecs()) {
            NovemberDaySpec resolved = base;
            if (calendarSection != null) {
                ConfigurationSection daysSec = calendarSection.getConfigurationSection("days");
                if (daysSec != null) {
                    ConfigurationSection dSec = daysSec.getConfigurationSection(String.valueOf(base.day()));
                    if (dSec != null) {
                        boolean en = dSec.getBoolean("enabled", base.enabled());
                        LocalTime time = parseTime(dSec.getString("time", base.formattedTime()), base.scheduledTime());
                        int dur = Math.max(5, dSec.getInt("duration-minutes", base.durationMinutes()));
                        double mult = dSec.getDouble("multiplier", base.multiplier());
                        resolved = new NovemberDaySpec(
                                base.day(), time, dur, en, base.phase(),
                                dSec.getString("kind", base.kind()),
                                dSec.getString("name-es", base.nameEs()),
                                dSec.getString("name-en", base.nameEn()),
                                dSec.getString("reward-es", base.rewardEs()),
                                dSec.getString("boost-category", base.boostCategory()),
                                mult,
                                dSec.getString("secondary-boost", base.secondaryBoost()),
                                dSec.getDouble("secondary-multiplier", base.secondaryMultiplier()),
                                dSec.getString("rush-type", base.rushType()),
                                dSec.getInt("rush-minutes", base.rushMinutes()),
                                dSec.getString("supply-drop-tier", base.supplyDropTier())
                        );
                    }
                }
            }
            map.put(resolved.day(), resolved);
        }
        return Collections.unmodifiableMap(map);
    }

    private static LocalTime parseTime(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            String[] parts = raw.trim().split(":");
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static List<NovemberDaySpec> defaultSpecs() {
        return List.of(
            boost(1, 19, 0, 30, "otono", "Alba de Noviembre: Impulso de Sabiduría", "November Dawn: Wisdom Surge", "2.0x XP natural (30 min)", "XP", 2.0),
            rush(2, 20, 0, 20, "otono", "Papa-Maratón de Otoño", "Autumn Potato & Harvest Rush", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "HARVEST"),
            rush(3, 20, 0, 20, "otono", "Fiebre de Vetas Profundas", "Deepslate Vein Fever", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "MINING"),
            boost(4, 20, 0, 30, "otono", "Resonancia de Investigación Slimefun", "Slimefun Research Resonance", "2.0x Investigación Slimefun (30 min)", "SLIMEFUN", 2.0),
            rush(5, 20, 0, 20, "otono", "Torneo de Aguas Serenas", "Serene Waters Fishing Derby", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "FISHING"),
            boost(6, 21, 0, 30, "otono", "Ruta Mercante de Atenas", "Athenian Merchant Route", "1.5x Economía activa (tope 600 ₯/min)", "ECONOMIA", 1.5),
            drop(7, 21, 0, 20, "otono", "Suministros del Olimpo", "Olympus Supply Drop", "Cofre del Olimpo épico en chunk salvaje seguro", "epico"),
            boost(8, 19, 0, 35, "otono", "Vigilia Dominical de Atenea", "Athena's Sunday Vigil", "2.0x XP natural (35 min)", "XP", 2.0),
            rush(9, 20, 0, 20, "otono", "Expedición de Esmeraldas y Diamantes", "Emerald & Diamond Expedition", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "MINING"),
            boost(10, 20, 0, 30, "otono", "Brisa Dorada del Egeo", "Golden Aegean Breeze", "1.5x Dracmas activos (30 min)", "DRACMAS", 1.5),
            boost(11, 20, 0, 30, "otono", "Pulso Tecnológico 11/11", "11/11 Tech Pulse", "2.0x Investigación Slimefun (30 min)", "SLIMEFUN", 2.0),
            rush(12, 20, 0, 20, "otono", "Festival de Deméter", "Festival of Demeter", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "HARVEST"),
            comboRush(13, 21, 0, 30, "otono", "Noche Estelar de Cañas y Runas", "Starlight Rods & Runes Night", "2.0x XP + Podio Pesca Top 3", "XP", 2.0, "FISHING", 20),
            drop(14, 21, 0, 20, "otono", "Reliquia Caída de Orión", "Fallen Relic of Orion", "Cofre del Olimpo épico seguro", "epico"),
            boost(15, 19, 0, 40, "otono", "Mitad de Noviembre: Mercado del Sol", "Mid-November: Sun Market Surge", "2.0x Economía activa (40 min)", "ECONOMIA", 2.0),
            rush(16, 20, 0, 20, "otono", "Desafío de Escombros Ancestrales", "Ancient Debris Challenge", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "MINING"),
            boost(17, 20, 0, 30, "otono", "Códice de los Sabios", "Codex of the Sages", "2.0x XP natural (30 min)", "XP", 2.0),
            rush(18, 20, 0, 20, "otono", "Corriente del Tridente", "Trident Current Derby", "Podio Top 3: 10.000 / 6.000 / 3.000 Dracmas", "FISHING"),
            boost(19, 20, 0, 30, "otono", "Acelerador de Laboratorios", "Research Lab Accelerator", "2.0x Investigación Slimefun (30 min)", "SLIMEFUN", 2.0),
            comboRush(20, 21, 0, 30, "otono", "Banquete Preludio de Otoño", "Autumn Prelude Banquet", "2.0x Dracmas activos + Podio Cosecha", "DRACMAS", 2.0, "HARVEST", 20),
            drop(21, 21, 0, 20, "otono", "Heraldo Astral: Víspera de la Semana Real", "Astral Herald: Royal Week Eve", "Cofre del Olimpo épico seguro", "epico"),
            comboRush(22, 19, 0, 45, "semana_cumple", "★ Semana del Fundador (Día 1): Apertura Dorada", "★ Founder's Week (Day 1): Golden Opening", "2.5x XP (45m) + Maratón Minero", "XP", 2.5, "MINING", 20),
            comboRush(23, 20, 0, 45, "semana_cumple", "★ Semana del Fundador (Día 2): Marea Real de Poseidón", "★ Founder's Week (Day 2): Poseidon's Royal Tide", "2.0x Economía activa + Torneo de Pesca", "ECONOMIA", 2.0, "FISHING", 20),
            comboRush(24, 20, 0, 45, "semana_cumple", "★ Semana del Fundador (Día 3): Cosecha Imperial", "★ Founder's Week (Day 3): Imperial Harvest", "2.5x Dracmas activos + Papa-Maratón", "DRACMAS", 2.5, "HARVEST", 20),
            comboDualBoost(25, 20, 0, 45, "semana_cumple", "★ Semana del Fundador (Día 4): Forja Cuántica de Hefesto", "★ Founder's Week (Day 4): Hephaestus Quantum Forge", "2.5x Investigación Slimefun + 2.0x XP", "SLIMEFUN", 2.5, "XP", 2.0),
            comboDrop(26, 20, 0, 30, "semana_cumple", "★ Semana del Fundador (Día 5): Tesoro Astral de Star", "★ Founder's Week (Day 5): Star Astral Treasure", "Cofre Legendario + 2.5x Economía activa", "ECONOMIA", 2.5, "legendario"),
            comboRush(27, 21, 0, 45, "semana_cumple", "★ Semana del Fundador (Día 6): Vigilia de los Dragones", "★ Founder's Week (Day 6): Vigil of the Dragons", "3.0x XP natural (45m) + Minería (25m)", "XP", 3.0, "MINING", 25),
            comboDualBoost(28, 21, 0, 60, "semana_cumple", "★ Semana del Fundador (Día 7): Víspera Real 3X", "★ Founder's Week (Day 7): Royal Eve 3X", "3.0x XP + 3.0x Investigación Slimefun (60m)", "XP", 3.0, "SLIMEFUN", 3.0),
            gala(29, 20, 0, 30, "cumple_owner", "👑 Cierre Mayor: Cumpleaños de JackStar (Gala 5X)", "👑 Grand Finale: JackStar's Birthday Gala (5X)", "5X Global 24h + Gran Rush de Gala + Cofre Legendario"),
            boost(30, 20, 0, 30, "cierre_mes", "Epílogo de Noviembre: Rumbo al Solsticio", "November Epilogue: Road to Winter Solstice", "2.0x XP natural (30 min)", "XP", 2.0)
        );
    }

    private static NovemberDaySpec boost(int d, int h, int m, int dur, String ph, String es, String en, String rw, String cat, double mult) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "GLOBAL_BOOST", es, en, rw, cat, mult, "", 0, "", 0, "");
    }

    private static NovemberDaySpec rush(int d, int h, int m, int dur, String ph, String es, String en, String rw, String rType) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "RUSH_" + rType, es, en, rw, "", 0, "", 0, rType, dur, "");
    }

    private static NovemberDaySpec drop(int d, int h, int m, int dur, String ph, String es, String en, String rw, String tier) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "SUPPLY_DROP", es, en, rw, "", 0, "", 0, "", 0, tier);
    }

    private static NovemberDaySpec comboRush(int d, int h, int m, int dur, String ph, String es, String en, String rw, String cat, double mult, String rType, int rMin) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "COMBO", es, en, rw, cat, mult, "", 0, rType, rMin, "");
    }

    private static NovemberDaySpec comboDualBoost(int d, int h, int m, int dur, String ph, String es, String en, String rw, String cat1, double m1, String cat2, double m2) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "COMBO", es, en, rw, cat1, m1, cat2, m2, "", 0, "");
    }

    private static NovemberDaySpec comboDrop(int d, int h, int m, int dur, String ph, String es, String en, String rw, String cat, double mult, String tier) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "COMBO", es, en, rw, cat, mult, "", 0, "", 0, tier);
    }

    private static NovemberDaySpec gala(int d, int h, int m, int dur, String ph, String es, String en, String rw) {
        return new NovemberDaySpec(d, LocalTime.of(h, m), dur, true, ph, "COMBO", es, en, rw, "", 0, "", 0, "MINING", dur, "legendario");
    }
}
