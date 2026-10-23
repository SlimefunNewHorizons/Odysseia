package org.metamechanists.odysseia.events;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Contenido extra de Halloween 2026 (Herobrine real, Luna de Sangre más frecuente, sustos nuevos, truco o trato).
 *
 * <p>Todo se apaga solo en cuanto el mes deja de ser octubre: {@link #activo} se evalúa en CADA uso (no al arrancar), así que
 * el 1 de noviembre se desactiva sin reiniciar el servidor. Un interruptor {@code halloween-plus.enabled} lo apaga antes.
 * La lógica de decisión está aquí como funciones puras para poder probarla sin servidor.
 */
public final class HalloweenPlus {

    /** Tipos de susto de la Noche de Horror de siempre. */
    public static final int SUSTOS_BASE = 7;
    /** Tipos adicionales que solo existen en octubre (Herobrine real, lluvia de sangre, ojos en la oscuridad, murciélagos). */
    public static final int SUSTOS_EXTRA = 4;

    private static volatile Boolean forzado;

    private HalloweenPlus() {
    }

    /** Solo para pruebas: fuerza el estado (null = usar el calendario). */
    static void forzar(Boolean valor) {
        forzado = valor;
    }

    public static boolean enOctubre(LocalDate hoy) {
        return hoy.getMonth() == Month.OCTOBER;
    }

    /** ¿Está activo el contenido extra ahora mismo? Interruptor del config Y mes de octubre en la zona horaria del servidor. */
    public static boolean activo(FileConfiguration config) {
        Boolean f = forzado;
        if (f != null) {
            return f;
        }
        if (config != null && !config.getBoolean("halloween-plus.enabled", true)) {
            return false;
        }
        return enOctubre(LocalDate.now(zona(config)));
    }

    static ZoneId zona(FileConfiguration config) {
        try {
            return ZoneId.of(config == null ? "America/Santiago" : config.getString("chatgames.timezone", "America/Santiago"));
        } catch (Exception e) {
            return ZoneId.of("America/Santiago");
        }
    }

    /** Cada cuántas noches toca Luna de Sangre. Fuera de octubre, el valor normal; en octubre el de halloween-plus si es mayor que 0. */
    public static int cadaNochesLunaDeSangre(boolean activo, int normal, int octubre) {
        return activo && octubre > 0 ? octubre : normal;
    }

    /** Minutos mínimos entre secuencias de horror: en octubre, el menor de los dos. */
    public static long intervaloHorrorMinutos(boolean activo, long normal, long octubre) {
        return activo && octubre >= 0 ? Math.min(normal, octubre) : normal;
    }

    /** Eventos de susto por noche: el base más los extra de octubre. */
    public static int eventosPorNoche(boolean activo, int baseAleatorio, int extra) {
        return activo ? baseAleatorio + Math.max(0, extra) : baseAleatorio;
    }

    /** Cuántos tipos de susto se pueden sortear (11 en octubre, 7 el resto del año). */
    public static int tiposDeSusto(boolean activo) {
        return activo ? SUSTOS_BASE + SUSTOS_EXTRA : SUSTOS_BASE;
    }
}
