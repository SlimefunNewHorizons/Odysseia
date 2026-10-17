package org.metamechanists.odysseia;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsMergeTest {

    @Test
    void productionConfigContainsNativeAutomationAndWelcomeBook() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File("src/main/resources/config.yml"));

        assertTrue(config.getBoolean("automation-guard.enabled"));
        assertEquals(40, config.getInt("automation-guard.redstone.fast-pulse-limit"));
        assertEquals(180, config.getInt("automation-guard.redstone.long-pulse-limit"));
        assertEquals(3, config.getInt("automation-guard.redstone.violations-before-break"));
        assertTrue(config.getBoolean("translation.enabled"));
        assertEquals("inicial", config.getString("starter-kit.kit"));
        assertEquals(400L, config.getLong("starter-kit.delay-ticks"));
        assertTrue(config.getConfigurationSection("starter-kit.items") == null);
        assertTrue(config.getStringList("starter-kit.commands").isEmpty());
        assertTrue(config.getBoolean("discord-translator.translate-discord-to-mc"));
        assertTrue(config.getBoolean("discord-translator.translate-mc-to-discord"));
        assertEquals("https://translate.drakescraft.cl", config.getString("discord-translator.api-url"));
        assertTrue(config.getStringList("sfmaster-audit.approved-addons").contains("SLIMEFUN"));
        assertTrue(config.getStringList("sfmaster-audit.blocked-addons").contains("INFINITYEXPANSION"));
        assertEquals("atlas", config.getString("protectionstones.aliases.overworld_1001"));
        assertEquals("nethercolossus", config.getString("protectionstones.aliases.nether_501"));
        assertTrue(config.getConfigurationSection("sfmaster-policy") == null);
        assertTrue(config.getConfigurationSection("restart") == null);
        assertEquals("oldschool", config.getString("kits.oldschool.protection-alias", ""));
        for (String kit : config.getConfigurationSection("kits").getKeys(false)) {
            String protectionKey = config.getString("kits." + kit + ".protection-alias", "").trim();
            if (!protectionKey.isEmpty()) {
                assertTrue(config.isString("protectionstones.aliases." + protectionKey),
                        () -> "Kit " + kit + " apunta a una ProtectionStone inexistente: " + protectionKey);
            }
        }
        assertEquals(6, config.getMapList("kits.inicial.vanilla-items").stream()
                .filter(item -> "WRITTEN_BOOK".equals(item.get("material")))
                .map(item -> ((java.util.List<?>) item.get("pages")).size())
                .findFirst()
                .orElse(0));
    }

    @Test
    void fillsExplicitlyEmptySectionsWithoutReplacingProductionValues() throws Exception {
        YamlConfiguration current = new YamlConfiguration();
        current.loadFromString("kits:\n  hermes: {}\nnative-menus:\n  shop:\n    title: Produccion\n    entries: {}\n");

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("kits:\n  hermes:\n    permission: drakes.kit.hermes\nnative-menus:\n  shop:\n    title: Default\n    entries:\n      slimefun:\n        slot: 40\n");

        assertTrue(Odysseia.mergeMissingConfig(current, defaults));
        assertEquals("drakes.kit.hermes", current.getString("kits.hermes.permission"));
        assertEquals(40, current.getInt("native-menus.shop.entries.slimefun.slot"));
        assertEquals("Produccion", current.getString("native-menus.shop.title"));
    }

    @Test
    void migratesOnlyKnownUnsafeAntigravityDefaults() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                starter-kit:
                  delay-ticks: 100
                  commands: [ps give novato player 1]
                  items: [{material: STONE_SWORD}]
                translation:
                  join-delay-ticks: 60
                discord-translator:
                  translate-mc-to-discord: true
                automation-guard:
                  redstone:
                    fast-pulse-limit: 12
                    long-window-seconds: 600
                    long-pulse-limit: 8
                """);

        assertTrue(Odysseia.migrateUnsafeLegacyDefaults(config));
        assertEquals(400L, config.getLong("starter-kit.delay-ticks"));
        assertTrue(config.getConfigurationSection("starter-kit.items") == null);
        assertTrue(config.getStringList("starter-kit.commands").isEmpty());
        assertEquals(400L, config.getLong("translation.join-delay-ticks"));
        assertTrue(config.getBoolean("discord-translator.translate-mc-to-discord"));
        assertEquals(40, config.getInt("automation-guard.redstone.fast-pulse-limit"));
        assertEquals(120, config.getInt("automation-guard.redstone.long-window-seconds"));
        assertEquals(180, config.getInt("automation-guard.redstone.long-pulse-limit"));
    }

    /**
     * Una lista que ya existe vacia en produccion no se rellenaba con el default del JAR, asi que
     * el guard de modalidades arranco con la lista vacia de la version anterior y la fuga de items
     * siguio abierta despues del reinicio.
     */
    @Test
    void emptyProductionListsAdoptTheDefaultFromTheJar() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.load(new File("src/main/resources/config.yml"));

        YamlConfiguration produccion = new YamlConfiguration();
        produccion.set("modalidades.guard.comandos-bloqueados", new java.util.ArrayList<String>());
        produccion.set("modalidades.guard.comandos-boveda", java.util.List.of("pv"));
        produccion.set("modalidades.guard.modalidades-aisladas", new java.util.ArrayList<String>());

        assertTrue(Odysseia.adoptEmptyListDefaults(produccion, defaults));
        assertTrue(produccion.getStringList("modalidades.guard.comandos-bloqueados").contains("team echest"),
                "la lista vacia debe adoptar el default seguro del JAR");
        assertFalse(produccion.getStringList("modalidades.guard.comandos-bloqueados").contains("ah"),
                "CrazyAuctions ya separa sus mercados por modalidad");
        assertEquals(java.util.List.of("pv"),
                produccion.getStringList("modalidades.guard.comandos-boveda"),
                "una lista ya configurada no se pisa");
        assertEquals(java.util.List.of("skyblock", "oneblock", "onechunk", "clasico", "laboratorio"),
                produccion.getStringList("modalidades.guard.modalidades-aisladas"),
                "las modalidades aisladas no pueden quedar abiertas por una lista vacia");
    }

    @Test
    void adoptingIsIdempotent() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.load(new File("src/main/resources/config.yml"));
        YamlConfiguration produccion = new YamlConfiguration();
        produccion.set("modalidades.guard.comandos-bloqueados", new java.util.ArrayList<String>());

        assertTrue(Odysseia.adoptEmptyListDefaults(produccion, defaults));
        assertFalse(Odysseia.adoptEmptyListDefaults(produccion, defaults),
                "una segunda pasada no debe marcar cambios");
    }

    @Test
    void addsClassicToAnExistingProductionIsolationList() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.load(new File("src/main/resources/config.yml"));
        YamlConfiguration production = new YamlConfiguration();
        production.set("modalidades.guard.modalidades-aisladas", java.util.List.of("skyblock", "oneblock", "evento"));

        assertTrue(Odysseia.ensureRequiredModalityDefaults(production, defaults));
        assertEquals(java.util.List.of("skyblock", "oneblock", "evento", "onechunk", "clasico", "laboratorio"),
                production.getStringList("modalidades.guard.modalidades-aisladas"));
        assertFalse(Odysseia.ensureRequiredModalityDefaults(production, defaults));
    }
}
