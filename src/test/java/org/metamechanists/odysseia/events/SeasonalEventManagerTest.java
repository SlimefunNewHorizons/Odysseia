package org.metamechanists.odysseia.events;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SeasonalEventManagerTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private SeasonalEventManager manager;
    private static final NamespacedKey OPT_OUT_KEY = new NamespacedKey("odysseia", "orange_fog_disabled");

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        manager = new SeasonalEventManager(plugin, null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void disablingOrangeFogPersistsInPdcAndRemovesDarkness() {
        PlayerMock player = server.addPlayer("pacox77");
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0));
        assertTrue(player.hasPotionEffect(PotionEffectType.DARKNESS));

        manager.setOrangeFogDisabled(player.getUniqueId(), true);

        assertTrue(manager.isOrangeFogDisabled(player.getUniqueId()));
        assertTrue(player.getPersistentDataContainer().has(OPT_OUT_KEY, PersistentDataType.BYTE));
        assertFalse(player.hasPotionEffect(PotionEffectType.DARKNESS));

        // Re-enabling clears PDC
        manager.setOrangeFogDisabled(player.getUniqueId(), false);
        assertFalse(manager.isOrangeFogDisabled(player.getUniqueId()));
        assertFalse(player.getPersistentDataContainer().has(OPT_OUT_KEY, PersistentDataType.BYTE));
    }

    @Test
    void playerJoinLoadsPersistentPdcPreference() {
        PlayerMock player = server.addPlayer("Mr_Em1lio");
        player.getPersistentDataContainer().set(OPT_OUT_KEY, PersistentDataType.BYTE, (byte) 1);

        assertFalse(manager.isOrangeFogDisabled(player.getUniqueId()));

        manager.onPlayerJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        assertTrue(manager.isOrangeFogDisabled(player.getUniqueId()));

        manager.onPlayerQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
        assertFalse(manager.isOrangeFogDisabled(player.getUniqueId()));
    }

    @Test
    void islandWorldsDoNotApplyDarknessBlindnessDuringNight() {
        WorldMock bskyblock = server.addSimpleWorld("bskyblock_world");
        bskyblock.setTime(18000L); // Midnight

        PlayerMock player = server.addPlayer("SkyblockExplorer");
        player.teleport(bskyblock.getSpawnLocation());
        player.setGameMode(GameMode.SURVIVAL);

        // Force Halloween module active for testing
        manager.setForceHalloween(true);
        manager.tick();

        // Should NOT have darkness potion in skyblock
        assertFalse(player.hasPotionEffect(PotionEffectType.DARKNESS),
                "Players in island worlds must not be blinded by darkness over the void");
    }
}
