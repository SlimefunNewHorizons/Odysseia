package org.metamechanists.odysseia.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ResourcePackListenerTest {

    @Test
    void promptTraduceCodigosAmpersand() {
        assertEquals("§bDrakesCraft §7ofrece texturas. §aEs opcional§7.",
                ResourcePackListener.colorize("&bDrakesCraft &7ofrece texturas. &aEs opcional&7."));
    }

    @Test
    void promptConSeccionONuloSeConserva() {
        assertEquals("§bYa traducido", ResourcePackListener.colorize("§bYa traducido"));
        assertNull(ResourcePackListener.colorize(null));
    }
}
