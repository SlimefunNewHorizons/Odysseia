package org.metamechanists.odysseia.events;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * La niebla (efecto DARKNESS y bruma de partículas) se QUITÓ de Halloween el 2026-10-23 a petición de Jack: quitar solo
 * el comando /niebla dejaba la niebla naranja activa cada noche. Este test impide que vuelva.
 */
class SeasonalEventManagerTest {

    private ServerMock server;
    private SeasonalEventManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        JavaPlugin plugin = mock(JavaPlugin.class);
        manager = new SeasonalEventManager(plugin, null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void assertNoFog(String worldName, long time, boolean storm) {
        WorldMock world = server.addSimpleWorld(worldName);
        world.setTime(time);
        world.setStorm(storm);
        PlayerMock player = server.addPlayer("Jugador_" + worldName);
        player.teleport(world.getSpawnLocation());
        player.setGameMode(GameMode.SURVIVAL);
        manager.setForceHalloween(true);
        manager.tick();
        assertFalse(player.hasPotionEffect(PotionEffectType.DARKNESS),
                "Halloween no debe aplicar niebla/oscuridad (" + worldName + ", t=" + time + ", tormenta=" + storm + ")");
    }

    @Test
    void halloweenNeverAppliesDarknessAtNight() {
        assertNoFog("world", 18000L, false);
    }

    @Test
    void halloweenNeverAppliesDarknessInStorms() {
        assertNoFog("world_tormenta", 6000L, true);
    }

    @Test
    void halloweenNeverAppliesDarknessInIslandWorlds() {
        assertNoFog("bskyblock_world", 18000L, false);
        assertNoFog("oneblock_world", 18000L, true);
        assertNoFog("onechunk_world", 18000L, false);
    }

    @Test
    void noFogToggleApiRemains() {
        for (String metodo : new String[] { "toggleOrangeFog", "setOrangeFogDisabled", "isOrangeFogDisabled" }) {
            for (java.lang.reflect.Method m : SeasonalEventManager.class.getMethods()) {
                assertFalse(m.getName().equals(metodo), "el interruptor de niebla ya no existe: " + metodo);
            }
        }
    }
}
