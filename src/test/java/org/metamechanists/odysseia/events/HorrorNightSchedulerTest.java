package org.metamechanists.odysseia.events;

import org.bukkit.Difficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorrorNightSchedulerTest {

    private static final long INTERVAL = 15L * 60_000L;

    @Test
    void firstNightTriggers() {
        assertTrue(HorrorNightScheduler.shouldTrigger(null, 11442L, null, 1_000L, INTERVAL));
    }

    @Test
    void sameNightDoesNotRetrigger() {
        assertFalse(HorrorNightScheduler.shouldTrigger(11442L, 11442L, 0L, INTERVAL * 2, INTERVAL));
    }

    @Test
    void halloweenDayJumpWithinIntervalDoesNotRetrigger() {
        // setTime(18000) de Halloween abre un "día" nuevo cada ~2-4 minutos reales.
        long last = 1_000_000L;
        assertFalse(HorrorNightScheduler.shouldTrigger(11442L, 11443L, last, last + 144_000L, INTERVAL));
    }

    @Test
    void newNightAfterIntervalTriggers() {
        long last = 1_000_000L;
        assertTrue(HorrorNightScheduler.shouldTrigger(11442L, 11450L, last, last + INTERVAL, INTERVAL));
    }

    @Test
    void peacefulBlocksMonsterApparitions() {
        assertFalse(HorrorNightScheduler.permiteMonstruos(Difficulty.PEACEFUL));
        assertTrue(HorrorNightScheduler.permiteMonstruos(Difficulty.EASY));
        assertTrue(HorrorNightScheduler.permiteMonstruos(Difficulty.HARD));
    }
}
