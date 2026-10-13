package org.metamechanists.odysseia.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DailyRewardPolicyTest {

    private static final long T0 = 1_790_000_000L;

    @Test
    void primerPagoEsDe300ConRacha1() {
        var r = DailyRewardPolicy.evaluate(0L, 0, T0);
        assertTrue(r.pay());
        assertEquals(1, r.streak());
        assertEquals(300, r.amount());
    }

    @Test
    void reconectarVariasVecesEnElMismoDiaNoVuelveAPagar() {
        long ultimo = T0;
        for (long minutos : new long[] { 1, 2, 23, 60, 600, 1439 }) {
            var r = DailyRewardPolicy.evaluate(ultimo, 1, T0 + minutos * 60);
            assertFalse(r.pay(), "no debe pagar a los " + minutos + " min");
            assertEquals(0, r.amount());
        }
    }

    @Test
    void alPasar24HorasPagaYSubeLaRacha() {
        var r = DailyRewardPolicy.evaluate(T0, 2, T0 + DailyRewardPolicy.DAY_SECONDS);
        assertTrue(r.pay());
        assertEquals(3, r.streak());
        assertEquals(1_000, r.amount());
    }

    @Test
    void siPasanMasDe48HorasLaRachaVuelveA1() {
        var r = DailyRewardPolicy.evaluate(T0, 9, T0 + DailyRewardPolicy.DAY_SECONDS * 2 + 1);
        assertTrue(r.pay());
        assertEquals(1, r.streak());
        assertEquals(300, r.amount());
    }

    @Test
    void escalonDeMontos() {
        assertEquals(300, DailyRewardPolicy.amountFor(2));
        assertEquals(1_000, DailyRewardPolicy.amountFor(3));
        assertEquals(3_000, DailyRewardPolicy.amountFor(7));
        assertEquals(7_500, DailyRewardPolicy.amountFor(14));
        assertEquals(20_000, DailyRewardPolicy.amountFor(30));
    }
}
