package org.metamechanists.odysseia.integrations;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SlimefunPkgTest {

    @Test
    void sinSlimefunLanzaClassNotFoundYNoUnNullPointer() {
        assertThrows(ClassNotFoundException.class, () -> SlimefunPkg.forName("api.items.NoExiste"));
    }
}
