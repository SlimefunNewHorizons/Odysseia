package org.metamechanists.odysseia.kits;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cada modalidad debe poder entregar su propio kit de bienvenida.
 *
 * El kit inicial se entregaba una sola vez por cuenta, asi que quien ya lo habia recibido en
 * Survival llegaba a SkyBlock y OneBlock sin nada. Lo reporto un jugador en vivo.
 */
class ModalityStarterKitTest {

    private static final YamlConfiguration CONFIG =
            YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

    @Test
    void everyModalityKitExistsInTheKitsSection() {
        ConfigurationSection porModalidad = CONFIG.getConfigurationSection("starter-kit.por-modalidad");
        assertNotNull(porModalidad, "starter-kit.por-modalidad debe existir");
        for (String modalidad : porModalidad.getKeys(false)) {
            String kit = porModalidad.getString(modalidad, "");
            if (kit.isBlank()) continue;
            assertNotNull(CONFIG.getConfigurationSection("kits." + kit),
                    "la modalidad '" + modalidad + "' promete el kit '" + kit + "' y no existe en kits");
        }
    }

    @Test
    void everyModalityKitTargetsADeclaredModality() {
        ConfigurationSection modos = CONFIG.getConfigurationSection("modalidades.modos");
        ConfigurationSection porModalidad = CONFIG.getConfigurationSection("starter-kit.por-modalidad");
        for (String modalidad : porModalidad.getKeys(false)) {
            assertNotNull(modos.getConfigurationSection(modalidad),
                    "starter-kit.por-modalidad apunta a una modalidad inexistente: " + modalidad);
        }
    }

    @Test
    void islandKitsDoNotHandOutProtectionStones() {
        // Dentro de las islas protege BentoBox; una piedra ahi no sirve y se perderia.
        for (String kit : new String[]{"inicial-skyblock", "inicial-oneblock", "inicial-onechunk"}) {
            assertTrue(CONFIG.getString("kits." + kit + ".protection-alias", "").isBlank(),
                    kit + " no deberia entregar proteccion");
        }
    }

    @Test
    void optionalNewKitsContainOnlyVanillaItems() {
        for (String kit : new String[]{"inicial-clasico", "inicial-onechunk"}) {
            assertNotNull(CONFIG.getConfigurationSection("kits." + kit));
            for (var item : CONFIG.getMapList("kits." + kit + ".vanilla-items")) {
                assertFalse(item.containsKey("slimefun-item"), kit + " must not leak Slimefun items");
                assertFalse(item.containsKey("special-item"), kit + " must use vanilla items");
            }
        }
    }

    @Test
    void firstVisitToAModalityDoesNotDependOnHavingPlayedBefore() {
        // La regla de la modalidad mide la entrada a ese mundo, no la primera vez en el servidor.
        assertTrue(StarterKitPolicy.shouldEnrollInModality(false, false, false));
        assertFalse(StarterKitPolicy.shouldEnrollInModality(false, true, false), "ya entregado");
        assertFalse(StarterKitPolicy.shouldEnrollInModality(false, false, true), "ya reclamado");
    }

    @Test
    void theBaseModalityKeepsItsOwnFirstJoinRule() {
        assertTrue(StarterKitPolicy.shouldEnroll(false, false, false, false), "cuenta nueva");
        assertFalse(StarterKitPolicy.shouldEnroll(true, false, false, false), "veterano sin pendiente");
        assertTrue(StarterKitPolicy.shouldEnroll(true, true, false, false), "veterano con entrega pendiente");
    }
}
