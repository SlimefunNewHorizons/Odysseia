package org.metamechanists.odysseia.listeners;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Cada rango con aura en ArmorEffectsListener tiene que tener su seccion en el config.yml publicado.
 *
 * Una seccion ausente no falla de forma ruidosa: el rango se queda sin efectos (peor que un rango
 * inferior) y la consola repite un WARN. Asi se perdieron los rangos Atenea a Titan Caos en 6539ccf.
 */
class ArmorEffectsConfigTest {

    private static final Path LISTENER = Path.of(
            "src/main/java/org/metamechanists/odysseia/listeners/ArmorEffectsListener.java");

    @Test
    void todoRangoDelListenerEstaDeclarado() throws Exception {
        Matcher matcher = Pattern.compile("addConfiguredEffects\\(effectsToApply, \"([a-z]+)\"\\)")
                .matcher(Files.readString(LISTENER));
        Set<String> usados = new HashSet<>();
        while (matcher.find()) usados.add(matcher.group(1));
        assertEquals(ArmorEffectsListener.AURA_RANKS, usados);
    }

    @Test
    void todoRangoTieneAuraConfigurada() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File("src/main/resources/config.yml"));

        for (String rank : ArmorEffectsListener.AURA_RANKS) {
            ConfigurationSection section = config.getConfigurationSection("armor-effects." + rank);
            assertNotNull(section, "falta armor-effects." + rank);
            assertFalse(ArmorEffectsListener.configuredEffectLevels(section).isEmpty(),
                    "armor-effects." + rank + " no otorga ningun efecto");
        }
    }
}
