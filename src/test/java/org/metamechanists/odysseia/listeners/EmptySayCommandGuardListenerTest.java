package org.metamechanists.odysseia.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class EmptySayCommandGuardListenerTest {

    @Test
    void identifiesOnlyEmptySayCommands() {
        assertTrue(EmptySayCommandGuardListener.isEmptySay("say"));
        assertTrue(EmptySayCommandGuardListener.isEmptySay(" minecraft:say  "));
        assertFalse(EmptySayCommandGuardListener.isEmptySay("say maintenance soon"));
        assertFalse(EmptySayCommandGuardListener.isEmptySay("broadcast say"));
        assertFalse(EmptySayCommandGuardListener.isEmptySay(null));
    }
}
