package org.metamechanists.odysseia.listeners;

import java.io.File;
import java.util.List;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.metamechanists.odysseia.Odysseia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class IslandFallSafetyListenerTest {
    private YamlConfiguration config;
    private Player player;
    private World world;
    private EntityDamageEvent event;
    private IslandFallSafetyListener listener;

    @BeforeEach
    void setup() throws Exception {
        config = new YamlConfiguration();
        config.load(new File("src/main/resources/config.yml"));
        Odysseia plugin = mock(Odysseia.class);
        when(plugin.getConfig()).thenReturn(config);
        player = mock(Player.class);
        world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("onechunk_world");
        event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        listener = new IslandFallSafetyListener(plugin);
    }

    @Test
    void preservesPreviousBehaviourUntilEnabled() {
        assertFalse(config.getBoolean("island-fall-safety.enabled"));
        listener.onFallDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
        verify(player, never()).setFallDistance(anyFloat());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bskyblock_world", "bskyblock_world_nether", "bskyblock_world_the_end",
            "oneblock_world", "oneblock_world_nether", "oneblock_world_the_end",
            "onechunk_world", "onechunk_world_nether", "onechunk_world_the_end"})
    void cancelsFallInEveryConfiguredDimension(String worldName) {
        config.set("island-fall-safety.enabled", true);
        when(world.getName()).thenReturn(worldName);
        listener.onFallDamage(event);
        verify(event).setCancelled(true);
        verify(player).setFallDistance(0.0f);
        verify(player, never()).teleportAsync(any());
        verify(player, never()).performCommand(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"world", "clasico", "world_lab_flat", "spawnwarps",
            "onechunk_world_backup", "custom_oneblock_world", "skyblock_arena"})
    void doesNotMatchUnlistedWorldsOrSubstrings(String worldName) {
        config.set("island-fall-safety.enabled", true);
        when(world.getName()).thenReturn(worldName);
        listener.onFallDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
        verify(player, never()).setFallDistance(anyFloat());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VOID", "WORLD_BORDER", "ENTITY_ATTACK", "FIRE", "LAVA"})
    void preservesOtherDamageCauses(String cause) {
        config.set("island-fall-safety.enabled", true);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.valueOf(cause));
        listener.onFallDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void doesNotProtectMobs() {
        config.set("island-fall-safety.enabled", true);
        when(event.getEntity()).thenReturn(mock(Entity.class));
        listener.onFallDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void observesConfigurationChangesWithoutRecreatingTheListener() {
        config.set("island-fall-safety.enabled", true);
        config.set("island-fall-safety.worlds", List.of("ONECHUNK_WORLD"));
        listener.onFallDamage(event);
        verify(event).setCancelled(true);
        clearInvocations(event, player);
        config.set("island-fall-safety.enabled", false);
        listener.onFallDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
        verify(player, never()).setFallDistance(anyFloat());
    }

    @Test
    void missingWorldListDoesNotGrantProtection() {
        config.set("island-fall-safety.enabled", true);
        config.set("island-fall-safety.worlds", null);
        assertTrue(config.getStringList("island-fall-safety.worlds").isEmpty());
        listener.onFallDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
    }
}
