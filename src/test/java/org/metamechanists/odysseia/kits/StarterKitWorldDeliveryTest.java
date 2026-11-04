package org.metamechanists.odysseia.kits;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.listeners.ServerAutomationListener;
import org.metamechanists.odysseia.modalities.ModalityService;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StarterKitWorldDeliveryTest {
    @TempDir Path data;
    ServerMock server;
    PlayerMock player;
    WorldMock lobby, survival, island;
    ServerAutomationListener listener;
    YamlConfiguration config;
    KitClaimService claims;

    @BeforeEach
    void setup() throws Exception {
        server = MockBukkit.mock();
        lobby = server.addSimpleWorld("lobby");
        survival = server.addSimpleWorld("world");
        island = server.addSimpleWorld("bskyblock_world");
        player = server.addPlayer();
        player.teleport(lobby.getSpawnLocation());
        config = new YamlConfiguration();
        config.set("translation.enabled", false);
        config.set("starter-kit.base-modality", "survival");
        config.set("starter-kit.delay-ticks", 20);
        config.set("starter-kit.por-modalidad.skyblock", "inicial-skyblock");
        config.set("modalidades.modos.survival.mundos", List.of("world"));
        config.set("modalidades.modos.skyblock.mundos", List.of("bskyblock_world"));
        for (String kit : List.of("inicial", "inicial-skyblock")) {
            config.set("kits." + kit + ".cooldown", "-1");
            config.set("kits." + kit + ".vanilla-items", List.of(java.util.Map.of("material", "STONE")));
        }
        var rewards = new YamlConfiguration();
        rewards.set("players." + player.getUniqueId() + ".last", Instant.now().getEpochSecond());
        rewards.save(data.resolve("daily-rewards.yml").toFile());
        Odysseia plugin = mock(Odysseia.class);
        when(plugin.getName()).thenReturn("Odysseia");
        when(plugin.namespace()).thenReturn("odysseia");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("starter-kit-test"));
        claims = new KitClaimService(plugin);
        var modalities = new ModalityService(plugin);
        when(plugin.getKitClaimService()).thenReturn(claims);
        when(plugin.getModalityService()).thenReturn(modalities);
        listener = new ServerAutomationListener(plugin);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private void enter(WorldMock world) {
        var from = player.getWorld();
        player.teleport(world.getSpawnLocation());
        listener.onModalityChange(new PlayerChangedWorldEvent(player, from));
    }

    private int items() {
        return java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(java.util.Objects::nonNull).mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum();
    }

    @Test void lobbyWaitsForSurvivalAndReturningDoesNotDuplicate() {
        listener.onJoin(new PlayerJoinEvent(player, (String) null));
        server.getScheduler().performTicks(25);
        assertEquals(0, items(), "fallback Survival must not deliver in the lobby");
        enter(survival);
        server.getScheduler().performTicks(25);
        assertEquals(1, items());
        enter(lobby);
        enter(survival);
        server.getScheduler().performTicks(25);
        assertEquals(1, items(), "a delivered kit is permanent");
    }

    @Test void leavingBeforeDeliveryKeepsKitPendingUntilTheCorrectWorld() {
        enter(island);
        enter(lobby);
        server.getScheduler().performTicks(25);
        assertEquals(0, items(), "the delayed island kit must not leak into the lobby");
        enter(island);
        server.getScheduler().performTicks(25);
        assertEquals(1, items());
    }

    @Test void reconnectInAnIslandResumesItsPendingKit() {
        player.teleport(island.getSpawnLocation());
        listener.onJoin(new PlayerJoinEvent(player, (String) null));
        server.getScheduler().performTicks(25);
        assertEquals(1, items());
        assertFalse(player.getPersistentDataContainer().has(
                new NamespacedKey("odysseia", "starter_kit_delivered"), PersistentDataType.BYTE));
    }

    @Test void legacyBaseDeliveryIsNeverGrantedAgain() {
        player.getPersistentDataContainer().set(new NamespacedKey("odysseia", "received_starter_kit"),
                PersistentDataType.BYTE, (byte) 1);
        enter(survival);
        server.getScheduler().performTicks(25);
        assertEquals(0, items());
    }

    @Test void repeatedEntriesWithPendingTasksOnlyDeliverOnce() {
        enter(survival);
        enter(lobby);
        enter(survival);
        server.getScheduler().performTicks(25);
        assertEquals(1, items());
    }

    @Test void baseAndIslandClaimsAreIndependent() {
        enter(survival);
        server.getScheduler().performTicks(25);
        enter(island);
        server.getScheduler().performTicks(25);
        assertEquals(2, items());
        enter(survival);
        enter(island);
        server.getScheduler().performTicks(25);
        assertEquals(2, items());
    }

    @Test void persistedClaimPreventsDeliveryWithoutAPdcFlag() {
        assertTrue(claims.record(player.getUniqueId(), "inicial"));
        enter(survival);
        server.getScheduler().performTicks(25);
        assertEquals(0, items());
    }

    @Test void disabledStarterKitsDoNotEnrollOrDeliver() {
        config.set("starter-kit.enabled", false);
        listener.onJoin(new PlayerJoinEvent(player, (String) null));
        enter(survival);
        enter(island);
        server.getScheduler().performTicks(25);
        assertEquals(0, items());
        assertFalse(player.getPersistentDataContainer().has(
                new NamespacedKey("odysseia", "starter_kit_pending"), PersistentDataType.BYTE));
    }
}
