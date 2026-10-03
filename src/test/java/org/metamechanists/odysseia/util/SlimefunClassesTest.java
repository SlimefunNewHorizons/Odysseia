package org.metamechanists.odysseia.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La busqueda de clases de Slimefun por reflexion.
 *
 * En la rama 26.x varias integraciones solo buscaban el paquete del fork propio y, con el Slimefun
 * universal de staging, quedaban inertes en silencio: /sell aceptaba items de Slimefun a precio
 * vanilla y el limite de spawners no se aplicaba.
 */
class SlimefunClassesTest {

    @Test
    void saltaCandidatosAusentesYDevuelveElPrimeroCargable() throws ClassNotFoundException {
        Class<?> found = SlimefunClasses.first(List.of("no.existe.Clase", "java.lang.String", "java.lang.Integer"));
        assertSame(String.class, found);
    }

    @Test
    void sinCandidatosCargablesLanzaClassNotFoundConTodosLosNombres() {
        ClassNotFoundException error = assertThrows(ClassNotFoundException.class,
                () -> SlimefunClasses.first(List.of("no.existe.A", "no.existe.B")));
        assertEquals("no.existe.A | no.existe.B", error.getMessage());
    }

    @Test
    void cadaClaseTieneVarianteUniversalYPropia() {
        for (List<String> candidates : List.of(SlimefunClasses.SLIMEFUN_ITEM,
                SlimefunClasses.SLIMEFUN_ADDON, SlimefunClasses.BLOCK_STORAGE)) {
            assertTrue(candidates.get(0).startsWith("io.github.thebusybiscuit.")
                    || candidates.get(0).startsWith("me.mrCookieSlime."), candidates.get(0));
            assertTrue(candidates.stream().anyMatch(name -> name.startsWith("com.github.drakescraft_labs.")));
        }
    }
}
