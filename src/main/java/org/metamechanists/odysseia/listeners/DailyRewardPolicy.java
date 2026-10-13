package org.metamechanists.odysseia.listeners;

/**
 * Regla de la recompensa diaria, sin Bukkit para poder probarla (incluida la reconexion).
 *
 * <p>Hay UN pago por jugador cada 24 h, sin importar cuantas veces entre y salga. La racha sube si el pago anterior fue
 * hace menos de 48 h y vuelve a 1 si paso mas. Ticket #352: al revisar el log se vieron varios "eco give 1000" al mismo
 * jugador en una hora; NO eran este pago (los pagos de la diaria son de 300/1000/3000/7500/20000 una vez al dia) sino
 * recompensas de DrakesCrates/Oraculo; esta prueba deja fijado que la diaria no se repite al reconectar.
 */
public final class DailyRewardPolicy {

    public static final long DAY_SECONDS = 86_400L;

    public record Result(boolean pay, int streak, int amount) {
    }

    private DailyRewardPolicy() {
    }

    public static Result evaluate(long last, int previousStreak, long now) {
        if (last != 0L && now - last < DAY_SECONDS) {
            return new Result(false, previousStreak, 0);
        }
        int streak = last == 0L || now - last > DAY_SECONDS * 2 ? 1 : previousStreak + 1;
        return new Result(true, streak, amountFor(streak));
    }

    public static int amountFor(int streak) {
        return streak >= 30 ? 20_000 : streak >= 14 ? 7_500 : streak >= 7 ? 3_000 : streak >= 3 ? 1_000 : 300;
    }
}
