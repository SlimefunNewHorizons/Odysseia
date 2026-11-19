package org.metamechanists.odysseia.papa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PapaTraderWorldPolicyTest {

    private static final List<String> WORLDS = List.of("world", "world_nether", "world_the_end");

    @Test
    void aceptaUnicamenteMundosExactosDeSlimefun() {
        assertTrue(PapaTraderMenu.mundoPermitido("world", WORLDS));
        assertTrue(PapaTraderMenu.mundoPermitido("WORLD_NETHER", WORLDS));
        assertFalse(PapaTraderMenu.mundoPermitido("SpawnWarps", WORLDS));
        assertFalse(PapaTraderMenu.mundoPermitido("laboratorio", WORLDS));
        assertFalse(PapaTraderMenu.mundoPermitido("world_laboratorio", WORLDS));
    }

    @Test
    void excluyeLaboratorioDeEntregaPeriodicaDePapaDeMar() {
        List<String> excluidos = List.of("laboratorio", "world_lab_flat");
        List<String> lab = List.of("laboratorio", "world_lab_flat");
        assertFalse(PapaDeMarService.debeRecibirEntrega("laboratorio", excluidos, lab));
        assertFalse(PapaDeMarService.debeRecibirEntrega("LABORATORIO", List.of(), List.of()));
        assertFalse(PapaDeMarService.debeRecibirEntrega("world_lab_flat", List.of(), List.of()));
        assertFalse(PapaDeMarService.debeRecibirEntrega("custom_excluded", List.of("custom_excluded"), List.of()));
        assertTrue(PapaDeMarService.debeRecibirEntrega("world", excluidos, lab));
        assertTrue(PapaDeMarService.debeRecibirEntrega("bskyblock_world", excluidos, lab));
        assertTrue(PapaDeMarService.debeRecibirEntrega("oneblock_world", excluidos, lab));
    }
}
